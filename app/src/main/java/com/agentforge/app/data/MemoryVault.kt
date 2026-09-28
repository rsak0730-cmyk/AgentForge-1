package com.agentforge.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MemoryVault(context: Context) {
    private val memoryFile = File(context.filesDir, "mira_memory.json")

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

    fun incrementInteraction() {
        try {
            val obj = JSONObject(memoryFile.readText())
            val count = obj.optInt("interaction_count", 0) + 1
            obj.put("interaction_count", count)
            memoryFile.writeText(obj.toString())
        } catch (_: Exception) {}
    }
}
