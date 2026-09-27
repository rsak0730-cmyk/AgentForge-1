package com.agentforge.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
            private set
    }

    private var island: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        showIsland("Agent Ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: "App"
            showIsland("Active: $pkg")
        }
    }

    override fun onInterrupt() {
        showIsland("Agent Paused")
    }

    override fun onDestroy() {
        island?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        island = null
        instance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            showIsland("Voice Trigger")
            return true
        }
        return super.onKeyEvent(event)
    }

    fun getScreenUiHierarchy(): String {
        val root = rootInActiveWindow ?: return "[]"
        val elements = mutableListOf<String>()
        traverseNodes(root, elements)
        return elements.joinToString("\n")
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val isClickable = node.isClickable
        val isEditable = node.isEditable

        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
            val label = if (!text.isNullOrEmpty()) text else desc
            val rect = Rect()
            node.getBoundsInScreen(rect)
            list.add("Element: \"$label\" | Clickable: $isClickable | Editable: $isEditable | Bounds: [${rect.centerX()}, ${rect.centerY()}]")
        }

        for (i in 0 until node.childCount) {
            traverseNodes(node.getChild(i), list)
        }
    }

    fun clickElementByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(targetText)
        for (node in nodes) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                parent = parent.parent
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (clickCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())) {
                return true
            }
        }
        return false
    }

    fun clickCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeTextIntoFocusedOrTarget(targetText: String?, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var targetNode: AccessibilityNodeInfo? = null

        if (!targetText.isNullOrBlank()) {
            val nodes = root.findAccessibilityNodeInfosByText(targetText)
            targetNode = nodes.firstOrNull { it.isEditable || it.isFocusable }
        }

        if (targetNode == null) {
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        targetNode?.let {
            val bundle = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            val res = it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (res) return true
        }

        return false
    }

    fun swipeVertical(fraction: Float): String {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * (if (fraction > 0) 0.7f else 0.3f)
        val y2 = dm.heightPixels * (if (fraction > 0) 0.3f else 0.7f)
        val path = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
        return if (fraction > 0) "Scrolled down" else "Scrolled up"
    }

    fun showIsland(text: String) {
        Handler(Looper.getMainLooper()).post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (island == null) {
                island = TextView(this).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xDD0B0C15.toInt())
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(24, 8, 24, 8)
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    80,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 20
                }
                try {
                    wm.addView(island, params)
                } catch (_: Exception) {}
            }
            island?.text = text
        }
    }
}
