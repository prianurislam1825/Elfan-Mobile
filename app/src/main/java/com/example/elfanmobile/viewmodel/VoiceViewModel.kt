package com.example.elfanmobile.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.elfanmobile.audio.VoiceCommandManager
import com.example.elfanmobile.audio.VoiceCommandManager.AssistantState
import com.example.elfanmobile.audio.WavRecorder
import com.example.elfanmobile.network.ElfanApi
import com.example.elfanmobile.network.ElfanResult
import com.example.elfanmobile.repository.SettingsRepository
import com.example.elfanmobile.repository.VoiceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * VoiceViewModel
 *
 * Manages the recording lifecycle, HTTP upload, and UI state.
 * Does NOT directly call AudioRecord or OkHttp — those are delegated
 * to WavRecorder and VoiceRepository.
 */
class VoiceViewModel(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val voiceRepository: VoiceRepository = VoiceRepository()
) : ViewModel() {

    // ── Recording state ───────────────────────────────────────────────────────

    sealed class RecordingState {
        object Idle : RecordingState()
        object Recording : RecordingState()
        object Uploading : RecordingState()
        data class Success(val result: ElfanResult) : RecordingState()
        data class Error(val message: String) : RecordingState()
    }

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0f)
    val recordingDuration: StateFlow<Float> = _recordingDuration.asStateFlow()

    private val _lastWavFile = MutableStateFlow<File?>(null)
    val lastWavFile: StateFlow<File?> = _lastWavFile.asStateFlow()

    private val _audioDiagnostic = MutableStateFlow<com.example.elfanmobile.audio.WavRecorder.AudioDiagnostic?>(null)
    val audioDiagnostic: StateFlow<com.example.elfanmobile.audio.WavRecorder.AudioDiagnostic?> = _audioDiagnostic.asStateFlow()

    // ── Settings ──────────────────────────────────────────────────────────────

    val raspberryPiUrl = settingsRepository.raspberryPiUrl
    val debugMode = settingsRepository.debugMode
    val wakeWordEnabled = settingsRepository.wakeWordEnabled
    val porcupineAccessKey = settingsRepository.porcupineAccessKey

    // ── Internals ─────────────────────────────────────────────────────────────

    private val wavRecorder = WavRecorder(appContext)
    private var recordingJob: Job? = null
    private var isCurrentSessionPushToTalk = false

    init {
        viewModelScope.launch {
            VoiceCommandManager.state.collect { state ->
                if (state == AssistantState.LISTENING_COMMAND) {
                    if (_recordingState.value is RecordingState.Idle) {
                        startRecording(isPushToTalk = false)
                    }
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** @return true if microphone permission is currently granted */
    fun hasMicrophonePermission(): Boolean = wavRecorder.hasMicrophonePermission()

    /** Start recording. Returns false if permission not granted or if already recording/uploading. */
    fun startRecording(isPushToTalk: Boolean = true): Boolean {
        if (!wavRecorder.hasMicrophonePermission()) return false

        // Reentrancy / debouncing check
        if (_recordingState.value is RecordingState.Recording || _recordingState.value is RecordingState.Uploading) {
            return false
        }

        isCurrentSessionPushToTalk = isPushToTalk
        _recordingState.value = RecordingState.Recording
        _recordingDuration.value = 0f

        if (VoiceCommandManager.state.value != AssistantState.LISTENING_COMMAND) {
            VoiceCommandManager.updateState(AssistantState.LISTENING_COMMAND)
        }

        recordingJob = viewModelScope.launch {
            val file = wavRecorder.startRecording()  // suspends until stopRecording() called

            // Capture diagnostic immediately after recording ends
            _audioDiagnostic.value = wavRecorder.lastDiagnostic

            if (wavRecorder.lastStopReason == WavRecorder.StopReason.INITIAL_TIMEOUT || !wavRecorder.hasSpeechDetected) {
                val msg = "Tidak ada suara terdeteksi."
                _recordingState.value = RecordingState.Error(msg)
                speakAndReturnToStandby(msg)
            } else if (file != null && file.length() > 44) {
                _recordingDuration.value = wavRecorder.lastRecordingDurationSeconds
                _lastWavFile.value = file
                uploadFile(file)
            } else {
                val msg = "Rekaman kosong atau gagal dibuat."
                _recordingState.value = RecordingState.Error(msg)
                speakAndReturnToStandby(msg)
            }
        }
        return true
    }

    /** Stop recording and trigger upload. */
    fun stopRecording() {
        if (_recordingState.value is RecordingState.Recording) {
            wavRecorder.stopRecording()
            // Upload triggered automatically after startRecording() resumes
        }
    }

    /** Cancel current operation and reset to Idle. */
    fun reset() {
        wavRecorder.stopRecording()
        VoiceCommandManager.stopSpeaking()
        recordingJob?.cancel()
        _recordingState.value = RecordingState.Idle
        viewModelScope.launch {
            VoiceCommandManager.transitionToStandby(200)
        }
    }

    /** Test connection to Raspberry Pi. Returns user-friendly message. */
    fun testConnection(callback: (String) -> Unit) {
        viewModelScope.launch {
            val baseUrl = raspberryPiUrl.first()
            val result = voiceRepository.testConnection(baseUrl)
            val message = when (result) {
                is ElfanApi.ConnectionTestResult.Success ->
                    "✓ Terhubung (HTTP ${result.httpCode})"
                is ElfanApi.ConnectionTestResult.Failure ->
                    "❌ Gagal: ${result.error}"
            }
            callback(message)
        }
    }

    /** Update stored Raspberry Pi URL. */
    fun updateRaspberryPiUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.saveRaspberryPiUrl(url)
        }
    }

    /** Toggle debug mode. */
    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveDebugMode(enabled)
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveWakeWordEnabled(enabled)
        }
    }

    fun updatePorcupineAccessKey(key: String) {
        viewModelScope.launch {
            settingsRepository.savePorcupineAccessKey(key)
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun uploadFile(file: File) {
        _recordingState.value = RecordingState.Uploading
        VoiceCommandManager.updateState(
            AssistantState.PROCESSING_COMMAND,
            "Mengirim ke ELFAN..."
        )
        val baseUrl = settingsRepository.raspberryPiUrl.first()

        try {
            val mode = if (isCurrentSessionPushToTalk) "push_to_talk" else null
            val uploadResult = voiceRepository.sendVoiceCommand(baseUrl, file, mode)

            uploadResult.fold(
                onSuccess = { elfanResult ->
                    _recordingState.value = RecordingState.Success(elfanResult)
                    val spoken = buildSpokenFeedback(elfanResult)
                    speakAndReturnToStandby(spoken)
                },
                onFailure = { error ->
                    val (uiMsg, spokenMsg) = mapErrorToFeedback(error)
                    _recordingState.value = RecordingState.Error(uiMsg)
                    speakAndReturnToStandby(spokenMsg)
                }
            )
        } catch (e: Exception) {
            val (uiMsg, spokenMsg) = mapErrorToFeedback(e)
            _recordingState.value = RecordingState.Error(uiMsg)
            speakAndReturnToStandby(spokenMsg)
        }
    }

    private fun speakAndReturnToStandby(text: String) {
        VoiceCommandManager.updateState(AssistantState.SPEAKING_RESPONSE, text)
        VoiceCommandManager.speak(
            text = text,
            onDone = {
                viewModelScope.launch {
                    VoiceCommandManager.transitionToStandby(300)
                }
            },
            onError = {
                viewModelScope.launch {
                    VoiceCommandManager.transitionToStandby(300)
                }
            }
        )
    }

    private fun buildSpokenFeedback(result: ElfanResult): String {
        if (result.success) {
            val device = result.device.lowercase()
            val action = when (result.action.uppercase()) {
                "ON", "TURN_ON" -> "dinyalakan"
                "OFF", "TURN_OFF" -> "dimatikan"
                else -> result.action.lowercase()
            }
            return if (device != "-" && action != "-") {
                "Baik, $device telah $action."
            } else {
                "Baik, perintah berhasil dijalankan."
            }
        } else {
            val err = result.rawResponse.error ?: result.rawResponse.message
            return when {
                !err.isNullOrBlank() -> {
                    if (err.contains("wake word", ignoreCase = true)) {
                        "Maaf, wake word tidak terdeteksi oleh server."
                    } else {
                        "Maaf, $err."
                    }
                }
                result.rawResponse.command?.success == false -> "Maaf, perintah tidak dikenali."
                else -> "Maaf, perintah belum berhasil dijalankan."
            }
        }
    }

    private fun mapErrorToFeedback(error: Throwable): Pair<String, String> {
        return when (error) {
            is ConnectException ->
                Pair(
                    "❌ Raspberry Pi tidak dapat dihubungi.\nPastikan server menyala dan IP benar.",
                    "Maaf, Raspberry Pi tidak dapat dihubungi."
                )
            is SocketTimeoutException ->
                Pair(
                    "⏱ Server tidak merespons (timeout).\nWisper mungkin sedang berjalan lambat.",
                    "Maaf, server tidak merespons. Waktu tunggu habis."
                )
            is IOException ->
                Pair(
                    "❌ Network error: ${error.message}",
                    "Maaf, terjadi gangguan jaringan."
                )
            else ->
                Pair(
                    "❌ Error: ${error.message}",
                    "Maaf, terjadi kesalahan."
                )
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val settingsRepo = SettingsRepository(context.applicationContext)
            return VoiceViewModel(context.applicationContext, settingsRepo) as T
        }
    }
}
