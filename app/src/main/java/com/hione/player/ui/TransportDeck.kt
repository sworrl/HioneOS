package com.hione.player.ui

import com.hione.player.Mascot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.hione.player.AudioCapture
import com.hione.player.ControlAssembly
import com.hione.player.HapticIconButton
import com.hione.player.IdleController
import com.hione.player.Muted
import com.hione.player.PlayPauseGlyph
import com.hione.player.TransportShapes
import kotlinx.coroutines.delay

/**
 * The Now Playing transport deck: the existing molded [ControlAssembly] + 3D [HapticIconButton]
 * keys (same raised/pressed bevel language as the 3D hearts), upgraded with:
 *  - a palette-tinted hero play key sitting in a living glass halo that breathes with the REAL
 *    bass energy from [AudioCapture] while playing (parks when paused / screen dimmed);
 *  - prev/next wings tinted by the art accent;
 *  - shuffle/repeat as flat keys with a hardware-style LED dot that lights when engaged.
 * Play/pause glyph morph is [PlayPauseGlyph] (spring pop), unchanged.
 */
@Composable
fun GlassTransportDeck(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: Boolean,
    accent: Color,
    accent2: Color,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Halo drive: smoothed bass (0..1). Manual loop (device animator scale may be 0).
    var halo by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) { halo = 0f; return@LaunchedEffect }
        while (true) {
            if (IdleController.screenActive) {
                val target = (AudioCapture.bass * 0.9f).coerceIn(0f, 1f)
                halo += (target - halo) * 0.35f
                delay(40)
            } else delay(500)
        }
    }
    val ink = onAccentColor(accent)

    Row(modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        ControlAssembly(cornerRadius = 34.dp) {
            LedKey(icon = Icons.Default.Shuffle, desc = "Shuffle", on = shuffle, ledColor = accent2, onClick = onShuffle)
            HapticIconButton(onClick = onPrev, keyShape = TransportShapes.prevWing, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, "Prev", tint = accent, modifier = Modifier.size(30.dp))
            }
            // Hero play key: glass halo behind, palette-tinted faceted face in front.
            Box(
                Modifier
                    .size(width = 84.dp, height = 62.dp)
                    .drawBehind {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val base = size.minDimension * 0.62f
                        val r = base * (1f + halo * 0.35f)
                        drawCircle(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = 0.32f + halo * 0.35f), accent.copy(alpha = 0.10f), Color.Transparent),
                                center = c, radius = r
                            ),
                            radius = r, center = c
                        )
                        if (isPlaying) drawCircle(lerp(accent, Color.White, 0.4f).copy(alpha = 0.18f + halo * 0.3f), radius = r * 0.78f, center = c, style = Stroke(1.5f))
                    },
                contentAlignment = Alignment.Center
            ) {
                HapticIconButton(onClick = onPlayPause, face = accent, keyShape = TransportShapes.hero, modifier = Modifier.size(width = 84.dp, height = 62.dp)) {
                    PlayPauseGlyph(isPlaying, tint = ink, size = 36.dp)
                }
            }
            HapticIconButton(onClick = onNext, keyShape = TransportShapes.nextWing, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, "Next", tint = accent, modifier = Modifier.size(30.dp))
            }
            LedKey(icon = Icons.Default.Repeat, desc = "Repeat", on = repeat, ledColor = accent2, onClick = onRepeat)
        }
    }
}

/** Flat mode key with a tiny LED under the glyph — lit + glowing when the mode is engaged. */
@Composable
private fun LedKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    on: Boolean,
    ledColor: Color,
    onClick: () -> Unit
) {
    HapticIconButton(onClick = onClick, flat = true, modifier = Modifier.size(40.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, desc, tint = if (on) ledColor else Muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .size(width = 12.dp, height = 3.dp)
                    .drawBehind {
                        val rr = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f)
                        if (on) {
                            drawRoundRect(ledColor.copy(alpha = 0.45f), topLeft = Offset(-2f, -2f), size = androidx.compose.ui.geometry.Size(size.width + 4f, size.height + 4f), cornerRadius = rr)
                            drawRoundRect(lerp(ledColor, Color.White, 0.3f), cornerRadius = rr)
                        } else {
                            drawRoundRect(Mascot.color(0xFF12292D), cornerRadius = rr)
                            drawRoundRect(Color(0x33FFFFFF), cornerRadius = rr, style = Stroke(0.8f))
                        }
                    }
            )
        }
    }
}
