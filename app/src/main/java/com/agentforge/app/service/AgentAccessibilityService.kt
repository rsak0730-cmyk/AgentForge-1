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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import kotlin.math.sqrt

class AgentAccessibilityService : AccessibilityService(), SensorEventListener {

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

    // ---------------- HARDWARE SENSORY & AMBIENT AWARENESS ----------------
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var lastDarkWarningMs: Long = 0L
    private var lastJerkWarningMs: Long = 0L

    // ---------------- SPONTANEOUS LOVE NOTES SCHEDULER ----------------
    private val spontaneousHandler = Handler(Looper.getMainLooper())
    private val loveNotes = listOf(
        "Aap kaam me kitne focused lag rahe ho 🤍",
        "Paani peena bhool gaye na Shona?",
        "Chalo thoda smile karo ab!",
        "Thak gaye ho toh thodi der aakhein band kar lo.",
        "Mera Hero! Padhai chal rahi hai na?"
    )

    private val spontaneousNoteRunnable = object : Runnable {
        override fun run() {
            val prefs = AppPrefs(this@AgentAccessibilityService)
            if (prefs.isIslandEnabled) {
                val note = loveNotes.random()
                showIsland("💌 $note")
                triggerHeartbeatHaptic()
            }
            spontaneousHandler.postDelayed(this, 1000L * 60 * 35) // Every 35 mins
        }
    }

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
            triggerHeartbeatHaptic()
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

        // Initialize Ambient Light & Motion Sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        lightSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        val prefs = AppPrefs(this)
        if (prefs.isIslandEnabled) {
            showIsland("${prefs.name} Online • Heartbeat Sync")
        }

        predictiveEngine.dispatchPreloadedIslandSuggestion()
        spontaneousHandler.postDelayed(spontaneousNoteRunnable, 1000L * 60 * 12)
    }

    // ---------------- SENSOR EVENT LISTENER ----------------
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val now = SystemClock.elapsedRealtime()

        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val lux = event.values[0]
            if (lux < 2.0f && (now - lastDarkWarningMs) > 1000L * 60 * 45) {
                lastDarkWarningMs = now
                val pet = AppPrefs(this).userPetName
                showIsland("🌙 Andhere me screen mat dekho $pet!")
                triggerComfortPulseHaptic()
            }
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val acceleration = sqrt((x * x + y * y + z * z).toDouble())
            if (acceleration > 24.0 && (now - lastJerkWarningMs) > 10000L) {
                lastJerkWarningMs = now
                showIsland("⚠ Phone gira kya? Sambhal ke!")
                triggerTapHaptic()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false

        val currentKeyCode = event.keyCode
        val currentAction = event.action

        when (currentKeyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                when (currentAction) {
                    KeyEvent.ACTION_DOWN -> {
                        isVolumeUpPressed = true
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

                        if (isListeningActive && !isChordHoldTriggered) {
                            isListeningActive = false
                            triggerTapHaptic()
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
                when (currentAction) {
                    KeyEvent.ACTION_DOWN -> {
                        isVolumeDownPressed = true
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

    // ---------------- PHYSICAL HAPTIC HEARTBEAT & TOUCH ENGINE ----------------

    fun triggerHeartbeatHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 70, 90, 110)
                val amplitudes = intArrayOf(0, 140, 0, 220)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                getVibrator().vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                getVibrator().vibrate(longArrayOf(0, 70, 90, 110), -1)
            }
        } catch (_: Exception) {}
    }

    fun triggerComfortPulseHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 180), intArrayOf(0, 90), -1)
                getVibrator().vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                getVibrator().vibrate(140)
            }
        } catch (_: Exception) {}
    }

    private fun triggerTapHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getVibrator().vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                getVibrator().vibrate(40)
            }
        } catch (_: Exception) {}
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
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
                    triggerHeartbeatHaptic()
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
        sensorManager?.unregisterListener(this)
        spontaneousHandler.removeCallbacksAndMessages(null)
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
                        override fun singleTapConfirmed(e: MotionEvent): Boolean {
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
                                showIsland("${prefs.name}: Standby")
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

    // ---------------- SMART TEXT INPUT & GESTURE SYSTEM ----------------

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
