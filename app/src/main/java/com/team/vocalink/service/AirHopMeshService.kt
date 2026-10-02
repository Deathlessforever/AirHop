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
    lateinit var nodePresenceDirectory: com.team.vocalink.mesh.NodePresenceDirectory
        private set

    private val serviceJob = kotlinx.coroutines.SupervisorJob()
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + serviceJob)

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

        try {
        // Initialize Core Engines
        geofenceManager = GeofenceManager(this)
        nodePresenceDirectory = com.team.vocalink.mesh.NodePresenceDirectory(this)
        dndAlertManager = DndBypassAlertManager(this)
        neuralTtsHook = NeuralTtsHook(this)
        offlineTtsEngine = com.team.vocalink.alert.OfflineTtsEngine(this)

        // Construct the relay dispatcher before radio callbacks can fire.
        // A radio callback may arrive immediately after start(), so capturing
        // an uninitialized lateinit property here is unsafe.
        bleMeshEngine = BleMeshEngine(this) { rawPacket, rssi ->
            if (::blindRelayManager.isInitialized) blindRelayManager.onRawPacketScanned(rawPacket, rssi)
        }
        wifiAwareEngine = WifiAwareMeshEngine(this) { rawPacket ->
            if (::blindRelayManager.isInitialized) blindRelayManager.onRawPacketScanned(rawPacket, -50)
        }

        blindRelayManager = BlindRelayManager(
            bleMeshEngine = bleMeshEngine,
            geofenceManager = geofenceManager,
            dndBypassAlertManager = dndAlertManager,
            neuralTtsHook = neuralTtsHook,
            context = this
        )

        bleMeshEngine.setSecondaryBroadcaster { packet ->
            if (::wifiAwareEngine.isInitialized) wifiAwareEngine.sendBurstPacket(packet)
        }
        bleMeshEngine.setPresenceLocationProvider {
            val loc = geofenceManager.currentLocation.value
            if (loc == null || !nodePresenceDirectory.visible) null else Triple(loc.latitude, loc.longitude, true)
        }
        bleMeshEngine.setPresenceObserver { id, lat, lon, rssi ->
            nodePresenceDirectory.observe(id, lat, lon, rssi, true)
        }

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
        } catch (e: Exception) {
            Log.e(TAG, "Mesh initialization failed", e)
            instance = null
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun startMeshEngines() {
        try { geofenceManager.start() } catch (e: Exception) { Log.e(TAG, "Location engine start failed", e) }
        try { bleMeshEngine.start() } catch (e: Exception) { Log.e(TAG, "BLE engine start failed", e) }
        try { wifiAwareEngine.start() } catch (e: Exception) { Log.e(TAG, "Wi-Fi Aware engine start failed", e) }
    }

    private fun stopMeshEngines() {
        try { if (::audioIngestEngine.isInitialized) audioIngestEngine.stopIngest() } catch (e: Exception) { Log.w(TAG, "Audio stop failed", e) }
        try { if (::bleMeshEngine.isInitialized) bleMeshEngine.stop() } catch (e: Exception) { Log.w(TAG, "BLE stop failed", e) }
        try { if (::wifiAwareEngine.isInitialized) wifiAwareEngine.stop() } catch (e: Exception) { Log.w(TAG, "Wi-Fi Aware stop failed", e) }
        try { if (::geofenceManager.isInitialized) geofenceManager.stop() } catch (e: Exception) { Log.w(TAG, "Location stop failed", e) }
        try { if (::dndAlertManager.isInitialized) dndAlertManager.stopAlarm() } catch (e: Exception) { Log.w(TAG, "Alert stop failed", e) }
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
        serviceJob.cancel()
        stopMeshEngines()
        try { if (::chatManager.isInitialized) chatManager.close() } catch (e: Exception) { Log.w(TAG, "Chat shutdown failed", e) }
        try { if (::offlineTtsEngine.isInitialized) offlineTtsEngine.shutdown() } catch (e: Exception) { Log.w(TAG, "TTS shutdown failed", e) }
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        instance = null
        super.onDestroy()
        Log.i(TAG, "AirHopMeshService destroyed")
    }
}
