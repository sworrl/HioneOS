package com.hione.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.Haptics
import com.hione.player.IdleController
import com.hione.player.HioneBpmGameLink
import com.hione.player.bpm.HioneBpmEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Highlight ring + on-the-beat pulse for the BPM game entry points.
 *
 * Always draws a calm accent ring and soft halo around the element, so the entry point reads as a
 * feature rather than a utility key. While HioneBpmEngine has a beat grid running (music playing
 * AND a known tempo), the element also flashes on every beat: a small scale bump and a brighter
 * ring that decay over the beat. With no grid it stays static. It never invents a beat.
 *
 * BEAT PHASE. phase = (elapsedRealtimeNanos - beatAnchorElapsedNanos) mod beatGridIntervalNanos,
 * the same grid the BPM_PULSE broadcasts are fired on, so the flash lands with the broadcast that
 * the launcher (LED, ring, rhythm game) reacts to.
 *
 * COST. The envelope is one float state read only in graphicsLayer and the draw lambda, so a
 * frame re-draws this element and recomposes nothing. The frame loop only runs while a grid
 * exists; otherwise it checks twice a second with a plain delay. It is not launched at all while
 * the screen is dimmed, ambient or off, and it ends with the element. Compose also stops frames
 * while the activity is stopped.
 *
 * Put it BEFORE any clip on the element, or the halo outside the bounds is clipped away.
 */
fun Modifier.bpmBeatPulse(accent: Color, shape: Shape = CircleShape): Modifier = composed {
    val env = remember { mutableFloatStateOf(0f) }
    val screenOn = IdleController.screenActive
    if (screenOn) {
        LaunchedEffect(Unit) {
            try {
                while (isActive) {
                    val iv = HioneBpmEngine.beatGridIntervalNanos
                    // screenActive is re-checked here as well as at composition: its display-on
                    // half is a plain volatile, so a panel switched off does not recompose us.
                    if (iv <= 0.0 || !HioneBpmEngine.isPlaying || !IdleController.screenActive) {
                        env.floatValue = 0f
                        delay(500)
                        continue
                    }
                    withFrameNanos { }
                    val since = (android.os.SystemClock.elapsedRealtimeNanos() - HioneBpmEngine.beatAnchorElapsedNanos).toDouble()
                    var ph = (since % iv) / iv
                    if (ph < 0.0) ph += 1.0
                    // Sharp attack on the beat, cubic decay through it.
                    val d = (1.0 - ph).toFloat()
                    env.floatValue = d * d * d
                }
            } finally {
                // Screen went idle or the element left: settle on the calm static highlight.
                env.floatValue = 0f
            }
        }
    }
    this
        .graphicsLayer {
            val s = 1f + 0.045f * env.floatValue
            scaleX = s; scaleY = s
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val ring = Stroke(width = 1.5.dp.toPx())
            val halo = Stroke(width = 6.dp.toPx())
            onDrawBehind {
                val e = env.floatValue
                drawOutline(outline, accent, alpha = 0.10f + 0.30f * e, style = halo)
                drawOutline(outline, accent, alpha = 0.55f + 0.45f * e, style = ring)
            }
        }
}

/**
 * The BPM game entry point as one reusable control: a gamepad icon, optionally with a short
 * label, highlighted and pulsing via [bpmBeatPulse]. Opens the game through HioneBpmGameLink,
 * tagged with where it was tapped ([from]).
 */
@Composable
fun BpmGameButton(
    from: String,
    accent: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: Dp = 34.dp,
    iconSize: Dp = 20.dp
) {
    val ctx = LocalContext.current
    val shape = if (label == null) CircleShape else RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .bpmBeatPulse(accent, shape)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null) {
                Haptics.tick(ctx)
                HioneBpmGameLink.open(ctx, from)
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "BPM rhythm game"
                role = Role.Button
            }
            .then(
                if (label == null) Modifier.size(size)
                else Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(Icons.Default.SportsEsports, null, tint = accent, modifier = Modifier.size(iconSize))
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
