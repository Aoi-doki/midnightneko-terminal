package dev.aoidoki.arise.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.aoidoki.arise.data.ObjectiveEntity
import dev.aoidoki.arise.data.QuestWithObjectives
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.PenaltyEngine
import dev.aoidoki.arise.engine.QuestStatus
import dev.aoidoki.arise.ui.UiState
import dev.aoidoki.arise.ui.components.ButtonKind
import dev.aoidoki.arise.ui.components.Hairline
import dev.aoidoki.arise.ui.components.ListRow
import dev.aoidoki.arise.ui.components.Meter
import dev.aoidoki.arise.ui.components.Pane
import dev.aoidoki.arise.ui.components.ProgressRing
import dev.aoidoki.arise.ui.components.SysButton
import dev.aoidoki.arise.ui.components.SysField
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun QuestScreen(
    state: UiState,
    onTrain: (ObjectiveType) -> Unit,
    onManual: (ObjectiveType, Int) -> Unit,
    onRecovery: () -> Unit,
    onReroll: () -> Unit,
    onStartTracker: () -> Unit,
    trackerGranted: Boolean,
    lockUnarmed: Boolean = false,
    onFixLock: () -> Unit = {},
) {
    val sys = LocalSys.current
    val p = state.player ?: return
    var manualFor by remember { mutableStateOf<ObjectiveType?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.penalty?.let { PenaltyPane(it, state.now) }
        val daily = state.daily
        if (daily == null) {
            Pane(label = "Daily Quest", emphasis = true) {
                val cleared = state.days.firstOrNull()?.result == "CLEARED"
                Text(if (cleared) "Cleared." else "Preparing today's quest…", style = SysType.Header.copy(color = sys.text))
                Spacer(Modifier.height(4.dp))
                Text(if (cleared) "A new quest arrives at midnight." else "One moment.", style = SysType.Small.copy(color = sys.muted))
            }
        } else {
            DailyPane(daily, state, onTrain, onManual = { manualFor = it })
        }
        state.trial?.let { TrialPane(it, state, onTrain) }
        if (lockUnarmed) {
            Pane(accent = Palette.Crimson) {
                ListRow(
                    title = "The Penalty Lock isn't armed",
                    subtitle = "Its accessibility service is off, so a penalty won't lock the phone.",
                    onClick = onFixLock,
                ) { Text("Fix", style = SysType.BodyStrong.copy(color = Palette.Crimson)) }
            }
        }
        if (!trackerGranted) {
            Pane(accent = Palette.Fatigue) {
                ListRow(
                    title = "Step tracking is off",
                    subtitle = "Allow physical activity so walking counts by itself.",
                    onClick = onStartTracker,
                ) { Text("Allow", style = SysType.BodyStrong.copy(color = Palette.Fatigue)) }
            }
        }
        val recoveryLeft = PenaltyEngine.recoveryLeft(p, state.today)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SysButton(
                if (p.recoveryDay == state.today) "Recovering today" else "Recovery day · $recoveryLeft left",
                onRecovery, Modifier.weight(1f), kind = ButtonKind.SECONDARY,
                enabled = p.recoveryDay != state.today && recoveryLeft > 0,
            )
            SysButton(
                "Reroll · 30 MP", onReroll, Modifier.weight(1f), kind = ButtonKind.SECONDARY,
                enabled = p.mp >= 30 && daily?.quest?.status == QuestStatus.ACTIVE && state.aiBusy == null,
            )
        }
        state.aiBusy?.let { Text(it, style = SysType.Label.copy(color = sys.accent)) }
        Spacer(Modifier.height(8.dp))
    }
    manualFor?.let { type ->
        ManualDialog(type, onDismiss = { manualFor = null }, onConfirm = { onManual(type, it); manualFor = null })
    }
}

@Composable
private fun DailyPane(q: QuestWithObjectives, state: UiState, onTrain: (ObjectiveType) -> Unit, onManual: (ObjectiveType) -> Unit) {
    val sys = LocalSys.current
    val done = q.quest.status == QuestStatus.COMPLETED
    val left = (endOfDay(state.now) - state.now).coerceAtLeast(0) / 1000
    Pane(
        label = "Daily Quest",
        emphasis = true,
        trailing = {
            Text(
                if (done) "CLEARED" else "%02d:%02d:%02d".format(left / 3600, (left / 60) % 60, left % 60),
                style = SysType.Num.copy(color = if (done) Palette.Good else sys.text, fontSize = 13.sp),
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pct = (q.completion * 100).roundToInt()
            ProgressRing(q.completion, size = 84.dp, color = if (done) Palette.Good else sys.accent) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$pct", style = SysType.NumLarge.copy(color = sys.text))
                    Text("%", style = SysType.Label.copy(color = sys.muted))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(questTitle(q.quest.title), style = SysType.Header.copy(color = sys.text))
                Spacer(Modifier.height(4.dp))
                Text(q.quest.flavor, style = SysType.Small.copy(color = sys.muted), maxLines = 3)
            }
        }
        Spacer(Modifier.height(14.dp))
        Hairline()
        q.objectives.forEach { o ->
            ObjectiveRow(o, enabled = !done, onTrain = onTrain, onManual = onManual)
            Hairline()
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (done) "Rewards distributed." else "Failure to complete will result in an appropriate penalty.",
                style = SysType.Small.copy(color = if (done) Palette.Good else Palette.Crimson),
                modifier = Modifier.weight(1f),
            )
            Text("+${q.quest.xpReward} XP", style = SysType.Label.copy(color = Palette.Gold))
        }
    }
}

private fun questTitle(raw: String): String {
    val t = raw.removePrefix("Daily Quest:").removePrefix("Daily Quest").trim()
    return t.ifEmpty { "Daily Quest" }
}

@Composable
private fun ObjectiveRow(o: ObjectiveEntity, enabled: Boolean, onTrain: (ObjectiveType) -> Unit, onManual: (ObjectiveType) -> Unit) {
    val sys = LocalSys.current
    val trainable = o.type.isRep || o.type.isTimed
    Column {
        ListRow(
            title = o.type.label,
            subtitle = when {
                o.done -> null
                trainable && enabled -> "Tap to train · hold to log by hand"
                else -> o.type.tracker
            },
            onClick = if (trainable && enabled && !o.done) ({ onTrain(o.type) }) else null,
            onLongClick = if (trainable && enabled && !o.done) ({ onManual(o.type) }) else null,
        ) {
            Text(
                o.type.format(o.progress.coerceAtMost(o.target)),
                style = SysType.Num.copy(color = if (o.done) Palette.Good else sys.text),
            )
            Text(" / ${o.type.format(o.target)}", style = SysType.Num.copy(color = Palette.Dim))
            if (trainable && enabled && !o.done) Text("  ›", style = SysType.Num.copy(color = sys.accent))
            if (o.done) Text("  ✓", style = SysType.Num.copy(color = Palette.Good))
        }
        Meter(o.progress, o.target, if (o.done) Palette.Good else sys.accent, height = 2.dp)
        if (o.unverified > 0) {
            Text("${o.unverified} logged by hand (half XP)", style = SysType.Label.copy(color = Palette.Gold, fontSize = 10.sp), modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun PenaltyPane(q: QuestWithObjectives, now: Long) {
    val open = now >= q.quest.startsAt
    val secs = ((if (open) q.quest.deadline else q.quest.startsAt) - now).coerceAtLeast(0) / 1000
    Pane(label = "Penalty Quest", accent = Palette.Crimson, emphasis = true) {
        Text("Survival", style = SysType.Title.copy(color = Color.White))
        Spacer(Modifier.height(2.dp))
        Text(if (open) "Time remaining" else "The zone opens in", style = SysType.Label.copy(color = Palette.Silver))
        Text(
            "%02d:%02d:%02d".format(secs / 3600, (secs / 60) % 60, secs % 60),
            style = SysType.Huge.copy(color = Palette.Crimson, fontSize = 44.sp, fontFamily = dev.aoidoki.arise.ui.theme.Fonts.Mono),
        )
        Spacer(Modifier.height(10.dp))
        q.objectives.forEach { o ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(o.type.label, style = SysType.BodyStrong.copy(color = Color.White), modifier = Modifier.weight(1f))
                Text(o.type.format(o.progress.coerceAtMost(o.target)), style = SysType.Num.copy(color = Color.White))
                Text(" / ${o.type.format(o.target)}", style = SysType.Num.copy(color = Palette.Silver))
            }
            Spacer(Modifier.height(6.dp))
            Meter(o.progress, o.target, Palette.Crimson, height = 3.dp)
        }
        if (!open) {
            Spacer(Modifier.height(8.dp))
            Text("Only steps taken inside the zone count.", style = SysType.Small.copy(color = Palette.Silver))
        }
    }
}

@Composable
private fun TrialPane(q: QuestWithObjectives, state: UiState, onTrain: (ObjectiveType) -> Unit) {
    val today = q.quest.day == state.today
    Pane(label = "Rank-Up Trial", accent = Palette.Violet, trailing = { Text(if (today) "TODAY" else "TOMORROW", style = SysType.Label.copy(color = Palette.Violet)) }) {
        Text(q.quest.title.removePrefix("Rank-Up Trial:").trim(), style = SysType.Header.copy(color = Color.White))
        Spacer(Modifier.height(8.dp))
        Hairline()
        q.objectives.forEach { o ->
            ObjectiveRow(o, enabled = today, onTrain = onTrain, onManual = {})
            Hairline()
        }
    }
}

@Composable
private fun ManualDialog(type: ObjectiveType, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        SystemWindow(title = "Log by hand") {
            Text("${type.label} done without tracking. Counts at half XP.", style = SysType.Small.copy(color = LocalSys.current.muted))
            Spacer(Modifier.height(12.dp))
            SysField(type.unit, value, { value = it.filter(Char::isDigit).take(5) }, numeric = true)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SysButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                SysButton("Log", { value.toIntOrNull()?.takeIf { it > 0 }?.let(onConfirm) }, Modifier.weight(1f))
            }
        }
    }
}

private fun endOfDay(now: Long): Long {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
    return d.atStartOfDay(zone).toInstant().toEpochMilli()
}
