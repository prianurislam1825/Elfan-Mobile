package com.example.elfanmobile.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.elfanmobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "VoskPrototype"
        private const val NOTIFICATION_CHANNEL_ID = "elfan_wake_word_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.elfanmobile.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.example.elfanmobile.action.STOP_WAKE_WORD"
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                showToast("Mencoba menjalankan Vosk...")
                startWakeWord()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startWakeWord() {
        try {
            val notification = createNotification("Sedang memuat model...")
            
            // Perbaikan Bug: Tipe Microphone baru ada di API 30 (R)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            initModel()
        } catch (e: Exception) {
            showToast("Crash saat mulai: ${e.message}")
            Log.e(TAG, "Foreground error", e)
        }
    }

    private fun initModel() {
        Log.d(TAG, "Initializing Vosk model...")
        showToast("Mengekstrak model Vosk (jangan ditutup)...")
        
        StorageService.unpack(this, "model", "model",
            { model ->
                this.model = model
                updateNotification("Siap! Ucapkan 'Okay Elfan'")
                showToast("Model siap! Vosk mulai mendengarkan.")
                startRecognition()
            },
            { exception ->
                Log.e(TAG, "Failed to unpack the model: ${exception.message}", exception)
                updateNotification("Gagal memuat model")
                showToast("Gagal memuat model: ${exception.message}")
                stopSelf()
            }
        )
    }

    private fun startRecognition() {
        try {
            val recognizer = Recognizer(model, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)
            Log.d(TAG, "Vosk SpeechService started listening.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognition", e)
            showToast("Gagal menyalakan mikrofon: ${e.message}")
        }
    }

    // --- RecognitionListener Callbacks ---

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis != null && hypothesis.contains("okay", ignoreCase = true)) {
            if (hypothesis.contains("elephant") || hypothesis.contains("elf") || hypothesis.contains("alpha") || hypothesis.contains("often") || hypothesis.contains("fun")) {
                triggerDetectionIndicator(hypothesis)
            }
        }
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis != null && hypothesis.contains("okay", ignoreCase = true)) {
            triggerDetectionIndicator(hypothesis)
        }
    }
    
    private fun triggerDetectionIndicator(matchedText: String) {
        showToast("🚨 WAKE WORD TERDETEKSI!")
        updateNotification("✅ WAKE WORD TERDETEKSI!")
        
        scope.launch {
            delay(3000)
            updateNotification("Siap! Ucapkan 'Okay Elfan'")
        }
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {
        showToast("Error Mic Vosk: ${exception?.message}")
    }
    override fun onTimeout() {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "ELFAN Always Listening",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ELFAN (Vosk Prototype)")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        showToast("Vosk dimatikan.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
