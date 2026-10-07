package com.team.vocalink.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Encodes free-form messages across the existing 13-byte token payload.
 * Fragments are individually valid AirHop packets, so relays can store and forward them.
 */
object AirHopTextFragments {
    private const val MARKER = 0xFD
    private const val GROUP_ID_OFFSET = 1
    private const val INDEX_OFFSET = 5
    private const val COUNT_OFFSET = 6
    private const val LENGTH_OFFSET = 7
    private const val DATA_OFFSET = 8
    private const val DATA_BYTES = 5

    const val MAX_FRAGMENTS = 64
    const val MAX_TEXT_BYTES = MAX_FRAGMENTS * DATA_BYTES

    data class Fragment(
        val groupId: Int,
        val index: Int,
        val count: Int,
        val data: ByteArray
    )

    data class AssembledText(
        val groupId: Int,
        val text: String,
        val packetIds: List<Int>,
        val completedAt: Long = System.currentTimeMillis()
    )

    fun hasMarker(tokens: ByteArray): Boolean =
        tokens.isNotEmpty() && (tokens[0].toInt() and 0xFF) == MARKER

    fun encode(text: String, groupId: Int): List<ByteArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 12 || bytes.size > MAX_TEXT_BYTES) return null
        val count = (bytes.size + DATA_BYTES - 1) / DATA_BYTES
        if (count > MAX_FRAGMENTS) return null

        return List(count) { index ->
            val start = index * DATA_BYTES
            val length = minOf(DATA_BYTES, bytes.size - start)
            ByteArray(ProtocolConstants.TOKEN_COUNT).apply {
                this[0] = MARKER.toByte()
                ByteBuffer.wrap(this, GROUP_ID_OFFSET, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(groupId)
                this[INDEX_OFFSET] = index.toByte()
                this[COUNT_OFFSET] = count.toByte()
                this[LENGTH_OFFSET] = length.toByte()
                System.arraycopy(bytes, start, this, DATA_OFFSET, length)
            }
        }
    }

    fun parse(tokens: ByteArray): Fragment? {
        if (tokens.size != ProtocolConstants.TOKEN_COUNT || !hasMarker(tokens)) return null
        val groupId = ByteBuffer.wrap(tokens, GROUP_ID_OFFSET, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        val index = tokens[INDEX_OFFSET].toInt() and 0xFF
        val count = tokens[COUNT_OFFSET].toInt() and 0xFF
        val length = tokens[LENGTH_OFFSET].toInt() and 0xFF
        if (count !in 2..MAX_FRAGMENTS || index >= count || length !in 1..DATA_BYTES) return null
        if (index < count - 1 && length != DATA_BYTES) return null
        return Fragment(
            groupId = groupId,
            index = index,
            count = count,
            data = tokens.copyOfRange(DATA_OFFSET, DATA_OFFSET + length)
        )
    }
}

/**
 * Bounded in-memory reassembly. Incomplete messages are not acknowledged, which lets
 * the existing durable sender outbox retry missing frames after a temporary disconnect.
 */
class AirHopTextAssembler(
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class Pending(
        val count: Int,
        val createdAt: Long,
        val fragments: MutableMap<Int, ByteArray> = mutableMapOf(),
        val packetIds: LinkedHashSet<Int> = linkedSetOf()
    )

    private val pending = LinkedHashMap<Int, Pending>()
    private val completed = LinkedHashMap<Int, AirHopTextFragments.AssembledText>()

    @Synchronized
    fun add(tokens: ByteArray, packetId: Int): AirHopTextFragments.AssembledText? {
        val fragment = AirHopTextFragments.parse(tokens) ?: return null
        val now = nowMs()
        pruneExpired(now)

        completed[fragment.groupId]?.let { previous ->
            val result = previous.copy(packetIds = (previous.packetIds + packetId).distinct().take(MAX_IN_FLIGHT * 4))
            completed[fragment.groupId] = result
            return result
        }

        var assembly = pending[fragment.groupId]
        if (assembly == null) {
            while (pending.size >= MAX_IN_FLIGHT) {
                pending.remove(pending.keys.first())
            }
            assembly = Pending(fragment.count, now)
            pending[fragment.groupId] = assembly
        } else if (assembly.count != fragment.count) {
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
            val part = assembly.fragments[index]
            if (part == null) {
                pending.remove(fragment.groupId)
                return null
            }
            parts.add(part)
        }
        val size = parts.sumOf { it.size }
        if (size !in 1..AirHopTextFragments.MAX_TEXT_BYTES) {
            pending.remove(fragment.groupId)
            return null
        }
        val bytes = ByteArray(size)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, bytes, offset, part.size)
            offset += part.size
        }

        val result = AirHopTextFragments.AssembledText(
            groupId = fragment.groupId,
            text = String(bytes, Charsets.UTF_8),
            packetIds = assembly.packetIds.toList(),
            completedAt = now
        )
        pending.remove(fragment.groupId)
        completed[fragment.groupId] = result
        while (completed.size > MAX_IN_FLIGHT) completed.remove(completed.keys.first())
        return result
    }

    private fun pruneExpired(now: Long) {
        pending.entries.removeAll { now - it.value.createdAt > RETENTION_MS }
        completed.entries.removeAll { now - it.value.completedAt > RETENTION_MS }
    }

    private companion object {
        const val MAX_IN_FLIGHT = 64
        const val RETENTION_MS = 10 * 60 * 1000L
    }
}
