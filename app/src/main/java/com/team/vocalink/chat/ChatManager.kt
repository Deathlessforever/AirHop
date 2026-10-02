package com.team.vocalink.chat

import android.content.Context
import android.util.Log
import com.team.vocalink.alert.OfflineTtsEngine
import com.team.vocalink.core.AirHopNative
import com.team.vocalink.core.ChatMessage
import com.team.vocalink.core.DisasterPhraseCodebook
import com.team.vocalink.core.MessageStatus
import com.team.vocalink.core.PacketRepairResult
import com.team.vocalink.core.ProtocolConstants
import com.team.vocalink.mesh.BleMeshEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatManager(
    private val context: Context,
    private val bleMeshEngine: BleMeshEngine,
    val offlineTtsEngine: OfflineTtsEngine,
    var onMessageSent: ((Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "ChatManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Event for when a blue tick delivery occurs
    private val _deliveryEvent = MutableSharedFlow<Int>()
    val deliveryEvent: SharedFlow<Int> = _deliveryEvent.asSharedFlow()

    fun sendMessage(
        text: String,
        phraseId: Int = 1,
        isSos: Boolean = false,
        lang: Byte = ProtocolConstants.LANG_ENGLISH,
        lat: Double = ProtocolConstants.BENCHMARK_MYSURU_LAT,
        lon: Double = ProtocolConstants.BENCHMARK_MYSURU_LON
    ) {
        val tokens = DisasterPhraseCodebook.encodeTextToTokens(text, phraseId)
        var flags = lang.toInt()
        if (isSos) flags = flags or ProtocolConstants.FLAG_EMERGENCY_SOS.toInt()

        val latE7 = (lat * 1e7).toInt()
        val lonE7 = (lon * 1e7).toInt()
        val now = System.currentTimeMillis()

        val packetBytes = AirHopNative.encodePacket(
            flags = flags.toByte(),
            ttl = ProtocolConstants.DEFAULT_TTL,
            targetZone = 0x01,
            latE7 = latE7,
            lonE7 = lonE7,
            tokens = tokens,
            timestampMs = now
        )

        // Extract msgId from bytes 3..6
        val msgId = java.nio.ByteBuffer.wrap(packetBytes, 3, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int

        onMessageSent?.invoke(msgId)

        val chatMsg = ChatMessage(
            id = msgId,
            text = text,
            senderName = "You",
            isFromMe = true,
            timestamp = now,
            lat = lat,
            lon = lon,
            hopCount = 1,
            status = MessageStatus.SENT
        )

        _messages.value = _messages.value + chatMsg

        // Broadcast over BLE + UDP mesh
        bleMeshEngine.broadcastPacket(packetBytes)
        Log.i(TAG, "Sent message $msgId over offline mesh: '$text'")
    }

    fun handleIncomingPacket(repairResult: PacketRepairResult) {
        val flags = repairResult.flags
        val msgId = repairResult.msgId
        val targetZone = repairResult.targetZone
        val isAck = (flags and ProtocolConstants.FLAG_ACK.toInt()) != 0

        if (isAck) {
            // This is an ACK delivery confirmation from User 2!
            val acknowledgedMsgId = targetZone
            scope.launch {
                val current = _messages.value.toMutableList()
                val index = current.indexOfFirst { it.id == acknowledgedMsgId && it.isFromMe }
                if (index != -1) {
                    val old = current[index]
                    val latency = System.currentTimeMillis() - old.timestamp
                    current[index] = old.copy(
                        status = MessageStatus.DELIVERED,
                        latencyMs = latency
                    )
                    _messages.value = current
                    _deliveryEvent.emit(acknowledgedMsgId)
                    Log.i(TAG, "BLUE TICK CONFIRMED for message ! Latency: ms")
                }
            }
            return
        }

        // Normal message received from peer (User 1 or User 2)
        scope.launch {
            val existing = _messages.value.find { it.id == msgId }
            if (existing != null) return@launch // Already have it

            val tokens = repairResult.tokens ?: ByteArray(13)
            val lang = (flags and ProtocolConstants.FLAG_LANG_MASK.toInt()).toByte()
            val text = DisasterPhraseCodebook.decodeText(tokens, lang)

            val incoming = ChatMessage(
                id = msgId,
                text = text,
                senderName = "Peer Node",
                isFromMe = false,
                timestamp = System.currentTimeMillis(),
                lat = repairResult.latitude,
                lon = repairResult.longitude,
                hopCount = (ProtocolConstants.DEFAULT_TTL - repairResult.ttl) + 1,
                status = MessageStatus.DELIVERED
            )

            _messages.value = _messages.value + incoming
            Log.i(TAG, "Received message from peer: ''")

            // Speak aloud automatically on User 2's phone!
            offlineTtsEngine.speak(text, lang)

            // Send Delivery ACK packet back over mesh so User 1 gets the Blue Tick!
            sendAckPacket(msgId, lang)
        }
    }

    private fun sendAckPacket(targetMsgId: Int, lang: Byte) {
        val ackFlags = (ProtocolConstants.FLAG_ACK.toInt() or lang.toInt()).toByte()
        val emptyTokens = ByteArray(13)
        val ackBytes = AirHopNative.encodePacket(
            flags = ackFlags,
            ttl = 5.toByte(),
            targetZone = targetMsgId, // Target message to acknowledge
            latE7 = 0,
            lonE7 = 0,
            tokens = emptyTokens,
            timestampMs = System.currentTimeMillis()
        )
        val ackMsgId = java.nio.ByteBuffer.wrap(ackBytes, 3, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        onMessageSent?.invoke(ackMsgId)
        bleMeshEngine.broadcastPacket(ackBytes)
        Log.i(TAG, "Dispatched delivery ACK for message $targetMsgId (ackId=$ackMsgId) over mesh")
    }
}
