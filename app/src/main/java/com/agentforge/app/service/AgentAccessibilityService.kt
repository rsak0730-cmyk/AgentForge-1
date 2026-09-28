package com.agentforge.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
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

    private var islandRoot: FrameLayout? = null
    private var borderTrailView: IslandBorderTrailView? = null
    private var islandContainer: LinearLayout? = null
    private var islandTextView: TextView? = null
    private var visualizerBarView: TextView? = null
    private var windowManager: WindowManager? = null
    private val elementBoundsMap = mutableMapOf<Int, Pair<Float, Float>>()
    private var clipboardManager: ClipboardManager? = null
    private var lastClipText: String = ""
    private var waveAnimator: ValueAnimator? = null

    private var lastSocialPackage: String? = null
    private var socialStartTimeMs: Long = 0L
    private var hasWarned30Min = false
    private lateinit var shizukuBridge: ShizukuBridge
    private lateinit var predictiveEngine: PredictiveActionEngine

    // ---------------- DUAL VOLUME KEY (UP + DOWN) CHORD TRIGGER ----------------
    private val keyHandler = Handler(Looper.getMainLooper())
    private var isVolumeUpPressed = false
    private var isVolumeDownPressed = false
    private var isChordHoldTriggered = false
    private var isListeningActive = false

    private val chordHoldRunnable = Runnable {
        if (isVolumeUpPressed && isVolumeDownPressed) {
            isChordHoldTriggered = true
            isListeningActive = true
            triggerHaptic(true)
            showIsland("🎙️ Listening Mode ON")

            val intent = Intent(this, VoiceListenerService::class.java).apply {
                action = VoiceListenerService.ACTION_START_LISTENING
            }
            startService(intent)
        }
    }

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

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        val keyCode = event.keyCode
        val action = event.action

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                when (action) {
                    KeyEvent.ACTION_DOWN -> {
                        isVolumeUpPressed = true
                        // Check if both keys are now pressed simultaneously
                        if (isVolumeDownPressed && !isChordHoldTriggered) {
                            keyHandler.removeCallbacks(chordHoldRunnable)
                            keyHandler.postDelayed(chordHoldRunnable, 2500)
                            return true
                        }
                        return isListeningActive
                    }
                    KeyEvent.ACTION_UP -> {
                        isVolumeUpPressed = false
                        keyHandler.removeCallbacks(chordHoldRunnable)

                        // 1-Click to Turn OFF when listening is active
                        if (isListeningActive && !isChordHoldTriggered) {
                            isListeningActive = false
                            triggerHaptic(false)
                            showIsland("🔇 Mic OFF")

                            val intent = Intent(this, VoiceListenerService::class.java).apply {
                                action = VoiceListenerService.ACTION_STOP_LISTENING
                            }
                            startService(intent)
                            return true
                        }

                        if (!isVolumeDownPressed) {
                            isChordHoldTriggered = false
                        }
                        return false
                    }
                }
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                when (action) {
                    KeyEvent.ACTION_DOWN -> {
                        isVolumeDownPressed = true
                        // Check if both keys are now pressed simultaneously
                        if (isVolumeUpPressed && !isChordHoldTriggered) {
                            keyHandler.removeCallbacks(chordHoldRunnable)
                            keyHandler.postDelayed(chordHoldRunnable, 2500)
                            return true
                        }
                        return isListeningActive
                    }
                    KeyEvent.ACTION_UP -> {
                        isVolumeDownPressed = false
                        keyHandler.removeCallbacks(chordHoldRunnable)
                        if (!isVolumeUpPressed) {
                            isChordHoldTriggered = false
                        }
                        return false
                    }
                }
            }
        }
        return super.onKeyEvent(event)
    }

    private fun triggerHaptic(isStart: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val effect = if (isStart) {
                    VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 140), -1)
                } else {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                }
                vm.defaultVibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (isStart) v.vibrate(140) else v.vibrate(35)
            }
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
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
                if (elapsedMinutes >= 30 && !hasWarned30Min) {
                    hasWarned30Min = true
                    showIsland("⏳ 30m on Reels. Break lijiye!")
                }
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
        keyHandler.removeCallbacksAndMessages(null)
        borderTrailView?.stopAnimation()
        waveAnimator?.cancel()
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        hideIsland()
        shizukuBridge.close()
        instance = null
        super.onDestroy()
    }

    // ---------------- DYNAMIC ISLAND ENGINE (TOUCH INTERACTIVE) ----------------

    fun updateIslandGeometry() {
        Handler(Looper.getMainLooper()).post {
            try {
                val prefs = AppPrefs(this)
                val root = islandRoot ?: return@post
                val wm = windowManager ?: return@post

                val params = root.layoutParams as? WindowManager.LayoutParams ?: return@post
                params.x = prefs.islandX.toInt()
                params.y = prefs.islandY.toInt()
                params.width = prefs.islandWidth.toInt()
                params.height = prefs.islandHeight.toInt()

                borderTrailView?.setCornerRadius(prefs.islandRadius)
                wm.updateViewLayout(root, params)
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

                if (islandRoot == null) {
                    val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            val intent = Intent(this@AgentAccessibilityService, VoiceListenerService::class.java).apply {
                                action = VoiceListenerService.ACTION_START_LISTENING
                            }
                            startService(intent)
                            showIsland("🎙️ Mic Opened")
                            return true
                        }

                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            val intent = Intent(this@AgentAccessibilityService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(intent)
                            return true
                        }

                        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                            if (Math.abs(velocityX) > 100) {
                                showIsland("Mira: Standby")
                                return true
                            }
                            return false
                        }
                    })

                    islandRoot = FrameLayout(this).apply {
                        setOnTouchListener { _, event ->
                            gestureDetector.onTouchEvent(event)
                            true
                        }
                    }

                    borderTrailView = IslandBorderTrailView(this).apply {
                        setCornerRadius(prefs.islandRadius)
                    }

                    islandContainer = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(14, 4, 14, 4)
                        setBackgroundColor(Color.TRANSPARENT)
                    }

                    visualizerBarView = TextView(this).apply {
                        setTextColor(0xFF00E5FF.toInt())
                        textSize = 10f
                        visibility = View.GONE
                        setPadding(0, 0, 8, 0)
                    }

                    islandTextView = TextView(this).apply {
                        setTextColor(Color.WHITE)
                        textSize = 11f
                        gravity = Gravity.CENTER
                    }

                    islandContainer?.addView(visualizerBarView)
                    islandContainer?.addView(islandTextView)

                    islandRoot?.addView(borderTrailView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    islandRoot?.addView(islandContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

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
                    wm.addView(islandRoot, params)
                } else {
                    updateIslandGeometry()
                }

                if (isMusicPlaying) {
                    visualizerBarView?.visibility = View.VISIBLE
                    startVisualizerWaveAnimation()
                } else {
                    visualizerBarView?.visibility = View.GONE
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
                borderTrailView?.stopAnimation()
                islandRoot?.let { windowManager?.removeView(it) }
                islandRoot = null
                borderTrailView = null
                islandContainer = null
                islandTextView = null
                visualizerBarView = null
            } catch (_: Throwable) {}
        }
    }

    // ---------------- SMART TEXT INPUT & AUTO-SEND (MESSAGING HELPER) ----------------

    fun typeAndSend(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root)
        if (focused != null) {
            val bundle = Bundle()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

            if (success) {
                Handler(Looper.getMainLooper()).postDelayed({
                    clickByTextOrDescription(listOf("send", "bhejo", "submit", "enter"))
                }, 400)
                return true
            }
        }
        return false
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    // ---------------- VIDEO CONTROLS (ORIENTATION-AWARE GESTURES) ----------------

    fun forwardVideo(): Boolean {
        val root = rootInActiveWindow
        if (root != null && clickByTextOrDescription(listOf("fast forward", "forward 10 seconds", "seek forward"))) {
            return true
        }
        val metrics = resources.displayMetrics
        val targetX = metrics.widthPixels * 0.78f
        val targetY = metrics.heightPixels * 0.35f
        return doubleTapCoordinates(targetX, targetY)
    }

    fun rewindVideo(): Boolean {
        val root = rootInActiveWindow
        if (root != null && clickByTextOrDescription(listOf("rewind", "rewind 10 seconds", "seek backward"))) {
            return true
        }
        val metrics = resources.displayMetrics
        val targetX = metrics.widthPixels * 0.22f
        val targetY = metrics.heightPixels * 0.35f
        return doubleTapCoordinates(targetX, targetY)
    }

    private fun doubleTapCoordinates(x: Float, y: Float): Boolean {
        val path1 = Path().apply { moveTo(x, y) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, 50)
        val path2 = Path().apply { moveTo(x, y) }
        val stroke2 = GestureDescription.StrokeDescription(path2, 100, 50)

        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ---------------- UNIVERSAL IN-APP GESTURE & UI CLICK ENGINE ----------------

    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root)
        return if (scrollable != null) {
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } else {
            val metrics = resources.displayMetrics
            val cx = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.8f
            val endY = metrics.heightPixels * 0.2f
            swipeGesture(cx, startY, cx, endY)
        }
    }

    fun scrollBackward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root)
        return if (scrollable != null) {
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        } else {
            val metrics = resources.displayMetrics
            val cx = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.2f
            val endY = metrics.heightPixels * 0.8f
            swipeGesture(cx, startY, cx, endY)
        }
    }

    private fun swipeGesture(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = findScrollableNode(root.getChild(i))
            if (child != null) return child
        }
        return null
    }

    fun clickByTextOrDescription(keywords: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        return searchAndClick(root, keywords)
    }

    fun clickAnyElementOnScreen(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return searchAndClick(root, listOf(targetText.lowercase().trim()))
    }

    private fun searchAndClick(node: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isMatch = keywords.any { k -> text.contains(k) || desc.contains(k) || viewId.contains(k) }
        if (isMatch) {
            var clickableNode: AccessibilityNodeInfo? = node
            while (clickableNode != null && !clickableNode.isClickable) {
                clickableNode = clickableNode.parent
            }
            if (clickableNode != null && clickableNode.isClickable) {
                return clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    return clickCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
            }
        }

        for (i in 0 until node.childCount) {
            if (searchAndClick(node.getChild(i), keywords)) return true
        }
        return false
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
}

// ---------------- HOLOGRAPHIC SWEEP BORDER CANVAS VIEW ----------------
class IslandBorderTrailView(context: Context) : View(context) {
    private var cornerRadius = 24f
    private val strokeWidthPx = 4f
    private var rotateAngle = 0f
    private var trailAnimator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE0B0D18.toInt()
        style = Paint.Style.FILL
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
    }

    private val trailColors = intArrayOf(
        0x00000000,
        0x2200E5FF.toInt(),
        0x9900E5FF.toInt(),
        0xFF00E5FF.toInt(),
        0xFFFFFFFF.toInt(),
        0x00000000
    )
    private val trailPositions = floatArrayOf(0.0f, 0.4f, 0.7f, 0.9f, 0.98f, 1.0f)

    init {
        trailAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                rotateAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setCornerRadius(r: Float) {
        cornerRadius = r
        invalidate()
    }

    fun stopAnimation() {
        trailAnimator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val rectF = RectF(strokeWidthPx / 2f, strokeWidthPx / 2f, w - strokeWidthPx / 2f, h - strokeWidthPx / 2f)

        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

        canvas.save()
        canvas.rotate(rotateAngle, w / 2f, h / 2f)
        trailPaint.shader = SweepGradient(w / 2f, h / 2f, trailColors, trailPositions)
        canvas.restore()

        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, trailPaint)
    }
}
