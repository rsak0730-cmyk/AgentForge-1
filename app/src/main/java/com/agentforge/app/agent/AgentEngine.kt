package com.agentforge.app.agent

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.service.AgentAccessibilityService
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

class AgentEngine(
    private val context: Context,
    private val ai: AiClient,
    private val shizuku: ShizukuBridge
) {
    private val history = mutableListOf<String>()
    private var isTorchOn = false

    suspend fun execute(userQuery: String): String {
        val trimmed = userQuery.trim()
        if (trimmed.isEmpty()) return "Main sun raha hoon, command dijiye."

        val service = AgentAccessibilityService.instance
        service?.showIsland("Thinking...")

        // Max autonomous steps limit to prevent infinite loops
        val maxSteps = 8
        var currentStep = 0
        var taskCompleted = false
        var finalFeedback = ""

        while (currentStep < maxSteps && !taskCompleted) {
            currentStep++
            val screenHierarchy = service?.getIndexedScreenElements() ?: "No screen access."

            val prompt = """
You are the world's most capable Autonomous Android Agent (Mira).
Goal: Complete the user's task on Android completely hands-free.

User Task: "$trimmed"
Step Number: $currentStep / $maxSteps

Visible Screen Elements with Indexed IDs:
$screenHierarchy

Conversation Memory:
${history.takeLast(4).joinToString("\n")}

AVAILABLE ACTIONS:
- "open_app": (param: package/app name) Launch application
- "click_id": (param: integer ID like 2) Tap the indexed element
- "click_coords": (x: float, y: float) Click exact pixel
- "type": (id: integer ID or 0 for active, text: string) Type text
- "scroll_down" / "scroll_up"
- "home" / "back" / "recents" / "play_pause" / "toggle_torch"
- "call": (param: contact name)
- "read_screen": (param: text summary of what is seen) Read screen content out loud
- "done": (param: completion message in user's spoken language) Task finished successfully
- "reply": (param: conversational reply in user's language) For normal conversation

DECISION RULES:
1. Always analyze if current screen needs an app launch or an element click to reach the goal.
2. If unexpected popups/ads appear, close them or click dismiss.
3. If the user asks a question about the screen, use "read_screen" or "done".
4. When finished, call "done".

OUTPUT FORMAT: Return STRICT VALID JSON ONLY (No markdown, no triple backticks):
{
  "action": "open_app"|"click_id"|"click_coords"|"type"|"scroll_down"|"scroll_up"|"home"|"back"|"recents"|"play_pause"|"toggle_torch"|"call"|"read_screen"|"done"|"reply",
  "param": "string param or text",
  "id": 0,
  "x": 0.0,
  "y": 0.0,
  "reason": "short explanation of why this step was taken"
}
            """.trimIndent()

            val raw = ai.ask(prompt)
            val clean = raw.replace("```json", "").replace("```", "").trim()

            try {
                val stepJson = JSONObject(clean)
                val action = stepJson.optString("action")
                val param = stepJson.optString("param")
                val id = stepJson.optInt("id", 0)
                val x = stepJson.optDouble("x", 0.0).toFloat()
                val y = stepJson.optDouble("y", 0.0).toFloat()
                val reason = stepJson.optString("reason")

                service?.showIsland("Step $currentStep: $action")

                when (action) {
                    "open_app" -> openApp(param)
                    "click_id" -> service?.clickElementById(id)
                    "click_coords" -> service?.clickCoordinates(x, y)
                    "type" -> service?.typeTextIntoFocusedOrById(if (id > 0) id else null, param)
                    "scroll_down" -> {
                        val dm = context.resources.displayMetrics
                        service?.swipe(dm.widthPixels / 2f, dm.heightPixels * 0.75f, dm.widthPixels / 2f, dm.heightPixels * 0.25f)
                    }
                    "scroll_up" -> {
                        val dm = context.resources.displayMetrics
                        service?.swipe(dm.widthPixels / 2f, dm.heightPixels * 0.25f, dm.widthPixels / 2f, dm.heightPixels * 0.75f)
                    }
                    "home" -> shizuku.run("home")
                    "back" -> shizuku.run("back")
                    "recents" -> shizuku.run("recent")
                    "play_pause" -> shizuku.run("play_pause")
                    "toggle_torch" -> toggleTorch()
                    "call" -> autoCall(param)
                    "read_screen", "done", "reply" -> {
                        finalFeedback = param.ifBlank { reason }
                        taskCompleted = true
                    }
                }
                delay(1200) // Screen rendering & animation wait
            } catch (_: Exception) {
                finalFeedback = ai.ask(trimmed)
                taskCompleted = true
            }
        }

        if (finalFeedback.isBlank()) {
            finalFeedback = "Task execute kar diya hai."
        }

        history.add("User: $trimmed")
        history.add("Agent: $finalFeedback")
        service?.showIsland("Ready")
        return finalFeedback
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

    private fun toggleTorch() {
        try {
            val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cam.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn
            cam.setTorchMode(id, isTorchOn)
        } catch (_: Exception) {}
    }
}
