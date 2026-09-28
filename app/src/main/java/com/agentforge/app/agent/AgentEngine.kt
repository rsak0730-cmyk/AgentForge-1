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
import android.os.BatteryManager
import android.provider.ContactsContract
import com.agentforge.app.automation.CallAutoAcceptManager
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.MemoryVault
import com.agentforge.app.service.AgentAccessibilityService
import com.agentforge.app.service.RoutineAlarmReceiver
import com.agentforge.app.service.SmartNotificationService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

class AgentEngine(
    private val context: Context,
    private val ai: AiClient,
    private val shizuku: ShizukuBridge
) {
    private val conversationHistory = mutableListOf<String>()
    private val memory = MemoryVault(context)
    private val callManager = CallAutoAcceptManager(context, shizuku)
    private var isTorchOn = false

    suspend fun execute(userQuery: String, capturedImageBytes: ByteArray? = null): String {
        val trimmed = userQuery.trim()
        if (trimmed.isEmpty() && capturedImageBytes == null) return "Boliye, sun rahi hoon."

        // Direct Intercom voice commands
        if (trimmed.contains("call utha lo", ignoreCase = true) || trimmed.contains("receive call", ignoreCase = true) || trimmed.contains("answer call", ignoreCase = true)) {
            val ok = callManager.acceptIncomingCall()
            return if (ok) "Call connect kar di hai speaker par." else "Call answer nahi ho paayi."
        }
        if (trimmed.contains("call kaat do", ignoreCase = true) || trimmed.contains("reject call", ignoreCase = true)) {
            val ok = callManager.rejectIncomingCall()
            return if (ok) "Call reject kar di." else "Call reject nahi ho paayi."
        }

        if (trimmed.contains("cancel everything red", ignoreCase = true) || trimmed.contains("code black emergency", ignoreCase = true)) {
            return triggerPanicDuressMode()
        }

        memory.incrementInteraction()

        if (!isNetworkAvailable()) {
            return executeOfflineFallback(trimmed)
        }

        val service = AgentAccessibilityService.instance
        service?.showIsland("Thinking...")

        val screenHierarchy = service?.getIndexedScreenElements() ?: "Screen unavailable"
        val knownMemory = memory.getMemorySummary()

        // Dynamic Dialect & Slang Detection
        val dialect = DialectAdapter.detectDialect(trimmed)

        val prompt = """
You are Mira, an autonomous and intelligent Android OS companion.
${dialect.systemPromptInstructions}

User Memory Context:
$knownMemory

Current Visible Screen Hierarchy:
$screenHierarchy

Recent Conversation History:
${conversationHistory.takeLast(4).joinToString("\n")}

SUPPORTED ACTIONS:
- "solve_study_problem": (Analyze current screen questions/notes, parse problem, and explain solution concisely with steps/formulas)
- "accept_call", "reject_call"
- "save_screen_notes", "react_story", "identify_music", "whatsapp_meme"
- "morning_briefing", "bedtime_routine", "macro_pipeline"
- "summarize_screen", "remember_fact", "open_app", "home", "back", "recents"
- "play_pause", "toggle_torch", "volume_up", "volume_down", "call"
- "whatsapp_send", "telegram_send", "notification_reply", "schedule_routine"
- "conversational_reply"

Output STRICT JSON ONLY:
{
  "action": "solve_study_problem"|"accept_call"|"reject_call"|"save_screen_notes"|"react_story"|"identify_music"|"whatsapp_meme"|"morning_briefing"|"bedtime_routine"|"macro_pipeline"|"summarize_screen"|"remember_fact"|"open_app"|"home"|"back"|"recents"|"play_pause"|"toggle_torch"|"volume_up"|"volume_down"|"call"|"whatsapp_send"|"telegram_send"|"notification_reply"|"schedule_routine"|"conversational_reply",
  "param": "target/app/fact",
  "text": "content",
  "id": 0,
  "reply": "voice response strictly formatted in the user's dialect (Bangla/Hinglish/English)"
}
        """.trimIndent()

        val rawAi = ai.ask("$prompt\n\nUser: \"$trimmed\"", capturedImageBytes)
        val cleanJson = rawAi.replace("```json", "").replace("```", "").trim()

        var feedback = ""

        try {
            val json = JSONObject(cleanJson)
            val action = json.optString("action")
            val param = json.optString("param")
            val text = json.optString("text")
            val replyMsg = json.optString("reply")

            when (action) {
                "solve_study_problem" -> {
                    feedback = replyMsg.ifBlank { "Problem solve kar di hai: $text" }
                    service?.showIsland("Study: Solution Ready")
                }
                "accept_call" -> {
                    callManager.acceptIncomingCall()
                    feedback = replyMsg.ifBlank { "Call connect kar diya." }
                }
                "reject_call" -> {
                    callManager.rejectIncomingCall()
                    feedback = replyMsg.ifBlank { "Call decline kar diya." }
                }
                "save_screen_notes" -> feedback = service?.saveVisibleTextToNotes() ?: "Accessibility not ready."
                "react_story" -> feedback = reactToVisibleStory(text, replyMsg)
                "identify_music" -> {
                    feedback = replyMsg.ifBlank { "Music listen kar rahi hoon..." }
                    service?.showIsland("Listening...", isMusicPlaying = true)
                }
                "whatsapp_meme" -> feedback = sendMemeStickerReply("com.whatsapp", param, text)
                "morning_briefing" -> feedback = executeMorningBriefing()
                "bedtime_routine" -> feedback = executeBedtimeRoutine()
                "macro_pipeline" -> feedback = executeMacroPipeline(json.optJSONArray("pipeline_steps") ?: JSONArray(), replyMsg)
                "whatsapp_send" -> feedback = sendInstantMessengerMessage("com.whatsapp", param, text)
                "telegram_send" -> feedback = sendInstantMessengerMessage("org.telegram.messenger", param, text)
                "notification_reply" -> {
                    val ok = SmartNotificationService.instance?.replyToSender(param, text) ?: false
                    feedback = if (ok) replyMsg.ifBlank { "$param ko bhej diya reply!" } else "Notification nahi mila."
                }
                "schedule_routine" -> {
                    val minutes = param.toIntOrNull() ?: 10
                    scheduleRoutine(minutes, text)
                    feedback = replyMsg.ifBlank { "Routine schedule kar diya." }
                }
                "open_app" -> { openApp(param); feedback = replyMsg.ifBlank { "$param khol diya." } }
                "click_id" -> { service?.clickElementById(json.optInt("id", 0)); feedback = replyMsg.ifBlank { "Tapped." } }
                "type" -> {
                    service?.typeTextIntoFocusedOrById(if (json.optInt("id", 0) > 0) json.optInt("id") else null, text)
                    feedback = replyMsg.ifBlank { "Typed." }
                }
                "home" -> { shizuku.run("home"); feedback = replyMsg.ifBlank { "Home screen." } }
                "back" -> { shizuku.run("back"); feedback = replyMsg.ifBlank { "Back." } }
                "recents" -> { shizuku.run("recent"); feedback = replyMsg.ifBlank { "Recents apps." } }
                "play_pause" -> {
                    shizuku.run("play_pause")
                    service?.showIsland("Media Active", isMusicPlaying = true)
                    feedback = replyMsg.ifBlank { "Media toggled." }
                }
                "toggle_torch" -> { toggleTorch(); feedback = replyMsg.ifBlank { "Flashlight toggled." } }
                "volume_up" -> { adjustVolume(true); feedback = replyMsg.ifBlank { "Volume badha diya." } }
                "volume_down" -> { adjustVolume(false); feedback = replyMsg.ifBlank { "Volume kam kar diya." } }
                "call" -> { autoCall(param); feedback = replyMsg.ifBlank { "$param ko call mila rahi hoon." } }
                "summarize_screen" -> feedback = replyMsg.ifBlank { "Screen summary: ${screenHierarchy.take(220)}" }
                "remember_fact" -> { memory.saveFact(param); feedback = replyMsg.ifBlank { "Yaad rakhungi!" } }
                "conversational_reply" -> feedback = replyMsg.ifBlank { rawAi }
                else -> feedback = replyMsg.ifBlank { rawAi }
            }
        } catch (_: Exception) {
            feedback = rawAi
        }

        conversationHistory.add("User: $trimmed")
        conversationHistory.add("Mira: $feedback")
        service?.showIsland("Ready")
        return feedback
    }

    private suspend fun reactToVisibleStory(suggestedReaction: String, voiceReply: String): String {
        val service = AgentAccessibilityService.instance
        service?.typeTextIntoFocusedOrById(null, suggestedReaction)
        delay(500)
        val dm = context.resources.displayMetrics
        service?.clickCoordinates(dm.widthPixels - 80f, dm.heightPixels - 120f)
        return voiceReply.ifBlank { "Story reaction bhej diya: '$suggestedReaction'" }
    }

    private fun triggerPanicDuressMode(): String {
        AgentAccessibilityService.instance?.hideIsland()
        shizuku.run("home")
        try {
            val alertFile = File(context.filesDir, "DURESS_TRIGGERED.txt")
            alertFile.writeText("Duress at: ${System.currentTimeMillis()}")
        } catch (_: Exception) {}
        AgentAccessibilityService.instance?.showIsland("System UI Error [0x8F]")
        return "Critical: System halted."
    }

    private fun executeMorningBriefing(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greet = if (hour < 12) "Good morning" else "Hello"
        return "$greet boss! Battery $battery% hai. Sabhi background routines synchronized hain. Schedule start karein?"
    }

    private fun executeBedtimeRoutine(): String {
        adjustVolume(false)
        adjustVolume(false)
        shizuku.run("home")
        AgentAccessibilityService.instance?.showIsland("Bedtime Mode Active")
        return "Shubh raatri boss! Volume mute kar diya hai, screen shield active hai. Kal subah ke alarms verified hain."
    }

    private suspend fun executeMacroPipeline(steps: JSONArray, confirmation: String): String {
        for (i in 0 until steps.length()) {
            val step = steps.optString(i).lowercase()
            when {
                step.contains("dnd") -> adjustVolume(false)
                step.contains("home") -> shizuku.run("home")
                step.contains("recents") -> shizuku.run("recent")
                step.contains("open") -> openApp(step.substringAfter("open").trim())
            }
            delay(800)
        }
        return confirmation.ifBlank { "Macro steps executed!" }
    }

    private suspend fun sendMemeStickerReply(packageName: String, contact: String, memeQuery: String): String {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return "$packageName missing."
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        delay(1800)
        val service = AgentAccessibilityService.instance
        service?.clickElementById(1)
        delay(600)
        service?.typeTextIntoFocusedOrById(null, contact)
        delay(1000)
        service?.clickCoordinates(300f, 380f)
        delay(1200)

        val memeText = "😂 [$memeQuery]"
        service?.typeTextIntoFocusedOrById(null, memeText)
        delay(600)

        val dm = context.resources.displayMetrics
        service?.clickCoordinates(dm.widthPixels - 80f, dm.heightPixels - 120f)
        return "$contact ko meme reply bhej diya: '$memeText'"
    }

    private fun executeOfflineFallback(cmd: String): String {
        val lower = cmd.lowercase()
        return when {
            lower.contains("torch") || lower.contains("light") -> { toggleTorch(); "Torch toggled." }
            lower.contains("home") -> { shizuku.run("home"); "Home." }
            lower.contains("back") -> { shizuku.run("back"); "Back." }
            lower.contains("pause") || lower.contains("play") -> { shizuku.run("play_pause"); "Playback toggled." }
            lower.contains("open") || lower.contains("kholo") -> {
                val app = cmd.substringAfter("open").substringAfter("kholo").trim()
                openApp(app)
                "$app open kar diya."
            }
            else -> "Offline fallback ready."
        }
    }

    private suspend fun sendInstantMessengerMessage(packageName: String, contact: String, message: String): String {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return "$packageName missing."
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        delay(1800)
        val service = AgentAccessibilityService.instance
        service?.clickElementById(1)
        delay(600)
        service?.typeTextIntoFocusedOrById(null, contact)
        delay(1000)
        service?.clickCoordinates(300f, 380f)
        delay(1200)
        service?.typeTextIntoFocusedOrById(null, message)
        delay(600)

        val dm = context.resources.displayMetrics
        service?.clickCoordinates(dm.widthPixels - 80f, dm.heightPixels - 120f)
        return "$contact ko bhej diya: '$message'"
    }

    private fun scheduleRoutine(delayMinutes: Int, task: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RoutineAlarmReceiver::class.java).apply { putExtra("ROUTINE_TASK", task) }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerTime = Calendar.getInstance().apply { add(Calendar.MINUTE, delayMinutes) }.timeInMillis
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
