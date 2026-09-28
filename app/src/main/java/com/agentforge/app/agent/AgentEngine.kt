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

    // Strictly blocked sensitive packages
    private val blockedFinancialPackages = listOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "net.one97.paytm",                        // Paytm
        "com.phonepe.app",                        // PhonePe
        "in.org.npci.upiapp",                     // BHIM
        "com.sbi.lotusintouch",                   // YONO SBI
        "com.msf.kbank.mobile",                   // Kotak
        "com.axis.mobile",                        // Axis Mobile
        "com.icicibank.mobile",                   // iMobile
        "com.hdfcbank.android"                    // HDFC MobileBanking
    )

    suspend fun execute(userInput: String): String = withContext(Dispatchers.IO) {
        val trimmedInput = userInput.trim()
        val currentAssistantName = prefs.name
        val storedMemories = prefs.agentMemories

        val historyContext = chatHistory.takeLast(8).joinToString("\n") {
            "User: ${it.first}\n$currentAssistantName: ${it.second}"
        }

        val systemPrompt = """
            Aap ek real human girl companion aur warm, sweet personal assistant ho jiska naam "$currentAssistantName" hai.
            User ka naam Manish hai.
            
            Aapka behavior:
            - Ek caring, sweet, lively female friend ki tarah baat karna (Hinglish/Bengali/Hindi).
            - Kabhi kabhar Manish ko uski purani baatein natural baatcheet ke dauran recall karwana (jaise: "Aapne bataya tha aapko ye pasand hai...").
            
            USER KA MEMORY VAULT:
            $storedMemories
            
            RECENT CHAT HISTORY:
            $historyContext
            
            CURRENT USER INPUT:
            "$trimmedInput"
            
            ACTIONS SUPPORTED:
            1. APP_CONTROL: (Like, Share, Comment, Save, Next/Scroll Down, Previous/Scroll Up, Play, Pause, Tap on any option).
               param: "LIKE" | "SHARE" | "COMMENT" | "SAVE" | "SCROLL_DOWN" | "SCROLL_UP" | "PLAY_PAUSE" | custom button text
            2. LAUNCH: Open any installed non-banking app.
            3. YOUTUBE: Play music, search mood-based videos.
            4. WEB_SEARCH: Search Google.
            5. ALARM: Set reminder or alarm.
            6. REMEMBER: Store personal details.
            7. CHAT: Warm female conversational reply.
            
            OUTPUT RULES (RAW JSON ONLY, NO MARKDOWN):
            {
              "thought": "Understanding user intent",
              "action": "APP_CONTROL | LAUNCH | YOUTUBE | WEB_SEARCH | ALARM | REMEMBER | CHAT",
              "param": "Target parameter or button name",
              "remember_key": "Fact key if user shared personal detail",
              "remember_value": "The fact to remember",
              "reply": "Sweet, expressive, natural girl reply for Manish"
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
            "APP_CONTROL" -> {
                handleAppInteraction(parsed.param)
                parsed.reply
            }
            "LAUNCH" -> {
                val pkg = getPackageByName(parsed.param)
                if (pkg != null && isFinancialApp(pkg)) {
                    "Security reasons ke chalte main banking ya payment apps open nahi kar sakti Manish."
                } else if (pkg != null) {
                    launchPackage(pkg)
                    parsed.reply
                } else {
                    "${parsed.param} app phone me nahi mili."
                }
            }
            "YOUTUBE" -> {
                val query = if (parsed.param.isNotBlank()) parsed.param else getMemory("fav_song") ?: "sweet melodies"
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

    private fun handleAppInteraction(actionType: String) {
        val service = AgentAccessibilityService.instance ?: return
        when (actionType.uppercase()) {
            "SCROLL_DOWN", "NEXT" -> service.scrollForward()
            "SCROLL_UP", "PREVIOUS" -> service.scrollBackward()
            "LIKE" -> service.clickByTextOrDescription(listOf("like", "heart", "pasand", "thumbs up"))
            "SHARE" -> service.clickByTextOrDescription(listOf("share", "send", "bhejo"))
            "COMMENT" -> service.clickByTextOrDescription(listOf("comment", "tippani", "reply"))
            "SAVE" -> service.clickByTextOrDescription(listOf("save", "bookmark", "collection"))
            "PLAY_PAUSE", "PLAY", "PAUSE" -> service.clickByTextOrDescription(listOf("play", "pause", "video"))
            else -> service.clickByTextOrDescription(listOf(actionType.lowercase()))
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
                    reply = obj.optString("reply", "Haan Manish, sun rahi hoon!")
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
            lower.contains("scroll") || lower.contains("next") || lower.contains("aage karo") -> {
                ParsedAction("APP_CONTROL", "SCROLL_DOWN", "", "", "Next reel scroll kar diya!")
            }
            lower.contains("like") || lower.contains("pasand") -> {
                ParsedAction("APP_CONTROL", "LIKE", "", "", "Like kar diya!")
            }
            lower.contains("share") -> {
                ParsedAction("APP_CONTROL", "SHARE", "", "", "Share menu open kar diya.")
            }
            lower.contains("youtube") || lower.contains("gaana") -> {
                val query = if (lower.contains("fav")) getMemory("fav_song") ?: "Arz kya hai" else "relaxing songs"
                ParsedAction("YOUTUBE", query, "", "", "YouTube par gaana chala diya hai!")
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
