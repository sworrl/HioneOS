package com.hione.launcher.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared Rainbow Beating Heart Colon for all HioneOS clocks.
 *
 * Replaces plain text ":" colons with rainbow spectrum-cycling stacked hearts.
 * When music is playing, pulses to the track BPM.
 * When idle, pulses at the steady 1 Hz (60 BPM) clock heartbeat.
 */
@Composable
fun HioneRainbowHeartColon(
    isPlaying: Boolean = false,
    bpm: Int = 0,
    size: Dp = 6.5.dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "HeartColonRainbow")

    val hue by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RainbowHue"
    )

    val rainbowColor = remember(hue) {
        val hsv = floatArrayOf((hue % 360f + 360f) % 360f, 0.95f, 1.0f)
        Color(android.graphics.Color.HSVToColor(255, hsv))
    }

    val effectiveBpm = if (isPlaying && bpm in 40..260) bpm else 60
    val periodMs = (60_000L / effectiveBpm).coerceIn(230L, 1500L)

    val pulseScale = remember { Animatable(1.0f) }
    LaunchedEffect(effectiveBpm, isPlaying) {
        while (true) {
            pulseScale.animateTo(1.24f, tween((periodMs * 0.28f).toInt().coerceAtLeast(60), easing = FastOutSlowInEasing))
            pulseScale.animateTo(1.0f, tween((periodMs * 0.72f).toInt().coerceAtLeast(140), easing = LinearOutSlowInEasing))
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((size.value * 0.35f).dp),
        modifier = modifier
            .padding(horizontal = 2.dp)
            .scale(pulseScale.value)
    ) {
        HioneHeartGlyph(color = rainbowColor, size = size)
        HioneHeartGlyph(color = rainbowColor, size = size)
    }
}

@Composable
fun HioneHeartGlyph(
    color: Color,
    size: Dp = 6.5.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.88f)
            cubicTo(w * 0.08f, h * 0.58f, 0f, h * 0.38f, 0f, h * 0.24f)
            cubicTo(0f, h * 0.08f, w * 0.18f, 0f, w * 0.36f, 0f)
            cubicTo(w * 0.44f, 0f, w * 0.5f, h * 0.08f, w * 0.5f, h * 0.16f)
            cubicTo(w * 0.5f, h * 0.08f, w * 0.56f, 0f, w * 0.64f, 0f)
            cubicTo(w * 0.82f, 0f, w, h * 0.08f, w, h * 0.24f)
            cubicTo(w, h * 0.38f, w * 0.92f, h * 0.58f, w * 0.5f, h * 0.88f)
            close()
        }
        drawPath(path = path, color = color)
    }
}
