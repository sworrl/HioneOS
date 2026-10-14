package com.caf.fmradio

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The shapes a receiver panel can take, in the Hione palette.
 *
 * These are the conventions real SDR software settled on, and they are conventions rather than
 * decoration: each one answers a different question. The point of offering all of them is that
 * which question you are asking changes with what you are doing - aiming an antenna is not the
 * same job as finding a station or looking at what a signal is made of.
 *
 * Colour is NOT what varies between them. Every skin draws through [FmWaterfallPalette], so the
 * noise floor is the same navy and a strong signal is the same teal-to-pink no matter which one
 * is selected. What varies is the geometry and what gets annotated.
 */
enum class SdrSkin(val label: String, val blurb: String) {
    /** Trace over a grid with a calibrated axis, waterfall below, timestamped down the edge. */
    CLASSIC("CLASSIC", "Calibrated trace, timestamped waterfall"),

    /** Solid gradient fill under the trace. Easiest to read at a glance in bright light. */
    FILLED("FILLED", "Solid spectrum, band pill"),

    /** Trace with the receiver's own channel shaded, so you can see what it is sitting on. */
    PASSBAND("PASSBAND", "Tuned channel shaded against its neighbors"),

    /** Perspective surface: sweeps recede into the distance, so time is depth. */
    SURFACE("3D", "Perspective surface, time as depth"),

    /** Every catalogued transmitter labelled at its frequency. */
    ANNOTATED("ANNOTATED", "Call signs on every cataloged peak");

    val showsAxis: Boolean get() = this == CLASSIC || this == PASSBAND
    val showsTimestamps: Boolean get() = this == CLASSIC
    val showsPassband: Boolean get() = this == PASSBAND || this == CLASSIC
    val fillsTrace: Boolean get() = this == FILLED || this == SURFACE
    val showsSurface: Boolean get() = this == SURFACE
    /** ANNOTATED labels everything it can; the others still mark the tuned station. */
    val labelsEverything: Boolean get() = this == ANNOTATED
}

/** Width of the left gutter the calibrated axis needs, in px, or 0 when there is no axis. */
fun DrawScope.rssiGutterWidth(skin: SdrSkin): Float = if (skin.showsAxis) 26.dp.toPx() else 0f

/**
 * The left-hand scale, in the units the hardware actually reports.
 *
 * This is labelled dBuV because that is what the Si4705 returns and what the sweep divides by
 * [QualcommFmHardwareEngine.SWEEP_RSSI_FULL_SCALE] to normalise. It is not a dBm figure dressed
 * up to look like a lab instrument: this part has no calibrated absolute reference, and writing
 * -90 dBm on the axis would be inventing precision the device cannot support.
 */
fun DrawScope.drawRssiAxis(
    tm: TextMeasurer,
    gutter: Float,
    traceHeight: Float,
    fullScale: Float,
    accent: Color,
) {
    if (gutter <= 0f) return
    drawRect(Color(0x66020A0F), Offset(0f, 0f), Size(gutter, traceHeight))
    val steps = 4
    for (i in 0..steps) {
        val frac = i.toFloat() / steps
        val y = traceHeight * frac
        val value = (fullScale * (1f - frac)).roundToInt()
        drawLine(accent.copy(alpha = 0.16f), Offset(gutter, y), Offset(size.width, y), strokeWidth = 1f)
        val lay: TextLayoutResult = tm.measure(
            value.toString(),
            TextStyle(color = accent.copy(alpha = 0.7f), fontSize = 6.sp)
        )
        val ty = (y - lay.size.height / 2f).coerceIn(0f, traceHeight - lay.size.height)
        drawText(lay, topLeft = Offset(gutter - lay.size.width - 2f, ty))
    }
    val unit = tm.measure("dBuV", TextStyle(color = accent.copy(alpha = 0.45f), fontSize = 5.5.sp))
    drawText(unit, topLeft = Offset(2f, traceHeight - unit.size.height - 1f))
}

/**
 * Sweep times down the left edge of the waterfall.
 *
 * A waterfall without them shows that something changed but not when, which is the whole reason
 * the reference panels all carry them: an interference streak you can put a time against is
 * evidence, and one you cannot is an anecdote.
 */
fun DrawScope.drawWaterfallTimestamps(
    tm: TextMeasurer,
    rowTimes: List<Long>,
    top: Float,
    rowHeight: Float,
    accent: Color,
) {
    if (rowTimes.isEmpty() || rowHeight < 7f) return
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    // Every row would be unreadable at this size; one in three keeps the column legible.
    val stride = if (rowHeight < 14f) 3 else 1
    for (r in rowTimes.indices step stride) {
        val t = rowTimes[r]
        if (t <= 0L) continue
        val lay = tm.measure(
            fmt.format(Date(t)),
            TextStyle(color = accent.copy(alpha = 0.55f), fontSize = 5.5.sp)
        )
        drawText(lay, topLeft = Offset(2f, top + r * rowHeight + 0.5f))
    }
}

/**
 * The receiver's own channel, shaded across the full height.
 *
 * Every reference panel marks this, and for the same reason: a peak is only interesting
 * relative to what the receiver is currently listening to. One channel wide, because that is
 * what the demodulator is actually taking.
 */
fun DrawScope.drawPassband(
    centreX: Float,
    halfWidth: Float,
    height: Float,
    accent: Color,
) {
    drawRect(
        accent.copy(alpha = 0.13f),
        Offset(centreX - halfWidth, 0f), Size(halfWidth * 2f, height)
    )
    for (edge in listOf(centreX - halfWidth, centreX + halfWidth)) {
        drawLine(accent.copy(alpha = 0.5f), Offset(edge, 0f), Offset(edge, height), strokeWidth = 1f)
    }
}

/**
 * A perspective waterfall: each sweep is drawn further back and smaller than the one in front.
 *
 * Not a real 3D projection, and not pretending to be - it is a shear plus a scale per row, which
 * is what the reference panels do too. The value is that a signal that is PERSISTENT reads as a
 * ridge running away from you, which is much easier to pick out of noise than the same thing
 * expressed as a column of slightly different blues.
 */
fun DrawScope.drawSurfaceWaterfall(
    rows: List<FloatArray>,
    top: Float,
    height: Float,
    width: Float,
) {
    if (rows.isEmpty() || height <= 0f) return
    val depth = rows.size.coerceAtMost(14)
    // Back rows are narrower and higher up; the horizon sits at 55% of the available height.
    val horizon = height * 0.55f
    for (r in depth - 1 downTo 0) {
        val prof = rows.getOrNull(r) ?: continue
        if (prof.isEmpty()) continue
        val t = r.toFloat() / depth                     // 0 = nearest sweep, 1 = furthest
        val shrink = 1f - 0.42f * t
        val rowY = top + height - (height - horizon) * (1f - t) - horizon * t * 0.35f
        val rowW = width * shrink
        val x0 = (width - rowW) / 2f
        val amp = (height * 0.30f) * (1f - 0.45f * t)
        val path = Path()
        val n = prof.size
        for (i in 0 until n) {
            val x = x0 + rowW * i / (n - 1).coerceAtLeast(1)
            val y = rowY - prof[i].coerceIn(0f, 1f) * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Fill under each ridge so nearer sweeps occlude the ones behind, which is what makes
        // the depth readable at all.
        val fill = Path().apply {
            addPath(path); lineTo(x0 + rowW, rowY + 2f); lineTo(x0, rowY + 2f); close()
        }
        val peak = prof.max().coerceIn(0f, 1f)
        drawPath(fill, Brush.verticalGradient(
            listOf(FmWaterfallPalette.lut(peak).copy(alpha = 0.55f - 0.3f * t), Color(0xEE04080E)),
            rowY - amp, rowY + 2f))
        drawPath(path, FmWaterfallPalette.lut(peak).copy(alpha = 0.95f - 0.5f * t),
                 style = Stroke(width = 1.2f))
    }
}
