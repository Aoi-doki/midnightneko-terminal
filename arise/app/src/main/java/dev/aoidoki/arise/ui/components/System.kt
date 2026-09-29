package dev.aoidoki.arise.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aoidoki.arise.engine.Rank
import dev.aoidoki.arise.ui.theme.LocalSys
import dev.aoidoki.arise.ui.theme.Palette
import dev.aoidoki.arise.ui.theme.SysType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** True in previews and screenshot tests: animations jump to their end state so output is deterministic. */
@Composable
fun staticMode(): Boolean = LocalInspectionMode.current || StaticUi.enabled

object StaticUi {
    @Volatile
    var enabled = false
}

/** The angular System frame: top-left and bottom-right corners cut, the other two square. */
fun systemShape(cut: Dp = 10.dp): Shape = CutCornerShape(topStart = cut, topEnd = 0.dp, bottomEnd = cut, bottomStart = 0.dp)

/** A smoky void: two soft pools of light in the dark, nothing moving. */
@Composable
fun SystemBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val sys = LocalSys.current
    Box(
        modifier
            .fillMaxSize()
            .background(sys.background)
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Palette.Charcoal, sys.background)))
                drawRect(
                    Brush.radialGradient(
                        listOf(sys.backgroundGlow.copy(alpha = 0.9f), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.08f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(sys.accent.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 0.65f),
                        radius = size.maxDimension * 0.5f,
                    ),
                )
            },
        content = content,
    )
}

/** Short runic ticks at the two cut corners: the one ornament the System allows itself. */
private fun DrawScope.runeTicks(color: Color, cut: Float) {
    val l = 6.dp.toPx()
    val w = 1.dp.toPx()
    // along the top-left cut
    drawLine(color, Offset(0f, cut + 3.dp.toPx()), Offset(0f, cut + 3.dp.toPx() + l), w * 2)
    drawLine(color, Offset(cut + 3.dp.toPx(), 0f), Offset(cut + 3.dp.toPx() + l, 0f), w * 2)
    // along the bottom-right cut
    drawLine(color, Offset(size.width, size.height - cut - 3.dp.toPx()), Offset(size.width, size.height - cut - 3.dp.toPx() - l), w * 2)
    drawLine(color, Offset(size.width - cut - 3.dp.toPx(), size.height), Offset(size.width - cut - 3.dp.toPx() - l, size.height), w * 2)
}

private fun DrawScope.outlinePath(shape: Shape, density: androidx.compose.ui.unit.Density): Path {
    val outline = shape.createOutline(size, LayoutDirection.Ltr, density)
    return when (outline) {
        is Outline.Generic -> outline.path
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
    }
}

/**
 * A floating System pane. 1px neon border on an angular frame; [emphasis] adds the faint outer
 * glow and the rune ticks. Use emphasis for the one pane per screen that matters.
 */
@Composable
fun Pane(
    modifier: Modifier = Modifier,
    label: String? = null,
    accent: Color = LocalSys.current.accent,
    emphasis: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    fill: Color = LocalSys.current.panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val shape = systemShape()
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val path = outlinePath(shape, density)
                if (emphasis) {
                    drawPath(path, accent.copy(alpha = 0.10f), style = Stroke(width = 6.dp.toPx()))
                    drawPath(path, accent.copy(alpha = 0.18f), style = Stroke(width = 3.dp.toPx()))
                }
            }
            .background(fill, shape)
            .border(1.dp, accent.copy(alpha = if (emphasis) 0.9f else 0.35f), shape)
            .drawBehind { if (emphasis) runeTicks(accent, 10.dp.toPx()) },
    ) {
        if (label != null || trailing != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = contentPadding, end = contentPadding, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (label != null) {
                    Box(
                        Modifier
                            .size(width = 3.dp, height = 12.dp)
                            .background(accent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label.uppercase(), style = SysType.Label.copy(color = accent), modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                trailing?.invoke(this)
            }
            Hairline(color = accent.copy(alpha = 0.25f))
        }
        Column(Modifier.padding(contentPadding)) { content() }
    }
}

/**
 * The System notification window — reserved for popups, where the show uses it: a boxed "!" and a
 * framed title, unfolding from its centre line.
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
    LaunchedEffect(Unit) { if (!still) open.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
    Box(
        modifier.graphicsLayer {
            scaleY = 0.05f + 0.95f * open.value
            alpha = open.value
        },
    ) {
        Pane(emphasis = true, accent = accent, contentPadding = 20.dp, fill = Palette.Charcoal) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .border(1.dp, accent, systemShape(5.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(icon, style = SysType.Num.copy(color = sys.text, fontWeight = FontWeight.Bold)) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .border(1.dp, accent.copy(alpha = 0.5f), systemShape(5.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(title.uppercase(), style = SysType.Label.copy(color = sys.text, fontSize = 12.sp, letterSpacing = 2.sp), textAlign = TextAlign.Center) }
                }
                Spacer(Modifier.height(16.dp))
            }
            content()
        }
    }
}

/** Kept for call sites that animated text in; the remake shows text immediately. */
@Composable
fun TypewriterText(text: String, style: TextStyle, modifier: Modifier = Modifier, @Suppress("UNUSED_PARAMETER") charMillis: Long = 0, textAlign: TextAlign? = null) {
    Text(text, style = style, modifier = modifier, textAlign = textAlign)
}

/** A flat 1-colour meter. No gloss, no glow: just the value. */
@Composable
fun Meter(value: Int, max: Int, color: Color, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val sys = LocalSys.current
    val frac by animateFloatAsState(if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f), tween(500), label = "meter")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        drawRect(sys.hairline)
        if (frac > 0f) drawRect(color, size = size.copy(width = size.width * frac))
    }
}

/** Label on the left, "value / max" in mono on the right, a meter underneath. */
@Composable
fun StatBar(
    label: String,
    value: Int,
    max: Int,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    showNumbers: Boolean = true,
) {
    val sys = LocalSys.current
    Column(modifier) {
        if (label.isNotEmpty() || showNumbers) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(label.uppercase(), style = SysType.Label.copy(color = sys.muted), modifier = Modifier.weight(1f))
                if (showNumbers) {
                    Text("$value", style = SysType.Num.copy(color = sys.text))
                    Text(" / $max", style = SysType.Num.copy(color = Palette.Dim))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Meter(value, max, color, height = height)
    }
}

enum class ButtonKind { PRIMARY, SECONDARY, DANGER, GHOST }

@Composable
fun SysButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
    accent: Color? = null,
) {
    val sys = LocalSys.current
    val base = accent ?: when (kind) {
        ButtonKind.DANGER -> Palette.Crimson
        ButtonKind.SECONDARY -> sys.muted
        else -> sys.accent
    }
    val c = if (enabled) base else Palette.Dim
    val shape = systemShape(7.dp)
    val content = @Composable {
        Text(
            text,
            style = SysType.BodyStrong.copy(color = if (!enabled) Palette.Dim else if (kind == ButtonKind.SECONDARY) sys.text else c),
            textAlign = TextAlign.Center,
        )
    }
    when (kind) {
        ButtonKind.GHOST -> Box(
            modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
        else -> Box(
            modifier
                .background(if (kind == ButtonKind.SECONDARY) Color.Transparent else c.copy(alpha = 0.10f), shape)
                .border(1.dp, if (kind == ButtonKind.SECONDARY) sys.hairline else c.copy(alpha = 0.8f), shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** Compatibility shim for older call sites. */
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
    val kind = when {
        accent == Palette.Crimson || accent == Palette.Red -> ButtonKind.DANGER
        accent == sys.muted -> ButtonKind.SECONDARY
        filled || accent == sys.accent -> ButtonKind.PRIMARY
        else -> ButtonKind.PRIMARY
    }
    SysButton(text, onClick, modifier, kind, enabled, accent = if (kind == ButtonKind.PRIMARY && accent != sys.accent) accent else null)
}

/** A tappable list row: title and subtitle on the left, anything on the right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val sys = LocalSys.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SysType.BodyStrong.copy(color = sys.text))
            if (subtitle != null) Text(subtitle, style = SysType.Small.copy(color = sys.muted))
        }
        trailing()
    }
}

/** The key/value line from the show's status window: "LABEL ........ value". */
@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = LocalSys.current.text) {
    val sys = LocalSys.current
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key.uppercase(), style = SysType.Label.copy(color = sys.muted), modifier = Modifier.width(96.dp))
        Text(value, style = SysType.BodyStrong.copy(color = valueColor), modifier = Modifier.weight(1f))
    }
}

/** Rank crest: a thin hexagon with the rank letter. */
@Composable
fun RankBadge(rank: Rank, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val color = Color(rank.color)
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
            drawPath(hex, color.copy(alpha = 0.12f))
            drawPath(hex, color.copy(alpha = 0.25f), style = Stroke(width = 4.dp.toPx()))
            drawPath(hex, color, style = Stroke(width = 1.dp.toPx()))
        }
        Text(rank.label, style = SysType.Title.copy(color = Color.White, fontSize = (size.value * 0.42f).sp))
    }
}

@Composable
fun Tag(text: String, color: Color = LocalSys.current.accent, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, color.copy(alpha = 0.6f), systemShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), style = SysType.Label.copy(color = color, fontSize = 10.sp))
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = LocalSys.current.hairline) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(1.dp),
    ) { drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), StrokeCap.Butt) }
}

/** Old name for [Hairline]. */
@Composable
fun Divider(modifier: Modifier = Modifier) = Hairline(modifier)

/** A text field in a hairline frame, with a small mono label above. */
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
    secret: Boolean = false,
) {
    val sys = LocalSys.current
    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label.uppercase(), style = SysType.Label.copy(color = sys.muted))
            Spacer(Modifier.height(6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = (if (numeric) SysType.Num else SysType.Body).copy(color = sys.text, fontSize = 15.sp),
            cursorBrush = SolidColor(sys.accent),
            keyboardOptions = when {
                secret && numeric -> KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                secret -> KeyboardOptions(keyboardType = KeyboardType.Password)
                numeric -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
                else -> KeyboardOptions.Default
            },
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Charcoal, systemShape(6.dp))
                .border(1.dp, sys.hairline, systemShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = SysType.Body.copy(color = Palette.Dim))
                    inner()
                }
            },
        )
    }
}

/** Segmented choice. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sys = LocalSys.current
    Box(
        modifier
            .background(if (selected) sys.accent.copy(alpha = 0.12f) else Color.Transparent, systemShape(5.dp))
            .border(1.dp, if (selected) sys.accent else sys.hairline, systemShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, style = SysType.Small.copy(color = if (selected) sys.text else sys.muted, fontWeight = FontWeight.Medium))
    }
}

/** A thin progress ring with the value in mono at its centre. */
@Composable
fun ProgressRing(fraction: Float, modifier: Modifier = Modifier, size: Dp = 108.dp, color: Color = LocalSys.current.accent, center: @Composable () -> Unit) {
    val sys = LocalSys.current
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 3.dp.toPx()
            drawArc(sys.hairline, 0f, 360f, false, style = Stroke(w), topLeft = Offset(w, w), size = this.size.copy(this.size.width - 2 * w, this.size.height - 2 * w))
            drawArc(color, -90f, 360f * f, false, style = Stroke(w, cap = StrokeCap.Butt), topLeft = Offset(w, w), size = this.size.copy(this.size.width - 2 * w, this.size.height - 2 * w))
        }
        center()
    }
}

@Composable
fun SectionGap() = Spacer(Modifier.height(12.dp))

@Composable
fun PaneColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
