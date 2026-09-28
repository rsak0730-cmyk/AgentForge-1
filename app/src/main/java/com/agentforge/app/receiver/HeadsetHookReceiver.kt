package com.agentforge.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import com.agentforge.app.service.AgentAccessibilityService
import com.agentforge.app.service.VoiceListenerService

class HeadsetHookReceiver : BroadcastReceiver() {

    companion object {
        private var clickCount = 0
        private var lastClickTime: Long = 0L
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action == Intent.ACTION_MEDIA_BUTTON) {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
            if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastClickTime < 600) {
                    clickCount++
                } else {
                    clickCount = 1
                }
                lastClickTime = now

                // Triple click on headset hook triggers Pocket Stealth Whisper mode
                if (clickCount >= 3) {
                    clickCount = 0
                    AgentAccessibilityService.instance?.let { service ->
                        service.triggerHeartbeatHaptic()
                        service.showIsland("🤫 Pocket Stealth Active")
                        service.speakDirectly("Pocket mode active. Whisper me boliye.")

                        val listenIntent = Intent(context, VoiceListenerService::class.java).apply {
                            action = VoiceListenerService.ACTION_START_LISTENING
                        }
                        context.startService(listenIntent)
                    }
                }
            }
        }
    }
}
