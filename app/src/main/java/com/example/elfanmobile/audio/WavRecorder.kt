package com.example.elfanmobile.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WavRecorder
 *
 * Native AudioRecord-based recorder that captures PCM 16-bit, 16 kHz, Mono audio
 * and writes it as a valid WAV file.
 *
 * Audio format:
 *   Sample rate : 16000 Hz
 *   Channels    : MONO
 *   Encoding    : PCM_16BIT
 *
 * IMPORTANT DESIGN NOTES:
 * - WAV header is written as raw bytes directly to FileOutputStream (NOT via
 *   DataOutputStream wrapper) to prevent buffer flush ordering issues that
 *   caused silent (-91 dB) recordings.
 * - AudioRecord state is fully logged for diagnostics.
 */
class WavRecorder(private val context: Context) {

    companion object {
        private const val TAG = "WavRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val OUTPUT_FILE_NAME = "elfan_record.wav"

        // WAV format constants
        private const val BITS_PER_SAMPLE = 16
        private const val NUM_CHANNELS = 1
        private const val BYTE_RATE = SAMPLE_RATE * NUM_CHANNELS * (BITS_PER_SAMPLE / 8)
        private const val BLOCK_ALIGN = NUM_CHANNELS * (BITS_PER_SAMPLE / 8)

        // WAV header is exactly 44 bytes
        private const val WAV_HEADER_SIZE = 44
    }

    /**
     * Diagnostic stats collected from the last recording session.
     * Exposed so the UI can display them without ADB/Logcat.
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

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var outputFile: File? = null

    /** Diagnostic from the most recent recording. Null if no recording has finished. */
    var lastDiagnostic: AudioDiagnostic? = null
        private set

    /** Duration of the last recording in seconds. */
    var lastRecordingDurationSeconds: Float = 0f
        private set

    // ── VAD Configuration (End-of-Speech & Speech Detection) ────────────────
    enum class StopReason {
        MANUAL_STOP,
        END_OF_SPEECH,
        INITIAL_TIMEOUT,
        MAX_DURATION,
        ERROR
    }

    var vadEnabled: Boolean = true
    var speechRmsThreshold: Int = 450 // RMS di atas ini dianggap sebagai suara/bicara normal
    var silenceRmsThreshold: Int = 280  // RMS di bawah ini dianggap hening setelah suara terdeteksi
    var initialSpeechTimeoutMs: Long = 5000L // Batas waktu menunggu pengguna mulai berbicara (5 detik)
    var postSpeechSilenceDurationMs: Long = 1800L // Jeda hening setelah berbicara sebelum selesai (1.8 detik)
    var minSpeechDurationMs: Long = 160L // Minimal akumulasi durasi suara aktif (160ms) agar dianggap perintah valid
    var maxTotalRecordingDurationMs: Long = 20000L // Batas pengaman rekaman maksimal (20 detik)

    /** Menandakan apakah ada suara pengguna yang terdeteksi selama sesi rekaman ini */
    var hasSpeechDetected: Boolean = false
        private set

    /** Alasan mengapa rekaman terakhir berhenti */
    var lastStopReason: StopReason = StopReason.MANUAL_STOP
        private set

    /** The output WAV file from the last recording. */
    val wavFile: File?
        get() = outputFile

    /** Check if microphone permission is granted. */
    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording audio to a WAV file.
     * Must be called from a coroutine. Suspends until [stopRecording] is called.
     *
     * @return the output File if successful, null if permission denied or error
     */
    suspend fun startRecording(): File? = withContext(Dispatchers.IO) {
        // ── 1. Permission check ──────────────────────────────────────────────
        if (!hasMicrophonePermission()) {
            Log.e(TAG, "❌ RECORD_AUDIO permission not granted")
            return@withContext null
        }
        Log.d(TAG, "✅ RECORD_AUDIO permission: GRANTED")

        // ── 2. Compute buffer size ───────────────────────────────────────────
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        Log.d(TAG, "minBufferSize=$minBufferSize")

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "❌ getMinBufferSize failed: $minBufferSize")
            return@withContext null
        }

        val bufferSize = maxOf(minBufferSize * 2, 4096)
        Log.d(TAG, "bufferSize=$bufferSize")

        // ── 3. Create AudioRecord dengan mekanisme retry (hand-off resilience) ──
        val audioSource = MediaRecorder.AudioSource.MIC
        Log.d(TAG, "AudioSource=$audioSource (MIC=${MediaRecorder.AudioSource.MIC})")

        var record: AudioRecord? = null
        var initAttempts = 0
        while (initAttempts < 3) {
            initAttempts++
            try {
                val candidate = AudioRecord(
                    audioSource,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    record = candidate
                    break
                } else {
                    Log.w(TAG, "AudioRecord attempt #$initAttempts gagal (state=${candidate.state}), retry setelah jeda...")
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord attempt #$initAttempts exception: ${e.message}")
            }
            kotlinx.coroutines.delay(100)
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ AudioRecord gagal diinisialisasi setelah 3 percobaan")
            record?.release()
            return@withContext null
        }
        Log.d(TAG, "✅ AudioRecord STATE_INITIALIZED (berhasil pada percobaan #$initAttempts)")

        // ── 4. Prepare output file ───────────────────────────────────────────
        val file = File(context.cacheDir, OUTPUT_FILE_NAME)
        outputFile = file
        Log.d(TAG, "Output file: $file")

        audioRecord = record
        isRecording = true

        try {
            // ── 5. Start recording ───────────────────────────────────────────
            record.startRecording()

            Log.d(TAG, "AudioRecord.recordingState=${record.recordingState} " +
                    "(RECORDSTATE_RECORDING=${AudioRecord.RECORDSTATE_RECORDING})")

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "❌ startRecording() did not transition to RECORDSTATE_RECORDING")
            } else {
                Log.d(TAG, "✅ startRecording() OK — recording is active")
            }

            // ── 6. Open file and write WAV header as raw bytes ───────────────
            // IMPORTANT: We write the header directly as a ByteArray to avoid
            // DataOutputStream buffering issues that caused silent recordings.
            FileOutputStream(file).use { fos ->
                // Write placeholder header (44 bytes of zeros) — fixed after stop
                fos.write(buildWavHeader(0))
                fos.flush() // Ensure header bytes reach the file before PCM data

                Log.d(TAG, "WAV placeholder header written (44 bytes)")

                val buffer = ByteArray(bufferSize)
                var totalBytesWritten = 0L
                var readCount = 0

                // ── Accumulated stats across entire recording ─────────────────
                var accumPeak = 0
                var accumMin = Int.MAX_VALUE
                var accumMax = Int.MIN_VALUE
                var accumTotalSamples = 0L
                var accumNonZero = 0L

                // ── VAD State ─────────────────────────────────────────────────
                hasSpeechDetected = false
                lastStopReason = StopReason.MANUAL_STOP

                var speechSamplesCount = 0L
                var consecutiveSilenceSamples = 0L
                val initialTimeoutSamples = (SAMPLE_RATE.toLong() * initialSpeechTimeoutMs) / 1000L
                val postSpeechSilenceThreshold = (SAMPLE_RATE.toLong() * postSpeechSilenceDurationMs) / 1000L
                val minSpeechSamples = (SAMPLE_RATE.toLong() * minSpeechDurationMs) / 1000L
                val maxTotalSamples = (SAMPLE_RATE.toLong() * maxTotalRecordingDurationMs) / 1000L

                // ── 7. Recording loop ────────────────────────────────────────
                while (isActive && isRecording) {
                    val bytesRead = record.read(buffer, 0, bufferSize)

                    when {
                        bytesRead > 0 -> {
                            fos.write(buffer, 0, bytesRead)
                            totalBytesWritten += bytesRead
                            readCount++

                            var chunkSumSquares = 0.0
                            var chunkSampleCount = 0

                            // Scan every PCM sample in this chunk
                            var i = 0
                            while (i + 1 < bytesRead) {
                                val raw = (buffer[i + 1].toInt() shl 8) or
                                        (buffer[i].toInt() and 0xFF)
                                val signed = if (raw >= 0x8000) raw - 0x10000 else raw
                                val abs = kotlin.math.abs(signed)

                                if (abs > accumPeak)    accumPeak = abs
                                if (signed < accumMin)  accumMin = signed
                                if (signed > accumMax)  accumMax = signed
                                if (signed != 0)        accumNonZero++

                                chunkSumSquares += signed.toDouble() * signed.toDouble()
                                chunkSampleCount++
                                accumTotalSamples++
                                i += 2
                            }

                            // Calculate RMS for this chunk
                            val chunkRms = if (chunkSampleCount > 0) {
                                kotlin.math.sqrt(chunkSumSquares / chunkSampleCount).toInt()
                            } else {
                                0
                            }

                            // ── 8. VAD Logic (3-Phase Speech & End-of-Speech Detection)
                            if (vadEnabled) {
                                if (!hasSpeechDetected) {
                                    // Fase 1: Menunggu pengguna mulai berbicara
                                    if (chunkRms >= speechRmsThreshold) {
                                        speechSamplesCount += chunkSampleCount
                                        if (speechSamplesCount >= minSpeechSamples) {
                                            hasSpeechDetected = true
                                            consecutiveSilenceSamples = 0
                                            Log.d(TAG, "🎙️ VAD: Suara pengguna terdeteksi! (RMS=$chunkRms >= $speechRmsThreshold)")
                                        }
                                    } else {
                                        // Jangan langsung reset ke 0 pada jeda konsonan/unvoiced, tetapi decay perlahan
                                        speechSamplesCount = maxOf(0L, speechSamplesCount - chunkSampleCount / 2)
                                    }

                                    // Cek timeout awal jika belum ada suara
                                    if (!hasSpeechDetected && accumTotalSamples >= initialTimeoutSamples) {
                                        Log.w(TAG, "⏱️ VAD: Timeout awal tercapai (${initialSpeechTimeoutMs}ms). Tidak ada suara terdeteksi.")
                                        lastStopReason = StopReason.INITIAL_TIMEOUT
                                        isRecording = false
                                    }
                                } else {
                                    // Fase 2 & 3: Suara sudah terdeteksi, deteksi jeda hening setelah berbicara
                                    if (chunkRms < silenceRmsThreshold) {
                                        consecutiveSilenceSamples += chunkSampleCount
                                    } else {
                                        consecutiveSilenceSamples = 0 // Reset hening jika berbicara kembali
                                    }

                                    if (consecutiveSilenceSamples >= postSpeechSilenceThreshold) {
                                        Log.d(TAG, "🛑 VAD: Akhir ucapan terdeteksi. Hening ${postSpeechSilenceDurationMs}ms setelah berbicara.")
                                        lastStopReason = StopReason.END_OF_SPEECH
                                        isRecording = false
                                    }
                                }

                                // Batas maksimal rekaman
                                if (accumTotalSamples >= maxTotalSamples) {
                                    Log.w(TAG, "⚠️ VAD: Durasi maksimal tercapai (${maxTotalRecordingDurationMs}ms).")
                                    lastStopReason = StopReason.MAX_DURATION
                                    isRecording = false
                                }
                            }

                            // Periodic log (every 10 reads) for anyone with Logcat
                            if (readCount % 10 == 0) {
                                Log.d(TAG,
                                    "🎙️ AUDIO DEBUG #$readCount | " +
                                    "bytes=$bytesRead | chunkRms=$chunkRms | " +
                                    "runningPeak=$accumPeak | " +
                                    "totalWritten=${totalBytesWritten}B"
                                )
                            }
                        }
                        bytesRead == AudioRecord.ERROR_INVALID_OPERATION ->
                            Log.e(TAG, "❌ read() ERROR_INVALID_OPERATION")
                        bytesRead == AudioRecord.ERROR_BAD_VALUE ->
                            Log.e(TAG, "❌ read() ERROR_BAD_VALUE")
                        bytesRead == AudioRecord.ERROR ->
                            Log.e(TAG, "❌ read() ERROR")
                        bytesRead == 0 ->
                            Log.w(TAG, "⚠️ read() returned 0 bytes")
                        else ->
                            Log.e(TAG, "❌ read() unexpected: $bytesRead")
                    }
                }

                fos.flush()

                // ── Store final accumulated diagnostic ───────────────────────
                val durSec = totalBytesWritten.toFloat() / BYTE_RATE
                val allZero = accumNonZero == 0L

                // Verifikasi integritas sinyal suara aktual pasca rekaman:
                // Jika audio memiliki peak amplitude dan durasi memadai, konfirmasi hasSpeechDetected
                if (!hasSpeechDetected) {
                    if (accumPeak >= 400 && accumNonZero > 1000 && durSec >= 0.4f) {
                        hasSpeechDetected = true
                        Log.d(TAG, "✅ Audio integrity: Suara valid terkonfirmasi post-recording (peak=$accumPeak, nonZero=$accumNonZero, dur=${durSec}s)")
                    } else if (lastStopReason == StopReason.MANUAL_STOP && accumPeak >= 300 && durSec >= 0.3f) {
                        hasSpeechDetected = true
                        Log.d(TAG, "✅ Manual stop: Pengguna menghentikan rekaman valid (peak=$accumPeak, dur=${durSec}s)")
                    }
                }

                lastDiagnostic = AudioDiagnostic(
                    totalBytesRead   = totalBytesWritten,
                    totalSamples     = accumTotalSamples,
                    nonZeroSamples   = accumNonZero,
                    peak             = accumPeak,
                    minSample        = if (accumMin == Int.MAX_VALUE) 0 else accumMin,
                    maxSample        = if (accumMax == Int.MIN_VALUE) 0 else accumMax,
                    allZero          = allZero,
                    readCount        = readCount,
                    durationSeconds  = durSec
                )

                Log.d(TAG, "Recording ended.\n${lastDiagnostic!!.toDisplayString()}")
            }


        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}", e)
        } finally {
            record.stop()
            Log.d(TAG, "AudioRecord stopped. recordingState=${record.recordingState}")
            record.release()
            audioRecord = null
        }

        // ── 9. Fix WAV header with real data size ────────────────────────────
        val fileSize = file.length()
        val pcmDataSize = (fileSize - WAV_HEADER_SIZE).toInt()

        Log.d(TAG, "File size on disk: $fileSize bytes | pcmDataSize: $pcmDataSize bytes")

        if (pcmDataSize > 0) {
            fixWavHeader(file, pcmDataSize)
            lastRecordingDurationSeconds = pcmDataSize.toFloat() / BYTE_RATE
            Log.d(TAG, "✅ WAV header fixed. duration=${lastRecordingDurationSeconds}s")
        } else {
            lastRecordingDurationSeconds = 0f
            Log.e(TAG, "❌ pcmDataSize=$pcmDataSize — file has no PCM data!")
        }

        return@withContext file
    }

    /** Stop an ongoing recording. */
    fun stopRecording() {
        lastStopReason = StopReason.MANUAL_STOP
        isRecording = false
        Log.d(TAG, "stopRecording() called — isRecording set to false")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WAV header helpers
    // Written directly as ByteArray to FileOutputStream — no intermediate
    // DataOutputStream wrapper to avoid buffer-ordering bugs.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a 44-byte WAV header as a raw ByteArray.
     * If [pcmDataSize] is 0, writes a placeholder that will be fixed later.
     */
    private fun buildWavHeader(pcmDataSize: Int): ByteArray {
        val totalDataLen = pcmDataSize + 36  // 36 = WAV_HEADER_SIZE - 8

        return ByteBuffer.allocate(WAV_HEADER_SIZE).apply {
            order(ByteOrder.LITTLE_ENDIAN)

            // RIFF chunk descriptor
            put("RIFF".toByteArray())       // ChunkID
            putInt(totalDataLen)            // ChunkSize
            put("WAVE".toByteArray())       // Format

            // fmt sub-chunk
            put("fmt ".toByteArray())       // Subchunk1ID
            putInt(16)                      // Subchunk1Size (PCM = 16)
            putShort(1)                     // AudioFormat (PCM = 1)
            putShort(NUM_CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(BYTE_RATE)
            putShort(BLOCK_ALIGN.toShort())
            putShort(BITS_PER_SAMPLE.toShort())

            // data sub-chunk
            put("data".toByteArray())       // Subchunk2ID
            putInt(pcmDataSize)             // Subchunk2Size
        }.array()
    }

    /**
     * Overwrite only the size fields in the WAV header in-place.
     * This avoids re-reading/re-writing the entire file.
     */
    private fun fixWavHeader(file: File, pcmDataSize: Int) {
        val totalDataLen = pcmDataSize + 36
        RandomAccessFile(file, "rw").use { raf ->
            // ChunkSize at byte offset 4
            raf.seek(4)
            raf.write(intToLittleEndianBytes(totalDataLen))

            // Subchunk2Size at byte offset 40
            raf.seek(40)
            raf.write(intToLittleEndianBytes(pcmDataSize))
        }
        Log.d(TAG, "fixWavHeader: totalDataLen=$totalDataLen, pcmDataSize=$pcmDataSize")
    }

    private fun intToLittleEndianBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}
