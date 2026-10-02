package com.team.vocalink.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.team.vocalink.core.ProtocolConstants
import com.team.vocalink.security.AirHopPacketAuthenticator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BLE Coded PHY Engine: Emits and listens for non-connectable anonymous packets carrying
 * 40-byte raw AirHop disaster frames over LE Coded PHY (S=8 mode, max range) with Service UUID 0xFD6F.
 */
class BleMeshEngine(
    private val context: Context,
    private val packetReceiver: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "BleMeshEngine"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private var currentAdvSet: AdvertisingSet? = null
    private var isAdvertising = false
    private var isScanning = false

    private val _isCodedPhySupported = MutableStateFlow(false)
    val isCodedPhySupported: StateFlow<Boolean> = _isCodedPhySupported.asStateFlow()

    private val _activePeerCount = MutableStateFlow(0)
    val activePeerCount: StateFlow<Int> = _activePeerCount.asStateFlow()

    private val _isPowerSaveMode = MutableStateFlow(false)
    val isPowerSaveMode: StateFlow<Boolean> = _isPowerSaveMode.asStateFlow()

    // Dual-bearer offline UDP socket for local mesh acceleration
    private val udpMeshSocket = UdpMeshSocket(packetReceiver)\n    private val authenticator = AirHopPacketAuthenticator(context)

    // Sliding window of peer device timestamps for real-time active peer counting
    private val recentPeers = ConcurrentLinkedQueue<Pair<String, Long>>()

    init {
        checkHardwareCapabilities()
    }

    private fun checkHardwareCapabilities() {
        val adapter = bluetoothAdapter ?: return
        val codedSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            adapter.isLeCodedPhySupported && adapter.isLeExtendedAdvertisingSupported
        } else {
            false
        }
        _isCodedPhySupported.value = codedSupported
        Log.i(TAG, "Bluetooth LE Coded PHY hardware support: $codedSupported")
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "BluetoothAdapter is null")
            return
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled on host device")
            return
        }

        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        startScanning()
        udpMeshSocket.start()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopScanning()
        stopAdvertising()
        udpMeshSocket.stop()
    }

    @SuppressLint("MissingPermission")
    fun setPowerSaveMode(enabled: Boolean) {
        if (_isPowerSaveMode.value == enabled) return
        _isPowerSaveMode.value = enabled
        Log.i(TAG, "Battery Eco-Mode toggled: enabled=$enabled")
        if (isScanning) {
            stopScanning()
            startScanning()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (isScanning || scanner == null) return

        val scanFilter = ScanFilter.Builder()
            .setServiceData(ProtocolConstants.PARCEL_SERVICE_UUID, null)
            .build()

        val scanMode = if (_isPowerSaveMode.value) {
            ScanSettings.SCAN_MODE_LOW_POWER
        } else {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .build()

        try {
            scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            Log.i(TAG, "Universal BLE Scanner started in mode $scanMode for UUID 0xFD6F (ecoMode=${_isPowerSaveMode.value})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
            isScanning = false
            Log.i(TAG, "BLE Scanner stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
    }

    /**
     * Broadcasts a raw 40-byte AirHop frame over BLE Extended / Legacy and local UDP mesh.
     */
    @SuppressLint("MissingPermission")
    fun broadcastPacket(packet40Bytes: ByteArray) {
        if (packet40Bytes.size != ProtocolConstants.PACKET_SIZE) {
            Log.e(TAG, "broadcastPacket: invalid payload size ${packet40Bytes.size}, expected 40")
            return
        }

        // Dual-bearer: always dispatch over UDP mesh socket simultaneously for sub-5ms latency
        udpMeshSocket.broadcastPacket(packet40Bytes)

        val adv = advertiser ?: bluetoothAdapter?.bluetoothLeAdvertiser ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bluetoothAdapter?.isLeExtendedAdvertisingSupported == true) {
            val maxLength = bluetoothAdapter?.getLeMaximumAdvertisingDataLength() ?: 0
            if (maxLength < ProtocolConstants.PACKET_SIZE + 3) {
                Log.w(TAG, "AirHop frame not sent: controller advertising capacity=$maxLength")
                return
            }
        }

        val pdata = AdvertiseData.Builder()
            .addServiceData(ProtocolConstants.PARCEL_SERVICE_UUID, packet40Bytes)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (bluetoothAdapter?.isLeExtendedAdvertisingSupported == true)) {
            val secondaryPhy = if (_isCodedPhySupported.value) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
                .setSecondaryPhy(secondaryPhy)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MAX)
                .setAnonymous(false)
                .build()

            try {
                if (currentAdvSet != null) {
                    currentAdvSet?.setAdvertisingData(pdata)
                } else {
                    adv.startAdvertisingSet(params, pdata, null, null, null, advertisingSetCallback)
                }
                isAdvertising = true
            } catch (e: Exception) {
                Log.e(TAG, "Extended advertising failed; AirHop frame was not sent over BLE", e)
            }
        } else {
            Log.w(TAG, "AirHop frame not sent: LE Extended Advertising is unavailable")
        }
    }

    @SuppressLint("MissingPermission")
    private fun broadcastLegacy(adv: BluetoothLeAdvertiser, pdata: AdvertiseData) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        try {
            adv.startAdvertising(settings, pdata, legacyAdvCallback)
            isAdvertising = true
        } catch (e: Exception) {
            Log.e(TAG, "Legacy advertising failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && currentAdvSet != null) {
                advertiser?.stopAdvertisingSet(advertisingSetCallback)
                currentAdvSet = null
            }
            advertiser?.stopAdvertising(legacyAdvCallback)
            isAdvertising = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val record = result?.scanRecord ?: return
            val serviceData = record.getServiceData(ProtocolConstants.PARCEL_SERVICE_UUID) ?: return

            if (serviceData.size == ProtocolConstants.PACKET_SIZE &&
                serviceData[0] == ProtocolConstants.AIRHOP_PREAMBLE) {

                val deviceAddr = result.device?.address ?: "ANON_${result.rssi}"
                updatePeerList(deviceAddr)

                // Dispatch to packet receiver on calling thread (handled by BlindRelayManager)
                packetReceiver(serviceData, result.rssi)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with errorCode: $errorCode")
        }
    }

    private val advertisingSetCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: AdvertisingSet?,
                txPower: Int,
                status: Int
            ) {
                if (status == ADVERTISE_SUCCESS) {
                    currentAdvSet = advertisingSet
                    Log.i(TAG, "Coded PHY Advertising Set started successfully (txPower=$txPower)")
                } else {
                    Log.w(TAG, "Advertising Set start failed with status: $status")
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                currentAdvSet = null
                Log.i(TAG, "Advertising Set stopped")
            }
        }
    } else {
        null
    }

    private val legacyAdvCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Legacy advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Legacy advertising failed with errorCode: $errorCode")
        }
    }

    private fun updatePeerList(address: String) {
        val now = System.currentTimeMillis()
        recentPeers.add(Pair(address, now))

        // Evict entries older than 30 seconds
        val cutoff = now - 30_000L
        while (true) {
            val head = recentPeers.peek() ?: break
            if (head.second < cutoff) {
                recentPeers.poll()
            } else {
                break
            }
        }

        // Count unique peer addresses
        val uniqueCount = recentPeers.map { it.first }.toSet().size
        _activePeerCount.value = uniqueCount
    }
}
