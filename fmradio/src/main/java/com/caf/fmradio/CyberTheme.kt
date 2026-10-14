package com.caf.fmradio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Cyber Oki Hione Palette Tokens
val CyberDarkBg = Color(0xFF0C0D10)
val CyberGlassCard = Color(0xDD0A1E26)
val CyberGlassBorder = Color(0x33FFC56B)
val HioneCyan = Color(0xFFFFC56B)
val HioneNeonPink = Color(0xFF6FB0FF)
val HionePurple = Color(0xFFB9C2D0)
val HioneTextPrimary = Color(0xFFFFF3E0)
val HioneTextSecondary = Color(0xFFFFD59A)
fun cyber24BitColorShift(phaseDeg: Float, saturation: Float = 0.88f, brightness: Float = 1.0f, alpha: Float = 1.0f): Color {
    val normHue = (phaseDeg % 360f + 360f) % 360f
    val hsv = floatArrayOf(normHue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    val argb = android.graphics.Color.HSVToColor((alpha.coerceIn(0f, 1f) * 255).toInt(), hsv)
    return Color(argb)
}

// Extra Hione palette colors used by the FM UI (ported from the app's Model.kt).
val HioneTeal = androidx.compose.ui.graphics.Color(0xFFFFAB2E)
val HioneTealBright = androidx.compose.ui.graphics.Color(0xFFFFD08A)
val HionePink = androidx.compose.ui.graphics.Color(0xFF8EC5FF)

/** Caution: marginal reception, grazing line of sight, a transmitter NOAA lists as degraded. */
val CyberAmber = Color(0xFFFFD54F)
/** Unknown / not rated. Grey with a little teal in it so it still belongs to the palette. */
val CyberMuted = Color(0xFF6E8A90)
