package com.example.elfanmobile.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OnnxWakeWordDetector
 *
 * Implementasi deteksi wake word "Oke Elfan" menggunakan pipeline 3 model ONNX
 * yang sesuai 100% dengan pipeline training openWakeWord:
 *
 * Pipeline:
 *   PCM int16 (1280 sampel / 80ms)
 *     → melspectrogram.onnx  → [1,1,5,32] → transform(/10+2) → buffer
 *     → embedding_model.onnx → [1,76,32,1] → [96-dim embedding] → ring buffer
 *     → oke_elfan.onnx       → [1,16,96]   → sigmoid score [0..1]
 *
 * Spesifikasi Pipeline (dari inspeksi openWakeWord utils.py):
 *   - melspec: input [1, 1280] float32 (dari int16), output [1,1,5,32]
 *   - melspec transform: x/10 + 2 (normalisasi ke TF native format)
 *   - embedding: input [batch,76,32,1] float32, output [batch,1,1,96]
 *   - classifier: input [1,16,96] float32, output [1,1] sigmoid
 *   - Mel window: 76 frame, step 8 frame
 *   - Embedding ring buffer: 16 embeddings
 *
 * ⚠️ CATATAN KALIBRASI:
 *   Model ini dilatih dengan dataset sintetis kecil (Piper TTS).
 *   Skor baseline pada silence/noise sekitar 0.999, yang menunjukkan model
 *   perlu threshold tinggi atau retraining dengan lebih banyak data negatif nyata.
 *   Gunakan [WAKE_WORD_SCORE_THRESHOLD] sebagai parameter yang dapat dikonfigurasi
 *   dan kalibrasi berdasarkan pengujian audio HP nyata.
 */
class OnnxWakeWordDetector(private val context: Context) {

    companion object {
        private const val TAG = "OnnxWakeWordDetector"

        // Nama file model di assets/ (menggunakan model oke_elfan.onnx terbaru)
        const val ASSET_MELSPEC    = "melspectrogram.onnx"
        const val ASSET_EMBEDDING  = "embedding_model.onnx"
        const val ASSET_CLASSIFIER = "oke_elfan.onnx"

        // Parameter pipeline (sesuai openWakeWord AudioFeatures source)
        const val CHUNK_SAMPLES     = 1280    // 80ms @ 16kHz
        const val SAMPLE_RATE       = 16000
        const val MEL_FRAMES_PER_CHUNK  = 5   // setiap 1280 sampel → 5 mel frame
        const val MEL_WINDOW_SIZE       = 76  // mel frames per embedding
        const val MEL_STEP_SIZE         = 8   // mel frames per embedding slide step
        const val MEL_FREQ_BINS         = 32  // frekuensi bins pada melspectrogram
        const val EMBEDDING_DIM         = 96  // dimensi embedding AudioSet
        const val EMBEDDING_WINDOW_SIZE = 16  // jumlah embedding per window classifier

        // Threshold default untuk model oke_elfan.onnx.
        // Berdasarkan kalibrasi: Positif "Oke Elfan" = 0.999997, Negatif = 0.0003 - 0.011, Hening = 0.000129.
        // Nilai 0.85f dengan persistence 2 frame (160ms) mencegah false trigger dari gumaman/suara kecil.
        const val DEFAULT_THRESHOLD = 0.85f

        // Verifikasi Frame Berurutan (Patience/Debounce):
        // Membutuhkan minimal 2 frame (160ms) berurutan di atas threshold agar tidak terpicu oleh spike noise sesaat.
        const val REQUIRED_CONSECUTIVE_HITS = 2

        // Energy Gate (RMS minimum):
        // Suara hening, bisikan, desah nafas, atau noise ruangan di bawah 250 RMS diblokir dari deteksi wake word.
        const val MIN_CHUNK_RMS = 250

        // Warmup: abaikan N inferensi pertama untuk mengisi ring buffer
        const val WARMUP_INFERENCES = 4

        // Cooldown setelah deteksi untuk mencegah pemicuan berulang
        const val COOLDOWN_INFERENCES = 10
    }

    // ── State ──────────────────────────────────────────────────────────────────

    @Volatile var isInitialized = false
        private set

    @Volatile var threshold: Float = DEFAULT_THRESHOLD

    private var melspecSession: ai.onnxruntime.OrtSession? = null
    private var embeddingSession: ai.onnxruntime.OrtSession? = null
    private var classifierSession: ai.onnxruntime.OrtSession? = null
    private var ortEnv: ai.onnxruntime.OrtEnvironment? = null

    // Ring buffers
    private val melBuffer = ArrayDeque<FloatArray>()      // [mel_freq_bins=32] per frame
    private val embeddingBuffer = ArrayDeque<FloatArray>() // [embedding_dim=96] per embedding

    // Inference counters
    private var inferenceCount = 0
    private var cooldownCount = 0
    private var consecutiveHits = 0

    /** Puncak skor pada inferensi terakhir. Thread-safe via @Volatile. */
    @Volatile var lastScore: Float = 0f
        private set

    /** RMS energi audio pada chunk terakhir. */
    @Volatile var lastChunkRms: Int = 0
        private set

    // ── Inisialisasi ──────────────────────────────────────────────────────────

    /**
     * Inisialisasi ONNX Runtime dan muat ketiga model dari assets.
     * Harus dipanggil sebelum [processChunk].
     * @throws IllegalStateException jika salah satu model gagal dimuat.
     */
    fun initialize() {
        if (isInitialized) return
        try {
            ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val opts = ai.onnxruntime.OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }

            melspecSession    = ortEnv!!.createSession(extractAsset(ASSET_MELSPEC), opts)
            embeddingSession  = ortEnv!!.createSession(extractAsset(ASSET_EMBEDDING), opts)
            classifierSession = ortEnv!!.createSession(extractAsset(ASSET_CLASSIFIER), opts)

            isInitialized = true
            Log.i(TAG, "✅ OnnxWakeWordDetector berhasil diinisialisasi")
            Log.i(TAG, "   Threshold: $threshold")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Gagal menginisialisasi ONNX sessions", e)
            release()
            throw IllegalStateException("OnnxWakeWordDetector gagal diinisialisasi: ${e.message}", e)
        }
    }

    // ── Pemrosesan Audio ──────────────────────────────────────────────────────

    /**
     * Memproses satu chunk 1280 sampel PCM 16-bit.
     * Harus dipanggil di thread bukan Main (misal: Dispatchers.IO atau thread AudioRecord).
     *
     * @param pcm16 Array int16 dengan panjang tepat [CHUNK_SAMPLES].
     * @return Skor wake word [0.0, 1.0], atau -1.0 jika buffer belum terisi.
     */
    fun processChunk(pcm16: ShortArray): Float {
        if (!isInitialized) return -1f
        if (cooldownCount > 0) {
            cooldownCount--
            return lastScore
        }

        // Hitung RMS energi audio chunk saat ini untuk energy gating
        var sumSquares = 0.0
        for (s in pcm16) {
            sumSquares += s.toDouble() * s.toDouble()
        }
        val currentRms = if (pcm16.isNotEmpty()) kotlin.math.sqrt(sumSquares / pcm16.size).toInt() else 0
        lastChunkRms = currentRms

        return try {
            // Step 1: Melspectrogram
            val melFrames = runMelspectrogram(pcm16) ?: return -1f
            for (frame in melFrames) {
                melBuffer.addLast(frame)
            }

            // Step 2: Embedding
            if (melBuffer.size >= MEL_WINDOW_SIZE) {
                val window = melBuffer.take(MEL_WINDOW_SIZE).toTypedArray()
                repeat(MEL_STEP_SIZE) { if (melBuffer.isNotEmpty()) melBuffer.removeFirst() }

                val embedding = runEmbedding(window) ?: return -1f
                embeddingBuffer.addLast(embedding)

                // Jaga ukuran buffer embedding
                while (embeddingBuffer.size > EMBEDDING_WINDOW_SIZE) {
                    embeddingBuffer.removeFirst()
                }

                // Step 3: Classifier
                if (embeddingBuffer.size == EMBEDDING_WINDOW_SIZE) {
                    inferenceCount++
                    if (inferenceCount <= WARMUP_INFERENCES) return -1f  // warmup

                    val score = runClassifier(embeddingBuffer.toTypedArray())
                    lastScore = score
                    return score
                }
            }

            -1f
        } catch (e: Exception) {
            Log.e(TAG, "Error saat processChunk", e)
            -1f
        }
    }

    /**
     * Menentukan apakah skor melebihi threshold dan memicu cooldown.
     * Dilengkapi energy gate (RMS) dan verifikasi frame berurutan (patience)
     * untuk mencegah false trigger dari gumaman/suara kecil/noise sesaat.
     *
     * @param score Skor dari [processChunk]
     * @param chunkRms RMS energi chunk saat ini (default: [lastChunkRms])
     */
    fun isWakeWordDetected(score: Float, chunkRms: Int = lastChunkRms): Boolean {
        if (score < 0) return false

        // 1. Energy Gate: Jika energi audio terlalu kecil (< MIN_CHUNK_RMS), abaikan
        if (chunkRms < MIN_CHUNK_RMS) {
            consecutiveHits = 0
            return false
        }

        // 2. Threshold & Persistence (Patience) Check
        return if (score >= threshold) {
            consecutiveHits++
            Log.d(TAG, "Wake word candidate: score=$score (threshold=$threshold, hits=$consecutiveHits/$REQUIRED_CONSECUTIVE_HITS, rms=$chunkRms)")
            if (consecutiveHits >= REQUIRED_CONSECUTIVE_HITS) {
                consecutiveHits = 0
                cooldownCount = COOLDOWN_INFERENCES
                Log.i(TAG, "🚨 Wake word tervalidasi! Skor: $score (threshold: $threshold, rms: $chunkRms)")
                true
            } else {
                false
            }
        } else {
            consecutiveHits = 0
            false
        }
    }

    /** Atur ulang state buffer dan counter tanpa menutup session. */
    fun reset() {
        melBuffer.clear()
        embeddingBuffer.clear()
        inferenceCount = 0
        cooldownCount = 0
        consecutiveHits = 0
        lastScore = 0f
        lastChunkRms = 0
        Log.d(TAG, "Buffer detector direset")
    }

    /** Tutup semua ONNX session dan lepaskan resource. */
    fun release() {
        try { melspecSession?.close() } catch (_: Exception) {}
        try { embeddingSession?.close() } catch (_: Exception) {}
        try { classifierSession?.close() } catch (_: Exception) {}
        // OrtEnvironment sengaja tidak di-close
        melspecSession = null
        embeddingSession = null
        classifierSession = null
        ortEnv = null
        isInitialized = false
        melBuffer.clear()
        embeddingBuffer.clear()
        Log.i(TAG, "OnnxWakeWordDetector dilepaskan")
    }

    // ── Private: Pipeline ONNX ────────────────────────────────────────────────

    /**
     * Jalankan melspectrogram.onnx.
     * Input: [1, 1280] float32 (dicast dari int16)
     * Output: List[FloatArray(32)] — 5 frame mel setelah transform
     */
    private fun runMelspectrogram(pcm16: ShortArray): List<FloatArray>? {
        val sess = melspecSession ?: return null
        val env  = ortEnv ?: return null

        // Cast int16 → float32 tanpa normalisasi tambahan (sesuai AudioFeatures source)
        val floatData = FloatArray(pcm16.size) { pcm16[it].toFloat() }
        val inputShape = longArrayOf(1L, pcm16.size.toLong())

        val inputTensor = ai.onnxruntime.OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(floatData),
            inputShape
        )

        return inputTensor.use {
            val result = sess.run(mapOf("input" to it))
            result.use { res ->
                // Output shape: [1, 1, 5, 32] — squeeze ke [5, 32]
                @Suppress("UNCHECKED_CAST")
                val raw = (res[0].value as Array<Array<Array<FloatArray>>>)
                val frames = raw[0][0]  // [5][32]

                // Apply transform: x / 10 + 2  (sesuai AudioFeatures._get_melspectrogram)
                frames.map { frame ->
                    FloatArray(frame.size) { i -> frame[i] / 10f + 2f }
                }
            }
        }
    }

    /**
     * Jalankan embedding_model.onnx.
     * Input: [1, 76, 32, 1] float32
     * Output: FloatArray(96) — 96-dim embedding
     */
    private fun runEmbedding(melWindow: Array<FloatArray>): FloatArray? {
        val sess = embeddingSession ?: return null
        val env  = ortEnv ?: return null

        // Build [1, 76, 32, 1] dari [76][32]
        val flatSize = MEL_WINDOW_SIZE * MEL_FREQ_BINS
        val flat = FloatArray(flatSize)
        for (t in 0 until MEL_WINDOW_SIZE) {
            val frame = melWindow[t]
            for (f in 0 until MEL_FREQ_BINS) {
                flat[t * MEL_FREQ_BINS + f] = frame[f]
            }
        }

        val inputShape = longArrayOf(1L, MEL_WINDOW_SIZE.toLong(), MEL_FREQ_BINS.toLong(), 1L)
        val inputTensor = ai.onnxruntime.OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flat),
            inputShape
        )

        return inputTensor.use {
            val result = sess.run(mapOf("input_1" to it))
            result.use { res ->
                // Output shape: [1, 1, 1, 96] — squeeze ke (96,)
                @Suppress("UNCHECKED_CAST")
                val raw = (res[0].value as Array<Array<Array<FloatArray>>>)
                raw[0][0][0]  // FloatArray(96)
            }
        }
    }

    /**
     * Jalankan classifier oke_elfan.onnx.
     * Input: [1, 16, 96] float32
     * Output: Float sigmoid score [0..1]
     */
    private fun runClassifier(embeddings: Array<FloatArray>): Float {
        val sess = classifierSession ?: return 0f
        val env  = ortEnv ?: return 0f

        // Build [1, 16, 96]
        val flatSize = EMBEDDING_WINDOW_SIZE * EMBEDDING_DIM
        val flat = FloatArray(flatSize)
        for (t in 0 until EMBEDDING_WINDOW_SIZE) {
            val emb = embeddings[t]
            for (d in 0 until EMBEDDING_DIM) {
                flat[t * EMBEDDING_DIM + d] = emb[d]
            }
        }

        val inputShape = longArrayOf(1L, EMBEDDING_WINDOW_SIZE.toLong(), EMBEDDING_DIM.toLong())
        val inputTensor = ai.onnxruntime.OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flat),
            inputShape
        )

        return inputTensor.use {
            val result = sess.run(mapOf("input" to it))
            result.use { res ->
                @Suppress("UNCHECKED_CAST")
                val raw = (res[0].value as Array<FloatArray>)
                raw[0][0]  // scalar sigmoid
            }
        }
    }

    // ── Private: Asset Helper ─────────────────────────────────────────────────

    /**
     * Ekstrak file dari assets ke direktori cache agar dapat dibaca oleh OrtSession.
     * OrtSession tidak mendukung pembacaan InputStream langsung.
     */
    private fun extractAsset(assetName: String): String {
        val cacheFile = File(context.cacheDir, "oww_$assetName")
        val assetSize = try {
            context.assets.open(assetName).use { it.available().toLong() }
        } catch (_: Exception) { -1L }

        if (!cacheFile.exists() || cacheFile.length() == 0L || (assetSize > 0 && cacheFile.length() != assetSize)) {
            Log.d(TAG, "Mengekstrak asset: $assetName → ${cacheFile.absolutePath} (size=$assetSize)")
            context.assets.open(assetName).use { input: InputStream ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }
        }
        return cacheFile.absolutePath
    }
}
