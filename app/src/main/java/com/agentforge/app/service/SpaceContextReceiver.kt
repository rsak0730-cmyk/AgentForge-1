package com.agentforge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.agentforge.app.automation.ShizukuBridge

class SpaceContextReceiver : BroadcastReceiver() {

    companion object {
        private var lastConnectedSsid: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shizuku = ShizukuBridge(context)

        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (isWifi) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val currentSsid = info.ssid.replace("\"", "")

            if (currentSsid != "<unknown ssid>" && currentSsid != lastConnectedSsid) {
                lastConnectedSsid = currentSsid
                handleWifiConnectedSpace(currentSsid, am, context)
            }
        } else {
            if (lastConnectedSsid != null) {
                // Stepped outside / Wi-Fi disconnected
                lastConnectedSsid = null
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                AgentAccessibilityService.instance?.showIsland("Outdoor Space: Sound Normal")
            }
        }
    }

    private fun handleWifiConnectedSpace(ssid: String, am: AudioManager, context: Context) {
        val lower = ssid.lowercase()
        when {
            lower.contains("school") || lower.contains("college") || lower.contains("office") || lower.contains("study") -> {
                am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                AgentAccessibilityService.instance?.showIsland("Campus Mode: Auto-Vibrate")
            }
            lower.contains("home") -> {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                AgentAccessibilityService.instance?.showIsland("Home Space: Relaxed Profile")
            }
            else -> {
                AgentAccessibilityService.instance?.showIsland("Connected: $ssid")
            }
        }
    }
}
