package dev.aoidoki.arise.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.aoidoki.arise.R

/** The System's palette: one accent, one alarm, white and silver. Everything else is darkness. */
object Palette {
    val Void = Color(0xFF050608)
    val Charcoal = Color(0xFF0B0D12)
    val Smoke = Color(0xFF121822)
    val PaneFill = Color(0xE00A0E14)
    val Hairline = Color(0xFF1B2430)

    val Cyan = Color(0xFF00D2FF)
    val CyanDeep = Color(0xFF00A2FF)
    val Crimson = Color(0xFFFF0055)
    val White = Color(0xFFFFFFFF)
    val Silver = Color(0xFFA0AEC0)
    val Dim = Color(0xFF5A6678)

    // Data colours, used only inside meters and badges.
    val Hp = Color(0xFFFF3D71)
    val Mp = Color(0xFF00A2FF)
    val Fatigue = Color(0xFFFFB547)
    val Good = Color(0xFF3DDC97)
    val Gold = Color(0xFFFFC857)
    val Violet = Color(0xFFB06BFF)

    // Kept for call sites that predate the remake.
    val Blue = CyanDeep
    val Ice = White
    val Muted = Silver
    val Red = Crimson
    val RedGlow = Color(0xFFFF4D85)
    val Abyss = Smoke
    val PanelSolid = Charcoal
    val Line = Hairline
    val Xp = Cyan
}

/** The System's colours for the current mode. The Penalty Zone swaps the accent to crimson. */
@Immutable
data class SysColors(
    val accent: Color,
    val accentSoft: Color,
    val text: Color,
    val muted: Color,
    val panel: Color,
    val background: Color,
    val backgroundGlow: Color,
    val hairline: Color,
    val penalty: Boolean,
)

val NormalColors = SysColors(
    accent = Palette.Cyan,
    accentSoft = Palette.CyanDeep,
    text = Palette.White,
    muted = Palette.Silver,
    panel = Palette.PaneFill,
    background = Palette.Void,
    backgroundGlow = Palette.Smoke,
    hairline = Palette.Hairline,
    penalty = false,
)

val PenaltyColors = SysColors(
    accent = Palette.Crimson,
    accentSoft = Color(0xFFFF4D85),
    text = Palette.White,
    muted = Color(0xFFC2A3AE),
    panel = Color(0xE0140609),
    background = Color(0xFF070203),
    backgroundGlow = Color(0xFF1E070D),
    hairline = Color(0xFF3A1320),
    penalty = true,
)

val LocalSys = staticCompositionLocalOf { NormalColors }

object Fonts {
    /** Angular display face for the few big things: level, rank, screen titles. */
    val Display = FontFamily(
        Font(R.font.rajdhani_medium, FontWeight.Medium),
        Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
        Font(R.font.rajdhani_bold, FontWeight.Bold),
    )

    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val Body = FontFamily(
        Font(R.font.exo2, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.exo2, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.exo2, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    )

    /** System-log monospace: every number, every label, every readout. */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val Mono = FontFamily(
        Font(R.font.jbmono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.jbmono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.jbmono, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.jbmono, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
}

/**
 * One size ramp. Uppercase is reserved for [Label] (the small system-log tags); everything a person
 * reads as a sentence stays in sentence case.
 */
object SysType {
    val Huge = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 56.sp)
    val Title = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp)
    val Header = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp)
    val Body = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val BodyStrong = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    val Small = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    /** The system-log tag: tiny, monospace, tracked, uppercase. */
    val Label = TextStyle(fontFamily = Fonts.Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp)
    val Num = TextStyle(fontFamily = Fonts.Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
    val NumLarge = TextStyle(fontFamily = Fonts.Mono, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp)
    val Mono = Num
    val Stat = NumLarge
}

@Composable
fun AriseTheme(penalty: Boolean = false, content: @Composable () -> Unit) {
    val sys = if (penalty) PenaltyColors else NormalColors
    val scheme = darkColorScheme(
        primary = sys.accent,
        onPrimary = Palette.Void,
        secondary = Palette.CyanDeep,
        background = sys.background,
        onBackground = sys.text,
        surface = Palette.Charcoal,
        onSurface = sys.text,
        surfaceVariant = Palette.Smoke,
        onSurfaceVariant = sys.muted,
        error = Palette.Crimson,
        outline = sys.hairline,
    )
    CompositionLocalProvider(LocalSys provides sys) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
