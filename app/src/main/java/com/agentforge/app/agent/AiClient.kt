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

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun ask(prompt: String, imageBytes: ByteArray? = null): String = withContext(Dispatchers.IO) {
        val apiKey = prefs.key
        if (apiKey.isBlank()) return@withContext "API Key missing hai. Settings me jakar set karein."

        return@withContext try {
            if (prefs.provider == "gemini") {
                callGemini(prompt, imageBytes, apiKey)
            } else {
                callOpenAiCompatible(prompt, imageBytes, apiKey)
            }
        } catch (e: Exception) {
            "API Connection Error: ${e.message}"
        }
    }

    private fun callGemini(prompt: String, imageBytes: ByteArray?, apiKey: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${prefs.model}:generateContent?key=$apiKey"
        val partsArray = JSONArray()

        if (imageBytes != null) {
            val inlineData = JSONObject().apply {
                put("mime_type", "image/jpeg")
                put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
            }
            partsArray.put(JSONObject().put("inline_data", inlineData))
        }

        partsArray.put(JSONObject().put("text", prompt))

        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", partsArray)))
        }

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val res = http.newCall(req).execute()
        val resBody = res.body?.string() ?: return "Empty response"
        val obj = JSONObject(resBody)
        return obj.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text") ?: resBody
    }

    private fun callOpenAiCompatible(prompt: String, imageBytes: ByteArray?, apiKey: String): String {
        val url = if (prefs.baseUrl.endsWith("/chat/completions")) prefs.baseUrl else "${prefs.baseUrl.removeSuffix("/")}/chat/completions"
        val messages = JSONArray()

        val contentObj = JSONArray()
        if (imageBytes != null) {
            val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            contentObj.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            })
        }
        contentObj.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", contentObj)
        })

        val bodyJson = JSONObject().apply {
            put("model", prefs.model)
            put("messages", messages)
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val res = http.newCall(req).execute()
        val resBody = res.body?.string() ?: return "Empty response"
        val obj = JSONObject(resBody)
        return obj.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content") ?: resBody
    }
}
