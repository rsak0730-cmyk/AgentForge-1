package com.agentforge.app.data

import android.content.Context
import com.agentforge.app.security.SecurityVault

class AppPrefs(context: Context) {
    private val p = context.getSharedPreferences("agentforge_vault", Context.MODE_PRIVATE)
    private val vault = SecurityVault(context)

    var provider: String get() = p.getString("provider", "gemini") ?: "gemini"; set(v) = p.edit().putString("provider", v).apply()
    var name: String get() = p.getString("name", "Mira") ?: "Mira"; set(v) = p.edit().putString("name", v).apply()
    var wakeWord: String get() = p.getString("wake_word", "hey mira") ?: "hey mira"; set(v) = p.edit().putString("wake_word", v).apply()
    var isSleeping: Boolean get() = p.getBoolean("is_sleeping", false); set(v) = p.edit().putBoolean("is_sleeping", v).apply()

    // Real Face Biometric Store
    var isFaceLockEnabled: Boolean get() = p.getBoolean("face_lock_enabled", false); set(v) = p.edit().putBoolean("face_lock_enabled", v).apply()
    var isFaceEnrolled: Boolean get() = p.getBoolean("face_enrolled", false); set(v) = p.edit().putBoolean("face_enrolled", v).apply()
    var registeredFaceHash: String get() = p.getString("face_hash_data", "") ?: ""; set(v) = p.edit().putString("face_hash_data", v).apply()

    // Dynamic Island State
    var isIslandEnabled: Boolean get() = p.getBoolean("island_enabled", false); set(v) = p.edit().putBoolean("island_enabled", v).apply()
    var islandX: Float get() = p.getFloat("ix", 0f); set(v) = p.edit().putFloat("ix", v).apply()
    var islandY: Float get() = p.getFloat("iy", 20f); set(v) = p.edit().putFloat("iy", v).apply()
    var islandWidth: Float get() = p.getFloat("iw", 180f); set(v) = p.edit().putFloat("iw", v).apply()
    var islandHeight: Float get() = p.getFloat("ih", 42f); set(v) = p.edit().putFloat("ih", v).apply()
    var islandRadius: Float get() = p.getFloat("ir", 24f); set(v) = p.edit().putFloat("ir", v).apply()

    // Voiceprint Biometrics
    var isVoiceprintEnrolled: Boolean get() = p.getBoolean("voiceprint_enrolled", false); set(v) = p.edit().putBoolean("voiceprint_enrolled", v).apply()
    var enrolledVoiceprint: String
        get() = vault.decrypt(p.getString("vault_voiceprint", "") ?: "")
        set(v) = p.edit().putString("vault_voiceprint", vault.encrypt(v)).apply()

    // Encrypted API Keys
    var key: String
        get() = vault.decrypt(p.getString("vault_api_key", "") ?: "")
        set(v) = p.edit().putString("vault_api_key", vault.encrypt(v)).apply()

    var model: String get() = p.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"; set(v) = p.edit().putString("model", v).apply()
    var baseUrl: String get() = p.getString("base", "https://generativelanguage.googleapis.com") ?: "https://generativelanguage.googleapis.com"; set(v) = p.edit().putString("base", v).apply()
}
