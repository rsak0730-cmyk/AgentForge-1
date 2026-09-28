package com.agentforge.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agentforge.app.automation.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class AgentEngine(
    private val context: Context,
    private val aiClient: AiClient,
    private val shizuku: ShizukuBridge
) {

    suspend fun execute(userInput: String): String = withContext(Dispatchers.IO) {
        val lower = userInput.lowercase().trim()

        // 1. Direct Local Fast-Path (Instant without waiting for API)
        if (lower.contains("youtube") && (lower.contains("search") || lower.contains("play") || lower.contains("chalao") || lower.contains("gaana"))) {
            val query = extractQuery(userInput, listOf("search", "play", "chalao", "sunao", "for", "on youtube", "youtube"))
            if (query.isNotBlank()) {
                val success = openYouTubeSearch(query)
                return@withContext if (success) "YouTube par '$query' play kar diya." else "YouTube open nahi ho paya."
            }
        }

        if (lower.startsWith("open ") || lower.startsWith("kholo ")) {
            val appTarget = lower.replace("open ", "").replace("kholo ", "").trim()
            val launched = launchAppByName(appTarget)
            if (launched) return@withContext "$appTarget open kar diya."
        }

        // 2. Fallback to Gemini Brain with Strict Action Prompting
        val systemPrompt = """
            You are Mira, an OS companion on Android.
            When the user wants to perform an action, you MUST respond in this format:
            ACTION: <COMMAND_TYPE> | <PARAMETERS> | <NATURAL_RESPONSE>
            
            Supported Actions:
            - YOUTUBE: <query> | <Hindi natural response>
            - LAUNCH: <app_name> | <Hindi natural response>
            - SHELL: <adb_command> | <Hindi natural response>
            - CHAT: none | <Hindi natural response>
            
            Example:
            User: open youtube and search Hindi song
            Response: ACTION: YOUTUBE | Hindi song | YouTube par Hindi song search kar diya hai.
        """.trimIndent()

        val aiResponse = aiClient.ask("$systemPrompt\n\nUser: $userInput")

        if (aiResponse.startsWith("ACTION:")) {
            parseAndExecuteAction(aiResponse)
        } else {
            aiResponse
        }
    }

    private fun parseAndExecuteAction(response: String): String {
        return try {
            val clean = response.removePrefix("ACTION:").trim()
            val parts = clean.split("|").map { it.trim() }
            val actionType = parts.getOrNull(0)?.uppercase() ?: "CHAT"
            val param = parts.getOrNull(1) ?: ""
            val naturalReply = parts.getOrNull(2) ?: "Done"

            when (actionType) {
                "YOUTUBE" -> {
                    openYouTubeSearch(param)
                    naturalReply
                }
                "LAUNCH" -> {
                    launchAppByName(param)
                    naturalReply
                }
                "SHELL" -> {
                    if (shizuku.hasPermission()) {
                        shizuku.executeCommand(param)
                    }
                    naturalReply
                }
                else -> naturalReply
            }
        } catch (_: Exception) {
            response
        }
    }

    private fun openYouTubeSearch(query: String): Boolean {
        return try {
            // Intent 1: Direct YouTube App Search Activity
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                // Fallback Intent 2: Universal Web/App Deep Link
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
            val appLabel = pm.getApplicationLabel(it).toString().lowercase()
            appLabel.contains(name.lowercase()) || it.packageName.lowercase().contains(name.lowercase())
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

    private fun extractQuery(text: String, stopWords: List<String>): String {
        var clean = text
        for (w in stopWords) {
            clean = clean.replace(Regex("(?i)\\b$w\\b"), "")
        }
        return clean.replace(Regex("(?i)and"), "").trim()
    }
}
