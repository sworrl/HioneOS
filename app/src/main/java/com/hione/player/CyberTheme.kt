package com.hione.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Cyber Oki Hione Palette Tokens
val CyberDarkBg get() = Mascot.color(0xFF0C0D10)
val CyberGlassCard get() = Mascot.color(0xDD0A1E26)
val CyberGlassBorder get() = Mascot.color(0x33FFC56B)
val HioneCyan get() = Mascot.color(0xFFFFC56B)
val HioneNeonPink get() = Mascot.color(0xFF6FB0FF)
val HionePurple get() = Mascot.color(0xFFB9C2D0)
val HioneTextPrimary get() = Mascot.color(0xFFFFF3E0)
val HioneTextSecondary get() = Mascot.color(0xFFFFD59A)
fun cyber24BitColorShift(phaseDeg: Float, saturation: Float = 0.88f, brightness: Float = 1.0f, alpha: Float = 1.0f): Color {
    val normHue = (phaseDeg % 360f + 360f) % 360f
    val hsv = floatArrayOf(normHue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    val argb = android.graphics.Color.HSVToColor((alpha.coerceIn(0f, 1f) * 255).toInt(), hsv)
    return Color(argb)
}
