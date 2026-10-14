package com.hione.player

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.hione.player.bpm.HioneBeatClock
import com.hione.player.bpm.HioneBpmEngine

/**
 * The Now Playing secret (HioneUnlocksReader.SECRET_LEEK_SPIN), drawn for the active mascot pack:
 * the leek for the Hione pack, the coda lock ([CodaLock]) for every other pack. It sits beside
 * the "Play along" pill, off the art and the visualizer.
 */
@Composable
fun SecretBeatAccent(playing: Boolean, modifier: Modifier = Modifier) {
    // Mascot.pack is Compose state, so a pack switch swaps the accent live.
    if (Mascot.hasHioneLore) LeekSpin(playing, modifier) else CodaLock(playing, modifier)
}

/** True when the system animator duration scale is 0 (Developer options or accessibility). */
internal fun motionReduced(ctx: Context): Boolean = try {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Throwable) {
    false
}

/**
 * The secret leek: a small spring onion twirling beside the "Play along" pill, after the old
 * leek-spin loop.
 *
 * TEMPO. With a phase-locked beat grid from HioneBeatClock and the music playing, the angle is
 * computed from the grid: one turn per two beats, upright on every other beat. With a tempo but
 * no phase yet it turns at the tempo. With no tempo (0 = unknown) or while paused it does not
 * spin, it sways at a fixed slow rate, so it is never seen keeping time it does not have. With
 * the animator duration scale at 0 it stands still and runs no frame loop.
 *
 * COST. The angle lives in one float state that is read only inside graphicsLayer, so a frame
 * re-draws this layer and recomposes nothing. Paths and brushes are built in drawWithCache and
 * only rebuilt on a size change. The caller removes it entirely while the screen is idle, which
 * also ends the frame loop.
 */
@Composable
fun LeekSpin(playing: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val still = remember { motionReduced(ctx) }
    val playingNow by rememberUpdatedState(playing)

    var angle by remember { mutableFloatStateOf(0f) }
    if (!still) LaunchedEffect(Unit) {
        var spinPhase = 0f       // degrees, accumulated so a tempo change never makes it jump
        var swayT = 0f           // seconds of sway
        var from = 0f            // angle when the mode last changed
        var ease = 1f            // 0..1 blend from [from] into the new mode
        var mode = -1            // 0 sway, 1 tempo only, 2 beat grid
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
            last = now
            val g = HioneBeatClock.grid
            val b = HioneBpmEngine.currentBpm
            val m = when {
                !playingNow -> 0
                g.periodNs > 0.0 && g.anchorNs != 0L && g.phaseLocked -> 2
                b > 0f -> 1
                else -> 0
            }
            if (m != mode) {
                from = angle
                ease = if (mode == -1) 1f else 0f
                if (m == 1) spinPhase = (angle % 360f + 360f) % 360f
                if (m == 0) swayT = 0f
                mode = m
            }
            ease = (ease + dt / 0.5f).coerceAtMost(1f)
            val want = when (m) {
                2 -> {
                    // 180 degrees per beat from the grid anchor: exact phase, no drift.
                    val beats = (SystemClock.elapsedRealtimeNanos() - g.anchorNs) / g.periodNs
                    val d = (beats * 180.0) % 360.0
                    (if (d < 0) d + 360.0 else d).toFloat()
                }
                1 -> {
                    spinPhase = (spinPhase + dt * (b / 60f) * 180f) % 360f
                    spinPhase
                }
                else -> {
                    // Fixed, unhurried sway: a 3.2 s period, +-16 degrees.
                    swayT += dt
                    16f * kotlin.math.sin(swayT * (2f * Math.PI.toFloat() / 3.2f))
                }
            }
            angle = if (ease >= 1f) want else {
                // Shortest way round from where the last mode left it.
                var delta = (want - from) % 360f
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                from + delta * ease
            }
        }
    }

    Box(
        modifier
            .graphicsLayer { rotationZ = angle }
            .drawWithCache {
                // Drawn in a 100 x 100 unit box, leaves up, bulb down.
                val u = size.minDimension / 100f
                val ox = (size.width - 100f * u) / 2f
                val oy = (size.height - 100f * u) / 2f
                fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)

                val leafL = Path().apply {
                    moveTo(p(45f, 44f).x, p(45f, 44f).y)
                    quadraticBezierTo(p(30f, 24f).x, p(30f, 24f).y, p(18f, 4f).x, p(18f, 4f).y)
                    quadraticBezierTo(p(38f, 18f).x, p(38f, 18f).y, p(51f, 42f).x, p(51f, 42f).y)
                    close()
                }
                val leafC = Path().apply {
                    moveTo(p(46f, 44f).x, p(46f, 44f).y)
                    quadraticBezierTo(p(48f, 18f).x, p(48f, 18f).y, p(53f, 1f).x, p(53f, 1f).y)
                    quadraticBezierTo(p(57f, 20f).x, p(57f, 20f).y, p(55f, 44f).x, p(55f, 44f).y)
                    close()
                }
                val leafR = Path().apply {
                    moveTo(p(50f, 43f).x, p(50f, 43f).y)
                    quadraticBezierTo(p(66f, 22f).x, p(66f, 22f).y, p(84f, 8f).x, p(84f, 8f).y)
                    quadraticBezierTo(p(70f, 28f).x, p(70f, 28f).y, p(56f, 46f).x, p(56f, 46f).y)
                    close()
                }
                val stemTL = p(43f, 40f)
                val stemSize = Size(14f * u, 40f * u)
                val stemBrush = Brush.verticalGradient(
                    listOf(Color(0xFFB8E6A0), Color(0xFFEFFBE6), Color(0xFFFFFFFF)),
                    startY = stemTL.y, endY = stemTL.y + stemSize.height
                )
                val bulbTL = p(39f, 70f)
                val bulbSize = Size(22f * u, 22f * u)
                val leafGreen = Color(0xFF3FAE4A)
                val leafDark = Color(0xFF1E6B2A)
                val outline = Color(0xCC0B2A12)
                val line = Stroke(width = 1.6f * u)
                val rootColor = Color(0xFFD9CBA3)
                val rootW = 1.4f * u
                val r0 = p(50f, 91f); val rA = p(44f, 99f); val rB = p(50f, 100f); val rC = p(56f, 99f)

                onDrawBehind {
                    // Roots, bulb, stem, then the leaves over the top of the stem.
                    drawLine(rootColor, r0, rA, rootW, StrokeCap.Round)
                    drawLine(rootColor, r0, rB, rootW, StrokeCap.Round)
                    drawLine(rootColor, r0, rC, rootW, StrokeCap.Round)
                    drawOval(Color.White, topLeft = bulbTL, size = bulbSize)
                    drawOval(outline, topLeft = bulbTL, size = bulbSize, style = line)
                    drawRect(stemBrush, topLeft = stemTL, size = stemSize)
                    drawRect(outline, topLeft = stemTL, size = stemSize, style = line)
                    drawPath(leafL, leafDark)
                    drawPath(leafR, leafDark)
                    drawPath(leafC, leafGreen)
                    drawPath(leafL, outline, style = line)
                    drawPath(leafC, outline, style = line)
                    drawPath(leafR, outline, style = line)
                }
            }
    )
}
