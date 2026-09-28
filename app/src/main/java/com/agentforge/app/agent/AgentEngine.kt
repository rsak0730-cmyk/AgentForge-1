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
    private val chatHistory = mutableListOf<Pair<String, String>>() // Memory buffer (User, Assistant)

    suspend fun execute(userInput: String): String = withContext(Dispatchers.IO) {
        val trimmedInput = userInput.trim()

        // 1. Long-Term Memory (Permanent facts storage)
        val storedMemories = prefs.agentMemories // Key-value JSON string of remembered facts
        val historyContext = chatHistory.takeLast(6).joinToString("\n") { 
            "User: ${it.first}\nAssistant: ${it.second}" 
        }

        // 2. Strict Brain Prompt with Memory Injection
        val systemPrompt = """
            Aap Mira ho, Manish ke personal Android OS companion aur Jarvis-style agent.
            
            Permanently Remembered Facts about Manish:
            $storedMemories
            
            Recent Conversation History:
            $historyContext
            
            Current User Input: "$trimmedInput"
            
            RULES FOR OUTPUT:
            Hamesha sirf valid JSON object me reply do:
            {
              "thought": "Short explanation of intent",
              "action": "YOUTUBE | LAUNCH | REMEMBER | SHELL | CHAT",
              "param": "Target parameter ya search query (agar user 'mera fav gaana' bole toh stored memories ya context se resolve karke actual song name likho)",
              "remember_key": "Fact key agar user kuch yaad rakhne bole (warna empty)",
              "remember_value": "Fact value agar user kuch yaad rakhne bole (warna empty)",
              "reply": "Natural Hinglish reply Manish ke liye"
            }
            
            Examples:
            1. User: mera fav gaana "Arz kya hai" ab ise yaad rakhna
               Output: {"thought":"Storing favorite song","action":"REMEMBER","param":"Arz kya hai","remember_key":"fav_song","remember_value":"Arz kya hai","reply":"Theek hai Manish, maine yaad rakh liya ki aapka favourite gaana 'Arz kya hai' hai."}
            2. User: mera fav gaana lagao youtube pe
               Output: {"thought":"Playing favorite song from memory","action":"YOUTUBE","param":"Arz kya hai","remember_key":"","remember_value":"","reply":"Aapka favourite gaana 'Arz kya hai' YouTube par play kar rahi hoon."}
        """.trimIndent()

        val aiRawResponse = try {
            aiClient.ask(systemPrompt)
        } catch (e: Exception) {
            return@withContext "Internet ya API connection me problem aayi: ${e.message}"
        }

        val parsed = parseJsonResponse(aiRawResponse)
        
        // Handle Permanent Fact Storage
        if (parsed.rememberKey.isNotBlank() && parsed.rememberValue.isNotBlank()) {
            saveMemory(parsed.rememberKey, parsed.rememberValue)
        }

        // Execute OS Actions
        val executionResult = when (parsed.action) {
            "YOUTUBE" -> {
                val query = if (parsed.param.isNotBlank()) parsed.param else getMemory("fav_song") ?: "Hindi song"
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
                    "${parsed.reply} (Lekin Shizuku offline hai)"
                }
            }
            else -> parsed.reply
        }

        // Save to active short-term session memory
        chatHistory.add(trimmedInput to executionResult)
        if (chatHistory.size > 12) chatHistory.removeAt(0)

        executionResult
    }

    private data class ParsedAction(
        val action: String,
        val param: String,
        val rememberKey: String,
        val rememberValue: String,
        val reply: String
    )

    private fun parseJsonResponse(raw: String): ParsedAction {
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
                    reply = obj.optString("reply", "Done")
                )
            } else {
                ParsedAction("CHAT", "", "", "", raw)
            }
        } catch (_: Exception) {
            ParsedAction("CHAT", "", "", "", raw)
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
