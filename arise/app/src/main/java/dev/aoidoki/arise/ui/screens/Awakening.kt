package dev.aoidoki.arise.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aoidoki.arise.Perm
import dev.aoidoki.arise.Perms
import dev.aoidoki.arise.ai.ModelSpec
import dev.aoidoki.arise.data.CustomStatEntity
import dev.aoidoki.arise.engine.BodyMetrics
import dev.aoidoki.arise.engine.Game
import dev.aoidoki.arise.ui.MainViewModel
import dev.aoidoki.arise.ui.UiState
import dev.aoidoki.arise.ui.components.Chip
import dev.aoidoki.arise.ui.components.GlowButton
import dev.aoidoki.arise.ui.components.SysField
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.components.TypewriterText
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType

/** Editable form of the Player's profile, shared by Awakening and Settings. */
class ProfileForm(
    name: String = "",
    age: String = "",
    height: String = "",
    weight: String = "",
    goal: String = "",
    about: String = "",
    stats: List<Pair<String, String>> = emptyList(),
    imperial: Boolean = false,
) {
    var name by mutableStateOf(name)
    var age by mutableStateOf(age)
    var height by mutableStateOf(height)
    var heightIn by mutableStateOf("")
    var weight by mutableStateOf(weight)
    var goal by mutableStateOf(goal)
    var about by mutableStateOf(about)
    var imperial by mutableStateOf(imperial)
    val stats = mutableStateListOf<Pair<String, String>>().apply { addAll(stats) }

    private fun num(s: String) = s.replace(',', '.').trim().toDoubleOrNull()

    fun heightCm(): Double? = if (imperial) {
        val ft = num(height) ?: return null
        val inch = num(heightIn) ?: 0.0
        (ft * 12 + inch) * 2.54
    } else num(height)

    fun kg(s: String): Double? = num(s)?.let { if (imperial) it * 0.45359237 else it }

    fun error(): String? {
        val a = age.trim().toIntOrNull()
        val h = heightCm()
        val w = kg(weight)
        val g = kg(goal)
        return when {
            name.isBlank() -> "The System requires a name."
            a == null || a !in 13..100 -> "Age must be between 13 and 100."
            h == null || h !in 120.0..230.0 -> "Height looks wrong."
            w == null || w !in 30.0..350.0 -> "Weight looks wrong."
            g == null || g !in 30.0..350.0 -> "Goal weight looks wrong."
            else -> null
        }
    }

    fun toProfile(): Game.Profile = Game.Profile(
        name = name.trim(),
        age = age.trim().toInt(),
        heightCm = heightCm()!!,
        weightKg = kg(weight)!!,
        goalWeightKg = kg(goal)!!,
        about = about.trim(),
        customStats = stats.filter { it.first.isNotBlank() || it.second.isNotBlank() }
            .map { CustomStatEntity(name = it.first.trim().take(60), value = it.second.trim().take(200)) },
    )
}

@Composable
fun AwakeningScreen(vm: MainViewModel, state: UiState, perms: Perms, startStep: Int = 0) {
    var step by rememberSaveable { mutableIntStateOf(startStep) }
    val form = remember { ProfileForm(imperial = state.settings.imperial) }
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        when (step) {
            0 -> IntroStep(onAccept = { step = 1 })
            1 -> BodyStep(form, onNext = { step = 2 })
            2 -> WordsStep(form, onBack = { step = 1 }, onNext = { step = 3 })
            3 -> PermissionsStep(perms, onNext = { step = 4 })
            4 -> CoreStep(state, onPick = { vm.downloadModel(it) }, onNext = { step = 5 })
            else -> AriseStep(form, onArise = {
                vm.setImperial(form.imperial)
                vm.awaken(form.toProfile())
            })
        }
    }
}

@Composable
fun IntroStep(onAccept: () -> Unit) {
    var declined by remember { mutableStateOf(false) }
    SystemWindow(Modifier.padding(20.dp), title = "Notification") {
        TypewriterText(
            if (!declined) "You have acquired the qualifications\nto be a Player.\n\nWill you accept?"
            else "If you choose not to accept,\nyour heart will stop in 0.02 seconds.\n\nWill you accept?",
            SysType.Body.copy(color = LocalSys.current.text, fontSize = 18.sp, lineHeight = 28.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlowButton("Accept", onAccept, Modifier.weight(1f), filled = true)
            if (!declined) GlowButton("Decline", { declined = true }, Modifier.weight(1f), accent = Palette.Red)
        }
    }
}

@Composable
fun BodyStep(form: ProfileForm, onNext: () -> Unit) {
    val sys = LocalSys.current
    var err by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        SystemWindow(title = "Player Registration") {
            Text("The System must know the body it is training.", style = SysType.Body.copy(color = sys.muted))
            Spacer(Modifier.height(14.dp))
            SysField("Name", form.name, { form.name = it }, placeholder = "What should the System call you?")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("UNITS", style = SysType.Small.copy(color = sys.muted, letterSpacing = 2.sp))
                Spacer(Modifier.width(4.dp))
                Chip("kg · cm", !form.imperial, { form.imperial = false })
                Chip("lb · ft", form.imperial, { form.imperial = true })
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SysField("Age", form.age, { form.age = it }, Modifier.weight(1f), numeric = true)
                if (form.imperial) {
                    SysField("Height ft", form.height, { form.height = it }, Modifier.weight(1f), numeric = true)
                    SysField("in", form.heightIn, { form.heightIn = it }, Modifier.weight(0.8f), numeric = true)
                } else {
                    SysField("Height cm", form.height, { form.height = it }, Modifier.weight(1f), numeric = true)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SysField(if (form.imperial) "Weight lb" else "Weight kg", form.weight, { form.weight = it }, Modifier.weight(1f), numeric = true)
                SysField(if (form.imperial) "Goal lb" else "Goal kg", form.goal, { form.goal = it }, Modifier.weight(1f), numeric = true)
            }
            val h = form.heightCm()
            val w = form.kg(form.weight)
            val g = form.kg(form.goal)
            if (h != null && w != null && h > 100) {
                val bmi = BodyMetrics.bmi(w, h)
                Spacer(Modifier.height(10.dp))
                Text("BMI %.1f · %s".format(bmi, BodyMetrics.bmiClass(bmi)), style = SysType.Mono.copy(color = sys.accentSoft))
                if (g != null && g < BodyMetrics.minHealthyWeight(h)) {
                    Text(
                        "That goal is below a healthy weight for your height. The System will aim for %.1f %s instead.".format(
                            if (form.imperial) BodyMetrics.minHealthyWeight(h) / 0.45359237 else BodyMetrics.minHealthyWeight(h),
                            if (form.imperial) "lb" else "kg",
                        ),
                        style = SysType.Small.copy(color = Palette.Gold),
                    )
                } else if (g != null && g < w) {
                    Text("At a safe pace (≈0.75%/week) that is about ${BodyMetrics.weeksToGoal(w, g)} weeks.", style = SysType.Small.copy(color = sys.muted))
                }
            }
            err?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = SysType.Small.copy(color = Palette.Red))
            }
            Spacer(Modifier.height(16.dp))
            GlowButton("Continue", { err = form.error(); if (err == null) onNext() }, Modifier.fillMaxWidth(), filled = true)
        }
    }
}

@Composable
fun WordsStep(form: ProfileForm, onBack: () -> Unit, onNext: () -> Unit) {
    val sys = LocalSys.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SystemWindow(title = "Tell the System Who You Are") {
            Text(
                "In your own words. Your strengths, your weaknesses, injuries, what you do all day, what you can already do. " +
                    "There are no options to pick — the System reads what you write.",
                style = SysType.Body.copy(color = sys.muted),
            )
            Spacer(Modifier.height(12.dp))
            SysField(
                "", form.about, { form.about = it }, singleLine = false, minLines = 5,
                placeholder = "e.g. Desk job, I used to play football, my left knee clicks, I can do about 15 push-ups…",
            )
            Spacer(Modifier.height(18.dp))
            Text("YOUR STATS", style = SysType.Header.copy(color = sys.text))
            Text("Name them yourself. Anything you can measure or describe.", style = SysType.Small.copy(color = sys.muted))
            Spacer(Modifier.height(10.dp))
            form.stats.forEachIndexed { i, (k, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    SysField("", k, { form.stats[i] = it to v }, Modifier.weight(1f), placeholder = "stat")
                    SysField("", v, { form.stats[i] = k to it }, Modifier.weight(1.3f), placeholder = "value")
                    Text(
                        "✕", style = SysType.Label.copy(color = Palette.Red),
                        modifier = Modifier
                            .clickable { form.stats.removeAt(i) }
                            .padding(bottom = 10.dp, start = 4.dp, end = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlowButton("+ Add stat", { form.stats.add("" to "") }, Modifier.weight(1f))
                if (form.stats.isNotEmpty()) GlowButton("− Remove last", { form.stats.removeAt(form.stats.lastIndex) }, Modifier.weight(1f), accent = Palette.Red)
            }
            if (form.stats.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Examples of what people write: \"max push-ups: 12\", \"plank: 45 s\", \"5k: 34 min\", \"daily steps: ~4000\", \"left knee: old ACL tear\", \"sleep: 6 h\".",
                    style = SysType.Small.copy(color = sys.muted.copy(alpha = 0.8f)),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlowButton("Back", onBack, Modifier.weight(1f))
                GlowButton("Continue", onNext, Modifier.weight(2f), filled = true)
            }
        }
    }
}

@Composable
fun PermissionsStep(perms: Perms, onNext: () -> Unit) {
    val sys = LocalSys.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        SystemWindow(title = "The System Is Watching") {
            Text("To track quests by itself, the System needs these. Each one can be changed later.", style = SysType.Body.copy(color = sys.muted))
            Spacer(Modifier.height(14.dp))
            PermRow("Physical activity", "Counts your steps all day from the phone's step sensor.", perms.activity) { perms.request(Perm.ACTIVITY) }
            PermRow("Notifications", "Quest arrivals, warnings, the Penalty Zone.", perms.notifications) { perms.request(Perm.NOTIFICATIONS) }
            PermRow(
                "Health Connect",
                if (perms.healthAvailable) "Reads Samsung Health / Galaxy Watch steps, sleep and weight." else "Install or update Health Connect to link Samsung Health.",
                perms.health,
            ) { perms.request(Perm.HEALTH) }
            PermRow("Never sleep", "Stops the phone's battery saver killing the System at night.", perms.battery) { perms.request(Perm.BATTERY) }
            Spacer(Modifier.height(16.dp))
            GlowButton("Continue", onNext, Modifier.fillMaxWidth(), filled = true)
        }
    }
}

@Composable
fun PermRow(title: String, detail: String, granted: Boolean, onGrant: () -> Unit) {
    val sys = LocalSys.current
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = SysType.Label.copy(color = sys.text))
            Text(detail, style = SysType.Small.copy(color = sys.muted))
        }
        Spacer(Modifier.width(10.dp))
        if (granted) Text("GRANTED", style = SysType.Label.copy(color = Palette.Good))
        else GlowButton("Grant", onGrant)
    }
}

@Composable
fun CoreStep(state: UiState, onPick: (ModelSpec) -> Unit, onNext: () -> Unit) {
    val sys = LocalSys.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        SystemWindow(title = "The System's Core", accent = Palette.Violet) {
            Text(
                "A small AI runs entirely on your phone. It reads your own words, writes your quests and judges your rank-ups. " +
                    "Nothing leaves the device. The System works without it, but thinks better with it.",
                style = SysType.Body.copy(color = sys.muted),
            )
            Spacer(Modifier.height(14.dp))
            ModelSpec.entries.forEach { spec ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(spec.label.uppercase(), style = SysType.Label.copy(color = sys.text))
                    Text(spec.description, style = SysType.Small.copy(color = sys.muted))
                    Spacer(Modifier.height(6.dp))
                    GlowButton("Download", { onPick(spec); onNext() }, Modifier.fillMaxWidth(), accent = Palette.Violet)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Downloads over Wi-Fi in the background (change in Settings). You can also import a MediaPipe .task file later.",
                style = SysType.Small.copy(color = sys.muted),
            )
            Spacer(Modifier.height(12.dp))
            GlowButton("Later", onNext, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun AriseStep(form: ProfileForm, onArise: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        SystemWindow(title = "Registration Complete") {
            TypewriterText(
                "Player ${form.name.ifBlank { "?" }} has been registered.\n\nFrom this moment, the System will issue a Daily Quest every day. " +
                    "Failure to complete it will result in an appropriate penalty.",
                SysType.Body.copy(color = LocalSys.current.text),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(22.dp))
            GlowButton(if (pressed) "…" else "Arise", { if (!pressed) { pressed = true; onArise() } }, Modifier.fillMaxWidth(), filled = true, enabled = !pressed)
        }
    }
}
