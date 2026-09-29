package com.agentforge.app.automation

import android.content.Context
import android.os.Build
import android.telecom.TelecomManager
import com.agentforge.app.service.AgentAccessibilityService

class CallAutoAcceptManager(private val context: Context, private val shizuku: ShizukuBridge) {

    fun acceptIncomingCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                tm?.acceptRingingCall()
                AgentAccessibilityService.instance?.showIsland("Call Connected")
                return true
            } catch (_: SecurityException) {}
        }
        return try {
            shizuku.run("input keyevent KEYCODE_HEADSETHOOK")
            AgentAccessibilityService.instance?.showIsland("Call Answered via Shizuku")
            true
        } catch (_: Exception) {
            false
        }
    }

    fun rejectIncomingCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                tm?.endCall()
                AgentAccessibilityService.instance?.showIsland("Call Declined")
                return true
            } catch (_: SecurityException) {}
        }
        return try {
            shizuku.run("input keyevent KEYCODE_ENDCALL")
            true
        } catch (_: Exception) {
            false
        }
    }
}
