package com.agentforge.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.service.AgentAccessibilityService
import java.util.Locale

class AgentEngine(private val context: Context, private val ai: AiClient, private val shizuku: ShizukuBridge) {
    suspend fun execute(command: String): String {
        val c = command.trim()
        val l = c.lowercase(Locale.getDefault())
        return when {
            l == "home" -> shizuku.run("home")
            l == "back" -> shizuku.run("back")
            l == "recent" || l == "recents" -> shizuku.run("recent")
            l.contains("play") && (l.contains("pause") || l.contains("music") || l.contains("video") || l.contains("reel") || l.contains("short")) -> shizuku.run("play_pause")
            l.startsWith("scroll up") -> AgentAccessibilityService.instance?.swipeVertical(-0.75f) ?: "Accessibility service is off."
            l.startsWith("scroll down") -> AgentAccessibilityService.instance?.swipeVertical(0.75f) ?: "Accessibility service is off."
            l.startsWith("open ") -> openNamedApp(c.removePrefix("open ").trim())
            l.startsWith("call ") -> callByName(c.removePrefix("call ").trim())
            else -> ai.ask("You are an Android agent. Respond concisely and give one safe, user-directed action plan for this command. Command: $c")
        }
    }
    private fun openNamedApp(name: String): String {
        val pm = context.packageManager
        val matches = pm.getInstalledApplications(0).filter { it.loadLabel(pm).toString().equals(name, true) }
        val app = matches.firstOrNull() ?: return "I couldn't find an installed app named $name."
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: return "No launch activity found for $name."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); return "Opened $name."
    }
    private fun callByName(name: String): String {
        val cr = context.contentResolver
        val cur = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null) ?: return "No contact found."
        val rows = buildList { cur.use { while (it.moveToNext()) add(it.getString(0) to it.getString(1)) } }.distinct()
        if (rows.isEmpty()) return "No contact found for $name."
        if (rows.size > 1) return rows.mapIndexed { i, p -> "${i + 1}. ${p.first}: ${p.second}" }.joinToString("\n") + "\nChoose a number, then confirm the call."
        val number = rows[0].second
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened the dialer for ${rows[0].first} ($number). Confirm the call there."
    }
}
