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

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var outputFile: File? = null

    /** Duration of the last recording in seconds. */
    var lastRecordingDurationSeconds: Float = 0f
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

        // ── 3. Create AudioRecord ────────────────────────────────────────────
        // Try MIC first, fallback to VOICE_RECOGNITION if blocked
        val audioSource = MediaRecorder.AudioSource.MIC
        Log.d(TAG, "AudioSource=$audioSource (MIC=${MediaRecorder.AudioSource.MIC})")

        val record = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        Log.d(TAG, "AudioRecord.state=${record.state} " +
                "(STATE_INITIALIZED=${AudioRecord.STATE_INITIALIZED}, " +
                "STATE_UNINITIALIZED=${AudioRecord.STATE_UNINITIALIZED})")

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ AudioRecord failed to initialize — state=${record.state}")
            record.release()
            return@withContext null
        }
        Log.d(TAG, "✅ AudioRecord STATE_INITIALIZED")

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
                var zeroChunkCount = 0

                // ── 7. Recording loop ────────────────────────────────────────
                while (isActive && isRecording) {
                    val bytesRead = record.read(buffer, 0, bufferSize)

                    when {
                        bytesRead > 0 -> {
                            fos.write(buffer, 0, bytesRead)
                            totalBytesWritten += bytesRead
                            readCount++

                            // ── 8. Diagnostic every 10 reads ─────────────────
                            if (readCount % 10 == 0) {
                                var peak = 0
                                var minSample = Int.MAX_VALUE
                                var maxSample = Int.MIN_VALUE
                                var allZero = true
                                var nonZeroCount = 0

                                var i = 0
                                while (i + 1 < bytesRead) {
                                    // Little-endian PCM16 → signed int
                                    val sample = (buffer[i + 1].toInt() shl 8) or
                                            (buffer[i].toInt() and 0xFF)
                                    // Convert to signed
                                    val signedSample = if (sample >= 0x8000) sample - 0x10000 else sample

                                    val abs = kotlin.math.abs(signedSample)
                                    if (abs > peak) peak = abs
                                    if (signedSample < minSample) minSample = signedSample
                                    if (signedSample > maxSample) maxSample = signedSample
                                    if (signedSample != 0) {
                                        allZero = false
                                        nonZeroCount++
                                    }
                                    i += 2
                                }

                                if (allZero) zeroChunkCount++

                                Log.d(TAG,
                                    "🎙️ AUDIO DEBUG #$readCount | " +
                                    "bytes=$bytesRead | " +
                                    "peak=$peak | " +
                                    "min=$minSample | " +
                                    "max=$maxSample | " +
                                    "allZero=$allZero | " +
                                    "nonZero=$nonZeroCount | " +
                                    "zeroChunks=$zeroChunkCount | " +
                                    "totalWritten=${totalBytesWritten}B"
                                )

                                if (allZero) {
                                    Log.w(TAG, "⚠️ WARNING: chunk #$readCount is ALL ZEROS — " +
                                            "microphone may be blocked or silent!")
                                }
                            }
                        }
                        bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                            Log.e(TAG, "❌ record.read() ERROR_INVALID_OPERATION — " +
                                    "AudioRecord not recording yet?")
                        }
                        bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                            Log.e(TAG, "❌ record.read() ERROR_BAD_VALUE")
                        }
                        bytesRead == AudioRecord.ERROR -> {
                            Log.e(TAG, "❌ record.read() ERROR")
                        }
                        bytesRead == 0 -> {
                            Log.w(TAG, "⚠️ record.read() returned 0 bytes")
                        }
                        else -> {
                            Log.e(TAG, "❌ record.read() unexpected return: $bytesRead")
                        }
                    }
                }

                fos.flush()
                Log.d(TAG, "Recording loop ended. totalBytesWritten=$totalBytesWritten")
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
