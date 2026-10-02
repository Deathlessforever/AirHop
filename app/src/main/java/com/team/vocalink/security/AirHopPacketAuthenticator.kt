package com.team.vocalink.security

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Compact authentication/integrity envelope for the 40-byte AirHop frame.
 *
 * The group key is generated/imported as 256-bit material and stored encrypted
 * under an Android Keystore AES key. This class authenticates transport frames;
 * it does not provide confidentiality.
 */
class AirHopPacketAuthenticator(context: Context) {
    companion object {
        const val TAG_BYTES = 8
        const val SECURE_FRAME_SIZE = 48
        private const val PREFS = "airhop_security"
        private const val KEY = "group_key"
        private const val SEALED_KEY = "group_key_sealed"
        private const val KS_ALIAS = "airhop_group_key_wrap_v1"
        private const val NONCE_BYTES = 12
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val jvmTestWrappingKey = SecretKeySpec(
        ByteArray(32).also { SecureRandom().nextBytes(it) },
        "AES"
    )
    private val isAndroidRuntime =
        System.getProperty("java.vm.name")?.contains("dalvik", ignoreCase = true) == true ||
        System.getProperty("java.vm.name")?.contains("art", ignoreCase = true) == true

    private fun wrappingKey(): SecretKey {
        if (!isAndroidRuntime) return jvmTestWrappingKey
        return try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KS_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generator.generateKey()
        } catch (_: Exception) {
            // JVM unit tests do not provide AndroidKeyStore. Real Android builds use it.
            jvmTestWrappingKey
        }
    }

    private fun key(): ByteArray {
        val sealed = prefs.getString(SEALED_KEY, null)
        if (sealed != null) {
            return unseal(Base64.decode(sealed, Base64.NO_WRAP))
        }

        // One-time migration from the previous plaintext SharedPreferences format.
        val legacy = prefs.getString(KEY, null)
        if (legacy != null) {
            val migrated = Base64.decode(legacy, Base64.NO_WRAP)
            require(migrated.size == 32) { "Stored AirHop group key is invalid" }
            val sealedBytes = seal(migrated)
            prefs.edit().remove(KEY)
                .putString(SEALED_KEY, Base64.encodeToString(sealedBytes, Base64.NO_WRAP))
                .apply()
            return migrated
        }

        val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(
                SEALED_KEY,
                Base64.encodeToString(seal(generated), Base64.NO_WRAP)
            )
            .apply()
        return generated
    }

    fun exportKey(): String =
        Base64.encodeToString(key(), Base64.NO_WRAP)

    fun importKey(encoded: String) {
        val bytes = try {
            Base64.decode(encoded.trim(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64 AirHop group key", e)
        }
        require(bytes.size == 32) { "AirHop group key must be 256 bits" }
        prefs.edit()
            .putString(
                SEALED_KEY,
                Base64.encodeToString(seal(bytes), Base64.NO_WRAP)
            )
            .remove(KEY)
            .apply()
    }

    fun wrap(packet: ByteArray): ByteArray {
        require(packet.size == 40)
        return packet + mac(packet)
    }

    fun unwrap(frame: ByteArray): ByteArray? {
        if (frame.size != SECURE_FRAME_SIZE) return null
        val packet = frame.copyOfRange(0, 40)
        val supplied = frame.copyOfRange(40, SECURE_FRAME_SIZE)
        val expected = mac(packet)
        return if (java.security.MessageDigest.isEqual(expected, supplied)) {
            packet
        } else {
            null
        }
    }

    private fun mac(packet: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key(), "HmacSHA256"))
        return mac.doFinal(packet).copyOf(TAG_BYTES)
    }

    private fun seal(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plain)
        return nonce + ciphertext
    }

    private fun unseal(sealed: ByteArray): ByteArray {
        require(sealed.size > NONCE_BYTES + 16) { "Invalid sealed AirHop key" }
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        val ciphertext = sealed.copyOfRange(NONCE_BYTES, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext).also {
            require(it.size == 32) { "Invalid unsealed AirHop key length" }
        }
    }
}
