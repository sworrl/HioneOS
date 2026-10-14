package com.hione.launcher.ui

import com.hione.launcher.Mascot
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import com.hione.launcher.quiltStitch
import com.hione.launcher.weather.HioneWeatherService
import kotlin.math.cos
import kotlin.math.sin

private enum class Sky { SUN, MOON, CLOUD, RAIN, SNOW, STORM, FOG }

/** WMO weather code → glyph family (same code the service already maps). */
private fun skyFor(code: Int, isDay: Boolean): Sky = when {
    code in 95..99 -> Sky.STORM
    code in 71..77 || code in 85..86 -> Sky.SNOW
    code in 51..67 || code in 80..82 -> Sky.RAIN
    code in 45..48 -> Sky.FOG
    code in 2..3 -> Sky.CLOUD
    isDay -> Sky.SUN
    else -> Sky.MOON
}

/** Pastel backdrop keyed to condition + time of day (dawn / day / dusk / night). */
private fun backdropFor(sky: Sky, solar: Float, isDay: Boolean): List<Color> {
    val phase = when {
        !isDay -> "night"
        solar < 0.18f -> "dawn"
        solar > 0.82f -> "dusk"
        else -> "day"
    }
    return when (sky) {
        Sky.RAIN, Sky.STORM -> listOf(Color(0xFF4A5B8C), Color(0xFF2B3552))
        Sky.SNOW -> listOf(Color(0xFF8FB9D9), Color(0xFF5E7FA8))
        Sky.FOG -> listOf(Color(0xFF7D8A99), Color(0xFF4E5866))
        Sky.CLOUD -> when (phase) {
            "night" -> listOf(Color(0xFF3B3F6B), Color(0xFF1E2140))
            else -> listOf(Color(0xFF7FB3D5), Color(0xFF5A8AB8))
        }
        else -> when (phase) {
            "dawn" -> listOf(Mascot.color(0xFFFFB3C6), Color(0xFFFFD6A5))
            "dusk" -> listOf(Mascot.color(0xFFFF8FAB), Color(0xFF7B5EA7))
            "night" -> listOf(Color(0xFF2E2A5E), Color(0xFF141334))
            else -> listOf(Color(0xFF6ED3F0), Mascot.color(0xFFFFAB2E))
        }
    }
}

private fun DrawScope.blush(cx: Float, cy: Float, r: Float) {
    drawCircle(Mascot.color(0x88FF7BA9), r, Offset(cx - r * 3.2f, cy + r * 1.2f))
    drawCircle(Mascot.color(0x88FF7BA9), r, Offset(cx + r * 3.2f, cy + r * 1.2f))
}

private fun DrawScope.eyes(cx: Float, cy: Float, r: Float) {
    drawCircle(Color(0xFF1B2430), r, Offset(cx - r * 2.4f, cy))
    drawCircle(Color(0xFF1B2430), r, Offset(cx + r * 2.4f, cy))
    drawCircle(Color.White, r * 0.4f, Offset(cx - r * 2.1f, cy - r * 0.35f))
    drawCircle(Color.White, r * 0.4f, Offset(cx + r * 2.7f, cy - r * 0.35f))
}

private fun DrawScope.heart(cx: Float, cy: Float, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(cx, cy + s)
        cubicTo(cx - s * 1.6f, cy - s * 0.2f, cx - s * 0.8f, cy - s * 1.2f, cx, cy - s * 0.4f)
        cubicTo(cx + s * 0.8f, cy - s * 1.2f, cx + s * 1.6f, cy - s * 0.2f, cx, cy + s)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.sparkle(cx: Float, cy: Float, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(cx, cy - s); quadraticBezierTo(cx, cy, cx + s, cy); quadraticBezierTo(cx, cy, cx, cy + s)
        quadraticBezierTo(cx, cy, cx - s, cy); quadraticBezierTo(cx, cy, cx, cy - s); close()
    }
    drawPath(p, color)
}

private fun DrawScope.cloudBody(cx: Float, cy: Float, w: Float, color: Color) {
    drawCircle(color, w * 0.22f, Offset(cx - w * 0.22f, cy))
    drawCircle(color, w * 0.30f, Offset(cx + w * 0.02f, cy - w * 0.10f))
    drawCircle(color, w * 0.22f, Offset(cx + w * 0.28f, cy + 2f))
    drawRoundRect(color, Offset(cx - w * 0.36f, cy - w * 0.02f), Size(w * 0.76f, w * 0.26f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f, w * 0.12f))
}

/** The kawaii condition glyph: sun with soft rays, cloud with blush cheeks, rain + hearts, snow sparkles, moon. */
@Composable
private fun KawaiiSkyGlyph(sky: Sky, modifier: Modifier = Modifier) {
    // Idle bob/spin run only in the perf/balanced power profiles (audio_only / idle freeze them).
    // Ambient-gated (freezes when nobody is interacting, when covered, or in low power).
    val ambientGate by rememberAmbientGate()
    val t = rememberInfiniteTransition(label = "kawaiiSky")
    val bobAnim by t.gatedFloat(ambientGate, 0.5f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "bob")
    val spinAnim by t.gatedFloat(ambientGate, 0f, 360f, infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val bob = bobAnim
    val spin = spinAnim
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f + (bob - 0.5f) * 4.dp.toPx()
        val twinkle = 0.45f + 0.55f * bob
        when (sky) {
            Sky.SUN -> {
                val r = w * 0.26f
                rotate(spin, Offset(cx, cy)) {
                    for (i in 0 until 8) {
                        val a = i * (Math.PI / 4).toFloat()
                        val x1 = cx + cos(a) * r * 1.35f; val y1 = cy + sin(a) * r * 1.35f
                        val x2 = cx + cos(a) * r * 1.85f; val y2 = cy + sin(a) * r * 1.85f
                        drawLine(Color(0xCCFFE082), Offset(x1, y1), Offset(x2, y2), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
                drawCircle(Color(0x66FFE082), r * 1.25f, Offset(cx, cy))
                drawCircle(Color(0xFFFFD54F), r, Offset(cx, cy))
                eyes(cx, cy - r * 0.05f, r * 0.13f)
                blush(cx, cy, r * 0.13f)
                sparkle(cx + r * 1.7f, cy - r * 1.4f, r * 0.28f, Color.White.copy(alpha = twinkle))
            }
            Sky.MOON -> {
                val r = w * 0.26f
                drawCircle(Color(0x55FFF8E1), r * 1.25f, Offset(cx, cy))
                drawCircle(Color(0xFFFFF3C4), r, Offset(cx, cy))
                drawCircle(Color(0x00000000), r * 0.8f, Offset(cx + r * 0.55f, cy - r * 0.35f))
                eyes(cx - r * 0.15f, cy, r * 0.12f)
                blush(cx - r * 0.15f, cy, r * 0.12f)
                sparkle(cx + r * 1.6f, cy - r * 1.3f, r * 0.3f, Color.White.copy(alpha = twinkle))
                sparkle(cx - r * 1.8f, cy + r * 1.1f, r * 0.2f, Mascot.color(0xFFB2EBF2).copy(alpha = 1f - twinkle * 0.6f))
            }
            Sky.CLOUD, Sky.FOG -> {
                val cw = w * 0.78f
                cloudBody(cx, cy, cw, Color(0xFFF4F9FF))
                eyes(cx, cy + cw * 0.02f, cw * 0.035f)
                blush(cx, cy + cw * 0.02f, cw * 0.035f)
                if (sky == Sky.FOG) drawLine(Color(0x99FFFFFF), Offset(cx - cw * 0.5f, cy + cw * 0.3f), Offset(cx + cw * 0.5f, cy + cw * 0.3f), 2.dp.toPx())
            }
            Sky.RAIN, Sky.STORM -> {
                val cw = w * 0.72f
                cloudBody(cx, cy - cw * 0.12f, cw, Color(0xFFE3ECF5))
                eyes(cx, cy - cw * 0.10f, cw * 0.035f)
                blush(cx, cy - cw * 0.10f, cw * 0.035f)
                val dropY = cy + cw * 0.26f + bob * 6.dp.toPx()
                for (i in -1..1) {
                    drawLine(Color(0xFF80D8FF), Offset(cx + i * cw * 0.22f, dropY), Offset(cx + i * cw * 0.22f - 2f, dropY + cw * 0.14f), 2.5.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                }
                heart(cx + cw * 0.42f, dropY + cw * 0.05f, cw * 0.05f, Mascot.color(0xFFFF80AB).copy(alpha = twinkle))
                if (sky == Sky.STORM) {
                    val bolt = Path().apply { moveTo(cx - 4f, dropY - 6f); lineTo(cx + 6f, dropY + 6f); lineTo(cx, dropY + 6f); lineTo(cx + 5f, dropY + 18f) }
                    drawPath(bolt, Color(0xFFFFF176), style = Stroke(2.dp.toPx()))
                }
            }
            Sky.SNOW -> {
                val cw = w * 0.72f
                cloudBody(cx, cy - cw * 0.12f, cw, Color(0xFFF7FBFF))
                eyes(cx, cy - cw * 0.10f, cw * 0.035f)
                blush(cx, cy - cw * 0.10f, cw * 0.035f)
                val fy = cy + cw * 0.28f + bob * 5.dp.toPx()
                sparkle(cx - cw * 0.25f, fy, cw * 0.06f, Color.White.copy(alpha = twinkle))
                sparkle(cx + cw * 0.05f, fy + cw * 0.08f, cw * 0.05f, Color(0xFFE1F5FE).copy(alpha = 1f - twinkle * 0.5f))
                sparkle(cx + cw * 0.3f, fy - cw * 0.02f, cw * 0.06f, Color.White.copy(alpha = twinkle))
            }
        }
    }
}

/**
 * Hero-size kawaii weather patch for the quilt (≈2 columns wide, ~1.5× a normal badge tall):
 * animated condition glyph · big temperature · condition · city · hi/lo · 3-slot hourly row,
 * on a pastel gradient keyed to condition + time of day, 24dp corners + the quilt stitch.
 * Data: unchanged (HioneWeatherService state, last-good fix). Idle motion is one shared
 * infinite transition (glyph bob + sparkle twinkle) — cheap.
 */
@Composable
fun HioneKawaiiWeatherBadge(
    weather: HioneWeatherService.WeatherCondition,
    city: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Honest empty state: before the first successful fetch the WeatherCondition defaults are
    // placeholders (72°F / "Clear Sky"), never show them as if they were real.
    if (weather.lastUpdatedTime == 0L) {
        Row(
            modifier
                .width(236.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Mascot.color(0xFF0B1A1F))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("☁", fontSize = 22.sp, color = Mascot.color(0xFFFFAB2E))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Weather", color = Mascot.color(0xFFFFAB2E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("No data yet. Tap to fetch.", color = Color(0xB3FFFFFF), fontSize = 11.sp)
            }
        }
        return
    }
    val sky = remember(weather.code, weather.isDay) { skyFor(weather.code, weather.isDay) }
    val backdrop = remember(sky, weather.solarFraction, weather.isDay) { backdropFor(sky, weather.solarFraction, weather.isDay) }
    val shape = RoundedCornerShape(24.dp)
    val forecast = remember(weather.hourlyPrecip6h) { weather.hourlyPrecip6h.drop(1).take(3) }
    Row(
        modifier
            .width(236.dp)
            .height(if (forecast.isEmpty()) 84.dp else 100.dp)
            .clip(shape)
            .background(Brush.linearGradient(backdrop))
            .quiltStitch(fillStart = Color(0x1AFFFFFF), fillEnd = Color(0x0DFFFFFF), seam = Color(0xB3FFFFFF), corner = 24.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KawaiiSkyGlyph(sky, Modifier.size(64.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${weather.tempF.toInt()}°",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.padding(bottom = 3.dp)) {
                    Text(weather.summary, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "▲${weather.highTempF.toInt()}° ▼${weather.lowTempF.toInt()}° · ${weather.precipitationProbPct}%",
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            Text(
                city,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (forecast.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    forecast.forEach { pt ->
                        Text(
                            "${pt.timeLabel.substringBefore(':').trimStart('0').ifEmpty { "0" }}h ${pt.tempF.toInt()}°",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
