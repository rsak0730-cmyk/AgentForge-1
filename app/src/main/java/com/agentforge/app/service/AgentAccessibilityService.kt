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
    // Fast lookup map for element IDs to screen coordinates
    private val elementBoundsMap = mutableMapOf<Int, Pair<Float, Float>>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        showIsland("Agent OS: Active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { showIsland("Paused") }

    override fun onDestroy() {
        island?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        island = null
        instance = null
        super.onDestroy()
    }

    // Scans screen and returns an indexed semantic map of everything on display
    fun getIndexedScreenElements(): String {
        val root = rootInActiveWindow ?: return "Screen tree empty or locked."
        elementBoundsMap.clear()
        val elements = mutableListOf<String>()
        var counter = 1
        traverseNodes(root, elements, counter)
        return elements.joinToString("\n")
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?, list: MutableList<String>, counterRef: Int): Int {
        if (node == null) return counterRef
        var currentId = counterRef

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val isClickable = node.isClickable
        val isEditable = node.isEditable

        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || isEditable) {
            val label = when {
                !text.isNullOrEmpty() -> text
                !desc.isNullOrEmpty() -> desc
                else -> "InputField"
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val cx = rect.centerX().toFloat()
            val cy = rect.centerY().toFloat()

            if (rect.width() > 0 && rect.height() > 0) {
                elementBoundsMap[currentId] = Pair(cx, cy)
                list.add("[#$currentId] \"$label\" | Type: ${if (isEditable) "Input" else "Clickable"} | Coords: ($cx, $cy)")
                currentId++
            }
        }

        for (i in 0 until node.childCount) {
            currentId = traverseNodes(node.getChild(i), list, currentId)
        }
        return currentId
    }

    fun clickElementById(id: Int): Boolean {
        val coords = elementBoundsMap[id] ?: return false
        return clickCoordinates(coords.first, coords.second)
    }

    fun clickCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeTextIntoFocusedOrById(targetId: Int?, textToType: String): Boolean {
        if (targetId != null && targetId in elementBoundsMap) {
            clickElementById(targetId)
            Thread.sleep(300)
        }
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 250): Boolean {
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun showIsland(text: String) {
        Handler(Looper.getMainLooper()).post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (island == null) {
                island = TextView(this).apply {
                    setTextColor(0xFF00FFCC.toInt())
                    setBackgroundColor(0xF00A0C16.toInt())
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(32, 14, 32, 14)
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    90,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 16
                }
                try { wm.addView(island, params) } catch (_: Exception) {}
            }
            island?.text = text
        }
    }
}
