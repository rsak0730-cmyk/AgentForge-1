package com.agentforge.app.automation

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuBridge(private val context: Context) {

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> }

    init {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.addRequestPermissionResultListener(permissionListener)
            }
        } catch (_: Throwable) {}
    }

    fun isReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return isReady()
    }

    fun requestPermission(requestCode: Int = 1001) {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(requestCode)
                }
            }
        } catch (_: Throwable) {}
    }

    fun connect(): Boolean {
        return isReady()
    }

    fun run(command: String): String {
        return runShellCommand(command)
    }

    fun runShellCommand(command: String): String {
        if (!isReady()) return "ERR_SHIZUKU_NOT_READY"
        return try {
            val method = Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Throwable) {
            "ERR_EXECUTION_FAILED: ${e.message}"
        }
    }

    // ---------------- DEEP HARDWARE & SYSTEM TOGGLES (BIXBY LEVEL) ----------------
    fun setMobileData(enable: Boolean): Boolean {
        val state = if (enable) "enable" else "disable"
        return !runShellCommand("svc data $state").startsWith("ERR")
    }

    fun setWifi(enable: Boolean): Boolean {
        val state = if (enable) "enable" else "disable"
        return !runShellCommand("svc wifi $state").startsWith("ERR")
    }

    fun setBluetooth(enable: Boolean): Boolean {
        val state = if (enable) "enable" else "disable"
        return !runShellCommand("cmd bluetooth_manager $state").startsWith("ERR")
    }

    fun setPowerSaver(enable: Boolean): Boolean {
        val state = if (enable) "1" else "0"
        return !runShellCommand("settings put global low_power $state").startsWith("ERR")
    }

    fun setBrightness(level: Int): Boolean {
        val clamped = level.coerceIn(0, 255)
        return !runShellCommand("settings put system screen_brightness $clamped").startsWith("ERR")
    }

    fun lockScreen(): Boolean {
        return !runShellCommand("input keyevent 26").startsWith("ERR")
    }

    // ---------------- INPUT & TOUCH INJECTION ----------------
    fun inputTap(x: Float, y: Float): Boolean {
        val res = runShellCommand("input tap ${x.toInt()} ${y.toInt()}")
        return !res.startsWith("ERR")
    }

    fun inputText(text: String): Boolean {
        val sanitized = text.replace(" ", "%s").replace("'", "\\'")
        val res = runShellCommand("input text '$sanitized'")
        return !res.startsWith("ERR")
    }

    fun setAppOp(packageName: String, opName: String, allow: Boolean): Boolean {
        val mode = if (allow) "allow" else "ignore"
        val res = runShellCommand("appops set $packageName $opName $mode")
        return !res.startsWith("ERR")
    }

    fun close() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {}
    }
}
