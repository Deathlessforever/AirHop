package com.team.vocalink.mesh

class ChunkAssembler(private val maxChunks: Int = 64) {
    private data class State(val count: Int, val parts: Array<ByteArray?>)
    private val states = mutableMapOf<Int, State>()

    @Synchronized
    fun split(id: Int, data: ByteArray, chunkSize: Int): List<ByteArray> {
        require(chunkSize > 0)
        val count = maxOf(1, (data.size + chunkSize - 1) / chunkSize)
        require(count <= maxChunks)
        return (0 until count).map { i ->
            val start = i * chunkSize
            data.copyOfRange(start, minOf(data.size, start + chunkSize))
        }
    }

    @Synchronized
    fun put(id: Int, index: Int, count: Int, bytes: ByteArray): ByteArray? {
        require(count in 1..maxChunks && index in 0 until count)
        val state = states.getOrPut(id) { State(count, arrayOfNulls(count)) }
        if (state.count != count) {
            states.remove(id)
            return null
        }
        state.parts[index] = bytes.copyOf()
        if (state.parts.any { it == null }) return null
        val result = state.parts.filterNotNull().fold(ByteArray(0)) { acc, part -> acc + part }
        states.remove(id)
        return result
    }

    @Synchronized fun clear() = states.clear()
}
