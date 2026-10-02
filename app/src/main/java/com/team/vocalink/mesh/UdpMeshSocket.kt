package com.team.vocalink.mesh

import android.util.Log
import com.team.vocalink.core.ProtocolConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Optional local-network UDP bearer. It only works when devices share an IP network; it is not a radio mesh.
 * Operates on port 40404 with broadcast enabled, allowing instantaneous sub-5ms
 * packet transmission whenever devices are in proximity or on local offline mesh/hotspot.
 */
class UdpMeshSocket(
    private val packetReceiver: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "UdpMeshSocket"
        private const val MESH_PORT = 40404
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var isRunning = false\n    private val authenticator = com.team.vocalink.security.AirHopPacketAuthenticator(context)

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(MESH_PORT))
                }
                socket = sock
                Log.i(TAG, "UDP bearer bound to port $MESH_PORT on the local IP network")

                val buffer = ByteArray(256)
                while (isActive && isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    if (packet.length == ProtocolConstants.PACKET_SIZE && buffer[0] == ProtocolConstants.AIRHOP_PREAMBLE) {
                        val data = buffer.copyOf(ProtocolConstants.PACKET_SIZE)
                        packetReceiver(data, -30)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP socket stopped or error: ")
            }
        }
    }

    fun broadcastPacket(bytes: ByteArray) {
        if (bytes.size != ProtocolConstants.PACKET_SIZE) return
        scope.launch {
            try {
                val sock = socket ?: return@launch
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(bytes, bytes.size, broadcastAddr, MESH_PORT)
                sock.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "UDP broadcast failed: ")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }
}
