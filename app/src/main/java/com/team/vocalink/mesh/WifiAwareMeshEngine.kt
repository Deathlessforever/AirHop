package com.team.vocalink.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Build
import android.util.Log
import com.team.vocalink.core.ProtocolConstants\nimport com.team.vocalink.security.AirHopPacketAuthenticator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Aware (NAN - Neighbor Awareness Networking) High-Throughput Fallback Engine:
 * Discovers nearby peer nodes in high-density cluster environments without access points or cellular backhaul.
 * Enables burst transmission of phonemic voice tokens and multi-hop data when BLE airtime is congested.
 */
class WifiAwareMeshEngine(
    private val context: Context,
    private val packetReceiver: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "WifiAwareMeshEngine"
        private const val AWARE_SERVICE_NAME = "AirHopDisasterMesh"
    }

    private val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private var awareSession: WifiAwareSession? = null
    private var publishDiscoverySession: PublishDiscoverySession? = null
    private var subscribeDiscoverySession: SubscribeDiscoverySession? = null\n    private val authenticator = AirHopPacketAuthenticator(context)

    private val _isAwareAvailable = MutableStateFlow(false)
    val isAwareAvailable: StateFlow<Boolean> = _isAwareAvailable.asStateFlow()

    private val _awarePeerCount = MutableStateFlow(0)
    val awarePeerCount: StateFlow<Int> = _awarePeerCount.asStateFlow()

    private val discoveredPeers = ConcurrentHashMap<PeerHandle, Long>()

    private val awareStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED) {
                checkAwareAvailability()
            }
        }
    }

    init {
        checkHardwareSupport()
    }

    private fun checkHardwareSupport() {
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        val isAvail = wifiAwareManager?.isAvailable == true
        _isAwareAvailable.value = hasFeature && isAvail
        Log.i(TAG, "Wi-Fi Aware support: hasFeature=$hasFeature, isAvailable=$isAvail")
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            Log.w(TAG, "Wi-Fi Aware hardware feature not present on device")
            return
        }

        context.registerReceiver(
            awareStateReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
        )

        attachToAwareService()
    }

    fun stop() {
        try {
            context.unregisterReceiver(awareStateReceiver)
        } catch (_: Exception) {}

        publishDiscoverySession?.close()
        publishDiscoverySession = null
        subscribeDiscoverySession?.close()
        subscribeDiscoverySession = null
        awareSession?.close()
        awareSession = null
        discoveredPeers.clear()
        _awarePeerCount.value = 0
    }

    private fun checkAwareAvailability() {
        val available = wifiAwareManager?.isAvailable == true
        _isAwareAvailable.value = available
        if (available && awareSession == null) {
            attachToAwareService()
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachToAwareService() {
        val manager = wifiAwareManager ?: return
        if (!manager.isAvailable) return

        try {
            manager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession?) {
                    awareSession = session
                    Log.i(TAG, "Wi-Fi Aware session attached successfully")
                    startPublishing()
                    startSubscribing()
                }

                override fun onAttachFailed() {
                    Log.w(TAG, "Wi-Fi Aware attach failed")
                    awareSession = null
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Exception attaching to Wi-Fi Aware", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPublishing() {
        val session = awareSession ?: return

        val config = PublishConfig.Builder()
            .setServiceName(AWARE_SERVICE_NAME)
            .build()

        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                publishDiscoverySession = session
                Log.i(TAG, "Wi-Fi Aware publish started for service '$AWARE_SERVICE_NAME'")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingMessage(peerHandle, message)
            }
        }, null)
    }

    @SuppressLint("MissingPermission")
    private fun startSubscribing() {
        val session = awareSession ?: return

        val config = SubscribeConfig.Builder()
            .setServiceName(AWARE_SERVICE_NAME)
            .build()

        session.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                subscribeDiscoverySession = session
                Log.i(TAG, "Wi-Fi Aware subscribe started for service '$AWARE_SERVICE_NAME'")
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: MutableList<ByteArray>?
            ) {
                discoveredPeers[peerHandle] = System.currentTimeMillis()
                _awarePeerCount.value = discoveredPeers.size
                Log.i(TAG, "Discovered Wi-Fi Aware peer. Active cluster count: ${discoveredPeers.size}")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingMessage(peerHandle, message)
            }
        }, null)
    }

    private fun handleIncomingMessage(peerHandle: PeerHandle, message: ByteArray) {
        discoveredPeers[peerHandle] = System.currentTimeMillis()
        _awarePeerCount.value = discoveredPeers.size

        if (message.size == ProtocolConstants.PACKET_SIZE &&
            message[0] == ProtocolConstants.AIRHOP_PREAMBLE) {
            packetReceiver(message)
        }
    }

    /**
     * Sends a 40-byte AirHop packet to all discovered Wi-Fi Aware cluster peers.
     */
    fun sendBurstPacket(packet40Bytes: ByteArray) {
        if (packet40Bytes.size != ProtocolConstants.PACKET_SIZE) return\n        val secure = authenticator.wrap(packet40Bytes)

        val session = publishDiscoverySession ?: subscribeDiscoverySession ?: return
        val now = System.currentTimeMillis()

        // Clean stale peers (> 60s)
        discoveredPeers.entries.removeIf { now - it.value > 60_000L }
        _awarePeerCount.value = discoveredPeers.size

        for (peer in discoveredPeers.keys) {
            try {
                session.sendMessage(peer, 1, secure)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send burst packet to peer $peer", e)
            }
        }
    }
}
