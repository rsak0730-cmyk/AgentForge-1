package com.agentforge.app.automation

import android.content.Context
import android.content.Intent

class TermuxBridge(private val context: Context) {

    companion object {
        private const val TERMUX_SERVICE = "com.termux.service.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    }

    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun executeTermuxScript(scriptPath: String, arguments: Array<String> = emptyArray(), inBackground: Boolean = true): Boolean {
        if (!isTermuxInstalled()) return false

        return try {
            val intent = Intent().apply {
                setClassName("com.termux", TERMUX_SERVICE)
                action = ACTION_RUN_COMMAND
                putExtra(EXTRA_COMMAND_PATH, scriptPath)
                putExtra(EXTRA_ARGUMENTS, arguments)
                putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
                putExtra(EXTRA_BACKGROUND, inBackground)
            }
            context.startService(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
