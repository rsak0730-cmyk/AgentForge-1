package com.agentforge.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.agentforge.app.MainActivity
import com.agentforge.app.automation.PredictiveActionEngine
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
            private set
    }

    private var islandContainer: LinearLayout? = null
    private var islandTextView: TextView? = null
    private var visualizerBarView: TextView? = null
    private var windowManager: WindowManager? = null
    private val elementBoundsMap = mutableMapOf<Int, Pair<Float, Float>>()
    private var clipboardManager: ClipboardManager? = null
    private var lastClipText: String = ""
    private var waveAnimator: ValueAnimator? = null

    // Detox Guardian Engine State
    private var lastSocialPackage: String? = null
    private var socialStartTimeMs: Long = 0L
    private var hasWarned30Min = false
    private lateinit var shizukuBridge: ShizukuBridge
    private lateinit var predictiveEngine: PredictiveActionEngine

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardChange()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
        shizukuBridge = ShizukuBridge(this)
        predictiveEngine = PredictiveActionEngine(this)

        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        val prefs = AppPrefs(this)
        if (prefs.isIslandEnabled) {
            showIsland("Mira Core Active")
        }

        predictiveEngine.dispatchPreloadedIslandSuggestion()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Social Feed Detox Guardian Tracking
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            handleAppSwitchDetox(pkg)
        }
    }

    private fun handleAppSwitchDetox(currentPkg: String) {
        val isSocialApp = currentPkg.contains("instagram.android") || 
                          currentPkg.contains("youtube") || 
                          currentPkg.contains("tiktok")

        if (isSocialApp) {
            if (lastSocialPackage != currentPkg) {
                lastSocialPackage = currentPkg
                socialStartTimeMs = SystemClock.elapsedRealtime()
                hasWarned30Min = false
            } else {
                val elapsedMinutes = (SystemClock.elapsedRealtime() - socialStartTimeMs) / (1000 * 60)
                
                // 30 Minutes Soft Warning
                if (elapsedMinutes >= 30 && !hasWarned30Min) {
                    hasWarned30Min = true
                    showIsland("⏳ 30m on Reels. Break le lijiye!")
                }

                // 45 Minutes Hard Kickout via Privileged Navigation
                if (elapsedMinutes >= 45) {
                    showIsland("🛑 45m Limit Reached. Closing...")
                    shizukuBridge.run("home")
                    lastSocialPackage = null
                    socialStartTimeMs = 0L
                    hasWarned30Min = false
                }
            }
        } else {
            lastSocialPackage = null
            socialStartTimeMs = 0L
            hasWarned30Min = false
        }
    }

    private fun handleClipboardChange() {
        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).text?.toString()?.trim() ?: return

        if (text.isEmpty() || text == lastClipText) return
        lastClipText = text

        when {
            text.contains("youtube.com") || text.contains("youtu.be") -> showIsland("▶ YouTube Link Copied")
            text.startsWith("http://") || text.startsWith("https://") -> showIsland("🔗 Link Copied: Open?")
            text.matches(Regex("^[0-9+\\-*/. ()]+$")) && text.length > 2 -> showIsland("🔢 Math Copied: Calculate?")
            else -> showIsland("📋 Copied: ${text.take(18)}...")
        }
    }

    override fun onInterrupt() { showIsland("Paused") }

    override fun onDestroy() {
        waveAnimator?.cancel()
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        hideIsland()
        shizukuBridge.close()
        instance = null
        super.onDestroy()
    }

    // ---------------- DYNAMIC ISLAND WIDGET ENGINE ----------------

    fun updateIslandGeometry() {
        Handler(Looper.getMainLooper()).post {
            try {
                val prefs = AppPrefs(this)
                val view = islandContainer ?: return@post
                val wm = windowManager ?: return@post

                val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
                params.x = prefs.islandX.toInt()
                params.y = prefs.islandY.toInt()
                params.width = prefs.islandWidth.toInt()
                params.height = prefs.islandHeight.toInt()

                val shape = GradientDrawable().apply {
                    setColor(0xEE0B0D18.toInt())
                    cornerRadius = prefs.islandRadius
                    setStroke(2, 0xFF00E5FF.toInt())
                }
                view.background = shape
                wm.updateViewLayout(view, params)
            } catch (_: Throwable) {}
        }
    }

    fun showIsland(text: String, isMusicPlaying: Boolean = false, progressPct: Int = -1) {
        Handler(Looper.getMainLooper()).post {
            try {
                val prefs = AppPrefs(this)
                if (!prefs.isIslandEnabled) return@post

                val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm

                val shape = GradientDrawable().apply {
                    setColor(0xEE0B0D18.toInt())
                    cornerRadius = prefs.islandRadius
                    setStroke(2, 0xFF00E5FF.toInt())
                }

                if (islandContainer == null) {
                    val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            val intent = Intent(this@AgentAccessibilityService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(intent)
                            showIsland("Mic Activated")
                            return true
                        }
                        override fun onLongPress(e: MotionEvent) {
                            showIsland("Camera Vision Mode")
                        }
                        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                            if (Math.abs(velocityX) > 100) {
                                showIsland("Mira: Standby")
                                return true
                            }
                            return false
                        }
                    })

                    islandContainer = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(14, 4, 14, 4)
                        background = shape
                        setOnTouchListener { _, event ->
                            gestureDetector.onTouchEvent(event)
                            true
                        }
                    }

                    visualizerBarView = TextView(this).apply {
                        setTextColor(Color(0xFF00E5FF))
                        textSize = 10f
                        visibility = GONE
                        setPadding(0, 0, 8, 0)
                    }

                    islandTextView = TextView(this).apply {
                        setTextColor(Color.WHITE)
                        textSize = 11f
                        gravity = Gravity.CENTER
                    }

                    islandContainer?.addView(visualizerBarView)
                    islandContainer?.addView(islandTextView)

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
                    wm.addView(islandContainer, params)
                } else {
                    updateIslandGeometry()
                }

                if (isMusicPlaying) {
                    visualizerBarView?.visibility = VISIBLE
                    startVisualizerWaveAnimation()
                } else {
                    visualizerBarView?.visibility = GONE
                    waveAnimator?.cancel()
                }

                val displayText = if (progressPct in 0..100) "[$progressPct%] $text" else text
                islandTextView?.text = displayText
            } catch (_: Throwable) {}
        }
    }

    private fun startVisualizerWaveAnimation() {
        if (waveAnimator?.isRunning == true) return
        val frames = listOf(" ▃▅", "▃▅█", "▅█▃", "█▃ ", "▃ ▅")
        waveAnimator = ValueAnimator.ofInt(0, frames.size - 1).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val idx = anim.animatedValue as Int
                visualizerBarView?.text = frames[idx]
            }
            start()
        }
    }

    fun hideIsland() {
        Handler(Looper.getMainLooper()).post {
            try {
                waveAnimator?.cancel()
                islandContainer?.let { windowManager?.removeView(it) }
                islandContainer = null
                islandTextView = null
                visualizerBarView = null
            } catch (_: Throwable) {}
        }
    }

    // ---------------- SCREEN INTERACTION & GROUNDING ----------------

    fun saveVisibleTextToNotes(): String {
        val root = rootInActiveWindow ?: return "Screen content empty."
        val buffer = StringBuilder()
        extractAllNodeTexts(root, buffer)
        val extracted = buffer.toString().trim()
        if (extracted.isEmpty()) return "Screen par text nahi mila."

        return try {
            val dir = File(filesDir, "notes").apply { if (!exists()) mkdirs() }
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "Snip_$time.md")
            file.writeText("# Screen Capture - $time\n\n$extracted")
            "Saved into notes: ${file.name}"
        } catch (e: Exception) {
            "Note save error: ${e.message}"
        }
    }

    private fun extractAllNodeTexts(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty()) sb.append(text).append("\n")
        else if (!desc.isNullOrEmpty()) sb.append(desc).append("\n")

        for (i in 0 until node.childCount) {
            extractAllNodeTexts(node.getChild(i), sb)
        }
    }

    fun getIndexedScreenElements(): String {
        val root = rootInActiveWindow ?: return "Screen tree empty or locked."
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
}
