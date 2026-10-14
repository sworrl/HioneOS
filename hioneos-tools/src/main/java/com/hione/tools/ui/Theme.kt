package com.hione.tools.ui

import com.hione.tools.Mascot
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Same palette HioneOS Settings and the launcher use (#FFAB2E teal, diva pink, obsidian). Copied,
// not shared through a library module, because every other HioneOS module also carries its own
// copy and a cross-module dependency would couple build orders for three colors.
object Hione {
    val Teal get() = Mascot.color(0xFFFFAB2E)
    val TealBright get() = Mascot.color(0xFFFFC56B)
    val TealGlow get() = Mascot.color(0xFF56F0E0)
    val Cyan get() = Mascot.color(0xFFFFC56B)
    val Pink get() = Mascot.color(0xFF8EC5FF)
    val PinkSoft get() = Mascot.color(0xFF6FB0FF)
    val Purple get() = Mascot.color(0xFFB9C2D0)
    val Gold = Color(0xFFFFD54F)
    val Red = Color(0xFFFF5370)

    val Bg get() = Mascot.color(0xFF0B0C0E)
    val BgDeep get() = Mascot.color(0xFF030506)
    val Surface get() = Mascot.color(0xFF0D1417)
    val Surface2 get() = Mascot.color(0xFF131E22)
    val Card get() = Mascot.color(0xFF0E191D)

    val Text get() = Mascot.color(0xFFF0FDFE)
    val TextDim = Color(0xFFB4CBD0)
    val Muted = Color(0xFF7E9AA0)
    val Faint = Color(0xFF4A5E63)

    /** Glass fill: mostly-transparent white over the dark backdrop reads as frosted without
     *  paying for a real backdrop blur, which this GPU cannot afford per frame. */
    val GlassTop = Color(0x1FFFFFFF)
    val GlassBottom = Color(0x0AFFFFFF)
    val GlassEdge get() = Mascot.color(0x33FFAB2E)
}

/** Monospace for digits: the launcher's Audiowide/Orbitron are aliased to monospace too, and
 *  fixed-width numerals stop a running stopwatch from jittering sideways. */
val HioneMono = FontFamily.Monospace

private val HioneTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = HioneMono, fontWeight = FontWeight.Light),
        displayMedium = t.displayMedium.copy(fontFamily = HioneMono, fontWeight = FontWeight.Light),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    )
}

private val HioneColors get() = darkColorScheme(
    primary = Hione.Teal,
    onPrimary = Mascot.color(0xFF00201D),
    primaryContainer = Mascot.color(0xFF0F3A37),
    onPrimaryContainer = Hione.TealGlow,
    secondary = Hione.PinkSoft,
    onSecondary = Color.White,
    secondaryContainer = Mascot.color(0xFF4A0F2A),
    onSecondaryContainer = Mascot.color(0xFFFFD9E6),
    tertiary = Hione.Purple,
    background = Hione.Bg,
    onBackground = Hione.Text,
    surface = Hione.Surface,
    onSurface = Hione.Text,
    surfaceVariant = Hione.Surface2,
    onSurfaceVariant = Hione.TextDim,
    surfaceContainer = Hione.Surface,
    surfaceContainerHigh = Hione.Surface2,
    surfaceContainerHighest = Mascot.color(0xFF1A282D),
    surfaceContainerLow = Hione.Surface,
    surfaceContainerLowest = Hione.BgDeep,
    outline = Hione.Faint,
    outlineVariant = Mascot.color(0xFF253438),
    error = Hione.Red,
)

@Composable
fun HioneTheme(content: @Composable () -> Unit) {
    // HioneOS is dark-only; ignore the system setting on purpose.
    MaterialTheme(colorScheme = HioneColors, typography = HioneTypography, content = content)
}

val NumberStyle = TextStyle(fontFamily = HioneMono, fontWeight = FontWeight.Light)
