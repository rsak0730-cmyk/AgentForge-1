package com.agentforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.agentforge.app.agent.AgentEngine
import com.agentforge.app.agent.AiClient
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import com.agentforge.app.security.VoiceprintManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceListenerService : Service(), TextToSpeech.OnInitListener, SensorEventListener {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var prefs: AppPrefs
    private lateinit var shizuku: ShizukuBridge
    private lateinit var engine: AgentEngine
    private lateinit var voiceprintManager: VoiceprintManager
    private lateinit var sensorManager: SensorManager

    private var proximitySensor: Sensor? = null
    private var isPhoneInPocket = false

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null

    @Volatile
    private var isEngineBusy = false
    @Volatile
    private var isListeningNow = false
    private var isAwaitingDirectCommand = false

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        shizuku = ShizukuBridge(this)
        engine = AgentEngine(this, AiClient(prefs), shizuku)
        voiceprintManager = VoiceprintManager(this)
        tts = TextToSpeech(this, this)
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)

        // Pocket / Proximity Guard
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        createNotificationChannel()
        updateServiceNotification(if (prefs.isSleeping) "Sleeping (Mic OFF)" else "Active • '${prefs.wakeWord}'")

        if (!prefs.isSleeping) {
            initRecognizerAndStart()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "agentforge_voice",
                "Voice Automation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun updateServiceNotification(status: String) {
        val notification: Notification = NotificationCompat.Builder(this, "agentforge_voice")
            .setContentTitle(prefs.name)
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(101, notification)
    }

    // ---------------- PROXIMITY GUARD ----------------
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            isPhoneInPocket = distance < maxRange

            if (isPhoneInPocket && isListeningNow) {
                destroyRecognizer()
            } else if (!isPhoneInPocket && !prefs.isSleeping && !isEngineBusy && !isListeningNow) {
                initRecognizerAndStart()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------- RECOGNIZER ENGINE ----------------
    private fun initRecognizerAndStart() {
        if (!SpeechRecognizer.isRecognitionAvailable(this) || isPhoneInPocket) return

        destroyRecognizer()

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningNow = true
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // Update visual wave amplitude in dynamic island if command is active
                    if (isAwaitingDirectCommand) {
                        AgentAccessibilityService.instance?.showIsland("Listening: ${"|".repeat(((rmsdB + 2) / 2).toInt().coerceIn(1, 6))}")
                    }
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
                    if (!prefs.isSleeping && !isEngineBusy && !isPhoneInPocket) {
                        safeRestart(1000)
                    }
                }
                override fun onResults(results: Bundle?) {
                    isListeningNow = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.lowercase(Locale.getDefault())?.trim() ?: ""
                    handleSpokenText(spoken)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        launchRecognitionIntent()
    }

    private fun launchRecognitionIntent() {
        if (prefs.isSleeping || isEngineBusy || isListeningNow || isPhoneInPocket) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer?.startListening(intent)
            isListeningNow = true
        } catch (_: Throwable) {
            safeRestart(1500)
        }
    }

    private fun safeRestart(delayMs: Long) {
        if (prefs.isSleeping || isEngineBusy || isPhoneInPocket) return
        scope.launch {
            delay(delayMs)
            if (!prefs.isSleeping && !isEngineBusy && !isListeningNow && !isPhoneInPocket) {
                launchRecognitionIntent()
            }
        }
    }

    // ---------------- SOUND & HAPTIC FEEDBACK ----------------
    private fun triggerWakeFeedback() {
        try {
            // Play short tech chime
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 120)

            // Distinctive Haptic tap
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Throwable) {}
    }

    private fun handleSpokenText(spoken: String) {
        if (spoken.isBlank()) {
            safeRestart(600)
            return
        }

        val wake = prefs.wakeWord.lowercase(Locale.getDefault()).trim()

        // 1. WAKE UP FROM SLEEP
        if (prefs.isSleeping) {
            if (spoken.contains("wake up") || spoken.contains("uth jao") || spoken.contains("on ho jao")) {
                prefs.isSleeping = false
                triggerWakeFeedback()
                updateServiceNotification("Ready for '${prefs.wakeWord}'")
                AgentAccessibilityService.instance?.showIsland("Mira: Online")
                speakAndFollowUp("Aapke sath hoon, boliye.", expectReply = true)
            }
            return
        }

        // 2. SHUTDOWN / SLEEP
        if (spoken.contains("shutdown") || spoken.contains("shut down") || spoken.contains("so jao") ||
            spoken.contains("turn off") || spoken.contains("band ho jao") || spoken.contains("sleep")) {

            prefs.isSleeping = true
            isAwaitingDirectCommand = false
            destroyRecognizer()
            updateServiceNotification("Sleeping (Mic OFF)")
            AgentAccessibilityService.instance?.showIsland("Mira: Sleeping")
            speakAndFollowUp("Shutting down. Mic abhi off hai.", expectReply = false)
            return
        }

        // 3. WAKE-WORD DETECTED
        if (spoken.contains(wake)) {
            triggerWakeFeedback()
            val leftoverCommand = spoken.substringAfter(wake).trim()
            if (leftoverCommand.isNotEmpty()) {
                executeUserCommand(leftoverCommand)
            } else {
                isAwaitingDirectCommand = true
                AgentAccessibilityService.instance?.showIsland("Listening...")
                speakAndFollowUp("Haan, boliye?", expectReply = true)
            }
            return
        }

        // 4. AWAITING DIRECT COMMAND
        if (isAwaitingDirectCommand) {
            isAwaitingDirectCommand = false
            executeUserCommand(spoken)
            return
        }

        safeRestart(600)
    }

    private fun executeUserCommand(command: String) {
        destroyRecognizer()
        isEngineBusy = true
        AgentAccessibilityService.instance?.showIsland("Thinking...")

        scope.launch {
            val response = engine.execute(command)
            speakAndFollowUp(response, expectReply = false)
        }
    }

    private fun speakAndFollowUp(text: String, expectReply: Boolean) {
        destroyRecognizer()
        isEngineBusy = true

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isEngineBusy = true
            }

            override fun onDone(utteranceId: String?) {
                isEngineBusy = false

                if (!prefs.isSleeping && !isPhoneInPocket) {
                    scope.launch {
                        delay(450)
                        if (expectReply) {
                            isAwaitingDirectCommand = true
                        }
                        initRecognizerAndStart()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isEngineBusy = false
                if (!prefs.isSleeping && !isPhoneInPocket) {
                    safeRestart(800)
                }
            }
        })

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MIRA_TTS_ID")
    }

    private fun destroyRecognizer() {
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
        job.cancel()
        sensorManager.unregisterListener(this)
        destroyRecognizer()
        toneGenerator?.release()
        tts?.shutdown()
        shizuku.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
