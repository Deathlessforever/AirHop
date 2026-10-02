package com.team.vocalink.mesh

import java.util.ArrayDeque
import kotlin.math.max

/**
 * Deterministic in-memory mesh simulator used by unit tests.
 * It models A -> B -> C -> D forwarding, duplicate suppression, TTL,
 * relay failure/recovery and end-to-end ACK routing without Android APIs.
 */
class MeshSimulation(
    nodeIds: Set<Int>,
    private val maxHops: Int = 10
) {
    data class Frame(
        val messageId: Int,
        val source: Int,
        val destination: Int,
        val payload: ByteArray,
        val ttl: Int,
        val hops: Int = 0,
        val currentNode: Int = source,
        val ack: Boolean = false,
        val acknowledgedMessageId: Int = 0
    )

    data class Result(
        val delivered: Boolean,
        val deliveryHops: Int,
        val duplicateFramesDropped: Int,
        val ttlDrops: Int,
        val trace: List<Int>
    )

    private val nodes = nodeIds.toMutableSet()
    private val neighbors = mutableMapOf<Int, MutableSet<Int>>()
    private val seen = mutableMapOf<Int, MutableSet<Int>>()

    fun connect(a: Int, b: Int) {
        neighbors.getOrPut(a) { mutableSetOf() }.add(b)
        neighbors.getOrPut(b) { mutableSetOf() }.add(a)
    }

    fun setOnline(node: Int, online: Boolean) {
        if (online) nodes.add(node) else nodes.remove(node)
    }

    fun send(source: Int, destination: Int, messageId: Int, payload: ByteArray): Result {
        val queue = ArrayDeque<Frame>()
        val trace = mutableListOf<Int>()
        var duplicates = 0
        var ttlDrops = 0
        val visited = mutableSetOf<Pair<Int, Int>>()
        queue.add(Frame(messageId, source, destination, payload.copyOf(), maxHops, currentNode = source))

        while (queue.isNotEmpty()) {
            val frame = queue.removeFirst()
            if (frame.ttl <= 0) {
                ttlDrops++
                continue
            }
            val key = frame.source to frame.messageId
            val node = frame.currentNode
            if (!nodes.contains(node)) continue
            if (!visited.add(node to frame.messageId)) {
                duplicates++
                continue
            }
            trace.add(node)

            if (node == destination) {
                return Result(true, frame.hops, duplicates, ttlDrops, trace.toList())
            }

            for (next in neighbors[node].orEmpty()) {
                if (!nodes.contains(next)) continue
                queue.add(frame.copy(ttl = frame.ttl - 1, hops = frame.hops + 1, currentNode = next))
            }
        }

        return Result(false, 0, duplicates, ttlDrops, trace.toList())
    }
}
