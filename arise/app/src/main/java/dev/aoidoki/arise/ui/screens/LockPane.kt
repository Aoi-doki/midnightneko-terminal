package dev.aoidoki.arise.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.aoidoki.arise.data.LockSettings
import dev.aoidoki.arise.lock.LockApp
import dev.aoidoki.arise.lock.OverrideCode
import dev.aoidoki.arise.lock.PenaltyLock
import dev.aoidoki.arise.ui.components.ButtonKind
import dev.aoidoki.arise.ui.components.Pane
import dev.aoidoki.arise.ui.components.SysButton
import dev.aoidoki.arise.ui.components.SysField
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType
import kotlinx.coroutines.launch

/** What the Settings screen needs from the lock, kept as callbacks so the pane renders in tests. */
class LockControls(
    val setCode: suspend (String) -> String,
    val setEnabled: suspend (Boolean, String) -> PenaltyLock.Attempt,
    val test: () -> Unit,
    val openAccessibility: () -> Unit,
    val openAppInfo: () -> Unit,
    val installedApps: () -> List<LockApp>,
    val setAllow: (Set<String>) -> Unit,
)

@Composable
fun LockPane(lock: LockSettings, engaged: Boolean, serviceOn: Boolean, c: LockControls, initialRecovery: String? = null) {
    val sys = LocalSys.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf(initialRecovery) }
    var askCode by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val status = when {
        !lock.armed -> "OFF" to Palette.Silver
        engaged -> "ENGAGED" to Palette.Crimson
        !serviceOn -> "NOT ARMED" to Palette.Fatigue
        else -> "ARMED" to Palette.Good
    }
    Pane(label = "Penalty Lock", accent = Palette.Crimson, trailing = { Text(status.first, style = SysType.Label.copy(color = status.second)) }) {
        Text(
            "In the Penalty Zone, the phone locks until the Penalty Quest is walked off. Calls, messages, alarms and " +
                "emergency calls always work. Your override code unlocks it if you ever need to.",
            style = SysType.Small.copy(color = sys.muted),
        )
        Spacer(Modifier.height(10.dp))
        if (!lock.hasCode || editing) {
            CodeSetter(
                onSet = { code -> scope.launch { recovery = c.setCode(code); editing = false } },
                onCancel = if (lock.hasCode) ({ editing = false }) else null,
            )
        } else {
            ToggleRow("Lock the phone", "Only while a Penalty Quest is running.", lock.enabled) { on ->
                if (!on && engaged) askCode = true else scope.launch { c.setEnabled(on, "") }
            }
            PermRow(
                "Accessibility service",
                "Turn on \"SYSTEM Penalty Lock\". It only sees which app is open.",
                serviceOn,
                c.openAccessibility,
            )
            if (!serviceOn) {
                Text(
                    "Greyed out or \"restricted setting\"? Because SYSTEM isn't from the Play Store, Android asks first: " +
                        "App info → ⋮ → Allow restricted settings, then try again.",
                    style = SysType.Small.copy(color = Palette.Fatigue),
                )
                Spacer(Modifier.height(6.dp))
                SysButton("App info", c.openAppInfo, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SysButton("Allowed apps · ${lock.allow.size}", { picking = true }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                SysButton("Change code", { editing = true }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = !engaged)
            }
            Spacer(Modifier.height(8.dp))
            SysButton("Test lock · 30 s", c.test, Modifier.fillMaxWidth(), accent = Palette.Crimson, enabled = lock.armed && serviceOn && !engaged)
        }
    }
    recovery?.let { code -> RecoveryDialog(code) { recovery = null } }
    if (askCode) {
        CodeDialog(
            title = "Turn off the lock",
            onDismiss = { askCode = false },
            onSubmit = { input -> c.setEnabled(false, input).also { if (it == PenaltyLock.Attempt.Accepted) askCode = false } },
        )
    }
    if (picking) AllowDialog(c.installedApps(), lock.allow, onDismiss = { picking = false }) { c.setAllow(it); picking = false }
}

@Composable
private fun CodeSetter(onSet: (String) -> Unit, onCancel: (() -> Unit)?) {
    val sys = LocalSys.current
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    SysField("Override code · ${OverrideCode.MIN_LENGTH}+ digits", a, { a = it.filter(Char::isDigit).take(16) }, numeric = true, secret = true)
    Spacer(Modifier.height(8.dp))
    SysField("Again", b, { b = it.filter(Char::isDigit).take(16) }, numeric = true, secret = true)
    val problem = when {
        a.isEmpty() -> null
        !OverrideCode.valid(a) -> "At least ${OverrideCode.MIN_LENGTH} digits."
        b.isNotEmpty() && a != b -> "The codes don't match."
        else -> null
    }
    problem?.let { Text(it, style = SysType.Small.copy(color = Palette.Fatigue), modifier = Modifier.padding(top = 6.dp)) }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        onCancel?.let { SysButton("Cancel", it, Modifier.weight(1f), kind = ButtonKind.SECONDARY) }
        SysButton("Set code", { onSet(a) }, Modifier.weight(1f), enabled = OverrideCode.valid(a) && a == b)
    }
    Text("Don't use your phone's PIN.", style = SysType.Small.copy(color = sys.muted), modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun RecoveryDialog(code: String, onDone: () -> Unit) {
    val sys = LocalSys.current
    Dialog(onDismissRequest = {}) {
        SystemWindow(title = "Recovery Code", accent = Palette.Gold) {
            Text(
                "Write this down somewhere off the phone. It unlocks the Penalty Lock like your override code. It will not be shown again.",
                style = SysType.Small.copy(color = sys.muted),
            )
            Spacer(Modifier.height(14.dp))
            Text(code, style = SysType.NumLarge.copy(color = Palette.Gold, fontSize = 26.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(16.dp))
            SysButton("I wrote it down", onDone, Modifier.fillMaxWidth(), accent = Palette.Gold)
        }
    }
}

@Composable
private fun CodeDialog(title: String, onDismiss: () -> Unit, onSubmit: suspend (String) -> PenaltyLock.Attempt) {
    val sys = LocalSys.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        SystemWindow(title = title, accent = Palette.Crimson) {
            Text("The lock is engaged. Enter your override or recovery code.", style = SysType.Small.copy(color = sys.muted))
            Spacer(Modifier.height(10.dp))
            SysField("Code", input, { input = it.take(24); message = null }, secret = true)
            message?.let { Text(it, style = SysType.Small.copy(color = Palette.Crimson), modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SysButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                SysButton("Confirm", {
                    scope.launch {
                        message = when (val r = onSubmit(input)) {
                            PenaltyLock.Attempt.Accepted -> null
                            is PenaltyLock.Attempt.Wrong -> "Wrong code."
                            is PenaltyLock.Attempt.Wait -> "Too many wrong codes. Wait a minute."
                        }
                        input = ""
                    }
                }, Modifier.weight(1f), accent = Palette.Crimson, enabled = input.isNotBlank())
            }
        }
    }
}

@Composable
private fun AllowDialog(apps: List<LockApp>, current: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val sys = LocalSys.current
    var chosen by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        SystemWindow(title = "Allowed Apps") {
            Text("Calls, messages, alarms and emergency are always allowed. Add what you need on a walk.", style = SysType.Small.copy(color = sys.muted))
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                apps.forEach { a ->
                    val on = a.pkg in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { chosen = if (on) chosen - a.pkg else chosen + a.pkg },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(on, { chosen = if (on) chosen - a.pkg else chosen + a.pkg }, colors = CheckboxDefaults.colors(checkedColor = sys.accent, uncheckedColor = Palette.Dim))
                        Text(a.label, style = SysType.Body.copy(color = sys.text))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SysButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                SysButton("Save", { onSave(chosen) }, Modifier.weight(1f))
            }
        }
    }
}
