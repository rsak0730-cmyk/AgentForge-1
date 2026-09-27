package com.agentforge.app.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.ContactsContract
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.service.AgentAccessibilityService
import com.agentforge.app.service.RoutineAlarmReceiver
import com.agentforge.app.service.SmartNotificationService
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Calendar

class AgentEngine(
    private val context: Context,
    private val ai: AiClient,
    private val shizuku: ShizukuBridge
) {
    private val history = mutableListOf<String>()
    private var isTorchOn = false

    suspend fun execute(userQuery: String, capturedImageBytes: ByteArray? = null): String {
        val trimmed = userQuery.trim()
        if (trimmed.isEmpty() && capturedImageBytes == null) return "Command dijiye."

        // 1. ON-DEVICE OFFLINE ENGINE FALLBACK
        if (!isNetworkAvailable()) {
            return executeOfflineFallback(trimmed)
        }

        val service = AgentAccessibilityService.instance
        service?.showIsland("Processing...")

        val screenHierarchy = service?.getIndexedScreenElements() ?: "Screen not accessible."

        val prompt = """
You are Mira, an ultra-advanced Autonomous Android Operator with Full OS Control.
Capabilities:
- Auto-Messaging: WhatsApp / Telegram chats, typing, sending.
- Quick Notifications: Direct reply via intercepted notifications.
- Schedule Routines: Set cron / alarm routines.
- Vision Analysis: Inspect captured camera images.
- System Actions: Shizuku privileged shell, accessibility clicks, volume, torch, calls.

Current Screen Elements:
$screenHierarchy

Recent History:
${history.takeLast(4).joinToString("\n")}

AVAILABLE ACTIONS:
- "whatsapp_send": (param: target contact, text: message content)
- "telegram_send": (param: target contact, text: message content)
- "notification_reply": (param: sender name, text: reply content)
- "schedule_routine": (param: minutes from now as integer e.g. "30", text: command to run)
- "open_app": (param: app name)
- "click_id": (id: integer ID)
- "type": (id: integer ID or 0, text: string)
- "home", "back", "recents", "play_pause", "toggle_torch"
- "call": (param: contact name)
- "done": (param: voice reply to user)
- "reply": (param: conversational text)

Output STRICT JSON ONLY:
{
  "action": "whatsapp_send"|"telegram_send"|"notification_reply"|"schedule_routine"|"open_app"|"click_id"|"type"|"home"|"back"|"recents"|"play_pause"|"toggle_torch"|"call"|"done"|"reply",
  "param": "contact/app/sender",
  "text": "message/routine content",
  "id": 0,
  "reply": "natural voice confirmation in user's language"
}
        """.trimIndent()

        val fullPrompt = "$prompt\n\nUser: \"$trimmed\""
        val rawAi = ai.ask(fullPrompt, capturedImageBytes)
        val cleanJson = rawAi.replace("```json", "").replace("```", "").trim()

        var feedback = ""

        try {
            val json = JSONObject(cleanJson)
            val action = json.optString("action")
            val param = json.optString("param")
            val text = json.optString("text")
            val replyMsg = json.optString("reply")

            when (action) {
                "whatsapp_send" -> {
                    feedback = sendInstantMessengerMessage("com.whatsapp", param, text)
                }
                "telegram_send" -> {
                    feedback = sendInstantMessengerMessage("org.telegram.messenger", param, text)
                }
                "notification_reply" -> {
                    val ok = SmartNotificationService.instance?.replyToSender(param, text) ?: false
                    feedback = if (ok) "$param ko direct reply bhej diya: '$text'" else "Active notification nahi mila."
                }
                "schedule_routine" -> {
                    val minutes = param.toIntOrNull() ?: 10
                    scheduleRoutine(minutes, text)
                    feedback = "$minutes minute baad routine schedule kar diya: '$text'"
                }
                "open_app" -> {
                    openApp(param)
                    feedback = replyMsg.ifBlank { "$param open kar diya." }
                }
                "click_id" -> {
                    service?.clickElementById(json.optInt("id", 0))
                    feedback = replyMsg.ifBlank { "Clicked element." }
                }
                "type" -> {
                    service?.typeTextIntoFocusedOrById(if (json.optInt("id", 0) > 0) json.optInt("id") else null, text)
                    feedback = replyMsg.ifBlank { "Text type kar diya." }
                }
                "home" -> { shizuku.run("home"); feedback = "Home screen." }
                "back" -> { shizuku.run("back"); feedback = "Wapas aa gaye." }
                "recents" -> { shizuku.run("recent"); feedback = "Recents open." }
                "play_pause" -> { shizuku.run("play_pause"); feedback = "Media toggled." }
                "toggle_torch" -> { toggleTorch(); feedback = "Torch toggled." }
                "call" -> { autoCall(param); feedback = "$param ko call lagaya." }
                "done", "reply" -> { feedback = replyMsg.ifBlank { param.ifBlank { rawAi } } }
                else -> { feedback = rawAi }
            }
        } catch (_: Exception) {
            feedback = rawAi
        }

        history.add("User: $trimmed")
        history.add("Agent: $feedback")
        service?.showIsland("Ready")
        return feedback
    }

    // ---------------- OFFLINE RULE & INTENT ENGINE ----------------
    private fun executeOfflineFallback(cmd: String): String {
        val lower = cmd.lowercase()
        return when {
            lower.contains("torch") || lower.contains("flashlight") -> {
                toggleTorch()
                "Offline Mode: Torch toggle kar diya."
            }
            lower.contains("home") || lower.contains("screen") -> {
                shizuku.run("home")
                "Offline Mode: Home screen."
            }
            lower.contains("back") -> {
                shizuku.run("back")
                "Offline Mode: Back."
            }
            lower.contains("pause") || lower.contains("play") -> {
                shizuku.run("play_pause")
                "Offline Mode: Playback toggled."
            }
            lower.contains("volume up") || lower.contains("aawaz badhao") -> {
                adjustVolume(true)
                "Offline Mode: Volume badha diya."
            }
            lower.contains("volume down") || lower.contains("aawaz kam") -> {
                adjustVolume(false)
                "Offline Mode: Volume kam kar diya."
            }
            lower.contains("open") || lower.contains("kholo") -> {
                val app = cmd.substringAfter("open").substringAfter("kholo").trim()
                openApp(app)
                "Offline Mode: $app open kar raha hoon."
            }
            lower.contains("call") -> {
                val contact = cmd.substringAfter("call").trim()
                autoCall(contact)
                "Offline Mode: $contact ko call mila raha hoon."
            }
            else -> "Internet offline hai. Basic commands bole jaise torch, volume, home, call."
        }
    }

    private suspend fun sendInstantMessengerMessage(packageName: String, contact: String, message: String): String {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return "$packageName installed nahi hai."
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        delay(1800)
        val service = AgentAccessibilityService.instance

        // Search contact
        service?.clickElementById(1) // Usually search icon
        delay(600)
        service?.typeTextIntoFocusedOrById(null, contact)
        delay(1000)

        // Tap first matched contact
        service?.clickCoordinates(300f, 380f)
        delay(1200)

        // Type message & send
        service?.typeTextIntoFocusedOrById(null, message)
        delay(600)

        val sent = service?.clickElementById(0) ?: false
        if (!sent) {
            val dm = context.resources.displayMetrics
            service?.clickCoordinates(dm.widthPixels - 80f, dm.heightPixels - 120f)
        }

        return "$contact ko message bhej diya: '$message'"
    }

    private fun scheduleRoutine(delayMinutes: Int, task: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RoutineAlarmReceiver::class.java).apply {
            putExtra("ROUTINE_TASK", task)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerTime = Calendar.getInstance().apply {
            add(Calendar.MINUTE, delayMinutes)
        }.timeInMillis

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun adjustVolume(increase: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun toggleTorch() {
        try {
            val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cam.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn
            cam.setTorchMode(id, isTorchOn)
        } catch (_: Exception) {}
    }

    private fun openApp(name: String) {
        val pm = context.packageManager
        val app = pm.getInstalledApplications(0).firstOrNull {
            it.loadLabel(pm).toString().contains(name, true)
        } ?: return
        val intent = pm.getLaunchIntentForPackage(app.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
        context.startActivity(intent)
    }

    private fun autoCall(name: String) {
        val cur = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        ) ?: return
        var num: String? = null
        cur.use { if (it.moveToNext()) num = it.getString(0) }
        num?.let {
            val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(it)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }
    }
}
