package com.agentforge.app.automation

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuBridge(private val context: Context) {

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        // Handle result if needed
    }

    init {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {}
    }

    fun hasPermission(): Boolean {
        return try {
            if (Shizuku.isPre_V11()) false
            else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (!hasPermission() && Shizuku.pingBinder()) {
                Shizuku.requestPermission(1001)
            }
        } catch (_: Throwable) {}
    }

    fun connect(): Boolean {
        return hasPermission()
    }

    fun run(action: String): String {
        val shellCmd = when (action) {
            "home" -> "input keyevent 3"
            "back" -> "input keyevent 4"
            "recent" -> "input keyevent 187"
            "play_pause" -> "input keyevent 85"
            else -> action
        }
        return executeCommand(shellCmd)
    }

    fun executeCommand(command: String): String {
        if (!hasPermission()) return "Shizuku permission not granted"
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun close() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {}
    }
}
