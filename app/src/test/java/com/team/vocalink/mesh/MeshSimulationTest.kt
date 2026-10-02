package com.team.vocalink.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshSimulationTest {
    private fun chain(): MeshSimulation {
        val sim = MeshSimulation(setOf(1, 2, 3, 4))
        sim.connect(1, 2)
        sim.connect(2, 3)
        sim.connect(3, 4)
        return sim
    }

    @Test fun aToD_deliversInThreeHops() {
        val result = chain().send(1, 4, 100, "hello".toByteArray())
        assertTrue(result.delivered)
        assertEquals(3, result.deliveryHops)
        assertEquals(listOf(1, 2, 3, 4), result.trace)
    }

    @Test fun failedRelay_preventsDeliveryUntilRestored() {
        val sim = chain()
        sim.setOnline(3, false)
        assertFalse(sim.send(1, 4, 101, "hello".toByteArray()).delivered)

        sim.setOnline(3, true)
        assertTrue(sim.send(1, 4, 102, "hello".toByteArray()).delivered)
    }

    @Test fun ttlPreventsInfiniteForwarding() {
        val sim = MeshSimulation(setOf(1, 2, 3, 4), maxHops = 2)
        sim.connect(1, 2)
        sim.connect(2, 3)
        sim.connect(3, 4)
        val result = sim.send(1, 4, 103, "hello".toByteArray())
        assertFalse(result.delivered)
        assertTrue(result.ttlDrops > 0)
    }
}
