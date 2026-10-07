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
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WavRecorder
 *
 * Native AudioRecord-based recorder that captures PCM 16-bit, 16 kHz, Mono audio
 * and writes it as a valid WAV file. This is required so Whisper.cpp on the
 * Raspberry Pi can process the audio correctly.
 *
 * Audio format:
 *   Sample rate : 16000 Hz
 *   Channels    : MONO
 *   Encoding    : PCM_16BIT
 */
class WavRecorder(private val context: Context) {

    companion object {
        private const val TAG = "WavRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val OUTPUT_FILE_NAME = "elfan_record.wav"

        // WAV format constants
        private const val BITS_PER_SAMPLE: Short = 16
        private const val NUM_CHANNELS: Short = 1
        private const val BYTE_RATE = SAMPLE_RATE * NUM_CHANNELS * (BITS_PER_SAMPLE / 8)
        private const val BLOCK_ALIGN: Short = (NUM_CHANNELS * (BITS_PER_SAMPLE / 8)).toShort()
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var outputFile: File? = null
    private var recordingStartTime: Long = 0L

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
        if (!hasMicrophonePermission()) {
            Log.e(TAG, "Microphone permission not granted")
            return@withContext null
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $minBufferSize")
            return@withContext null
        }

        // Use at least 2x min buffer for stability
        val bufferSize = maxOf(minBufferSize * 2, 4096)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return@withContext null
        }

        // Prepare output file in app's cache directory
        val file = File(context.cacheDir, OUTPUT_FILE_NAME)
        outputFile = file

        audioRecord = record
        isRecording = true
        recordingStartTime = System.currentTimeMillis()

        try {
            record.startRecording()
            Log.d(TAG, "Recording started → $file")

            // Write raw PCM data to file first; WAV header added after stopping
            FileOutputStream(file).use { fos ->
                val buffer = ByteArray(bufferSize)

                // Write placeholder WAV header (44 bytes) — will be fixed after stop
                writeWavHeader(fos, 0)

                while (isActive && isRecording) {
                    val bytesRead = record.read(buffer, 0, bufferSize)
                    if (bytesRead > 0) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}", e)
        } finally {
            record.stop()
            record.release()
            audioRecord = null
        }

        // Fix WAV header with actual data size
        val pcmDataSize = (file.length() - 44).toInt()
        if (pcmDataSize > 0) {
            fixWavHeader(file, pcmDataSize)
            lastRecordingDurationSeconds = pcmDataSize.toFloat() / BYTE_RATE
            Log.d(TAG, "Recording finished. PCM size=$pcmDataSize bytes, duration=${lastRecordingDurationSeconds}s")
        } else {
            lastRecordingDurationSeconds = 0f
        }

        return@withContext file
    }

    /** Stop an ongoing recording. */
    fun stopRecording() {
        isRecording = false
        Log.d(TAG, "Stop recording requested")
    }

    /**
     * Write a WAV header to the output stream.
     * If pcmDataSize is 0 it writes a placeholder header.
     */
    private fun writeWavHeader(fos: FileOutputStream, pcmDataSize: Int) {
        val totalDataLen = pcmDataSize + 36  // 36 = header size - 8
        val byteRate = BYTE_RATE

        DataOutputStream(fos).apply {
            // RIFF chunk descriptor
            write("RIFF".toByteArray())
            writeIntLE(totalDataLen)
            write("WAVE".toByteArray())

            // fmt sub-chunk
            write("fmt ".toByteArray())
            writeIntLE(16)           // Sub-chunk1 size for PCM
            writeShortLE(1)          // Audio format: PCM = 1
            writeShortLE(NUM_CHANNELS.toInt())
            writeIntLE(SAMPLE_RATE)
            writeIntLE(byteRate)
            writeShortLE(BLOCK_ALIGN.toInt())
            writeShortLE(BITS_PER_SAMPLE.toInt())

            // data sub-chunk
            write("data".toByteArray())
            writeIntLE(pcmDataSize)
        }
    }

    /**
     * Re-write the WAV header in-place with the correct pcmDataSize.
     */
    private fun fixWavHeader(file: File, pcmDataSize: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            val totalDataLen = pcmDataSize + 36

            // Fix "RIFF" chunk size at byte offset 4
            raf.seek(4)
            raf.write(intToLittleEndianBytes(totalDataLen))

            // Fix "data" chunk size at byte offset 40
            raf.seek(40)
            raf.write(intToLittleEndianBytes(pcmDataSize))
        }
    }

    // ─── Little-endian helpers ────────────────────────────────────────────────

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(intToLittleEndianBytes(value))
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
        write(buf)
    }

    private fun intToLittleEndianBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}
