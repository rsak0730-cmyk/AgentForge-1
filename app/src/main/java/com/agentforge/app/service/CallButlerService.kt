package com.agentforge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
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
import java.util.Locale

class CallButlerService : Service(), TextToSpeech.OnInitListener {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var tts: TextToSpeech? = null
    private lateinit var prefs: AppPrefs
    private lateinit var ai: AiClient

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        ai = AiClient(prefs)
        tts = TextToSpeech(this, this)
        startButlerForeground()
    }

    private fun startButlerForeground() {
        val channelId = "agentforge_butler"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Call Butler", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Call Butler Active")
            .setContentText("Screening incoming call...")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
        startForeground(104, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val caller = intent?.getStringExtra("CALLER_NAME") ?: "Unknown"
        scope.launch {
            delay(1500)
            screenCaller(caller)
        }
        return START_NOT_STICKY
    }

    private suspend fun screenCaller(caller: String) {
        AgentAccessibilityService.instance?.showIsland("Butler Screening: $caller")
        
        // Butler initial inquiry - dynamic multi-language prompt
        val initialGreeting = "Namaste. Manish abhi available nahi hain. Aap kaun bol rahe hain aur kya zaroori kaam hai?"
        speakResponse(initialGreeting, Locale("hi", "IN"))

        // Simulating speech sample analysis from caller
        delay(4000)
        val callerTranscriptSample = "Ami bolchi dada, dorkari kotha chilo" // Simulated audio capture to transcript

        // AI Language detection and contextual response
        val prompt = """
Caller transcript: "$callerTranscriptSample"
Analyze the language of the caller (Bengali, Hindi, or English).
Formulate a polite butler response in that EXACT SAME language explaining that the owner will call back soon, or ask them to leave a short note.
Output STRICT JSON:
{"detected_lang": "bn"|"hi"|"en", "response_text": "...", "is_spam": false}
        """.trimIndent()

        val raw = ai.ask(prompt)
        val clean = raw.replace("```json", "").replace("```", "").trim()
        try {
            val obj = org.json.JSONObject(clean)
            val lang = obj.optString("detected_lang", "hi")
            val replyText = obj.optString("response_text")
            val isSpam = obj.optBoolean("is_spam", false)

            val targetLocale = when (lang) {
                "bn" -> Locale("bn", "IN")
                "en" -> Locale.ENGLISH
                else -> Locale("hi", "IN")
            }

            AgentAccessibilityService.instance?.showIsland("Caller: $callerTranscriptSample")
            speakResponse(replyText, targetLocale)

            if (isSpam) {
                delay(3000)
                terminateCall()
            }
        } catch (_: Exception) {
            speakResponse("Dhanyawad. Message record kar liya gaya hai.", Locale("hi", "IN"))
        }
    }

    private fun speakResponse(text: String, locale: Locale) {
        tts?.language = locale
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "BUTLER_REPLY")
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
            tts?.language = Locale("hi", "IN")
        }
    }

    override fun onDestroy() {
        job.cancel()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
