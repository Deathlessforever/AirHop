package com.team.vocalink.core

object AirHopNative {

    init {
        System.loadLibrary("airhop_core")
    }

    /**
     * Constructs a 40-byte AirHop frame with FNV-1a hash and RS(40,32) parity.
     */
    external fun encodePacket(
        flags: Byte,
        ttl: Byte,
        targetZone: Int,
        latE7: Int,
        lonE7: Int,
        tokens: ByteArray?,
        timestampMs: Long
    ): ByteArray

    /**
     * Decodes and repairs up to 4 corrupted bytes in a 40-byte AirHop frame using RS(40,32).
     */
    external fun decodeAndRepairPacket(
        rawPacket: ByteArray
    ): PacketRepairResult?

    /**
     * Decrements TTL (ttl--) and recomputes RS(40,32) parity in-place.
     * Returns null if TTL <= 1 (expired).
     */
    external fun verifyAndDecrementTTL(
        rawPacket: ByteArray
    ): ByteArray?

    /**
     * Ingests 16 kHz 16-bit mono PCM into the C++ lock-free circular ring buffer and steps Silero VAD.
     * Returns packed integer: (event << 4) | (state & 0x0F)
     */
    external fun processAudioChunk(
        pcmChunk: ShortArray
    ): Int

    /**
     * Reads PCM audio samples out of the C++ circular ring buffer.
     */
    external fun readAudioFromRingBuffer(
        destination: ShortArray
    ): Int

    /**
     * Resets ring buffer read/write pointers and Silero VAD state machine.
     */
    external fun resetAudioEngine()

    fun unpackVadResult(packed: Int): Pair<VadEvent, VadState> {
        val eventCode = (packed shr 4) and 0x0F
        val stateCode = packed and 0x0F
        return Pair(VadEvent.fromCode(eventCode), VadState.fromCode(stateCode))
    }
}
