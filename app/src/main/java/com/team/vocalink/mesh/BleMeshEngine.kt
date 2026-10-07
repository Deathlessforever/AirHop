package com.team.vocalink.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.team.vocalink.core.ProtocolConstants
import com.team.vocalink.security.AirHopPacketAuthenticator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BleMeshEngine(
    private val context: Context,
    private val packetReceiver: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "BleMeshEngine"
        private const val QUEUE_LIMIT = 64
        private const val HOLD_MS = 120L
        private const val PEER_TTL_MS = 30_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var currentAdvSet: AdvertisingSet? = null
    private var presenceAdvSet: AdvertisingSet? = null
    private val presenceUuid = android.os.ParcelUuid(java.util.UUID.fromString("0000FD70-0000-1000-8000-00805F9B34FB"))
    private val nodeIdentity = com.team.vocalink.core.NodeIdentity(context)
    private var presenceLocationProvider: (() -> Triple<Double, Double, Boolean>?)? = null
    private var presenceCallback: ((Int, Double, Double, Int) -> Unit)? = null
    private var isScanning = false

    private val authenticator = AirHopPacketAuthenticator(context)
    private val udpMeshSocket = UdpMeshSocket(context, packetReceiver)
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private val pumpRunning = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var scanExecutor = Executors.newSingleThreadExecutor()
    private val peers = ConcurrentHashMap<String, Long>()

    private val _isCodedPhySupported = MutableStateFlow(false)
    val isCodedPhySupported: StateFlow<Boolean> = _isCodedPhySupported.asStateFlow()
    private val _activePeerCount = MutableStateFlow(0)
    val activePeerCount: StateFlow<Int> = _activePeerCount.asStateFlow()
    private val _isPowerSaveMode = MutableStateFlow(false)
    val isPowerSaveMode: StateFlow<Boolean> = _isPowerSaveMode.asStateFlow()

    init {
        val adapter = bluetoothAdapter
        _isCodedPhySupported.value = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                adapter?.isLeCodedPhySupported == true &&
                adapter.isLeExtendedAdvertisingSupported
        } catch (e: SecurityException) {
            // Bluetooth is optional. A denied BLUETOOTH_CONNECT permission must
            // not prevent the rest of AirHop (typed messaging/UI) from starting.
            Log.w(TAG, "Bluetooth capability query denied; BLE transport disabled", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Bluetooth capability query failed; BLE transport disabled", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun setPresenceLocationProvider(provider: (() -> Triple<Double, Double, Boolean>?)?) { presenceLocationProvider = provider }
    fun setPresenceObserver(observer: ((Int, Double, Double, Int) -> Unit)?) { presenceCallback = observer }

    fun start() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner
        if (scanExecutor.isShutdown || scanExecutor.isTerminated) {
            scanExecutor = Executors.newSingleThreadExecutor()
        }
        startScanning()
        startPresenceAdvertising()
        handler.postDelayed(presenceRefresh, 10_000L)
        udpMeshSocket.start()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopScanning()
        stopAdvertising()
        stopPresenceAdvertising()
        udpMeshSocket.stop()
        queue.clear()
        scanExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("MissingPermission")
    fun setPowerSaveMode(enabled: Boolean) {
        if (_isPowerSaveMode.value == enabled) return
        _isPowerSaveMode.value = enabled
        if (isScanning) {
            stopScanning()
            startScanning()
        }
    }

    private var secondaryBroadcaster: ((ByteArray) -> Unit)? = null
    fun setSecondaryBroadcaster(broadcaster: ((ByteArray) -> Unit)?) { secondaryBroadcaster = broadcaster }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val s = scanner ?: return
        if (isScanning) return
        val filter = ScanFilter.Builder().setServiceData(ProtocolConstants.PARCEL_SERVICE_UUID, null).build()
        val presenceFilter = ScanFilter.Builder().setServiceData(presenceUuid, null).build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (_isPowerSaveMode.value) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            s.startScan(listOf(filter, presenceFilter), settings, scanCallback)
            isScanning = true
        } catch (e: Exception) {
            Log.e(TAG, "BLE scan start failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!isScanning) return
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    fun broadcastPacket(packet40Bytes: ByteArray) {
        if (packet40Bytes.size != ProtocolConstants.PACKET_SIZE ||
            packet40Bytes[0] != ProtocolConstants.AIRHOP_PREAMBLE) return

        val secure = authenticator.wrap(packet40Bytes)
        udpMeshSocket.broadcastPacket(secure)

        while (queue.size >= QUEUE_LIMIT) queue.poll()
        queue.offer(secure)
        pump()
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        if (!pumpRunning.compareAndSet(false, true)) return
        handler.post {
            try {
                val frame = queue.poll() ?: return@post
                val adv = advertiser ?: return@post
                val adapter = bluetoothAdapter ?: return@post
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !adapter.isLeExtendedAdvertisingSupported) return@post
                if (adapter.getLeMaximumAdvertisingDataLength() < AirHopPacketAuthenticator.SECURE_FRAME_SIZE + 3) return@post

                val data = AdvertiseData.Builder()
                    .addServiceData(ProtocolConstants.PARCEL_SERVICE_UUID, frame)
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .build()
                val secondary = if (_isCodedPhySupported.value) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M
                val params = AdvertisingSetParameters.Builder()
                    .setLegacyMode(false)
                    .setConnectable(false)
                    .setScannable(false)
                    .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
                    .setSecondaryPhy(secondary)
                    .setInterval(if (_isPowerSaveMode.value) AdvertisingSetParameters.INTERVAL_MEDIUM else AdvertisingSetParameters.INTERVAL_LOW)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build()

                if (currentAdvSet == null) {
                    adv.startAdvertisingSet(params, data, null, null, null, advertisingSetCallback)
                } else {
                    currentAdvSet?.setAdvertisingData(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "BLE advertising failed", e)
            } finally {
                pumpRunning.set(false)
                if (queue.isNotEmpty()) handler.postDelayed({ pump() }, HOLD_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try { if (currentAdvSet != null) advertiser?.stopAdvertisingSet(advertisingSetCallback) } catch (_: Exception) {}
        currentAdvSet = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val record = result?.scanRecord ?: return
            val presence = record.getServiceData(presenceUuid)
            if (presence != null) { handlePresence(presence, result.rssi); return }
            val serviceData = record.getServiceData(ProtocolConstants.PARCEL_SERVICE_UUID) ?: return
            val packet = authenticator.unwrap(serviceData) ?: return
            if (packet.size != ProtocolConstants.PACKET_SIZE || packet[0] != ProtocolConstants.AIRHOP_PREAMBLE) return
            val address = result.device?.address ?: return
            peers[address] = System.currentTimeMillis()
            val cutoff = System.currentTimeMillis() - PEER_TTL_MS
            peers.entries.removeIf { it.value < cutoff }
            _activePeerCount.value = peers.size
            scanExecutor.execute { packetReceiver(packet, result.rssi) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPresenceAdvertising() {
        val adv = advertiser ?: return
        val location = presenceLocationProvider?.invoke() ?: return
        if (!location.third) return
        val latE7 = (location.first * 1e7).toInt()
        val lonE7 = (location.second * 1e7).toInt()
        val payload = buildPresencePayload(latE7, lonE7, System.currentTimeMillis())
        try {
            val adapter = bluetoothAdapter ?: return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !adapter.isLeExtendedAdvertisingSupported) return
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
                .setSecondaryPhy(if (_isCodedPhySupported.value) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M)
                .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceData(presenceUuid, payload)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
            if (presenceAdvSet == null) {
                adv.startAdvertisingSet(params, data, null, null, null, presenceAdvertisingSetCallback)
            } else {
                presenceAdvSet?.setAdvertisingData(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLE presence advertising failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopPresenceAdvertising() {
        try { if (presenceAdvSet != null) advertiser?.stopAdvertisingSet(presenceAdvertisingSetCallback) } catch (_: Exception) {}
        presenceAdvSet = null
    }

    private val presenceRefresh = object : Runnable {
        override fun run() {
            if (bluetoothAdapter?.isEnabled == true) startPresenceAdvertising()
            handler.postDelayed(this, 10_000L)
        }
    }

    private fun buildPresencePayload(latE7: Int, lonE7: Int, timestampMs: Long): ByteArray {
        val body = java.nio.ByteBuffer.allocate(25).order(java.nio.ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('A'.code.toByte(), 'H'.code.toByte(), 'P'.code.toByte(), 'R'.code.toByte()))
            putInt(nodeIdentity.intId())
            putInt(latE7)
            putInt(lonE7)
            putLong(timestampMs)
            put(1.toByte())
        }.array()
        return body + presenceMac(body)
    }

    private fun presenceMac(body: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(authenticator.exportKey().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body).copyOf(8)
    }

    private fun handlePresence(payload: ByteArray, rssi: Int) {
        if (payload.size != 33) return
        val body = payload.copyOfRange(0, 25)
        val tag = payload.copyOfRange(25, 33)
        if (!java.security.MessageDigest.isEqual(presenceMac(body), tag)) return
        val buffer = java.nio.ByteBuffer.wrap(body).order(java.nio.ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4); buffer.get(magic)
        if (!magic.contentEquals(byteArrayOf('A'.code.toByte(), 'H'.code.toByte(), 'P'.code.toByte(), 'R'.code.toByte()))) return
        val nodeId = buffer.int
        val lat = buffer.int / 1e7
        val lon = buffer.int / 1e7
        val timestamp = buffer.long
        val relayCapable = buffer.get().toInt() != 0
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - timestamp) > 120_000L) return
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        presenceCallback?.invoke(nodeId, lat, lon, rssi)
    }

    private val presenceAdvertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS) {
                presenceAdvSet = advertisingSet
                Log.i(TAG, "BLE presence advertising active")
            } else {
                Log.w(TAG, "BLE presence advertising start failed: $status")
            }
        }
        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            if (presenceAdvSet == advertisingSet) presenceAdvSet = null
        }
    }

    private val advertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS) {
                currentAdvSet = advertisingSet
                Log.i(TAG, "BLE extended advertising active")
            } else {
                Log.w(TAG, "BLE advertising start failed: $status")
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            if (currentAdvSet == advertisingSet) currentAdvSet = null
        }
    }
}
