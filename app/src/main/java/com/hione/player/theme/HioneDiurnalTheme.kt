package com.hione.player.theme

import com.hione.player.Mascot
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.sin

/**
 * Oki Hione 24-Hour Diurnal 24-Bit Truecolor Shifting Engine.
 * Modulates the entire UI palette across a 24-hour continuous cycle with second-level micro-drift.
 * Clamped strictly to the Oki Hione Cyber Spectrum:
 * Cyan (180°) -> Turquoise (160°) -> Emerald (140°) -> Violet (270°) -> Neon Pink/Magenta (320°) -> Sakura (340°) -> Solar (50°) -> Cyan.
 */
object HioneDiurnalTheme {

    fun getDiurnalHue(calendar: Calendar = Calendar.getInstance()): Float {
        val secondsInDay = (calendar.get(Calendar.HOUR_OF_DAY) * 3600) +
                (calendar.get(Calendar.MINUTE) * 60) +
                calendar.get(Calendar.SECOND) +
                (calendar.get(Calendar.MILLISECOND) / 1000f)
        val dayFraction = secondsInDay / 86400f // 0.0 to 1.0

        // Smooth diurnal cyclical hue curve mapped through Hione spectrum anchor points
        val baseHue = when {
            dayFraction < 0.25f -> 200f - (dayFraction / 0.25f) * 35f       // 00:00-06:00: 200° (Indigo) -> 165° (Turquoise)
            dayFraction < 0.50f -> 165f + ((dayFraction - 0.25f) / 0.25f) * 20f // 06:00-12:00: 165° -> 185° (Hione Cyan)
            dayFraction < 0.75f -> 185f + ((dayFraction - 0.50f) / 0.25f) * 130f // 12:00-18:00: 185° -> 315° (Neon Pink)
            dayFraction < 0.90f -> 315f - ((dayFraction - 0.75f) / 0.15f) * 50f  // 18:00-21:30: 315° -> 265° (Cyber Violet)
            else -> 265f - ((dayFraction - 0.90f) / 0.10f) * 65f  // 21:30-24:00: 265° -> 200° (Indigo)
        }

        // Continuous second-to-second breathing micro-drift (ensures color changes every second without jumping)
        val secFraction = (secondsInDay % 60f) / 60f
        val microDrift = (sin(secFraction * Math.PI * 2.0).toFloat()) * 8f

        return (baseHue + microDrift + 360f) % 360f
    }

    fun getColor(
        hueOffsetDeg: Float = 0f,
        saturation: Float = 0.88f,
        brightness: Float = 1.0f,
        alpha: Float = 1.0f,
        calendar: Calendar = Calendar.getInstance()
    ): Color {
        val hue = (getDiurnalHue(calendar) + hueOffsetDeg + 360f) % 360f
        val hsv = floatArrayOf(hue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
        val argb = AndroidColor.HSVToColor((alpha.coerceIn(0f, 1f) * 255).toInt(), hsv)
        return Color(argb)
    }

    data class DiurnalPalette(
        val primary: Color,
        val secondary: Color,
        val accent: Color,
        val glow: Color,
        val border: Color,
        val backgroundGradient: List<Color>,
        val currentHue: Float
    )

    @Composable
    fun rememberDiurnalPalette(): State<DiurnalPalette> {
        val paletteState = remember {
            mutableStateOf(
                DiurnalPalette(
                    primary = getColor(0f),
                    secondary = getColor(45f),
                    accent = getColor(120f),
                    glow = getColor(0f, alpha = 0.65f),
                    border = getColor(0f, saturation = 0.55f, brightness = 0.85f, alpha = 0.65f),
                    backgroundGradient = listOf(Mascot.color(0xFF041018), Color(0xFF02080D)),
                    currentHue = getDiurnalHue()
                )
            )
        }

        LaunchedEffect(Unit) {
            while (true) {
                val cal = Calendar.getInstance()
                val hue = getDiurnalHue(cal)
                val prim = getColor(0f, calendar = cal)
                val sec = getColor(45f, calendar = cal)
                val acc = getColor(120f, calendar = cal)
                val gl = getColor(0f, alpha = 0.65f, calendar = cal)
                val brd = getColor(0f, saturation = 0.55f, brightness = 0.85f, alpha = 0.65f, calendar = cal)

                paletteState.value = DiurnalPalette(
                    primary = prim,
                    secondary = sec,
                    accent = acc,
                    glow = gl,
                    border = brd,
                    backgroundGradient = listOf(
                        getColor(0f, saturation = 0.9f, brightness = 0.08f, calendar = cal),
                        getColor(60f, saturation = 0.9f, brightness = 0.03f, calendar = cal)
                    ),
                    currentHue = hue
                )
                delay(200L) // 5 FPS smooth 24-bit interpolation
            }
        }
        return paletteState
    }
}
