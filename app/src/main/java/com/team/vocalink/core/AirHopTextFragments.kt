package com.team.vocalink.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Losslessly compresses free-form text when useful, then carries it across the
 * existing 13-byte token payload. Each fragment remains a normal AirHop packet.
 */
object AirHopTextFragments {
    private const val RAW_MARKER = 0xFD
    private const val DEFLATE_MARKER = 0xFC
    private const val GROUP_ID_OFFSET = 1
    private const val INDEX_OFFSET = 5
    private const val COUNT_OFFSET = 6
    private const val LENGTH_OFFSET = 7
    private const val DATA_OFFSET = 8
    private const val DATA_BYTES = 5

    const val MAX_FRAGMENTS = 64
    const val MAX_TEXT_BYTES = 2_048
    const val MAX_ENCODED_BYTES = MAX_FRAGMENTS * DATA_BYTES

    data class Fragment(
        val groupId: Int,
        val index: Int,
        val count: Int,
        val data: ByteArray,
        val isCompressed: Boolean
    )

    data class AssembledText(
        val groupId: Int,
        val text: String,
        val packetIds: List<Int>,
        val completedAt: Long = System.currentTimeMillis()
    )

    fun hasMarker(tokens: ByteArray): Boolean {
        if (tokens.isEmpty()) return false
        val marker = tokens[0].toInt() and 0xFF
        return marker == RAW_MARKER || marker == DEFLATE_MARKER
    }

    fun encode(text: String, groupId: Int): List<ByteArray>? {
        val original = text.toByteArray(Charsets.UTF_8)
        if (original.size <= 12 || original.size > MAX_TEXT_BYTES) return null

        val compressed = deflate(original)
        val useCompression = compressed.size < original.size
        val payload = if (useCompression) compressed else original
        if (payload.isEmpty() || payload.size > MAX_ENCODED_BYTES) return null

        val count = (payload.size + DATA_BYTES - 1) / DATA_BYTES
        if (count !in 1..MAX_FRAGMENTS) return null
        val marker = if (useCompression) DEFLATE_MARKER else RAW_MARKER

        return List(count) { index ->
            val start = index * DATA_BYTES
            val length = minOf(DATA_BYTES, payload.size - start)
            ByteArray(ProtocolConstants.TOKEN_COUNT).apply {
                this[0] = marker.toByte()
                ByteBuffer.wrap(this, GROUP_ID_OFFSET, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(groupId)
                this[INDEX_OFFSET] = index.toByte()
                this[COUNT_OFFSET] = count.toByte()
                this[LENGTH_OFFSET] = length.toByte()
                System.arraycopy(payload, start, this, DATA_OFFSET, length)
            }
        }
    }

    fun parse(tokens: ByteArray): Fragment? {
        if (tokens.size != ProtocolConstants.TOKEN_COUNT || !hasMarker(tokens)) return null
        val marker = tokens[0].toInt() and 0xFF
        val groupId = ByteBuffer.wrap(tokens, GROUP_ID_OFFSET, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        val index = tokens[INDEX_OFFSET].toInt() and 0xFF
        val count = tokens[COUNT_OFFSET].toInt() and 0xFF
        val length = tokens[LENGTH_OFFSET].toInt() and 0xFF
        if (count !in 1..MAX_FRAGMENTS || index >= count || length !in 1..DATA_BYTES) return null
        if (index < count - 1 && length != DATA_BYTES) return null
        return Fragment(
            groupId = groupId,
            index = index,
            count = count,
            data = tokens.copyOfRange(DATA_OFFSET, DATA_OFFSET + length),
            isCompressed = marker == DEFLATE_MARKER
        )
    }

    private fun deflate(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(bytes) }
        }.toByteArray()

    internal fun inflate(bytes: ByteArray): ByteArray? {
        return try {
            InflaterInputStream(ByteArrayInputStream(bytes)).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(256)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_TEXT_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun decodeUtf8(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            null
        }
}

/**
 * Bounded in-memory reassembly. Incomplete messages are not acknowledged, which lets
 * the durable sender outbox retry missing frames after a temporary disconnect.
 */
class AirHopTextAssembler(
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class Pending(
        val count: Int,
        val isCompressed: Boolean,
        val createdAt: Long,
        val fragments: MutableMap<Int, ByteArray> = mutableMapOf(),
        val packetIds: LinkedHashSet<Int> = linkedSetOf()
    )

    private data class Completed(
        val message: AirHopTextFragments.AssembledText,
        val isCompressed: Boolean,
        val fragments: List<ByteArray>
    )

    private val pending = LinkedHashMap<Int, Pending>()
    private val completed = LinkedHashMap<Int, Completed>()

    @Synchronized
    fun add(tokens: ByteArray, packetId: Int): AirHopTextFragments.AssembledText? {
        val fragment = AirHopTextFragments.parse(tokens) ?: return null
        val now = nowMs()
        pruneExpired(now)

        completed[fragment.groupId]?.let { previous ->
            if (previous.isCompressed != fragment.isCompressed ||
                previous.fragments.size != fragment.count ||
                !previous.fragments[fragment.index].contentEquals(fragment.data)
            ) {
                return null
            }
            val updatedMessage = previous.message.copy(
                packetIds = (previous.message.packetIds + packetId)
                    .distinct()
                    .take(MAX_IN_FLIGHT * 4)
            )
            completed[fragment.groupId] = previous.copy(message = updatedMessage)
            return updatedMessage
        }

        var assembly = pending[fragment.groupId]
        if (assembly == null) {
            while (pending.size >= MAX_IN_FLIGHT) {
                pending.remove(pending.keys.first())
            }
            assembly = Pending(fragment.count, fragment.isCompressed, now)
            pending[fragment.groupId] = assembly
        } else if (assembly.count != fragment.count ||
            assembly.isCompressed != fragment.isCompressed
        ) {
            pending.remove(fragment.groupId)
            return null
        }

        val previousFragment = assembly.fragments[fragment.index]
        if (previousFragment != null && !previousFragment.contentEquals(fragment.data)) {
            pending.remove(fragment.groupId)
            return null
        }
        assembly.fragments[fragment.index] = fragment.data
        assembly.packetIds.add(packetId)
        if (assembly.fragments.size != assembly.count) return null

        val parts = ArrayList<ByteArray>(assembly.count)
        for (index in 0 until assembly.count) {
            val part = assembly.fragments[index] ?: run {
                pending.remove(fragment.groupId)
                return null
            }
            parts.add(part)
        }
        val encodedSize = parts.sumOf { it.size }
        if (encodedSize !in 1..AirHopTextFragments.MAX_ENCODED_BYTES) {
            pending.remove(fragment.groupId)
            return null
        }

        val encoded = ByteArray(encodedSize)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, encoded, offset, part.size)
            offset += part.size
        }
        val textBytes = if (assembly.isCompressed) {
            AirHopTextFragments.inflate(encoded)
        } else {
            encoded
        } ?: run {
            pending.remove(fragment.groupId)
            return null
        }
        if (textBytes.size > AirHopTextFragments.MAX_TEXT_BYTES) {
            pending.remove(fragment.groupId)
            return null
        }
        val text = AirHopTextFragments.decodeUtf8(textBytes) ?: run {
            pending.remove(fragment.groupId)
            return null
        }

        val message = AirHopTextFragments.AssembledText(
            groupId = fragment.groupId,
            text = text,
            packetIds = assembly.packetIds.toList(),
            completedAt = now
        )
        pending.remove(fragment.groupId)
        completed[fragment.groupId] = Completed(message, assembly.isCompressed, parts)
        while (completed.size > MAX_IN_FLIGHT) completed.remove(completed.keys.first())
        return message
    }

    private fun pruneExpired(now: Long) {
        pending.entries.removeAll { now - it.value.createdAt > RETENTION_MS }
        completed.entries.removeAll { now - it.value.message.completedAt > RETENTION_MS }
    }

    private companion object {
        const val MAX_IN_FLIGHT = 64
        const val RETENTION_MS = 10 * 60 * 1000L
    }
}
