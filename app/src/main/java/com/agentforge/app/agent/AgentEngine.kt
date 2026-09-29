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
            service.showIsland("📡 $sender:$message")
            service.speakDirectly("$sender:$message")
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

        // 1. FAST LOCAL OFFLINE ROUTER (Guaranteed 0ms execution without waiting for AI)
        val offlineResult = handleLocalOfflineCommands(lower)
        if (offlineResult != null) {
            chatHistory.add(trimmed to offlineResult)
            return@withContext offlineResult
        }

        // 2. FOCUS TIMER ROUTER
        if (lower.contains("focus") || lower.contains("pomodoro") || lower.contains("padhai")) {
            val minutes = Regex("\\d+").find(lower)?.value?.toIntOrNull() ?: 25
            AgentAccessibilityService.startFocusMode(minutes)
            val msg = "Focus mode $minutes minute ke liye start ho gaya."
            chatHistory.add(trimmed to msg)
            return@withContext msg
        }

        if (lower.contains("stop focus") || lower.contains("focus band")) {
            AgentAccessibilityService.stopFocusMode()
            val msg = "Focus mode band ho gaya."
            chatHistory.add(trimmed to msg)
            return@withContext msg
        }

        // 3. MULTI-MODAL SCREEN VISION CHECK
        val needsVision = lower.contains("dekh") || lower.contains("screen") ||
                lower.contains("ye kya hai") || lower.contains("analyze") ||
                lower.contains("code") || lower.contains("error") || lower.contains("save code")

        val screenBytes: ByteArray? = if (needsVision) {
            ScreenCaptureService.instance?.captureCurrentScreenJpeg()
        } else {
            null
        }

        val isNearEar = AgentAccessibilityService.isNearEar
        val currentAssistantName = prefs.name
        val storedMemories = prefs.agentMemories
        val structuredFacts = memoryVault.getMemorySummary()
        val currentPetName = prefs.userPetName
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)

        val historyContext = chatHistory.takeLast(6).joinToString("\n") {
            "User: ${it.first}\nAssistant:${it.second}"
        }

        val systemPrompt = """
            You are "$currentAssistantName", an Android automation and smart companion agent.
            
            CRITICAL RULE:
            Always return a valid raw JSON object. Do not include markdown blocks (```json) or conversational text outside the JSON.
            
            ACTIONS SUPPORTED:
            - APP_CONTROL: (param: "SCROLL_DOWN" | "SCROLL_UP" | "LIKE" | "COMMENT" | "SHARE" | element text to click)
            - VIDEO_CONTROL: (param: "FORWARD" | "REWIND" | "PLAY_PAUSE")
            - TYPE_AND_SEND: (param: text to type and submit immediately)
            - LAUNCH: (param: app name e.g. "whatsapp", "instagram", "youtube", "settings", "termux")
            - SAVE_CODE: (code_payload: clean python/js code extracted from screen, param: file name)
            - DECLINE_AND_TEXT: (param: excuse message text)
            - YOUTUBE: (param: video query or music title)
            - WEB_SEARCH: (param: query)
            - CHAT: (conversational reply)
            
            USER INPUT: "$trimmed"
            CLOCK: $currentHour:00
            
            JSON FORMAT:
            {
              "action": "APP_CONTROL | VIDEO_CONTROL | TYPE_AND_SEND | LAUNCH | SAVE_CODE | DECLINE_AND_TEXT | YOUTUBE | WEB_SEARCH | CHAT",
              "param": "",
              "code_payload": "",
              "reply": "Short natural Hinglish response"
            }
        """.trimIndent()

        val aiRaw = try {
            aiClient.ask(systemPrompt, screenBytes)
        } catch (e: Exception) {
            return@withContext "Network issue aayi $currentPetName, wapas boliye?"
        }

        val parsed = parseJsonResponse(aiRaw, trimmed, currentPetName)
        AgentAccessibilityService.instance?.triggerHeartbeatHaptic()

        val finalReply = when (parsed.action) {
            "APP_CONTROL" -> {
                handleUniversalAppAction(parsed.param)
                parsed.reply.ifBlank { "Kar diya!" }
            }
            "VIDEO_CONTROL" -> {
                handleVideoControl(parsed.param)
                parsed.reply.ifBlank { "Video update kiya." }
            }
            "TYPE_AND_SEND" -> {
                val ok = AgentAccessibilityService.instance?.typeAndSend(parsed.param) ?: false
                if (ok) parsed.reply.ifBlank { "Bhej diya!" } else "Screen par active chat box nahi mila."
            }
            "LAUNCH" -> {
                val pkg = getPackageByName(parsed.param)
                if (pkg != null && isFinancialApp(pkg)) {
                    "Security rules ki wajah se banking apps direct control nahi ki ja sakti."
                } else if (pkg != null) {
                    launchPackage(pkg)
                    parsed.reply.ifBlank { "${parsed.param} open kar diya." }
                } else {
                    "${parsed.param} app phone me nahi mili."
                }
            }
            "SAVE_CODE" -> {
                val code = parsed.codePayload.ifBlank { "print('No code extracted')" }
                saveCodeToFile(code, "script_${System.currentTimeMillis() % 1000}.py")
                "Screen ka code extract karke /sdcard/MiraScripts me save kar diya hai."
            }
            "DECLINE_AND_TEXT" -> {
                declineCallAndSendText(parsed.param)
                "Call reject karke excuse message bhej diya."
            }
            "YOUTUBE" -> {
                val query = if (parsed.param.isNotBlank()) parsed.param else "Coding lo-fi beats"
                openYouTubeSearch(query)
                parsed.reply.ifBlank { "YouTube par chala diya." }
            }
            "WEB_SEARCH" -> {
                openGoogleSearch(parsed.param)
                parsed.reply.ifBlank { "Search kar diya." }
            }
            else -> parsed.reply
        }

        chatHistory.add(trimmed to finalReply)
        if (chatHistory.size > 12) chatHistory.removeAt(0)

        finalReply
    }

    private fun handleLocalOfflineCommands(lower: String): String? {
        val petName = prefs.userPetName
        val service = AgentAccessibilityService.instance

        // Reels & Scrolling
        if (lower.contains("scroll") || lower.contains("next") || lower.contains("aage badhao") || lower.contains("dusra dikhao")) {
            service?.scrollForward()
            return "Next scroll kar diya!"
        }
        if (lower.contains("peeche scroll") || lower.contains("previous reel") || lower.contains("wapas upar")) {
            service?.scrollBackward()
            return "Upar scroll kar diya."
        }
        if (lower.contains("like") || lower.contains("heart") || lower.contains("pasand")) {
            service?.likeCurrentContent()
            return "Like kar diya!"
        }

        // Fast Video Controls
        if (lower.contains("forward") || lower.contains("aage karo")) {
            service?.forwardVideo()
            return "10 second forward kar diya."
        }
        if (lower.contains("rewind") || lower.contains("peeche karo")) {
            service?.rewindVideo()
            return "10 second rewind kar diya."
        }

        // Direct Text Send
        if (lower.startsWith("send ") || lower.startsWith("type ") || lower.startsWith("bhejo ")) {
            val content = lower.replaceFirst("send ", "").replaceFirst("type ", "").replaceFirst("bhejo ", "")
            val ok = service?.typeAndSend(content) ?: false
            return if (ok) "Type karke send kar diya!" else "Koi active chat field nahi mila."
        }

        // Hardware Controls
        if (lower.contains("torch") || lower.contains("flashlight")) {
            return if (lower.contains("on") || lower.contains("jalao")) {
                toggleFlashlight(true)
                "Torch on kar di $petName."
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
                return "Volume badha diya."
            }
            if (lower.contains("down") || lower.contains("kam") || lower.contains("slow")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return "Volume kam kar diya."
            }
            if (lower.contains("mute") || lower.contains("chup")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                return "Mute kar diya."
            }
        }

        // Navigation
        if (lower == "home" || lower == "home screen" || lower == "bahar aao") {
            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return "Home screen par aa gaye."
        }
        if (lower == "back" || lower == "piche jao" || lower == "wapas") {
            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return "Back kar diya."
        }
        if (lower == "recent" || lower == "recent apps") {
            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            return "Recent apps open kar di."
        }
        if (lower.contains("screenshot")) {
            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            return "Screenshot capture kar liya."
        }

        return null
    }

    private fun handleUniversalAppAction(target: String) {
        val service = AgentAccessibilityService.instance ?: return
        when (target.uppercase()) {
            "SCROLL_DOWN", "NEXT" -> service.scrollForward()
            "SCROLL_UP", "PREVIOUS" -> service.scrollBackward()
            "LIKE" -> service.likeCurrentContent()
            "SHARE" -> service.clickByTextOrDescription(listOf("share", "send", "bhejo"))
            "COMMENT" -> service.clickByTextOrDescription(listOf("comment", "reply"))
            else -> service.clickAnyElementOnScreen(target)
        }
    }

    private fun handleVideoControl(command: String) {
        val service = AgentAccessibilityService.instance ?: return
        when (command.uppercase()) {
            "FORWARD" -> service.forwardVideo()
            "REWIND" -> service.rewindVideo()
            "PLAY_PAUSE", "PLAY", "PAUSE" -> service.clickByTextOrDescription(listOf("play", "pause", "video"))
        }
    }

    private fun toggleFlashlight(status: Boolean) {
        try {
            val id = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(id, status)
            isTorchOn = status
        } catch (_: Exception) {}
    }

    private fun declineCallAndSendText(excuse: String) {
        val service = AgentAccessibilityService.instance ?: return
        service.clickByTextOrDescription(listOf("decline", "reject", "dismiss", "cut"))
        service.typeAndSend(excuse.ifBlank { "Thoda busy hoon, baad me call karta hoon." })
    }

    private fun saveCodeToFile(code: String, filename: String) {
        try {
            val dir = File(Environment.getExternalStorageDirectory(), "MiraScripts")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            file.writeText(code)
        } catch (_: Exception) {}
    }

    private data class ParsedAction(
        val action: String,
        val param: String,
        val codePayload: String,
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
                    reply = obj.optString("reply", "Haan bolo!")
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
            lower.contains("scroll") || lower.contains("next") -> ParsedAction("APP_CONTROL", "SCROLL_DOWN", "", "Next reel laga di!")
            lower.contains("like") -> ParsedAction("APP_CONTROL", "LIKE", "", "Like kar diya!")
            lower.contains("forward") -> ParsedAction("VIDEO_CONTROL", "FORWARD", "", "Forward kar diya.")
            lower.contains("rewind") -> ParsedAction("VIDEO_CONTROL", "REWIND", "", "Rewind kar diya.")
            else -> ParsedAction("CHAT", "", "", if (rawReply.isNotBlank()) rawReply else "Sun rahi hoon, boliye!")
        }
    }

    private fun isFinancialApp(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return blockedFinancialPackages.any { lower.contains(it) } ||
                lower.contains("bank") || lower.contains("upi") || lower.contains("wallet")
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
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("[https://www.youtube.com/results?search_query=$encoded](https://www.youtube.com/results?search_query=$encoded)")).apply {
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
}
