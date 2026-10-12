package com.example.elfanmobile.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VoiceCommandManager
 *
 * Koordinator State Machine terpusat untuk asisten suara ELFAN:
 * STANDBY -> WAKE_WORD_DETECTED -> SPEAKING_RESPONSE -> LISTENING_COMMAND -> PROCESSING -> RETURN_TO_STANDBY
 */
object VoiceCommandManager {
    private const val TAG = "VoiceCommandManager"

    enum class AssistantState {
        STANDBY,            // Mode standby aktif, menunggu wake word
        WAKE_WORD_DETECTED, // Wake word terdeteksi
        SPEAKING_RESPONSE,  // Memutar suara konfirmasi "Baik, ada yang bisa saya bantu?"
        LISTENING_COMMAND,  // Merekam perintah pengguna (VAD aktif)
        PROCESSING,         // Mengirim & memproses perintah ke Raspberry Pi
        RETURN_TO_STANDBY   // Transisi menyelesaikan sesi dan kembali standby
    }

    private val _state = MutableStateFlow(AssistantState.STANDBY)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("Siap menerima perintah")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    /**
     * Memperbarui status asisten suara dengan pengaman transisi.
     */
    @Synchronized
    fun updateState(newState: AssistantState, customMessage: String? = null) {
        val oldState = _state.value
        Log.d(TAG, "State transition: $oldState -> $newState")
        _state.value = newState

        _statusMessage.value = customMessage ?: when (newState) {
            AssistantState.STANDBY -> "● SIAP (Katakan 'Oke Elfan')"
            AssistantState.WAKE_WORD_DETECTED -> "🚨 WAKE WORD TERDETEKSI"
            AssistantState.SPEAKING_RESPONSE -> "🗣️ ELFAN: \"Baik, ada yang bisa saya bantu?\""
            AssistantState.LISTENING_COMMAND -> "🎙️ MENDENGARKAN PERINTAH..."
            AssistantState.PROCESSING -> "⏳ MEMPROSES KE ELFAN..."
            AssistantState.RETURN_TO_STANDBY -> "🔄 MENYIAPKAN STANDBY..."
        }
    }
}
