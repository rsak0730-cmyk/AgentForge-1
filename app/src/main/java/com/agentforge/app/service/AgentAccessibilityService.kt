package com.agentforge.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.agentforge.app.data.AppPrefs

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
            private set
    }

    private var island: TextView? = null
    private var windowManager: WindowManager? = null
    private val elementBoundsMap = mutableMapOf<Int, Pair<Float, Float>>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        showIsland("Agent OS Ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { showIsland("Paused") }

    override fun onDestroy() {
        island?.let { windowManager?.removeView(it) }
        island = null
        instance = null
        super.onDestroy()
    }

    fun updateIslandGeometry() {
        Handler(Looper.getMainLooper()).post {
            try {
                val prefs = AppPrefs(this)
                val view = island ?: return@post
                val wm = windowManager ?: return@post

                val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
                params.x = prefs.islandX.toInt()
                params.y = prefs.islandY.toInt()
                params.width = prefs.islandWidth.toInt()
                params.height = prefs.islandHeight.toInt()

                val shape = GradientDrawable().apply {
                    setColor(0xEE0B0D18.toInt())
                    cornerRadius = prefs.islandRadius
                    setStroke(2, 0xFF00FFCC.toInt())
                }
                view.background = shape

                wm.updateViewLayout(view, params)
            } catch (_: Throwable) {}
        }
    }

    fun showIsland(text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val prefs = AppPrefs(this)
                val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm

                val shape = GradientDrawable().apply {
                    setColor(0xEE0B0D18.toInt())
                    cornerRadius = prefs.islandRadius
                    setStroke(2, 0xFF00FFCC.toInt())
                }

                if (island == null) {
                    island = TextView(this).apply {
                        setTextColor(Color.WHITE)
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setPadding(12, 6, 12, 6)
                        background = shape
                    }
                    val params = WindowManager.LayoutParams(
                        prefs.islandWidth.toInt(),
                        prefs.islandHeight.toInt(),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        x = prefs.islandX.toInt()
                        y = prefs.islandY.toInt()
                    }
                    wm.addView(island, params)
                } else {
                    updateIslandGeometry()
                }
                island?.text = text
            } catch (_: Throwable) {}
        }
    }

    fun getIndexedScreenElements(): String {
        val root = rootInActiveWindow ?: return "Screen tree empty."
        elementBoundsMap.clear()
        val elements = mutableListOf<String>()
        traverseNodes(root, elements, 1)
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
            val label = if (!text.isNullOrEmpty()) text else (desc ?: "Input")
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val cx = rect.centerX().toFloat()
            val cy = rect.centerY().toFloat()

            if (rect.width() > 0 && rect.height() > 0) {
                elementBoundsMap[currentId] = Pair(cx, cy)
                list.add("[#$currentId] \"$label\" | ${if (isEditable) "Input" else "Clickable"} | ($cx, $cy)")
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
}
