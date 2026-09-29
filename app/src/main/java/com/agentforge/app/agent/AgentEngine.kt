package com.agentforge.app.agent

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Environment
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.automation.TermuxBridge
import com.agentforge.app.data.AppPrefs
import com.agentforge.app.data.MemoryVault
import com.agentforge.app.network.AgentPeerSync
import com.agentforge.app.service.AgentAccessibilityService
import com.agentforge.app.service.ScreenCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val termuxBridge = TermuxBridge(context)
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

    // Phonetic & STT Misinterpretation Healing Map
    private val phoneticAliasMap = mapOf(
        "tata" to "data",
        "deta" to "data",
        "gata" to "data",
        "bata" to "data",
        "waifai" to "wifi",
        "bhai bhai" to "wifi",
        "wi fi" to "wifi",
        "blue tooth" to "bluetooth",
        "blutut" to "bluetooth",
        "toras" to "torch",
        "taurch" to "torch",
        "taurs" to "torch",
        "flash light" to "flashlight",
        "sukrol" to "scroll",
        "iscol" to "scroll",
        "skrol" to "scroll",
        "lik" to "like",
        "laik" to "like",
        "termaks" to "termux",
        "tarmux" to "termux"
    )

    private fun sanitizePhoneticSpokenText(input: String): String {
        var clean = input.lowercase()
        for ((wrong, correct) in phoneticAliasMap) {
            clean = clean.replace(Regex("\\b$wrong\\b"), correct)
        }
        return clean
    }

    suspend fun execute(userInput: String): String = withContext(Dispatchers.IO) {
        val trimmed = userInput.trim()
        val normalizedInput = sanitizePhoneticSpokenText(trimmed)
        val lower = normalizedInput.lowercase()

        // 1. FAST LOCAL OFFLINE ROUTER (5ms Phonetically Cleaned Execution)
        val offlineResult = handleLocalOfflineCommands(lower)
        if (offlineResult != null) {
            chatHistory.add(trimmed to offlineResult)
            return@withContext offlineResult
        }

        // 2. FOCUS TIMER DIRECT ROUTER
        if (lower.contains("focus") || lower.contains("pomodoro") || lower.contains("padhai")) {
            val minutes = Regex("\\d+").find(lower)?.value?.toIntOrNull() ?: 25
            AgentAccessibilityService.startFocusMode(minutes)
            val msg = "Focus mode $minutes minute ke liye shuru ho gaya."
            chatHistory.add(trimmed to msg)
            return@withContext msg
        }

        if (lower.contains("stop focus") || lower.contains("focus band")) {
            AgentAccessibilityService.stopFocusMode()
            val msg = "Focus mode band kar diya."
            chatHistory.add(trimmed to msg)
            return@withContext msg
        }

        // 3. CAPTURE ACTIVE SCREEN STATE & CACHE
        val screenElementsJson = AgentAccessibilityService.instance?.scrapeScreenElements() ?: "[]"
        memoryVault.cacheScreenText(screenElementsJson)

        val needsVision = lower.contains("dekh") || lower.contains("screen") ||
                lower.contains("ye kya") || lower.contains("code") || lower.contains("padho") ||
                lower.contains("ye") || lower.contains("wo") || lower.contains("color") ||
                lower.contains("photo") || lower.contains("button") || lower.contains("iska") ||
                lower.contains("eta") || lower.contains("ki eta")

        val screenBytes: ByteArray? = if (needsVision) {
            ScreenCaptureService.instance?.captureCurrentScreenJpeg()
        } else {
            null
        }

        val currentAssistantName = prefs.name
        val currentPetName = prefs.userPetName
        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val recentScreenContext = if (lower.contains("pehle") || lower.contains("kya tha") || lower.contains("dekha tha")) {
            memoryVault.getRecentScreenMemory()
        } else {
            ""
        }

        val historyContext = chatHistory.takeLast(6).joinToString("\n") {
            "User: ${it.first}\nAssistant: ${it.second}"
        }

        val systemPrompt = """
            You are "$currentAssistantName", an ultra-autonomous next-gen Android OS companion and phone co-pilot.
            
            AUDIO CLARITY & SPEECH TYPO RECOVERY:
            - The user input comes directly from Android voice speech recognition.
            - It might have minor phonetic misinterpretations or accents (e.g. "tata/deta" for data, "waifai" for wifi, "skrol" for scroll).
            - Intelligently deduce the user's intended phone actions from context.
            
            RULES & INTELLIGENCE:
            1. QUESTION vs ACTION:
               - If user is asking questions ("kaise karein", "how to"), keep "steps": [] empty.
            2. TEMPORAL SCREEN RECALL:
               - Past Screen: $recentScreenContext
            3. MEDIA DOWNLOAD TRIGGER:
               - For saving reels/videos: {"action": "DOWNLOAD_MEDIA", "param": "url_or_screen_link", "mode": "audio | video"}
            4. LIVE WEB SCRAPER:
               - Real-time live info/scores/news: {"action": "WEB_SEARCH", "param": "search query"}
            5. ZERO-SHOT GHOST TAP:
               - Untagged UI targets: {"action": "GHOST_TAP", "cx_pct": 0.5, "cy_pct": 0.5}
            
            SCROLL DIRECTION PHYSICS:
            - View lower content ("niche dikhao", "scroll down") -> SWIPE UP.
            - View upper content ("upar karo", "scroll up") -> SWIPE DOWN.
            
            ACTIVE ON-SCREEN UI TREE:
            $screenElementsJson
            
            SUPPORTED ACTIONS:
            - CLICK_NODE: {"action": "CLICK_NODE", "param": "matching text"}
            - CLICK_AT: {"action": "CLICK_AT", "cx_pct": 0.5, "cy_pct": 0.5}
            - GHOST_TAP: {"action": "GHOST_TAP", "cx_pct": 0.5, "cy_pct": 0.5}
            - SWIPE: {"action": "SWIPE", "param": "UP | DOWN | LEFT | RIGHT"}
            - TYPE_AND_SEND: {"action": "TYPE_AND_SEND", "param": "text to type"}
            - LAUNCH: {"action": "LAUNCH", "param": "app name"}
            - DOWNLOAD_MEDIA: {"action": "DOWNLOAD_MEDIA", "param": "url", "mode": "audio | video"}
            - WEB_SEARCH: {"action": "WEB_SEARCH", "param": "query"}
            - RUN_PYTHON: {"action": "RUN_PYTHON", "code_payload": "clean python script"}
            - APP_OPS: {"action": "APP_OPS", "pkg": "package.name", "op": "RECORD_AUDIO | CAMERA | POST_NOTIFICATION", "mode": "allow | ignore"}
            - TERMUX_EXEC: {"action": "TERMUX_EXEC", "param": "script_name.sh"}
            - YOUTUBE: {"action": "YOUTUBE", "param": "query"}
            - VOLUME: {"action": "VOLUME", "param": "UP | DOWN | MUTE"}
            - GLOBAL: {"action": "GLOBAL", "param": "HOME | BACK | RECENTS | SCREENSHOT"}
            
            OUTPUT RULES (RAW JSON ONLY, STRICTLY NO MARKDOWN BACKTICKS):
            {
              "reply": "Warm, crisp Hinglish conversation response",
              "steps": [
                {"action": "ACTION_NAME", "param": "", "cx_pct": 0.0, "cy_pct": 0.0, "pkg": "", "op": "", "mode": "", "code_payload": ""}
              ]
            }
            
            USER INPUT: "$normalizedInput" (Raw: "$trimmed")
            CLOCK: $currentHour:00 hrs
            HISTORY:
            $historyContext
        """.trimIndent()

        val aiRaw = try {
            aiClient.ask(systemPrompt, screenBytes)
        } catch (e: Exception) {
            return@withContext "Network issue aayi $currentPetName, wapas boliye na?"
        }

        val parsed = parseJsonResponse(aiRaw, normalizedInput)
        val service = AgentAccessibilityService.instance
        service?.triggerHeartbeatHaptic()

        // 4. INTELLIGENT SEQUENTIAL EXECUTION LOOP
        for (step in parsed.steps) {
            if (step.action == "LAUNCH") {
                val pkg = getPackageByName(step.param)
                if (pkg != null && !isFinancialApp(pkg)) {
                    launchPackage(pkg)
                    waitForAppRender(pkg, maxWaitMs = 4500)
                }
            } else {
                executeIndividualStep(step, service)
                if (parsed.steps.size > 1) {
                    delay(300)
                }
            }
        }

        chatHistory.add(trimmed to parsed.reply)
        if (chatHistory.size > 12) chatHistory.removeAt(0)

        parsed.reply
    }

    private suspend fun waitForAppRender(targetPackage: String, maxWaitMs: Long = 4500) {
        val startTime = System.currentTimeMillis()
        val service = AgentAccessibilityService.instance

        delay(350)
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (service != null && service.isAppRendered(targetPackage)) {
                delay(200)
                return
            }
            delay(150)
        }
    }

    private fun executeIndividualStep(step: ActionStep, service: AgentAccessibilityService?) {
        when (step.action) {
            "CLICK_NODE" -> {
                val ok = service?.clickAnyElementOnScreen(step.param) ?: false
                if (!ok && (step.cxPct > 0f || step.cyPct > 0f)) {
                    if (service != null && !service.clickAtPercentage(step.cxPct, step.cyPct) && shizuku.isReady()) {
                        val realW = service.resources.displayMetrics.widthPixels
                        val realH = service.resources.displayMetrics.heightPixels
                        shizuku.inputTap(step.cxPct * realW, step.cyPct * realH)
                    }
                }
            }
            "CLICK_AT", "GHOST_TAP" -> {
                val ok = service?.clickAtPercentage(step.cxPct, step.cyPct) ?: false
                if (!ok && shizuku.isReady() && service != null) {
                    val realW = service.resources.displayMetrics.widthPixels
                    val realH = service.resources.displayMetrics.heightPixels
                    shizuku.inputTap(step.cxPct * realW, step.cyPct * realH)
                }
            }
            "SWIPE" -> service?.swipeDirection(step.param)
            "TYPE_AND_SEND" -> {
                val ok = service?.typeAndSend(step.param) ?: false
                if (!ok && shizuku.isReady()) {
                    shizuku.inputText(step.param)
                    shizuku.run("input keyevent 66")
                }
            }
            "DOWNLOAD_MEDIA" -> {
                val isAudio = step.mode.equals("audio", ignoreCase = true)
                termuxBridge.downloadMedia(step.param, isAudio)
                service?.showIsland("⬇️ Downloading Media...")
            }
            "WEB_SEARCH" -> {
                termuxBridge.triggerWebSearch(step.param)
                service?.showIsland("🌐 Searching Web...")
            }
            "RUN_PYTHON" -> {
                if (step.codePayload.isNotBlank()) {
                    termuxBridge.runDynamicPython(step.codePayload)
                    service?.showIsland("🐍 Script Running...")
                }
            }
            "APP_OPS" -> {
                if (shizuku.isReady() && step.pkg.isNotBlank() && step.op.isNotBlank()) {
                    shizuku.setAppOp(step.pkg, step.op, step.mode.equals("allow", true))
                }
            }
            "TERMUX_EXEC" -> {
                termuxBridge.executeScript(step.param)
            }
            "YOUTUBE" -> openYouTubeSearch(step.param.ifBlank { "lo-fi" })
            "VOLUME" -> {
                when (step.param.uppercase()) {
                    "UP" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "DOWN" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "MUTE" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                }
            }
            "GLOBAL" -> {
                when (step.param.uppercase()) {
                    "HOME" -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    "BACK" -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    "RECENTS" -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    "SCREENSHOT" -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                }
            }
        }
    }

    private fun handleLocalOfflineCommands(lower: String): String? {
        val petName = prefs.userPetName
        val service = AgentAccessibilityService.instance

        if (lower.contains("kaise") || lower.contains("kya hai") || lower.contains("how to") || lower.contains("explain")) {
            return null
        }

        // Shizuku Direct Fast Toggles with Phonetic Tolerance
        if (lower.contains("data") || lower.contains("internet")) {
            if (lower.contains("on") || lower.contains("chalu")) {
                shizuku.setMobileData(true)
                return "Mobile data on kar diya."
            } else if (lower.contains("off") || lower.contains("band")) {
                shizuku.setMobileData(false)
                return "Mobile data band kar diya."
            }
        }

        if (lower.contains("wifi")) {
            if (lower.contains("on") || lower.contains("chalu")) {
                shizuku.setWifi(true)
                return "Wi-Fi chalu kar diya."
            } else if (lower.contains("off") || lower.contains("band")) {
                shizuku.setWifi(false)
                return "Wi-Fi band kar diya."
            }
        }

        if (lower.contains("bluetooth")) {
            if (lower.contains("on") || lower.contains("chalu")) {
                shizuku.setBluetooth(true)
                return "Bluetooth on kar diya."
            } else if (lower.contains("off") || lower.contains("band")) {
                shizuku.setBluetooth(false)
                return "Bluetooth band kar diya."
            }
        }

        if (lower.contains("battery saver") || lower.contains("power saver")) {
            if (lower.contains("on") || lower.contains("lagao")) {
                shizuku.setPowerSaver(true)
                return "Power saving mode on kar diya."
            } else if (lower.contains("off") || lower.contains("hatao")) {
                shizuku.setPowerSaver(false)
                return "Power saving mode band kar diya."
            }
        }

        if (lower.contains("lock karo") || lower.contains("phone band karo")) {
            shizuku.lockScreen()
            return "Phone lock kar diya."
        }

        if (lower.contains("brightness") || lower.contains("roshni")) {
            if (lower.contains("full") || lower.contains("tez") || lower.contains("badhao")) {
                shizuku.setBrightness(240)
                return "Brightness badha di."
            } else if (lower.contains("kam") || lower.contains("low")) {
                shizuku.setBrightness(40)
                return "Brightness kam kar di."
            }
        }

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
        if (lower.contains("forward") || lower.contains("aage karo")) {
            service?.forwardVideo()
            return "10 second forward kar diya."
        }
        if (lower.contains("rewind") || lower.contains("peeche karo")) {
            service?.rewindVideo()
            return "10 second rewind kar diya."
        }
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

        return null
    }

    private fun toggleFlashlight(status: Boolean) {
        try {
            val id = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(id, status)
            isTorchOn = status
        } catch (_: Exception) {}
    }

    private data class ActionStep(
        val action: String,
        val param: String = "",
        val cxPct: Float = 0f,
        val cyPct: Float = 0f,
        val pkg: String = "",
        val op: String = "",
        val mode: String = "",
        val codePayload: String = ""
    )

    private data class ParsedPlan(
        val reply: String,
        val steps: List<ActionStep>
    )

    private fun parseJsonResponse(raw: String, originalInput: String): ParsedPlan {
        return try {
            val jsonStart = raw.indexOf("{")
            val jsonEnd = raw.lastIndexOf("}")
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val jsonStr = raw.substring(jsonStart, jsonEnd + 1)
                val obj = JSONObject(jsonStr)
                val reply = obj.optString("reply", "Haan boliye!")
                val stepsList = mutableListOf<ActionStep>()

                val stepsArray = obj.optJSONArray("steps")
                if (stepsArray != null) {
                    for (i in 0 until stepsArray.length()) {
                        val s = stepsArray.getJSONObject(i)
                        stepsList.add(
                            ActionStep(
                                action = s.optString("action", "CHAT").uppercase(),
                                param = s.optString("param", ""),
                                cxPct = s.optDouble("cx_pct", 0.0).toFloat(),
                                cyPct = s.optDouble("cy_pct", 0.0).toFloat(),
                                pkg = s.optString("pkg", ""),
                                op = s.optString("op", ""),
                                mode = s.optString("mode", ""),
                                codePayload = s.optString("code_payload", "")
                            )
                        )
                    }
                }
                ParsedPlan(reply, stepsList)
            } else {
                fallbackDeducer(raw, originalInput)
            }
        } catch (_: Exception) {
            fallbackDeducer(raw, originalInput)
        }
    }

    private fun fallbackDeducer(rawReply: String, input: String): ParsedPlan {
        val lower = input.lowercase()
        val steps = mutableListOf<ActionStep>()
        if (!lower.contains("kaise") && !lower.contains("kya") && !lower.contains("how") && !lower.contains("explain")) {
            if (lower.contains("scroll") || lower.contains("next")) {
                steps.add(ActionStep("SWIPE", "UP"))
            } else if (lower.contains("like")) {
                steps.add(ActionStep("CLICK_NODE", "like"))
            }
        }
        return ParsedPlan(if (rawReply.isNotBlank()) rawReply else "Sun rahi hoon!", steps)
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
