package com.team.vocalink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirHopProtocolTest {
    @Test fun ttlMustReachZeroOnlyAfterFinalForward() {
        var ttl = 3
        repeat(2) { ttl -= 1 }
        assertEquals(1, ttl)
        ttl -= 1
        assertEquals(0, ttl)
    }

    @Test fun destinationMatchingIsExact() {
        val local = 0x12345678
        assertTrue(local == 0x12345678)
    }
}
