package com.team.vocalink.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.util.Log
import com.team.vocalink.core.ProtocolConstants
import com.team.vocalink.security.AirHopPacketAuthenticator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class WifiAwareMeshEngine(
    private val context: Context,
    private val packetReceiver: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "WifiAwareMeshEngine"
        private const val SERVICE_NAME = "AirHopDisasterMesh"
    }

    private val manager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val auth = AirHopPacketAuthenticator(context)
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val peers = ConcurrentHashMap<PeerHandle, Long>()

    private val _available = MutableStateFlow(false)
    val isAwareAvailable: StateFlow<Boolean> = _available.asStateFlow()
    private val _peerCount = MutableStateFlow(0)
    val awarePeerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED) refresh()
        }
    }

    init { refresh() }

    private fun refresh() {
        _available.value = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            manager?.isAvailable == true
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !_available.value) return
        try { context.registerReceiver(stateReceiver, IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)) } catch (_: Exception) {}
        if (awareSession != null) return
        manager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession?) {
                awareSession = session
                publish()
                subscribe()
            }
            override fun onAttachFailed() {
                awareSession = null
                Log.w(TAG, "Wi-Fi Aware attach failed")
            }
        }, null)
    }

    fun stop() {
        try { context.unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()
        publishSession = null
        subscribeSession = null
        awareSession = null
        peers.clear()
        _peerCount.value = 0
    }

    private fun remember(peer: PeerHandle) {
        peers[peer] = System.currentTimeMillis()
        val cutoff = System.currentTimeMillis() - 60_000L
        peers.entries.removeIf { it.value < cutoff }
        _peerCount.value = peers.size
    }

    @SuppressLint("MissingPermission")
    private fun publish() {
        val session = awareSession ?: return
        session.publish(PublishConfig.Builder().setServiceName(SERVICE_NAME).build(),
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                }
                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    remember(peerHandle)
                    val packet = auth.unwrap(message) ?: return
                    if (packet.size == ProtocolConstants.PACKET_SIZE &&
                        packet[0] == ProtocolConstants.AIRHOP_PREAMBLE) packetReceiver(packet)
                }
            }, null)
    }

    @SuppressLint("MissingPermission")
    private fun subscribe() {
        val session = awareSession ?: return
        session.subscribe(SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build(),
            object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                }
                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray?,
                    matchFilter: MutableList<ByteArray>?
                ) {
                    remember(peerHandle)
                }
                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    remember(peerHandle)
                    val packet = auth.unwrap(message) ?: return
                    if (packet.size == ProtocolConstants.PACKET_SIZE &&
                        packet[0] == ProtocolConstants.AIRHOP_PREAMBLE) packetReceiver(packet)
                }
            }, null)
    }

    @SuppressLint("MissingPermission")
    fun sendBurstPacket(packet40Bytes: ByteArray) {
        if (packet40Bytes.size != ProtocolConstants.PACKET_SIZE) return
        val payload = auth.wrap(packet40Bytes)
        val now = System.currentTimeMillis()
        peers.entries.removeIf { now - it.value > 60_000L }
        _peerCount.value = peers.size
        val publisher = publishSession
        val subscriber = subscribeSession
        for (peer in peers.keys) {
            try {
                (publisher ?: subscriber)?.sendMessage(peer, 1, payload)
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi Aware discovery message failed", e)
            }
        }
    }
}
