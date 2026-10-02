package com.team.vocalink.alert

import android.content.Context
import android.util.Log
import com.team.vocalink.core.PacketAction
import com.team.vocalink.core.WaterfallLogItem
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Offline Disaster Triage Log Store:
 * Persists every received and relayed packet, victim coordinates, and SOS beacons
 * to local storage in standard JSONL format for disaster triage export.
 */
class DisasterLogStore(private val storageDir: File) {

    constructor(context: Context) : this(context.filesDir)

    companion object {
        private const val TAG = "DisasterLogStore"
        private const val LOG_FILE_NAME = "airhop_disaster_triage.jsonl"
    }

    private val lock = ReentrantLock()
    private val logFile: File = File(storageDir, LOG_FILE_NAME)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    fun logPacket(item: WaterfallLogItem) = lock.withLock {
        try {
            val json = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("isoTime", isoFormat.format(Date(item.timestamp)))
                put("msgId", "0x%08X".format(item.msgId))
                put("ttl", item.ttl)
                put("isSos", item.isSos)
                put("action", item.action.name)
                put("correctedBytes", item.correctedBytes)
                put("lat", item.lat)
                put("lon", item.lon)
                put("distanceMeters", item.distanceMeters ?: -1.0)
                put("inGeofence", item.inGeofence)
                put("tokensSummary", item.tokensSummary)
            }

            FileWriter(logFile, true).use { writer ->
                writer.append(json.toString()).append("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing triage log entry", e)
        }
    }

    fun getAllLogs(): List<WaterfallLogItem> = lock.withLock {
        if (!logFile.exists()) return emptyList()

        val list = mutableListOf<WaterfallLogItem>()
        try {
            logFile.forEachLine { line ->
                if (line.isNotBlank()) {
                    val obj = JSONObject(line)
                    val actionName = obj.optString("action", PacketAction.INGESTED.name)
                    val action = try {
                        PacketAction.valueOf(actionName)
                    } catch (_: Exception) {
                        PacketAction.INGESTED
                    }

                    val dist = obj.optDouble("distanceMeters", -1.0)

                    list.add(
                        WaterfallLogItem(
                            id = obj.optString("id"),
                            timestamp = obj.optLong("timestamp"),
                            msgId = obj.optInt("msgId"),
                            ttl = obj.optInt("ttl"),
                            isSos = obj.optBoolean("isSos"),
                            action = action,
                            correctedBytes = obj.optInt("correctedBytes"),
                            lat = obj.optDouble("lat"),
                            lon = obj.optDouble("lon"),
                            distanceMeters = if (dist >= 0) dist else null,
                            inGeofence = obj.optBoolean("inGeofence"),
                            tokensSummary = obj.optString("tokensSummary")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading triage log entries", e)
        }
        return list
    }

    fun exportAsCsv(): String = lock.withLock {
        val sb = StringBuilder()
        sb.append("Timestamp,ISO_Time,MsgID,TTL,IsSOS,Action,RS_Corrected_Bytes,Latitude,Longitude,Distance_Meters,In_Geofence,Tokens\n")

        if (!logFile.exists()) return sb.toString()

        try {
            logFile.forEachLine { line ->
                if (line.isNotBlank()) {
                    val obj = JSONObject(line)
                    sb.append(obj.optLong("timestamp")).append(",")
                    sb.append(obj.optString("isoTime")).append(",")
                    sb.append(obj.optString("msgId")).append(",")
                    sb.append(obj.optInt("ttl")).append(",")
                    sb.append(obj.optBoolean("isSos")).append(",")
                    sb.append(obj.optString("action")).append(",")
                    sb.append(obj.optInt("correctedBytes")).append(",")
                    sb.append(obj.optDouble("lat")).append(",")
                    sb.append(obj.optDouble("lon")).append(",")
                    sb.append(obj.optDouble("distanceMeters")).append(",")
                    sb.append(obj.optBoolean("inGeofence")).append(",")
                    sb.append("\"").append(obj.optString("tokensSummary")).append("\"\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting triage CSV", e)
        }

        return sb.toString()
    }

    fun clear() = lock.withLock {
        if (logFile.exists()) {
            logFile.delete()
        }
    }
}
