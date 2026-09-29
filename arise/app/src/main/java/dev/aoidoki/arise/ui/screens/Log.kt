package dev.aoidoki.arise.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.aoidoki.arise.data.DayLogEntity
import dev.aoidoki.arise.data.EventEntity
import dev.aoidoki.arise.data.WeightEntity
import dev.aoidoki.arise.ui.UiState
import dev.aoidoki.arise.ui.components.GlowButton
import dev.aoidoki.arise.ui.components.SysField
import dev.aoidoki.arise.ui.components.Pane
import dev.aoidoki.arise.ui.components.SystemWindow
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LogScreen(state: UiState, onAddWeight: (Double) -> Unit) {
    val sys = LocalSys.current
    val p = state.player ?: return
    var adding by remember { mutableStateOf(false) }
    val imperial = state.settings.imperial
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Pane(label = "Body Transformation") {
            WeightChart(state.weights, p.goalWeightKg, imperial)
            Spacer(Modifier.height(10.dp))
            val latest = state.weights.lastOrNull()
            if (latest != null) {
                Text(
                    "Last weigh-in: ${fmtKg(latest.kg, imperial)} · ${date(latest.time)} · ${latest.source.lowercase().replace('_', ' ')}",
                    style = SysType.Small.copy(color = sys.muted),
                )
            }
            Spacer(Modifier.height(10.dp))
            GlowButton("Log weight", { adding = true }, Modifier.fillMaxWidth())
            Text("Weigh-ins from a smart scale or Samsung Health arrive through Health Connect automatically.", style = SysType.Small.copy(color = sys.muted.copy(alpha = 0.7f)))
        }
        Pane(label = "Last 14 Days") { DayStrip(state.days.take(14).reversed(), state.today) }
        Pane(label = "System Log") {
            if (state.log.isEmpty()) Text("No messages yet.", style = SysType.Small.copy(color = sys.muted))
            state.log.take(60).forEach { EventRow(it) }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (adding) {
        WeightDialog(imperial, onDismiss = { adding = false }) { kg ->
            onAddWeight(kg)
            adding = false
        }
    }
}

private fun fmtKg(kg: Double, imperial: Boolean) = if (imperial) "%.1f lb".format(kg / 0.45359237) else "%.1f kg".format(kg)

private fun date(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))

@Composable
private fun WeightChart(weights: List<WeightEntity>, goal: Double, imperial: Boolean) {
    val sys = LocalSys.current
    if (weights.size < 2) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Log a few weigh-ins and your transformation appears here.", style = SysType.Small.copy(color = sys.muted))
        }
        return
    }
    val min = minOf(weights.minOf { it.kg }, goal) - 1
    val max = weights.maxOf { it.kg } + 1
    val t0 = weights.first().time
    val t1 = weights.last().time.coerceAtLeast(t0 + 1)
    Column {
        Row {
            Text(fmtKg(max, imperial), style = SysType.Small.copy(color = sys.muted), modifier = Modifier.weight(1f))
            Text("goal ${fmtKg(goal, imperial)}", style = SysType.Small.copy(color = Palette.Good))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * size.width
            fun y(kg: Double) = ((max - kg) / (max - min)).toFloat() * size.height
            val gy = y(goal)
            drawLine(Palette.Good.copy(alpha = 0.7f), Offset(0f, gy), Offset(size.width, gy), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            val path = Path()
            weights.forEachIndexed { i, w -> if (i == 0) path.moveTo(x(w.time), y(w.kg)) else path.lineTo(x(w.time), y(w.kg)) }
            val fill = Path().apply {
                addPath(path)
                lineTo(x(weights.last().time), size.height)
                lineTo(x(weights.first().time), size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(sys.accent.copy(alpha = 0.3f), Color.Transparent)))
            drawPath(path, sys.accent.copy(alpha = 0.3f), style = Stroke(8.dp.toPx()))
            drawPath(path, sys.accentSoft, style = Stroke(2.5.dp.toPx()))
            weights.forEach { w -> drawCircle(Color.White, 3.dp.toPx(), Offset(x(w.time), y(w.kg))) }
        }
        Text(fmtKg(min, imperial), style = SysType.Small.copy(color = sys.muted))
    }
}

@Composable
private fun DayStrip(days: List<DayLogEntity>, today: Long) {
    val sys = LocalSys.current
    if (days.isEmpty()) {
        Text("History starts today.", style = SysType.Small.copy(color = sys.muted))
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEach { d ->
            val color = when (d.result) {
                "CLEARED" -> Palette.Good
                "FAILED" -> Palette.Red
                "RECOVERY" -> Palette.Mp
                else -> if (d.day == today) sys.accent else Palette.Dim
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(width = 14.dp, height = (8 + 40 * d.completion).dp)
                        .then(Modifier),
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawRect(color.copy(alpha = 0.85f)) }
                }
                Spacer(Modifier.height(4.dp))
                Text(LocalDate.ofEpochDay(d.day).dayOfMonth.toString(), style = SysType.Small.copy(color = sys.muted))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Legend("Cleared", Palette.Good); Legend("Failed", Palette.Red); Legend("Recovery", Palette.Mp)
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawRect(color) }
        Spacer(Modifier.width(4.dp))
        Text(text, style = SysType.Small.copy(color = LocalSys.current.muted))
    }
}

@Composable
private fun EventRow(e: EventEntity) {
    val sys = LocalSys.current
    val color = when (e.type) {
        "PENALTY_STARTED", "PENALTY_FAILED", "DEATH", "WARNING" -> Palette.Red
        "LEVEL_UP", "RANK_UP", "WEIGHT" -> Palette.Gold
        "TITLE" -> Palette.Violet
        "QUEST_COMPLETED", "PENALTY_CLEARED" -> Palette.Good
        else -> sys.accent
    }
    Row(Modifier.padding(vertical = 5.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp),
        ) { Canvas(Modifier.fillMaxSize()) { drawCircle(color) } }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("${e.title.uppercase()} · ${date(e.time)}", style = SysType.Small.copy(color = color))
            Text(e.message, style = SysType.Small.copy(color = sys.text))
        }
    }
}

@Composable
private fun WeightDialog(imperial: Boolean, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var v by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        SystemWindow(title = "Weigh-in") {
            SysField(if (imperial) "Weight (lb)" else "Weight (kg)", v, { v = it }, numeric = true)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton("Cancel", onDismiss, Modifier.weight(1f))
                GlowButton("Record", {
                    v.replace(',', '.').toDoubleOrNull()?.let { n ->
                        val kg = if (imperial) n * 0.45359237 else n
                        if (kg in 30.0..350.0) onConfirm(kg)
                    }
                }, Modifier.weight(1f), filled = true)
            }
        }
    }
}
