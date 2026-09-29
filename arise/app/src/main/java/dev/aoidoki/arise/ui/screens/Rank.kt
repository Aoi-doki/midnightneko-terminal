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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aoidoki.arise.engine.Rank
import dev.aoidoki.arise.ui.UiState
import dev.aoidoki.arise.ui.components.GlowButton
import dev.aoidoki.arise.ui.components.RankBadge
import dev.aoidoki.arise.ui.components.StatBar
import dev.aoidoki.arise.ui.components.Pane
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.components.TypewriterText
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType

@Composable
fun RankScreen(state: UiState, onAcceptTrial: () -> Unit, onEvaluate: () -> Unit) {
    val sys = LocalSys.current
    val p = state.player ?: return
    val power = state.power
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Pane(label = "Hunter Association") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                RankBadge(p.rank, size = 120.dp)
                Spacer(Modifier.height(8.dp))
                Text(p.rank.displayName.uppercase() + " HUNTER", style = SysType.Title.copy(color = Color(p.rank.color)))
                Text(p.rank.epithet, style = SysType.Small.copy(color = sys.muted, letterSpacing = 2.sp))
            }
            Spacer(Modifier.height(16.dp))
            if (power != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("HUNTER POWER", style = SysType.Label.copy(color = sys.muted, letterSpacing = 3.sp), modifier = Modifier.weight(1f))
                    Text("${power.total}", style = SysType.Title.copy(color = sys.text))
                    Text(" / 100", style = SysType.Small.copy(color = sys.muted))
                }
                Spacer(Modifier.height(10.dp))
                StatBar("Body", power.body, 100, Palette.Good)
                Spacer(Modifier.height(8.dp))
                StatBar("Cardio", power.cardio, 100, Palette.Cyan)
                Spacer(Modifier.height(8.dp))
                StatBar("Strength", power.strength, 100, Palette.Hp)
                Spacer(Modifier.height(8.dp))
                StatBar("Discipline", power.discipline, 100, Palette.Gold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Body: BMI and progress to your goal. Cardio: 7-day average steps. Strength: your measured reps. Discipline: streak and completion. All age-adjusted.",
                    style = SysType.Small.copy(color = sys.muted),
                )
            }
        }

        val next = p.rank.next
        val trial = state.trial
        val trialRank = state.trialRank
        when {
            trial != null -> Pane(label = "Trial in Progress", accent = Palette.Violet) {
                Text("${trial.quest.title}. See the Quest tab.", style = SysType.Body.copy(color = sys.text))
            }
            trialRank != null -> Pane(label = "Rank-Up Available", accent = Palette.Gold) {
                TypewriterText(
                    "You qualify for promotion to ${trialRank.displayName}. Accepting issues a one-day Rank-Up Trial " +
                        "(today if before noon, otherwise tomorrow). Fail, and you may retry in 3 days.",
                    SysType.Body.copy(color = sys.text),
                )
                Spacer(Modifier.height(12.dp))
                GlowButton("Accept the trial", onAcceptTrial, Modifier.fillMaxWidth(), accent = Palette.Gold, filled = true, enabled = state.aiBusy == null)
                state.aiBusy?.let { Text(it, style = SysType.Small.copy(color = Palette.Violet), modifier = Modifier.padding(top = 6.dp)) }
            }
            next != null -> Pane(label = "Next: ${next.displayName}") {
                val pw = power?.total ?: 0
                KeyValue("Hunter Power", "$pw / ${next.minPower}", if (pw >= next.minPower) Palette.Good else sys.text)
                KeyValue("Level", "${p.level} / ${next.minLevel}", if (p.level >= next.minLevel) Palette.Good else sys.text)
                if (p.trialCooldownUntilDay >= state.today) KeyValue("Trial cooldown", "${p.trialCooldownUntilDay - state.today + 1} day(s)", Palette.Red)
            }
        }

        Pane(label = "Evaluation", accent = Palette.Violet) {
            state.evaluation?.let {
                TypewriterText(it, SysType.Body.copy(color = sys.text))
                Spacer(Modifier.height(10.dp))
            }
            GlowButton("Request evaluation", onEvaluate, Modifier.fillMaxWidth(), accent = Palette.Violet, enabled = state.aiBusy == null)
        }

        Pane(label = "Ranks") {
            Rank.entries.forEach { r ->
                val reached = r.ordinal <= p.rank.ordinal
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    RankBadge(r, size = 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.displayName.uppercase(), style = SysType.Label.copy(color = if (reached) Color(r.color) else sys.muted))
                        Text(r.epithet, style = SysType.Small.copy(color = sys.muted))
                    }
                    Text(
                        if (r == Rank.E) "start" else "Power ${r.minPower}+ · Lv ${r.minLevel}+",
                        style = SysType.Small.copy(color = sys.muted), textAlign = TextAlign.End,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
