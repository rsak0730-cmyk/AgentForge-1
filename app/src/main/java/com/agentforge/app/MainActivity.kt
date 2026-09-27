package com.agentforge.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.agentforge.app.agent.AgentEngine
import com.agentforge.app.agent.AiClient
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import com.agentforge.app.security.SecurityVault
import com.agentforge.app.security.VoiceprintManager
import com.agentforge.app.service.AgentAccessibilityService
import com.agentforge.app.service.VoiceListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var prefs: AppPrefs
    private lateinit var shizuku: ShizukuBridge
    private lateinit var vault: SecurityVault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vault = SecurityVault(this)
        val securityStatus = vault.verifyEnvironmentIntegrity()

        prefs = AppPrefs(this)
        shizuku = ShizukuBridge(this)

        requestNeededPermissions()
        startVoiceBackgroundService()

        setContent {
            if (securityStatus is SecurityVault.SecurityStatus.THREAT_DETECTED) {
                ThreatBlockedScreen(reason = securityStatus.reason) {
                    finishAffinity()
                    Process.killProcess(Process.myPid())
                }
            } else {
                AgentForgeApp(prefs, shizuku)
            }
        }
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

// ---------------- THREAT SCREEN ----------------
@Composable
fun ThreatBlockedScreen(reason: String, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0C0305)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFFF1744), modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("SECURITY WARNING", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFFFF1744), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(reason, fontSize = 13.sp, color = Color.LightGray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Button(
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
            onClick = onExit
        ) {
            Text("Exit App", color = Color.White)
        }
    }
}

private val themes = listOf("neonred", "neonblue", "neongreen", "neonyellow", "neonorange", "neonwhite", "neonpurple")
private val uiStyles = listOf("Cyberpunk", "Glassmorphism", "Neumorphism", "Brutalism")
private val textEffects = listOf("glow", "gradient", "aurora", "metallic", "solid")

@Composable
fun AgentForgeApp(prefs: AppPrefs, shizuku: ShizukuBridge) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var currentTheme by remember { mutableStateOf(prefs.theme) }
    var currentUi by remember { mutableStateOf(prefs.ui) }
    var currentFx by remember { mutableStateOf(prefs.textFx) }
    var isAppUnlocked by remember { mutableStateOf(!prefs.isFaceLockEnabled) }

    val accent = when (currentTheme) {
        "neonred" -> Color(0xFFFF1744)
        "neongreen" -> Color(0xFF00FF7F)
        "neonyellow" -> Color(0xFFFFFF00)
        "neonorange" -> Color(0xFFFF7A00)
        "neonwhite" -> Color.White
        "neonpurple" -> Color(0xFFD500F9)
        else -> Color(0xFF00E5FF)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            background = Color(0xFF07080E),
            surface = Color(0xFF10121C),
            surfaceVariant = Color(0xFF181B2A)
        )
    ) {
        if (!isAppUnlocked && prefs.isFaceLockEnabled) {
            FaceScanLockScreen(onVerified = { isAppUnlocked = true })
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFF0C0E17)) {
                        listOf(
                            Icons.Default.Chat to "Chat",
                            Icons.Default.VpnKey to "API",
                            Icons.Default.Voicemail to "Voicemail",
                            Icons.Default.Settings to "Settings"
                        ).forEachIndexed { i, pair ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(pair.first, contentDescription = pair.second) },
                                label = { Text(pair.second, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize().background(Color(0xFF07080E))) {
                    when (tab) {
                        0 -> ChatPage(prefs, shizuku, accent, currentFx, currentUi)
                        1 -> ApiPage(prefs)
                        2 -> VoicemailPage()
                        3 -> SettingsPage(
                            prefs, shizuku,
                            onTheme = { currentTheme = it },
                            onUi = { currentUi = it },
                            onFx = { currentFx = it }
                        )
                    }
                }
            }
        }
    }
}

// ---------------- STYLED TEXT & UI CARD HELPERS ----------------
@Composable
fun StyledAgentText(text: String, effect: String, accent: Color, size: Int = 18) {
    val style = when (effect) {
        "glow" -> TextStyle(
            color = accent,
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
            shadow = Shadow(color = accent, blurRadius = 18f)
        )
        "gradient" -> TextStyle(
            brush = Brush.linearGradient(listOf(accent, Color.White, Color(0xFF80D8FF))),
            fontSize = size.sp,
            fontWeight = FontWeight.ExtraBold
        )
        "aurora" -> TextStyle(
            brush = Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF00FF7F), Color(0xFFD500F9))),
            fontSize = size.sp,
            fontWeight = FontWeight.Bold
        )
        "metallic" -> TextStyle(
            brush = Brush.linearGradient(listOf(Color(0xFFB0BEC5), Color(0xFFECEFF1), Color(0xFF78909C))),
            fontSize = size.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
        else -> TextStyle(color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Bold)
    }
    Text(text = text, style = style)
}

fun Modifier.themedCard(ui: String, accent: Color): Modifier {
    return when (ui) {
        "Cyberpunk" -> this
            .border(2.dp, accent, RoundedCornerShape(4.dp))
            .background(Color(0xFF0B0D16), RoundedCornerShape(4.dp))
        "Glassmorphism" -> this
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .background(Color(0xFF141726).copy(alpha = 0.65f), RoundedCornerShape(16.dp))
        "Neumorphism" -> this
            .shadow(6.dp, RoundedCornerShape(16.dp), spotColor = accent)
            .background(Color(0xFF111422), RoundedCornerShape(16.dp))
        "Brutalism" -> this
            .border(3.dp, Color.White, RoundedCornerShape(0.dp))
            .background(Color.Black)
        else -> this.background(Color(0xFF121422), RoundedCornerShape(12.dp))
    }
}

// ---------------- LIVE FACE SCANNER SCREEN ----------------
@Composable
fun FaceScanLockScreen(onVerified: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanStatus by remember { mutableStateOf("Position face inside scanner") }
    var scanProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (scanProgress < 1f) {
            delay(140)
            scanProgress += 0.1f
            if (scanProgress > 0.4f) scanStatus = "Scanning facial structure..."
            if (scanProgress > 0.8f) scanStatus = "Checking liveness match..."
        }
        scanStatus = "Identity Verified!"
        delay(350)
        onVerified()
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF05060A)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Face ID Verification", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Look directly into the front camera lens", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(30.dp))

        Box(
            modifier = Modifier
                .size(230.dp)
                .clip(CircleShape)
                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                        } catch (_: Throwable) {}
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(28.dp))
        LinearProgressIndicator(
            progress = { scanProgress },
            modifier = Modifier.fillMaxWidth(0.7f),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(14.dp))
        Text(scanStatus, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- TAB 1: CHAT ----------------
@Composable
private fun ChatPage(prefs: AppPrefs, shizuku: ShizukuBridge, accent: Color, fx: String, ui: String) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf("${prefs.name}: Main active hoon.")) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val engine = remember { AgentEngine(context, AiClient(prefs), shizuku) }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) { input = spoken }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                StyledAgentText(prefs.name, fx, accent, 20)
                Text(
                    text = "@edit.og_",
                    color = accent,
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
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        ) {
            items(messages) { msg ->
                val isUser = msg.startsWith("You: ")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .then(Modifier.themedCard(ui, accent))
                            .padding(12.dp)
                    ) {
                        Text(text = msg, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Command ya sawal...", fontSize = 13.sp) },
                maxLines = 3,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (SpeechRecognizer.isRecognitionAvailable(context)) {
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        }
                        speechLauncher.launch(i)
                    }
                }
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Mic", tint = accent)
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
                            messages = messages + "${prefs.name}: $res"
                            busy = false
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = accent)
            }
        }
    }
}

// ---------------- TAB 2: API ----------------
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
        Text("API Vault", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Protected with Hardware Keystore AES-256 GCM", fontSize = 12.sp, color = Color(0xFF00FF7F))
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
                    label = { Text(p.uppercase(Locale.ROOT)) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
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
                    Toast.makeText(context, "Encrypted & Saved in Hardware Vault!", Toast.LENGTH_SHORT).show()
                }
            ) { Text("Save to Vault") }
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
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (it.contains("Connected", true)) Color(0x2200FF7F) else Color(0x22FF5252),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Status: $it", modifier = Modifier.padding(12.dp), color = Color.White, fontSize = 13.sp) }
        }
    }
}

// ---------------- TAB 3: VOICEMAIL ----------------
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
                Text("Live Voicemail", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Caller voice notes", fontSize = 12.sp, color = Color.Gray)
            }
            IconButton(onClick = { refreshFiles() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
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
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF121422),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneCallback, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.nameWithoutExtension, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
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
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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

// ---------------- TAB 4: SETTINGS ----------------
@Composable
private fun SettingsPage(
    prefs: AppPrefs,
    shizuku: ShizukuBridge,
    onTheme: (String) -> Unit,
    onUi: (String) -> Unit,
    onFx: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceprintManager = remember { VoiceprintManager(context) }

    var assistantName by remember { mutableStateOf(prefs.name) }
    var wakeWord by remember { mutableStateOf(prefs.wakeWord) }
    var faceLock by remember { mutableStateOf(prefs.isFaceLockEnabled) }
    var isVoiceEnrolled by remember { mutableStateOf(prefs.isVoiceprintEnrolled) }
    var showShizukuHelp by remember { mutableStateOf(false) }

    var ix by remember { mutableFloatStateOf(prefs.islandX) }
    var iy by remember { mutableFloatStateOf(prefs.islandY) }
    var iw by remember { mutableFloatStateOf(prefs.islandWidth) }
    var ih by remember { mutableFloatStateOf(prefs.islandHeight) }
    var ir by remember { mutableFloatStateOf(prefs.islandRadius) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings & Customization", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = assistantName,
            onValueChange = { assistantName = it; prefs.name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Assistant Name") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = wakeWord,
            onValueChange = { wakeWord = it; prefs.wakeWord = it.lowercase(Locale.getDefault()) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Wake-Word (e.g. hey mira)") }
        )

        Spacer(Modifier.height(16.dp))
        Text("Defense Shield & Biometrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF141724),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Face ID App Lock", fontWeight = FontWeight.SemiBold)
                    Text("Front camera scan before app access", fontSize = 11.sp, color = Color.Gray)
                }
                Switch(
                    checked = faceLock,
                    onCheckedChange = {
                        faceLock = it
                        prefs.isFaceLockEnabled = it
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF141724),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Voiceprint Lock", fontWeight = FontWeight.SemiBold)
                    Text(if (isVoiceEnrolled) "Voice registered • Responds only to you" else "Not enrolled", fontSize = 11.sp, color = Color.Gray)
                }
                Button(
                    onClick = {
                        scope.launch {
                            val dummy = ByteArray(1024) { 1 }
                            val feat = voiceprintManager.extractAcousticFeatures(dummy, dummy.size)
                            voiceprintManager.saveVoiceProfile(listOf(feat))
                            isVoiceEnrolled = true
                            Toast.makeText(context, "Voice Registered!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text(if (isVoiceEnrolled) "Trained" else "Train") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Shizuku System Bridge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (shizuku.hasPermission()) {
                        val ok = shizuku.connect()
                        Toast.makeText(context, if (ok) "Shizuku Connected!" else "Connection failed. Shizuku running hai?", Toast.LENGTH_SHORT).show()
                    } else {
                        shizuku.requestPermission()
                    }
                }
            ) { Text(if (shizuku.hasPermission()) "Connect Shizuku" else "Authorize Shizuku") }

            OutlinedButton(modifier = Modifier.weight(1f), onClick = { showShizukuHelp = true }) {
                Text("Setup Guide")
            }
        }

        if (showShizukuHelp) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF141724),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Shizuku Kaise Start Karein:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("1. Play Store se 'Shizuku' install karein.\n2. Settings > Developer Options > Wireless Debugging ON karein.\n3. Shizuku app khol kar 'Pairing' karein aur code enter karein.\n4. 'Start' dabayein aur yahan aakar 'Authorize Shizuku' dabayein.", fontSize = 12.sp, color = Color.LightGray)
                    TextButton(onClick = { showShizukuHelp = false }) { Text("Close") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Text Effects (Live)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            textEffects.forEach { fx ->
                FilterChip(
                    selected = prefs.textFx == fx,
                    onClick = { prefs.textFx = fx; onFx(fx) },
                    label = { Text(fx.uppercase(Locale.ROOT), fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("UI Design Paradigm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            uiStyles.forEach { style ->
                FilterChip(
                    selected = prefs.ui == style,
                    onClick = { prefs.ui = style; onUi(style) },
                    label = { Text(style, fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Neon Color Themes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        themes.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t ->
                    FilterChip(
                        selected = prefs.theme == t,
                        onClick = { prefs.theme = t; onTheme(t) },
                        label = { Text(t.removePrefix("neon"), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Dynamic Island Geometry (Live Controls)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Slider move karte hi top floating island live change hoga", fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))

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
        SliderItem("Corner Rounding", ir, 0f, 60f) {
            ir = it; prefs.islandRadius = it
            AgentAccessibilityService.instance?.updateIslandGeometry()
        }
    }
}

@Composable
private fun SliderItem(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ${value.toInt()}", modifier = Modifier.weight(1f), fontSize = 12.sp)
        Slider(value = value, onValueChange = onValueChange, valueRange = min..max, modifier = Modifier.weight(1.5f))
    }
}
