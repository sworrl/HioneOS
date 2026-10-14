package com.caf.fmradio

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * The signal meter.
 *
 * Built for one job in particular: this device's antenna is a bare wire and aiming it changes
 * reception a lot, so the meter has to answer "did that last move help?" faster than a number
 * can. Hence the **peak hold** — a ghost needle that jumps to any new maximum instantly and
 * then decays slowly. Point the wire, watch where the ghost sits, and you can tell whether you
 * are improving or wandering without staring at digits.
 *
 * LOCK is the Si4705's own `valid` bit, not a threshold invented here. When it lights, the chip
 * is saying it considers this a real channel. That is the difference between a station and
 * noise that happens to be loud, and it is worth its own indicator.
 */

/** RSSI ceiling actually observed on this tuner. Scales the dial only; printed values stay raw. */
private const val RSSI_FULL = 40f

@Composable
fun FmSignalMeter(st: FmState, modifier: Modifier = Modifier) {
    val s = st.signal
    val rssi = s.rssi ?: st.rssi ?: 0
    val target = (rssi / RSSI_FULL).coerceIn(0f, 1f)
    // Needle: snapped, not tweened. A 220 ms tween on every 750 ms reading meant ~13 window
    // frames per reading, and each window frame re-blends the full screen on this GPU. One
    // frame per reading is what the data supports anyway.
    //
    // Peak hold: snap up, bleed down at ~4.5% of scale per second, computed from elapsed time
    // when a reading arrives instead of on a timer, so the ghost costs no frames of its own.
    val hold = remember { PeakHold() }
    val peak = hold.update(target, SystemClock.elapsedRealtime())
    val needle = target

    Row(
        modifier.fillMaxWidth()
            .glass(RoundedCornerShape(14.dp), accent = HioneTeal, accent2 = HionePink, fill = Color(0xB807161D), rimAlpha = 0.4f, shine = 0.8f)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FmSignalDial(needle, peak, st.isHardwareOnline, Modifier.size(74.dp, 42.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            FmSignalNumbers(st, rssi)
            Spacer(Modifier.height(4.dp))
            FmSignalPills(st)
        }
    }
}

/** The dial itself: a 180 degree arc, a live needle, and the peak-hold ghost. */
@Composable
private fun FmSignalDial(value: Float, peak: Float, online: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h
        val r = minOf(w / 2f, h) - 4f

        // Scale arc, graded so the eye reads weak/usable/strong without a legend.
        val sweep = 180f
        drawArc(
            brush = Brush.horizontalGradient(
                listOf(Color(0xFF7A2140), Color(0xFF7A6A21), Color(0xFFFFC56B))
            ),
            startAngle = 180f, sweepAngle = sweep, useCenter = false,
            topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        )

        // Ticks every 20% of full scale.
        for (i in 0..5) {
            val a = Math.toRadians((180f + sweep * i / 5f).toDouble())
            val inner = r - 6.dp.toPx()
            drawLine(
                HioneTeal.copy(alpha = 0.45f),
                Offset(cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat()),
                Offset(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat()),
                strokeWidth = 1f
            )
        }

        if (!online) return@Canvas

        // Peak hold first, so the live needle draws over it.
        if (peak > 0.01f) {
            val pa = Math.toRadians((180f + sweep * peak).toDouble())
            drawLine(
                HioneNeonPink.copy(alpha = 0.85f),
                Offset(cx, cy),
                Offset(cx + (r * cos(pa)).toFloat(), cy + (r * sin(pa)).toFloat()),
                strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round
            )
        }

        val na = Math.toRadians((180f + sweep * value).toDouble())
        drawLine(
            HioneCyan,
            Offset(cx, cy),
            Offset(cx + ((r - 2f) * cos(na)).toFloat(), cy + ((r - 2f) * sin(na)).toFloat()),
            strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round
        )
        drawCircle(HioneCyan, radius = 3.dp.toPx(), center = Offset(cx, cy))
    }
}

/** Raw numbers. Every one is a reading; a dash means it was asked for and did not come back. */
@Composable
private fun FmSignalNumbers(st: FmState, rssi: Int) {
    val s = st.signal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Num("RSSI", rssi.toString())
        Num("SNR", s.snr?.toString() ?: "—")
        Num("MPATH", s.multipath?.toString() ?: "—")
        Num("OFFSET", s.freqOffset?.toString() ?: "—")
        Num("AUDIO", if (st.isPowerOn && st.audioLevel > 0f)
            String.format(Locale.US, "%.2f", st.audioLevel) else "—")
    }
}

@Composable
private fun Num(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = HioneTextSecondary, fontSize = 6.5.sp, fontFamily = AudiowideFont)
        Text(value, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** State pills. LOCK is the chip's verdict; the rest are things it told us, not guesses. */
@Composable
private fun FmSignalPills(st: FmState) {
    val s = st.signal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CyberChip(
            when (s.valid) { true -> "LOCK"; false -> "NO LOCK"; null -> "LOCK ?" },
            s.valid == true
        )
        CyberChip(
            when {
                st.isStereo == true -> "STEREO ${s.stereoBlendPct ?: 100}%"
                s.pilot == true -> "PILOT · ${s.stereoBlendPct ?: 0}%"
                st.isStereo == false -> "MONO"
                else -> "STEREO ?"
            },
            st.isStereo == true, HionePurple
        )
        CyberChip(
            when (st.rdsAvailable) { true -> "RDS"; false -> "NO RDS"; null -> "RDS ?" },
            st.rdsAvailable == true, HioneTeal
        )
        if (st.diagnostics.halLoopback == true) CyberChip("AUDIO PATH", true, HioneTeal)
        Spacer(Modifier.weight(1f))
        Text(
            st.diagnostics.routeLabel.uppercase(Locale.US),
            color = HioneTextSecondary, fontSize = 6.5.sp, fontFamily = AudiowideFont
        )
    }
}

/** Peak-hold ghost state. Plain object: it changes only alongside a new reading. */
private class PeakHold {
    private var value = 0f
    private var at = 0L
    fun update(target: Float, now: Long): Float {
        val decayed = if (at == 0L) 0f else (value - (now - at) / 1000f * 0.045f).coerceAtLeast(0f)
        value = maxOf(target, decayed)
        at = now
        return value
    }
}
