package com.example.elfanmobile.audio

/**
 * Penyebab berhentinya sebuah sesi rekaman.
 *
 * Dipisahkan dari [WavRecorder] agar [AudioRecorder] dapat merujuknya tanpa
 * menyeret implementasi `AudioRecord`. `WavRecorder.StopReason` tetap tersedia
 * sebagai typealias agar referensi lama tidak berubah.
 */
enum class StopReason {
    MANUAL_STOP,
    END_OF_SPEECH,
    INITIAL_TIMEOUT,
    MAX_DURATION,
    ERROR
}

/**
 * Statistik diagnostik dari satu sesi rekaman.
 *
 * Dipisahkan dari [WavRecorder] untuk alasan yang sama dengan [StopReason].
 */
data class AudioDiagnostic(
    val totalBytesRead: Long,
    val totalSamples: Long,
    val nonZeroSamples: Long,
    val peak: Int,        // max absolute amplitude (0–32767)
    val minSample: Int,   // most negative signed sample
    val maxSample: Int,   // most positive signed sample
    val allZero: Boolean, // true = entire recording is silence
    val readCount: Int,   // how many times AudioRecord.read() was called
    val durationSeconds: Float
) {
    /** Human-readable summary for display in UI */
    fun toDisplayString(): String = buildString {
        appendLine("═══ AUDIO DIAGNOSTIC ═══")
        appendLine("totalBytes   : $totalBytesRead B")
        appendLine("readCount    : $readCount")
        appendLine("totalSamples : $totalSamples")
        appendLine("nonZero      : $nonZeroSamples")
        appendLine("peak         : $peak")
        appendLine("min          : $minSample")
        appendLine("max          : $maxSample")
        appendLine("allZero      : $allZero")
        append(    "duration     : ${"%.2f".format(durationSeconds)} s")
    }
}
