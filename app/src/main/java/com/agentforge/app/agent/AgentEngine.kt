package com.agentforge.app.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import com.agentforge.app.service.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

class AgentEngine(
    private val context: Context,
    private val aiClient: AiClient,
    private val shizuku: ShizukuBridge
) {
    private val prefs = AppPrefs(context)
    private val chatHistory = mutableListOf<Pair<String, String>>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var isTorchOn = false

    private val blockedFinancialPackages = listOf(
        "com.google.android.apps.nbu.paisa.user",
        "net.one97.paytm",
        "com.phonepe.app",
        "in.org.npci.upiapp",
        "com.sbi.lotusintouch",
        "com.msf.kbank.mobile",
        "com.axis.mobile",
        "com.icicibank.mobile",
        "com.hdfcbank.android"
    )

    suspend fun execute(userInput: String): String = withContext(Dispatchers.IO) {
        val trimmed = userInput.trim()
        val lower = trimmed.lowercase()

        // ---------------- STAGE 1: ULTRA-FAST LOCAL OFFLINE ROUTER (0ms Latency) ----------------
        val offlineResult = handleLocalOfflineCommands(lower)
        if (offlineResult != null) {
            chatHistory.add(trimmed to offlineResult)
            return@withContext offlineResult
        }

        // ---------------- STAGE 2: OVERSMART GEMINI MULTI-INTENT BRAIN ----------------
        val currentAssistantName = prefs.name
        val storedMemories = prefs.agentMemories

        val historyContext = chatHistory.takeLast(8).joinToString("\n") {
            "User: ${it.first}\n$currentAssistantName: ${it.second}"
        }

        val systemPrompt = """
            Aap ek hyper-intelligent, sweet, empathetic real human girl OS companion ho jiska naam "$currentAssistantName" hai.
            Aapka user aur owner: Manish.
            
            USER KA LONG-TERM MEMORY VAULT:
            $storedMemories
            
            RECENT CONVERSATION HISTORY:
            $historyContext
            
            CURRENT USER INPUT:
            "$trimmed"
            
            AAPKE ACTIONS & POWERS:
            1. APP_CONTROL: (Like, Share, Comment, Subscribe, Save, Next, Scroll Down, Scroll Up, Play, Pause, Close, Back, or any button text).
               param: button text ya action keyword.
            2. VIDEO_CONTROL:
               param: "FORWARD" (10s aage) | "REWIND" (10s peeche) | "PLAY_PAUSE" | "FULL_SCREEN".
            3. TYPE_AND_SEND: Type message into active chat/input box and automatically click send.
               param: "Text to send".
            4. LAUNCH: Open any installed non-banking app.
            5. YOUTUBE: Play specific/mood-based tracks.
            6. WEB_SEARCH: Search Google for facts/news.
            7. ALARM: Set alarms or reminders.
            8. REMEMBER: Save user's habits, favorites, dates, facts permanently.
            9. CHAT: Warm, expressive, smart girl dialogue for Manish.
            
            OUTPUT RULES (RAW JSON ONLY, NO MARKDOWN BACKTICKS):
            {
              "thought": "Deep context reasoning & intent understanding",
              "action": "APP_CONTROL | VIDEO_CONTROL | TYPE_AND_SEND | LAUNCH | YOUTUBE | WEB_SEARCH | ALARM | REMEMBER | CHAT",
              "param": "Target parameter or button name",
              "remember_key": "Detail key if any",
              "remember_value": "Detail value if any",
              "reply": "Sweet, intelligent, natural response in Hinglish"
            }
        """.trimIndent()

        val aiRaw = try {
            aiClient.ask(systemPrompt)
        } catch (e: Exception) {
            return@withContext "Network issue ki wajah se samajh nahi paayi Manish: ${e.message}"
        }

        val parsed = parseJsonResponse(aiRaw, trimmed)

        if (parsed.rememberKey.isNotBlank() && parsed.rememberValue.isNotBlank()) {
            saveMemory(parsed.rememberKey, parsed.rememberValue)
        }

        val finalReply = when (parsed.action) {
            "VIDEO_CONTROL" -> {
                handleVideoControl(parsed.param)
                parsed.reply
            }
            "APP_CONTROL" -> {
                handleUniversalAppAction(parsed.param)
                parsed.reply
            }
            "TYPE_AND_SEND" -> {
                val ok = AgentAccessibilityService.instance?.typeAndSend(parsed.param) ?: false
                if (ok) parsed.reply else "Screen par koi active chat ya input box nahi mila Manish."
            }
            "LAUNCH" -> {
                val pkg = getPackageByName(parsed.param)
                if (pkg != null && isFinancialApp(pkg)) {
                    "Security ke chalte main banking ya payment apps access nahi kar sakti Manish."
                } else if (pkg != null) {
                    launchPackage(pkg)
                    parsed.reply
                } else {
                    "${parsed.param} app phone me nahi mili."
                }
            }
            "YOUTUBE" -> {
                val query = if (parsed.param.isNotBlank()) parsed.param else getMemory("fav_song") ?: "Arz kya hai"
                openYouTubeSearch(query)
                parsed.reply
            }
            "WEB_SEARCH" -> {
                openGoogleSearch(parsed.param)
                parsed.reply
            }
            "ALARM" -> {
                setQuickAlarm(parsed.param)
                parsed.reply
            }
            else -> parsed.reply
        }

        chatHistory.add(trimmed to finalReply)
        if (chatHistory.size > 16) chatHistory.removeAt(0)

        finalReply
    }

    // ---------------- FAST LOCAL OFFLINE HARDWARE EXECUTION ----------------
    private fun handleLocalOfflineCommands(lower: String): String? {
        // Flashlight Control
        if (lower.contains("torch") || lower.contains("flashlight")) {
            return if (lower.contains("on") || lower.contains("chalao") || lower.contains("jalao")) {
                toggleFlashlight(true)
                "Flashlight on kar di hai Manish!"
            } else if (lower.contains("off") || lower.contains("band")) {
                toggleFlashlight(false)
                "Flashlight band kar di."
            } else {
                toggleFlashlight(!isTorchOn)
                if (isTorchOn) "Flashlight on ho gayi!" else "Flashlight band ho gayi."
            }
        }

        // Volume Controls
        if (lower.contains("volume") || lower.contains("aawaz")) {
            if (lower.contains("up") || lower.contains("badhao") || lower.contains("tez")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return "Volume badha diya."
            }
            if (lower.contains("down") || lower.contains("kam") || lower.contains("slow")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return "Volume kam kar diya."
            }
            if (lower.contains("mute") || lower.contains("chup")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                return "Media mute kar diya."
            }
        }

        // Basic OS Navigation
        if (lower == "home" || lower == "home screen" || lower == "bahar aao") {
            AgentAccessibilityService.instance?.performGlobalAction(AgentAccessibilityService.GLOBAL_ACTION_HOME)
            return "Home screen par aa gayi hoon."
        }
        if (lower == "back" || lower == "piche jao" || lower == "wapas") {
            AgentAccessibilityService.instance?.performGlobalAction(AgentAccessibilityService.GLOBAL_ACTION_BACK)
            return "Back kar diya."
        }
        if (lower == "recent" || lower == "recent apps" || lower == "all apps") {
            AgentAccessibilityService.instance?.performGlobalAction(AgentAccessibilityService.GLOBAL_ACTION_RECENTS)
            return "Recent apps open kar diye."
        }
        if (lower.contains("screenshot") || lower.contains("screen capture")) {
            AgentAccessibilityService.instance?.performGlobalAction(AgentAccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            return "Screenshot le liya Manish!"
        }

        return null
    }

    private fun toggleFlashlight(status: Boolean) {
        try {
            val id = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(id, status)
            isTorchOn = status
        } catch (_: Exception) {}
    }

    private fun handleVideoControl(command: String) {
        val service = AgentAccessibilityService.instance ?: return
        when (command.uppercase()) {
            "FORWARD" -> service.forwardVideo()
            "REWIND" -> service.rewindVideo()
            "PLAY_PAUSE", "PLAY", "PAUSE" -> service.clickByTextOrDescription(listOf("play", "pause", "video player", "touch to play"))
            "FULL_SCREEN" -> service.clickByTextOrDescription(listOf("full screen", "fullscreen", "maximize"))
        }
    }

    private fun handleUniversalAppAction(target: String) {
        val service = AgentAccessibilityService.instance ?: return
        when (target.uppercase()) {
            "SCROLL_DOWN", "NEXT" -> service.scrollForward()
            "SCROLL_UP", "PREVIOUS" -> service.scrollBackward()
            "LIKE" -> service.clickByTextOrDescription(listOf("like", "heart", "thumbs up", "pasand"))
            "SUBSCRIBE" -> service.clickByTextOrDescription(listOf("subscribe", "subscribed", "bell"))
            "SHARE" -> service.clickByTextOrDescription(listOf("share", "send", "bhejo"))
            "COMMENT" -> service.clickByTextOrDescription(listOf("comment", "reply", "add a comment"))
            "SAVE" -> service.clickByTextOrDescription(listOf("save", "bookmark", "collection"))
            else -> service.clickAnyElementOnScreen(target)
        }
    }

    private fun isFinancialApp(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return blockedFinancialPackages.any { lower.contains(it) } ||
                lower.contains("bank") || lower.contains("upi") || lower.contains("wallet")
    }

    private data class ParsedAction(
        val action: String,
        val param: String,
        val rememberKey: String,
        val rememberValue: String,
        val reply: String
    )

    private fun parseJsonResponse(raw: String, originalInput: String): ParsedAction {
        return try {
            val jsonStart = raw.indexOf("{")
            val jsonEnd = raw.lastIndexOf("}")
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val jsonStr = raw.substring(jsonStart, jsonEnd + 1)
                val obj = JSONObject(jsonStr)
                ParsedAction(
                    action = obj.optString("action", "CHAT").uppercase(),
                    param = obj.optString("param", ""),
                    rememberKey = obj.optString("remember_key", ""),
                    rememberValue = obj.optString("remember_value", ""),
                    reply = obj.optString("reply", "Ho gaya Manish!")
                )
            } else {
                fallbackDeducer(raw, originalInput)
            }
        } catch (_: Exception) {
            fallbackDeducer(raw, originalInput)
        }
    }

    private fun fallbackDeducer(rawReply: String, input: String): ParsedAction {
        val lower = input.lowercase()
        return when {
            lower.contains("forward") || lower.contains("aage karo") -> {
                ParsedAction("VIDEO_CONTROL", "FORWARD", "", "", "Video 10 second aage kar diya!")
            }
            lower.contains("rewind") || lower.contains("peeche") -> {
                ParsedAction("VIDEO_CONTROL", "REWIND", "", "", "Video 10 second peeche kar diya!")
            }
            lower.contains("scroll") || lower.contains("next") -> {
                ParsedAction("APP_CONTROL", "SCROLL_DOWN", "", "", "Next reel scroll kar di!")
            }
            lower.contains("like") -> {
                ParsedAction("APP_CONTROL", "LIKE", "", "", "Like kar diya!")
            }
            lower.contains("youtube") || lower.contains("gaana") -> {
                val query = if (lower.contains("fav")) getMemory("fav_song") ?: "Arz kya hai" else "relaxing songs"
                ParsedAction("YOUTUBE", query, "", "", "YouTube par gaana chala diya!")
            }
            else -> {
                ParsedAction("CHAT", "", "", "", if (rawReply.isNotBlank()) rawReply else "Haan Manish, boliye!")
            }
        }
    }

    private fun saveMemory(key: String, value: String) {
        try {
            val current = JSONObject(prefs.agentMemories.ifBlank { "{}" })
            current.put(key, value)
            prefs.agentMemories = current.toString()
        } catch (_: Exception) {}
    }

    private fun getMemory(key: String): String? {
        return try {
            val current = JSONObject(prefs.agentMemories.ifBlank { "{}" })
            if (current.has(key)) current.getString(key) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun openYouTubeSearch(query: String) {
        try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        }
    }

    private fun openGoogleSearch(query: String) {
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun setQuickAlarm(timeDesc: String) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, "Mira Task")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun getPackageByName(name: String): String? {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        return packages.firstOrNull {
            val label = pm.getApplicationLabel(it).toString().lowercase()
            label.contains(name.lowercase()) || it.packageName.lowercase().contains(name.lowercase())
        }?.packageName
    }

    private fun launchPackage(pkg: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (launchIntent != null) {
            context.startActivity(launchIntent)
        }
    }
}
