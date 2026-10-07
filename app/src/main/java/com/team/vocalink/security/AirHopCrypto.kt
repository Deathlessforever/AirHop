package com.team.vocalink.security

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local AES-256-GCM envelope for AirHop message storage and future authenticated
 * transport. Each envelope has a fresh 96-bit nonce and authentication tag.
 *
 * The transport layer must carry the resulting envelope as fragments; this class
 * intentionally never invents plaintext integrity or delivery guarantees.
 */
class AirHopCrypto(private val context: Context) {
    companion object {
        private const val PREFS = "airhop_security"
        private const val KEY_B64 = "mesh_key"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
    }

    private fun key(): SecretKey {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_B64, null)
        if (existing != null) {
            val bytes = Base64.decode(existing, Base64.NO_WRAP)
            return javax.crypto.spec.SecretKeySpec(bytes, "AES")
        }

        val generator = KeyGenerator.getInstance("AES")
        generator.init(KEY_BITS)
        val generated = generator.generateKey()
        prefs.edit().putString(
            KEY_B64,
            Base64.encodeToString(generated.encoded, Base64.NO_WRAP)
        ).apply()
        return generated
    }

    fun encrypt(plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + nonce.size + ciphertext.size)
            .put(1)
            .put(nonce)
            .put(ciphertext)
            .array()
    }

    fun decrypt(envelope: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(envelope.size > 1 + NONCE_BYTES + 16) { "Invalid AirHop secure envelope" }
        require(envelope[0].toInt() == 1) { "Unsupported AirHop secure envelope version" }

        val nonce = envelope.copyOfRange(1, 1 + NONCE_BYTES)
        val ciphertext = envelope.copyOfRange(1 + NONCE_BYTES, envelope.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}
