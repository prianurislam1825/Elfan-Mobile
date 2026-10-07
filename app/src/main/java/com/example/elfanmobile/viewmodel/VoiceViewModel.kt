package com.example.elfanmobile.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

    // ── Settings ──────────────────────────────────────────────────────────────

    val raspberryPiUrl = settingsRepository.raspberryPiUrl
    val debugMode = settingsRepository.debugMode

    // ── Internals ─────────────────────────────────────────────────────────────

    private val wavRecorder = WavRecorder(appContext)
    private var recordingJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /** @return true if microphone permission is currently granted */
    fun hasMicrophonePermission(): Boolean = wavRecorder.hasMicrophonePermission()

    /** Start recording. Returns false if permission not granted. */
    fun startRecording(): Boolean {
        if (!wavRecorder.hasMicrophonePermission()) return false

        recordingJob = viewModelScope.launch {
            _recordingState.value = RecordingState.Recording
            _recordingDuration.value = 0f

            val file = wavRecorder.startRecording()  // suspends until stopRecording() called

            if (file != null && file.length() > 44) {
                _recordingDuration.value = wavRecorder.lastRecordingDurationSeconds
                _lastWavFile.value = file
                uploadFile(file)
            } else {
                _recordingState.value = RecordingState.Error("Rekaman kosong atau gagal dibuat")
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
        recordingJob?.cancel()
        _recordingState.value = RecordingState.Idle
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

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun uploadFile(file: File) {
        _recordingState.value = RecordingState.Uploading
        val baseUrl = settingsRepository.raspberryPiUrl.first()

        val uploadResult = voiceRepository.sendVoiceCommand(baseUrl, file)

        uploadResult.fold(
            onSuccess = { elfanResult ->
                _recordingState.value = RecordingState.Success(elfanResult)
            },
            onFailure = { error ->
                val message = when (error) {
                    is ConnectException ->
                        "❌ Raspberry Pi tidak dapat dihubungi.\nPastikan server menyala dan IP benar."
                    is SocketTimeoutException ->
                        "⏱ Server tidak merespons (timeout).\nWisper mungkin sedang berjalan lambat."
                    is IOException ->
                        "❌ Network error: ${error.message}"
                    else ->
                        "❌ Error: ${error.message}"
                }
                _recordingState.value = RecordingState.Error(message)
            }
        )
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
