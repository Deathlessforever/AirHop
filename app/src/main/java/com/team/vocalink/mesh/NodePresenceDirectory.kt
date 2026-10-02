package com.team.vocalink.mesh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class AirHopNode(
    val nodeId: Int,
    val lat: Double,
    val lon: Double,
    val rssi: Int,
    val lastSeenMs: Long,
    val relayCapable: Boolean = true
)

class NodePresenceDirectory(context: Context) {
    companion object { private const val TTL_MS = 90_000L; private const val PREFS = "airhop_presence" }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val nodes = ConcurrentHashMap<Int, AirHopNode>()
    private val _nodes = MutableStateFlow<List<AirHopNode>>(emptyList())
    val nodes: StateFlow<List<AirHopNode>> = _nodes.asStateFlow()

    var visible: Boolean
        get() = prefs.getBoolean("visible", true)
        set(value) { prefs.edit().putBoolean("visible", value).apply() }

    fun observe(nodeId: Int, lat: Double, lon: Double, rssi: Int, relayCapable: Boolean) {
        nodes[nodeId] = AirHopNode(nodeId, lat, lon, rssi, System.currentTimeMillis(), relayCapable)
        prune()
    }

    fun prune() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        nodes.entries.removeIf { it.value.lastSeenMs < cutoff }
        _nodes.value = nodes.values.sortedByDescending { it.lastSeenMs }
    }

    fun clear() { nodes.clear(); _nodes.value = emptyList() }
}
