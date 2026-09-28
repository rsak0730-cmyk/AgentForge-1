package com.agentforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import com.agentforge.app.agent.DialectAdapter
import com.agentforge.app.automation.AppWhitelistHelper
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import com.agentforge.app.security.VoiceprintManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceListenerService : Service(), TextToSpeech.OnInitListener, SensorEventListener {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: AppPrefs
    private lateinit var shizuku: ShizukuBridge
    private lateinit var engine: AgentEngine
    private lateinit var voiceprintManager: VoiceprintManager
    private lateinit var sensorManager: SensorManager
    private lateinit var whitelistHelper: AppWhitelistHelper

    private var proximitySensor: Sensor? = null
    private var isPhoneInPocket = false

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null

    // Dual-Stage Passive VAD Audio Thread
    private var passiveAudioRecord: AudioRecord? = null
    @Volatile
    private var isPassiveListening = false
    @Volatile
    private var isSpeaking = false
    @Volatile
    private var isListeningNow = false
    private var isAwaitingDirectCommand = false
    private var isServiceAlive = true

    companion object {
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

        // Kill-Guard Whitelist
        whitelistHelper.ensureBackgroundSurvival()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        createNotificationChannel()
        updateServiceNotification(if (prefs.isSleeping) "Sleeping (Mic OFF)" else "Active • '${prefs.wakeWord}'")

        if (!prefs.isSleeping) {
            startPassiveEnergyListening()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Automation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mira Active Listener Guard"
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

        // Android 14 & 15 Strict Mic FGS Requirement
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

    // ---------------- STAGE 1: LOW-POWER PASSIVE VAD LISTENER ----------------
    private fun startPassiveEnergyListening() {
        if (isPassiveListening || prefs.isSleeping || isPhoneInPocket || !isServiceAlive) return
        destroyRecognizer()

        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                
                if (minBuf <= 0) {
                    launchOnMain { safeRestart(1500) }
                    return@launch
                }

                passiveAudioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )

                if (passiveAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    stopPassiveListening()
                    launchOnMain { safeRestart(1500) }
                    return@launch
                }

                val buffer = ShortArray(minBuf / 2)
                passiveAudioRecord?.startRecording()
                isPassiveListening = true

                while (isActive && isPassiveListening && !prefs.isSleeping && !isPhoneInPocket && isServiceAlive) {
                    val read = passiveAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = Math.sqrt(sum / read)

                        // Threshold check: Voice detected -> Switch to Stage 2 SpeechRecognizer
                        if (rms > 1200.0) {
                            stopPassiveListening()
                            launchOnMain { initRecognizerAndStart() }
                            break
                        }
                    }
                    delay(35)
                }
            } catch (_: Exception) {
                stopPassiveListening()
                launchOnMain { safeRestart(1500) }
            }
        }
    }

    private fun stopPassiveListening() {
        isPassiveListening = false
        try {
            passiveAudioRecord?.stop()
            passiveAudioRecord?.release()
        } catch (_: Exception) {}
        passiveAudioRecord = null
    }

    private fun launchOnMain(block: () -> Unit) {
        if (isServiceAlive) {
            scope.launch(Dispatchers.Main) { block() }
        }
    }

    // ---------------- STAGE 2: SPEECH RECOGNIZER PIPELINE ----------------
    private fun initRecognizerAndStart() {
        if (!SpeechRecognizer.isRecognitionAvailable(this) || isPhoneInPocket || !isServiceAlive) {
            startPassiveEnergyListening()
            return
        }

        destroyRecognizer()

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningNow = true
                }

                override fun onBeginningOfSpeech() {
                    if (isSpeaking) {
                        tts?.stop()
                        isSpeaking = false
                        AgentAccessibilityService.instance?.showIsland("Interrupted • Listening...")
                    }
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (isAwaitingDirectCommand) {
                        val bars = ((rmsdB + 2) / 2).toInt().coerceIn(1, 6)
                        AgentAccessibilityService.instance?.showIsland("Listening: ${"|".repeat(bars)}")
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
                    destroyRecognizer()
                    if (!prefs.isSleeping && !isPhoneInPocket && isServiceAlive) {
                        // Resilient loop restart for Android 15
                        mainHandler.postDelayed({ startPassiveEnergyListening() }, 400)
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
        if (prefs.isSleeping || isListeningNow || isPhoneInPocket || !isServiceAlive) return
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
        if (prefs.isSleeping || isPhoneInPocket || !isServiceAlive) return
        scope.launch {
            delay(delayMs)
            if (!prefs.isSleeping && !isListeningNow && !isPhoneInPocket && isServiceAlive) {
                startPassiveEnergyListening()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            isPhoneInPocket = distance < maxRange

            if (isPhoneInPocket) {
                stopPassiveListening()
                destroyRecognizer()
            } else if (!prefs.isSleeping && !isListeningNow && isServiceAlive) {
                startPassiveEnergyListening()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerWakeFeedback() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Throwable) {}
    }

    private fun handleSpokenText(spoken: String) {
        if (spoken.isBlank()) {
            destroyRecognizer()
            startPassiveEnergyListening()
            return
        }

        val wake = prefs.wakeWord.lowercase(Locale.getDefault()).trim()
        val defaultName = prefs.name.lowercase(Locale.getDefault()).trim()

        if (prefs.isSleeping) {
            if (spoken.contains("wake up") || spoken.contains("uth jao") || spoken.contains("on ho jao") || spoken.contains(wake)) {
                prefs.isSleeping = false
                triggerWakeFeedback()
                updateServiceNotification("Active • '${prefs.wakeWord}'")
                AgentAccessibilityService.instance?.showIsland("Mira: Online")
                speakAndFollowUp("Aapke sath hoon, boliye.", expectReply = true)
            }
            return
        }

        if (spoken.contains("shutdown") || spoken.contains("shut down") || spoken.contains("so jao") ||
            spoken.contains("turn off") || spoken.contains("band ho jao") || spoken.contains("sleep") || spoken.contains("chup raho")) {

            prefs.isSleeping = true
            isAwaitingDirectCommand = false
            stopPassiveListening()
            destroyRecognizer()
            updateServiceNotification("Sleeping (Mic OFF)")
            AgentAccessibilityService.instance?.showIsland("Mira: Sleeping")
            speakAndFollowUp("Shutting down. Mic abhi off hai.", expectReply = false)
            return
        }

        if (spoken.contains(wake) || spoken.contains(defaultName)) {
            triggerWakeFeedback()
            val leftoverCommand = spoken.replace(wake, "").replace(defaultName, "").trim()
            if (leftoverCommand.isNotEmpty()) {
                executeUserCommand(leftoverCommand)
            } else {
                isAwaitingDirectCommand = true
                AgentAccessibilityService.instance?.showIsland("Listening...")
                speakAndFollowUp("Haan, boliye?", expectReply = true)
            }
            return
        }

        if (isAwaitingDirectCommand) {
            isAwaitingDirectCommand = false
            executeUserCommand(spoken)
            return
        }

        destroyRecognizer()
        startPassiveEnergyListening()
    }

    private fun executeUserCommand(command: String) {
        destroyRecognizer()
        AgentAccessibilityService.instance?.showIsland("Thinking...")

        scope.launch {
            val response = engine.execute(command)
            speakAndFollowUp(response, expectReply = false)
        }
    }

    private fun speakAndFollowUp(text: String, expectReply: Boolean) {
        destroyRecognizer()
        stopPassiveListening()
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
                if (!prefs.isSleeping && !isPhoneInPocket && isServiceAlive) {
                    mainHandler.postDelayed({
                        if (expectReply) {
                            isAwaitingDirectCommand = true
                            initRecognizerAndStart()
                        } else {
                            startPassiveEnergyListening()
                        }
                    }, 350)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                if (!prefs.isSleeping && !isPhoneInPocket && isServiceAlive) {
                    mainHandler.postDelayed({ startPassiveEnergyListening() }, 300)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isServiceAlive = false
        job.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        stopPassiveListening()
        destroyRecognizer()
        toneGenerator?.release()
        tts?.shutdown()
        shizuku.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
