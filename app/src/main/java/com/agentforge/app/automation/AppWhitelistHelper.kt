package com.agentforge.app.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

class AppWhitelistHelper(private val context: Context, private val shizuku: ShizukuBridge) {

    fun ensureBackgroundSurvival() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {}

        if (shizuku.hasPermission()) {
            val pkg = context.packageName
            shizuku.run("dumpsys deviceidle whitelist +$pkg")
            shizuku.run("cmd appops set $pkg RUN_IN_BACKGROUND allow")
            shizuku.run("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        }
    }
}
