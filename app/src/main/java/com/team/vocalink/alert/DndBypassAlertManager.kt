package com.team.vocalink.alert

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * DND Bypass Alert Engine: Forces audible emergency alarm playback by binding to
 * AudioManager.STREAM_ALARM with FLAG_ALLOW_RINGER_MODES and locking stream volume to maximum
 * whenever an Emergency SOS packet breaches the local geofence.
 */
class DndBypassAlertManager(private val context: Context) {

    companion object {
        private const val TAG = "DndBypassAlertManager"
        private const val SAMPLE_RATE = 44100
        private const val SIREN_DURATION_MS = 6000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val alertScope = CoroutineScope(Dispatchers.Default)

    private var activeAlarmJob: Job? = null
    private var isAlarmPlaying = false

    fun isAlarmActive(): Boolean = isAlarmPlaying

    /**
     * Triggers the high-priority DND bypass emergency siren.
     */
    @Synchronized
    fun triggerSosAlarm(msgId: Int = 0, lat: Double = 0.0, lon: Double = 0.0, loopContinuous: Boolean = false) {
        if (isAlarmPlaying) {
            Log.i(TAG, "Alarm already active for concurrent SOS, skipping duplicate trigger")
            return
        }

        activeAlarmJob?.cancel()
        activeAlarmJob = alertScope.launch {
            try {
                isAlarmPlaying = true
                Log.w(TAG, ">>> EXECUTING DND BYPASS ALARM FOR SOS MSG $msgId AT [$lat, $lon] (continuous=$loopContinuous) <<<")

                // 1. DND Bypass Policy check and volume lock
                val mgr = audioManager
                if (mgr != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val hasDndAccess = notificationManager?.isNotificationPolicyAccessGranted == true
                        if (hasDndAccess) {
                            mgr.ringerMode = AudioManager.RINGER_MODE_NORMAL
                        }
                    }

                    val maxVolume = mgr.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    // Bind to STREAM_ALARM with FLAG_ALLOW_RINGER_MODES and lock to max volume
                    mgr.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        maxVolume,
                        AudioManager.FLAG_ALLOW_RINGER_MODES or AudioManager.FLAG_SHOW_UI
                    )
                }

                // 2. Play synthesized tactical disaster siren waveform via AudioTrack
                if (loopContinuous) {
                    while (isActive && isAlarmPlaying) {
                        playSynthesizedEmergencySiren()
                        delay(300)
                    }
                } else {
                    playSynthesizedEmergencySiren()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error executing DND bypass alarm", e)
            } finally {
                isAlarmPlaying = false
            }
        }
    }

    /**
     * Generates a dual-tone warble emergency siren pattern (800 Hz <-> 1200 Hz).
     */
    private suspend fun playSynthesizedEmergencySiren() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            audioTrack.play()

            val chunkSamples = 1024
            val pcmChunk = ShortArray(chunkSamples)
            val startTime = System.currentTimeMillis()
            var phase = 0.0

            while (System.currentTimeMillis() - startTime < SIREN_DURATION_MS) {
                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0

                // 2 Hz oscillation between 800 Hz and 1300 Hz
                val sweepFreq = 1000.0 + 300.0 * sin(2.0 * Math.PI * 2.0 * elapsedSec)

                for (i in 0 until chunkSamples) {
                    val sample = (sin(phase) * 32000.0).toInt().toShort()
                    pcmChunk[i] = sample
                    phase += 2.0 * Math.PI * sweepFreq / SAMPLE_RATE
                    if (phase > 2.0 * Math.PI) {
                        phase -= 2.0 * Math.PI
                    }
                }

                audioTrack.write(pcmChunk, 0, chunkSamples)
                delay(10)
            }

            audioTrack.stop()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack playback error", e)
        } finally {
            audioTrack.release()
        }
    }

    fun stopAlarm() {
        activeAlarmJob?.cancel()
        isAlarmPlaying = false
    }
}
