package com.team.vocalink.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.util.Log
import com.team.vocalink.core.ProtocolConstants
import com.team.vocalink.security.AirHopPacketAuthenticator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Wi-Fi Aware transport.
 *
 * Discovery messages are used for control-plane negotiation/fallback.
 * AirHop frames use a peer-specific Wi-Fi Aware network socket whenever one
 * is established. Discovery remains a compatibility fallback for peers that
 * cannot complete socket negotiation.
 */
class WifiAwareMeshEngine(
    private val context: Context,
    private val packetReceiver: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "WifiAwareMeshEngine"
        private const val SERVICE_NAME = "AirHopDisasterMesh"
        private const val HELLO_TEXT = "AIRHOP/1 HELLO"
        private const val READY_PREFIX = "AIRHOP/1 READY "
        private const val FRAME_BYTES = AirHopPacketAuthenticator.SECURE_FRAME_SIZE
        private const val LINK_IDLE_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
    }

    private val manager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val auth = AirHopPacketAuthenticator(context)
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool()

    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    private val peers = ConcurrentHashMap<PeerHandle, Long>()
    private val links = ConcurrentHashMap<PeerHandle, Link>()
    private var serverSocket: ServerSocket? = null
    private var serverPort = 0

    private val _available = MutableStateFlow(false)
    val isAwareAvailable: StateFlow<Boolean> = _available.asStateFlow()
    private val _peerCount = MutableStateFlow(0)
    val awarePeerCount: StateFlow<Int> = _peerCount.asStateFlow()

    private data class Link(
        val peer: PeerHandle,
        val socket: Socket,
        val output: BufferedOutputStream,
        @Volatile var lastUsed: Long = System.currentTimeMillis()
    )

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED) refresh()
        }
    }

    init {
        refresh()
    }

    private fun refresh() {
        _available.value =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            manager?.isAvailable == true
    }

    private fun remember(peer: PeerHandle) {
        val now = System.currentTimeMillis()
        peers[peer] = now
        peers.entries.removeIf { now - it.value > LINK_IDLE_MS }
        links.entries.removeIf {
            if (now - it.value.lastUsed > LINK_IDLE_MS) {
                closeLink(it.value)
                true
            } else false
        }
        _peerCount.value = peers.size
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !_available.value) return

        try {
            context.registerReceiver(
                stateReceiver,
                IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
            )
        } catch (_: Exception) {}

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

        links.values.forEach { closeLink(it) }
        links.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverPort = 0

        publishSession?.close()
        subscribeSession?.close()
        awareSession?.close()
        publishSession = null
        subscribeSession = null
        awareSession = null

        peers.clear()
        _peerCount.value = 0
        ioExecutor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    private fun publish() {
        val session = awareSession ?: return
        session.publish(
            PublishConfig.Builder().setServiceName(SERVICE_NAME).build(),
            object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    remember(peerHandle)
                    val text = message.decodeToString()
                    when {
                        text == HELLO_TEXT -> establishPublisherLink(peerHandle)
                        text.startsWith(READY_PREFIX) ->
                            establishSubscriberLink(
                                peerHandle,
                                text.removePrefix(READY_PREFIX)
                            )
                        else -> deliverDiscoveryFrame(message)
                    }
                }
            },
            null
        )
    }

    @SuppressLint("MissingPermission")
    private fun subscribe() {
        val session = awareSession ?: return
        session.subscribe(
            SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build(),
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
                    try {
                        session.sendMessage(peerHandle, 1, HELLO_TEXT.toByteArray())
                    } catch (e: Exception) {
                        Log.w(TAG, "Wi-Fi Aware HELLO failed", e)
                    }
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    remember(peerHandle)
                    val text = message.decodeToString()
                    if (text.startsWith(READY_PREFIX)) {
                        establishSubscriberLink(
                            peerHandle,
                            text.removePrefix(READY_PREFIX)
                        )
                    } else {
                        deliverDiscoveryFrame(message)
                    }
                }
            },
            null
        )
    }

    private fun deliverDiscoveryFrame(message: ByteArray) {
        val packet = auth.unwrap(message) ?: return
        if (packet.size == ProtocolConstants.PACKET_SIZE &&
            packet[0] == ProtocolConstants.AIRHOP_PREAMBLE
        ) {
            packetReceiver(packet)
        }
    }

    @SuppressLint("MissingPermission")
    private fun establishPublisherLink(peer: PeerHandle) {
        if (links.containsKey(peer)) return

        if (serverSocket == null) {
            try {
                serverSocket = ServerSocket(0)
                serverPort = serverSocket!!.localPort
                val listener = serverSocket!!
                ioExecutor.execute {
                    while (!listener.isClosed) {
                        try {
                            val socket = listener.accept()
                            ioExecutor.execute { acceptSocket(socket) }
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi Aware server socket failed", e)
                return
            }
        }

        val publish = publishSession ?: return
        val specifier = WifiAwareNetworkSpecifier.Builder(publish, peer)
            .setPskPassphrase(psk())
            .setPort(serverPort)
            .build()

        requestAwareNetwork(specifier) {
            val ready = (READY_PREFIX + serverPort).toByteArray()
            try {
                publish.sendMessage(peer, 2, ready)
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi Aware READY failed", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun establishSubscriberLink(peer: PeerHandle, portText: String) {
        if (links.containsKey(peer)) return
        val port = portText.toIntOrNull() ?: return
        val subscribe = subscribeSession ?: return

        val specifier = WifiAwareNetworkSpecifier.Builder(subscribe, peer)
            .setPskPassphrase(psk())
            .setPort(port)
            .build()

        requestAwareNetwork(specifier) { network ->
            val awareInfo = network.transportInfo as? WifiAwareNetworkInfo ?: return@requestAwareNetwork
            val address = awareInfo.peerIpv6Addr ?: return@requestAwareNetwork

            ioExecutor.execute {
                try {
                    val socket = network.socketFactory.createSocket()
                    socket.connect(java.net.InetSocketAddress(address, awareInfo.port), CONNECT_TIMEOUT_MS)
                    installLink(peer, socket)
                } catch (e: Exception) {
                    Log.w(TAG, "Wi-Fi Aware socket connect failed", e)
                }
            }
        }
    }

    private fun acceptSocket(socket: Socket) {
        try {
            val peer = peers.keys.firstOrNull { !links.containsKey(it) }
            if (peer == null) {
                socket.close()
                return
            }
            installLink(peer, socket)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            Log.w(TAG, "Wi-Fi Aware socket accept failed", e)
        }
    }

    private fun installLink(peer: PeerHandle, socket: Socket) {
        socket.tcpNoDelay = true
        val link = Link(peer, socket, BufferedOutputStream(socket.getOutputStream()))
        val previous = links.put(peer, link)
        if (previous != null) closeLink(previous)
        ioExecutor.execute { readLoop(link) }
    }

    private fun readLoop(link: Link) {
        try {
            val input = BufferedInputStream(link.socket.getInputStream())
            val header = ByteArray(2)
            while (!link.socket.isClosed) {
                if (!readFully(input, header)) break
                val size = ((header[0].toInt() and 0xFF) shl 8) or
                    (header[1].toInt() and 0xFF)
                if (size != FRAME_BYTES) {
                    if (size <= 0 || size > 1024) break
                    val discarded = ByteArray(size)
                    if (!readFully(input, discarded)) break
                    continue
                }

                val frame = ByteArray(FRAME_BYTES)
                if (!readFully(input, frame)) break
                val packet = auth.unwrap(frame) ?: continue

                if (packet.size == ProtocolConstants.PACKET_SIZE &&
                    packet[0] == ProtocolConstants.AIRHOP_PREAMBLE
                ) {
                    link.lastUsed = System.currentTimeMillis()
                    packetReceiver(packet)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Wi-Fi Aware link closed: " + e.message)
        } finally {
            links.remove(link.peer, link)
            closeLink(link)
        }
    }

    private fun readFully(input: BufferedInputStream, target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) return false
            if (count == 0) continue
            offset += count
        }
        return true
    }

    fun sendBurstPacket(packet40Bytes: ByteArray) {
        if (packet40Bytes.size != ProtocolConstants.PACKET_SIZE) return
        val frame = auth.wrap(packet40Bytes)
        val now = System.currentTimeMillis()

        peers.entries.removeIf { now - it.value > LINK_IDLE_MS }
        links.entries.removeIf {
            if (now - it.value.lastUsed > LINK_IDLE_MS) {
                closeLink(it.value)
                true
            } else false
        }
        _peerCount.value = peers.size

        var sent = false
        for (link in links.values) {
            try {
                synchronized(link) {
                    link.output.write((frame.size ushr 8) and 0xFF)
                    link.output.write(frame.size and 0xFF)
                    link.output.write(frame)
                    link.output.flush()
                    link.lastUsed = now
                }
                sent = true
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi Aware socket send failed", e)
                links.remove(link.peer, link)
                closeLink(link)
            }
        }

        if (!sent) {
            val publisher = publishSession
            val subscriber = subscribeSession
            for (peer in peers.keys) {
                try {
                    (publisher ?: subscriber)?.sendMessage(peer, 3, frame)
                } catch (e: Exception) {
                    Log.w(TAG, "Wi-Fi Aware discovery fallback failed", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestAwareNetwork(
        specifier: WifiAwareNetworkSpecifier,
        onAvailable: (Network) -> Unit
    ) {
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        connectivity.requestNetwork(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onAvailable(network)
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Wi-Fi Aware network lost: " + network.networkHandle)
                }
            }
        )
    }

    private fun psk(): String =
        auth.exportKey().replace("=", "").take(63).padEnd(8, '0')

    private fun closeLink(link: Link) {
        try { link.output.close() } catch (_: Exception) {}
        try { link.socket.close() } catch (_: Exception) {}
    }
}
