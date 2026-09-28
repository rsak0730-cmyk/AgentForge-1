package com.agentforge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager

class BatteryGuardianReceiver : BroadcastReceiver() {

    companion object {
        private var hasNotified80 = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

            val batteryPct = (level * 100 / scale.toFloat()).toInt()
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            // Thermal Warning (> 44°C)
            if (temp > 44.0) {
                AgentAccessibilityService.instance?.showIsland("⚠️ Device Heating: ${temp}°C")
            }

            // 80% Unplug Protection Notification
            if (isCharging && batteryPct >= 80 && !hasNotified80) {
                hasNotified80 = true
                AgentAccessibilityService.instance?.showIsland("🔋 Battery 80% • Unplug Charger")
            } else if (!isCharging) {
                hasNotified80 = false
            }
        }
    }
}
