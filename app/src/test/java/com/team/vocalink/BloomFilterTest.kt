package com.team.vocalink

import com.team.vocalink.mesh.RotatingBloomFilter
import org.junit.Assert.*
import org.junit.Test

class BloomFilterTest {

    @Test
    fun testInsertAndDuplicateRejection() {
        val filter = RotatingBloomFilter()

        val id1 = 0x12345678
        val id2 = 0x7FFFFFFF
        val id3 = -0x12345678

        assertFalse("Unseen ID should return false", filter.contains(id1))
        assertFalse("checkAndAdd on new ID should return false", filter.checkAndAdd(id1))
        assertTrue("Duplicate checkAndAdd should return true", filter.checkAndAdd(id1))
        assertTrue("Contains after add should return true", filter.contains(id1))

        assertFalse("Unseen ID2 should return false", filter.contains(id2))
        assertFalse("checkAndAdd on new ID2 should return false", filter.checkAndAdd(id2))
        assertTrue("Contains ID2 should return true", filter.contains(id2))

        assertFalse("Unseen ID3 should return false", filter.contains(id3))
        assertFalse("checkAndAdd on new ID3 should return false", filter.checkAndAdd(id3))
        assertTrue("Contains ID3 should return true", filter.contains(id3))
    }

    @Test
    fun testGenerationalRotation() {
        // Filter configured with 5 max items per generation
        val filter = RotatingBloomFilter(rotationIntervalMs = 100_000L, maxInsertsPerGen = 5)

        for (i in 1..5) {
            filter.add(i)
        }

        // 6th insert triggers rotation: items 1..5 move to previousGen
        filter.add(6)

        // Previous generation items should still be recognized!
        for (i in 1..6) {
            assertTrue("Item $i should still be recognized after 1 generation shift", filter.contains(i))
        }

        // Fill current generation again (items 7, 8, 9, 10, 11) to cause second rotation
        for (i in 7..11) {
            filter.add(i)
        }

        // Now items 6..11 are in previousGen, items 1..5 have been naturally aged out
        for (i in 7..11) {
            assertTrue("Recent item $i should be recognized", filter.contains(i))
        }
    }
}
