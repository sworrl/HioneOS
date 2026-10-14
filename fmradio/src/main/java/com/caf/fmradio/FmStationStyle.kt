package com.caf.fmradio

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * How a station looks, wherever it appears: its colour, its reach colour, and its gauge.
 *
 * One place on purpose. The point of giving each station a colour is that WVAQ is the same
 * violet on the preset bar, in the station list and in the detail sheet, so the eye learns it;
 * that only works if nobody computes it twice.
 */

/**
 * A stable, well-separated colour per station.
 *
 * Hue from the call sign's hash stepped by the golden ratio, which spreads consecutive hashes
 * around the wheel instead of clumping them. String.hashCode is specified by the language, so
 * the colour survives restarts and updates. Saturation and value are fixed high enough to read
 * on the navy background and low enough not to fight the teal/pink chrome.
 */
private val colorCache = HashMap<String, Color>()

fun stationColor(call: String): Color = colorCache.getOrPut(call) {
    val base = call.substringBefore('-').uppercase(Locale.US)
    val h = ((base.hashCode().toLong() and 0xffffffffL) * 0.6180339887498949) % 1.0
    cyber24BitColorShift((h * 360.0).toFloat(), saturation = 0.58f, brightness = 1f)
}

/** Colour for a frequency nothing in the catalogue explains (a bare preset). */
fun frequencyColor(khz: Int): Color = stationColor("F$khz")

/** Ordering for "best first": what was measured here beats what was predicted. */
fun FmReach.Verdict.rank(): Int = when (this) {
    FmReach.Verdict.HEARD -> 0
    FmReach.Verdict.LIKELY -> 1
    FmReach.Verdict.MARGINAL -> 2
    FmReach.Verdict.UNKNOWN -> 3
    FmReach.Verdict.UNLIKELY -> 4
    FmReach.Verdict.NOT_HEARD -> 5
}

/** Whether a verdict belongs with the stations you can expect to listen to here. */
val FmReach.Verdict.inReach: Boolean
    get() = this == FmReach.Verdict.HEARD || this == FmReach.Verdict.LIKELY || this == FmReach.Verdict.MARGINAL

val FmReach.Verdict.outOfReach: Boolean
    get() = this == FmReach.Verdict.UNLIKELY || this == FmReach.Verdict.NOT_HEARD

val FmReach.Verdict.color: Color
    get() = when (this) {
        FmReach.Verdict.HEARD -> HioneCyan
        FmReach.Verdict.LIKELY -> HioneTeal
        FmReach.Verdict.MARGINAL -> CyberAmber
        FmReach.Verdict.UNLIKELY -> HionePink.copy(alpha = 0.75f)
        FmReach.Verdict.NOT_HEARD -> HioneNeonPink
        FmReach.Verdict.UNKNOWN -> CyberMuted
    }

/** Short label for a chip, where "should come in" will not fit. */
val FmReach.Verdict.shortLabel: String
    get() = when (this) {
        FmReach.Verdict.HEARD -> "HEARD"
        FmReach.Verdict.LIKELY -> "LIKELY"
        FmReach.Verdict.MARGINAL -> "MARGINAL"
        FmReach.Verdict.UNLIKELY -> "OUT OF REACH"
        FmReach.Verdict.NOT_HEARD -> "NOT HEARD"
        FmReach.Verdict.UNKNOWN -> "UNRATED"
    }

/**
 * Line of sight, bucketed the way radio planners do: the first Fresnel zone 60% clear is the
 * conventional "as good as free space" limit, and below zero the ground itself is in the line.
 */
enum class Los(val label: String, val color: Color) {
    CLEAR("clear", HioneCyan),
    OK("60% clear", HioneTeal),
    GRAZING("grazing", CyberAmber),
    BLOCKED("blocked", HioneNeonPink),
    UNKNOWN("terrain ?", CyberMuted);

    companion object {
        fun of(fraction: Double?): Los = when {
            fraction == null -> UNKNOWN
            fraction >= 1.0 -> CLEAR
            fraction >= FmFresnel.FRESNEL_RULE -> OK
            fraction >= 0.0 -> GRAZING
            else -> BLOCKED
        }
    }
}

/** Fixed hues for the genre buckets the catalogue uses, so a filter chip and a row agree. */
fun genreColor(genre: String?): Color {
    val g = genre?.lowercase(Locale.US) ?: return CyberMuted
    return when {
        "rock" in g -> Color(0xFFFF7A59)
        "country" in g -> Color(0xFFFFC857)
        "public" in g || "npr" in g -> Color(0xFF7FB4FF)
        "christian" in g || "relig" in g || "gospel" in g -> Color(0xFFB9A3FF)
        "top 40" in g || "pop" in g || "chr" in g || "hits" in g -> HionePink
        "news" in g || "talk" in g || "sport" in g -> Color(0xFFB0BEC5)
        "jazz" in g || "classical" in g -> Color(0xFF9FE6A0)
        "hip" in g || "urban" in g || "r&b" in g || "rhythm" in g -> Color(0xFFFF9BE0)
        "college" in g || "variety" in g || "free" in g -> HioneTealBright
        "oldies" in g || "classic" in g -> Color(0xFFFFA26B)
        "spanish" in g || "latin" in g -> Color(0xFFFF6E6E)
        else -> stationColor("G$g")
    }
}

/**
 * The reception gauge: a 270 degree arc for the odds of hearing it, and inside it a small
 * terrain glyph for line of sight.
 *
 * The arc is [FmStationCatalogue.Station.receptionScore] (measured level when a sweep has read
 * the frequency here, else the calibrated prediction), coloured by the reach verdict so the
 * colour carries the conclusion and the length carries the margin. The glyph is the worst
 * Fresnel clearance on the path: a hill with the sight line over it, touching it, or through
 * it. Unknowns are drawn as unknowns (dashed track, "?"), never as a guess.
 */
@Composable
fun ReceptionGauge(
    score: Float?,
    verdict: FmReach.Verdict,
    fresnel: Double?,
    modifier: Modifier = Modifier,
    strokeDp: Float = 3f,
) {
    val tm = rememberTextMeasurer()
    val los = Los.of(fresnel)
    val q = remember(tm) {
        tm.measure("?", TextStyle(color = CyberMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold))
    }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(4f, 4f)) }
    val hill = remember { Path() }
    Canvas(modifier) {
        val sw = strokeDp * density
        val d = size.minDimension - sw
        val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val arcSize = Size(d, d)
        drawArc(
            Color.White.copy(alpha = 0.12f), 135f, 270f, false, tl, arcSize,
            style = Stroke(sw, cap = StrokeCap.Round, pathEffect = if (score == null) dash else null)
        )
        if (score != null) {
            drawArc(
                verdict.color, 135f, 270f * score.coerceIn(0.03f, 1f), false, tl, arcSize,
                style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
        // LOS glyph in the middle of the ring.
        val gw = d * 0.56f
        val gh = d * 0.36f
        val gx = (size.width - gw) / 2f
        val gy = size.height / 2f - gh * 0.25f
        if (los == Los.UNKNOWN) {
            drawText(q, topLeft = Offset((size.width - q.size.width) / 2f, (size.height - q.size.height) / 2f))
            return@Canvas
        }
        hill.reset()
        hill.apply {
            moveTo(gx, gy + gh)
            lineTo(gx + gw * 0.5f, gy + gh * 0.25f)
            lineTo(gx + gw, gy + gh)
            close()
        }
        drawPath(hill, los.color.copy(alpha = 0.35f))
        val lineY = when (los) {
            Los.CLEAR -> gy - gh * 0.25f
            Los.OK -> gy
            Los.GRAZING -> gy + gh * 0.25f
            else -> gy + gh * 0.6f
        }
        drawLine(los.color, Offset(gx - gw * 0.1f, lineY), Offset(gx + gw * 1.1f, lineY),
                 strokeWidth = 1.4f * density, cap = StrokeCap.Round)
        if (los == Los.BLOCKED) {
            val cx = gx + gw * 0.5f
            val r = gh * 0.28f
            drawLine(HioneNeonPink, Offset(cx - r, lineY - r), Offset(cx + r, lineY + r), strokeWidth = 1.2f * density)
            drawLine(HioneNeonPink, Offset(cx - r, lineY + r), Offset(cx + r, lineY - r), strokeWidth = 1.2f * density)
        }
    }
}

fun fmtMhz(khz: Int): String = String.format(Locale.US, "%.1f", khz / 1000.0)

/** The catalogue station that a frequency on the dial most plausibly is, best reach first. */
fun stationOn(khz: Int, stations: List<FmStationCatalogue.Station>): FmStationCatalogue.Station? {
    var best: FmStationCatalogue.Station? = null
    for (s in stations) {
        if (kotlin.math.abs(s.khz - khz) > 60) continue
        if (best == null || s.reach.rank() < best.reach.rank() ||
            (s.reach.rank() == best.reach.rank() && s.score > best.score)) best = s
    }
    return best
}
