package com.agentforge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.TelephonyManager

class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"
        val callerName = resolveContactName(context, incomingNumber)

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                AgentAccessibilityService.instance?.showIsland("Incoming: $callerName")

                // Start Intelligent Multilingual Butler Screening Service
                val butlerIntent = Intent(context, CallButlerService::class.java).apply {
                    putExtra("CALLER_NAME", callerName)
                    putExtra("CALLER_NUMBER", incomingNumber)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(butlerIntent)
                } else {
                    context.startService(butlerIntent)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                context.stopService(Intent(context, CallButlerService::class.java))
                context.stopService(Intent(context, CallVoicemailService::class.java))
                AgentAccessibilityService.instance?.showIsland("Mira: Ready")
            }
        }
    }

    private fun resolveContactName(context: Context, number: String): String {
        if (number == "Unknown") return "Unknown Caller"
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            var name = number
            cursor?.use {
                if (it.moveToFirst()) {
                    name = it.getString(0)
                }
            }
            name
        } catch (_: Exception) {
            number
        }
    }
}
