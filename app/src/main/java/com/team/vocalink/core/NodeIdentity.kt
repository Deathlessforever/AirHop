package com.team.vocalink.core

import android.content.Context
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

class NodeIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("airhop_identity", Context.MODE_PRIVATE)
    private val identity: ByteArray = run {
        val stored = prefs.getString("node_id", null)
        if (stored != null) return@run stored.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val generated = ByteArray(4).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("node_id", generated.joinToString("") { "%02x".format(it) }).apply()
        generated
    }
    fun id(): ByteArray = identity.copyOf()
    fun intId(): Int = ByteBuffer.wrap(identity).int
    fun shortId(): String = identity.joinToString("") { "%02x".format(it) }
}

class ReplayGuard(private val maxAgeMs: Long = ProtocolConstants.REPLAY_WINDOW_MS) {
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Synchronized fun accept(source: ByteArray, msgId: Int, now: Long = System.currentTimeMillis()): Boolean {
        seen.entries.removeIf { now - it.value > maxAgeMs }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source + ByteBuffer.allocate(4).putInt(msgId).array())
        return seen.putIfAbsent(digest.take(16).joinToString("") { "%02x".format(it) }, now) == null
    }
}
