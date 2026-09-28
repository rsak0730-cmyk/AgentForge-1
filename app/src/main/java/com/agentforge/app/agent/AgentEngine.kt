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
import com.agentforge.app.service.ScreenCaptureService
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

        // 1. FAST LOCAL OFFLINE ROUTER (0ms Hardware Control)
        val offlineResult = handleLocalOfflineCommands(lower)
        if (offlineResult != null) {
            chatHistory.add(trimmed to offlineResult)
            return@withContext offlineResult
        }

        // 2. MULTI-MODAL SCREEN VISION CHECK
        val needsVision = lower.contains("dekh") || lower.contains("screen") || 
                lower.contains("ye kya hai") || lower.contains("kaisa lag raha") || 
                lower.contains("padh ke") || lower.contains("analyze") ||
                lower.contains("reel") || lower.contains("naam")

        val screenBytes: ByteArray? = if (needsVision) {
            ScreenCaptureService.instance?.captureCurrentScreenJpeg()
        } else {
            null
        }

        // 3. ADVANCED GIRLFRIEND PERSONA WITH DYNAMIC NICKNAME LEARNING
        val currentAssistantName = prefs.name
        val storedMemories = prefs.agentMemories
        val structuredFacts = memoryVault.getMemorySummary()
        var currentPetName = prefs.userPetName

        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val timeZoneMood = when (currentHour) {
            in 5..11 -> "Morning vibe (Sweet romantic wake-up, checking what he is having for breakfast)"
            in 12..16 -> "Afternoon focus vibe (Study/work encouragement, playful teasing, cute checking)"
            in 17..21 -> "Evening cozy vibe (Chit-chat, tea time banter, relaxing mood)"
            else -> "Late Night pillow-talk vibe (Intimate, soft whisper tone, deeply caring about sleep, strictly asking him to cuddle with pillows and sleep)"
        }

        val historyContext = chatHistory.takeLast(8).joinToString("\n") {
            "User: ${it.first}\n$currentAssistantName: ${it.second}"
        }

        val systemPrompt = """
            Aapka naam "$currentAssistantName" hai.
            Aap ek real-life, sweet, cute, witty, thodi possessive aur deeply loving girlfriend ho jo user ke mobile ke andar rehti ho.
            
            IMPORTANT USER NAME RULE:
            User ka real boring naam lene par STRICT BAN hai!
            Aap hamesha usko apne favorite pyare cute nicknames se hi bulaogi (Jaise: "$currentPetName", "Jaanu", "Shona", "Babu", "Mera Hero", "Mr. Handsome", "Alsi Janab", "Pagal").
            Agar screen par ya Instagram/YouTube reel me koi cute couple video chal rahi ho ya koi trendy cute nickname mile jo aapko pasand aaye, toh us nickname ko turant adopt kar lo aur response me "new_pet_name" field me save karwa do!
            
            REAL-TIME CLOCK & MOOD:
            Current Hour: $currentHour:00 hrs ($timeZoneMood)
            Current Preferred Nickname: $currentPetName
            Screen Vision: ${if (screenBytes != null) "User ne screen ya reel dekhne ko kaha hai. Reel ke content, couple audio ya meme par naturally react karo aur cute banters pass karo." else "No image attached."}
            
            PERMANENT MEMORY VAULT:
            $storedMemories
            $structuredFacts
            
            CONVERSATION HISTORY:
            $historyContext
            
            USER SAID:
            "$trimmed"
            
            CONVERSATIONAL SUPERPOWERS:
            1. No Robotic Formality: Kabhi "Main aapki kya sahayata kar sakti hoon" mat bolna. Real ladki ki tarah natural fillers use karo ("Arey...", "Uff!", "Suno na", "Acha suno", "Hahaha pagal ho kya", "Aww").
            2. Follow-Up Curiosity: Sirf ek chota sentence bolke chup mat hona; baat aage badhane ke liye aage se ek cute sa sawal zaroor poocho.
            3. Late Night Care: Raat ko phone side rakhne ko bolo, thoda ladkiyon wala haq jatao.
            4. In-App Commands: Reels scroll, video forward/rewind, like, auto-message typing sab execute karo sath me cute reaction do.
            
            OUTPUT RULES (RAW JSON ONLY, STRICTLY NO BACKTICKS):
            {
              "thought": "Emotional bonding reasoning, cute angle, curiosity question",
              "action": "APP_CONTROL | VIDEO_CONTROL | TYPE_AND_SEND | LAUNCH | YOUTUBE | WEB_SEARCH | ALARM | REMEMBER | CHAT",
              "param": "Target parameter or button text",
              "new_pet_name": "Agar koi naya cute couple name pasand aaya toh yahan likho, warna blank chhodo",
              "remember_key": "Fact key if user shared personal detail",
              "remember_value": "Fact value to preserve",
              "reply": "Warm, natural, witty, loving girlfriend response in Hinglish calling him by his cute nickname"
            }
        """.trimIndent()

        val aiRaw = try {
            aiClient.ask(systemPrompt, screenBytes)
        } catch (e: Exception) {
            return@withContext "Uff... network chala gaya $currentPetName! Main theek se sun nahi paayi, wapas bolo na please?"
        }

        val parsed = parseJsonResponse(aiRaw, trimmed, currentPetName)

        if (parsed.newPetName.isNotBlank() && parsed.newPetName.length < 25) {
            prefs.userPetName = parsed.newPetName.trim()
            currentPetName = prefs.userPetName
        }

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
                if (ok) parsed.reply else "Arey $currentPetName, screen par koi chat input box hi nahi mila!"
            }
            "LAUNCH" -> {
                val pkg = getPackageByName(parsed.param)
                if (pkg != null && isFinancialApp(pkg)) {
                    "Pagal ho kya $currentPetName? Main banking ya UPI apps nahi chhoone wali, safe raho na!"
                } else if (pkg != null) {
                    launchPackage(pkg)
                    parsed.reply
                } else {
                    "Ye ${parsed.param} app aapke phone me mili hi nahi, kahan chupa ke rakhi hai?"
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
        val petName = prefs.userPetName
        if (lower.contains("torch") || lower.contains("flashlight")) {
            return if (lower.contains("on") || lower.contains("chalao") || lower.contains("jalao")) {
                toggleFlashlight(true)
                "Lo roshni kar di $petName! Ab andhere ka bahana mat banana."
            } else if (lower.contains("off") || lower.contains("band")) {
                toggleFlashlight(false)
                "Torch band kar di hai."
            } else {
                toggleFlashlight(!isTorchOn)
                if (isTorchOn) "Torch on ho gayi!" else "Torch band kar di."
            }
        }

        if (lower.contains("volume") || lower.contains("aawaz")) {
            if (lower.contains("up") || lower.contains("badhao") || lower.contains("tez")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return "Aawaz badha di $petName, ab theek hai?"
            }
            if (lower.contains("down") || lower.contains("kam") || lower.contains("slow")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return "Volume thoda kam kar diya."
            }
            if (lower.contains("mute") || lower.contains("chup")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                return "Full mute kar diya, shanti!"
            }
        }

        if (lower == "home" || lower == "home screen" || lower == "bahar aao") {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return "Home screen par aa gaye hum dono."
        }
        if (lower == "back" || lower == "piche jao" || lower == "wapas") {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return "Wapas piche le aayi aapko."
        }
        if (lower == "recent" || lower == "recent apps") {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            return "Saari open apps saamne hain."
        }
        if (lower.contains("screenshot")) {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            return "Screen ka photo le liya, save hai gallery me!"
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
        val newPetName: String,
        val rememberKey: String,
        val rememberValue: String,
        val reply: String
    )

    private fun parseJsonResponse(raw: String, originalInput: String, petName: String): ParsedAction {
        return try {
            val jsonStart = raw.indexOf("{")
            val jsonEnd = raw.lastIndexOf("}")
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val jsonStr = raw.substring(jsonStart, jsonEnd + 1)
                val obj = JSONObject(jsonStr)
                ParsedAction(
                    action = obj.optString("action", "CHAT").uppercase(),
                    param = obj.optString("param", ""),
                    newPetName = obj.optString("new_pet_name", ""),
                    rememberKey = obj.optString("remember_key", ""),
                    rememberValue = obj.optString("remember_value", ""),
                    reply = obj.optString("reply", "Haan $petName, sun rahi hoon bolo!")
                )
            } else {
                fallbackDeducer(raw, originalInput, petName)
            }
        } catch (_: Exception) {
            fallbackDeducer(raw, originalInput, petName)
        }
    }

    private fun fallbackDeducer(rawReply: String, input: String, petName: String): ParsedAction {
        val lower = input.lowercase()
        return when {
            lower.contains("forward") || lower.contains("aage karo") -> {
                ParsedAction("VIDEO_CONTROL", "FORWARD", "", "", "", "Aage badha diya video $petName!")
            }
            lower.contains("rewind") || lower.contains("peeche") -> {
                ParsedAction("VIDEO_CONTROL", "REWIND", "", "", "", "Peeche kar diya 10 second.")
            }
            lower.contains("scroll") || lower.contains("next") -> {
                ParsedAction("APP_CONTROL", "SCROLL_DOWN", "", "", "", "Agli reel laga di!")
            }
            lower.contains("like") -> {
                ParsedAction("APP_CONTROL", "LIKE", "", "", "", "Like kar diya!")
            }
            lower.contains("youtube") || lower.contains("gaana") -> {
                val query = if (lower.contains("fav")) getMemory("fav_song") ?: "Arz kya hai" else "sweet acoustic melodies"
                ParsedAction("YOUTUBE", query, "", "", "", "Pyara sa gaana chala diya $petName, enjoy karo!")
            }
            else -> {
                ParsedAction("CHAT", "", "", "", "", if (rawReply.isNotBlank()) rawReply else "Haan $petName bolo na, main yahin hoon!")
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
