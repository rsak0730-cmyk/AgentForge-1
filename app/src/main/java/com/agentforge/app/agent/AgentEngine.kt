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
            service.speakDirectly("$sender: $message")
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

        // 1. FAST LOCAL OFFLINE COMMANDS (0ms Hardware Routing)
        val offlineResult = handleLocalOfflineCommands(lower)
        if (offlineResult != null) {
            chatHistory.add(trimmed to offlineResult)
            return@withContext offlineResult
        }

        // 2. FOCUS TIMER DIRECT ROUTING
        if (lower.contains("focus") || lower.contains("pomodoro") || lower.contains("padhai")) {
            val minutes = Regex("\\d+").find(lower)?.value?.toIntOrNull() ?: 25
            AgentAccessibilityService.startFocusMode(minutes)
            val msg = "Focus mode $minutes minute ke liye shuru kar diya!"
            chatHistory.add(trimmed to msg)
            return@withContext msg
        }

        if (lower.contains("stop focus") || lower.contains("focus band")) {
            AgentAccessibilityService.stopFocusMode()
            val msg = "Focus mode band ho gaya."
            chatHistory.add(trimmed to msg)
            return@withContext msg
        }

        // 3. CAPTURE REAL-TIME SCREEN CONTEXT & UI ELEMENTS
        val screenElementsJson = AgentAccessibilityService.instance?.scrapeScreenElements() ?: "[]"
        val needsVision = lower.contains("dekh") || lower.contains("screen") ||
                lower.contains("ye kya hai") || lower.contains("code") || lower.contains("save code")

        val screenBytes: ByteArray? = if (needsVision) {
            ScreenCaptureService.instance?.captureCurrentScreenJpeg()
        } else {
            null
        }

        val currentAssistantName = prefs.name
        val storedMemories = prefs.agentMemories
        val structuredFacts = memoryVault.getMemorySummary()
        val currentPetName = prefs.userPetName
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)

        val historyContext = chatHistory.takeLast(6).joinToString("\n") {
            "User: ${it.first}\nAssistant: ${it.second}"
        }

        // 4. AUTONOMOUS UI GROUNDING SYSTEM PROMPT
        val systemPrompt = """
            You are "$currentAssistantName", an autonomous Android OS companion.
            You must understand ANY natural conversational command from the user in English, Hindi, or Hinglish, and map it to system execution.
            
            REAL-TIME SCREEN ACCESSIBILITY TREE:
            $screenElementsJson
            
            ACTIONS SUPPORTED:
            1. CLICK_NODE: {"action": "CLICK_NODE", "param": "text or desc of screen element to click"}
            2. CLICK_AT: {"action": "CLICK_AT", "cx_pct": 0.5, "cy_pct": 0.5} (Tap exact percentage coordinate on screen)
            3. LONG_PRESS: {"action": "LONG_PRESS", "cx_pct": 0.5, "cy_pct": 0.5}
            4. SWIPE: {"action": "SWIPE", "param": "UP | DOWN | LEFT | RIGHT"}
            5. TYPE_AND_SEND: {"action": "TYPE_AND_SEND", "param": "text to type into active input and submit"}
            6. LAUNCH: {"action": "LAUNCH", "param": "app name to launch e.g. whatsapp, chrome, termux, instagram"}
            7. SAVE_CODE: {"action": "SAVE_CODE", "code_payload": "clean extracted code from screen"}
            8. YOUTUBE: {"action": "YOUTUBE", "param": "song or video query"}
            9. CHAT: {"action": "CHAT", "reply": "conversational answer"}
            
            RULE: Output ONLY a single raw valid JSON object. No markdown backticks, no explanations.
            
            CLOCK: $currentHour:00
            HISTORY:
            $historyContext
            
            USER SAID:
            "$trimmed"
            
            JSON FORMAT:
            {
              "action": "CLICK_NODE | CLICK_AT | LONG_PRESS | SWIPE | TYPE_AND_SEND | LAUNCH | SAVE_CODE | YOUTUBE | CHAT",
              "param": "",
              "cx_pct": 0.0,
              "cy_pct": 0.0,
              "code_payload": "",
              "reply": "Crisp Hinglish response"
            }
        """.trimIndent()

        val aiRaw = try {
            aiClient.ask(systemPrompt, screenBytes)
        } catch (e: Exception) {
            return@withContext "Network issue aayi $currentPetName, wapas boliye?"
        }

        val parsed = parseJsonResponse(aiRaw, trimmed)
        val service = AgentAccessibilityService.instance
        service?.triggerHeartbeatHaptic()

        // 5. ACTION DISPATCH
        val finalReply = when (parsed.action) {
            "CLICK_NODE" -> {
                val ok = service?.clickAnyElementOnScreen(parsed.param) ?: false
                if (ok) parsed.reply.ifBlank { "Click kar diya." } else {
                    service?.clickAtPercentage(parsed.cxPct, parsed.cyPct)
                    parsed.reply.ifBlank { "${parsed.param} tap kiya." }
                }
            }
            "CLICK_AT" -> {
                service?.clickAtPercentage(parsed.cxPct, parsed.cyPct)
                parsed.reply.ifBlank { "Tap kar diya." }
            }
            "LONG_PRESS" -> {
                service?.longPressAtPercentage(parsed.cxPct, parsed.cyPct)
                parsed.reply.ifBlank { "Long press kiya." }
            }
            "SWIPE" -> {
                service?.swipeDirection(parsed.param)
                parsed.reply.ifBlank { "Scroll kar diya." }
            }
            "TYPE_AND_SEND" -> {
                val ok = service?.typeAndSend(parsed.param) ?: false
                if (ok) parsed.reply.ifBlank { "Type karke send kar diya!" } else "Koi chat box nahi mila."
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
                "Screen se code extract karke /sdcard/MiraScripts me save kar diya hai."
            }
            "YOUTUBE" -> {
                openYouTubeSearch(parsed.param.ifBlank { "lo-fi beats" })
                parsed.reply.ifBlank { "YouTube par chala diya." }
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

        // Fast Natural Gestures
        if (lower.contains("scroll") || lower.contains("next") || lower.contains("aage badhao") || lower.contains("dusra")) {
            service?.scrollForward()
            return "Next scroll kar diya!"
        }
        if (lower.contains("peeche scroll") || lower.contains("previous") || lower.contains("upar karo")) {
            service?.scrollBackward()
            return "Upar scroll kar diya."
        }
        if (lower.contains("like") || lower.contains("heart") || lower.contains("pasand")) {
            service?.likeCurrentContent()
            return "Like kar diya!"
        }

        // Fast Video Navigation
        if (lower.contains("forward") || lower.contains("aage karo")) {
            service?.forwardVideo()
            return "10 second forward kar diya."
        }
        if (lower.contains("rewind") || lower.contains("peeche karo")) {
            service?.rewindVideo()
            return "10 second rewind kar diya."
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

        // System Navigation
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

    private fun toggleFlashlight(status: Boolean) {
        try {
            val id = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(id, status)
            isTorchOn = status
        } catch (_: Exception) {}
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
        val cxPct: Float,
        val cyPct: Float,
        val codePayload: String,
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
                    cxPct = obj.optDouble("cx_pct", 0.5).toFloat(),
                    cyPct = obj.optDouble("cy_pct", 0.5).toFloat(),
                    codePayload = obj.optString("code_payload", ""),
                    reply = obj.optString("reply", "Haan boliye!")
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
            lower.contains("scroll") || lower.contains("next") -> ParsedAction("SWIPE", "UP", 0.5f, 0.5f, "", "Next scroll kar diya!")
            lower.contains("like") -> ParsedAction("CLICK_NODE", "like", 0.5f, 0.5f, "", "Like kar diya!")
            else -> ParsedAction("CHAT", "", 0.5f, 0.5f, "", if (rawReply.isNotBlank()) rawReply else "Sun rahi hoon!")
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
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        }
    }
}
