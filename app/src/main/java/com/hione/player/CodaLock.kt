package com.hione.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hione.player.bpm.HioneBeatClock
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private val CodaAmber = Color(0xFFFFAB2E)
private val CodaAmberHot = Color(0xFFFFD99A)
private val CodaIce = Color(0xFF8EC5FF)
private val CodaBackdrop = Color(0xFF0B1016)

/**
 * The coda lock: the Now Playing secret for packs without Hione lore (Hione and downloaded packs).
 * It is Hione's coda mark, a circle crossed by a plus sign, drawn as a small scope reticle.
 *
 * WHAT IT DOES.
 *  - Paused, or playing with no beat phase yet: the reticle is open (the crosshair arms stand
 *    off the center). While playing it searches: the four bezel ticks turn slowly at a fixed rate.
 *  - Playing with a phase-locked HioneBeatClock grid: it locks. On every beat the crosshair snaps
 *    tight to the center and back to square, the ring flashes, and the bezel tick for that beat
 *    lights. Between beats the arms ease open and drift a few degrees. An ice blue scan line
 *    sweeps the ring once every four beats. The first of each four gets the full amber flash and
 *    a center pip. That count of four starts at the grid anchor: it is a bar of four beats, not a
 *    detected downbeat.
 *  - Animator duration scale 0: it draws the open reticle once and runs no frame loop.
 *
 * COST. Two float states, read only in the draw lambda, so a frame re-draws this element and
 * recomposes nothing. Strokes and sizes are built in drawWithCache. The frame loop runs only
 * while playing. Paused, it checks twice a second with a plain delay. The caller removes it
 * while the screen is idle, which ends the loop.
 */
@Composable
fun CodaLock(playing: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val still = remember { motionReduced(ctx) }
    val playingNow by rememberUpdatedState(playing)
    // Position in the current four-beat bar, 0..4, or -1 with no locked grid.
    val beatPos = remember { mutableFloatStateOf(-1f) }
    // Bezel rotation in degrees.
    val bezel = remember { mutableFloatStateOf(0f) }

    if (!still) LaunchedEffect(Unit) {
        var last = 0L
        var bez = bezel.floatValue
        while (true) {
            if (!playingNow) {
                beatPos.floatValue = -1f
                last = 0L
                delay(500)
                continue
            }
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
            last = now
            val g = HioneBeatClock.grid
            if (g.periodNs > 0.0 && g.anchorNs != 0L && g.phaseLocked) {
                val b = (SystemClock.elapsedRealtimeNanos() - g.anchorNs) / g.periodNs
                beatPos.floatValue = (b - 4.0 * floor(b / 4.0)).toFloat()
                // One bezel turn per 16 beats.
                bez += dt * (1e9 / g.periodNs).toFloat() * 22.5f
            } else {
                beatPos.floatValue = -1f
                bez += dt * 24f
            }
            bez %= 360f
            bezel.floatValue = bez
        }
    }

    Box(
        modifier.drawWithCache {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val ringR = 0.56f * r
            val ringW = maxOf(1.4.dp.toPx(), 0.085f * r)
            val ring = Stroke(width = ringW)
            val armW = maxOf(1.2.dp.toPx(), 0.08f * r)
            val tickW = maxOf(1.2.dp.toPx(), 0.08f * r)
            val scanW = maxOf(1.dp.toPx(), 0.05f * r)
            val tickIn = ringR + ringW
            val tickOut = 0.86f * r
            val armOut = 0.99f * r
            val scanTL = Offset(c.x - ringR, c.y - ringR)
            val scanSize = Size(2f * ringR, 2f * ringR)

            onDrawBehind {
                val bp = beatPos.floatValue
                val locked = bp >= 0f
                val i = if (locked) floor(bp).toInt().coerceIn(0, 3) else -1
                val ph = if (locked) bp - i else 0f
                val env = if (locked) (1f - ph).let { it * it * it } else 0f
                val bar = i == 0

                drawCircle(CodaBackdrop, r * 0.98f, c, alpha = 0.55f)

                // Scan line: once round the ring per four beats, from 12 o'clock, with a faint wake.
                if (locked) {
                    val deg = bp / 4f * 360f - 90f
                    drawArc(CodaIce, deg - 40f, 40f, true, topLeft = scanTL, size = scanSize, alpha = 0.16f)
                    val a = Math.toRadians(deg.toDouble())
                    drawLine(
                        CodaIce, c,
                        Offset(c.x + ringR * cos(a).toFloat(), c.y + ringR * sin(a).toFloat()),
                        scanW, StrokeCap.Round, alpha = 0.85f
                    )
                }

                // Ring, with the beat flash over it.
                drawCircle(CodaAmber, ringR, c, alpha = if (locked) 0.95f else 0.7f, style = ring)
                if (env > 0.01f) drawCircle(CodaAmberHot, ringR, c, alpha = env * (if (bar) 1f else 0.45f), style = ring)

                // Bezel ticks between the arms. The one for the current beat is lit and kicks out.
                rotate(bezel.floatValue + 45f, c) {
                    for (k in 0 until 4) {
                        val lit = k == i
                        val out = if (lit) tickOut + env * 0.1f * r else tickOut
                        rotate(k * 90f, c) {
                            drawLine(
                                if (lit) CodaAmberHot else CodaAmber,
                                Offset(c.x, c.y - tickIn), Offset(c.x, c.y - out),
                                tickW, StrokeCap.Round, alpha = if (lit) 1f else 0.35f
                            )
                        }
                    }
                }

                // Crosshair: open while searching. Locked, it snaps tight on the beat and eases
                // back open, drifting up to 10 degrees before the next snap.
                val gap = if (locked) 0.34f * r - (0.22f * r) * env else 0.34f * r
                rotate(if (locked) 10f * ph * ph else 0f, c) {
                    drawLine(CodaAmber, Offset(c.x, c.y - gap), Offset(c.x, c.y - armOut), armW, StrokeCap.Butt)
                    drawLine(CodaAmber, Offset(c.x, c.y + gap), Offset(c.x, c.y + armOut), armW, StrokeCap.Butt)
                    drawLine(CodaAmber, Offset(c.x - gap, c.y), Offset(c.x - armOut, c.y), armW, StrokeCap.Butt)
                    drawLine(CodaAmber, Offset(c.x + gap, c.y), Offset(c.x + armOut, c.y), armW, StrokeCap.Butt)
                }

                // Lock pip on the first beat of the four.
                if (bar && env > 0.01f) drawCircle(CodaAmberHot, 0.08f * r, c, alpha = env)
            }
        }
    )
}
