package dev.aoidoki.arise.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import dev.aoidoki.arise.data.PlayerEntity
import dev.aoidoki.arise.engine.BodyMetrics
import dev.aoidoki.arise.engine.PenaltyEngine
import dev.aoidoki.arise.engine.Progression
import dev.aoidoki.arise.engine.StatType
import dev.aoidoki.arise.ui.UiState
import dev.aoidoki.arise.ui.components.Hairline
import dev.aoidoki.arise.ui.components.KeyValueRow
import dev.aoidoki.arise.ui.components.Meter
import dev.aoidoki.arise.ui.components.Pane
import dev.aoidoki.arise.ui.components.RankBadge
import dev.aoidoki.arise.ui.components.StatBar
import dev.aoidoki.arise.ui.components.Tag
import dev.aoidoki.arise.ui.components.systemShape
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType

@Composable
fun StatusScreen(state: UiState, onAllocate: (StatType) -> Unit, onTitle: (String) -> Unit, onGoQuest: () -> Unit) {
    val p = state.player ?: return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusPane(state, p, onAllocate)
        BodyPane(state, p)
        AssessmentPane(p)
        if (p.titles.isNotEmpty()) TitlesPane(p, onTitle)
        RecordPane(p, state.today)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusPane(state: UiState, p: PlayerEntity, onAllocate: (StatType) -> Unit) {
    val sys = LocalSys.current
    Pane(label = "Status", emphasis = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LEVEL", style = SysType.Label.copy(color = sys.muted))
                Text("${p.level}", style = SysType.Huge.copy(color = sys.text))
            }
            RankBadge(p.rank, size = 60.dp)
        }
        Spacer(Modifier.height(8.dp))
        KeyValueRow("Name", p.name)
        KeyValueRow("Job", p.job)
        KeyValueRow("Title", p.title)
        KeyValueRow("Rank", p.rank.displayName)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatBar("HP", p.hp, Progression.maxHp(p), Palette.Hp, Modifier.weight(1f))
            StatBar("MP", p.mp, Progression.maxMp(p), Palette.Mp, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatBar("Fatigue", p.fatigue, 100, Palette.Fatigue, Modifier.weight(1f))
            StatBar("EXP", p.xp, Progression.xpToNext(p.level), sys.accent, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Hairline()
        Spacer(Modifier.height(4.dp))
        StatType.entries.chunked(2).forEach { pair ->
            Row {
                pair.forEach { s -> StatCell(s, Progression.stat(p, s), p.freePoints > 0, Modifier.weight(1f)) { onAllocate(s) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("REMAINING POINTS", style = SysType.Label.copy(color = sys.muted), modifier = Modifier.weight(1f))
            Text("${p.freePoints}", style = SysType.NumLarge.copy(color = if (p.freePoints > 0) Palette.Gold else sys.text))
        }
        val effects = buildList {
            if (Progression.isWeakened(p, state.today)) add("Weakened · −25% EXP" to Palette.Crimson)
            if (p.recoveryDay == state.today) add("Recovering" to Palette.Good)
            if (p.fatigue >= 70) add("Exhausted" to Palette.Fatigue)
            if (p.streakWards > 0) add("Warded ×${p.streakWards}" to Palette.Violet)
            if (p.streak >= 3) add("Streak ${p.streak}" to Palette.Gold)
        }
        if (effects.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { effects.forEach { (t, c) -> Tag(t, c) } }
        }
    }
}

@Composable
private fun StatCell(s: StatType, value: Int, canRaise: Boolean, modifier: Modifier, onRaise: () -> Unit) {
    val sys = LocalSys.current
    Row(modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.full.uppercase(), style = SysType.Label.copy(color = sys.muted, fontSize = 10.sp))
            Text("$value", style = SysType.NumLarge.copy(color = sys.text))
        }
        if (canRaise) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .size(28.dp)
                    .border(1.dp, Palette.Gold, systemShape(5.dp))
                    .clickable(onClick = onRaise),
                contentAlignment = Alignment.Center,
            ) { Text("+", style = SysType.Num.copy(color = Palette.Gold)) }
        }
    }
}

@Composable
private fun BodyPane(state: UiState, p: PlayerEntity) {
    val sys = LocalSys.current
    val imperial = state.settings.imperial
    fun w(kg: Double) = if (imperial) "%.1f lb".format(kg / 0.45359237) else "%.1f kg".format(kg)
    val lost = p.startWeightKg - p.weightKg
    val toLose = (p.startWeightKg - p.goalWeightKg).coerceAtLeast(0.1)
    val bmi = BodyMetrics.bmi(p.weightKg, p.heightCm)
    val steps = state.todayLog?.steps ?: 0
    Pane(label = "Body") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(w(p.weightKg), style = SysType.NumLarge.copy(color = sys.text))
            Text("  →  ${w(p.goalWeightKg)}", style = SysType.Num.copy(color = Palette.Good), modifier = Modifier.weight(1f))
            Text(if (lost >= 0) "−${w(lost)}" else "+${w(-lost)}", style = SysType.Num.copy(color = if (lost >= 0) Palette.Good else Palette.Crimson))
        }
        Spacer(Modifier.height(8.dp))
        Meter(((lost / toLose) * 100).toInt().coerceIn(0, 100), 100, Palette.Good, height = 3.dp)
        Spacer(Modifier.height(12.dp))
        Row {
            MiniStat("BMI", "%.1f".format(bmi), Modifier.weight(1f))
            MiniStat("Steps", "%,d".format(steps), Modifier.weight(1f))
            MiniStat("Walked", "${BodyMetrics.walkKcal(steps, p.weightKg, p.heightCm)} kcal", Modifier.weight(1f))
        }
    }
}

@Composable
fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, color: Color = LocalSys.current.text) {
    Column(modifier) {
        Text(label.uppercase(), style = SysType.Label.copy(color = LocalSys.current.muted, fontSize = 10.sp))
        Spacer(Modifier.height(2.dp))
        Text(value, style = SysType.Num.copy(color = color))
    }
}

@Composable
private fun AssessmentPane(p: PlayerEntity) {
    if (p.assessment.isBlank() && p.limitations.notes.isEmpty()) return
    val sys = LocalSys.current
    var open by remember { mutableStateOf(false) }
    Pane(label = "System assessment", trailing = { Text(if (open) "−" else "+", style = SysType.Num.copy(color = sys.accent), modifier = Modifier.clickable { open = !open }) }) {
        Text(
            p.assessment.ifBlank { "Constraints registered." },
            style = SysType.Small.copy(color = sys.muted),
            maxLines = if (open) Int.MAX_VALUE else 2,
            modifier = Modifier.clickable { open = !open },
        )
        if (open) {
            p.limitations.notes.forEach {
                Spacer(Modifier.height(6.dp))
                Text("› $it", style = SysType.Small.copy(color = Palette.Gold))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitlesPane(p: PlayerEntity, onTitle: (String) -> Unit) {
    Pane(label = "Titles") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            p.titles.forEach { t ->
                Box(Modifier.clickable { onTitle(t) }) { Tag(t, if (t == p.title) Palette.Gold else LocalSys.current.muted) }
            }
        }
    }
}

@Composable
private fun RecordPane(p: PlayerEntity, today: Long) {
    Pane(label = "Record") {
        Row {
            MiniStat("Cleared", "${p.questsCompleted}", Modifier.weight(1f))
            MiniStat("Failed", "${p.questsFailed}", Modifier.weight(1f))
            MiniStat("Best streak", "${p.bestStreak}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row {
            MiniStat("Deaths", "${p.deaths}", Modifier.weight(1f))
            MiniStat("Recovery", "${PenaltyEngine.recoveryLeft(p, today)} left", Modifier.weight(1f))
            MiniStat("Gold", "${p.gold}", Modifier.weight(1f), Palette.Gold)
        }
        if (p.overrides > 0) {
            Spacer(Modifier.height(12.dp))
            Row { MiniStat("Overrides", "${p.overrides}", Modifier.weight(1f), Palette.Crimson) }
        }
    }
}

/** Shared by other screens. */
@Composable
fun KeyValue(key: String, value: String, valueColor: Color = LocalSys.current.text) = KeyValueRow(key, value, valueColor)
