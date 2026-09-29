package com.agentforge.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MemoryVault(context: Context) {
    private val memoryFile = File(context.filesDir, "mira_memory.json")
    private val screenMemoryFile = File(context.filesDir, "mira_screen_cache.json")

    init {
        if (!memoryFile.exists()) {
            val initial = JSONObject().apply {
                put("user_facts", JSONArray().put("Owner name is Boss/Manish"))
                put("user_mood", "neutral")
                put("interaction_count", 0)
            }
            memoryFile.writeText(initial.toString())
        }
    }

    fun getMemorySummary(): String {
        return try {
            val obj = JSONObject(memoryFile.readText())
            val facts = obj.optJSONArray("user_facts")
            val list = mutableListOf<String>()
            if (facts != null) {
                for (i in 0 until facts.length()) {
                    list.add("- " + facts.getString(i))
                }
            }
            list.joinToString("\n")
        } catch (_: Exception) {
            "No prior memories."
        }
    }

    fun saveFact(newFact: String) {
        try {
            val obj = JSONObject(memoryFile.readText())
            val facts = obj.optJSONArray("user_facts") ?: JSONArray()
            facts.put(newFact)
            obj.put("user_facts", facts)
            memoryFile.writeText(obj.toString())
        } catch (_: Exception) {}
    }

    // Passive temporal screen memory caching
    fun cacheScreenText(screenTextDump: String) {
        if (screenTextDump.isBlank()) return
        try {
            val list = if (screenMemoryFile.exists()) {
                JSONArray(screenMemoryFile.readText())
            } else {
                JSONArray()
            }
            val entry = JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("text", screenTextDump.take(500))
            }
            list.put(entry)
            // Keep last 15 screen memories only to save memory
            while (list.length() > 15) {
                list.remove(0)
            }
            screenMemoryFile.writeText(list.toString())
        } catch (_: Exception) {}
    }

    fun getRecentScreenMemory(): String {
        return try {
            if (!screenMemoryFile.exists()) return "No recent screen activity recorded."
            val array = JSONArray(screenMemoryFile.readText())
            val sb = StringBuilder()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                sb.append("• ").append(item.optString("text")).append("\n")
            }
            sb.toString()
        } catch (_: Exception) {
            "No screen activity logged."
        }
    }
}
