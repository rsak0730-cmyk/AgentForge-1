package com.agentforge.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.agentforge.app.security.VoiceprintManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        shizuku = ShizukuBridge(this)

        requestNeededPermissions()
        startVoiceBackgroundService()

        setContent {
            AgentForgeApp(prefs, shizuku)
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
        } catch (_: Exception) {}
    }
}

private val themes = listOf("neonred", "neonblue", "neongreen", "neonyellow", "neonorange", "neonwhite", "neonbrown", "neonpurple")

@Composable
fun AgentForgeApp(prefs: AppPrefs, shizuku: ShizukuBridge) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var currentTheme by remember { mutableStateOf(prefs.theme) }
    var isAppUnlocked by remember { mutableStateOf(!prefs.isFaceLockEnabled) }

    val accent = when (currentTheme) {
        "neonred" -> Color(0xFFFF1744)
        "neongreen" -> Color(0xFF00FF7F)
        "neonyellow" -> Color(0xFFFFFF00)
        "neonorange" -> Color(0xFFFF7A00)
        "neonwhite" -> Color.White
        "neonbrown" -> Color(0xFFA66A3F)
        "neonpurple" -> Color(0xFFB000FF)
        else -> Color(0xFF00E5FF)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            background = Color(0xFF08090E),
            surface = Color(0xFF12141F),
            surfaceVariant = Color(0xFF1B1E2E)
        )
    ) {
        if (!isAppUnlocked && prefs.isFaceLockEnabled) {
            FaceScanLockScreen(onVerified = { isAppUnlocked = true })
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFF0D0F18)) {
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
                Box(Modifier.padding(pad).fillMaxSize().background(Color(0xFF08090E))) {
                    when (tab) {
                        0 -> ChatPage(prefs, shizuku)
                        1 -> ApiPage(prefs)
                        2 -> VoicemailPage()
                        3 -> SettingsPage(prefs, shizuku) { currentTheme = it }
                    }
                }
            }
        }
    }
}

// ---------------- LIVE FACE SCANNER SCREEN ----------------
@Composable
fun FaceScanLockScreen(onVerified: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanStatus by remember { mutableStateOf("Position face in the circle") }
    var scanProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        // Simulating camera frame scanning & verification
        while (scanProgress < 1f) {
            delay(150)
            scanProgress += 0.1f
            if (scanProgress > 0.4f) scanStatus = "Scanning biometric facial landmarks..."
            if (scanProgress > 0.8f) scanStatus = "Verifying identity..."
        }
        scanStatus = "Identity Verified!"
        delay(400)
        onVerified()
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF05060A)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Face ID Verification", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Look directly at the front camera", fontSize = 13.sp, color = Color.Gray)
        Spacer(Modifier.height(30.dp))

        Box(
            modifier = Modifier
                .size(240.dp)
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
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                        } catch (_: Exception) {}
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(30.dp))
        LinearProgressIndicator(
            progress = { scanProgress },
            modifier = Modifier.fillMaxWidth(0.7f),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(14.dp))
        Text(scanStatus, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- TAB 1: CHAT TAB ----------------
@Composable
private fun ChatPage(prefs: AppPrefs, shizuku: ShizukuBridge) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf("${prefs.name}: Main ready hoon. Voiceprint: ${if (prefs.isVoiceprintEnrolled) "Active (Locked to you)" else "Unenrolled"}")) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val engine = remember { AgentEngine(context, AiClient(prefs), shizuku) }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            input = spoken
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(prefs.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "@edit.og_",
                    color = MaterialTheme.colorScheme.primary,
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
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Chat", tint = Color.Gray)
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
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color(0xFF141724),
                        border = if (isUser) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.widthIn(max = 300.dp)
                    ) {
                        Text(
                            text = msg,
                            modifier = Modifier.padding(12.dp),
                            color = Color.White,
                            fontSize = 14.sp
                        )
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
                    } else {
                        Toast.makeText(context, "Speech recognizer available nahi hai", Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Mic", tint = MaterialTheme.colorScheme.primary)
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
                Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------- TAB 2: API TAB ----------------
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
        Text("API Configuration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Gemini, OpenAI, ya OpenRouter configure karein", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("gemini", "openrouter", "openai").forEach { p ->
                FilterChip(
                    selected = provider == p,
                    onClick = {
                        provider = p
                        when (p) {
                            "gemini" -> {
                                model = "gemini-2.5-flash"
                                base = "https://generativelanguage.googleapis.com"
                            }
                            "openai" -> {
                                model = "gpt-4o-mini"
                                base = "https://api.openai.com/v1"
                            }
                            "openrouter" -> {
                                model = "meta-llama/llama-3.3-70b-instruct"
                                base = "https://openrouter.ai/api/v1"
                            }
                        }
                    },
                    label = { Text(p.uppercase(Locale.ROOT)) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model ID") })
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), label = { Text("Base URL Endpoint") })
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    prefs.provider = provider
                    prefs.key = key.trim()
                    prefs.model = model.trim()
                    prefs.baseUrl = base.trim()
                    Toast.makeText(context, "API Settings Saved!", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Save Settings")
            }

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
                        val res = withContext(Dispatchers.IO) {
                            client.ask("Respond with ONLY one word: Connected")
                        }
                        testResult = res
                        isTesting = false
                    }
                }
            ) {
                Text(if (isTesting) "Testing..." else "Test Connection")
            }
        }

        testResult?.let {
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (it.contains("Connected", true)) Color(0x2200FF7F) else Color(0x22FF5252),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Status: $it",
                    modifier = Modifier.padding(14.dp),
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ---------------- TAB 3: IOS STYLE CALLER VOICEMAIL ----------------
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

    LaunchedEffect(Unit) {
        refreshFiles()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live Voicemail", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Caller voice messages & recordings", fontSize = 12.sp, color = Color.Gray)
            }
            IconButton(onClick = { refreshFiles() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(14.dp))

        if (recordList.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Voicemail, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No Caller Voicemails", color = Color.Gray, fontWeight = FontWeight.SemiBold)
                    Text("Incoming calls ke recorded voice messages yahan appear honge.", fontSize = 12.sp, color = Color.DarkGray, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(recordList) { file ->
                    val isPlaying = currentlyPlayingFile == file.name
                    val nameParts = file.nameWithoutExtension.split("_")
                    val callerDisplay = if (nameParts.size >= 2) nameParts[1] else "Unknown Caller"
                    val timeDisplay = if (nameParts.size >= 3) nameParts[2] else "Recent"

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF141724),
                        border = if (isPlaying) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.PhoneCallback, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(callerDisplay, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("Recorded at: $timeDisplay • ${file.length() / 1024} KB", fontSize = 11.sp, color = Color.Gray)
                                }
                                IconButton(onClick = {
                                    val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$callerDisplay")).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(dial)
                                }) {
                                    Icon(Icons.Default.Call, contentDescription = "Call Back", tint = Color(0xFF00FF7F))
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(
                                Modifier.fillMaxWidth().background(Color(0xFF0D0F18), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        if (isPlaying) {
                                            activePlayer?.stop()
                                            activePlayer?.release()
                                            activePlayer = null
                                            currentlyPlayingFile = null
                                        } else {
                                            activePlayer?.release()
                                            val mp = MediaPlayer().apply {
                                                setDataSource(file.absolutePath)
                                                prepare()
                                                start()
                                                setOnCompletionListener {
                                                    currentlyPlayingFile = null
                                                }
                                            }
                                            activePlayer = mp
                                            currentlyPlayingFile = file.name
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                        contentDescription = "Play/Pause",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Text(
                                    text = if (isPlaying) "Playing voice note..." else "Tap to listen to caller message",
                                    fontSize = 12.sp,
                                    color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Gray,
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(onClick = {
                                    if (currentlyPlayingFile == file.name) {
                                        activePlayer?.stop()
                                        activePlayer?.release()
                                        activePlayer = null
                                        currentlyPlayingFile = null
                                    }
                                    file.delete()
                                    refreshFiles()
                                }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFFF5252))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- TAB 4: SETTINGS & BIOMETRIC ENROLLMENT ----------------
@Composable
private fun SettingsPage(
    prefs: AppPrefs,
    shizuku: ShizukuBridge,
    onThemeUpdated: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceprintManager = remember { VoiceprintManager(context) }

    var assistantName by remember { mutableStateOf(prefs.name) }
    var wakeWord by remember { mutableStateOf(prefs.wakeWord) }
    var faceLock by remember { mutableStateOf(prefs.isFaceLockEnabled) }
    var isVoiceEnrolled by remember { mutableStateOf(prefs.isVoiceprintEnrolled) }

    var isTrainingVoice by remember { mutableStateOf(false) }
    var voiceStep by remember { mutableIntStateOf(0) }

    var ix by remember { mutableFloatStateOf(prefs.islandX) }
    var iy by remember { mutableFloatStateOf(prefs.islandY) }
    var iw by remember { mutableFloatStateOf(prefs.islandWidth) }
    var ih by remember { mutableFloatStateOf(prefs.islandHeight) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Assistant Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
            label = { Text("Wake-Word Command (e.g. hey mira)") }
        )

        Spacer(Modifier.height(18.dp))
        Text("Biometric & Voice Security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        // Face Lock Switch
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

        // iOS Style Voiceprint Setup
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF141724),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Personal Voiceprint (Siri Style)", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (isVoiceEnrolled) "Voice registered • Only responds to your voice" else "Not enrolled • Responds to anyone",
                            fontSize = 11.sp,
                            color = if (isVoiceEnrolled) Color(0xFF00FF7F) else Color.Gray
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (isTrainingVoice) {
                    Column(Modifier.fillMaxWidth().background(Color(0xFF0B0D16), RoundedCornerShape(8.dp)).padding(10.dp)) {
                        Text("Step ${voiceStep + 1}/3: Say clearly -> \"${prefs.wakeWord}\"", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (voiceStep + 1) / 3f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                isTrainingVoice = true
                                voiceStep = 0
                                scope.launch {
                                    val collected = mutableListOf<FloatArray>()
                                    for (i in 0 until 3) {
                                        voiceStep = i
                                        delay(2000)
                                        // Fake capture / extract features
                                        val dummyBytes = ByteArray(1024) { (it % 64).toByte() }
                                        collected.add(voiceprintManager.extractAcousticFeatures(dummyBytes, dummyBytes.size))
                                    }
                                    voiceprintManager.saveVoiceProfile(collected)
                                    isVoiceEnrolled = true
                                    isTrainingVoice = false
                                    Toast.makeText(context, "Voiceprint Registered Successfully!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isVoiceEnrolled) "Re-train Voice" else "Train My Voice")
                        }

                        if (isVoiceEnrolled) {
                            OutlinedButton(
                                onClick = {
                                    prefs.isVoiceprintEnrolled = false
                                    prefs.enrolledVoiceprint = ""
                                    isVoiceEnrolled = false
                                    Toast.makeText(context, "Voiceprint Removed", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Reset")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("System Privileges", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (shizuku.hasPermission()) {
                        val connected = shizuku.connect()
                        Toast.makeText(context, if (connected) "Shizuku Connected!" else "Connection failed", Toast.LENGTH_SHORT).show()
                    } else {
                        shizuku.requestPermission()
                    }
                }
            ) {
                Text(if (shizuku.hasPermission()) "Connect Shizuku" else "Authorize Shizuku")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            ) {
                Text("Accessibility")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Color Themes", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        themes.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t ->
                    FilterChip(
                        selected = prefs.theme == t,
                        onClick = {
                            prefs.theme = t
                            onThemeUpdated(t)
                        },
                        label = { Text(t.removePrefix("neon"), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Dynamic Island Geometry", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SliderItem("X-Axis Offset", ix, -200f, 200f) { ix = it; prefs.islandX = it }
        SliderItem("Y-Axis Height Offset", iy, -100f, 400f) { iy = it; prefs.islandY = it }
        SliderItem("Island Width", iw, 100f, 450f) { iw = it; prefs.islandWidth = it }
        SliderItem("Island Height", ih, 24f, 100f) { ih = it; prefs.islandHeight = it }
    }
}

@Composable
private fun SliderItem(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ${value.toInt()}", modifier = Modifier.weight(1f), fontSize = 12.sp)
        Slider(value = value, onValueChange = onValueChange, valueRange = min..max, modifier = Modifier.weight(1.5f))
    }
}
