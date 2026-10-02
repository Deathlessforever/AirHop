package com.team.vocalink

import com.team.vocalink.alert.DisasterLogStore
import com.team.vocalink.core.PacketAction
import com.team.vocalink.core.WaterfallLogItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class DisasterLogStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: DisasterLogStore

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "airhop_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        store = DisasterLogStore(tempDir)
    }

    @After
    fun tearDown() {
        store.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun testLogAndExportCsv() {
        val item1 = WaterfallLogItem(
            msgId = 0x12345678,
            ttl = 9,
            isSos = false,
            action = PacketAction.RELAYED,
            correctedBytes = 1,
            lat = 12.2958,
            lon = 76.6393,
            distanceMeters = 250.0,
            inGeofence = true,
            tokensSummary = "01 02 03"
        )

        val item2 = WaterfallLogItem(
            msgId = 0x7EAA55FF,
            ttl = 10,
            isSos = true,
            action = PacketAction.ALERT_TRIGGERED,
            correctedBytes = 0,
            lat = 12.2960,
            lon = 76.6400,
            distanceMeters = 300.0,
            inGeofence = true,
            tokensSummary = "01 01 01"
        )

        store.logPacket(item1)
        store.logPacket(item2)

        val allLogs = store.getAllLogs()
        assertEquals("Expected 2 logs persisted", 2, allLogs.size)

        val csv = store.exportAsCsv()
        assertTrue("CSV must contain header", csv.contains("Timestamp,ISO_Time,MsgID,TTL,IsSOS"))
        assertTrue("CSV must contain item1 MsgId", csv.contains("0x12345678"))
        assertTrue("CSV must contain item2 SOS flag", csv.contains("true"))
        assertTrue("CSV must contain ALERT_TRIGGERED action", csv.contains("ALERT_TRIGGERED"))

        store.clear()
        val emptyLogs = store.getAllLogs()
        assertTrue("Store must be empty after clear", emptyLogs.isEmpty())
    }
}
