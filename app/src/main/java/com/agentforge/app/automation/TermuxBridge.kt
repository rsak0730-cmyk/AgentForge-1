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

    // Dynamic Python Script Runner
    fun runDynamicPython(pythonCode: String, scriptTag: String = "agent_dynamic") {
        try {
            val baseDir = File(Environment.getExternalStorageDirectory(), "MiraScripts")
            if (!baseDir.exists()) baseDir.mkdirs()

            val pyFile = File(baseDir, "$scriptTag.py")
            pyFile.writeText(pythonCode)

            executeScript("runner.sh", arrayOf(pyFile.absolutePath))
        } catch (_: Throwable) {}
    }

    // Media Engine: yt-dlp & ffmpeg trigger for video/audio downloading
    fun downloadMedia(url: String, extractAudioOnly: Boolean = false) {
        val audioFlag = if (extractAudioOnly) "audio" else "video"
        executeScript("download_media.sh", arrayOf(url, audioFlag))
    }

    // Headless Live Web Scraper Search
    fun triggerWebSearch(query: String) {
        val pythonSearchCode = """
            import urllib.request
            import json
            import urllib.parse
            
            q = urllib.parse.quote("$query")
            url = f"https://html.duckduckgo.com/html/?q={q}"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            try:
                html = urllib.request.urlopen(req, timeout=5).read().decode('utf-8')
                with open('/sdcard/mira_search_result.txt', 'w') as f:
                    f.write(html[:1000])
            except Exception as e:
                pass
        """.trimIndent()
        runDynamicPython(pythonSearchCode, "search_job")
    }
}
