package com.team.vocalink.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.team.vocalink.alert.DisasterLogStore
import com.team.vocalink.alert.DndBypassAlertManager
import com.team.vocalink.alert.GeofenceManager
import com.team.vocalink.alert.NeuralTtsHook
import com.team.vocalink.audio.AudioIngestEngine
import com.team.vocalink.core.ProtocolConstants
import com.team.vocalink.mesh.BleMeshEngine
import com.team.vocalink.mesh.BlindRelayManager
import com.team.vocalink.mesh.WifiAwareMeshEngine
import com.team.vocalink.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground Service maintaining the offline disaster mesh radio stack:
 * BLE Coded PHY, Wi-Fi Aware NAN discovery, blind relay dispatcher, and GPS/NavIC geofencing.
 */
class AirHopMeshService : Service() {

    companion object {
        private const val TAG = "AirHopMeshService"
        private const val NOTIFICATION_ID = 26173
        private const val CHANNEL_ID = "airhop_disaster_mesh_channel"

        const val ACTION_START = "com.team.vocalink.action.START"
        const val ACTION_STOP = "com.team.vocalink.action.STOP"

        var instance: AirHopMeshService? = null
            private set
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var geofenceManager: GeofenceManager
        private set
    lateinit var dndAlertManager: DndBypassAlertManager
        private set
    lateinit var neuralTtsHook: NeuralTtsHook
        private set
    lateinit var bleMeshEngine: BleMeshEngine
        private set
    lateinit var wifiAwareEngine: WifiAwareMeshEngine
        private set
    lateinit var blindRelayManager: BlindRelayManager
        private set
    lateinit var audioIngestEngine: AudioIngestEngine
        private set
    lateinit var disasterLogStore: DisasterLogStore
        private set
    lateinit var offlineTtsEngine: com.team.vocalink.alert.OfflineTtsEngine
        private set
    lateinit var chatManager: com.team.vocalink.chat.ChatManager
        private set

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): AirHopMeshService = this@AirHopMeshService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        instance = this

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirHop:DisasterMeshWakeLock")
        wakeLock?.acquire()

        createNotificationChannel()

        // Enter the foreground before initializing the radio stack.
        // This avoids long engine initialization consuming the Android
        // foreground-service startup window.
        startForegroundServiceNotification()

        // Initialize Core Engines
        geofenceManager = GeofenceManager(this)
        dndAlertManager = DndBypassAlertManager(this)
        neuralTtsHook = NeuralTtsHook(this)
        offlineTtsEngine = com.team.vocalink.alert.OfflineTtsEngine(this)

        bleMeshEngine = BleMeshEngine(this) { rawPacket, rssi ->
            blindRelayManager.onRawPacketScanned(rawPacket, rssi)
        }

        wifiAwareEngine = WifiAwareMeshEngine(this) { rawPacket ->
            blindRelayManager.onRawPacketScanned(rawPacket, -50)
        }

        bleMeshEngine.setSecondaryBroadcaster { packet -> wifiAwareEngine.sendBurstPacket(packet) }\n\n        blindRelayManager = BlindRelayManager(
            bleMeshEngine = bleMeshEngine,
            geofenceManager = geofenceManager,
            dndBypassAlertManager = dndAlertManager,
            neuralTtsHook = neuralTtsHook,
            context = this
        )

        chatManager = com.team.vocalink.chat.ChatManager(this, bleMeshEngine, offlineTtsEngine) { msgId ->
            blindRelayManager.registerSentMessageId(msgId)
        }

        blindRelayManager.onPacketDecoded = { repairResult ->
            chatManager.handleIncomingPacket(repairResult)
        }

        audioIngestEngine = AudioIngestEngine(this) { tokens ->
            val loc = geofenceManager.currentLocation.value
            val latE7 = ((loc?.latitude ?: 0.0) * 1e7).toInt()
            val lonE7 = ((loc?.longitude ?: 0.0) * 1e7).toInt()

            blindRelayManager.broadcastOriginPacket(
                flags = ProtocolConstants.LANG_KANNADA,
                ttl = ProtocolConstants.DEFAULT_TTL,
                targetZone = 0,
                latE7 = latE7,
                lonE7 = lonE7,
                tokens = tokens
            )
        }

        disasterLogStore = com.team.vocalink.alert.DisasterLogStore(this)

        serviceScope.launch {
            blindRelayManager.waterfallEvents.collect { logItem ->
                disasterLogStore.logPacket(logItem)
            }
        }

        startMeshEngines()
        Log.i(TAG, "AirHopMeshService initialized with offline DisasterLogStore")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun startMeshEngines() {
        geofenceManager.start()
        bleMeshEngine.start()
        wifiAwareEngine.start()
    }

    private fun stopMeshEngines() {
        audioIngestEngine.stopIngest()
        bleMeshEngine.stop()
        wifiAwareEngine.stop()
        geofenceManager.stop()
        dndAlertManager.stopAlarm()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirHop Disaster Mesh Transceiver",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Offline Coded PHY & Wi-Fi Aware Disaster Emergency Transceiver"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirHop Disaster Transceiver Active")
            .setContentText("AirHop mesh relay active — waiting for nearby devices")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMeshEngines()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        instance = null
        Log.i(TAG, "AirHopMeshService destroyed")
    }
}
