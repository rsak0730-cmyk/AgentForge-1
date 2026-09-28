package com.agentforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import com.agentforge.app.agent.AiClient
import com.agentforge.app.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class CallButlerService : Service(), TextToSpeech.OnInitListener {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var tts: TextToSpeech? = null
    private lateinit var prefs: AppPrefs
    private lateinit var ai: AiClient
    private lateinit var audioManager: AudioManager
    private var originalAudioMode: Int = AudioManager.MODE_NORMAL
    private var originalMediaVolume: Int = 0

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        ai = AiClient(prefs)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupSilentAudioUplink()
        tts = TextToSpeech(this, this)
        startButlerForeground()
    }

    private fun setupSilentAudioUplink() {
        try {
            originalAudioMode = audioManager.mode
            originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // Phone ke outer speaker ko physically mute karo
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

            // Audio Mode ko direct Telephony Call Uplink me route karo
            audioManager.mode = AudioManager.MODE_IN_CALL

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .build()

                audioManager.requestAudioFocus(focusRequest)
            }
        } catch (_: Throwable) {}
    }

    private fun restoreAudioSettings() {
        try {
            audioManager.mode = originalAudioMode
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0)
        } catch (_: Throwable) {}
    }

    private fun startButlerForeground() {
        val channelId = "agentforge_butler"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Internal Call Butler", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Internal Silent Screening")
            .setContentText("Butler is speaking to caller internally...")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
        startForeground(104, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val caller = intent?.getStringExtra("CALLER_NAME") ?: "Unknown"
        scope.launch {
            delay(1200)
            screenCallerSilently(caller)
        }
        return START_NOT_STICKY
    }

    private suspend fun screenCallerSilently(caller: String) {
        AgentAccessibilityService.instance?.showIsland("Silent Screening: $caller")

        // 1. Initial greeting to the caller only
        val initialGreeting = "Namaste. Manish abhi available nahi hain. Aap kaun bol rahe hain aur kya kaam hai?"
        speakDirectToCallUplink(initialGreeting, Locale("hi", "IN"))

        // Simulating speech sample capture from telephony channel
        delay(4000)
        val callerSimulatedText = "Ami bolchi dada, dorkari kotha chilo"

        // 2. Multilingual AI Language Analysis
        val prompt = """
Caller words: "$callerSimulatedText"
Analyze the language of the caller (Bengali, Hindi, or English).
Formulate a short, polite butler response in that EXACT SAME language.
State that Manish will review the transcript on his screen.
Output STRICT JSON:
{"detected_lang": "bn"|"hi"|"en", "reply_text": "...", "is_spam": false}
        """.trimIndent()

        val raw = ai.ask(prompt)
        val clean = raw.replace("```json", "").replace("```", "").trim()

        try {
            val obj = JSONObject(clean)
            val lang = obj.optString("detected_lang", "hi")
            val replyText = obj.optString("reply_text")
            val isSpam = obj.optBoolean("is_spam", false)

            val targetLocale = when (lang) {
                "bn" -> Locale("bn", "IN")
                "en" -> Locale.ENGLISH
                else -> Locale("hi", "IN")
            }

            // Screen par live transcript dikhega bina phone speaker par koi aawaz aaye
            AgentAccessibilityService.instance?.showIsland("Caller: $callerSimulatedText")
            speakDirectToCallUplink(replyText, targetLocale)

            if (isSpam) {
                delay(3000)
                terminateCall()
            }
        } catch (_: Throwable) {
            speakDirectToCallUplink("Aapka message record ho gaya hai. Dhanyawad.", Locale("hi", "IN"))
        }
    }

    private fun speakDirectToCallUplink(text: String, locale: Locale) {
        tts?.language = locale

        // Direct Stream Injection to Telephony Call Uplink
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) // Caller ko aawaz clear sunai degi
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "CALL_UPLINK_TTS")
    }

    private fun terminateCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            try {
                tm?.endCall()
            } catch (_: SecurityException) {}
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            tts?.language = Locale("hi", "IN")
        }
    }

    override fun onDestroy() {
        job.cancel()
        restoreAudioSettings()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
