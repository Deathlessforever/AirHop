package com.team.vocalink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirHopTextFragmentsTest {
    @Test
    fun compressedMultilingualMessageReassemblesOutOfOrderAndKeepsPacketIds() {
        val text = ("ನಾನು ಅವಶೇಷಗಳ ಕೆಳಗೆ ಸಿಲುಕಿದ್ದೇನೆ. Please send rescue. ").repeat(3)
        val groupId = 0xA14238FF.toInt()
        val packetIds = (100..120).toList()
        val frames = AirHopTextFragments.encode(text, groupId)
            ?: error("Expected the message to fit AirHop's compressed frame limit")

        assertTrue(frames.size > 1)
        assertEquals(0xFC, frames.first()[0].toInt() and 0xFF)
        assertTrue(frames.size < (text.toByteArray(Charsets.UTF_8).size + 4) / 5)

        val assembler = AirHopTextAssembler()
        var complete: AirHopTextFragments.AssembledText? = null
        frames.indices.reversed().forEach { index ->
            complete = assembler.add(frames[index], packetIds[index])
        }

        assertEquals(groupId, complete?.groupId)
        assertEquals(text, complete?.text)
        assertEquals(packetIds.take(frames.size).toSet(), complete?.packetIds?.toSet())
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

    @Test
    fun corruptCompressedFramesAreRejected() {
        val frames = AirHopTextFragments.encode("help ".repeat(30), groupId = 7)
            ?: error("Expected repeated text to compress")
        assertEquals(0xFC, frames.first()[0].toInt() and 0xFF)
        val corruptedFrames = frames.mapIndexed { index, frame ->
            frame.copyOf().apply {
                if (index == 0) this[8] = (this[8].toInt() xor 0x55).toByte()
            }
        }

        val assembler = AirHopTextAssembler()
        var complete: AirHopTextFragments.AssembledText? = null
        corruptedFrames.forEachIndexed { index, frame ->
            complete = assembler.add(frame, packetId = 90 + index)
        }
        assertNull(complete)
    }
}
