package com.agentforge.app.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
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
        val trimmedInput = userInput.trim()
        val currentAssistantName = prefs.name
        val storedMemories = prefs.agentMemories

        val historyContext = chatHistory.takeLast(8).joinToString("\n") {
            "User: ${it.first}\n$currentAssistantName: ${it.second}"
        }

        val systemPrompt = """
            Aap ek omnipotent, obedient Android OS companion aur sweet girl assistant ho jiska naam "$currentAssistantName" hai.
            User ka naam Manish hai.
            
            USER KA MEMORY VAULT:
            $storedMemories
            
            RECENT CHAT HISTORY:
            $historyContext
            
            CURRENT USER INPUT:
            "$trimmedInput"
            
            Aap phone me chal rahi KISI BHI APP (YouTube, Instagram, Browser, Shopping, Files, WhatsApp, etc.) ke har UI element, button aur video ko control kar sakti ho.
            
            ACTIONS SUPPORTED:
            1. VIDEO_CONTROL:
               param: "FORWARD" (10s aage) | "REWIND" (10s peeche) | "PLAY_PAUSE" | "FULL_SCREEN"
            2. APP_ACTION:
               param: "SCROLL_DOWN" | "SCROLL_UP" | "LIKE" | "SHARE" | "COMMENT" | "SUBSCRIBE" | "SAVE" | or EXACT ANY BUTTON TEXT/DESCRIPTION on the screen (e.g. "Buy Now", "Profile", "Search", "Next", "Close", "Send").
            3. TYPE_TEXT:
               param: "Text to type into the current focused input field"
            4. LAUNCH: Open any installed non-banking app.
            5. YOUTUBE: Play/search music or video.
            6. WEB_SEARCH: Search Google.
            7. ALARM: Set reminder or alarm.
            8. REMEMBER: Store personal details.
            9. CHAT: Empathetic girl response.
            
            OUTPUT RULES (RAW JSON ONLY, NO MARKDOWN):
            {
              "thought": "Deep context reasoning",
              "action": "VIDEO_CONTROL | APP_ACTION | TYPE_TEXT | LAUNCH | YOUTUBE | WEB_SEARCH | ALARM | REMEMBER | CHAT",
              "param": "Resolved action or target button text/description",
              "remember_key": "Detail key if any",
              "remember_value": "Detail value if any",
              "reply": "Sweet natural response"
            }
        """.trimIndent()

        val aiRawResponse = try {
            aiClient.ask(systemPrompt)
        } catch (e: Exception) {
            return@withContext "Network issue ki wajah se sun nahi paayi: ${e.message}"
        }

        val parsed = parseJsonResponse(aiRawResponse, trimmedInput)

        if (parsed.rememberKey.isNotBlank() && parsed.rememberValue.isNotBlank()) {
            saveMemory(parsed.rememberKey, parsed.rememberValue)
        }

        val finalReply = when (parsed.action) {
            "VIDEO_CONTROL" -> {
                handleVideoControl(parsed.param)
                parsed.reply
            }
            "APP_ACTION" -> {
                handleUniversalAppAction(parsed.param)
                parsed.reply
            }
            "TYPE_TEXT" -> {
                val ok = AgentAccessibilityService.instance?.typeTextIntoFocusedOrById(null, parsed.param) ?: false
                if (ok) "Type kar diya Manish." else "Input field par focus nahi tha."
            }
            "LAUNCH" -> {
                val pkg = getPackageByName(parsed.param)
                if (pkg != null && isFinancialApp(pkg)) {
                    "Security reasons ke chalte main banking ya payment apps access nahi kar sakti Manish."
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

        chatHistory.add(trimmedInput to finalReply)
        if (chatHistory.size > 16) chatHistory.removeAt(0)

        finalReply
    }

    private fun handleVideoControl(command: String) {
        val service = AgentAccessibilityService.instance ?: return
        when (command.uppercase()) {
            "FORWARD" -> service.forwardVideo()
            "REWIND" -> service.rewindVideo()
            "PLAY_PAUSE", "PLAY", "PAUSE" -> service.clickByTextOrDescription(listOf("play", "pause", "video player", "touch to play"))
            "FULL_SCREEN" -> service.clickByTextOrDescription(listOf("full screen", "fullscreen", "enter full screen", "maximize"))
        }
    }

    private fun handleUniversalAppAction(target: String) {
        val service = AgentAccessibilityService.instance ?: return
        when (target.uppercase()) {
            "SCROLL_DOWN", "NEXT" -> service.scrollForward()
            "SCROLL_UP", "PREVIOUS" -> service.scrollBackward()
            "LIKE" -> service.clickByTextOrDescription(listOf("like", "heart", "thumbs up", "pasand"))
            "SUBSCRIBE" -> service.clickByTextOrDescription(listOf("subscribe", "subscribed", "ghanti", "bell"))
            "SHARE" -> service.clickByTextOrDescription(listOf("share", "send", "bhejo"))
            "COMMENT" -> service.clickByTextOrDescription(listOf("comment", "tippani", "reply", "add a comment"))
            "SAVE" -> service.clickByTextOrDescription(listOf("save", "bookmark", "collection", "save to playlist"))
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
            lower.contains("forward") || lower.contains("aage karo") || lower.contains("skip") -> {
                ParsedAction("VIDEO_CONTROL", "FORWARD", "", "", "Video 10 second aage kar diya!")
            }
            lower.contains("rewind") || lower.contains("peeche") || lower.contains("back karo") -> {
                ParsedAction("VIDEO_CONTROL", "REWIND", "", "", "Video 10 second peeche kar diya!")
            }
            lower.contains("subscribe") -> {
                ParsedAction("APP_ACTION", "SUBSCRIBE", "", "", "Channel subscribe kar diya!")
            }
            lower.contains("scroll") || lower.contains("next") -> {
                ParsedAction("APP_ACTION", "SCROLL_DOWN", "", "", "Scroll kar diya!")
            }
            lower.contains("like") -> {
                ParsedAction("APP_ACTION", "LIKE", "", "", "Like kar diya!")
            }
            lower.contains("youtube") || lower.contains("gaana") -> {
                val query = if (lower.contains("fav")) getMemory("fav_song") ?: "Arz kya hai" else "relaxing songs"
                ParsedAction("YOUTUBE", query, "", "", "YouTube par gaana chala diya!")
            }
            else -> {
                ParsedAction("CHAT", "", "", "", if (rawReply.isNotBlank()) rawReply else "Haan Manish, boliye main kya karoon?")
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
