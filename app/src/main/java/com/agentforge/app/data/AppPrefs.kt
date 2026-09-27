package com.agentforge.app.data

import android.content.Context

class AppPrefs(context: Context) {
    private val p = context.getSharedPreferences("agentforge", Context.MODE_PRIVATE)

    var provider: String get() = p.getString("provider", "gemini") ?: "gemini"; set(v) = p.edit().putString("provider", v).apply()
    var name: String get() = p.getString("name", "Mira") ?: "Mira"; set(v) = p.edit().putString("name", v).apply()
    var wakeWord: String get() = p.getString("wake_word", "hey mira") ?: "hey mira"; set(v) = p.edit().putString("wake_word", v).apply()
    var isSleeping: Boolean get() = p.getBoolean("is_sleeping", false); set(v) = p.edit().putBoolean("is_sleeping", v).apply()

    // Security Settings
    var isFaceLockEnabled: Boolean get() = p.getBoolean("face_lock_enabled", false); set(v) = p.edit().putBoolean("face_lock_enabled", v).apply()
    var isVoiceprintEnrolled: Boolean get() = p.getBoolean("voiceprint_enrolled", false); set(v) = p.edit().putBoolean("voiceprint_enrolled", v).apply()
    var enrolledVoiceprint: String get() = p.getString("voiceprint_data", "") ?: ""; set(v) = p.edit().putString("voiceprint_data", v).apply()

    var key: String get() = p.getString("key", "") ?: ""; set(v) = p.edit().putString("key", v).apply()
    var model: String get() = p.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"; set(v) = p.edit().putString("model", v).apply()
    var baseUrl: String get() = p.getString("base", "https://generativelanguage.googleapis.com") ?: "https://generativelanguage.googleapis.com"; set(v) = p.edit().putString("base", v).apply()
    var theme: String get() = p.getString("theme", "neonblue") ?: "neonblue"; set(v) = p.edit().putString("theme", v).apply()
    var ui: String get() = p.getString("ui", "Glassmorphism") ?: "Glassmorphism"; set(v) = p.edit().putString("ui", v).apply()
    var textFx: String get() = p.getString("textFx", "glow") ?: "glow"; set(v) = p.edit().putString("textFx", v).apply()
    var islandX: Float get() = p.getFloat("ix", 0f); set(v) = p.edit().putFloat("ix", v).apply()
    var islandY: Float get() = p.getFloat("iy", 0f); set(v) = p.edit().putFloat("iy", v).apply()
    var islandWidth: Float get() = p.getFloat("iw", 180f); set(v) = p.edit().putFloat("iw", v).apply()
    var islandHeight: Float get() = p.getFloat("ih", 42f); set(v) = p.edit().putFloat("ih", v).apply()
    var islandRadius: Float get() = p.getFloat("ir", 24f); set(v) = p.edit().putFloat("ir", v).apply()
}
