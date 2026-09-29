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
import dev.aoidoki.arise.ui.components.ButtonKind
import dev.aoidoki.arise.ui.components.Chip
import dev.aoidoki.arise.ui.components.Pane
import dev.aoidoki.arise.ui.components.SysButton
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
            else -> RegistrationStep(form, onArise = {
                vm.setImperial(form.imperial)
                vm.awaken(form.toProfile())
            })
        }
    }
}

/** The show's first message, as a System notification. */
@Composable
fun IntroStep(onAccept: () -> Unit) {
    var declined by remember { mutableStateOf(false) }
    SystemWindow(Modifier.padding(24.dp), title = "Notification") {
        Text(
            if (!declined) "You have acquired the qualifications to be a Player. Will you accept?"
            else "If you choose not to accept, your heart will stop in 0.02 seconds. Will you accept?",
            style = SysType.BodyStrong.copy(color = LocalSys.current.text, fontSize = 17.sp, lineHeight = 26.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!declined) SysButton("Decline", { declined = true }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
            SysButton("Accept", onAccept, Modifier.weight(1f))
        }
    }
}

/** One screen: the body, then (optionally) the Player's own words. Permissions are asked later, in context. */
@Composable
fun RegistrationStep(form: ProfileForm, onArise: () -> Unit) {
    val sys = LocalSys.current
    var err by remember { mutableStateOf<String?>(null) }
    var more by rememberSaveable { mutableStateOf(form.about.isNotEmpty() || form.stats.isNotEmpty()) }
    var pressed by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("PLAYER REGISTRATION", style = SysType.Label.copy(color = sys.accent))
        Text("The System needs the body it will train.", style = SysType.Title.copy(color = sys.text))
        Pane(label = "Body", emphasis = true) {
            SysField("Name", form.name, { form.name = it }, placeholder = "What should the System call you?")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("kg · cm", !form.imperial, { form.imperial = false })
                Chip("lb · ft", form.imperial, { form.imperial = true })
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SysField("Age", form.age, { form.age = it }, Modifier.weight(1f), numeric = true)
                if (form.imperial) {
                    SysField("Height ft", form.height, { form.height = it }, Modifier.weight(1f), numeric = true)
                    SysField("in", form.heightIn, { form.heightIn = it }, Modifier.weight(0.8f), numeric = true)
                } else {
                    SysField("Height cm", form.height, { form.height = it }, Modifier.weight(1f), numeric = true)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SysField(if (form.imperial) "Weight lb" else "Weight kg", form.weight, { form.weight = it }, Modifier.weight(1f), numeric = true)
                SysField(if (form.imperial) "Goal lb" else "Goal kg", form.goal, { form.goal = it }, Modifier.weight(1f), numeric = true)
            }
            val h = form.heightCm()
            val w = form.kg(form.weight)
            val g = form.kg(form.goal)
            if (h != null && w != null && h > 100) {
                val bmi = BodyMetrics.bmi(w, h)
                Spacer(Modifier.height(12.dp))
                Row {
                    Text("BMI ", style = SysType.Label.copy(color = sys.muted))
                    Text("%.1f · %s".format(bmi, BodyMetrics.bmiClass(bmi)), style = SysType.Num.copy(color = sys.text, fontSize = 12.sp))
                }
                if (g != null && g < BodyMetrics.minHealthyWeight(h)) {
                    Text(
                        "Below a healthy weight for your height. The System will aim for %.1f %s.".format(
                            if (form.imperial) BodyMetrics.minHealthyWeight(h) / 0.45359237 else BodyMetrics.minHealthyWeight(h),
                            if (form.imperial) "lb" else "kg",
                        ),
                        style = SysType.Small.copy(color = Palette.Gold),
                    )
                } else if (g != null && g < w) {
                    Text("About ${BodyMetrics.weeksToGoal(w, g)} weeks at a safe pace.", style = SysType.Small.copy(color = sys.muted))
                }
            }
        }
        Pane(label = "In your own words", trailing = { Text(if (more) "−" else "optional  +", style = SysType.Label.copy(color = sys.accent), modifier = Modifier.clickable { more = !more }) }) {
            if (!more) {
                Text(
                    "Strengths, injuries, what you can already do. The System reads what you write. No options to pick.",
                    style = SysType.Small.copy(color = sys.muted),
                    modifier = Modifier.clickable { more = true },
                )
            } else {
                SysField("", form.about, { form.about = it }, singleLine = false, minLines = 4, placeholder = "Desk job, used to play football, left knee clicks, about 15 push-ups…")
                Spacer(Modifier.height(14.dp))
                Text("YOUR STATS", style = SysType.Label.copy(color = sys.muted))
                Spacer(Modifier.height(8.dp))
                form.stats.forEachIndexed { i, (k, v) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SysField("", k, { form.stats[i] = it to v }, Modifier.weight(1f), placeholder = "stat")
                        SysField("", v, { form.stats[i] = k to it }, Modifier.weight(1.3f), placeholder = "value")
                        Text("✕", style = SysType.Num.copy(color = Palette.Dim), modifier = Modifier.clickable { form.stats.removeAt(i) }.padding(6.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                SysButton("Add a stat", { form.stats.add("" to "") }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
                if (form.stats.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("e.g.  max push-ups: 12   plank: 0:45   5k: 34 min   left knee: old ACL tear", style = SysType.Label.copy(color = Palette.Dim, letterSpacing = 0.sp))
                }
            }
        }
        err?.let { Text(it, style = SysType.Small.copy(color = Palette.Crimson)) }
        SysButton(if (pressed) "Registering…" else "Arise", {
            err = form.error()
            if (err == null && !pressed) { pressed = true; onArise() }
        }, Modifier.fillMaxWidth(), enabled = !pressed)
        Text("You can change all of this later.", style = SysType.Small.copy(color = Palette.Dim), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
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

