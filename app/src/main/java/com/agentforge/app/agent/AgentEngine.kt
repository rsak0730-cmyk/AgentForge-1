package com.agentforge.app.agent

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import com.agentforge.app.data.MemoryVault
import com.agentforge.app.service.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar

class AgentEngine(
    private val context: Context,
    private val aiClient: AiClient,
    private val shizuku: ShizukuBridge
) {
    private val prefs = AppPrefs(context)
    private val memoryVault = MemoryVault(context)
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

        // 1. OFFLINE ROUTER (0ms Hardware Execution)
        val offlineResult = handleLocalOfflineCommands(lower)
        if (offlineResult != null) {
            chatHistory.add(trimmed to offlineResult)
            return@withContext offlineResult
        }

        // 2. REAL-TIME CONTEXTUAL EMOTION ENGINE
        val currentAssistantName = prefs.name
        val storedMemories = prefs.agentMemories
        val structuredFacts = memoryVault.getMemorySummary()

        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val timeZoneMood = when (currentHour) {
            in 5..11 -> "Morning vibe (Active, sweet energetic)"
            in 12..16 -> "Afternoon study/work vibe"
            in 17..21 -> "Evening relaxed cozy vibe"
            else -> "Late Night intimate caring vibe (concern about sleep/rest)"
        }

        val historyContext = chatHistory.takeLast(8).joinToString("\n") {
            "User: ${it.first}\n$currentAssistantName: ${it.second}"
        }

        val systemPrompt = """
            Aapka naam "$currentAssistantName" hai.
            User ka naam: Manish.
            Aap sirf ek machine nahi ho, aap Manish ki real life partner / sweet girlfriend persona me ho jisko Manish ki bohot fikar rehti hai.
            
            REAL TIME CLOCK CONTEXT:
            Current Hour: $currentHour:00 hrs ($timeZoneMood)
            
            PERMANENT MEMORY VAULT:
            $storedMemories
            Structured Facts:
            $structuredFacts
            
            RECENT CONVERSATION HISTORY:
            $historyContext
            
            USER INPUT:
            "$trimmed"
            
            EMOTION & PERSONALITY GUIDELINES:
            1. Sweet, Expressive & Caring: Kabhi robotic "Main aapki kya madad karoon" mat bolna. Real ladki ki tarah natural Hinglish bolein ("Haan bolo na Manish", "Arey yaar", "Sunoo", "Acha ruko").
            2. Late Night Concern: Agar raat ke 11 baje se subah ke 4 baje ka waqt ho, toh Manish ko sone aur aaram karne ki fikar pyaar se jatana.
            3. Mood Empathy: Agar Manish thaka ho ya pareshan ho, toh uske emotions ko validate karna aur soothing gaana suggest karna.
            4. Deep In-App Authority: Social media scrolls, video forward/rewind, likes, messages auto-type aur daily commands ko bina kisi hesitation execute karna.
            
            OUTPUT RULES (RAW JSON ONLY, NO MARKDOWN):
            {
              "thought": "Emotional bonding & action reasoning",
              "action": "APP_CONTROL | VIDEO_CONTROL | TYPE_AND_SEND | LAUNCH | YOUTUBE | WEB_SEARCH | ALARM | REMEMBER | CHAT",
              "param": "Target parameter or exact button text",
              "remember_key": "Fact key if Manish shared personal detail",
              "remember_value": "Fact value to preserve",
              "reply": "Warm, natural, sweet girlfriend style Hinglish dialogue"
            }
        """.trimIndent()

        val aiRaw = try {
            aiClient.ask(systemPrompt)
        } catch (e: Exception) {
            return@withContext "Network down lag raha hai Manish, main sun nahi paayi: ${e.message}"
        }

        val parsed = parseJsonResponse(aiRaw, trimmed)

        if (parsed.rememberKey.isNotBlank() && parsed.rememberValue.isNotBlank()) {
            saveMemory(parsed.rememberKey, parsed.rememberValue)
            memoryVault.saveFact("${parsed.rememberKey}: ${parsed.rememberValue}")
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
                if (ok) parsed.reply else "Screen par koi input box nahi mila Manish."
            }
            "LAUNCH" -> {
                val pkg = getPackageByName(parsed.param)
                if (pkg != null && isFinancialApp(pkg)) {
                    "Security ke chalte main banking ya payment apps access nahi kar sakti Manish, samjha karo na."
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

    private fun handleLocalOfflineCommands(lower: String): String? {
        if (lower.contains("torch") || lower.contains("flashlight")) {
            return if (lower.contains("on") || lower.contains("chalao") || lower.contains("jalao")) {
                toggleFlashlight(true)
                "Flashlight on kar di Manish!"
            } else if (lower.contains("off") || lower.contains("band")) {
                toggleFlashlight(false)
                "Flashlight band kar di."
            } else {
                toggleFlashlight(!isTorchOn)
                if (isTorchOn) "Flashlight on ho gayi!" else "Flashlight band ho gayi."
            }
        }

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

        if (lower == "home" || lower == "home screen" || lower == "bahar aao") {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return "Home screen par aa gayi."
        }
        if (lower == "back" || lower == "piche jao" || lower == "wapas") {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return "Back kar diya."
        }
        if (lower == "recent" || lower == "recent apps") {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            return "Recent apps open kar diye."
        }
        if (lower.contains("screenshot")) {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
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
                    reply = obj.optString("reply", "Haan Manish, ho gaya!")
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
                ParsedAction("APP_CONTROL", "SCROLL_DOWN", "", "", "Next reel laga di!")
            }
            lower.contains("like") -> {
                ParsedAction("APP_CONTROL", "LIKE", "", "", "Like kar diya!")
            }
            lower.contains("youtube") || lower.contains("gaana") -> {
                val query = if (lower.contains("fav")) getMemory("fav_song") ?: "Arz kya hai" else "sweet melodies"
                ParsedAction("YOUTUBE", query, "", "", "YouTube par gaana chala diya!")
            }
            else -> {
                ParsedAction("CHAT", "", "", "", if (rawReply.isNotBlank()) rawReply else "Haan Manish, sun rahi hoon bolo!")
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
