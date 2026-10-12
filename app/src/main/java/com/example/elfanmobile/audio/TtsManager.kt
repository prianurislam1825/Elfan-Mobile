package com.example.elfanmobile.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TtsManager
 *
 * Mengelola respons audio Text-To-Speech dalam bahasa Indonesia.
 * Menyediakan callback completion listener untuk memastikan perekaman
 * baru dimulai setelah suara selesai diputar, mencegah loopback audio.
 */
class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_ID_RESPONSE = "elfan_response_utterance"
    }

    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    private var isIndonesianAvailable = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menginisialisasi TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsInstance = tts ?: return
            val idLocale = Locale.forLanguageTag("id-ID")
            val result = ttsInstance.setLanguage(idLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Bahasa Indonesia tidak didukung/missing data pada TTS, mencoba fallback default locale.")
                val defaultResult = ttsInstance.setLanguage(Locale.getDefault())
                isIndonesianAvailable = (defaultResult != TextToSpeech.LANG_MISSING_DATA && defaultResult != TextToSpeech.LANG_NOT_SUPPORTED)
            } else {
                isIndonesianAvailable = true
                Log.d(TAG, "TTS Bahasa Indonesia berhasil disetel.")
            }
            isInitialized = true
        } else {
            Log.e(TAG, "Inisialisasi TextToSpeech gagal dengan status: $status")
            isInitialized = false
        }
    }

    /**
     * Memutar teks respons suara.
     * @param text Kalimat yang diucapkan
     * @param onDone Callback saat pemutaran suara selesai sepenuhnya
     * @param onError Callback jika TTS gagal, error, atau belum siap
     */
    fun speak(
        text: String,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val ttsInstance = tts
        if (!isInitialized || ttsInstance == null) {
            Log.w(TAG, "TTS belum siap atau tidak tersedia, memicu fallback visual.")
            onError("TTS belum siap")
            return
        }

        ttsInstance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS mulai berbicara: $text")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS selesai berbicara: $text")
                onDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error saat berbicara")
                onError("Error pemutaran suara")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error dengan kode: $errorCode")
                onError("Error pemutaran suara ($errorCode)")
            }
        })

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID_RESPONSE)
        }

        val queueMode = TextToSpeech.QUEUE_FLUSH
        val result = ttsInstance.speak(text, queueMode, params, UTTERANCE_ID_RESPONSE)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "tts.speak mengembalikan TextToSpeech.ERROR")
            onError("Gagal memulai ucapan")
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error saat menghentikan TTS", e)
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error saat shutdown TTS", e)
        }
    }
}
