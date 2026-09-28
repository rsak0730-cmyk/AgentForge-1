package com.agentforge.app.shizuku

import android.os.Process
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader

@Keep
class PrivilegedUserService : IPrivilegedActions.Stub() {
    override fun runAllowed(action: String, payload: String?): String {
        val cmd = when (action) {
            "home" -> arrayOf("cmd", "input", "keyevent", "KEYCODE_HOME")
            "back" -> arrayOf("cmd", "input", "keyevent", "KEYCODE_BACK")
            "recent" -> arrayOf("cmd", "input", "keyevent", "KEYCODE_APP_SWITCH")
            "play_pause" -> arrayOf("cmd", "input", "keyevent", "KEYCODE_MEDIA_PLAY_PAUSE")
            else -> return "DENIED: unsupported privileged action"
        }
        return try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            p.waitFor()
            if (p.exitValue() == 0) "OK" else "ERROR: ${out.take(300)}"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    override fun destroy() {
        Process.killProcess(Process.myPid())
    }
}
