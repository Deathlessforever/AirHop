package com.team.vocalink.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class NodeIdentity {
    private val random = SecureRandom()
    private val identity = ByteArray(16).also(random::nextBytes)
    fun id(): ByteArray = identity.copyOf()
    fun shortId(): String = identity.take(4).joinToString("") { "%02x".format(it) }
}

class ReplayGuard(private val maxAgeMs: Long = 120_000L) {
    private val seen = ConcurrentHashMap<String, Long>()
    @Synchronized fun accept(source: ByteArray, msgId: Int, now: Long = System.currentTimeMillis()): Boolean {
        seen.entries.removeIf { now - it.value > maxAgeMs }
        val key = MessageDigest.getInstance("SHA-256").digest(source + msgId.toString().toByteArray()).take(16).toByteArray()
        return seen.putIfAbsent(key.joinToString("") { "%02x".format(it) }, now) == null
    }
}
