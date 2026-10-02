package com.team.vocalink.alert

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optical Emergency Beacon: Pulses the phone's camera LED torch in the
 * international SOS Morse code pattern (... --- ...) to signal rescue boats,
 * helicopters, and ground teams in dark, rainy, or rubble-filled disaster zones.
 */
class FlashlightStrobeManager(private val context: Context) {

    companion object {
        private const val TAG = "FlashlightStrobe"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val scope = CoroutineScope(Dispatchers.Default)
    private var strobeJob: Job? = null
    private var isStrobing = false

    private val cameraIdWithFlash: String? by lazy {
        try {
            cameraManager?.cameraIdList?.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect camera characteristics for flash", e)
            null
        }
    }

    fun isFlashAvailable(): Boolean = cameraIdWithFlash != null

    @Synchronized
    fun startSosStrobe() {
        if (isStrobing || cameraIdWithFlash == null) return
        isStrobing = true

        strobeJob = scope.launch {
            val camId = cameraIdWithFlash ?: return@launch
            Log.i(TAG, "Starting optical SOS Morse strobe on camera $camId")

            try {
                while (isActive && isStrobing) {
                    // S: 3 short pulses
                    repeat(3) {
                        setTorch(camId, true)
                        delay(150)
                        setTorch(camId, false)
                        delay(150)
                    }
                    delay(300) // letter gap

                    // O: 3 long pulses
                    repeat(3) {
                        setTorch(camId, true)
                        delay(450)
                        setTorch(camId, false)
                        delay(150)
                    }
                    delay(300) // letter gap

                    // S: 3 short pulses
                    repeat(3) {
                        setTorch(camId, true)
                        delay(150)
                        setTorch(camId, false)
                        delay(150)
                    }

                    // Word gap before repeating SOS
                    delay(1200)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SOS strobe loop error", e)
            } finally {
                setTorch(camId, false)
                isStrobing = false
            }
        }
    }

    @Synchronized
    fun stopSosStrobe() {
        strobeJob?.cancel()
        strobeJob = null
        isStrobing = false
        val camId = cameraIdWithFlash
        if (camId != null) {
            setTorch(camId, false)
        }
    }

    private fun setTorch(cameraId: String, state: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                cameraManager?.setTorchMode(cameraId, state)
            } catch (e: Exception) {
                // Torch mode might fail if camera is temporarily locked
            }
        }
    }

    fun isRunning(): Boolean = isStrobing
}