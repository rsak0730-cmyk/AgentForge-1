package com.agentforge.app.security

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurityVault(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "AgentForgeMasterShieldKey"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    init {
        initHardwareKey()
    }

    private fun initHardwareKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
        } catch (_: Exception) {}
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) { plainText }
    }

    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        return try {
            val decoded = Base64.decode(cipherText, Base64.NO_WRAP)
            if (decoded.size <= GCM_IV_LENGTH) return ""
            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherData = ByteArray(decoded.size - GCM_IV_LENGTH)
            System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(decoded, GCM_IV_LENGTH, cipherData, 0, cipherData.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            String(cipher.doFinal(cipherData), Charsets.UTF_8)
        } catch (_: Exception) { cipherText }
    }

    fun verifyEnvironmentIntegrity(): SecurityStatus {
        if (isDebuggerAttached()) {
            return SecurityStatus.THREAT_DETECTED("Active Debugger attached! Process halted.")
        }
        if (isFridaOrHookingPresent()) {
            return SecurityStatus.THREAT_DETECTED("Dynamic Hooking (Frida/Xposed) engine detected!")
        }
        if (isDeviceRootedStrict()) {
            return SecurityStatus.THREAT_DETECTED("Compromised System: Device has Root/Magisk binaries!")
        }
        if (isEmulator()) {
            return SecurityStatus.THREAT_DETECTED("Virtual Sandbox/Emulator detected!")
        }
        return SecurityStatus.SECURE
    }

    private fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    private fun isFridaOrHookingPresent(): Boolean {
        val ports = intArrayOf(27042, 27043)
        for (port in ports) {
            try {
                Socket("127.0.0.1", port).use { return true }
            } catch (_: Exception) {}
        }

        try {
            val file = File("/proc/${Process.myPid()}/maps")
            if (file.canRead()) {
                val lines = file.readLines()
                for (line in lines) {
                    if (line.contains("frida", ignoreCase = true) ||
                        line.contains("xposed", ignoreCase = true) ||
                        line.contains("substrate", ignoreCase = true)
                    ) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}

        return false
    }

    private fun isDeviceRootedStrict(): Boolean {
        val rootPaths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup"
        )
        for (path in rootPaths) {
            if (File(path).exists()) return true
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.readLine() != null
        } catch (_: Exception) {
            false
        }
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }

    sealed class SecurityStatus {
        object SECURE : SecurityStatus()
        data class THREAT_DETECTED(val reason: String) : SecurityStatus()
    }
}
