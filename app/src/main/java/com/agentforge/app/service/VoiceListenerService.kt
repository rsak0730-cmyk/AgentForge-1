package com.agentforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.agentforge.app.R
import com.agentforge.app.agent.AgentEngine
import com.agentforge.app.agent.AiClient
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceListenerService : Service(), TextToSpeech.OnInitListener {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var prefs: AppPrefs
    private lateinit var shizuku: ShizukuBridge
    private lateinit var engine: AgentEngine
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var isAwaitingCommand = false

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        shizuku = ShizukuBridge(this)
        engine = AgentEngine(this, AiClient(prefs), shizuku)
        tts = TextToSpeech(this, this)

        startForegroundNotification()
        initSpeechRecognizer()
        startListening()
    }

    private fun startForegroundNotification() {
        val channelId = "agentforge_voice"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Voice Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(prefs.name)
            .setContentText("Wake-word listening active (${prefs.wakeWord})")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(101, notification)
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    // Fail ya silence hone par background loop restart karo
                    restartListening()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
                    handleSpokenInput(spoken)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            restartListening()
        }
    }

    private fun restartListening() {
        scope.launch {
            kotlinx.coroutines.delay(600)
            startListening()
        }
    }

    private fun handleSpokenInput(spoken: String) {
        val wake = prefs.wakeWord.lowercase(Locale.getDefault()).trim()

        // 1. Shutdown / Sleep Mode Check
        if (prefs.isSleeping) {
            if (spoken.contains(wake) && (spoken.contains("wake up") || spoken.contains("uth jao") || spoken.contains("on ho jao") || spoken.contains("jago"))) {
                prefs.isSleeping = false
                speakAndStop("I am awake now. What can I do for you?")
                isAwaitingCommand = true
                return
            } else {
                // Sleep mode me hai aur wake command nahi hai to ignore karo aur dobara listen karo
                restartListening()
                return
            }
        }

        // 2. Shut Down / Turn Off Command Check
        if (spoken.contains("shutdown") || spoken.contains("shut down") || spoken.contains("so jao") || spoken.contains("turn off") || spoken.contains("band ho jao")) {
            prefs.isSleeping = true
            isAwaitingCommand = false
            speakAndStop("Shutting down. Say wake up to activate me again.")
            return
        }

        // 3. Siri-style Trigger Logic
        if (spoken.contains(wake)) {
            val commandAfterWake = spoken.substringAfter(wake).trim()
            if (commandAfterWake.isNotEmpty()) {
                // e.g. "Hey Mira open YouTube" -> Turant action execute karo
                processCommand(commandAfterWake)
            } else {
                // Sirf "Hey Mira" bola -> Promp do aur next command ke liye listen karo
                isAwaitingCommand = true
                speakThenListen("Yes? I am listening.")
            }
            return
        }

        // 4. Follow-up Command Execution
        if (isAwaitingCommand) {
            isAwaitingCommand = false
            processCommand(spoken)
            return
        }

        // Agar bina wake-word ka random noise/sound hai toh silently reset
        restartListening()
    }

    private fun processCommand(cmd: String) {
        AgentAccessibilityService.instance?.showIsland("Processing: $cmd")
        scope.launch {
            val reply = engine.execute(cmd)
            // Task complete hone ke baad bolkar mic turant band kar do
            speakAndStop(reply)
        }
    }

    private fun speakAndStop(text: String) {
        recognizer?.stopListening()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_ID")
        // Bolne ke baad regular wake-word listening pe wapas jao
        restartListening()
    }

    private fun speakThenListen(text: String) {
        recognizer?.stopListening()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "LISTEN_AGAIN")
        scope.launch {
            kotlinx.coroutines.delay(1800)
            startListening()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
        }
    }

    override fun onDestroy() {
        job.cancel()
        recognizer?.destroy()
        tts?.shutdown()
        shizuku.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
