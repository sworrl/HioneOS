package com.hione.tv

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.ProjectMNative
import kotlin.math.*

enum class VisualizerPreset(val title: String) {
    COSMIC_SUPERNOVA("Milkdrop Cosmic Supernova"),
    SYNTHWAVE_GRID("Synthwave Cyber Horizon"),
    SPECTRUM_MATRIX("64-Band Matrix Equalizer"),
    PHOSPHOR_OSCILLOSCOPE("Cathode Oscilloscope"),
    STARFIELD_VORTEX("Warp Starfield Vortex")
}

@Composable
fun VisualizerView(
    client: HioneCastClient,
    state: HioneCastClient.ClientState,
    visuals: HioneCastClient.AudioVisuals,
    presetIndex: Int,
    lastUserActionTime: Long = 0L,
    onCommand: (String) -> Unit,
    onCyclePreset: (Int) -> Unit,
    onBackToHits: () -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = VisualizerPreset.entries
    val currentPreset = presets[presetIndex % presets.size]

    var livePresetName by remember { mutableStateOf(ProjectMNative.presetName()) }
    LaunchedEffect(Unit) {
        while (true) {
            val name = ProjectMNative.presetName()
            if (name.isNotBlank() && name != livePresetName) {
                livePresetName = name
            }
            kotlinx.coroutines.delay(500)
        }
    }

    // Duck controls out of view after 2.5 seconds of remote inactivity
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(lastUserActionTime) {
        controlsVisible = true
        kotlinx.coroutines.delay(2500)
        controlsVisible = false
    }

    val transition = rememberInfiniteTransition(label = "vizClock")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vizTime"
    )

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF03070C))) {
        // Fullscreen ProjectM Native Visualizer (or Fallback Canvas)
        if (ProjectMNative.available) {
            ProjectMVisualizerView(
                client = client,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                when (currentPreset) {
                    VisualizerPreset.COSMIC_SUPERNOVA -> drawCosmicSupernova(visuals, time)
                    VisualizerPreset.SYNTHWAVE_GRID -> drawSynthwaveGrid(visuals, time)
                    VisualizerPreset.SPECTRUM_MATRIX -> drawSpectrumMatrix(visuals)
                    VisualizerPreset.PHOSPHOR_OSCILLOSCOPE -> drawPhosphorOscilloscope(visuals)
                    VisualizerPreset.STARFIELD_VORTEX -> drawStarfieldVortex(visuals, time)
                }
            }
        }

        // ── LIQUID GLASS BOTTOM CONTROLS DECK (HIONEOS / HITS THEMED) ──
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible,
            enter = androidx.compose.animation.fadeIn(tween(300)) + androidx.compose.animation.slideInVertically(tween(300)) { it },
            exit = androidx.compose.animation.fadeOut(tween(350)) + androidx.compose.animation.slideOutVertically(tween(350)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp, start = 48.dp, end = 48.dp)
        ) {
            val dur = state.durationMs
            val pos = state.positionMs
            val src = state.source

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xD90A2228),
                                Color(0xF2040E12)
                            )
                        )
                    )
                    .border(
                        1.5.dp,
                        Brush.horizontalGradient(
                            listOf(
                                HioneOrange.copy(alpha = 0.75f),
                                Color.White.copy(alpha = 0.25f),
                                HioneTeal.copy(alpha = 0.75f)
                            )
                        ),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Row: Engine Telemetry + Metadata + Glowing Heart
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Engine badge + preset
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(HioneTeal)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (ProjectMNative.available)
                                        "PROJECTM 4.2 GL · ${livePresetName.ifBlank { "MILKDROP ENGINE" }.uppercase()}"
                                    else
                                        "PROJECTM ENGINE · ${currentPreset.title.uppercase()}",
                                    color = HioneOrange,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.8.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = state.title.ifBlank { if (state.connected) "Audio Stream Ready" else state.status },
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.artist.isNotBlank() || state.album.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.artist.isNotBlank()) {
                                        Text(
                                            text = state.artist,
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (state.artist.isNotBlank() && state.album.isNotBlank()) {
                                        Text("  ·  ", color = HioneMuted, fontSize = 13.sp)
                                    }
                                    if (state.album.isNotBlank()) {
                                        Text(
                                            text = state.album,
                                            color = HioneOrange,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            // Format pills
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TvDataChip("FLAC", HioneOrange)
                                val bits = if (src.bytesPerSample > 0) src.bytesPerSample * 8 else 24
                                TvDataChip("${bits}-BIT", HioneOrange)
                                val khz = if (src.sampleRate > 0) "%.1fKHZ".format(src.sampleRate / 1000f) else "48.0KHZ"
                                TvDataChip(khz, HioneOrange)
                                TvDataChip(if (state.passthroughExact) "BIT-PERFECT DIRECT" else "DIRECT", HioneGreen)
                                if (dur > 0L) {
                                    TvDataChip(formatMs(dur), HioneTeal)
                                }
                            }
                        }

                        Spacer(Modifier.width(16.dp))

                        // Glowing Heart Badge
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0x3300E5FF))
                                .border(1.5.dp, HioneTeal, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Liked",
                                tint = HioneOrange,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Audio-Reactive Wavy Scrubber in Fullscreen
                    WavyScrubber(
                        pos = pos,
                        dur = dur,
                        playing = state.isPlaying,
                        accent = HioneOrange,
                        accent2 = HioneTeal,
                        rms = visuals.rms,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatMs(pos),
                            color = HioneTeal,
                            fontSize = 14.sp,
                            fontFamily = OrbitronFont,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "PROJECTM FULLSCREEN VISUALIZER",
                            color = HioneMuted.copy(alpha = 0.6f),
                            fontSize = 10.5.sp,
                            fontFamily = OrbitronFont,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = if (dur > 0L) formatMs(dur) else "--:--",
                            color = HioneTeal,
                            fontSize = 14.sp,
                            fontFamily = OrbitronFont,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Focusable Glass Remote Controls
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvFocusableButton(
                            onClick = {
                                if (ProjectMNative.available) ProjectMNative.requestPrev() else onCyclePreset(-1)
                            },
                            icon = Icons.Default.NavigateBefore,
                            label = "Prev Preset",
                            height = 40.dp,
                            fontSize = 11.5.sp
                        )
                        TvFocusableButton(
                            onClick = { onCommand("prev") },
                            icon = Icons.Default.SkipPrevious,
                            label = "Prev",
                            height = 40.dp,
                            fontSize = 11.5.sp
                        )
                        TvFocusableButton(
                            onClick = { onCommand("rewind") },
                            icon = Icons.Default.FastRewind,
                            label = "-15s",
                            height = 40.dp,
                            fontSize = 11.5.sp
                        )
                        TvFocusableButton(
                            onClick = { onCommand("playpause") },
                            icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            label = if (state.isPlaying) "Pause" else "Play",
                            primary = true,
                            height = 40.dp,
                            fontSize = 12.5.sp
                        )
                        TvFocusableButton(
                            onClick = { onCommand("forward") },
                            icon = Icons.Default.FastForward,
                            label = "+15s",
                            height = 40.dp,
                            fontSize = 11.5.sp
                        )
                        TvFocusableButton(
                            onClick = { onCommand("next") },
                            icon = Icons.Default.SkipNext,
                            label = "Next",
                            height = 40.dp,
                            fontSize = 11.5.sp
                        )
                        TvFocusableButton(
                            onClick = {
                                if (ProjectMNative.available) ProjectMNative.requestNext() else onCyclePreset(1)
                            },
                            icon = Icons.Default.NavigateNext,
                            label = "Next Preset",
                            height = 40.dp,
                            fontSize = 11.5.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        TvFocusableButton(
                            onClick = onBackToHits,
                            icon = Icons.Default.ArrowBack,
                            label = "Hits View",
                            height = 40.dp,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawCosmicSupernova(v: HioneCastClient.AudioVisuals, time: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val maxR = min(size.width, size.height) * 0.42f
    val baseR = maxR * 0.35f + v.rms * 80f

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFAB2E), Color(0x66FF3366), Color.Transparent),
            center = center,
            radius = baseR * 1.5f
        ),
        radius = baseR * 1.5f,
        center = center
    )

    val numRays = 72
    val radStep = (2 * Math.PI / numRays).toFloat()
    val tRad = Math.toRadians(time.toDouble()).toFloat()

    for (i in 0 until numRays) {
        val angle = i * radStep + tRad * 0.3f
        val specIdx = (i % 64)
        val mag = v.spectrum[specIdx]
        val r1 = baseR
        val r2 = baseR + mag * (maxR - baseR) * 1.2f

        val start = Offset(center.x + cos(angle) * r1, center.y + sin(angle) * r1)
        val end = Offset(center.x + cos(angle) * r2, center.y + sin(angle) * r2)

        val rayColor = Color.hsl(
            hue = (i * 5f + time * 2f) % 360f,
            saturation = 0.9f,
            lightness = (0.5f + mag * 0.4f).coerceIn(0.2f, 0.95f)
        )

        drawLine(
            color = rayColor,
            start = start,
            end = end,
            strokeWidth = 3f + mag * 4f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawSynthwaveGrid(v: HioneCastClient.AudioVisuals, time: Float) {
    val w = size.width
    val h = size.height
    val horizonY = h * 0.52f

    val sunR = h * 0.22f
    val sunCenter = Offset(w / 2f, horizonY - sunR * 0.3f)
    drawCircle(
        brush = Brush.verticalGradient(listOf(Color(0xFFFFEE55), Color(0xFFFF2A85))),
        radius = sunR,
        center = sunCenter
    )
    for (i in 1..8) {
        val blindY = sunCenter.y + i * (sunR * 0.12f)
        drawLine(
            color = Color(0xFF03070C),
            start = Offset(sunCenter.x - sunR, blindY),
            end = Offset(sunCenter.x + sunR, blindY),
            strokeWidth = 3f + i * 1.2f
        )
    }

    val numLines = 24
    val vp = Offset(w / 2f, horizonY)

    for (i in 0..numLines) {
        val frac = i.toFloat() / numLines
        val bottomX = w * frac
        drawLine(
            color = Color(0x6600F0FF),
            start = vp,
            end = Offset(bottomX, h),
            strokeWidth = 2f
        )
    }

    val rungs = 16
    for (r in 1..rungs) {
        val t = (r.toFloat() / rungs).pow(2.2f)
        val y = horizonY + (h - horizonY) * t
        val specIdx = ((r * 4) % 64)
        val bounce = v.spectrum[specIdx] * 35f * t

        drawLine(
            color = Color(0x99FF2A85),
            start = Offset(0f, y - bounce),
            end = Offset(w, y - bounce),
            strokeWidth = 2f + t * 3f
        )
    }
}

private fun DrawScope.drawSpectrumMatrix(v: HioneCastClient.AudioVisuals) {
    val w = size.width
    val h = size.height
    val numBars = 64
    val barMargin = 4f
    val totalBarWidth = (w - (numBars + 1) * barMargin) / numBars
    val baseLineY = h * 0.82f

    for (i in 0 until numBars) {
        val mag = v.spectrum[i].coerceIn(0.02f, 1.4f)
        val barH = mag * (h * 0.55f)
        val x = barMargin + i * (totalBarWidth + barMargin)

        val brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFF3366), Color(0xFFFFAB2E), Color(0xFF00F0FF)),
            startY = baseLineY - barH,
            endY = baseLineY
        )

        drawRoundRect(
            brush = brush,
            topLeft = Offset(x, baseLineY - barH),
            size = Size(totalBarWidth, barH)
        )
    }
}

private fun DrawScope.drawPhosphorOscilloscope(v: HioneCastClient.AudioVisuals) {
    val w = size.width
    val h = size.height
    val midY = h / 2f
    val wave = v.waveform
    val n = wave.size

    val path = Path()
    for (i in 0 until n) {
        val x = (i.toFloat() / (n - 1).toFloat()) * w
        val y = midY + (wave[i] / 32768f) * (h * 0.38f)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    drawPath(path, Color(0x3300FF88), style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color(0x8800FF88), style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color(0xFFE8FFE8), style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawStarfieldVortex(v: HioneCastClient.AudioVisuals, time: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val maxR = max(size.width, size.height) * 0.65f
    val numStars = 120
    val speed = 1.0f + v.rms * 3.5f

    for (i in 0 until numStars) {
        val seed = i * 47.19f
        val angle = (seed + time * 0.05f) % (2 * Math.PI.toFloat())
        val distFrac = ((seed * 13.7f + time * speed * 8f) % 1000f) / 1000f
        val r = distFrac.pow(2.0f) * maxR

        val x = center.x + cos(angle) * r
        val y = center.y + sin(angle) * r
        val starR = 1.5f + distFrac * 4.5f
        val alpha = (distFrac * 1.2f).coerceIn(0.1f, 1.0f)

        drawCircle(
            color = Color(0xFF8EC5FF).copy(alpha = alpha),
            radius = starR,
            center = Offset(x, y)
        )
    }
}
