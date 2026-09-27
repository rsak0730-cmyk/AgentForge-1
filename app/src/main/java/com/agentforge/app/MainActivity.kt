package com.agentforge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.agentforge.app.agent.AgentEngine
import com.agentforge.app.agent.AiClient
import com.agentforge.app.automation.ShizukuBridge
import com.agentforge.app.data.AppPrefs
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var prefs: AppPrefs
    private lateinit var shizuku: ShizukuBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        shizuku = ShizukuBridge(this)
        setContent {
            AgentForgeApp(prefs, shizuku)
        }
    }
}

private val themes = listOf("neonred", "neonblue", "neongreen", "neonyellow", "neonorange", "neonwhite", "neonbrown", "neonpurple")
private val uiStyles = listOf("Soft UI", "Brutalism / Neobrutalism", "Aero Glassmorphism", "Liquid Glass / Liquidmorphism", "Auroramorphism", "Claymorphism", "Skeuomorphism", "Glassmorphism", "Neumorphism", "Frutiger Aero", "Y2K UI", "Cyberpunk UI", "Holographic UI", "Material Design", "Fluent Design", "Metallicmorphism", "Glassmorphic Neumorphism", "Gradientmorphism", "3D Morphism", "Pixel UI", "Retro-futuristic UI", "Paper/Material Morphism", "Inflated UI")
private val textFx = listOf("solid", "gradient", "aurora", "glow", "glass", "metalic", "holographic", "liquid", "3d", "outline")

@Composable
fun AgentForgeApp(prefs: AppPrefs, shizuku: ShizukuBridge) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val accent = when (prefs.theme) {
        "neonred" -> Color(0xFFFF1744)
        "neongreen" -> Color(0xFF00FF7F)
        "neonyellow" -> Color(0xFFFFFF00)
        "neonorange" -> Color(0xFFFF7A00)
        "neonwhite" -> Color.White
        "neonbrown" -> Color(0xFFA66A3F)
        "neonpurple" -> Color(0xFFB000FF)
        else -> Color(0xFF00A8FF)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            background = Color(0xFF06070B),
            surface = Color(0xFF10121A)
        )
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf(
                        Icons.Default.Chat to "Chat",
                        Icons.Default.Key to "API",
                        Icons.Default.Voicemail to "Voicemail",
                        Icons.Default.Settings to "Settings"
                    ).forEachIndexed { i, pair ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(pair.first, contentDescription = null) },
                            label = { Text(pair.second) }
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> ChatPage(prefs, shizuku)
                    1 -> ApiPage(prefs)
                    2 -> VoicemailPage()
                    3 -> SettingsPage(prefs, shizuku)
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, action: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        action?.let {
            IconButton(onClick = it) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun ChatPage(prefs: AppPrefs, shizuku: ShizukuBridge) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf("AgentForge: Ready. Give me a command.")) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val engine = remember { AgentEngine(context, AiClient(prefs), shizuku) }

    Column(Modifier.fillMaxSize()) {
        Header("AgentForge") { messages = emptyList() }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "@edit.og_",
                fontSize = 13.sp,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.instagram.com/edit.og_/")))
                }
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (shizuku.hasPermission()) "Shizuku Active" else "Shizuku off",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp
            )
        }
        LazyColumn(Modifier.weight(1f).padding(12.dp)) {
            items(messages) { msg ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                ) {
                    Text(msg, Modifier.padding(14.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Command or question...") },
                maxLines = 5
            )
            IconButton(onClick = {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    }
                    (context as? MainActivity)?.startActivity(i)
                }
            }) {
                Icon(Icons.Default.Mic, contentDescription = "Voice")
            }
            IconButton(
                enabled = !busy,
                onClick = {
                    val c = input.trim()
                    if (c.isNotEmpty()) {
                        messages = messages + "You: $c"
                        input = ""
                        busy = true
                        scope.launch {
                            val r = engine.execute(c)
                            messages = messages + "Agent: $r"
                            busy = false
                        }
                    }
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun ApiPage(prefs: AppPrefs) {
    var provider by remember { mutableStateOf(prefs.provider) }
    var name by remember { mutableStateOf(prefs.name) }
    var key by remember { mutableStateOf(prefs.key) }
    var model by remember { mutableStateOf(prefs.model) }
    var base by remember { mutableStateOf(prefs.baseUrl) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("API Setup", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("gemini", "openrouter", "openai").forEach { p ->
                FilterChip(
                    selected = provider == p,
                    onClick = { provider = p },
                    label = { Text(p) }
                )
            }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("API name") })
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model") })
        OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") })
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            prefs.provider = provider
            prefs.name = name
            prefs.key = key
            prefs.model = model
            prefs.baseUrl = base
        }) {
            Text("Save API configuration")
        }
        Text("Suggested models", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        Text(
            "Gemini: gemini-2.5-flash / gemini-2.5-pro\nOpenAI: gpt-4o-mini / gpt-4o\nOpenRouter: meta-llama/llama-3.3-70b-instruct",
            Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun VoicemailPage() {
    var items by remember { mutableStateOf(listOf<String>()) }
    var playing by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Header("Voicemail")
        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No saved voicemails yet.")
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(items) { v ->
                    ListItem(
                        headlineContent = { Text(v) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { playing = if (playing == v) null else v }) {
                                    Icon(if (playing == v) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = "Play/Stop")
                                }
                                IconButton(onClick = { items = items - v }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    )
                }
            }
        }
        Text(
            "Carrier voicemail recording requires telecom dialer integration. This inbox serves imported local audio notes.",
            Modifier.padding(16.dp),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SettingsPage(prefs: AppPrefs, shizuku: ShizukuBridge) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            if (shizuku.hasPermission()) shizuku.connect() else shizuku.requestPermission()
        }) {
            Text(if (shizuku.hasPermission()) "Connect Shizuku" else "Authorize Shizuku")
        }
        SettingGroup("Theme color", themes, prefs.theme) { prefs.theme = it }
        SettingGroup("UI design", uiStyles, prefs.ui) { prefs.ui = it }
        SettingGroup("Text color / animation", textFx, prefs.textFx) { prefs.textFx = it }
        Spacer(Modifier.height(12.dp))
        Text("Dynamic island", style = MaterialTheme.typography.titleLarge)
        SliderRow("X-axis", prefs.islandX, -200f, 200f) { prefs.islandX = it }
        SliderRow("Y-axis", prefs.islandY, -200f, 500f) { prefs.islandY = it }
        SliderRow("Width", prefs.islandWidth, 120f, 500f) { prefs.islandWidth = it }
        SliderRow("Height", prefs.islandHeight, 28f, 120f) { prefs.islandHeight = it }
        SliderRow("Corner rounding", prefs.islandRadius, 0f, 60f) { prefs.islandRadius = it }
    }
}

@Composable
private fun SettingGroup(title: String, options: List<String>, selected: String, set: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    options.chunked(2).forEach { rowList ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowList.forEach { o ->
                FilterChip(
                    selected = selected == o,
                    onClick = { set(o) },
                    label = { Text(o) },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SliderRow(title: String, value: Float, min: Float, max: Float, set: (Float) -> Unit) {
    Text("$title: ${value.toInt()}")
    Slider(value = value, onValueChange = { set(it) }, valueRange = min..max)
}
