package com.agentforge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager

class CallStateReceiver : BroadcastReceiver() {
    companion object {
        var lastState = TelephonyManager.CALL_STATE_IDLE
        var incomingNumber: String? = null
        var isIncoming = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.intent.action.PHONE_STATE") {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (!number.isNullOrBlank()) {
                incomingNumber = number
            }

            var state = TelephonyManager.CALL_STATE_IDLE
            if (stateStr == TelephonyManager.EXTRA_STATE_RINGING) {
                state = TelephonyManager.CALL_STATE_RINGING
            } else if (stateStr == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                state = TelephonyManager.CALL_STATE_OFFHOOK
            }

            onCallStateChanged(context, state, incomingNumber ?: "Unknown Caller")
        }
    }

    private fun onCallStateChanged(context: Context, state: Int, number: String) {
        if (lastState == state) return

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                isIncoming = true
                incomingNumber = number
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (isIncoming) {
                    // Call uthayi gayi ya voicemail recording mode activate hua
                    startVoicemailRecording(context, number)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isIncoming) {
                    // Call khatam ho gayi - recording stop karo
                    stopVoicemailRecording(context)
                    isIncoming = false
                }
            }
        }
        lastState = state
    }

    private fun startVoicemailRecording(context: Context, callerNumber: String) {
        val intent = Intent(context, CallVoicemailService::class.java).apply {
            action = "START_RECORDING"
            putExtra("CALLER_NUMBER", callerNumber)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopVoicemailRecording(context: Context) {
        val intent = Intent(context, CallVoicemailService::class.java).apply {
            action = "STOP_RECORDING"
        }
        context.startService(intent)
    }
}
