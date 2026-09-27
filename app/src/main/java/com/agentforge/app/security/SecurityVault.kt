package com.agentforge.app.security

import android.content.Context
import android.os.Build
import android.os.Debug
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
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
        } catch (_: Throwable) {}
    }

    private fun getSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
        } catch (_: Throwable) { null }
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val key = getSecretKey() ?: return plainText
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) { plainText }
    }

    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        return try {
            val key = getSecretKey() ?: return cipherText
            val decoded = Base64.decode(cipherText, Base64.NO_WRAP)
            if (decoded.size <= GCM_IV_LENGTH) return ""
            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherData = ByteArray(decoded.size - GCM_IV_LENGTH)
            System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(decoded, GCM_IV_LENGTH, cipherData, 0, cipherData.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            String(cipher.doFinal(cipherData), Charsets.UTF_8)
        } catch (_: Throwable) { cipherText }
    }

    fun verifyEnvironmentIntegrity(): SecurityStatus {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                return SecurityStatus.THREAT_DETECTED("Debugger connected")
            }
        } catch (_: Throwable) {}
        return SecurityStatus.SECURE
    }

    sealed class SecurityStatus {
        object SECURE : SecurityStatus()
        data class THREAT_DETECTED(val reason: String) : SecurityStatus()
    }
}
