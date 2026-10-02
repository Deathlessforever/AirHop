package com.team.vocalink.mesh

import android.util.Log
import com.team.vocalink.alert.DndBypassAlertManager
import com.team.vocalink.alert.GeofenceManager
import com.team.vocalink.alert.NeuralTtsHook
import com.team.vocalink.core.AirHopNative
import com.team.vocalink.core.PacketAction
import com.team.vocalink.core.PacketRepairResult
import com.team.vocalink.core.ProtocolConstants
import com.team.vocalink.core.WaterfallLogItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Blind Relay Engine: Dedicated background engine that intercepts packets from BLE Coded PHY,
 * performs sub-millisecond duplicate suppression via rotating Bloom Filter, repairs corrupted
 * bytes with RS(40,32), decrements TTL in native C++, and re-broadcasts without UI thread dispatch.
 */
class BlindRelayManager(
    private val bleMeshEngine: BleMeshEngine,
    private val geofenceManager: GeofenceManager,
    private val dndBypassAlertManager: DndBypassAlertManager,
    private val neuralTtsHook: NeuralTtsHook
) {
    companion object {
        private const val TAG = "BlindRelayManager"
    }

    private val relayScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val bloomFilter = RotatingBloomFilter()

    private val _waterfallEvents = MutableSharedFlow<WaterfallLogItem>(replay = 50)
    val waterfallEvents: SharedFlow<WaterfallLogItem> = _waterfallEvents.asSharedFlow()

    // Callback hook for ChatManager and UI packet dispatch
    var onPacketDecoded: ((PacketRepairResult) -> Unit)? = null

    fun registerSentMessageId(msgId: Int) {
        bloomFilter.add(msgId)
    }

    /**
     * Entry point for incoming BLE scan packets. Runs completely off the UI thread.
     */
    fun onRawPacketScanned(rawPacket: ByteArray, rssi: Int) {
        if (rawPacket.size != ProtocolConstants.PACKET_SIZE ||
            rawPacket[0] != ProtocolConstants.AIRHOP_PREAMBLE) {
            return
        }

        relayScope.launch {
            processPacketPipeline(rawPacket, rssi)
        }
    }

    private fun processPacketPipeline(rawPacket: ByteArray, rssi: Int) {
        // 1. Decode and automatic RS(40,32) error correction in Native C++
        val decodeResult: PacketRepairResult? = AirHopNative.decodeAndRepairPacket(rawPacket)
        if (decodeResult == null || !decodeResult.success) {
            Log.w(TAG, "Packet $preliminaryMsgId dropped: unrecoverable RS FEC errors (>4 bytes corrupted)")
            return
        }

        val repairedBytes = decodeResult.repairedPacket ?: rawPacket
        val msgId = decodeResult.msgId

        // Duplicate suppression is performed after FEC repair so corrupted msg_id bytes
        // cannot poison the deduplication table with a false identity.
        if (bloomFilter.checkAndAdd(msgId)) return
        val flags = decodeResult.flags
        val ttl = decodeResult.ttl
        val latE7 = decodeResult.latE7
        val lonE7 = decodeResult.lonE7
        val tokens = decodeResult.tokens ?: ByteArray(13)

        // Dispatch to ChatManager / UI
        onPacketDecoded?.invoke(decodeResult)

        // If this is an ACK delivery receipt, finish pipeline without sounding sirens
        val isAck = (flags and ProtocolConstants.FLAG_ACK.toInt()) != 0

        // 2. Geofence evaluation against NavIC / GPS coordinates
        val geofenceStatus = geofenceManager.checkPoint(latE7 / 1e7, lonE7 / 1e7)
        val inGeofence = geofenceStatus.isInside
        val distance = geofenceStatus.distanceMeters

        // 3. Blind Relay: Decrement TTL and immediately re-broadcast if hops remain
        var relayAction = PacketAction.INGESTED
        if (ttl > 1) {
            val decrementedPacket = AirHopNative.verifyAndDecrementTTL(repairedBytes)
            if (decrementedPacket != null) {
                // Re-broadcast over BLE Coded PHY without UI thread switching
                bleMeshEngine.broadcastPacket(decrementedPacket)
                relayAction = PacketAction.RELAYED
                Log.i(TAG, "Blind Relay: Packet $msgId rebroadcast with TTL=${ttl - 1}")
            } else {
                relayAction = PacketAction.DROPPED_TTL
            }
        } else {
            relayAction = PacketAction.DROPPED_TTL
            Log.i(TAG, "Packet $msgId reached hop limit (TTL=$ttl), not relaying further")
        }

        if (isAck) return

        // 4. Alert & SOS Handling
        val isEmergencySos = decodeResult.isEmergencySos
        if (isEmergencySos && inGeofence) {
            relayAction = PacketAction.ALERT_TRIGGERED
            Log.w(TAG, "EMERGENCY SOS TARGETING CURRENT GEOFENCE! Triggering DND Bypass")
            dndBypassAlertManager.triggerSosAlarm(
                msgId = msgId,
                lat = decodeResult.latitude,
                lon = decodeResult.longitude
            )
        }

        // 5. Neural Indic Voice Synthesis hook
        if (tokens.any { it != 0.toByte() }) {
            neuralTtsHook.synthesizeAndPlayTokens(
                tokens = tokens,
                languageId = (flags and ProtocolConstants.FLAG_LANG_MASK.toInt()).toByte()
            )
        }

        // 6. Push to Waterfall Log Flow for HUD UI
        val tokensSummary = tokens.joinToString(" ") { "%02X".format(it) }
        val logItem = WaterfallLogItem(
            msgId = msgId,
            ttl = ttl,
            isSos = isEmergencySos,
            action = if (decodeResult.hadErrors) PacketAction.REPAIRED else relayAction,
            correctedBytes = decodeResult.correctedBytes,
            lat = decodeResult.latitude,
            lon = decodeResult.longitude,
            distanceMeters = distance,
            inGeofence = inGeofence,
            tokensSummary = tokensSummary
        )
        _waterfallEvents.tryEmit(logItem)
    }

    /**
     * Initiates an originating packet transmission from this local node.
     */
    fun broadcastOriginPacket(
        flags: Byte,
        ttl: Byte = ProtocolConstants.DEFAULT_TTL,
        targetZone: Int,
        latE7: Int,
        lonE7: Int,
        tokens: ByteArray,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        relayScope.launch {
            val encodedPacket = AirHopNative.encodePacket(
                flags = flags,
                ttl = ttl,
                targetZone = targetZone,
                latE7 = latE7,
                lonE7 = lonE7,
                tokens = tokens,
                timestampMs = timestampMs
            )

            // Register in local bloom filter so we don't re-relay our own echo
            val msgId = ByteBuffer.wrap(encodedPacket, 3, 4).order(ByteOrder.LITTLE_ENDIAN).int
            bloomFilter.add(msgId)

            // Broadcast over BLE Coded PHY
            bleMeshEngine.broadcastPacket(encodedPacket)

            val logItem = WaterfallLogItem(
                msgId = msgId,
                ttl = ttl.toInt(),
                isSos = (flags.toInt() and ProtocolConstants.FLAG_EMERGENCY_SOS.toInt()) != 0,
                action = PacketAction.INGESTED,
                correctedBytes = 0,
                lat = latE7 / 1e7,
                lon = lonE7 / 1e7,
                distanceMeters = 0.0,
                inGeofence = true,
                tokensSummary = tokens.joinToString(" ") { "%02X".format(it) }
            )
            _waterfallEvents.tryEmit(logItem)
        }
    }
}
