package com.example.elfanmobile.audio

import java.io.File

/**
 * AudioRecorder
 *
 * Kontrak minimal untuk perekam audio PCM → WAV yang dipakai oleh
 * [com.example.elfanmobile.viewmodel.VoiceViewModel].
 *
 * Diekstraksi agar perekaman dapat diuji di JVM tanpa perangkat/`AudioRecord`
 * (lihat `VoiceViewModelWakeWordTest`). Implementasi produksi adalah [WavRecorder].
 */
interface AudioRecorder {

    /** Statistik diagnostik dari sesi rekaman terakhir. Null bila belum ada rekaman selesai. */
    val lastDiagnostic: AudioDiagnostic?

    /** Durasi rekaman terakhir dalam detik. */
    val lastRecordingDurationSeconds: Float

    /** Menandakan apakah suara pengguna terdeteksi selama sesi rekaman terakhir. */
    val hasSpeechDetected: Boolean

    /** Alasan mengapa rekaman terakhir berhenti. */
    val lastStopReason: StopReason

    /** File WAV keluaran dari rekaman terakhir. */
    val wavFile: File?

    /** @return true bila izin mikrofon sudah diberikan. */
    fun hasMicrophonePermission(): Boolean

    /**
     * Mulai merekam. Suspend hingga [stopRecording] dipanggil atau VAD
     * menghentikan rekaman.
     *
     * @return File WAV bila berhasil, null bila izin ditolak atau gagal.
     */
    suspend fun startRecording(): File?

    /** Hentikan rekaman yang sedang berjalan. */
    fun stopRecording()
}
