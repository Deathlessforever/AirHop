package com.team.vocalink

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GeofenceTest {

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0).pow(2.0) + cos(phi1) * cos(phi2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }

    @Test
    fun testMysuruFloodGeofence() {
        val mysuruCenterLat = 12.2958
        val mysuruCenterLon = 76.6393
        val disasterRadiusMeters = 5000.0 // 5 km radius

        // 1. Same point distance must be 0
        val distZero = haversine(mysuruCenterLat, mysuruCenterLon, mysuruCenterLat, mysuruCenterLon)
        assertEquals(0.0, distZero, 0.001)

        // 2. Point ~1.5 km away (Chamundi Hill base: 12.3050, 76.6500)
        val distClose = haversine(mysuruCenterLat, mysuruCenterLon, 12.3050, 76.6500)
        assertTrue("Expected ~1.5km, got $distClose", distClose in 1200.0..1800.0)
        assertTrue("Should be inside 5km geofence", distClose <= disasterRadiusMeters)

        // 3. Point ~140 km away (Bengaluru: 12.9716, 77.5946)
        val distFar = haversine(mysuruCenterLat, mysuruCenterLon, 12.9716, 77.5946)
        assertTrue("Expected ~130-150km, got $distFar", distFar in 120000.0..160000.0)
        assertFalse("Bengaluru must be OUTSIDE Mysuru geofence", distFar <= disasterRadiusMeters)
    }
}
