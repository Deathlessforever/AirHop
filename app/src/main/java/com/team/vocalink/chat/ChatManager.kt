package com.team.vocalink.chat

import android.content.Context
import android.util.Log
import android.util.Base64
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import com.team.vocalink.alert.OfflineTtsEngine
import com.team.vocalink.core.AirHopNative
import com.team.vocalink.core.AirHopTextAssembler
import com.team.vocalink.core.AirHopTextFragments
import com.team.vocalink.core.ChatMessage
import com.team.vocalink.core.DisasterPhraseCodebook
import com.team.vocalink.core.MessageStatus
import com.team.vocalink.core.PacketRepairResult
import com.team.vocalink.core.ProtocolConstants
import com.team.vocalink.mesh.BleMeshEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
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

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val prefs = context.getSharedPreferences("airhop_messages", Context.MODE_PRIVATE)
    private val nodeIdentity = com.team.vocalink.core.NodeIdentity(context)
    private val textAssembler = AirHopTextAssembler()
    private val storageKey = "messages_v1"
    private val outboxKey = "outbox_v1"

    private val _messages = MutableStateFlow<List<ChatMessage>>(loadMessages())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Event for when a blue tick delivery occurs
    private val _deliveryEvent = MutableSharedFlow<Int>()
    val deliveryEvent: SharedFlow<Int> = _deliveryEvent.asSharedFlow()

    init {
        val raw = prefs.getString(outboxKey, null)
        val array = raw?.let { runCatching { JSONArray(it) }.getOrNull() }
        if (array != null) for (i in 0 until array.length()) retryOutbox(array.getJSONObject(i).optInt("id"))
    }

    fun close() { scope.cancel() }

    fun sendMessage(
        text: String,
        phraseId: Int = 1,
        isSos: Boolean = false,
        lang: Byte = ProtocolConstants.LANG_ENGLISH,
        lat: Double = 0.0,
        lon: Double = 0.0,
        destinationId: Int = 0
    ): Boolean {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val isFragmentedText = phraseId == 0xFE && textBytes.size > 12
        if (isFragmentedText && textBytes.size > AirHopTextFragments.MAX_TEXT_BYTES) {
            Log.w(TAG, "Rejected free-form message larger than ${AirHopTextFragments.MAX_TEXT_BYTES} UTF-8 bytes")
            return false
        }

        val transferId = if (isFragmentedText) {
            java.util.UUID.randomUUID().leastSignificantBits.toInt()
        } else {
            null
        }
        val tokenFrames = if (transferId != null) {
            AirHopTextFragments.encode(text, transferId) ?: return false
        } else {
            listOf(DisasterPhraseCodebook.encodeTextToTokens(text, phraseId))
        }

        var flags = lang.toInt()
        if (isSos) flags = flags or ProtocolConstants.FLAG_EMERGENCY_SOS.toInt()
        val latE7 = (lat * 1e7).toInt()
        val lonE7 = (lon * 1e7).toInt()
        val now = System.currentTimeMillis()

        val packets = tokenFrames.mapIndexed { index, tokens ->
            val packetBytes = AirHopNative.encodePacket(
                flags = flags.toByte(),
                ttl = ProtocolConstants.DEFAULT_TTL,
                targetZone = destinationId,
                latE7 = latE7,
                lonE7 = lonE7,
                tokens = tokens,
                timestampMs = now + index
            )
            val packetId = java.nio.ByteBuffer.wrap(packetBytes, 3, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            packetId to packetBytes
        }
        val messageId = transferId ?: packets.firstOrNull()?.first ?: return false

        // Keep each radio frame durable and independently acknowledged. The chat row
        // represents the whole spoken message and is delivered only after every frame ACKs.
        packets.forEach { (packetId, packetBytes) ->
            persistOutbox(packetId, packetBytes, messageId)
            onMessageSent?.invoke(packetId)
        }

        val chatMsg = ChatMessage(
            id = messageId,
            text = text,
            senderName = "You",
            isFromMe = true,
            timestamp = now,
            lat = lat,
            lon = lon,
            hopCount = 1,
            status = MessageStatus.SENDING
        )

        _messages.value = _messages.value + chatMsg
        persistMessages()

        packets.forEach { (_, packetBytes) -> bleMeshEngine.broadcastPacket(packetBytes) }
        currentMessageState(messageId, MessageStatus.SENT)
        Log.i(TAG, "Queued ${packets.size} frame(s) over offline mesh: '$text'")
        return true
    }

    fun handleIncomingPacket(repairResult: PacketRepairResult) {
        val flags = repairResult.flags
        val msgId = repairResult.msgId
        val targetZone = repairResult.targetZone
        val destinationId = targetZone
        val isAck = (flags and ProtocolConstants.FLAG_ACK.toInt()) != 0

        if (isAck) {
            // ACKs are broadcast so relays do not mistake the acknowledged message ID
            // for a destination node ID. New ACKs carry the original packet ID in the
            // first four token bytes; targetZone remains a legacy fallback.
            val ackTokens = repairResult.tokens ?: ByteArray(13)
            val acknowledgedPacketId = if (ackTokens.size >= 4 && ackTokens.take(4).any { it != 0.toByte() }) {
                java.nio.ByteBuffer.wrap(ackTokens, 0, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            } else {
                repairResult.targetZone
            }
            scope.launch {
                val groupId = findOutboxGroupId(acknowledgedPacketId) ?: return@launch
                removeOutbox(acknowledgedPacketId)
                if (hasPendingOutboxForGroup(groupId)) {
                    Log.i(TAG, "Frame $acknowledgedPacketId acknowledged; waiting for remaining frames of $groupId")
                    return@launch
                }

                val current = _messages.value.toMutableList()
                val index = current.indexOfFirst { it.id == groupId && it.isFromMe }
                if (index != -1) {
                    val old = current[index]
                    val latency = System.currentTimeMillis() - old.timestamp
                    current[index] = old.copy(
                        status = MessageStatus.DELIVERED,
                        latencyMs = latency
                    )
                    _messages.value = current
                    persistMessages()
                    _deliveryEvent.emit(groupId)
                    Log.i(TAG, "BLUE TICK CONFIRMED for message $groupId! Latency: $latency ms")
                }
            }
            return
        }

        // Directed frames are consumed only by their destination; zero is broadcast.
        if (destinationId != 0 && destinationId != nodeIdentity.intId()) return

        scope.launch {
            val tokens = repairResult.tokens ?: ByteArray(13)
            val lang = (flags and ProtocolConstants.FLAG_LANG_MASK.toInt()).toByte()

            if (AirHopTextFragments.hasMarker(tokens)) {
                val assembled = textAssembler.add(tokens, msgId) ?: return@launch
                val alreadyDisplayed = _messages.value.any {
                    it.id == assembled.groupId && !it.isFromMe
                }
                if (!alreadyDisplayed) {
                    val incoming = ChatMessage(
                        id = assembled.groupId,
                        text = assembled.text,
                        senderName = "Peer Node",
                        isFromMe = false,
                        timestamp = System.currentTimeMillis(),
                        lat = repairResult.latitude,
                        lon = repairResult.longitude,
                        hopCount = (ProtocolConstants.DEFAULT_TTL - repairResult.ttl) + 1,
                        status = MessageStatus.DELIVERED
                    )
                    _messages.value = _messages.value + incoming
                    persistMessages()
                    Log.i(TAG, "Received reassembled message from peer: ${assembled.groupId}")
                    offlineTtsEngine.speak(assembled.text, lang)
                }

                // Do not ACK partial messages: the sender keeps retrying missing frames.
                assembled.packetIds.forEach { sendAckPacket(it, lang) }
                return@launch
            }

            val existing = _messages.value.find { it.id == msgId && !it.isFromMe }
            if (existing != null) return@launch

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
            persistMessages()
            Log.i(TAG, "Received message from peer: $msgId")

            offlineTtsEngine.speak(text, lang)
            sendAckPacket(msgId, lang)
        }
    }

    private fun persistOutbox(msgId: Int, packet: ByteArray, groupId: Int = msgId) {
        val out = prefs.getString(outboxKey, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        val obj = JSONObject().apply {
            put("id", msgId)
            put("groupId", groupId)
            put("packet", Base64.encodeToString(packet, Base64.NO_WRAP))
        }
        out.put(obj)
        prefs.edit().putString(outboxKey, out.toString()).apply()
    }

    private fun findOutboxGroupId(msgId: Int): Int? {
        val raw = prefs.getString(outboxKey, null) ?: return null
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.optInt("id") == msgId) return item.optInt("groupId", msgId)
        }
        return null
    }

    private fun hasPendingOutboxForGroup(groupId: Int): Boolean {
        val raw = prefs.getString(outboxKey, null) ?: return false
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return false
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.optInt("groupId", item.optInt("id")) == groupId) return true
        }
        return false
    }

    private fun removeOutbox(msgId: Int) {
        val raw = prefs.getString(outboxKey, null) ?: return
        val old = runCatching { JSONArray(raw) }.getOrNull() ?: return
        val next = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).optInt("id") != msgId) next.put(old.getJSONObject(i))
        prefs.edit().putString(outboxKey, next.toString()).apply()
    }

    private fun retryOutbox(msgId: Int) {
        scope.launch {
            // Store-and-forward must survive long gaps between nearby relays.
            // Use bounded exponential backoff, then keep retrying every 5 minutes
            // until the destination ACK removes the durable outbox entry.
            val initialBackoffMs = longArrayOf(
                5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L
            )
            var attempt = 0
            while (isOutboxPending(msgId)) {
                val delayMs = initialBackoffMs.getOrElse(attempt) { 300_000L }
                delay(delayMs)
                if (!isOutboxPending(msgId)) break

                val raw = prefs.getString(outboxKey, null) ?: break
                val array = runCatching { JSONArray(raw) }.getOrNull() ?: break
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    if (item.optInt("id") == msgId) {
                        val packet = Base64.decode(item.getString("packet"), Base64.NO_WRAP)
                        bleMeshEngine.broadcastPacket(packet)
                    }
                }
                attempt++
            }
        }
    }

    private fun isOutboxPending(msgId: Int): Boolean {
        val raw = prefs.getString(outboxKey, null) ?: return false
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return false
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).optInt("id") == msgId) return true
        }
        return false
    }

    private fun currentMessageState(id: Int, status: MessageStatus) {
        val current = _messages.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = current[index].copy(status = status)
            _messages.value = current
            persistMessages()
        }
    }

    private fun persistMessages() {
        val array = JSONArray()
        _messages.value.takeLast(200).forEach { message ->
            array.put(JSONObject().apply {
                put("id", message.id)
                put("text", message.text)
                put("sender", message.senderName)
                put("fromMe", message.isFromMe)
                put("timestamp", message.timestamp)
                put("lat", message.lat)
                put("lon", message.lon)
                put("hops", message.hopCount)
                put("status", message.status.name)
                if (message.latencyMs == null) put("latencyMs", JSONObject.NULL)
                else put("latencyMs", message.latencyMs)
            })
        }
        prefs.edit().putString(storageKey, array.toString()).apply()
    }

    private fun loadMessages(): List<ChatMessage> {
        val raw = prefs.getString(storageKey, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(ChatMessage(
                        id = o.getInt("id"),
                        text = o.getString("text"),
                        senderName = o.getString("sender"),
                        isFromMe = o.getBoolean("fromMe"),
                        timestamp = o.getLong("timestamp"),
                        lat = o.optDouble("lat", 0.0),
                        lon = o.optDouble("lon", 0.0),
                        hopCount = o.optInt("hops", 1),
                        status = runCatching {
                            MessageStatus.valueOf(o.optString("status", MessageStatus.SENT.name))
                        }.getOrDefault(MessageStatus.SENT),
                        latencyMs = if (o.isNull("latencyMs")) null else o.optLong("latencyMs")
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stored AirHop message history could not be restored", e)
            emptyList()
        }
    }

    private fun sendAckPacket(targetMsgId: Int, lang: Byte) {
        val ackFlags = (ProtocolConstants.FLAG_ACK.toInt() or lang.toInt()).toByte()
        val ackTokens = ByteArray(13)
        // ACK is intentionally broadcast (targetZone=0). Put the acknowledged
        // message ID in the payload so relay nodes never confuse it with a node ID.
        java.nio.ByteBuffer.wrap(ackTokens, 0, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(targetMsgId)
        val ackBytes = AirHopNative.encodePacket(
            flags = ackFlags,
            ttl = ProtocolConstants.DEFAULT_TTL,
            targetZone = 0,
            latE7 = 0,
            lonE7 = 0,
            tokens = ackTokens,
            timestampMs = System.currentTimeMillis()
        )
        val ackMsgId = java.nio.ByteBuffer.wrap(ackBytes, 3, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        onMessageSent?.invoke(ackMsgId)
        bleMeshEngine.broadcastPacket(ackBytes)
        Log.i(TAG, "Dispatched delivery ACK for message $targetMsgId (ackId=$ackMsgId) over mesh")
    }
}
