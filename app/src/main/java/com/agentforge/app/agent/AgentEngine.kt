package com.agentforge.app.agent

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Environment
import android.provider.AlarmClock
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import com.agentforge.app.data.MemoryVault
import com.agentforge.app.network.AgentPeerSync
import com.agentforge.app.service.AgentAccessibilityService
import com.agentforge.app.service.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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

    private val peerSync = AgentPeerSync(context) { sender, message ->
        AgentAccessibilityService.instance?.let { service ->
            service.showIsland("📡 $sender: $message")
            service.speakDirectly("$sender keh raha hai: $message")
            service.triggerHeartbeatHaptic()
        }
    }.apply {
        startListener()
    }

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

        // 1. FAST LOCAL OFFLINE ROUTER
        val offlineResult = handleLocalOfflineCommands(lower)
        if (offlineResult != null) {
            chatHistory.add(trimmed to offlineResult)
            return@withContext offlineResult
        }

        // 2. FOCUS POMODORO ROUTER
        if (lower.contains("focus") || lower.contains("pomodoro") || lower.contains("padhai")) {
            val minutes = Regex("\\d+").find(lower)?.value?.toIntOrNull() ?: 25
            AgentAccessibilityService.startFocusMode(minutes)
            val msg = "Focus mode $minutes minute ke liye shuru ho gaya. Distractions block rahenge!"
            chatHistory.add(trimmed to msg)
            return@withContext msg
        }

        if (lower.contains("stop focus") || lower.contains("focus band")) {
            AgentAccessibilityService.stopFocusMode()
            val msg = "Focus mode band kar diya hai."
            chatHistory.add(trimmed to msg)
            return@withContext msg
        }

        // 3. MULTI-DEVICE PEER BROADCAST ROUTER
        if (lower.startsWith("send to peer") || lower.contains("dost ko bolo") || lower.contains("sync message")) {
            val targetIp = prefs.agentMemories.let {
                try { JSONObject(it).optString("peer_ip", "192.168.1.100") } catch (_: Exception) { "192.168.1.100" }
            }
            peerSync.broadcastPeerMessage(targetIp, "NOTIFY", trimmed, prefs.userPetName)
            val msg = "Dost ke phone par message broadcast kar diya hai."
            chatHistory.add(trimmed to msg)
            return@withContext msg
        }

        // 4. MULTI-MODAL SCREEN VISION CHECK
        val needsVision = lower.contains("dekh") || lower.contains("screen") || 
                lower.contains("ye kya hai") || lower.contains("kaisa lag raha") || 
                lower.contains("padh ke") || lower.contains("analyze") ||
                lower.contains("code") || lower.contains("error") || lower.contains("save code")

        val screenBytes: ByteArray? = if (needsVision) {
            ScreenCaptureService.instance?.captureCurrentScreenJpeg()
        } else {
            null
        }

        // 5. CONVERSATIONAL PROMPT & CODING SCRIPTER
        val isNearEar = AgentAccessibilityService.isNearEar
        val currentAssistantName = prefs.name
        val storedMemories = prefs.agentMemories
        val structuredFacts = memoryVault.getMemorySummary()
        val currentPetName = prefs.userPetName
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)

        val historyContext = chatHistory.takeLast(8).joinToString("\n") {
            "User: ${it.first}\n$currentAssistantName: ${it.second}"
        }

        val systemPrompt = """
            Aapka naam "$currentAssistantName" hai.
            Aap user ke advanced phone companion, coding co-pilot aur system automation agent ho.
            
            FEATURES:
            1. Screen Code Extraction: Agar user bole ki screen ka code save karo ya extract karo, action ko "SAVE_CODE" rakho, param me sanitized file extension ya path do, aur reply me confirm karo.
            2. Call Screening & Auto Excuse: Agar incoming call ka zikr ho ya decline karne ko bole, action "DECLINE_AND_TEXT" use karo aur param me excuse text do.
            3. Peer Sync: Doosre phone par message bhejne ke liye "PEER_BROADCAST" use karo.
            4. Tone: Confident, fast, natural Hinglish. User ko "$currentPetName" se address karo.
            5. Earpiece Whisper: ${if (isNearEar) "User kaan par phone lagaye hue hai, whisper short concise voice me bolo." else "Normal responsive speaker tone."}
            
            CLOCK: $currentHour:00 hrs
            Screen Vision: ${if (screenBytes != null) "Screen image attached. Extract errors, code snippets or analyze visual accurately." else "No image attached."}
            
            MEMORY VAULT:
            $storedMemories
            $structuredFacts
            
            HISTORY:
            $historyContext
            
            USER INPUT:
            "$trimmed"
            
            OUTPUT RULES (RAW JSON ONLY, STRICTLY NO BACKTICKS):
            {
              "thought": "Technical action reasoning and intent extraction",
              "action": "APP_CONTROL | VIDEO_CONTROL | TYPE_AND_SEND | LAUNCH | YOUTUBE | WEB_SEARCH | ALARM | SAVE_CODE | DECLINE_AND_TEXT | PEER_BROADCAST | REMEMBER | CHAT",
              "param": "Target parameter, button name, or text payload",
              "new_pet_name": "",
              "code_payload": "Agar SAVE_CODE hai toh yahan pure extracted python/js code likho, warna blank",
              "remember_key": "Fact key if user shared personal detail",
              "remember_value": "Fact value to preserve",
              "reply": "Crisp, helpful Hinglish response"
            }
        """.trimIndent()

        val aiRaw = try {
            aiClient.ask(systemPrompt, screenBytes)
        } catch (e: Exception) {
            return@withContext "Network issue aayi $currentPetName, wapas try karenge?"
        }

        val parsed = parseJsonResponse(aiRaw, trimmed, currentPetName)

        if (parsed.rememberKey.isNotBlank() && parsed.rememberValue.isNotBlank()) {
            saveMemory(parsed.rememberKey, parsed.rememberValue)
            memoryVault.saveFact("${parsed.rememberKey}: ${parsed.rememberValue}")
        }

        AgentAccessibilityService.instance?.triggerHeartbeatHaptic()

        val finalReply = when (parsed.action) {
            "SAVE_CODE" -> {
                val code = parsed.codePayload.ifBlank { "print('No code extracted')" }
                saveCodeToFile(code, "script_${System.currentTimeMillis() % 1000}.py")
                "Screen se code extract karke /sdcard/MiraScripts me save kar diya hai!"
            }
            "DECLINE_AND_TEXT" -> {
                declineCallAndSendText(parsed.param)
                "Call cut karke WhatsApp excuse message type kar diya."
            }
            "PEER_BROADCAST" -> {
                val targetIp = getMemory("peer_ip") ?: "192.168.1.100"
                peerSync.broadcastPeerMessage(targetIp, "TELEPATHY", parsed.param, currentPetName)
                "Dost ke device par telepathy alert bhej diya."
            }
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
                if (ok) parsed.reply else "Screen par active chat box nahi mila type karne ke liye!"
            }
            "LAUNCH" -> {
                val pkg = getPackageByName(parsed.param)
                if (pkg != null && isFinancialApp(pkg)) {
                    "Security rules ki wajah se payment apps direct access nahi ho sakte."
                } else if (pkg != null) {
                    launchPackage(pkg)
                    parsed.reply
                } else {
                    "${parsed.param} application phone me nahi mili."
                }
            }
            "YOUTUBE" -> {
                val query = if (parsed.param.isNotBlank()) parsed.param else getMemory("fav_song") ?: "Chill beats"
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

    private fun saveCodeToFile(code: String, filename: String) {
        try {
            val dir = File(Environment.getExternalStorageDirectory(), "MiraScripts")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            file.writeText(code)
        } catch (_: Exception) {}
    }

    private fun declineCallAndSendText(excuse: String) {
        val service = AgentAccessibilityService.instance ?: return
        service.clickByTextOrDescription(listOf("decline", "reject", "dismiss", "cut"))
        service.typeAndSend(excuse.ifBlank { "Thoda busy hoon, baad me call karta hoon." })
    }

    private fun handleLocalOfflineCommands(lower: String): String? {
        val petName = prefs.userPetName
        if (lower.contains("torch") || lower.contains("flashlight")) {
            return if (lower.contains("on") || lower.contains("chalao") || lower.contains("jalao")) {
                toggleFlashlight(true)
                "Torch on kar di hai $petName."
            } else if (lower.contains("off") || lower.contains("band")) {
                toggleFlashlight(false)
                "Torch band kar di."
            } else {
                toggleFlashlight(!isTorchOn)
                if (isTorchOn) "Torch on ho gayi." else "Torch band kar di."
            }
        }

        if (lower.contains("volume") || lower.contains("aawaz")) {
            if (lower.contains("up") || lower.contains("badhao") || lower.contains("tez")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return "Volume badha diya hai."
            }
            if (lower.contains("down") || lower.contains("kam") || lower.contains("slow")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return "Volume kam kar diya."
            }
            if (lower.contains("mute") || lower.contains("chup")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                return "Media mute kar diya gaya hai."
            }
        }

        if (lower == "home" || lower == "home screen" || lower == "bahar aao") {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return "Home screen par switch kar diya."
        }
        if (lower == "back" || lower == "piche jao" || lower == "wapas") {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return "Back step execute kiya."
        }
        if (lower == "recent" || lower == "recent apps") {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            return "Recents screen open kar di."
        }
        if (lower.contains("screenshot")) {
            AgentAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            return "Screenshot capture kar liya hai."
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
        val codePayload: String,
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
                    codePayload = obj.optString("code_payload", ""),
                    rememberKey = obj.optString("remember_key", ""),
                    rememberValue = obj.optString("remember_value", ""),
                    reply = obj.optString("reply", "Haan $petName, boliye kya karna hai?")
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
                ParsedAction("VIDEO_CONTROL", "FORWARD", "", "", "", "Video forward kar diya.")
            }
            lower.contains("rewind") || lower.contains("peeche") -> {
                ParsedAction("VIDEO_CONTROL", "REWIND", "", "", "", "Video rewind kar diya.")
            }
            lower.contains("scroll") || lower.contains("next") -> {
                ParsedAction("APP_CONTROL", "SCROLL_DOWN", "", "", "", "Next scroll kar diya.")
            }
            lower.contains("like") -> {
                ParsedAction("APP_CONTROL", "LIKE", "", "", "", "Like kar diya.")
            }
            else -> {
                ParsedAction("CHAT", "", "", "", "", if (rawReply.isNotBlank()) rawReply else "Haan $petName, main sun rahi hoon!")
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
