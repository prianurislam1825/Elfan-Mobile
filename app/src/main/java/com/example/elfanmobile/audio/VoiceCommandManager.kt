package com.example.elfanmobile.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VoiceCommandManager
 *
 * Koordinator State Machine terpusat untuk asisten suara ELFAN:
 * STANDBY -> WAKE_DETECTED -> SPEAKING_PROMPT -> LISTENING_COMMAND -> PROCESSING_COMMAND -> SPEAKING_RESPONSE -> RECOVERING -> STANDBY
 */
object VoiceCommandManager {
    private const val TAG = "VoiceCommandManager"

    enum class AssistantState {
        STANDBY,            // Memantau mikrofon dan mendeteksi wake word
        WAKE_DETECTED,      // Wake word "Oke Elfan" terdeteksi
        SPEAKING_PROMPT,    // Mengucapkan "Ada yang bisa saya bantu?"
        LISTENING_COMMAND,  // Merekam perintah suara pengguna (VAD aktif)
        PROCESSING_COMMAND, // Mengirim audio dan menunggu respons backend Raspberry Pi
        SPEAKING_RESPONSE,  // Mengucapkan hasil eksekusi perintah melalui TTS
        RECOVERING          // Membersihkan resource & jeda pemulihan sebelum kembali STANDBY
    }

    private val _state = MutableStateFlow(AssistantState.STANDBY)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("Siap menerima perintah")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var ttsManager: TtsManager? = null

    /**
     * Mendaftarkan instance TtsManager terpusat.
     */
    fun registerTtsManager(manager: TtsManager) {
        ttsManager = manager
        Log.d(TAG, "TtsManager berhasil didaftarkan ke VoiceCommandManager")
    }

    /**
     * Memutar respons suara TTS terpusat.
     */
    fun speak(
        text: String,
        onDone: () -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        val tts = ttsManager
        if (tts != null) {
            tts.speak(
                text = text,
                onDone = onDone,
                onError = { err ->
                    Log.w(TAG, "TTS playback error ($err), menjalankan fallback onDone")
                    onError?.invoke(err) ?: onDone()
                }
            )
        } else {
            Log.w(TAG, "TtsManager belum siap, langsung memanggil onDone")
            onDone()
        }
    }

    /**
     * Menghentikan TTS jika sedang berbicara.
     */
    fun stopSpeaking() {
        ttsManager?.stop()
    }

    /**
     * Memperbarui status asisten suara dengan pengaman transisi thread-safe.
     */
    @Synchronized
    fun updateState(newState: AssistantState, customMessage: String? = null) {
        val oldState = _state.value
        Log.d(TAG, "State transition: $oldState -> $newState")
        _state.value = newState

        _statusMessage.value = customMessage ?: when (newState) {
            AssistantState.STANDBY -> "● STANDBY (Katakan 'Oke Elfan')"
            AssistantState.WAKE_DETECTED -> "🚨 WAKE WORD TERDETEKSI"
            AssistantState.SPEAKING_PROMPT -> "🗣️ ELFAN: \"Ada yang bisa saya bantu?\""
            AssistantState.LISTENING_COMMAND -> "🎙️ MENDENGARKAN PERINTAH..."
            AssistantState.PROCESSING_COMMAND -> "⏳ MEMPROSES KE ELFAN..."
            AssistantState.SPEAKING_RESPONSE -> "🗣️ ELFAN MENJAWAB..."
            AssistantState.RECOVERING -> "🔄 MENYIAPKAN STANDBY..."
        }
    }

    /**
     * Memindahkan status ke RECOVERING lalu otomatis kembali ke STANDBY setelah jeda.
     */
    suspend fun transitionToStandby(cooldownMs: Long = 300L) {
        updateState(AssistantState.RECOVERING)
        kotlinx.coroutines.delay(cooldownMs)
        updateState(AssistantState.STANDBY)
    }
}
