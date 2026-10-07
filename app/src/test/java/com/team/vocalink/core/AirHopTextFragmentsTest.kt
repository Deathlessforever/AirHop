package com.team.vocalink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirHopTextFragmentsTest {
    @Test
    fun longUtf8MessageReassemblesOutOfOrderAndKeepsPacketIds() {
        val text = "I am trapped under the north side rubble. There are two people with me. Please send rescue."
        val groupId = 0xA14238FF.toInt()
        val packetIds = (100..120).toList()
        val fragments = AirHopTextFragments.encode(text, groupId)
        assertNotNull(fragments)
        assertTrue(fragments!!.size > 1)

        val assembler = AirHopTextAssembler()
        var complete: AirHopTextFragments.AssembledText? = null
        fragments.indices.reversed().forEach { index ->
            complete = assembler.add(fragments[index], packetIds[index])
        }

        assertNotNull(complete)
        assertEquals(groupId, complete!!.groupId)
        assertEquals(text, complete!!.text)
        assertEquals(packetIds.take(fragments.size).toSet(), complete!!.packetIds.toSet())
    }

    @Test
    fun malformedAndOversizedMessagesAreRejected() {
        assertNull(AirHopTextFragments.parse(ByteArray(ProtocolConstants.TOKEN_COUNT)))
        assertNull(
            AirHopTextFragments.encode(
                "x".repeat(AirHopTextFragments.MAX_TEXT_BYTES + 1),
                groupId = 1
            )
        )
    }
}
