package com.agentforge.app.agent

import com.agentforge.app.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AiClient(private val prefs: AppPrefs) {
    private val client = OkHttpClient()
    suspend fun ask(prompt: String): String = withContext(Dispatchers.IO) {
        if (prefs.key.isBlank()) return@withContext "API key is not configured. Open API Setup first."
        when (prefs.provider.lowercase()) {
            "openai", "openrouter" -> openCompatible(prompt)
            else -> gemini(prompt)
        }
    }
    private fun gemini(prompt: String): String {
        val base = prefs.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com" }
        val url = "$base/v1beta/models/${prefs.model}:generateContent?key=${prefs.key}"
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))).toString()
        val req = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) return "AI error ${r.code}: ${raw.take(300)}"
            return JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: "No response."
        }
    }
    private fun openCompatible(prompt: String): String {
        val base = prefs.baseUrl.trimEnd('/').ifBlank { "https://api.openai.com/v1" }
        val body = JSONObject().put("model", prefs.model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt))).toString()
        val req = Request.Builder().url("$base/chat/completions").addHeader("Authorization", "Bearer ${prefs.key}").post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) return "AI error ${r.code}: ${raw.take(300)}"
            return JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: "No response."
        }
    }
}
