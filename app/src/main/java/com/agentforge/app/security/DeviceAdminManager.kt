package com.agentforge.app.security

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class AgentAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {}
    override fun onDisabled(context: Context, intent: Intent) {}
}

class DeviceAdminManager(private val context: Context) {
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, AgentAdminReceiver::class.java)

    fun isAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    fun requestAdminPermission(activityContext: Context) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Mira needs Device Admin privileges for panic emergency lock and policy enforcement."
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activityContext.startActivity(intent)
    }

    fun lockDeviceNow(): Boolean {
        return try {
            if (isAdminActive()) {
                dpm.lockNow()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
