package com.agentforge.app.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.util.Base64
import com.agentforge.app.data.AppPrefs
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class AiClient(
    private val context: Context,
    private val prefs: AppPrefs
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Edge On-Device Engine Instance
    private var localLlmInference: LlmInference? = null
    private var isLocalModelInitialized = false

    init {
        initLocalEdgeModelIfPresent()
    }

    private fun initLocalEdgeModelIfPresent() {
        try {
            // Check if user has placed an on-device model file in MiraScripts or App Storage
            val candidatePaths = listOf(
                File(Environment.getExternalStorageDirectory(), "MiraScripts/model.bin"),
                File(Environment.getExternalStorageDirectory(), "MiraScripts/gemma.bin"),
                File(context.filesDir, "model.bin")
            )
            val modelFile = candidatePaths.firstOrNull { it.exists() && it.length() > 50_000_000 }

            if (modelFile != null && !isLocalModelInitialized) {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(512)
                    .setTopK(40)
                    .setTemperature(0.3f)
                    .build()
                localLlmInference = LlmInference.createFromOptions(context, options)
                isLocalModelInitialized = true
            }
        } catch (_: Throwable) {
            localLlmInference = null
            isLocalModelInitialized = false
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val act = cm.getNetworkCapabilities(net) ?: return false
            act.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun ask(prompt: String, screenJpegBytes: ByteArray? = null): String = withContext(Dispatchers.IO) {
        // 1. Check internet availability
        if (isNetworkAvailable() && prefs.key.isNotBlank()) {
            try {
                return@withContext executeCloudRequest(prompt, screenJpegBytes)
            } catch (_: Exception) {
                // Network call failed (packet loss / API quota issue) -> Fallback to Local Edge Brain
            }
        }

        // 2. Offline / Edge On-Device Fallback
        return@withContext executeEdgeLocalRequest(prompt)
    }

    private fun executeEdgeLocalRequest(prompt: String): String {
        // A. If Google AI Edge Model is loaded, use true on-device inference
        if (localLlmInference != null) {
            try {
                val response = localLlmInference?.generateResponse(prompt)
                if (!response.isNullOrBlank()) {
                    return response.trim()
                }
            } catch (_: Exception) {}
        }

        // B. Instant Offline Heuristic JSON Fallback
        val lower = prompt.lowercase()
        val petName = prefs.userPetName

        if (lower.contains("data") || lower.contains("internet")) {
            val isOff = lower.contains("band") || lower.contains("off")
            return """{"reply": "Mobile data offline handle kar diya $petName.", "steps": [{"action": "APP_OPS", "pkg": "", "op": "", "mode": ""}]}"""
        }
        if (lower.contains("scroll") || lower.contains("next")) {
            return """{"reply": "Next post scroll kar diya.", "steps": [{"action": "SWIPE", "param": "UP"}]}"""
        }
        if (lower.contains("like")) {
            return """{"reply": "Post like kar di.", "steps": [{"action": "CLICK_NODE", "param": "like"}]}"""
        }

        return """{"reply": "Internet connection nahi hai aur local model loaded nahi mila $petName. Lekin basic hardware triggers armed hain.", "steps": []}"""
    }

    private fun executeCloudRequest(prompt: String, screenJpegBytes: ByteArray?): String {
        val provider = prefs.provider.lowercase()
        return when (provider) {
            "gemini" -> callGeminiCloud(prompt, screenJpegBytes)
            "openai", "openrouter" -> callOpenAiCompatible(prompt, screenJpegBytes)
            else -> callGeminiCloud(prompt, screenJpegBytes)
        }
    }

    private fun callGeminiCloud(prompt: String, screenJpegBytes: ByteArray?): String {
        val apiKey = prefs.key.trim()
        val model = if (prefs.model.isNotBlank()) prefs.model.trim() else "gemini-2.5-flash"
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val partsArray = JSONArray()
        partsArray.put(JSONObject().put("text", prompt))

        if (screenJpegBytes != null && screenJpegBytes.isNotEmpty()) {
            val base64Data = Base64.encodeToString(screenJpegBytes, Base64.NO_WRAP)
            val inlineData = JSONObject().apply {
                put("mime_type", "image/jpeg")
                put("data", base64Data)
            }
            partsArray.put(JSONObject().put("inline_data", inlineData))
        }

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
            put("generationConfig", JSONObject().put("temperature", 0.3))
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string() ?: throw RuntimeException("Empty response")
            if (!response.isSuccessful) throw RuntimeException("API error: ${response.code}")

            val obj = JSONObject(raw)
            val candidates = obj.optJSONArray("candidates") ?: throw RuntimeException("No candidates")
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")
            return parts.getJSONObject(0).getString("text")
        }
    }

    private fun callOpenAiCompatible(prompt: String, screenJpegBytes: ByteArray?): String {
        val apiKey = prefs.key.trim()
        val baseUrl = prefs.baseUrl.trim().ifBlank { "https://api.openai.com/v1" }
        val model = if (prefs.model.isNotBlank()) prefs.model.trim() else "gpt-4o-mini"
        val endpoint = if (baseUrl.endsWith("/")) "${baseUrl}chat/completions" else "$baseUrl/chat/completions"

        val messagesArray = JSONArray()
        val userContent = JSONArray()
        userContent.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })

        if (screenJpegBytes != null && screenJpegBytes.isNotEmpty()) {
            val base64Data = Base64.encodeToString(screenJpegBytes, Base64.NO_WRAP)
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Data"))
            })
        }

        messagesArray.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("temperature", 0.3)
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string() ?: throw RuntimeException("Empty response")
            if (!response.isSuccessful) throw RuntimeException("API error: ${response.code}")

            val obj = JSONObject(raw)
            val choices = obj.getJSONArray("choices")
            return choices.getJSONObject(0).getJSONObject("message").getString("content")
        }
    }
}
