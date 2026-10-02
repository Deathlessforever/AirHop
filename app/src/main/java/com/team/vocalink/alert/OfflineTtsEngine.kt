package com.team.vocalink.alert

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Native Android Text-To-Speech engine.
 * Decodes received emergency text and speaks it aloud through the phone's speaker.
 */
class OfflineTtsEngine(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "OfflineTtsEngine"
    }

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            tts?.language = Locale.ENGLISH
            tts?.setSpeechRate(0.95f)
            tts?.setPitch(1.05f)
            Log.i(TAG, "Offline TextToSpeech initialized successfully")
        } else {
            Log.w(TAG, "Failed to initialize TextToSpeech: status=")
        }
    }

    fun speak(text: String, lang: Byte = 0) {
        if (!isReady || tts == null) {
            Log.w(TAG, "TTS not ready, queuing text: ")
            return
        }

        try {
            val locale = when (lang.toInt()) {
                com.team.vocalink.core.ProtocolConstants.LANG_KANNADA.toInt() -> Locale("kn", "IN")
                com.team.vocalink.core.ProtocolConstants.LANG_HINDI.toInt() -> Locale("hi", "IN")
                com.team.vocalink.core.ProtocolConstants.LANG_TAMIL.toInt() -> Locale("ta", "IN")
                com.team.vocalink.core.ProtocolConstants.LANG_TELUGU.toInt() -> Locale("te", "IN")
                com.team.vocalink.core.ProtocolConstants.LANG_MALAYALAM.toInt() -> Locale("ml", "IN")
                com.team.vocalink.core.ProtocolConstants.LANG_BENGALI.toInt() -> Locale("bn", "IN")
                com.team.vocalink.core.ProtocolConstants.LANG_MARATHI.toInt() -> Locale("mr", "IN")
                com.team.vocalink.core.ProtocolConstants.LANG_GUJARATI.toInt() -> Locale("gu", "IN")
                com.team.vocalink.core.ProtocolConstants.LANG_PUNJABI.toInt() -> Locale("pa", "IN")
                com.team.vocalink.core.ProtocolConstants.LANG_ODIA.toInt() -> Locale("or", "IN")
                else -> Locale.ENGLISH
            }
            try {
                tts?.language = locale
            } catch (_: Exception) {
                tts?.language = Locale.ENGLISH
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "airhop_${System.currentTimeMillis()}")
            Log.i(TAG, "Spoke aloud: '$text' in locale $locale")
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak error", e)
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {}
    }
}
