package com.agentforge.app.agent

import android.util.Base64
import com.agentforge.app.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiClient(private val prefs: AppPrefs) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun ask(prompt: String, screenBytes: ByteArray? = null): String = withContext(Dispatchers.IO) {
        val apiKey = prefs.key.trim()
        if (apiKey.isBlank()) {
            return@withContext """{"thought":"No API Key","action":"CHAT","param":"","remember_key":"","remember_value":"","reply":"API Key configure nahi hai Manish. Settings me jakar apni key save karo na."}"""
        }

        val provider = prefs.provider.lowercase().trim()
        var model = prefs.model.trim()
        if (model.isBlank() || model == "gemini-2.5-flash") {
            model = "gemini-3.8-flash"
        }

        try {
            when (provider) {
                "gemini" -> callGemini(apiKey, model, prompt, screenBytes)
                "openai", "openrouter" -> callOpenAiCompatible(apiKey, model, prompt)
                else -> callGemini(apiKey, model, prompt, screenBytes)
            }
        } catch (e: Exception) {
            """{"thought":"API Error","action":"CHAT","param":"","remember_key":"","remember_value":"","reply":"Network me problem aayi Manish: ${e.message ?: "Connection failed"}"}"""
        }
    }

    private fun callGemini(apiKey: String, model: String, prompt: String, screenBytes: ByteArray?): String {
        val cleanBase = prefs.baseUrl.trim().removeSuffix("/")
        val cleanModel = model.removePrefix("models/")
        val url = "$cleanBase/v1beta/models/$cleanModel:generateContent?key=$apiKey"

        val rootJson = JSONObject().apply {
            val contentsArray = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        // 1. Text Prompt Part
                        put(JSONObject().apply { put("text", prompt) })

                        // 2. Multimodal Screen Vision Image Part (if available)
                        if (screenBytes != null && screenBytes.isNotEmpty()) {
                            val base64Data = Base64.encodeToString(screenBytes, Base64.NO_WRAP)
                            val inlineData = JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Data)
                            }
                            put(JSONObject().apply { put("inline_data", inlineData) })
                        }
                    }
                    put("parts", partsArray)
                }
                put(contentObj)
            }
            put("contents", contentsArray)
        }

        val body = rootJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).getJSONObject("error").getString("message")
                } catch (_: Exception) {
                    "HTTP ${response.code}"
                }
                throw Exception("Gemini: $errorMsg")
            }

            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return parts.getJSONObject(0).optString("text", "")
                }
            }
            throw Exception("Empty response from AI")
        }
    }

    private fun callOpenAiCompatible(apiKey: String, model: String, prompt: String): String {
        val cleanBase = prefs.baseUrl.trim().removeSuffix("/")
        val url = if (cleanBase.endsWith("/v1")) "$cleanBase/chat/completions" else "$cleanBase/v1/chat/completions"

        val rootJson = JSONObject().apply {
            put("model", model)
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("messages", messagesArray)
        }

        val body = rootJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                return choices.getJSONObject(0).getJSONObject("message").optString("content", "")
            }
            throw Exception("Empty response from model")
        }
    }
}
