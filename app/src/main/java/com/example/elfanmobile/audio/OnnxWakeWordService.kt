package com.example.elfanmobile.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.elfanmobile.R
import com.example.elfanmobile.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * OnnxWakeWordService
 *
 * Foreground Service untuk deteksi wake word "Oke Elfan" menggunakan pipeline ONNX lokal.
 * Menggantikan WakeWordService (Vosk prototype) sebagai implementasi utama.
 *
 * Alur:
 *   STANDBY: AudioRecord aktif → stream ke OnnxWakeWordDetector per 1280 sampel
 *   Deteksi: Hentikan AudioRecord → trigger VoiceCommandManager → TTS respons
 *   Kembali Standby: Restart AudioRecord setelah sesi perintah selesai
 *
 * Lifecycle:
 *   ACTION_START → startForeground → initDetector → startAudioLoop
 *   ACTION_STOP  → stopSelf → onDestroy → lepaskan AudioRecord + OnnxWakeWordDetector
 *
 * Thread Safety:
 *   AudioRecord dan OnnxWakeWordDetector berjalan di Dispatchers.IO.
 *   UI/state update dilakukan via Handler(Main) dan VoiceCommandManager.
 */
class OnnxWakeWordService : Service() {

    companion object {
        private const val TAG = "OnnxWakeWordService"
        private const val NOTIFICATION_CHANNEL_ID = "elfan_onnx_ww_channel"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.elfanmobile.action.START_ONNX_WW"
        const val ACTION_STOP  = "com.example.elfanmobile.action.STOP_ONNX_WW"

        /** Extra untuk mengatur threshold dari luar (misal dari SettingsScreen). */
        const val EXTRA_THRESHOLD = "extra_threshold"
    }

    // ── Komponen ───────────────────────────────────────────────────────────────

    private var detector: OnnxWakeWordDetector? = null
    private var audioRecord: AudioRecord? = null
    private var ttsManager: TtsManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioLoopJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isServiceRunning = false
    @Volatile private var isListening = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true

        // Acquire WakeLock agar proses deteksi tidak terbunuh saat layar mati
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ElfanMobile:OnnxWakeWordService").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Gagal acquire WakeLock: ${e.message}")
        }

        val tts = TtsManager(this)
        ttsManager = tts
        VoiceCommandManager.registerTtsManager(tts)

        createNotificationChannel()
        observeAssistantState()
        Log.i(TAG, "OnnxWakeWordService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val threshold = intent.getFloatExtra(EXTRA_THRESHOLD, OnnxWakeWordDetector.DEFAULT_THRESHOLD)
                startOnnxWakeWord(threshold)
                return START_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            null -> {
                // SVC-1: Penanganan aman untuk START_STICKY restart pada Android 12+
                // Jangan memanggil startForeground berulang atau membuka mikrofon dua kali.
                // Periksa preferensi wake word; jika aktif mulai ulang dengan aman, jika tidak hentikan service.
                scope.launch {
                    try {
                        val settings = SettingsRepository(applicationContext)
                        val enabled = withTimeoutOrNull(1000L) { settings.wakeWordEnabled.first() } ?: false
                        if (!enabled) {
                            Log.w(TAG, "Restart tanpa intent: wake word dinonaktifkan, menghentikan service")
                            stopSelf()
                            return@launch
                        }
                        if (isServiceRunning && (detector != null && detector?.isInitialized == true) && isListening) {
                            Log.d(TAG, "Restart tanpa intent: service sudah berjalan dengan benar")
                            return@launch
                        }
                        startOnnxWakeWord(OnnxWakeWordDetector.DEFAULT_THRESHOLD)
                    } catch (e: Exception) {
                        Log.e(TAG, "Gagal menangani restart tanpa intent", e)
                        stopSelf()
                    }
                }
                return START_STICKY
            }
            else -> {
                // Action tidak dikenal; pertahankan perilaku aman tanpa memulai ulang ganda
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopAudioLoop()
        releaseAudioRecord()
        detector?.release()
        detector = null
        ttsManager?.shutdown()
        ttsManager = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal melepaskan WakeLock: ${e.message}")
        }
        wakeLock = null

        Log.i(TAG, "OnnxWakeWordService onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Inisialisasi ───────────────────────────────────────────────────────────

    private fun startOnnxWakeWord(threshold: Float) {
        if (detector?.isInitialized == true && isListening && audioRecord != null) {
            Log.d(TAG, "startOnnxWakeWord: sudah berjalan, mengabaikan pemanggilan ganda")
            return
        }

        // Hindari pemanggilan startForeground berulang jika sudah foreground
        val notification = createNotification("Memuat model wake word ONNX...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground gagal atau sudah aktif: ${e.message}")
        }

        if (detector?.isInitialized == true) {
            val det = detector
            if (det != null) {
                updateNotification("Siap! Ucapkan 'Oke Elfan'")
                showToast("ELFAN ONNX siap mendengarkan")
                startAudioLoop(det)
                return
            }
        }

        scope.launch {
            try {
                // Inisialisasi detector di IO thread
                val det = OnnxWakeWordDetector(applicationContext).also {
                    it.threshold = threshold
                    it.initialize()
                }
                detector = det

                updateNotification("Siap! Ucapkan 'Oke Elfan'")
                showToast("ELFAN ONNX siap mendengarkan")
                startAudioLoop(det)
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menginisialisasi ONNX detector", e)
                updateNotification("Gagal memuat model")
                showToast("Gagal memuat model ONNX: ${e.message}")
                stopSelf()
            }
        }
    }

    // ── Audio Loop ─────────────────────────────────────────────────────────────

    private fun startAudioLoop(det: OnnxWakeWordDetector) {
        if (isListening && audioRecord != null) {
            Log.d(TAG, "AudioRecord loop sudah berjalan")
            return
        }
        stopAudioLoop()
        releaseAudioRecord()

        audioLoopJob = scope.launch {
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    OnnxWakeWordDetector.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) * 2,
                OnnxWakeWordDetector.CHUNK_SAMPLES * 2
            )

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    OnnxWakeWordDetector.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Izin RECORD_AUDIO ditolak", e)
                showToast("Izin mikrofon diperlukan!")
                return@launch
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord gagal diinisialisasi (state=${record.state})")
                record.release()
                return@launch
            }

            audioRecord = record
            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Gagal startRecording", e)
                record.release()
                audioRecord = null
                return@launch
            }

            isListening = true
            det.reset()
            Log.i(TAG, "AudioRecord mulai merekam wake word (bufferSize=$bufferSize)")

            val chunkBuffer = ShortArray(OnnxWakeWordDetector.CHUNK_SAMPLES)

            while (isActive && isListening && isServiceRunning) {
                val read = record.read(chunkBuffer, 0, chunkBuffer.size)
                if (read <= 0) continue

                // Hanya proses jika state STANDBY
                val currentState = VoiceCommandManager.state.value
                if (currentState != VoiceCommandManager.AssistantState.STANDBY) break

                val score = det.processChunk(chunkBuffer)

                if (score >= 0) {
                    Log.v(TAG, "Score: ${"%.4f".format(score)}")
                }

                if (det.isWakeWordDetected(score, det.lastChunkRms)) {
                    onWakeWordDetected()
                    break
                }
            }

            try {
                record.stop()
                record.release()
            } catch (_: Exception) {}
            if (audioRecord == record) {
                audioRecord = null
            }
            isListening = false
            Log.i(TAG, "AudioRecord loop selesai")
        }
    }

    private fun stopAudioLoop() {
        isListening = false
        audioLoopJob?.cancel()
        audioLoopJob = null
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    // ── Deteksi Wake Word ──────────────────────────────────────────────────────

    private fun onWakeWordDetected() {
        // Guard: hanya proses di STANDBY
        if (VoiceCommandManager.state.value != VoiceCommandManager.AssistantState.STANDBY) return

        Log.i(TAG, "🚨 Wake word ONNX terdeteksi! Score=${detector?.lastScore}")
        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.WAKE_DETECTED)

        // Hentikan audio loop agar mikrofon dilepas segera
        stopAudioLoop()
        releaseAudioRecord()

        showToast("🚨 OKE ELFAN TERDETEKSI!")
        updateNotification("Memutar respons...")

        // Putar TTS konfirmasi exact "Ada yang bisa saya bantu?"
        mainHandler.post {
            VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.SPEAKING_PROMPT)
            VoiceCommandManager.speak(
                text = "Ada yang bisa saya bantu?",
                onDone = {
                    scope.launch {
                        delay(150) // Jeda 150ms anti-loopback
                        updateNotification("Mendengarkan perintah...")
                        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.LISTENING_COMMAND)
                    }
                },
                onError = { errMsg ->
                    Log.w(TAG, "TTS error: $errMsg, langsung ke LISTENING")
                    scope.launch {
                        delay(50)
                        updateNotification("Mendengarkan perintah...")
                        VoiceCommandManager.updateState(VoiceCommandManager.AssistantState.LISTENING_COMMAND)
                    }
                }
            )
        }
    }

    // ── Observasi Status Asisten ───────────────────────────────────────────────

    private fun observeAssistantState() {
        scope.launch(Dispatchers.Main) {
            VoiceCommandManager.state.collect { state ->
                Log.d(TAG, "OnnxWakeWordService observed state: $state")
                when (state) {
                    VoiceCommandManager.AssistantState.STANDBY -> {
                        if (isServiceRunning && !isListening) {
                            val det = detector
                            if (det != null && det.isInitialized) {
                                det.reset()
                                updateNotification("Siap! Ucapkan 'Oke Elfan'")
                                startAudioLoop(det)
                                Log.i(TAG, "ONNX detector kembali ke STANDBY dan mulai mendengarkan")
                            }
                        }
                    }
                    VoiceCommandManager.AssistantState.RECOVERING -> {
                        stopAudioLoop()
                        releaseAudioRecord()
                        detector?.reset()
                        updateNotification("Menyiapkan standby...")
                    }
                    VoiceCommandManager.AssistantState.WAKE_DETECTED,
                    VoiceCommandManager.AssistantState.SPEAKING_PROMPT,
                    VoiceCommandManager.AssistantState.LISTENING_COMMAND,
                    VoiceCommandManager.AssistantState.PROCESSING_COMMAND,
                    VoiceCommandManager.AssistantState.SPEAKING_RESPONSE -> {
                        if (isListening || audioRecord != null) {
                            stopAudioLoop()
                            releaseAudioRecord()
                        }
                    }
                }
            }
        }
    }

    // ── Notifikasi & Toast ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "ELFAN Wake Word (ONNX)",
                NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(ch)
        }
    }

    private fun updateNotification(text: String) {
        val notif = createNotification(text)
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, notif)
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ELFAN (ONNX Wake Word)")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
