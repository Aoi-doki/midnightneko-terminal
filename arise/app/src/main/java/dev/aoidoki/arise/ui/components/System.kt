package dev.aoidoki.arise.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aoidoki.arise.engine.Rank
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** True in previews and screenshot tests: animations jump to their end state so output is deterministic. */
@Composable
fun staticMode(): Boolean = LocalInspectionMode.current || StaticUi.enabled

object StaticUi {
    @Volatile
    var enabled = false
}

/** Deep-void background with slowly rising motes of mana and a faint grid. */
@Composable
fun SystemBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val sys = LocalSys.current
    val still = staticMode()
    val motes = remember { List(38) { Triple(Random.nextFloat(), Random(it * 31).nextFloat(), 0.5f + Random.nextFloat()) } }
    val t = if (still) 0.3f else {
        val inf = rememberInfiniteTransition(label = "motes")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "t").value
    }
    Box(
        modifier
            .fillMaxSize()
            .background(sys.background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(sys.backgroundGlow, sys.background),
                        center = Offset(size.width * 0.5f, size.height * 0.12f),
                        radius = size.maxDimension * 0.9f,
                    ),
                )
                val step = 48.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(sys.accent.copy(alpha = 0.035f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(sys.accent.copy(alpha = 0.035f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }
                for ((mx, my, sp) in motes) {
                    val py = ((my - t * sp) % 1f + 1f) % 1f
                    val px = mx + 0.015f * sin((t * 2 * PI * sp + my * 10).toFloat())
                    val a = (sin((py * PI).toFloat()) * 0.55f).coerceAtLeast(0f)
                    drawCircle(sys.accentSoft.copy(alpha = a * 0.5f), radius = 1.2.dp.toPx() * sp, center = Offset(px * size.width, py * size.height))
                    drawCircle(sys.accent.copy(alpha = a * 0.12f), radius = 5.dp.toPx() * sp, center = Offset(px * size.width, py * size.height))
                }
            },
        content = content,
    )
}

private fun DrawScope.glowFrame(color: Color, corner: Float, strength: Float, brackets: Boolean = true) {
    for (i in 4 downTo 1) {
        val w = i * 3.dp.toPx()
        drawRoundRect(
            color = color.copy(alpha = 0.05f * strength * (5 - i)),
            topLeft = Offset(-w / 2, -w / 2),
            size = Size(size.width + w, size.height + w),
            cornerRadius = CornerRadius(corner + w / 2),
            style = Stroke(width = w),
        )
    }
    drawRoundRect(color = color.copy(alpha = 0.85f), cornerRadius = CornerRadius(corner), style = Stroke(width = 1.2.dp.toPx()))
    if (!brackets) return
    // Corner brackets, the System window's signature.
    val l = 14.dp.toPx()
    val o = -4.dp.toPx()
    val sw = 2.dp.toPx()
    val c = color.copy(alpha = 1f)
    fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(c, Offset(x, y), Offset(x + dx * l, y), sw, StrokeCap.Square)
        drawLine(c, Offset(x, y), Offset(x, y + dy * l), sw, StrokeCap.Square)
    }
    bracket(o, o, 1f, 1f)
    bracket(size.width - o, o, -1f, 1f)
    bracket(o, size.height - o, 1f, -1f)
    bracket(size.width - o, size.height - o, -1f, -1f)
}

/**
 * The System window: translucent panel, glowing border, corner brackets, a "[ ! ]" header and an
 * opening animation that unfolds it from its centre line like the show.
 */
@Composable
fun SystemWindow(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = LocalSys.current.accent,
    icon: String = "!",
    animate: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sys = LocalSys.current
    val still = staticMode() || !animate
    val open = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) open.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    val shimmer = if (still) 0f else {
        val inf = rememberInfiniteTransition(label = "scan")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3800, easing = LinearEasing)), label = "s").value
    }
    val corner = 6.dp
    Box(
        modifier
            .padding(6.dp)
            .graphicsLayer {
                scaleY = 0.04f + 0.96f * open.value
                alpha = open.value.coerceIn(0f, 1f)
            }
            .drawBehind { glowFrame(accent, corner.toPx(), 1f) }
            .background(sys.panel, RoundedCornerShape(corner))
            .drawWithContent {
                drawContent()
                val y = size.height * shimmer
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.07f), Color.Transparent), startY = y - 40f, endY = y + 40f),
                    topLeft = Offset(0f, y - 40f),
                    size = Size(size.width, 80f),
                )
            },
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .border(1.4.dp, accent, RoundedCornerShape(3.dp))
                            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(icon, style = SysType.Label.copy(color = sys.text, fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(title.uppercase(), style = SysType.Header.copy(color = sys.text), textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            content()
        }
    }
}

/** Text that types itself out, like System messages do. */
@Composable
fun TypewriterText(text: String, style: TextStyle, modifier: Modifier = Modifier, charMillis: Long = 18, textAlign: TextAlign? = null) {
    val still = staticMode()
    var shown by remember(text) { mutableIntStateOf(if (still) text.length else 0) }
    LaunchedEffect(text) {
        if (still) return@LaunchedEffect
        while (shown < text.length) {
            delay(charMillis)
            shown = (shown + 1).coerceAtMost(text.length)
        }
    }
    Text(text.take(shown), style = style, modifier = modifier, textAlign = textAlign)
}

@Composable
fun StatBar(
    label: String,
    value: Int,
    max: Int,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    showNumbers: Boolean = true,
) {
    val sys = LocalSys.current
    val frac by animateFloatAsState(if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f), tween(700), label = "bar")
    Column(modifier) {
        if (label.isNotEmpty() || showNumbers) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = SysType.Label.copy(color = sys.text))
                if (showNumbers) Text("$value / $max", style = SysType.Mono.copy(color = sys.muted))
            }
            Spacer(Modifier.height(4.dp))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(color.copy(alpha = 0.12f), cornerRadius = r)
            drawRoundRect(color.copy(alpha = 0.35f), cornerRadius = r, style = Stroke(1.dp.toPx()))
            if (frac > 0f) {
                val w = size.width * frac
                drawRoundRect(color.copy(alpha = 0.25f), topLeft = Offset(0f, -2f), size = Size(w, size.height + 4f), cornerRadius = r)
                drawRoundRect(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color)), size = Size(w, size.height), cornerRadius = r)
                drawRoundRect(Color.White.copy(alpha = 0.25f), size = Size(w, size.height * 0.35f), cornerRadius = r)
            }
        }
    }
}

@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = LocalSys.current.accent,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val sys = LocalSys.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val c = if (enabled) accent else Palette.Dim
    Box(
        modifier
            .drawBehind { if (enabled) glowFrame(c, 4.dp.toPx(), if (pressed) 1.8f else 0.7f, brackets = false) else drawRoundRect(c, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx())) }
            .background(if (filled || pressed) c.copy(alpha = 0.22f) else c.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = SysType.Label.copy(color = if (enabled) sys.text else Palette.Dim, letterSpacing = 3.sp), textAlign = TextAlign.Center)
    }
}

/** Rank crest: a glowing hexagon with the rank letter. */
@Composable
fun RankBadge(rank: Rank, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val color = Color(rank.color)
    val still = staticMode()
    val pulse = if (still) 1f else {
        val inf = rememberInfiniteTransition(label = "pulse")
        inf.animateFloat(0.7f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "p").value
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2 * 0.92f
            val c = center
            val hex = Path().apply {
                for (k in 0..5) {
                    val a = (PI / 3 * k - PI / 2).toFloat()
                    val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
                    if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            for (i in 3 downTo 1) drawPath(hex, color.copy(alpha = 0.08f * i * pulse), style = Stroke(width = i * 5.dp.toPx()))
            drawPath(hex, Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), center = c, radius = r))
            drawPath(hex, color, style = Stroke(width = 2.dp.toPx()))
        }
        Text(rank.label, style = SysType.Title.copy(color = Color.White, fontSize = (size.value * 0.42f).sp, letterSpacing = 0.sp))
    }
}

@Composable
fun Tag(text: String, color: Color = LocalSys.current.accent, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), style = SysType.Small.copy(color = color, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp))
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    val sys = LocalSys.current
    Canvas(
        modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, sys.accent.copy(alpha = 0.6f), Color.Transparent)),
            Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(),
        )
    }
}

/** Themed single-line (or multi-line) text field with a System label. */
@Composable
fun SysField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String = "",
    minLines: Int = 1,
) {
    val sys = LocalSys.current
    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label.uppercase(), style = SysType.Small.copy(color = sys.muted, letterSpacing = 2.sp))
            Spacer(Modifier.height(4.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = SysType.Body.copy(color = sys.text),
            cursorBrush = SolidColor(sys.accent),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, sys.accent.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                .background(sys.accent.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = SysType.Body.copy(color = sys.muted.copy(alpha = 0.6f)))
                    inner()
                }
            },
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sys = LocalSys.current
    Box(
        modifier
            .border(1.dp, if (selected) sys.accent else sys.accent.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
            .background(if (selected) sys.accent.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = SysType.Label.copy(color = if (selected) sys.text else sys.muted))
    }
}
