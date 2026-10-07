package com.team.vocalink.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChunkAssemblerTest {
    @Test fun outOfOrderChunksReassembleExactly() {
        val a = ChunkAssembler()
        val original = "AirHop production payload".toByteArray()
        val chunks = a.split(7, original, 5)
        assertNull(a.put(7, 2, chunks.size, chunks[2]))
        assertNull(a.put(7, 0, chunks.size, chunks[0]))
        for (i in 1 until chunks.size - 1) a.put(7, i, chunks.size, chunks[i])
        assertArrayEquals(original, a.put(7, chunks.lastIndex, chunks.size, chunks.last()))
    }
}
