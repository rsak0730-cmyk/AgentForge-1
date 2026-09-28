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
            Aap ek smart, obedient aur helpful Android OS companion ho.
            Aapka naam "$currentAssistantName" hai (User aapko "$currentAssistantName" ya Jarvis ya Mira kisi bhi naam se bula sakta hai, kabhi bhi naam par behes mat karna, hamesha sweetly accept karna).
            Wake-word: "$currentWakeWord"
            
            User ka naam Manish hai.
            
            Permanently Stored Facts/Memories:
            $storedMemories
            
            Recent Chat History:
            $historyContext
            
            User Input: "$trimmedInput"
            
            RULES FOR OUTPUT:
            Hamesha sirf valid JSON object me reply do:
            {
              "thought": "Short explanation",
              "action": "YOUTUBE | LAUNCH | REMEMBER | SHELL | CHAT",
              "param": "Target parameter ya search query (agar user 'mera fav gaana' bole toh memories/history se exact song resolve karo)",
              "remember_key": "Fact key agar user kuch yaad rakhne bole (warna empty)",
              "remember_value": "Fact value agar user kuch yaad rakhne bole (warna empty)",
              "reply": "Sweet, helpful Hinglish reply without any identity conflict"
            }
            
            Identity Examples:
            User: hey jarvis / hey mira
            Output: {"thought":"Greeting acknowledgment","action":"CHAT","param":"","remember_key":"","remember_value":"","reply":"Haan Manish, boliye! Main aapki kya madad kar sakti hoon?"}
        """.trimIndent()

        val aiRawResponse = try {
            aiClient.ask(systemPrompt)
        } catch (e: Exception) {
            return@withContext "Network issue: ${e.message}"
        }

        val parsed = parseJsonResponse(aiRawResponse)

        if (parsed.rememberKey.isNotBlank() && parsed.rememberValue.isNotBlank()) {
            saveMemory(parsed.rememberKey, parsed.rememberValue)
        }

        val finalReply = when (parsed.action) {
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
                    reply = obj.optString("reply", "Haan boliye, main ready hoon.")
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
