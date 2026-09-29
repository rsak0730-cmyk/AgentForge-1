package com.agentforge.app.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.File

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

    // Dynamic self-healing runner: Writes python code and fires via bash script wrapper
    fun runDynamicPython(pythonCode: String, scriptTag: String = "agent_dynamic") {
        try {
            val baseDir = File(Environment.getExternalStorageDirectory(), "MiraScripts")
            if (!baseDir.exists()) baseDir.mkdirs()

            val pyFile = File(baseDir, "$scriptTag.py")
            pyFile.writeText(pythonCode)

            // Trigger Termux execution of python script
            executeScript("runner.sh", arrayOf(pyFile.absolutePath))
        } catch (_: Throwable) {}
    }
}
