package dev.aoidoki.arise.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.aoidoki.arise.data.ObjectiveEntity
import dev.aoidoki.arise.data.QuestWithObjectives
import dev.aoidoki.arise.engine.ObjectiveType
import dev.aoidoki.arise.engine.PenaltyEngine
import dev.aoidoki.arise.engine.QuestStatus
import dev.aoidoki.arise.ui.UiState
import dev.aoidoki.arise.ui.components.GlowButton
import dev.aoidoki.arise.ui.components.StatBar
import dev.aoidoki.arise.ui.components.SysField
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.components.Tag
import dev.aoidoki.arise.ui.components.TypewriterText
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType
import java.time.Instant
import java.time.ZoneId

@Composable
fun QuestScreen(
    state: UiState,
    onTrain: (ObjectiveType) -> Unit,
    onManual: (ObjectiveType, Int) -> Unit,
    onRecovery: () -> Unit,
    onReroll: () -> Unit,
    onStartTracker: () -> Unit,
    trackerGranted: Boolean,
) {
    val sys = LocalSys.current
    val p = state.player ?: return
    var manualFor by remember { mutableStateOf<ObjectiveType?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (!trackerGranted) {
            SystemWindow(title = "Tracking Offline", accent = Palette.Gold) {
                Text("The System cannot see your steps. Grant Physical activity so quests track themselves.", style = SysType.Body.copy(color = sys.text))
                Spacer(Modifier.height(10.dp))
                GlowButton("Grant", onStartTracker, Modifier.fillMaxWidth(), accent = Palette.Gold)
            }
        }
        state.penalty?.let { PenaltyWindow(it, state.now) }
        state.trial?.let { TrialWindow(it, state, onTrain) }
        val daily = state.daily
        if (daily == null) {
            SystemWindow(title = "Daily Quest") {
                Text(
                    if (state.days.firstOrNull()?.result == "CLEARED") "Today's quest is complete. Rest, Player. A new quest arrives at midnight."
                    else "The System is preparing today's quest…",
                    style = SysType.Body.copy(color = sys.text),
                )
            }
        } else {
            DailyWindow(daily, state, onTrain = onTrain, onManual = { manualFor = it })
        }
        val recoveryLeft = PenaltyEngine.recoveryLeft(p, state.today)
        SystemWindow(title = "Options", icon = "?") {
            Text(
                "Sick or injured? Declare a Recovery day: today's quest won't be penalised and any Penalty Zone moves to tomorrow. $recoveryLeft left this month.",
                style = SysType.Small.copy(color = sys.muted),
            )
            Spacer(Modifier.height(8.dp))
            GlowButton(
                if (p.recoveryDay == state.today) "Recovering today" else "Recovery day",
                onRecovery, Modifier.fillMaxWidth(), accent = Palette.Good,
                enabled = p.recoveryDay != state.today && recoveryLeft > 0,
            )
            Spacer(Modifier.height(12.dp))
            Text("Spend 30 MP to have the System rewrite today's quest.", style = SysType.Small.copy(color = sys.muted))
            Spacer(Modifier.height(8.dp))
            GlowButton(
                "Reroll · 30 MP (${p.mp})", onReroll, Modifier.fillMaxWidth(), accent = Palette.Mp,
                enabled = p.mp >= 30 && daily?.quest?.status == QuestStatus.ACTIVE && state.aiBusy == null,
            )
            state.aiBusy?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = SysType.Small.copy(color = Palette.Violet))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    manualFor?.let { type ->
        ManualDialog(type, onDismiss = { manualFor = null }, onConfirm = { onManual(type, it); manualFor = null })
    }
}

@Composable
private fun DailyWindow(q: QuestWithObjectives, state: UiState, onTrain: (ObjectiveType) -> Unit, onManual: (ObjectiveType) -> Unit) {
    val sys = LocalSys.current
    val done = q.quest.status == QuestStatus.COMPLETED
    SystemWindow(title = "Quest Info") {
        Text(
            "[Daily Quest: ${q.quest.title.removePrefix("Daily Quest: ").removePrefix("Daily Quest")} has arrived.]".replace("[Daily Quest:  has", "[Daily Quest has"),
            style = SysType.Label.copy(color = sys.accentSoft),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        TypewriterText(q.quest.flavor, SysType.Body.copy(color = sys.muted), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text("GOAL", style = SysType.Header.copy(color = sys.text), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        q.objectives.forEach { o -> ObjectiveRow(o, onTrain, onManual, enabled = !done) }
        Spacer(Modifier.height(12.dp))
        if (done) {
            Text("QUEST COMPLETE", style = SysType.Title.copy(color = Palette.Good), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            val left = (endOfDay(state.now) - state.now).coerceAtLeast(0) / 1000
            Text(
                "Time remaining  %02d:%02d:%02d".format(left / 3600, (left / 60) % 60, left % 60),
                style = SysType.Mono.copy(color = sys.text, fontSize = 16.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "WARNING: Failure to complete the Daily Quest will result in an appropriate penalty.",
                style = SysType.Small.copy(color = Palette.Red), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(if (q.quest.source == "AI") "Written by the core" else "Standard issue", if (q.quest.source == "AI") Palette.Violet else sys.accent)
            Tag("XP ${q.quest.xpReward}", Palette.Gold)
        }
    }
}

@Composable
private fun ObjectiveRow(o: ObjectiveEntity, onTrain: (ObjectiveType) -> Unit, onManual: (ObjectiveType) -> Unit, enabled: Boolean) {
    val sys = LocalSys.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.type.label, style = SysType.Label.copy(color = sys.text), modifier = Modifier.weight(1f))
            Text(
                "[${o.type.format(o.progress.coerceAtMost(o.target))}/${o.type.format(o.target)}]",
                style = SysType.Mono.copy(color = if (o.done) Palette.Good else sys.text),
            )
            if (o.done) Text("  ✓", style = SysType.Label.copy(color = Palette.Good))
        }
        Spacer(Modifier.height(4.dp))
        StatBar("", o.progress, o.target, if (o.done) Palette.Good else sys.accent, height = 6.dp, showNumbers = false)
        if (enabled && !o.done && (o.type.isRep || o.type.isTimed)) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton("Train", { onTrain(o.type) }, Modifier.weight(2f))
                GlowButton("By hand", { onManual(o.type) }, Modifier.weight(1.2f), accent = sys.muted)
            }
        } else if (!o.done) {
            Text(o.type.tracker, style = SysType.Small.copy(color = sys.muted.copy(alpha = 0.7f)))
        }
        if (o.unverified > 0) Text("${o.unverified} logged by hand (half XP)", style = SysType.Small.copy(color = Palette.Gold.copy(alpha = 0.8f)))
    }
}

@Composable
private fun PenaltyWindow(q: QuestWithObjectives, now: Long) {
    val open = now >= q.quest.startsAt
    SystemWindow(title = q.quest.title, accent = Palette.Red, icon = "!") {
        Text(q.quest.flavor, style = SysType.Body.copy(color = Color(0xFFFFE6E8)))
        Spacer(Modifier.height(12.dp))
        q.objectives.forEach { o ->
            Row {
                Text(o.type.label, style = SysType.Label.copy(color = Color.White), modifier = Modifier.weight(1f))
                Text("[${o.type.format(o.progress.coerceAtMost(o.target))}/${o.type.format(o.target)}]", style = SysType.Mono.copy(color = Color.White))
            }
            Spacer(Modifier.height(4.dp))
            StatBar("", o.progress, o.target, Palette.Red, height = 8.dp, showNumbers = false)
        }
        Spacer(Modifier.height(12.dp))
        val secs = ((if (open) q.quest.deadline else q.quest.startsAt) - now).coerceAtLeast(0) / 1000
        Box(
            Modifier
                .fillMaxWidth()
                .background(Palette.Red.copy(alpha = 0.12f))
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (if (open) "SURVIVE  " else "OPENS IN  ") + "%02d:%02d:%02d".format(secs / 3600, (secs / 60) % 60, secs % 60),
                style = SysType.Title.copy(color = Palette.RedGlow),
            )
        }
        if (!open) {
            Spacer(Modifier.height(6.dp))
            Text("Only steps taken after the zone opens count.", style = SysType.Small.copy(color = Color(0xFFB57C84)))
        }
    }
}

@Composable
private fun TrialWindow(q: QuestWithObjectives, state: UiState, onTrain: (ObjectiveType) -> Unit) {
    SystemWindow(title = q.quest.title, accent = Palette.Violet, icon = "★") {
        Text(q.quest.flavor, style = SysType.Body.copy(color = LocalSys.current.text))
        Spacer(Modifier.height(8.dp))
        val when_ = if (q.quest.day == state.today) "Today, until midnight." else "Tomorrow, all day."
        Text(when_, style = SysType.Small.copy(color = Palette.Violet))
        Spacer(Modifier.height(8.dp))
        q.objectives.forEach { o -> ObjectiveRow(o, onTrain, {}, enabled = q.quest.day == state.today) }
    }
}

@Composable
private fun ManualDialog(type: ObjectiveType, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        SystemWindow(title = "Log by hand") {
            Text(
                "${type.label} done without tracking. The System trusts you — at half XP.",
                style = SysType.Small.copy(color = LocalSys.current.muted),
            )
            Spacer(Modifier.height(10.dp))
            SysField(type.unit, value, { value = it.filter(Char::isDigit).take(5) }, numeric = true)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton("Cancel", onDismiss, Modifier.weight(1f))
                GlowButton("Log", { value.toIntOrNull()?.takeIf { it > 0 }?.let(onConfirm) }, Modifier.weight(1f), filled = true)
            }
        }
    }
}

private fun endOfDay(now: Long): Long {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
    return d.atStartOfDay(zone).toInstant().toEpochMilli()
}
