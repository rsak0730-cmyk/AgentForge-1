package com.agentforge.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
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

    suspend fun execute(userInput: String): String = withContext(Dispatchers.IO) {
        val trimmedInput = userInput.trim()
        val currentAssistantName = prefs.name
        val currentWakeWord = prefs.wakeWord

        val storedMemories = prefs.agentMemories
        val historyContext = chatHistory.takeLast(6).joinToString("\n") { 
            "User: ${it.first}\n$currentAssistantName: ${it.second}" 
        }

        val systemPrompt = """
            Aap ek fast, intelligent Android OS companion ho.
            Aapka naam "$currentAssistantName" hai. (User agar "$currentAssistantName", Jarvis ya Mira bole, politely accept karo).
            Wake-word: "$currentWakeWord"
            User ka naam Manish hai.
            
            Permanently Stored Facts/Memories:
            $storedMemories
            
            Recent Chat History:
            $historyContext
            
            User Input: "$trimmedInput"
            
            CRITICAL INSTRUCTION:
            - Agar user bole "mera fav gaana" aur memory me "Arz kya hai" ya koi song save ho, toh YouTube search me wahi song parameter me bhejo.
            - Agar user bole "achha sa gaana jisse fresh lage" ya koi mood bataye, toh parameter me relevant search terms (jaise "fresh feel good songs hindi") generate karo.
            - Agar user koi fact bataye ya kahe "ise yaad rakhna", toh action "REMEMBER" select karo.
            - KABHI BHI bina soche "Haan boliye, main ready hoon" mat bolna.
            
            RESPONSE FORMAT (Strictly ONLY valid JSON, no markdown backticks):
            {
              "thought": "Reasoning",
              "action": "YOUTUBE | LAUNCH | REMEMBER | SHELL | CHAT",
              "param": "Target parameter ya search query (e.g. Arz kya hai / fresh songs / app name)",
              "remember_key": "Fact key agar yaad rakhna ho warna empty",
              "remember_value": "Fact value agar yaad rakhna ho warna empty",
              "reply": "Warm natural Hinglish response for Manish"
            }
        """.trimIndent()

        val aiRawResponse = try {
            aiClient.ask(systemPrompt)
        } catch (e: Exception) {
            "Network error: ${e.message}"
        }

        val parsed = parseJsonResponse(aiRawResponse, trimmedInput)

        // Save memories permanently
        if (parsed.rememberKey.isNotBlank() && parsed.rememberValue.isNotBlank()) {
            saveMemory(parsed.rememberKey, parsed.rememberValue)
        }

        val finalReply = when (parsed.action) {
            "YOUTUBE" -> {
                val query = when {
                    parsed.param.isNotBlank() -> parsed.param
                    trimmedInput.contains("fav", ignoreCase = true) -> getMemory("fav_song") ?: "Arz kya hai"
                    else -> "fresh energetic songs"
                }
                val ok = openYouTubeSearch(query)
                if (ok) parsed.reply else "YouTube open nahi ho paya."
            }
            "LAUNCH" -> {
                val ok = launchAppByName(parsed.param)
                if (ok) parsed.reply else "${parsed.param} app nahi mila."
            }
            "SHELL" -> {
                if (shizuku.hasPermission()) {
                    shizuku.executeCommand(parsed.param)
                    parsed.reply
                } else {
                    "${parsed.reply} (Shizuku offline hai)"
                }
            }
            else -> parsed.reply
        }

        chatHistory.add(trimmedInput to finalReply)
        if (chatHistory.size > 12) chatHistory.removeAt(0)

        finalReply
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
                    reply = obj.optString("reply", "Theek hai Manish, process kar rahi hoon.")
                )
            } else {
                // If AI returned raw text instead of JSON
                fallbackActionDeducer(raw, originalInput)
            }
        } catch (_: Exception) {
            fallbackActionDeducer(raw, originalInput)
        }
    }

    private fun fallbackActionDeducer(rawReply: String, input: String): ParsedAction {
        val lower = input.lowercase()
        return when {
            lower.contains("youtube") && (lower.contains("gaana") || lower.contains("song") || lower.contains("play") || lower.contains("chala")) -> {
                val query = if (lower.contains("fav")) getMemory("fav_song") ?: "Arz kya hai" else "fresh energetic songs"
                ParsedAction("YOUTUBE", query, "", "", "YouTube par gaana chala rahi hoon.")
            }
            rawReply.isNotBlank() && !rawReply.contains("ready hoon") -> {
                ParsedAction("CHAT", "", "", "", rawReply)
            }
            else -> {
                ParsedAction("CHAT", "", "", "", "Haan Manish, boliye main kya karoon?")
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

    private fun openYouTubeSearch(query: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun launchAppByName(name: String): Boolean {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        val target = packages.firstOrNull {
            val label = pm.getApplicationLabel(it).toString().lowercase()
            label.contains(name.lowercase()) || it.packageName.lowercase().contains(name.lowercase())
        }

        return if (target != null) {
            val launchIntent = pm.getLaunchIntentForPackage(target.packageName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                true
            } else false
        } else false
    }
}
