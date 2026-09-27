package com.agentforge.app.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class SmartNotificationService : NotificationListenerService() {

    companion object {
        var instance: SmartNotificationService? = null
            private set
        val lastReceivedMessages = mutableMapOf<String, Notification.Action>()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // Intercept WhatsApp, Telegram, SMS
        if (pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messaging")) {
            val extras = sbn.notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: return
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

            val wearableExtender = Notification.WearableExtender(sbn.notification)
            val actions = wearableExtender.actions.ifEmpty { sbn.notification.actions?.toList() ?: emptyList() }

            for (action in actions) {
                if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                    lastReceivedMessages[title.lowercase()] = action
                    AgentAccessibilityService.instance?.showIsland("$title: $text")
                    break
                }
            }
        }
    }

    fun replyToSender(senderName: String, replyMessage: String): Boolean {
        val entry = lastReceivedMessages.entries.firstOrNull {
            it.key.contains(senderName.lowercase())
        } ?: return false

        val action = entry.value
        val remoteInputs = action.remoteInputs ?: return false
        val intent = Intent()
        val bundle = Bundle()

        for (input in remoteInputs) {
            bundle.putCharSequence(input.resultKey, replyMessage)
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

        return try {
            action.actionIntent.send(this, 0, intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
