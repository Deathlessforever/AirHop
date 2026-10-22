package com.team.vocalink.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AirHopPacketAuthenticatorTest {
    private val testKey = ByteArray(32) { (it + 1).toByte() }

    private fun authenticator(): AirHopPacketAuthenticator {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AirHopPacketAuthenticator(context, testKey)
    }

    @Test
    fun intactFrameRoundTrips() {
        val auth = authenticator()
        val packet = ByteArray(40).also { it[0] = 0x7E; it[5] = 42 }
        assertArrayEquals(packet, auth.unwrap(auth.wrap(packet)))
    }

    @Test
    fun tamperedFrameIsRejected() {
        val auth = authenticator()
        val packet = ByteArray(40).also { it[0] = 0x7E }
        val frame = auth.wrap(packet)
        frame[10] = (frame[10].toInt() xor 1).toByte()
        assertNull(auth.unwrap(frame))
    }

    @Test
    fun wrongFrameLengthsAreRejected() {
        val auth = authenticator()
        assertNull(auth.unwrap(ByteArray(39)))
        assertNull(auth.unwrap(ByteArray(47)))
        assertNull(auth.unwrap(ByteArray(49)))
    }

    @Test
    fun packetsMustBeExactlyFortyBytesBeforeWrapping() {
        val auth = authenticator()
        assertThrows(IllegalArgumentException::class.java) { auth.wrap(ByteArray(39)) }
        assertThrows(IllegalArgumentException::class.java) { auth.wrap(ByteArray(41)) }
    }
}
