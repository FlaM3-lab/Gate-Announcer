package com.gateannouncer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        speaker = Speaker(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(store, speaker)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speaker.shutdown()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(store: Store, speaker: Speaker) {
    val custom = remember { mutableStateListOf<Template>().apply { addAll(store.loadCustom()) } }
    val all = baseTemplates + custom
    var cur by remember { mutableStateOf(baseTemplates[1]) }
    val vals = remember { mutableStateMapOf<String, String>().apply { putAll(store.loadVals()) } }
    var settings by remember { mutableStateOf(store.loadSettings()) }
    val history = remember { mutableStateListOf<List<Item>>().apply { addAll(store.loadHistory()) } }
    var status by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }
    var showBig by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    speaker.onStatus = { status = it }
    fun update(s: Settings) { settings = s; store.saveSettings(s) }

    val langs = LANGS.map { it.code }.filter { it in settings.langs }
    val items = build(cur, vals, langs)
    val allText = items.joinToString("\n\n") { it.text }
    val missing = cur.keys.filter { vals[it].isNullOrBlank() }
    val noTranslation = langs.filter { !cur.hasLang(it) }.map { langOf(it).name }

    fun addHistory(g: List<Item>) {
        history.remove(g); history.add(0, g)
        while (history.size > 8) history.removeAt(history.lastIndex)
        store.saveHistory(history)
    }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("✈️ Gate Announcer", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSettings = true }) { Text("⚙ Settings") }
            }

            Text("Languages (read in this order)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LANGS.forEach { l ->
                    val on = l.code in settings.langs
                    FilterChip(selected = on, onClick = {
                        val next = if (on) settings.langs - l.code else settings.langs + l.code
                        if (next.isNotEmpty()) update(settings.copy(langs = next))
                    }, label = { Text(l.name) })
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                all.forEach { t ->
                    FilterChip(selected = t == cur, onClick = { cur = t }, label = { Text(t.title) })
                }
            }

            cur.keys.forEach { k ->
                OutlinedTextField(
                    value = vals[k] ?: "",
                    onValueChange = { vals[k] = it; if (k in SAVED_KEYS) store.saveVals(vals) },
                    label = { Text(label(k)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items.forEach { it0 ->
                        Column {
                            if (items.size > 1) Text(langOf(it0.lang).name.uppercase(), fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(it0.text, fontSize = 18.sp, lineHeight = 26.sp)
                        }
                    }
                }
            }
            if (missing.isNotEmpty()) {
                Text("Still to fill in: " + missing.joinToString { label(it) } + ".",
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            if (noTranslation.isNotEmpty()) {
                Text("No ${noTranslation.joinToString()} text for this template, so English is used.",
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { speaker.speak(items, settings); addHistory(items) },
                    modifier = Modifier.weight(1f)) { Text("🔊 Read aloud") }
                OutlinedButton(onClick = { speaker.stop() }, modifier = Modifier.weight(1f)) { Text("■ Stop") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(allText)); addHistory(items); status = "Copied." },
                    modifier = Modifier.weight(1f)) { Text("Copy") }
                OutlinedButton(onClick = { showBig = true }, modifier = Modifier.weight(1f)) { Text("Full screen") }
            }
            if (status.isNotEmpty()) Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { cur.keys.forEach { vals[it] = "" }; store.saveVals(vals) }) { Text("Clear fields") }
                TextButton(onClick = { showCustom = true }) { Text("+ New template") }
                if (cur.custom) TextButton(onClick = {
                    custom.remove(cur); store.saveCustom(custom); cur = baseTemplates[1]
                }) { Text("Delete") }
            }

            if (history.isNotEmpty()) {
                Text("Recent (tap to play again)", fontWeight = FontWeight.SemiBold)
                history.toList().forEach { h ->
                    Card(onClick = { speaker.speak(h, settings) }, modifier = Modifier.fillMaxWidth()) {
                        Text(h.joinToString("\n\n") { it.text }, Modifier.padding(12.dp), fontSize = 14.sp, maxLines = 6)
                    }
                }
                TextButton(onClick = { history.clear(); store.saveHistory(history) }) { Text("Clear history") }
            }
        }
    }

    if (showBig) {
        Dialog(onDismissRequest = { showBig = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().clickable { showBig = false }) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.Start) {
                    Spacer(Modifier.height(8.dp))
                    items.forEach { Text(it.text, fontSize = if (items.size > 1) 24.sp else 30.sp,
                        lineHeight = if (items.size > 1) 32.sp else 40.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    if (showCustom) {
        var title by remember { mutableStateOf("") }
        val body = remember { mutableStateMapOf<String, String>() }
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("New template") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true)
                    LANGS.forEach { l ->
                        OutlinedTextField(body[l.code] ?: "", { body[l.code] = it },
                            label = { Text(if (l.code == "en") "English text (required)" else "${l.name} text (optional)") },
                            minLines = 2, placeholder = { if (l.code == "en") Text("A {item} was found at gate {gate}.") })
                    }
                    Text("Use {name}, {gate}, {anything} for fill-in fields. Use the same ones in every language.", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank() && !body["en"].isNullOrBlank()) {
                        val texts = LANGS.associate { it.code to (body[it.code] ?: "").trim() }.filterValues { it.isNotBlank() }
                        val t = Template(title.trim(), texts, true)
                        custom.add(t); store.saveCustom(custom); cur = t; showCustom = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text("Cancel") } }
        )
    }

    if (showSettings) {
        SettingsDialog(settings, speaker.voices(), onChange = { update(it) }, onDismiss = { showSettings = false })
    }
}

@Composable
fun SettingsDialog(s: Settings, voices: List<String>, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice settings") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Phone voice (English)", fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(s.localVoice.ifBlank { "Default" }) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Default") }, onClick = { onChange(s.copy(localVoice = "")); menu = false })
                        voices.forEach { v ->
                            DropdownMenuItem(text = { Text(v) }, onClick = { onChange(s.copy(localVoice = v)); menu = false })
                        }
                    }
                }
                Text("Other languages use the phone’s default voice for that language.", fontSize = 12.sp)
                Text("Speed: ${"%.2f".format(s.rate)}×")
                Slider(value = s.rate, onValueChange = { onChange(s.copy(rate = it)) }, valueRange = 0.6f..1.3f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeat all")
                    (1..3).forEach { n ->
                        FilterChip(selected = s.repeat == n, onClick = { onChange(s.copy(repeat = n)) }, label = { Text("${n}×") })
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use cloud voice (Google)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Switch(checked = s.useCloud, onCheckedChange = { onChange(s.copy(useCloud = it)) })
                }
                OutlinedTextField(s.apiKey, { onChange(s.copy(apiKey = it)) }, label = { Text("Google API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation())
                LANGS.forEach { l ->
                    OutlinedTextField(s.cloudVoices[l.code] ?: l.defaultVoice,
                        { onChange(s.copy(cloudVoices = s.cloudVoices + (l.code to it))) },
                        label = { Text("Cloud voice: ${l.name}") }, singleLine = true)
                }
                Text("If the cloud voice fails or there’s no internet, the phone voice is used instead.", fontSize = 12.sp)
            }
        }
    )
}
