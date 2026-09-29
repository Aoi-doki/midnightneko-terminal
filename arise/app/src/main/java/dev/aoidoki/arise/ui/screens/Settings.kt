package dev.aoidoki.arise.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aoidoki.arise.BuildConfig
import dev.aoidoki.arise.Perm
import dev.aoidoki.arise.Perms
import dev.aoidoki.arise.ai.ModelSpec
import dev.aoidoki.arise.ai.ModelState
import dev.aoidoki.arise.data.VoiceSettings
import dev.aoidoki.arise.ui.MainViewModel
import dev.aoidoki.arise.ui.UiState
import dev.aoidoki.arise.ui.components.Divider
import dev.aoidoki.arise.ui.components.GlowButton
import dev.aoidoki.arise.ui.components.StatBar
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType

@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState, perms: Perms) {
    val context = LocalContext.current
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importModel(uri, displayName(context, uri))
    }
    val exportSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportTo(uri) }
    val importSave = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFrom(uri) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        ProfileWindow(vm, state)
        VoiceWindow(vm, state.settings.voice)
        CoreWindow(vm, state, onImport = { importModel.launch(arrayOf("*/*")) })
        SystemWindow(title = "Tracking") {
            ToggleRow("The System is watching", "Count steps all day (keeps a notification).", state.settings.trackingEnabled) { vm.setTracking(it) }
            ToggleRow("Imperial units", "lb and ft instead of kg and cm.", state.settings.imperial) { vm.setImperial(it) }
            Spacer(Modifier.height(6.dp))
            PermRow("Physical activity", "Step counting.", perms.activity) { perms.request(Perm.ACTIVITY) }
            PermRow("Notifications", "Quests, warnings, penalties.", perms.notifications) { perms.request(Perm.NOTIFICATIONS) }
            PermRow("Camera", "Rep counting by pose.", perms.camera) { perms.request(Perm.CAMERA) }
            PermRow("Health Connect", "Samsung Health steps, sleep, weight.", perms.health) { perms.request(Perm.HEALTH) }
            PermRow("Never sleep", "Exempt from battery optimisation.", perms.battery) { perms.request(Perm.BATTERY) }
            Spacer(Modifier.height(6.dp))
            Text(
                "Samsung phones: also add SYSTEM to Settings → Battery → Background usage limits → Never sleeping apps. " +
                    "In Samsung Health, turn on Settings → Health Connect sync so watch steps and scale weigh-ins arrive.",
                style = SysType.Small.copy(color = LocalSys.current.muted),
            )
        }
        SystemWindow(title = "Save Data") {
            Text("Your progress lives only on this phone. Export it before switching phones or reinstalling.", style = SysType.Small.copy(color = LocalSys.current.muted))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton("Export", { exportSave.launch("arise-save.json") }, Modifier.weight(1f))
                GlowButton("Import", { importSave.launch(arrayOf("application/json", "*/*")) }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            var confirm by remember { mutableStateOf(0) }
            GlowButton(
                when (confirm) { 0 -> "Erase everything"; 1 -> "Tap again to erase"; else -> "Erasing…" },
                { confirm++; if (confirm >= 2) vm.wipe() }, Modifier.fillMaxWidth(), accent = Palette.Red,
            )
        }
        if (BuildConfig.DEBUG) {
            SystemWindow(title = "Debug", accent = Palette.Gold) {
                Text("Moves the game clock forward to test midnight, penalties and rank-ups.", style = SysType.Small.copy(color = LocalSys.current.muted))
                Spacer(Modifier.height(8.dp))
                GlowButton("Skip to tomorrow", { vm.skipDays(1) }, Modifier.fillMaxWidth(), accent = Palette.Gold)
            }
        }
        Text(
            "SYSTEM ${BuildConfig.VERSION_NAME} · everything runs on-device",
            style = SysType.Small.copy(color = LocalSys.current.muted.copy(alpha = 0.6f)),
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ProfileWindow(vm: MainViewModel, state: UiState) {
    val p = state.player ?: return
    val imperial = state.settings.imperial
    val form = remember(p.id, imperial) {
        ProfileForm(
            name = p.name,
            age = p.age.toString(),
            height = if (imperial) ((p.heightCm / 2.54).toInt() / 12).toString() else "%.0f".format(p.heightCm),
            weight = if (imperial) "%.1f".format(p.weightKg / 0.45359237) else "%.1f".format(p.weightKg),
            goal = if (imperial) "%.1f".format(p.goalWeightKg / 0.45359237) else "%.1f".format(p.goalWeightKg),
            about = p.about,
            stats = state.customStats.map { it.name to it.value },
            imperial = imperial,
        ).also { if (imperial) it.heightIn = ((p.heightCm / 2.54).toInt() % 12).toString() }
    }
    var open by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    SystemWindow(title = "Player") {
        if (!open) {
            Text("Edit your body numbers, your own words and your self-defined stats.", style = SysType.Small.copy(color = LocalSys.current.muted))
            Spacer(Modifier.height(8.dp))
            GlowButton("Edit profile", { open = true }, Modifier.fillMaxWidth())
            return@SystemWindow
        }
        BodyStepFields(form)
        Spacer(Modifier.height(12.dp))
        WordsFields(form)
        err?.let { Text(it, style = SysType.Small.copy(color = Palette.Red)) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowButton("Cancel", { open = false }, Modifier.weight(1f))
            GlowButton("Save & re-analyze", {
                err = form.error()
                if (err == null) {
                    vm.updateProfile(form.toProfile(), reassess = true)
                    open = false
                }
            }, Modifier.weight(2f), filled = true)
        }
        Text("Current weight is changed from the Log tab (weigh-ins).", style = SysType.Small.copy(color = LocalSys.current.muted.copy(alpha = 0.7f)))
    }
}

@Composable
private fun VoiceWindow(vm: MainViewModel, v: VoiceSettings) {
    val voices by vm.voices().collectAsState()
    var showVoices by remember { mutableStateOf(false) }
    SystemWindow(title = "The System's Voice", accent = Palette.Cyan) {
        ToggleRow("Voice", "The System speaks its messages.", v.enabled) { vm.setVoice(v.copy(enabled = it)) }
        ToggleRow("Chime", "The notification tone before it speaks.", v.chime) { vm.setVoice(v.copy(chime = it)) }
        SliderRow("Echo", v.echo, 0f..1f) { vm.setVoice(v.copy(echo = it)) }
        SliderRow("Reverb", v.reverb, 0f..1f) { vm.setVoice(v.copy(reverb = it)) }
        SliderRow("Ghost layer", v.ghost, 0f..0.8f) { vm.setVoice(v.copy(ghost = it)) }
        SliderRow("Pitch", v.pitch, 0.6f..1.4f) { vm.setVoice(v.copy(pitch = it)) }
        SliderRow("Speed", v.rate, 0.6f..1.3f) { vm.setVoice(v.copy(rate = it)) }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VOICE", style = SysType.Label.copy(color = LocalSys.current.text))
                Text(v.voiceName.ifBlank { "Automatic (best female voice)" }, style = SysType.Small.copy(color = LocalSys.current.muted))
            }
            GlowButton(if (showVoices) "Close" else "Choose", { showVoices = !showVoices })
        }
        if (showVoices) {
            Spacer(Modifier.height(6.dp))
            (listOf("") + voices.take(24)).forEach { name ->
                Text(
                    if (name.isEmpty()) "Automatic" else name,
                    style = SysType.Small.copy(color = if (name == v.voiceName) Palette.Cyan else LocalSys.current.text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.setVoice(v.copy(voiceName = name)) }
                        .padding(vertical = 6.dp),
                )
            }
            if (voices.isEmpty()) Text("Voices appear after the System speaks once. Tap Test.", style = SysType.Small.copy(color = LocalSys.current.muted))
        }
        Spacer(Modifier.height(10.dp))
        GlowButton("Test voice", { vm.testVoice(v) }, Modifier.fillMaxWidth(), accent = Palette.Cyan)
        Text(
            "For the most natural voice install Google's Speech Services and pick an English (US) female voice in Android's text-to-speech settings.",
            style = SysType.Small.copy(color = LocalSys.current.muted.copy(alpha = 0.7f)),
        )
    }
}

@Composable
private fun CoreWindow(vm: MainViewModel, state: UiState, onImport: () -> Unit) {
    val sys = LocalSys.current
    SystemWindow(title = "The Core · On-device AI", accent = Palette.Violet) {
        when (val m = state.model) {
            is ModelState.Ready -> {
                Text("INSTALLED", style = SysType.Label.copy(color = Palette.Good))
                Text(m.label, style = SysType.Body.copy(color = sys.text))
                Spacer(Modifier.height(8.dp))
                GlowButton("Remove model", { vm.deleteModel() }, Modifier.fillMaxWidth(), accent = Palette.Red)
            }
            is ModelState.Downloading -> {
                Text(if (m.paused) "WAITING FOR NETWORK" else "DOWNLOADING", style = SysType.Label.copy(color = Palette.Violet))
                Text(m.spec.label, style = SysType.Small.copy(color = sys.muted))
                Spacer(Modifier.height(6.dp))
                StatBar("", (m.done / 1_048_576).toInt(), (m.total / 1_048_576).toInt(), Palette.Violet)
                Spacer(Modifier.height(8.dp))
                GlowButton("Cancel", { vm.cancelModel() }, Modifier.fillMaxWidth())
            }
            is ModelState.Verifying -> {
                Text("VERIFYING", style = SysType.Label.copy(color = Palette.Violet))
                StatBar("", (m.done / 1_048_576).toInt(), (m.total / 1_048_576).toInt(), Palette.Violet)
            }
            is ModelState.Failed -> {
                Text(m.message, style = SysType.Small.copy(color = Palette.Red))
                Spacer(Modifier.height(8.dp))
                ModelButtons(vm)
            }
            ModelState.None -> {
                Text("No core installed. Quests are written by the standard rules until one is.", style = SysType.Small.copy(color = sys.muted))
                Spacer(Modifier.height(8.dp))
                ModelButtons(vm)
            }
        }
        Spacer(Modifier.height(10.dp))
        GlowButton("Import .task file", onImport, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Divider()
        ToggleRow("Use the core", "Let the AI write quests and evaluations.", state.settings.aiEnabled) { vm.setAiEnabled(it) }
        ToggleRow("Wi-Fi only", "Download the model only on Wi-Fi.", state.settings.wifiOnly) { vm.setWifiOnly(it) }
        state.aiBusy?.let { Text(it, style = SysType.Small.copy(color = Palette.Violet)) }
    }
}

@Composable
private fun ModelButtons(vm: MainViewModel) {
    ModelSpec.entries.forEach { spec ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(spec.label.uppercase(), style = SysType.Label.copy(color = LocalSys.current.text))
            Text(spec.description, style = SysType.Small.copy(color = LocalSys.current.muted))
            Spacer(Modifier.height(4.dp))
            GlowButton("Download", { vm.downloadModel(spec) }, Modifier.fillMaxWidth(), accent = Palette.Violet)
        }
    }
}

@Composable
fun ToggleRow(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val sys = LocalSys.current
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = SysType.Label.copy(color = sys.text))
            Text(detail, style = SysType.Small.copy(color = sys.muted))
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = sys.accent, checkedThumbColor = sys.text, uncheckedTrackColor = Palette.Abyss, uncheckedBorderColor = Palette.Dim),
        )
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val sys = LocalSys.current
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 2.dp)) {
        Row {
            Text(label.uppercase(), style = SysType.Small.copy(color = sys.muted), modifier = Modifier.weight(1f))
            Text("%.2f".format(local), style = SysType.Small.copy(color = sys.text))
        }
        Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = sys.accentSoft, activeTrackColor = sys.accent, inactiveTrackColor = Palette.Abyss),
        )
    }
}

/** The body + words fields without the Awakening chrome, for editing in Settings. */
@Composable
private fun BodyStepFields(form: ProfileForm) {
    Column {
        dev.aoidoki.arise.ui.components.SysField("Name", form.name, { form.name = it })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.aoidoki.arise.ui.components.SysField("Age", form.age, { form.age = it }, Modifier.weight(1f), numeric = true)
            dev.aoidoki.arise.ui.components.SysField(if (form.imperial) "Height ft" else "Height cm", form.height, { form.height = it }, Modifier.weight(1f), numeric = true)
            if (form.imperial) dev.aoidoki.arise.ui.components.SysField("in", form.heightIn, { form.heightIn = it }, Modifier.weight(0.8f), numeric = true)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.aoidoki.arise.ui.components.SysField(if (form.imperial) "Weight lb" else "Weight kg", form.weight, { form.weight = it }, Modifier.weight(1f), numeric = true)
            dev.aoidoki.arise.ui.components.SysField(if (form.imperial) "Goal lb" else "Goal kg", form.goal, { form.goal = it }, Modifier.weight(1f), numeric = true)
        }
    }
}

@Composable
private fun WordsFields(form: ProfileForm) {
    val sys = LocalSys.current
    dev.aoidoki.arise.ui.components.SysField("In your own words", form.about, { form.about = it }, singleLine = false, minLines = 4)
    Spacer(Modifier.height(10.dp))
    Text("YOUR STATS", style = SysType.Label.copy(color = sys.text))
    form.stats.forEachIndexed { i, (k, v) ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            dev.aoidoki.arise.ui.components.SysField("", k, { form.stats[i] = it to v }, Modifier.weight(1f), placeholder = "stat")
            dev.aoidoki.arise.ui.components.SysField("", v, { form.stats[i] = k to it }, Modifier.weight(1.3f), placeholder = "value")
            Text("✕", style = SysType.Label.copy(color = Palette.Red), modifier = Modifier
                .clickable { form.stats.removeAt(i) }
                .padding(4.dp))
        }
    }
    GlowButton("+ Add stat", { form.stats.add("" to "") }, Modifier.fillMaxWidth())
}

private fun displayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "model.task"
