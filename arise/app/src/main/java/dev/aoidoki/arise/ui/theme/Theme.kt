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

object Palette {
    val Void = Color(0xFF05070D)
    val Abyss = Color(0xFF081223)
    val Panel = Color(0xE60A1628)
    val PanelSolid = Color(0xFF0A1628)
    val Line = Color(0xFF16304F)
    val Blue = Color(0xFF1EA7FF)
    val Cyan = Color(0xFF6FE3FF)
    val Ice = Color(0xFFE6F7FF)
    val Muted = Color(0xFF7C93B5)
    val Dim = Color(0xFF3D5270)
    val Red = Color(0xFFFF2A3D)
    val RedGlow = Color(0xFFFF5566)
    val RedAbyss = Color(0xFF1A0306)
    val Violet = Color(0xFF8B5CF6)
    val Gold = Color(0xFFFBBF24)
    val Hp = Color(0xFFFF4D6D)
    val Mp = Color(0xFF3B82F6)
    val Fatigue = Color(0xFFF59E0B)
    val Xp = Color(0xFF6FE3FF)
    val Good = Color(0xFF4ADE80)
}

/** The System's colours for the current mode. The Penalty Zone swaps the whole UI to red. */
@Immutable
data class SysColors(
    val accent: Color,
    val accentSoft: Color,
    val text: Color,
    val muted: Color,
    val panel: Color,
    val background: Color,
    val backgroundGlow: Color,
    val penalty: Boolean,
)

val NormalColors = SysColors(
    accent = Palette.Blue,
    accentSoft = Palette.Cyan,
    text = Palette.Ice,
    muted = Palette.Muted,
    panel = Palette.Panel,
    background = Palette.Void,
    backgroundGlow = Palette.Abyss,
    penalty = false,
)

val PenaltyColors = SysColors(
    accent = Palette.Red,
    accentSoft = Palette.RedGlow,
    text = Color(0xFFFFE6E8),
    muted = Color(0xFFB57C84),
    panel = Color(0xE6200A0E),
    background = Color(0xFF0A0204),
    backgroundGlow = Palette.RedAbyss,
    penalty = true,
)

val LocalSys = staticCompositionLocalOf { NormalColors }

object Fonts {
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
        Font(R.font.exo2, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
}

object SysType {
    val Huge = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight.Bold, fontSize = 64.sp, letterSpacing = 2.sp)
    val Title = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 3.sp)
    val Header = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 4.sp)
    val Label = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 1.5.sp)
    val Stat = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp)
    val Body = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val Small = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)
    val Mono = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 1.sp)
}

@Composable
fun AriseTheme(penalty: Boolean = false, content: @Composable () -> Unit) {
    val sys = if (penalty) PenaltyColors else NormalColors
    val scheme = darkColorScheme(
        primary = sys.accent,
        onPrimary = Palette.Void,
        secondary = Palette.Violet,
        background = sys.background,
        onBackground = sys.text,
        surface = Palette.PanelSolid,
        onSurface = sys.text,
        surfaceVariant = Palette.Abyss,
        onSurfaceVariant = sys.muted,
        error = Palette.Red,
        outline = Palette.Line,
    )
    CompositionLocalProvider(LocalSys provides sys) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
