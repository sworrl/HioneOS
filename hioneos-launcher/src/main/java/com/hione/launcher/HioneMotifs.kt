package com.hione.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

// Hione's own art for the BPM secrets, drawn under every pack without Hione lore. These replace
// the leeks outright; nothing here is a recolored leek. The motif is her coda mark: a circle
// crossed by a plus sign, read as a scope reticle (see CodaLock.kt in Hione Music), plus ice
// shards. Colors come from the active pack's palette: primary is amber (#FFAB2E) and accent is
// ice blue (#8EC5FF) under Hione, and a downloaded pack gets its own two.

/**
 * The coda mark at [c]: a ring of radius [ringR] and four arms that start [gap] from the center
 * and run out to [armOut], past the ring. [pip] draws a center dot when not null.
 */
fun DrawScope.drawCodaMark(
    c: Offset,
    ringR: Float,
    gap: Float,
    armOut: Float,
    ringW: Float,
    armW: Float,
    ring: Color,
    arms: Color = ring,
    pip: Color? = null,
) {
    drawCircle(ring, ringR, c, style = Stroke(width = ringW))
    drawLine(arms, Offset(c.x, c.y - gap), Offset(c.x, c.y - armOut), armW, StrokeCap.Butt)
    drawLine(arms, Offset(c.x, c.y + gap), Offset(c.x, c.y + armOut), armW, StrokeCap.Butt)
    drawLine(arms, Offset(c.x - gap, c.y), Offset(c.x - armOut, c.y), armW, StrokeCap.Butt)
    drawLine(arms, Offset(c.x + gap, c.y), Offset(c.x + armOut, c.y), armW, StrokeCap.Butt)
    if (pip != null) drawCircle(pip, ringR * 0.28f, c)
}

/** A standalone coda mark, for places that used a leek glyph (the golden pickup, the fever face). */
@Composable
fun CodaMarkGlyph(size: Dp, ring: Color, arms: Color = ring, pip: Color? = null, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCodaMark(
            c = center, ringR = 0.56f * r, gap = 0.24f * r, armOut = 0.99f * r,
            ringW = maxOf(1.dp.toPx(), 0.12f * r), armW = maxOf(1.dp.toPx(), 0.11f * r),
            ring = ring, arms = arms, pip = pip,
        )
    }
}

/**
 * The battery secret under Hione, in the top bar's 26 x 11 dp slot. A small coda reticle sits on
 * the left, and an ice shard lying on its side, point to the right, is the gauge: it fills with
 * ice from the left as the level rises. Charging fills it amber and lights the reticle's center
 * pip; low fills it in the warning color. An unknown level (pct < 0) draws an empty shard.
 */
@Composable
fun CodaShardBatteryGlyph(pct: Int, charging: Boolean, low: Boolean, modifier: Modifier = Modifier) {
    val amber = Mascot.primary
    val ice = Mascot.accent
    val warn = Mascot.warn
    Canvas(modifier.size(width = 26.dp, height = 11.dp)) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val r = h / 2f

        // Reticle.
        drawCodaMark(
            c = Offset(r, cy), ringR = 0.52f * r, gap = 0.2f * r, armOut = 0.98f * r,
            ringW = maxOf(1.dp.toPx() * 0.9f, 0.13f * r), armW = maxOf(1.dp.toPx() * 0.8f, 0.11f * r),
            ring = amber, pip = if (charging) lerp(amber, Color.White, 0.45f) else if (low) warn else null,
        )

        // Shard: a long six-sided crystal, blunt facets at the left, a point at the right.
        val x0 = h + 1.5.dp.toPx()
        val x1 = w
        val len = x1 - x0
        val hh = h * 0.36f
        val shard = Path().apply {
            moveTo(x0, cy - hh * 0.55f)
            lineTo(x0 + len * 0.18f, cy - hh)
            lineTo(x1 - len * 0.28f, cy - hh * 0.8f)
            lineTo(x1, cy + hh * 0.05f)
            lineTo(x1 - len * 0.34f, cy + hh)
            lineTo(x0 + len * 0.08f, cy + hh * 0.82f)
            close()
        }
        drawPath(shard, ice.copy(alpha = 0.14f))
        val frac = if (pct < 0) 0f else (pct / 100f).coerceIn(0f, 1f)
        if (frac > 0f) {
            val fill = when {
                charging -> amber
                low -> warn
                else -> ice
            }
            clipRect(right = x0 + len * frac) { drawPath(shard, fill) }
        }
        drawPath(shard, ice.copy(alpha = 0.7f), style = Stroke(width = 0.8.dp.toPx()))
        // The facet edge that catches the light.
        drawLine(
            Color.White.copy(alpha = 0.45f),
            Offset(x0 + len * 0.18f, cy - hh), Offset(x1 - len * 0.06f, cy - hh * 0.05f),
            strokeWidth = 0.6.dp.toPx(), cap = StrokeCap.Round,
        )
    }
}

// One ice shard, pointing down, 1 unit long. Fill only, so it can be scaled to any size.
private val shardUnit: Path by lazy {
    Path().apply {
        moveTo(0f, 0f)
        lineTo(0.15f, 0.28f)
        lineTo(0.10f, 0.76f)
        lineTo(0.01f, 1f)
        lineTo(-0.13f, 0.64f)
        lineTo(-0.11f, 0.20f)
        close()
    }
}

/**
 * The falling-wallpaper secret under Hione: ice shards tumbling down, with every third item a
 * small amber coda mark turning slowly. Same count, timing and speed rule as the leeks it
 * replaces, so it costs the same: sixteen items, a handful of primitives each.
 */
fun DrawScope.drawCodaSnow(t: Long, speedMul: Float) {
    val w = size.width
    val h = size.height
    val ice = Mascot.accent
    val icePale = lerp(ice, Color.White, 0.5f)
    val amber = Mascot.primary
    for (i in 0 until 16) {
        val seed = (i * 0.618034f) % 1f
        val speed = (22f + 18f * ((i * 7) % 5)) * speedMul      // px per second
        val travel = h + 80f
        val y = ((seed * travel + (t % 600_000L) / 1000f * speed) % travel) - 40f
        val x = (((i * 97) % 100) / 100f) * w + sin(t / 1400.0 + i).toFloat() * 14f
        if (i % 3 == 2) {
            // Coda mark: turns a full circle every 12 s or so, each one at its own phase.
            val ringR = 5.5f + (i % 2) * 1.5f
            val spin = ((t % 12_000L) / 12_000f * 360f + i * 37f) % 360f
            rotate(spin, pivot = Offset(x, y)) {
                drawCodaMark(
                    c = Offset(x, y), ringR = ringR, gap = 1.8f, armOut = ringR * 1.75f,
                    ringW = 1.8f, armW = 1.6f, ring = amber.copy(alpha = 0.8f),
                )
            }
        } else {
            val tilt = sin(t / 1100.0 + i * 1.3).toFloat() * 35f + (i % 3 - 1) * 20f
            val len = 18f + (i % 4) * 4f
            translate(x, y) {
                rotate(tilt, pivot = Offset.Zero) {
                    scale(len, len, pivot = Offset.Zero) {
                        drawPath(shardUnit, (if (i % 2 == 0) ice else icePale).copy(alpha = 0.75f))
                        drawLine(
                            Color.White.copy(alpha = 0.6f), Offset(0.01f, 0.06f), Offset(0.03f, 0.9f),
                            strokeWidth = 0.05f, cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}
