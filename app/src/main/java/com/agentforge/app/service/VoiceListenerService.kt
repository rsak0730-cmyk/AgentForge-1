package com.agentforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.agentforge.app.agent.AgentEngine
import com.agentforge.app.agent.AiClient
import com.agentforge.app.agent.DialectAdapter
import com.agentforge.app.automation.AppWhitelistHelper
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import com.agentforge.app.security.VoiceprintManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceListenerService : Service(), TextToSpeech.OnInitListener {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: AppPrefs
    private lateinit var shizuku: ShizukuBridge
    private lateinit var engine: AgentEngine
    private lateinit var voiceprintManager: VoiceprintManager
    private lateinit var whitelistHelper: AppWhitelistHelper

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null

    @Volatile
    private var isSpeaking = false
    @Volatile
    private var isListeningNow = false
    private var isServiceAlive = true

    companion object {
        const val ACTION_START_LISTENING = "com.agentforge.app.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.agentforge.app.STOP_LISTENING"
        private const val CHANNEL_ID = "agentforge_voice"
        private const val NOTIFICATION_ID = 101
    }

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        prefs = AppPrefs(this)
        shizuku = ShizukuBridge(this)
        engine = AgentEngine(this, AiClient(prefs), shizuku)
        voiceprintManager = VoiceprintManager(this)
        whitelistHelper = AppWhitelistHelper(this, shizuku)
        tts = TextToSpeech(this, this)
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)

        whitelistHelper.ensureBackgroundSurvival()
        createNotificationChannel()
        updateServiceNotification("Hardware Standby • Hold Vol Up 3s to Talk")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                startOnDemandListening()
            }
            ACTION_STOP_LISTENING -> {
                stopListeningManually()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Automation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Volume Button Voice Trigger"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun updateServiceNotification(status: String) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(prefs.name)
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startOnDemandListening() {
        if (isSpeaking) {
            tts?.stop()
            isSpeaking = false
        }

        cleanupRecognizer()
        triggerTone(ToneGenerator.TONE_PROP_BEEP)
        updateServiceNotification("🎙️ Listening... (Press Vol Up to Stop)")

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningNow = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    val bars = ((rmsdB + 2) / 2).toInt().coerceIn(1, 6)
                    AgentAccessibilityService.instance?.showIsland("Listening: ${"|".repeat(bars)}")
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    if (buffer != null && prefs.isVoiceprintEnrolled) {
                        val features = voiceprintManager.extractAcousticFeatures(buffer, buffer.size)
                        if (!voiceprintManager.verifySpeaker(features)) {
                            AgentAccessibilityService.instance?.showIsland("Unauthorized Voice")
                        }
                    }
                }

                override fun onEndOfSpeech() {
                    isListeningNow = false
                }

                override fun onError(error: Int) {
                    isListeningNow = false
                    cleanupRecognizer()
                    updateServiceNotification("Hardware Standby • Hold Vol Up 3s to Talk")
                    AgentAccessibilityService.instance?.showIsland("Mic Standby")
                }

                override fun onResults(results: Bundle?) {
                    isListeningNow = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.trim() ?: ""
                    handleSpokenCommand(spoken)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            cleanupRecognizer()
        }
    }

    private fun stopListeningManually() {
        cleanupRecognizer()
        triggerTone(ToneGenerator.TONE_PROP_ACK)
        updateServiceNotification("Hardware Standby • Hold Vol Up 3s to Talk")
    }

    private fun handleSpokenCommand(spoken: String) {
        cleanupRecognizer()
        if (spoken.isBlank()) {
            AgentAccessibilityService.instance?.showIsland("Koi aawaz nahi aayi")
            updateServiceNotification("Hardware Standby • Hold Vol Up 3s to Talk")
            return
        }

        AgentAccessibilityService.instance?.showIsland("Thinking...")
        scope.launch {
            val reply = engine.execute(spoken)
            speakResponse(reply)
        }
    }

    private fun triggerTone(tone: Int) {
        try {
            toneGenerator?.startTone(tone, 100)
        } catch (_: Exception) {}
    }

    private fun speakResponse(text: String) {
        isSpeaking = true

        val dialect = DialectAdapter.detectDialect(text)
        tts?.language = dialect.ttsLocale
        tts?.setPitch(dialect.ttsPitch)
        tts?.setSpeechRate(dialect.ttsSpeechRate)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                mainHandler.post {
                    updateServiceNotification("Hardware Standby • Hold Vol Up 3s to Talk")
                    AgentAccessibilityService.instance?.showIsland("${prefs.name}: Standby")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                mainHandler.post {
                    updateServiceNotification("Hardware Standby • Hold Vol Up 3s to Talk")
                }
            }
        })

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MIRA_VOICE_OUT")
    }

    private fun cleanupRecognizer() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Throwable) {}
        recognizer = null
        isListeningNow = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
        }
    }

    override fun onDestroy() {
        isServiceAlive = false
        job.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        cleanupRecognizer()
        toneGenerator?.release()
        tts?.shutdown()
        shizuku.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
