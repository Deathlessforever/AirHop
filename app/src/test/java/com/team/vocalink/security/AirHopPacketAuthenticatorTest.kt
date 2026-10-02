package com.team.vocalink.security

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirHopPacketAuthenticatorTest {
    @Test fun tamperedFrameIsRejected() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val auth = AirHopPacketAuthenticator(context, ByteArray(32) { (it + 1).toByte() })
        val packet = ByteArray(40).also { it[0] = 0x7E }
        val frame = auth.wrap(packet)
        frame[10] = (frame[10].toInt() xor 1).toByte()
        assertNull(auth.unwrap(frame))
    }

    @Test fun intactFrameRoundTrips() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val auth = AirHopPacketAuthenticator(context, ByteArray(32) { (it + 1).toByte() })
        val packet = ByteArray(40).also { it[0] = 0x7E; it[5] = 42 }
        assertArrayEquals(packet, auth.unwrap(auth.wrap(packet)))
    }
}
