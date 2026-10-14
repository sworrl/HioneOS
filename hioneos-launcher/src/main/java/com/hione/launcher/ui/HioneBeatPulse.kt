package com.hione.launcher.ui

import com.hione.launcher.Mascot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import com.hione.launcher.bpm.HioneBpmEngine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Highlight + beat pulse for anything that opens the BPM game.
 *
 * An entry point has to read as "this is a game, and it is playing along right now". So it always
 * carries an accent ring (the static highlight), and while music plays with a known tempo the ring
 * flares and the element kicks a few percent on every beat, phase-locked to the same beat clock
 * the game judges against ([HioneBpmEngine.state]: smoothed last pulse + locked period). No tempo,
 * no pulse: it never flashes on a beat nobody measured.
 *
 * COST. The beat value is a float state written once per frame and read only inside graphicsLayer
 * and drawBehind, so a beat costs a layer update and a redraw, never a recomposition. The caller
 * recomposes only when "is there a live tempo" flips. The frame loop does not run while there is
 * no live tempo, while the ambient/low-power gate is closed (screen dimmed, launcher covered or in
 * the background), or once the element leaves composition; Compose also pauses the frame clock
 * when the activity stops.
 */
@Composable
fun Modifier.hioneBeatPulse(accent: Color, corner: Dp = 12.dp, kick: Float = 0.06f, insideCover: Boolean = false): Modifier {
    val live by remember {
        HioneBpmEngine.state.map {
            it.isPlaying && it.bpm > 0f && it.lastPulseEpochMs > 0L && it.beatIntervalMs in 200L..3000L
        }.distinctUntilChanged()
    }.collectAsState(initial = false)
    // insideCover: the element lives INSIDE something that marks home as covered (the app
    // drawer sheet), so the covered count must not freeze it; everything else still applies.
    val gate by if (insideCover) rememberUncoveredGate() else rememberAmbientGate()
    val beat = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(live, gate) {
        beat.floatValue = 0f
        if (!live || gate) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            val s = HioneBpmEngine.state.value
            val period = s.beatIntervalMs.toFloat()
            if (period <= 0f || s.lastPulseEpochMs <= 0L) { beat.floatValue = 0f; continue }
            val since = (System.currentTimeMillis() - s.lastPulseEpochMs).toFloat()
            val phase = (since.mod(period)) / period
            val b = 1f - phase
            beat.floatValue = b * b * b
        }
    }
    return this
        .graphicsLayer {
            val s = 1f + kick * beat.floatValue
            scaleX = s; scaleY = s
        }
        .drawBehind {
            val b = beat.floatValue
            val r = CornerRadius(corner.toPx(), corner.toPx())
            // Glow that swells on the beat, then the ring that is always there (the highlight).
            if (b > 0.02f) {
                val grow = 3.dp.toPx() * b
                drawRoundRect(
                    accent.copy(alpha = 0.35f * b),
                    topLeft = androidx.compose.ui.geometry.Offset(-grow, -grow),
                    size = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2),
                    cornerRadius = CornerRadius(r.x + grow, r.y + grow)
                )
            }
            drawRoundRect(
                accent.copy(alpha = 0.55f + 0.45f * b),
                cornerRadius = r,
                style = Stroke(width = (1.5f + 1.5f * b).dp.toPx())
            )
        }
}

/**
 * The home-screen BPM game patch: a highlighted pill with the controller icon and "BPM game",
 * pulsing on the beat while music plays. Opens the game where the caller decides (on home it is
 * the in-launcher modal, so closing it lands back on home).
 */
@Composable
fun HioneBpmGameEntryPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = Mascot.color(0xFFFF4FA3)
    Box(
        modifier
            .hioneBeatPulse(accent, corner = 12.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SportsEsports, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("BPM game", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, maxLines = 1)
        }
    }
}

/** [rememberAmbientGate] minus the "covered" term, for elements drawn on the covering surface. */
@Composable
private fun rememberUncoveredGate(): androidx.compose.runtime.State<Boolean> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { HioneIdleTier.attach(ctx) }
    val low by rememberLowPower()
    val attention by HioneAmbient.attention.collectAsState()
    val playing by HioneAmbient.playing.collectAsState()
    val visible by HionePowerProfile.visible.collectAsState()
    val idleTier by HioneIdleTier.tier.collectAsState()
    return remember {
        androidx.compose.runtime.derivedStateOf {
            low || !visible || !(attention || playing) || idleTier != HioneIdleTier.TIER_ACTIVE
        }
    }
}
