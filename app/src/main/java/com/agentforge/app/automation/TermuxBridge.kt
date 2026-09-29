package com.agentforge.app.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent

class TermuxBridge(private val context: Context) {

    fun executeScript(scriptName: String, args: Array<String> = emptyArray()) {
        try {
            val intent = Intent().apply {
                component = ComponentName("com.termux.tasker", "com.termux.tasker.ExecutionService")
                action = "com.termux.tasker.ACTION_EXECUTE"
                putExtra("com.termux.tasker.extra.EXECUTABLE", scriptName)
                putExtra("com.termux.tasker.extra.ARGUMENTS", args)
                putExtra("com.termux.tasker.extra.TERMINAL_SESSION", false)
            }
            context.startService(intent)
        } catch (_: Throwable) {}
    }
}
