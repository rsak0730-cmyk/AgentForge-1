package com.agentforge.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Path
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.graphics.PixelFormat
import android.view.Gravity
import android.widget.TextView
import android.content.Context

class AgentAccessibilityService : AccessibilityService() {
    companion object { var instance: AgentAccessibilityService? = null; private set }
    private var island: TextView? = null
    override fun onServiceConnected() {
        super.onServiceConnected(); instance = this
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        showIsland("Agent ready")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) showIsland("Watching ${event.packageName ?: "screen"}") }
    override fun onInterrupt() { showIsland("Paused") }
    override fun onDestroy() { island?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }; island = null; instance = null; super.onDestroy() }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) { showIsland("Listening toggle"); return true }
        return super.onKeyEvent(event)
    }
    fun swipeVertical(fraction: Float): String {
        val dm = resources.displayMetrics; val x = dm.widthPixels / 2f; val y1 = dm.heightPixels * (if (fraction > 0) .3f else .7f); val y2 = dm.heightPixels * (if (fraction > 0) .7f else .3f)
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 450)).build()
        dispatchGesture(g, null, null); return "Scrolled ${if (fraction > 0) "down" else "up"}."
    }
    fun typeText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }
    private fun showIsland(text: String) {
        runOnUiThread {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (island == null) {
                island = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xDD090A12.toInt()); textSize = 12f; gravity = Gravity.CENTER; setPadding(24, 8, 24, 8) }
                val params = WindowManager.LayoutParams(360, 48, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 16 }
                wm.addView(island, params)
            }
            island?.text = text
        }
    }
    private fun runOnUiThread(block: () -> Unit) { android.os.Handler(mainLooper).post(block) }
}
