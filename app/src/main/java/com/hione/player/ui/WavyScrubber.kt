package com.hione.player.ui

import com.hione.player.Mascot
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hione.player.AudioCapture
import com.hione.player.IdleController
import com.hione.player.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Wavy play bar": the played portion of the seek bar is a live sine ribbon whose amplitude
 * follows the REAL output waveform (the shared [AudioCapture] Visualizer on the player's audio
 * session — the same capture the GL visualizers read, so no second Visualizer fights over the
 * session), settling to a gentle idle swell when paused or when no capture data is flowing.
 * The unplayed remainder is a recessed groove; a glass knob marks the boundary. Drag or tap to
 * seek — same contract as EmbossedScrubber (preview while dragging, commit on release).
 *
 * Perf: one remembered Path rebuilt in place per frame (no per-frame allocation besides Compose's
 * own state writes), ~30 fps while playing / 15 fps idle, and fully parked when the screen is
 * dimmed off (IdleController.screenActive).
 */
@Composable
fun WavyScrubber(
    pos: Long,
    dur: Long,
    playing: Boolean,
    accent: Color,
    accent2: Color,
    sessionId: Int,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val d = dur.coerceAtLeast(1L)
    val hapticCtx = androidx.compose.ui.platform.LocalContext.current   // seek detents
    val prog = (pos.toFloat() / d).coerceIn(0f, 1f)

    var phase by remember { mutableFloatStateOf(0f) }
    var amp by remember { mutableFloatStateOf(0.2f) }
    var frame by remember { mutableIntStateOf(0) }   // bumps to invalidate the Canvas

    // Bind the shared capture to this player session (idempotent; the GL renderers do the same).
    LaunchedEffect(sessionId) {
        if (sessionId > 0) withContext(Dispatchers.Default) { runCatching { AudioCapture.ensure(sessionId) } }
    }

    LaunchedEffect(playing) {
        while (true) {
            if (!IdleController.screenActive) { delay(500); continue }
            if (playing) {
                // RMS of the 64-sample waveform snapshot → 0..1 drive, smoothed so it breathes.
                val wf = AudioCapture.waveform
                var acc = 0f
                for (i in wf.indices) { val s = wf[i]; acc += s * s }
                val rms = sqrt(acc / wf.size)
                val target = (0.22f + rms * 2.6f).coerceIn(0.22f, 1f)
                amp += (target - amp) * 0.28f
                phase += 0.19f
                frame++
                delay(33)
            } else {
                amp += (0.16f - amp) * 0.15f
                phase += 0.05f
                frame++
                delay(66)
            }
        }
    }

    val active = if (playing) accent else lerp(accent, Muted, 0.5f)
    val active2 = if (playing) accent2 else lerp(accent2, Muted, 0.5f)
    val brighter = lerp(active, Color.White, 0.35f)
    val darker = lerp(active, Color.Black, 0.4f)
    val ribbonBrush = remember(active, active2) { Brush.horizontalGradient(listOf(active, active2)) }
    val path = remember { Path() }
    val density = LocalDensity.current
    // Stroke styles are immutable objects — build them once, not per frame.
    val glowStroke = remember(density) { with(density) { Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) } }
    val ribbonStroke = remember(density) { with(density) { Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) } }
    val hiliteStroke = remember(density) { with(density) { Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) } }
    val rimStroke = remember { Stroke(1.2f) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            // Edge-safe: a drag that starts in a window edge band (side = system back, bottom =
            // home) is the OS's, not a scrub. Everything inboard of the bands still scrubs.
            .edgeSafePointerInput(d) { guard ->
                var frac = 0f
                detectHorizontalDragGesturesEdgeSafe(
                    guard,
                    onDragStart = { o -> frac = (o.x / size.width).coerceIn(0f, 1f); com.hione.player.Haptics.seekDetent(hapticCtx, (frac * d).toLong(), d, start = true); onSeekPreview((frac * d).toLong()) },
                    onDragEnd = { onSeekCommit((frac * d).toLong()) },
                    onDragCancel = { onSeekCommit((frac * d).toLong()) }
                ) { change, _ -> frac = (change.position.x / size.width).coerceIn(0f, 1f); com.hione.player.Haptics.seekDetent(hapticCtx, (frac * d).toLong(), d); onSeekPreview((frac * d).toLong()) }
            }
            .pointerInput(d) {
                detectTapGestures { o -> onSeekCommit(((o.x / size.width).coerceIn(0f, 1f) * d).toLong()) }
            }
    ) {
        val tick = frame   // read → this draw re-runs on every animation tick
        if (tick < 0) return@Canvas
        val w = size.width; val cy = size.height / 2f
        val kr = 10.dp.toPx()
        val fillW = (w * prog).coerceIn(0f, w)

        // Recessed groove for the whole track length (what the ribbon "fills").
        val gh = 5.dp.toPx()
        drawRoundRect(Mascot.color(0xFF06201F), topLeft = Offset(0f, cy - gh / 2f), size = Size(w, gh), cornerRadius = CornerRadius(gh / 2f, gh / 2f))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0x66000000), Color(0x00000000)), startY = cy - gh / 2f, endY = cy + gh / 4f),
            topLeft = Offset(0f, cy - gh / 2f), size = Size(w, gh), cornerRadius = CornerRadius(gh / 2f, gh / 2f)
        )
        drawLine(Color(0x1CFFFFFF), Offset(gh, cy + gh / 2f - 0.8f), Offset(w - gh, cy + gh / 2f - 0.8f), 1.2f, cap = StrokeCap.Round)

        // The ribbon: a sine wave over the played portion. Amplitude fades in over the first
        // wavelength and out toward the knob so the line always meets the groove and the knob cleanly.
        if (fillW > 2f) {
            val ampPx = amp * 8.5.dp.toPx()
            val wavelength = 30.dp.toPx()
            val step = 3f
            path.reset()
            var x = 0f
            var first = true
            while (x <= fillW) {
                val envIn = (x / wavelength).coerceIn(0f, 1f)
                val envOut = ((fillW - x) / (wavelength * 0.8f)).coerceIn(0f, 1f)
                val y = cy + sin((x / wavelength) * 2f * PI.toFloat() - phase) * ampPx * envIn * envOut
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                x += step
            }
            if (x - step < fillW) path.lineTo(fillW, cy)
            // Soft glow under the ribbon, then the ribbon, then a thin highlight for the "glass" read.
            drawPath(path, active.copy(alpha = 0.28f), style = glowStroke)
            drawPath(path, ribbonBrush, style = ribbonStroke)
            drawPath(path, brighter.copy(alpha = 0.55f), style = hiliteStroke)
        }

        // Glass knob at the play head: drop shadow, gradient dome, rim light, specular dot.
        val tx = fillW.coerceIn(kr, w - kr)
        drawCircle(Color(0x66000000), kr + 2.5f, Offset(tx, cy + 2f))
        drawCircle(Brush.verticalGradient(listOf(brighter, active, darker), startY = cy - kr, endY = cy + kr), kr, Offset(tx, cy))
        drawCircle(Color.White.copy(alpha = 0.6f), kr, Offset(tx, cy), style = rimStroke)
        drawCircle(Color.White.copy(alpha = 0.65f), kr * 0.30f, Offset(tx - kr * 0.32f, cy - kr * 0.32f))
    }
}
