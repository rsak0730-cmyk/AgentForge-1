package com.agentforge.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppPrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    // Encrypted Hardware Storage for sensitive credentials
    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "agentforge_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Standard SharedPreferences for app layout, toggles & states
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agentforge_prefs", Context.MODE_PRIVATE)

    // ---------------- PERMANENT LONG-TERM MEMORY (JSON) ----------------
    var agentMemories: String
        get() = prefs.getString("agent_memories", "{}") ?: "{}"
        set(v) = prefs.edit().putString("agent_memories", v).apply()

    // ---------------- ASSISTANT IDENTITY & VOICE ----------------
    var name: String
        get() = prefs.getString("assistant_name", "Mira") ?: "Mira"
        set(v) = prefs.edit().putString("assistant_name", v).apply()

    var wakeWord: String
        get() = prefs.getString("wake_word", "hey mira") ?: "hey mira"
        set(v) = prefs.edit().putString("wake_word", v).apply()

    var isSleeping: Boolean
        get() = prefs.getBoolean("is_sleeping", false)
        set(v) = prefs.edit().putBoolean("is_sleeping", v).apply()

    // ---------------- AI API VAULT (ENCRYPTED) ----------------
    var provider: String
        get() = prefs.getString("ai_provider", "gemini") ?: "gemini"
        set(v) = prefs.edit().putString("ai_provider", v).apply()

    var key: String
        get() = securePrefs.getString("ai_key", "") ?: ""
        set(v) = securePrefs.edit().putString("ai_key", v).apply()

    var model: String
        get() = prefs.getString("ai_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) = prefs.edit().putString("ai_model", v).apply()

    var baseUrl: String
        get() = prefs.getString("ai_base_url", "https://generativelanguage.googleapis.com") ?: "https://generativelanguage.googleapis.com"
        set(v) = prefs.edit().putString("ai_base_url", v).apply()

    // ---------------- BIOMETRIC FACE MESH LOCK ----------------
    var isFaceLockEnabled: Boolean
        get() = prefs.getBoolean("face_lock_enabled", false)
        set(v) = prefs.edit().putBoolean("face_lock_enabled", v).apply()

    var isFaceEnrolled: Boolean
        get() = prefs.getBoolean("face_enrolled", false)
        set(v) = prefs.edit().putBoolean("face_enrolled", v).apply()

    var registeredFaceHash: String
        get() = securePrefs.getString("registered_face_hash", "") ?: ""
        set(v) = securePrefs.edit().putString("registered_face_hash", v).apply()

    // ---------------- VOICEPRINT ACOUSTIC BIOMETRICS ----------------
    var isVoiceprintEnrolled: Boolean
        get() = prefs.getBoolean("voiceprint_enrolled", false)
        set(v) = prefs.edit().putBoolean("voiceprint_enrolled", v).apply()

    var registeredVoiceprint: String
        get() = securePrefs.getString("registered_voiceprint", "") ?: ""
        set(v) = securePrefs.edit().putString("registered_voiceprint", v).apply()

    // ---------------- DYNAMIC ISLAND GEOMETRY & TOGGLES ----------------
    var isIslandEnabled: Boolean
        get() = prefs.getBoolean("island_enabled", true)
        set(v) = prefs.edit().putBoolean("island_enabled", v).apply()

    var islandX: Float
        get() = prefs.getFloat("island_x", 0f)
        set(v) = prefs.edit().putFloat("island_x", v).apply()

    var islandY: Float
        get() = prefs.getFloat("island_y", 20f)
        set(v) = prefs.edit().putFloat("island_y", v).apply()

    var islandWidth: Float
        get() = prefs.getFloat("island_width", 260f)
        set(v) = prefs.edit().putFloat("island_width", v).apply()

    var islandHeight: Float
        get() = prefs.getFloat("island_height", 42f)
        set(v) = prefs.edit().putFloat("island_height", v).apply()

    // ---------------- SYSTEM LEVEL FLAGS ----------------
    var isAccessibilityEnabled: Boolean
        get() = prefs.getBoolean("accessibility_enabled", false)
        set(v) = prefs.edit().putBoolean("accessibility_enabled", v).apply()

    var isShizukuPermitted: Boolean
        get() = prefs.getBoolean("shizuku_permitted", false)
        set(v) = prefs.edit().putBoolean("shizuku_permitted", v).apply()
}
