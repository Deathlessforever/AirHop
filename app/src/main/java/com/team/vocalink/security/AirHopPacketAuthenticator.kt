package com.team.vocalink.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AirHopPacketAuthenticator(context: Context) {
    companion object {
        const val TAG_BYTES = 8
        const val SECURE_FRAME_SIZE = 48
        private const val PREFS = "airhop_security"
        private const val KEY = "group_key"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): ByteArray {
        val encoded = prefs.getString(KEY, null)
        if (encoded != null) return Base64.decode(encoded, Base64.NO_WRAP)
        val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, Base64.encodeToString(generated, Base64.NO_WRAP)).apply()
        return generated
    }

    fun exportKey(): String = Base64.encodeToString(key(), Base64.NO_WRAP)

    fun importKey(encoded: String) {
        val bytes = Base64.decode(encoded.trim(), Base64.DEFAULT)
        require(bytes.size == 32) { "AirHop group key must be 256 bits" }
        prefs.edit().putString(KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    fun wrap(packet: ByteArray): ByteArray {
        require(packet.size == 40)
        val tag = mac(packet)
        return packet + tag
    }

    fun unwrap(frame: ByteArray): ByteArray? {
        if (frame.size != SECURE_FRAME_SIZE) return null
        val packet = frame.copyOfRange(0, 40)
        val supplied = frame.copyOfRange(40, 48)
        val expected = mac(packet)
        return if (java.security.MessageDigest.isEqual(expected, supplied)) packet else null
    }

    private fun mac(packet: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key(), "HmacSHA256"))
        return mac.doFinal(packet).copyOf(TAG_BYTES)
    }
}
