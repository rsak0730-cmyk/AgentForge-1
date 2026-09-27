package com.agentforge.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.service.AgentAccessibilityService
import org.json.JSONObject

class AgentEngine(
    private val context: Context,
    private val ai: AiClient,
    private val shizuku: ShizukuBridge
) {

    suspend fun execute(userMessage: String): String {
        val trimmed = userMessage.trim()
        if (trimmed.isEmpty()) return "Kuch boliye ya type kijiye."

        val service = AgentAccessibilityService.instance
        val screenContext = service?.getScreenUiHierarchy() ?: "Screen access inactive or no elements visible."

        val systemPrompt = """
You are an intelligent, multilingual Android OS Agent.
The user will talk to you in any language (English, Hindi, Bengali, Hinglish, slang, casual speech).
Your job is to understand the user's TRUE INTENT from the conversation and decide whether it requires a physical device action or a conversational reply.

Current Active Screen UI Elements:
$screenContext

AVAILABLE ACTIONS:
1. "home": Go to device home screen.
2. "back": Go back to previous screen.
3. "recents": Open recent apps overview.
4. "play_pause": Toggle music/video playback.
5. "scroll_down": Scroll down the active feed or page.
6. "scroll_up": Scroll up the active feed or page.
7. "open_app": Open an application by name (specify "target").
8. "call": Open dialer/call contact by name or number (specify "target").
9. "click": Tap an element on screen using visible text or content description (specify "target").
10. "type": Input text into an active or targeted textfield (specify "target" and "text").
11. "reply": When the user is asking a question, making small talk, or no system action is required.

RULES:
- Handle multilingual requests naturally (e.g. "Ghar chalo" / "Bari jao" -> home, "Gaan bondho koro" / "Gaana rok do" -> play_pause, "Call lagao Rohit ko" -> call target "Rohit").
- If the intent is conversational, set action to "reply" and answer in the SAME language the user used.
- ALWAYS respond with STRICT JSON ONLY. No markdown, no triple backticks.

JSON FORMAT:
{
  "action": "home" | "back" | "recents" | "play_pause" | "scroll_down" | "scroll_up" | "open_app" | "call" | "click" | "type" | "reply",
  "target": "app name, contact name, or UI element",
  "text": "text to type if action is type",
  "reply": "friendly natural response in user's language confirming the action or answering their query"
}
        """.trimIndent()

        val fullPrompt = "$systemPrompt\n\nUser input: \"$trimmed\""
        val rawAi = ai.ask(fullPrompt)
        val cleanJson = rawAi.replace("```json", "").replace("```", "").trim()

        return try {
            val json = JSONObject(cleanJson)
            val action = json.optString("action", "reply")
            val target = json.optString("target", "")
            val text = json.optString("text", "")
            val replyMsg = json.optString("reply", "")

            when (action) {
                "home" -> {
                    shizuku.run("home")
                    if (replyMsg.isNotBlank()) replyMsg else "Home screen par aa gaye."
                }
                "back" -> {
                    shizuku.run("back")
                    if (replyMsg.isNotBlank()) replyMsg else "Back kiya."
                }
                "recents" -> {
                    shizuku.run("recent")
                    if (replyMsg.isNotBlank()) replyMsg else "Recent apps open kar diye."
                }
                "play_pause" -> {
                    shizuku.run("play_pause")
                    if (replyMsg.isNotBlank()) replyMsg else "Media playback toggle kar diya."
                }
                "scroll_down" -> {
                    service?.swipeVertical(0.75f)
                    if (replyMsg.isNotBlank()) replyMsg else "Neeche scroll kiya."
                }
                "scroll_up" -> {
                    service?.swipeVertical(-0.75f)
                    if (replyMsg.isNotBlank()) replyMsg else "Upar scroll kiya."
                }
                "open_app" -> {
                    val res = openNamedApp(target)
                    if (replyMsg.isNotBlank()) "$replyMsg ($res)" else res
                }
                "call" -> {
                    val res = callByName(target)
                    if (replyMsg.isNotBlank()) "$replyMsg ($res)" else res
                }
                "click" -> {
                    val clicked = service?.clickElementByText(target) ?: false
                    if (clicked) {
                        if (replyMsg.isNotBlank()) replyMsg else "'$target' click kar diya."
                    } else {
                        "Screen par '$target' nahi mila click karne ke liye."
                    }
                }
                "type" -> {
                    val typed = service?.typeTextIntoFocusedOrTarget(target, text) ?: false
                    if (typed) {
                        if (replyMsg.isNotBlank()) replyMsg else "'$text' type kar diya."
                    } else {
                        "Text input field nahi mila."
                    }
                }
                "reply" -> {
                    if (replyMsg.isNotBlank()) replyMsg else rawAi
                }
                else -> {
                    if (replyMsg.isNotBlank()) replyMsg else rawAi
                }
            }
        } catch (_: Exception) {
            // Agar model kabhi JSON syntax miss kare toh fallback plain reply
            ai.ask(trimmed)
        }
    }

    private fun openNamedApp(name: String): String {
        if (name.isBlank()) return "Kaunsa app kholna hai?"
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val app = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, ignoreCase = true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            ?: return "App '$name' nahi mila."

        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?: return "'$name' ke liye launch activity nahi mili."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "$name open kar diya."
    }

    private fun callByName(name: String): String {
        if (name.isBlank()) return "Kisko call lagana hai?"
        val cr = context.contentResolver
        val cur = cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        ) ?: return "Contacts access nahi ho sake."

        val matches = mutableListOf<Pair<String, String>>()
        cur.use {
            while (it.moveToNext()) {
                matches.add(it.getString(0) to it.getString(1))
            }
        }

        if (matches.isEmpty()) return "'$name' naam ka koi contact nahi mila."

        val contact = matches.first()
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contact.second)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(dialIntent)
        return "${contact.first} (${contact.second}) ke liye dialer open kiya."
    }
}
