package com.team.vocalink.alert

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.team.vocalink.core.ProtocolConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

data class GeofenceCheckResult(
    val isInside: Boolean,
    val distanceMeters: Double,
    val targetLat: Double,
    val targetLon: Double,
    val radiusMeters: Double
)

/**
 * Offline Haversine Geofence Engine with NavIC / GPS satellite constellation tracking.
 * Calculates geodesic distance without external maps or network access.
 * Preconfigured with the Mysuru flood disaster benchmark (Lat: 12.2958, Lon: 76.6393).
 */
class GeofenceManager(private val context: Context) : LocationListener {

    companion object {
        private const val TAG = "GeofenceManager"
        private const val EARTH_RADIUS_METERS = 6371000.0
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _navicSatelliteCount = MutableStateFlow(0)
    val navicSatelliteCount: StateFlow<Int> = _navicSatelliteCount.asStateFlow()

    private val _isGpsLocked = MutableStateFlow(false)
    val isGpsLocked: StateFlow<Boolean> = _isGpsLocked.asStateFlow()

    private var gnssCallback: GnssStatus.Callback? = null

    // Default emergency reference zone: Mysuru flood disaster zone
    var referenceLat: Double = ProtocolConstants.BENCHMARK_MYSURU_LAT
    var referenceLon: Double = ProtocolConstants.BENCHMARK_MYSURU_LON
    var referenceRadiusMeters: Double = ProtocolConstants.BENCHMARK_DEFAULT_RADIUS_METERS

    @SuppressLint("MissingPermission")
    fun start() {
        val manager = locationManager ?: run {
            Log.e(TAG, "LocationManager is null")
            return
        }

        try {
            // Attempt to seed with last known location
            val lastGps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val seed = lastGps ?: lastNet
            if (seed != null) {
                _currentLocation.value = seed
                _isGpsLocked.value = true
                Log.i(TAG, "Seeded location with lat=${seed.latitude}, lon=${seed.longitude}")
            }

            // Register continuous GPS/NavIC updates
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L, // 2 seconds
                    1.0f,  // 1 meter
                    this
                )
            }

            // Register GNSS status for NavIC (IRNSS constellation) tracking
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        var navicCount = 0
                        val totalSatellites = status.satelliteCount
                        for (i in 0 until totalSatellites) {
                            // GnssStatus.CONSTELLATION_IRNSS is 7 (NavIC)
                            if (status.getConstellationType(i) == GnssStatus.CONSTELLATION_IRNSS) {
                                if (status.usedInFix(i)) {
                                    navicCount++
                                }
                            }
                        }
                        _navicSatelliteCount.value = navicCount
                    }
                }
                gnssCallback = cb
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    manager.registerGnssStatusCallback(androidx.core.content.ContextCompat.getMainExecutor(context), cb)
                } else {
                    @Suppress("DEPRECATION")
                    manager.registerGnssStatusCallback(cb)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location tracking", e)
        }
    }

    fun stop() {
        try {
            locationManager?.removeUpdates(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssCallback?.let { locationManager?.unregisterGnssStatusCallback(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location updates", e)
        }
    }

    /**
     * Checks if coordinates fall within the disaster geofence using the offline Haversine formula.
     */
    fun checkPoint(
        targetLat: Double,
        targetLon: Double,
        radiusMeters: Double = referenceRadiusMeters
    ): GeofenceCheckResult {
        val current = _currentLocation.value
        val originLat = current?.latitude ?: referenceLat
        val originLon = current?.longitude ?: referenceLon

        val distance = calculateHaversineDistance(originLat, originLon, targetLat, targetLon)
        val inside = distance <= radiusMeters

        return GeofenceCheckResult(
            isInside = inside,
            distanceMeters = distance,
            targetLat = targetLat,
            targetLon = targetLon,
            radiusMeters = radiusMeters
        )
    }

    /**
     * Strict offline Haversine spherical distance computation.
     */
    fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0).pow(2.0) + cos(phi1) * cos(phi2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    override fun onLocationChanged(location: Location) {
        _currentLocation.value = location
        _isGpsLocked.value = true
    }

    override fun onProviderEnabled(provider: String) {
        Log.i(TAG, "Location provider enabled: $provider")
    }

    override fun onProviderDisabled(provider: String) {
        Log.w(TAG, "Location provider disabled: $provider")
        if (provider == LocationManager.GPS_PROVIDER) {
            _isGpsLocked.value = false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
