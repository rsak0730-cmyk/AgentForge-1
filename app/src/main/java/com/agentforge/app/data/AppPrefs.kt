package com.agentforge.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppPrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "agentforge_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("agentforge_prefs", Context.MODE_PRIVATE)

    // ---------------- PERMANENT MEMORY & IDENTITY ----------------
    var agentMemories: String
        get() = prefs.getString("agent_memories", "{}") ?: "{}"
        set(v) = prefs.edit().putString("agent_memories", v).apply()

    var name: String
        get() = prefs.getString("assistant_name", "Mira") ?: "Mira"
        set(v) = prefs.edit().putString("assistant_name", v).apply()

    var wakeWord: String
        get() = prefs.getString("wake_word", "hey mira") ?: "hey mira"
        set(v) = prefs.edit().putString("wake_word", v).apply()

    var isSleeping: Boolean
        get() = prefs.getBoolean("is_sleeping", false)
        set(v) = prefs.edit().putBoolean("is_sleeping", v).apply()

    // ---------------- CASUAL NICKNAME PREFERENCE ----------------
    var userPetName: String
        get() = prefs.getString("user_pet_name", "Buddy") ?: "Buddy"
        set(v) = prefs.edit().putString("user_pet_name", v).apply()

    // ---------------- AI API VAULT ----------------
    var provider: String
        get() = prefs.getString("ai_provider", "gemini") ?: "gemini"
        set(v) = prefs.edit().putString("ai_provider", v).apply()

    var key: String
        get() = securePrefs.getString("ai_key", "") ?: ""
        set(v) = securePrefs.edit().putString("ai_key", v).apply()

    var model: String
        get() = prefs.getString("ai_model", "gemini-3.8-flash") ?: "gemini-3.8-flash"
        set(v) = prefs.edit().putString("ai_model", v).apply()

    var baseUrl: String
        get() = prefs.getString("ai_base_url", "https://generativelanguage.googleapis.com") ?: "https://generativelanguage.googleapis.com"
        set(v) = prefs.edit().putString("ai_base_url", v).apply()

    // ---------------- BIOMETRICS ----------------
    var isFaceLockEnabled: Boolean
        get() = prefs.getBoolean("face_lock_enabled", false)
        set(v) = prefs.edit().putBoolean("face_lock_enabled", v).apply()

    var isFaceEnrolled: Boolean
        get() = prefs.getBoolean("face_enrolled", false)
        set(v) = prefs.edit().putBoolean("face_enrolled", v).apply()

    var registeredFaceHash: String
        get() = securePrefs.getString("registered_face_hash", "") ?: ""
        set(v) = securePrefs.edit().putString("registered_face_hash", v).apply()

    var isVoiceprintEnrolled: Boolean
        get() = prefs.getBoolean("voiceprint_enrolled", false)
        set(v) = prefs.edit().putBoolean("voiceprint_enrolled", v).apply()

    var registeredVoiceprint: String
        get() = securePrefs.getString("registered_voiceprint", "") ?: ""
        set(v) = securePrefs.edit().putString("registered_voiceprint", v).apply()

    var enrolledVoiceprint: String
        get() = registeredVoiceprint
        set(v) { registeredVoiceprint = v }

    // ---------------- DYNAMIC ISLAND ----------------
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

    var islandRadius: Float
        get() = prefs.getFloat("island_radius", 24f)
        set(v) = prefs.edit().putFloat("island_radius", v).apply()
}
