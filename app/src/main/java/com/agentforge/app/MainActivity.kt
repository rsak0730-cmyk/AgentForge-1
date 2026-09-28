package com.agentforge.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.agentforge.app.agent.AgentEngine
import com.agentforge.app.agent.AiClient
import com.agentforge.app.agent.DialectAdapter
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import com.agentforge.app.security.RealFaceBiometricEngine
import com.agentforge.app.security.SecurityVault
import com.agentforge.app.service.AgentAccessibilityService
import com.agentforge.app.service.ScreenCaptureService
import com.agentforge.app.service.VoiceListenerService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private lateinit var prefs: AppPrefs
    private lateinit var shizuku: ShizukuBridge
    private lateinit var vault: SecurityVault

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.setProjectionIntent(result.resultCode, result.data)
            val intent = Intent(this, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Screen Vision Activated!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = SecurityVault(this)
        prefs = AppPrefs(this)
        shizuku = ShizukuBridge(this)

        requestNeededPermissions()
        startVoiceBackgroundService()

        setContent {
            AgentForgeApp(
                prefs = prefs,
                shizuku = shizuku,
                onRequestScreenVision = { requestScreenVisionPermission() }
            )
        }
    }

    fun requestScreenVisionPermission() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 102)
        }
    }

    private fun startVoiceBackgroundService() {
        try {
            val intent = Intent(this, VoiceListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Throwable) {}
    }
}

val NeuBackground = Color(0xFF0F111A)
val NeuSurface = Color(0xFF151824)
val NeuLightShadow = Color(0xFF1E2235)
val NeuDarkShadow = Color(0xFF08090E)
val WhiteNeonBlue = Color(0xFFE0F7FA)
val NeonBlueAccent = Color(0xFF00E5FF)

@Composable
fun ShimmerNeonText(text: String, size: Int = 16, weight: FontWeight = FontWeight.Bold) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -300f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_float"
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            WhiteNeonBlue.copy(alpha = 0.85f),
            Color(0xFF00E5FF),
            Color(0xFF05060A),
            WhiteNeonBlue
        ),
        start = Offset(translateAnim, translateAnim),
        end = Offset(translateAnim + 160f, translateAnim + 160f)
    )

    Text(
        text = text,
        style = TextStyle(
            brush = brush,
            fontSize = size.sp,
            fontWeight = weight,
            shadow = Shadow(color = NeonBlueAccent.copy(alpha = 0.4f), blurRadius = 10f)
        )
    )
}

fun Modifier.neumorphicCard(cornerRadius: Int = 16): Modifier = this
    .shadow(
        elevation = 6.dp,
        shape = RoundedCornerShape(cornerRadius.dp),
        ambientColor = NeuLightShadow,
        spotColor = NeuDarkShadow
    )
    .background(NeuSurface, RoundedCornerShape(cornerRadius.dp))
    .border(1.dp, NeuLightShadow.copy(alpha = 0.45f), RoundedCornerShape(cornerRadius.dp))

@Composable
fun AgentForgeApp(
    prefs: AppPrefs,
    shizuku: ShizukuBridge,
    onRequestScreenVision: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var isAppUnlocked by remember { mutableStateOf(!prefs.isFaceLockEnabled || !prefs.isFaceEnrolled) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonBlueAccent,
            background = NeuBackground,
            surface = NeuSurface
        )
    ) {
        if (!isAppUnlocked && prefs.isFaceLockEnabled && prefs.isFaceEnrolled) {
            StealthFaceUnlockScreen(
                prefs = prefs,
                onVerified = { isAppUnlocked = true }
            )
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = NeuDarkShadow) {
                        listOf(
                            Icons.Default.Chat to "Chat",
                            Icons.Default.VpnKey to "API",
                            Icons.Default.Voicemail to "Voicemail",
                            Icons.Default.Settings to "Settings"
                        ).forEachIndexed { i, pair ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(pair.first, contentDescription = pair.second, tint = if (tab == i) NeonBlueAccent else Color.Gray) },
                                label = { ShimmerNeonText(pair.second, size = 11, weight = if (tab == i) FontWeight.Bold else FontWeight.Normal) }
                            )
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize().background(NeuBackground)) {
                    when (tab) {
                        0 -> ChatPage(prefs, shizuku)
                        1 -> ApiPage(prefs)
                        2 -> VoicemailPage()
                        3 -> SettingsPage(prefs, shizuku, onRequestScreenVision)
                    }
                }
            }
        }
    }
}

@Composable
fun FluidGlowOrb(isActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "fluid_orb")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val morphRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "morph_rotation"
    )

    val orbCyan = Color(0xFF00E5FF)
    val orbViolet = Color(0xFF7C4DFF)
    val orbMagenta = Color(0xFFFF4081)
    val orbBlue = Color(0xFF2979FF)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isActive) 120.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isActive) {
            Canvas(modifier = Modifier.size(110.dp * pulseScale)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = size.minDimension / 3.2f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(orbCyan.copy(alpha = 0.45f), Color.Transparent),
                        center = center,
                        radius = baseRadius * 1.8f
                    ),
                    center = center,
                    radius = baseRadius * 1.8f
                )

                val angleRad = Math.toRadians(morphRotation.toDouble())
                val offsetX1 = (cos(angleRad) * 22).toFloat()
                val offsetY1 = (sin(angleRad) * 22).toFloat()
                val offsetX2 = (-sin(angleRad) * 20).toFloat()
                val offsetY2 = (cos(angleRad) * 20).toFloat()

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(orbMagenta.copy(alpha = 0.75f), orbViolet.copy(alpha = 0.2f), Color.Transparent),
                        center = center + Offset(offsetX1, offsetY1),
                        radius = baseRadius * 1.3f
                    ),
                    center = center + Offset(offsetX1, offsetY1),
                    radius = baseRadius * 1.3f,
                    blendMode = BlendMode.Screen
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(orbCyan.copy(alpha = 0.85f), orbBlue.copy(alpha = 0.3f), Color.Transparent),
                        center = center + Offset(offsetX2, offsetY2),
                        radius = baseRadius * 1.25f
                    ),
                    center = center + Offset(offsetX2, offsetY2),
                    radius = baseRadius * 1.25f,
                    blendMode = BlendMode.Screen
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, Color.Transparent),
                        center = center,
                        radius = baseRadius * 0.45f
                    ),
                    center = center,
                    radius = baseRadius * 0.45f
                )
            }
        }
    }
}

@Composable
private fun ChatPage(prefs: AppPrefs, shizuku: ShizukuBridge) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var currentName by remember { mutableStateOf(prefs.name) }
    var messages by remember { mutableStateOf(listOf("$currentName: Cyber & Fluid Orb Core ready. Command boliye.")) }
    var busy by remember { mutableStateOf(false) }
    var isListeningVoice by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val engine = remember { AgentEngine(context, AiClient(prefs), shizuku) }

    LaunchedEffect(prefs.name) {
        currentName = prefs.name
    }

    val google4Colors = listOf(
        Color(0xFF4285F4),
        Color(0xFFEA4335),
        Color(0xFFFBBC05),
        Color(0xFF34A853),
        Color(0xFF4285F4)
    )

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        isListeningVoice = false
        val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) { input = spoken }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                ShimmerNeonText(currentName, size = 20)
                Text(
                    text = "@edit.og_",
                    color = NeonBlueAccent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/edit.og_/")))
                    }
                )
            }
            Text(
                text = if (shizuku.hasPermission()) "● Shizuku Online" else "○ Shizuku Off",
                color = if (shizuku.hasPermission()) Color(0xFF00FF7F) else Color(0xFFFF5252),
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            IconButton(onClick = { messages = emptyList() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = Color.Gray)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp)
        ) {
            items(messages) { msg ->
                val isUser = msg.startsWith("You: ")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 310.dp)
                            .neumorphicCard(cornerRadius = 14)
                            .padding(14.dp)
                    ) {
                        Text(text = msg, color = WhiteNeonBlue, fontSize = 14.sp)
                    }
                }
            }
        }

        FluidGlowOrb(isActive = busy || isListeningVoice)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .then(
                    if (busy) {
                        Modifier.drawBehind {
                            val strokeWidth = 3.dp.toPx()
                            val brush = Brush.sweepGradient(google4Colors)
                            drawRoundRect(
                                brush = brush,
                                size = size,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()),
                                style = Stroke(width = strokeWidth)
                            )
                        }
                    } else {
                        Modifier.neumorphicCard(cornerRadius = 24)
                    }
                )
                .background(NeuSurface, RoundedCornerShape(24.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Command ya sawaal...", color = Color.Gray, fontSize = 13.sp) },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = WhiteNeonBlue,
                        unfocusedTextColor = WhiteNeonBlue
                    )
                )

                IconButton(onClick = {
                    if (SpeechRecognizer.isRecognitionAvailable(context)) {
                        isListeningVoice = true
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        }
                        speechLauncher.launch(i)
                    }
                }) {
                    Icon(Icons.Default.Mic, contentDescription = "Mic", tint = if (isListeningVoice) Color(0xFFFF4081) else NeonBlueAccent)
                }

                IconButton(
                    enabled = !busy && input.isNotBlank(),
                    onClick = {
                        val cmd = input.trim()
                        if (cmd.isNotEmpty()) {
                            messages = messages + "You: $cmd"
                            input = ""
                            busy = true
                            scope.launch {
                                listState.animateScrollToItem(messages.size - 1)
                                val res = engine.execute(cmd)
                                messages = messages + "$currentName: $res"
                                busy = false
                                listState.animateScrollToItem(messages.size - 1)
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = if (busy) Color.Gray else NeonBlueAccent)
                }
            }
        }
    }
}

@Composable
private fun ApiPage(prefs: AppPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(prefs.provider) }
    var key by remember { mutableStateOf(prefs.key) }
    var model by remember { mutableStateOf(prefs.model) }
    var base by remember { mutableStateOf(prefs.baseUrl) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ShimmerNeonText("API Vault Configuration", size = 22)
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("gemini", "openrouter", "openai").forEach { p ->
                FilterChip(
                    selected = provider == p,
                    onClick = {
                        provider = p
                        when (p) {
                            "gemini" -> { model = "gemini-2.5-flash"; base = "https://generativelanguage.googleapis.com" }
                            "openai" -> { model = "gpt-4o-mini"; base = "https://api.openai.com/v1" }
                            "openrouter" -> { model = "meta-llama/llama-3.3-70b-instruct"; base = "https://openrouter.ai/api/v1" }
                        }
                    },
                    label = { Text(p.uppercase(Locale.ROOT), color = if (provider == p) NeonBlueAccent else Color.Gray) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key (Encrypted in Vault)") }, visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model ID") })
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") })
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    prefs.provider = provider
                    prefs.key = key.trim()
                    prefs.model = model.trim()
                    prefs.baseUrl = base.trim()
                    Toast.makeText(context, "Saved to Hardware Keystore!", Toast.LENGTH_SHORT).show()
                }
            ) { Text("Save Settings") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !isTesting && key.isNotBlank(),
                onClick = {
                    isTesting = true
                    testResult = "Connecting..."
                    scope.launch {
                        prefs.provider = provider
                        prefs.key = key.trim()
                        prefs.model = model.trim()
                        prefs.baseUrl = base.trim()
                        val client = AiClient(prefs)
                        val res = withContext(Dispatchers.IO) { client.ask("Respond with ONLY one word: Connected") }
                        testResult = res
                        isTesting = false
                    }
                }
            ) { Text(if (isTesting) "Testing..." else "Test Connection") }
        }

        testResult?.let {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().neumorphicCard(12).padding(12.dp)) {
                Text("Status: $it", color = WhiteNeonBlue, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun VoicemailPage() {
    val context = LocalContext.current
    val audioDir = File(context.filesDir, "voicemails").apply { if (!exists()) mkdirs() }
    var recordList by remember { mutableStateOf(audioDir.listFiles()?.toList() ?: emptyList()) }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentlyPlayingFile by remember { mutableStateOf<String?>(null) }

    fun refreshFiles() {
        recordList = audioDir.listFiles()?.filter { it.extension == "m4a" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    LaunchedEffect(Unit) { refreshFiles() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ShimmerNeonText("Live Voicemail Inbox", size = 22)
                Text("Caller voice notes and audio logs", fontSize = 12.sp, color = Color.Gray)
            }
            IconButton(onClick = { refreshFiles() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = NeonBlueAccent)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (recordList.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No recordings yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(recordList) { file ->
                    val isPlaying = currentlyPlayingFile == file.name
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).neumorphicCard(12).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneCallback, contentDescription = null, tint = NeonBlueAccent)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.nameWithoutExtension, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = WhiteNeonBlue)
                                Text("${file.length() / 1024} KB", fontSize = 11.sp, color = Color.Gray)
                            }
                            IconButton(onClick = {
                                if (isPlaying) {
                                    activePlayer?.stop(); activePlayer?.release(); activePlayer = null; currentlyPlayingFile = null
                                } else {
                                    activePlayer?.release()
                                    val mp = MediaPlayer().apply {
                                        setDataSource(file.absolutePath); prepare(); start()
                                        setOnCompletionListener { currentlyPlayingFile = null }
                                    }
                                    activePlayer = mp; currentlyPlayingFile = file.name
                                }
                            }) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = NeonBlueAccent)
                            }
                            IconButton(onClick = { file.delete(); refreshFiles() }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    prefs: AppPrefs,
    shizuku: ShizukuBridge,
    onRequestScreenVision: () -> Unit
) {
    val context = LocalContext.current
    var assistantName by remember { mutableStateOf(prefs.name) }
    var wakeWord by remember { mutableStateOf(prefs.wakeWord) }
    var voicePitch by remember { mutableFloatStateOf(prefs.customVoicePitch) }
    var voiceSpeed by remember { mutableFloatStateOf(prefs.customVoiceSpeed) }
    var isTestingTts by remember { mutableStateOf(false) }

    var faceLock by remember { mutableStateOf(prefs.isFaceLockEnabled) }
    var isFaceEnrolled by remember { mutableStateOf(prefs.isFaceEnrolled) }
    var showEnrollDialog by remember { mutableStateOf(false) }

    var isIslandOn by remember { mutableStateOf(prefs.isIslandEnabled) }
    var ix by remember { mutableFloatStateOf(prefs.islandX) }
    var iy by remember { mutableFloatStateOf(prefs.islandY) }
    var iw by remember { mutableFloatStateOf(prefs.islandWidth) }
    var ih by remember { mutableFloatStateOf(prefs.islandHeight) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ShimmerNeonText("Neumorphic Master Settings", size = 22)
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = assistantName,
            onValueChange = { assistantName = it; prefs.name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Companion Name") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = wakeWord,
            onValueChange = { wakeWord = it; prefs.wakeWord = it.lowercase(Locale.getDefault()) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Wake-Word (e.g. hey mira)") }
        )

        Spacer(Modifier.height(18.dp))
        ShimmerNeonText("Mira Voice Studio & Acoustics", size = 16)
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().neumorphicCard(14).padding(14.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = NeonBlueAccent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Realistic Girl Voice Tuning", fontWeight = FontWeight.Bold, color = WhiteNeonBlue)
                        Text("Adjust pitch & speed to match your favorite companion style", fontSize = 11.sp, color = Color.Gray)
                    }
                }

                Spacer(Modifier.height(14.dp))
                SliderItem("Voice Pitch (Acoustic Height)", voicePitch, 0.7f, 1.8f) {
                    voicePitch = it
                    prefs.customVoicePitch = it
                }
                SliderItem("Speech Pace (Flow Rate)", voiceSpeed, 0.7f, 1.5f) {
                    voiceSpeed = it
                    prefs.customVoiceSpeed = it
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        isTestingTts = true
                        var previewTts: TextToSpeech? = null
                        previewTts = TextToSpeech(context) { status ->
                            if (status == TextToSpeech.SUCCESS) {
                                DialectAdapter.applyRealisticGirlVoice(context, previewTts, "Haan Manish, suniye! Yeh meri nayi voice hai, aapko kaisi lagi?")
                                previewTts?.speak("Haan Manish, suniye! Yeh meri nayi voice hai, aapko kaisi lagi?", TextToSpeech.QUEUE_FLUSH, null, "PREVIEW")
                            }
                            isTestingTts = false
                        }
                    }
                ) {
                    Text(if (isTestingTts) "Generating Voice..." else "Preview Voice (Sunke Dekho)")
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        ShimmerNeonText("Multi-Modal Screen Vision", size = 16)
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().neumorphicCard(14).padding(14.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = NeonBlueAccent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Live Screen Eyes", fontWeight = FontWeight.Bold, color = WhiteNeonBlue)
                        Text("Allows Mira to see screen, reels, memes & questions", fontSize = 11.sp, color = Color.Gray)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRequestScreenVision
                ) {
                    Text("Grant Screen Vision Permission")
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        ShimmerNeonText("Biometric Security Guard", size = 16)
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().neumorphicCard(14).padding(14.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Face, contentDescription = null, tint = NeonBlueAccent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Adaptive Facial Contours", fontWeight = FontWeight.Bold, color = WhiteNeonBlue)
                        Text(
                            text = if (isFaceEnrolled) "Owner Enrolled • Stealth Unlock Ready" else "Requires frontal face scan",
                            fontSize = 11.sp,
                            color = if (isFaceEnrolled) Color(0xFF00FF7F) else Color.Gray
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { showEnrollDialog = true }
                    ) {
                        Text(if (isFaceEnrolled) "Re-Enroll Owner" else "Enroll Owner Face")
                    }

                    if (isFaceEnrolled) {
                        OutlinedButton(
                            onClick = {
                                prefs.isFaceEnrolled = false
                                prefs.isFaceLockEnabled = false
                                prefs.registeredFaceHash = ""
                                isFaceEnrolled = false
                                faceLock = false
                                Toast.makeText(context, "Face Vector Erased", Toast.LENGTH_SHORT).show()
                            }
                        ) { Text("Delete") }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable Mobile-Style Stealth Face Unlock", modifier = Modifier.weight(1f), color = WhiteNeonBlue, fontSize = 13.sp)
                    Switch(
                        checked = faceLock && isFaceEnrolled,
                        enabled = isFaceEnrolled,
                        onCheckedChange = {
                            faceLock = it
                            prefs.isFaceLockEnabled = it
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        ShimmerNeonText("Dynamic Island Cyber Pod", size = 16)
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().neumorphicCard(14).padding(14.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SmartDisplay, contentDescription = null, tint = NeonBlueAccent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dynamic Island Status", fontWeight = FontWeight.Bold, color = WhiteNeonBlue)
                        Text(if (isIslandOn) "Cyber Trail Active on Screen" else "Island is Turned OFF", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = isIslandOn,
                        onCheckedChange = {
                            isIslandOn = it
                            prefs.isIslandEnabled = it
                            if (it) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                                }
                                AgentAccessibilityService.instance?.showIsland("Mira Island Ready")
                            } else {
                                AgentAccessibilityService.instance?.hideIsland()
                            }
                        }
                    )
                }

                if (isIslandOn) {
                    Spacer(Modifier.height(12.dp))
                    SliderItem("X-Axis Offset", ix, -200f, 200f) {
                        ix = it; prefs.islandX = it
                        AgentAccessibilityService.instance?.updateIslandGeometry()
                    }
                    SliderItem("Y-Axis Offset", iy, -50f, 400f) {
                        iy = it; prefs.islandY = it
                        AgentAccessibilityService.instance?.updateIslandGeometry()
                    }
                    SliderItem("Island Width", iw, 120f, 480f) {
                        iw = it; prefs.islandWidth = it
                        AgentAccessibilityService.instance?.updateIslandGeometry()
                    }
                    SliderItem("Island Height", ih, 24f, 100f) {
                        ih = it; prefs.islandHeight = it
                        AgentAccessibilityService.instance?.updateIslandGeometry()
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        ShimmerNeonText("Privileged Automation", size = 16)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (shizuku.hasPermission()) {
                        val ok = shizuku.connect()
                        Toast.makeText(context, if (ok) "Shizuku Connected!" else "Connection failed.", Toast.LENGTH_SHORT).show()
                    } else {
                        shizuku.requestPermission()
                    }
                }
            ) { Text(if (shizuku.hasPermission()) "Shizuku Active" else "Authorize Shizuku") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            ) { Text("Accessibility") }
        }
    }

    if (showEnrollDialog) {
        RealFaceEnrollDialog(
            prefs = prefs,
            onDismiss = { showEnrollDialog = false },
            onEnrolled = {
                isFaceEnrolled = true
                showEnrollDialog = false
                Toast.makeText(context, "Face Vector Saved!", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun SliderItem(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ${String.format(Locale.US, "%.2f", value)}", modifier = Modifier.weight(1f), fontSize = 12.sp, color = WhiteNeonBlue)
        Slider(value = value, onValueChange = onValueChange, valueRange = min..max, modifier = Modifier.weight(1.5f))
    }
}

@Composable
fun RealFaceEnrollDialog(prefs: AppPrefs, onDismiss: () -> Unit, onEnrolled: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var statusText by remember { mutableStateOf("Look straight at the front camera") }
    var isCalibrated by remember { mutableStateOf(false) }
    var lockedHash by remember { mutableStateOf<String?>(null) }

    val detectorOptions = remember {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
    }
    val detector = remember { FaceDetection.getClient(detectorOptions) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeuBackground,
        title = { ShimmerNeonText("Enroll Owner Face", size = 18) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .border(3.dp, if (isCalibrated) Color(0xFF00FF7F) else NeonBlueAccent, CircleShape)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null && !isCalibrated) {
                                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                        detector.process(image)
                                            .addOnSuccessListener { faces ->
                                                if (faces.isNotEmpty()) {
                                                    val face = faces[0]
                                                    if (abs(face.headEulerAngleY) < 8f && abs(face.headEulerAngleX) < 8f) {
                                                        val hash = RealFaceBiometricEngine.extractBiometricHash(face)
                                                        if (hash != null) {
                                                            lockedHash = hash
                                                            isCalibrated = true
                                                            statusText = "Face Contours Locked! Ready to save."
                                                        }
                                                    } else {
                                                        statusText = "Hold head straight"
                                                    }
                                                }
                                            }
                                            .addOnCompleteListener { imageProxy.close() }
                                    } else {
                                        imageProxy.close()
                                    }
                                }

                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_FRONT_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(statusText, color = WhiteNeonBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            Button(
                enabled = isCalibrated && lockedHash != null,
                onClick = {
                    lockedHash?.let {
                        prefs.registeredFaceHash = it
                        prefs.isFaceEnrolled = true
                    }
                    onEnrolled()
                }
            ) { Text("Save Biometrics") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun StealthFaceUnlockScreen(prefs: AppPrefs, onVerified: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var scanStatus by remember { mutableStateOf("Authenticating...") }
    var scanMatched by remember { mutableStateOf(false) }
    var matchCounter by remember { mutableIntStateOf(0) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val lockScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lock_scale"
    )

    val detectorOptions = remember {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
    }
    val detector = remember { FaceDetection.getClient(detectorOptions) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        AgentAccessibilityService.instance?.showIsland("⟳ Scanning Face...")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null && !scanMatched) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    detector.process(image)
                        .addOnSuccessListener { faces ->
                            if (faces.isNotEmpty()) {
                                val face = faces[0]
                                val currentHash = RealFaceBiometricEngine.extractBiometricHash(face)
                                if (currentHash != null && prefs.registeredFaceHash.isNotEmpty()) {
                                    val isMatch = RealFaceBiometricEngine.verifyFaces(prefs.registeredFaceHash, currentHash)
                                    if (isMatch) {
                                        matchCounter++
                                        scanStatus = "Matching Biometrics..."
                                        AgentAccessibilityService.instance?.showIsland("⚡ Verifying ($matchCounter/2)")
                                        if (matchCounter >= 2) {
                                            scanMatched = true
                                            scanStatus = "Face Verified"
                                            AgentAccessibilityService.instance?.showIsland("✓ Face Verified • Unlocked")
                                            ContextCompat.getMainExecutor(context).execute {
                                                onVerified()
                                            }
                                        }
                                    } else {
                                        matchCounter = 0
                                        scanStatus = "Unauthorized Face"
                                        AgentAccessibilityService.instance?.showIsland("⚠ Unauthorized Face")
                                    }
                                }
                            } else {
                                matchCounter = 0
                                scanStatus = "Looking for face..."
                                AgentAccessibilityService.instance?.showIsland("⟳ Scanning Face...")
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(context))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeuBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .scale(if (scanMatched) 1.15f else lockScale)
                    .neumorphicCard(55)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (scanMatched) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (scanMatched) Color(0xFF00FF7F) else NeonBlueAccent,
                    modifier = Modifier.size(46.dp)
                )
            }

            Spacer(Modifier.height(28.dp))
            ShimmerNeonText(if (scanMatched) "Unlocked" else scanStatus, size = 18, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Hold device naturally", fontSize = 12.sp, color = Color.Gray)
        }
    }
}
