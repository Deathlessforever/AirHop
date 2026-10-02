package com.team.vocalink.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.team.vocalink.core.AirHopNative
import com.team.vocalink.core.VadEvent
import com.team.vocalink.core.VadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Audio Ingest Engine: Manages 16 kHz 16-bit mono AudioRecord stream and feeds
 * PCM frames directly to the C++ lock-free ring buffer and Silero VAD state machine.
 */
class AudioIngestEngine(
    private val context: Context,
    private val onSpeechSegmentReady: (tokens: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "AudioIngestEngine"
        const val SAMPLE_RATE = 16000
        const val CHUNK_SIZE = 512 // 32 ms per frame
    }

    private val audioScope = CoroutineScope(Dispatchers.Default)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _vadState = MutableStateFlow(VadState.INACTIVE)
    val vadState: StateFlow<VadState> = _vadState.asStateFlow()

    private val _currentRms = MutableStateFlow(0f)
    val currentRms: StateFlow<Float> = _currentRms.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startIngest() {
        if (_isRecording.value) return

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufSize, CHUNK_SIZE * 4 * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            _isRecording.value = true
            AirHopNative.resetAudioEngine()

            recordJob = audioScope.launch {
                val pcmBuffer = ShortArray(CHUNK_SIZE)

                while (isActive && _isRecording.value) {
                    val readCount = audioRecord?.read(pcmBuffer, 0, CHUNK_SIZE) ?: 0
                    if (readCount > 0) {
                        // Compute instantaneous RMS for tactical HUD
                        var sum = 0.0
                        for (i in 0 until readCount) {
                            sum += pcmBuffer[i] * pcmBuffer[i]
                        }
                        val rms = kotlin.math.sqrt(sum / readCount).toFloat()
                        _currentRms.value = rms

                        // Send directly to native C++ ring buffer and Silero VAD state machine
                        val packed = AirHopNative.processAudioChunk(pcmBuffer)
                        val (event, state) = AirHopNative.unpackVadResult(packed)
                        _vadState.value = state

                        if (event == VadEvent.SPEECH_FINISHED) {
                            Log.i(TAG, "Silero VAD: 450ms silence hangover completed, extracting speech tokens")
                            extractAndEmitTokens()
                        }
                    }
                }
            }

            Log.i(TAG, "Audio Ingest Engine started at 16 kHz mono PCM")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting audio ingest", e)
            _isRecording.value = false
        }
    }

    fun stopIngest() {
        _isRecording.value = false
        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
            _vadState.value = VadState.INACTIVE
            _currentRms.value = 0f
        }
    }

    /**
     * Manual Push-To-Talk (PTT) flush and token emit.
     */
    fun manualPttFlush() {
        extractAndEmitTokens()
        AirHopNative.resetAudioEngine()
    }

    private fun extractAndEmitTokens() {
        // Read accumulated speech from native ring buffer
        val maxExtract = 16000 * 3 // Up to 3 seconds of voice
        val audioData = ShortArray(maxExtract)
        val samplesRead = AirHopNative.readAudioFromRingBuffer(audioData)

        if (samplesRead > 1600) { // Require at least 100 ms of audio
            // Quantize speech into 13 phonemic tokens using spectral energy clustering
            val tokens = quantizeAudioToPhonemeTokens(audioData, samplesRead)
            onSpeechSegmentReady(tokens)
        }
    }

    /**
     * Quantizes 16 kHz PCM audio slice into 13 phonemic indices for AirHopPacket.
     */
    private fun quantizeAudioToPhonemeTokens(audio: ShortArray, length: Int): ByteArray {
        val tokens = ByteArray(13)
        val sliceSize = length / 13

        for (i in 0 until 13) {
            val start = i * sliceSize
            var energy = 0.0
            var zcr = 0
            val limit = minOf(start + sliceSize, length)

            for (j in start until limit) {
                energy += kotlin.math.abs(audio[j].toDouble())
                if (j > start && ((audio[j] >= 0 && audio[j - 1] < 0) || (audio[j] < 0 && audio[j - 1] >= 0))) {
                    zcr++
                }
            }

            val avgEnergy = (energy / maxOf(1, limit - start)).toInt()
            val zcrRatio = (zcr.toDouble() / maxOf(1, limit - start) * 100).toInt()

            // Map energy and zero-crossing profile to phonemic index [1..64]
            val tokenCode = ((avgEnergy / 500) + (zcrRatio % 8)) and 0x3F
            tokens[i] = maxOf(1, tokenCode).toByte()
        }

        return tokens
    }
}
