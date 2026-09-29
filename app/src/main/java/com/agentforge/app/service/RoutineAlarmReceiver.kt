package com.agentforge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agentforge.app.agent.AgentEngine
import com.agentforge.app.agent.AiClient
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val routineTask = intent.getStringExtra("ROUTINE_TASK") ?: return
        val prefs = AppPrefs(context)
        val shizuku = ShizukuBridge(context)
        val engine = AgentEngine(context, AiClient(context, prefs), shizuku)
        AgentAccessibilityService.instance?.showIsland("Cron: $routineTask")
        CoroutineScope(Dispatchers.Main).launch {
            engine.execute(routineTask)
        }
    }
}
