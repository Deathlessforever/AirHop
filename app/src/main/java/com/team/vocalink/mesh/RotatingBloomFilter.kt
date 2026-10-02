package com.team.vocalink.mesh

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * High-performance, in-memory 1024-bit Bloom Filter with generational rotating window.
 * Eliminates duplicate message broadcasts in sub-millisecond time without unbounded memory growth.
 */
class RotatingBloomFilter(
    private val rotationIntervalMs: Long = 60_000L,
    private val maxInsertsPerGen: Int = 400
) {
    companion object {
        const val BIT_SIZE = 1024
        const val LONG_COUNT = BIT_SIZE / 64 // 16 Longs = 1024 bits
        const val HASH_COUNT = 4
    }

    private val lock = ReentrantReadWriteLock()
    private val currentGen = LongArray(LONG_COUNT)
    private val previousGen = LongArray(LONG_COUNT)

    private var insertCount = 0
    private var lastRotateTime = System.currentTimeMillis()

    /**
     * Checks if a 32-bit msg_id has already been seen in either generation.
     * Guaranteed sub-millisecond execution.
     * @return true if probably present (duplicate), false if definitely not seen.
     */
    fun contains(msgId: Int): Boolean = lock.read {
        checkAndRotateIfNeeded()
        val h1 = hash1(msgId)
        val h2 = hash2(msgId)

        var presentInCurrent = true
        var presentInPrevious = true

        for (i in 0 until HASH_COUNT) {
            val bitIndex = Math.floorMod(h1 + i * h2, BIT_SIZE)
            val wordIndex = bitIndex shr 6 // bitIndex / 64
            val bitMask = 1L shl (bitIndex and 0x3F) // bitIndex % 64

            if ((currentGen[wordIndex] and bitMask) == 0L) {
                presentInCurrent = false
            }
            if ((previousGen[wordIndex] and bitMask) == 0L) {
                presentInPrevious = false
            }

            if (!presentInCurrent && !presentInPrevious) {
                return false
            }
        }

        return presentInCurrent || presentInPrevious
    }

    /**
     * Inserts a 32-bit msg_id into the current generation.
     */
    fun add(msgId: Int) = lock.write {
        checkAndRotateIfNeeded()
        val h1 = hash1(msgId)
        val h2 = hash2(msgId)

        for (i in 0 until HASH_COUNT) {
            val bitIndex = Math.floorMod(h1 + i * h2, BIT_SIZE)
            val wordIndex = bitIndex shr 6
            val bitMask = 1L shl (bitIndex and 0x3F)

            currentGen[wordIndex] = currentGen[wordIndex] or bitMask
        }

        insertCount++
        if (insertCount >= maxInsertsPerGen) {
            rotateGenerations()
        }
    }

    /**
     * Atomically checks if msgId is seen. If not seen, inserts it and returns false.
     * If already seen, returns true.
     */
    fun checkAndAdd(msgId: Int): Boolean = lock.write {
        checkAndRotateIfNeeded()
        val h1 = hash1(msgId)
        val h2 = hash2(msgId)

        var presentInCurrent = true
        var presentInPrevious = true

        for (i in 0 until HASH_COUNT) {
            val bitIndex = Math.floorMod(h1 + i * h2, BIT_SIZE)
            val wordIndex = bitIndex shr 6
            val bitMask = 1L shl (bitIndex and 0x3F)

            if ((currentGen[wordIndex] and bitMask) == 0L) {
                presentInCurrent = false
            }
            if ((previousGen[wordIndex] and bitMask) == 0L) {
                presentInPrevious = false
            }
        }

        if (presentInCurrent || presentInPrevious) {
            return true // Duplicate!
        }

        // Add to current generation
        for (i in 0 until HASH_COUNT) {
            val bitIndex = Math.floorMod(h1 + i * h2, BIT_SIZE)
            val wordIndex = bitIndex shr 6
            val bitMask = 1L shl (bitIndex and 0x3F)
            currentGen[wordIndex] = currentGen[wordIndex] or bitMask
        }

        insertCount++
        if (insertCount >= maxInsertsPerGen) {
            rotateGenerations()
        }
        return false // New entry
    }

    private fun checkAndRotateIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastRotateTime >= rotationIntervalMs) {
            rotateGenerations()
        }
    }

    private fun rotateGenerations() {
        System.arraycopy(currentGen, 0, previousGen, 0, LONG_COUNT)
        currentGen.fill(0L)
        insertCount = 0
        lastRotateTime = System.currentTimeMillis()
    }

    fun clear() = lock.write {
        currentGen.fill(0L)
        previousGen.fill(0L)
        insertCount = 0
        lastRotateTime = System.currentTimeMillis()
    }

    // Murmur-inspired mixing functions
    private fun hash1(k: Int): Int {
        var x = k
        x = x xor (x ushr 16)
        x *= -0x7a143595 // 0x85EBCA6B
        x = x xor (x ushr 13)
        x *= -0x3d4d51cb // 0xC2B2AE35
        x = x xor (x ushr 16)
        return x
    }

    private fun hash2(k: Int): Int {
        var x = k xor 0x5bd1e995
        x = (x xor (x ushr 15)) * 0x9e3779b9.toInt()
        x = x xor (x ushr 12)
        return x or 1 // Ensure odd number for coprime step
    }
}
