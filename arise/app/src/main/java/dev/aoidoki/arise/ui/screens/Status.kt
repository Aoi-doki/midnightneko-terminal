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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aoidoki.arise.data.PlayerEntity
import dev.aoidoki.arise.engine.BodyMetrics
import dev.aoidoki.arise.engine.PenaltyEngine
import dev.aoidoki.arise.engine.Progression
import dev.aoidoki.arise.engine.StatType
import dev.aoidoki.arise.ui.UiState
import dev.aoidoki.arise.ui.components.Divider
import dev.aoidoki.arise.ui.components.GlowButton
import dev.aoidoki.arise.ui.components.RankBadge
import dev.aoidoki.arise.ui.components.StatBar
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.components.Tag
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType

@Composable
fun StatusScreen(state: UiState, onAllocate: (StatType) -> Unit, onTitle: (String) -> Unit, onGoQuest: () -> Unit) {
    val p = state.player ?: return
    val sys = LocalSys.current
    val today = state.today
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (state.penalty != null) {
            SystemWindow(title = "Penalty Zone", accent = Palette.Red, icon = "!") {
                Text("A Penalty Quest is active. Survive it.", style = SysType.Body.copy(color = sys.text))
                Spacer(Modifier.height(10.dp))
                GlowButton("Go to quest", onGoQuest, Modifier.fillMaxWidth(), accent = Palette.Red)
            }
        }
        SystemWindow(title = "Status") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.level.toString(), style = SysType.Huge.copy(color = sys.text))
                    Text("LEVEL", style = SysType.Label.copy(color = sys.muted, letterSpacing = 6.sp))
                }
                RankBadge(p.rank, size = 78.dp)
            }
            Spacer(Modifier.height(12.dp))
            KeyValue("Name", p.name)
            KeyValue("Job", p.job)
            KeyValue("Title", p.title)
            KeyValue("Rank", "${p.rank.displayName} · ${p.rank.epithet}")
            Spacer(Modifier.height(14.dp))
            StatBar("HP", p.hp, Progression.maxHp(p), Palette.Hp)
            Spacer(Modifier.height(10.dp))
            StatBar("MP", p.mp, Progression.maxMp(p), Palette.Mp)
            Spacer(Modifier.height(10.dp))
            StatBar("Fatigue", p.fatigue, 100, Palette.Fatigue)
            Spacer(Modifier.height(10.dp))
            StatBar("XP", p.xp, Progression.xpToNext(p.level), Palette.Xp)
            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            StatGrid(p, onAllocate)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Available ability points: ", style = SysType.Label.copy(color = sys.muted))
                Text(p.freePoints.toString(), style = SysType.Stat.copy(color = if (p.freePoints > 0) Palette.Gold else sys.text))
            }
            val effects = buildList {
                if (Progression.isWeakened(p, today)) add("Weakened · XP −25%" to Palette.Red)
                if (p.recoveryDay == today) add("Recovering" to Palette.Good)
                if (p.fatigue >= 70) add("Exhausted" to Palette.Fatigue)
                if (p.streak >= 7) add("Streak ${p.streak}" to Palette.Gold)
            }
            if (effects.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { effects.forEach { (t, c) -> Tag(t, c) } }
            }
        }
        BodyWindow(state, p)
        if (p.assessment.isNotBlank() || p.limitations.notes.isNotEmpty()) {
            SystemWindow(title = "System Assessment", accent = Palette.Violet) {
                if (p.assessment.isNotBlank()) Text(p.assessment, style = SysType.Body.copy(color = sys.text))
                p.limitations.notes.forEach {
                    Spacer(Modifier.height(6.dp))
                    Text("• $it", style = SysType.Small.copy(color = Palette.Gold))
                }
            }
        }
        if (p.titles.isNotEmpty()) TitlesWindow(p, onTitle)
        SystemWindow(title = "Record") {
            KeyValue("Quests cleared", p.questsCompleted.toString())
            KeyValue("Quests failed", p.questsFailed.toString())
            KeyValue("Best streak", "${p.bestStreak} days")
            KeyValue("Deaths", p.deaths.toString())
            KeyValue("Recovery days left", "${PenaltyEngine.recoveryLeft(p, today)} this month")
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatGrid(p: PlayerEntity, onAllocate: (StatType) -> Unit) {
    val rows = StatType.entries.chunked(2)
    rows.forEach { pair ->
        Row(Modifier.fillMaxWidth()) {
            pair.forEach { s ->
                Row(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${s.short}:", style = SysType.Stat.copy(color = LocalSys.current.muted), modifier = Modifier.width(58.dp))
                    Text(Progression.stat(p, s).toString(), style = SysType.Stat.copy(color = LocalSys.current.text))
                    if (p.freePoints > 0) {
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .size(26.dp)
                                .border(1.dp, Palette.Gold, RoundedCornerShape(3.dp))
                                .clickable { onAllocate(s) },
                            contentAlignment = Alignment.Center,
                        ) { Text("+", style = SysType.Label.copy(color = Palette.Gold, fontWeight = FontWeight.Bold)) }
                    }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BodyWindow(state: UiState, p: PlayerEntity) {
    val sys = LocalSys.current
    val imperial = state.settings.imperial
    fun w(kg: Double) = if (imperial) "%.1f lb".format(kg / 0.45359237) else "%.1f kg".format(kg)
    val lost = p.startWeightKg - p.weightKg
    val toLose = (p.startWeightKg - p.goalWeightKg).coerceAtLeast(0.1)
    val bmi = BodyMetrics.bmi(p.weightKg, p.heightCm)
    val steps = state.todayLog?.steps ?: 0
    SystemWindow(title = "Body") {
        Row {
            Column(Modifier.weight(1f)) {
                Text(w(p.weightKg), style = SysType.Title.copy(color = sys.text))
                Text("CURRENT", style = SysType.Small.copy(color = sys.muted, letterSpacing = 2.sp))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(w(p.goalWeightKg), style = SysType.Title.copy(color = Palette.Good))
                Text("GOAL", style = SysType.Small.copy(color = sys.muted, letterSpacing = 2.sp))
            }
        }
        Spacer(Modifier.height(10.dp))
        StatBar(
            if (lost >= 0) "Lost ${w(lost)}" else "Gained ${w(-lost)}",
            ((lost / toLose) * 100).toInt().coerceIn(0, 100), 100, Palette.Good, showNumbers = false,
        )
        Spacer(Modifier.height(10.dp))
        KeyValue("BMI", "%.1f · %s".format(bmi, BodyMetrics.bmiClass(bmi)))
        KeyValue("Steps today", "%,d".format(steps))
        KeyValue("Walked off today", "≈ ${BodyMetrics.walkKcal(steps, p.weightKg, p.heightCm)} kcal")
        KeyValue("Resting burn", "≈ ${BodyMetrics.bmr(p.weightKg, p.heightCm, p.age, p.sexHint)} kcal/day")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitlesWindow(p: PlayerEntity, onTitle: (String) -> Unit) {
    SystemWindow(title = "Titles", accent = Palette.Violet) {
        Text("Tap to equip.", style = SysType.Small.copy(color = LocalSys.current.muted))
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            p.titles.forEach { t ->
                Box(Modifier.clickable { onTitle(t) }) { Tag(t, if (t == p.title) Palette.Gold else Palette.Violet) }
            }
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = LocalSys.current.text) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key.uppercase(), style = SysType.Small.copy(color = LocalSys.current.muted, letterSpacing = 1.5.sp), modifier = Modifier.weight(1f))
        Text(value, style = SysType.Label.copy(color = valueColor))
    }
}
