package com.team.vocalink.mesh

import android.content.Context
import android.util.Log
import com.team.vocalink.security.AirHopPacketAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class UdpMeshSocket(
    private val context: Context,
    private val packetReceiver: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "UdpMeshSocket"
        private const val MESH_PORT = 40404
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val authenticator = AirHopPacketAuthenticator(context)
    private var socket: DatagramSocket? = null
    private var isRunning = false

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
                val buffer = ByteArray(AirHopPacketAuthenticator.SECURE_FRAME_SIZE)
                while (isActive && isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    if (packet.length == AirHopPacketAuthenticator.SECURE_FRAME_SIZE) {
                        val secure = buffer.copyOf()
                        val data = authenticator.unwrap(secure) ?: continue
                        packetReceiver(data, -30)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "UDP bearer stopped", e)
            }
        }
    }

    fun broadcastPacket(secureFrame: ByteArray) {
        if (secureFrame.size != AirHopPacketAuthenticator.SECURE_FRAME_SIZE) return
        scope.launch {
            try {
                val sock = socket ?: return@launch
                val address = InetAddress.getByName("255.255.255.255")
                sock.send(DatagramPacket(secureFrame, secureFrame.size, address, MESH_PORT))
            } catch (e: Exception) {
                Log.w(TAG, "UDP broadcast failed", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
