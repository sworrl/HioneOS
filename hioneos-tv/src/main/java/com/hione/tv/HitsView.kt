package com.hione.tv

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.ProjectMNative

/**
 * Hione Hits TV Main Overview Screen.
 *
 * Full-screen ProjectM visualizer running in the background with unimpeded center viewing area,
 * flanked by a large 4K Album Artwork stage and authentic DAP metadata card, with live adaptive
 * streaming jitter cache telemetry, audio-reactive wavy scrubber, and focusable glass transport controls.
 */
@Composable
fun HitsView(
    client: HioneCastClient,
    state: HioneCastClient.ClientState,
    visuals: HioneCastClient.AudioVisuals,
    onCommand: (String) -> Unit,
    onSwitchMode: (TvViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    // Dynamic color-based ambient animation for background and accents (matching M500 app)
    val infiniteTransition = rememberInfiniteTransition(label = "ambientMesh")
    val ambientShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientShift"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // ── 1. ROOT BACKGROUND: FULL-SCREEN PROJECTM VISUALIZER OR COLOR-MESH ──
        if (ProjectMNative.available) {
            ProjectMVisualizerView(
                client = client,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Liquid color-mesh gradient fallback matching M500 app
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                HioneOrange.copy(alpha = 0.35f + ambientShift * 0.15f),
                                HioneTeal.copy(alpha = 0.25f + (1f - ambientShift) * 0.15f),
                                Color(0xFF030A10)
                            ),
                            center = Offset(800f * ambientShift, 500f)
                        )
                    )
            )
            VisualizerCanvasFallback(visuals = visuals)
        }

        // ── 2. CINEMATIC GRADIENT MASK (Soft center transparency, dark edges for UI legibility) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.10f),
                            Color.Black.copy(alpha = 0.10f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.88f)
                        )
                    )
                )
        )

        // ── 3. MAIN UI OVERVIEW LAYER ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── TOP HEADER TELEMETRY STRIP ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Top-Left Badge: Engine Status
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xCC050E11))
                        .border(1.2.dp, HioneOrange.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(HioneTeal)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (ProjectMNative.available) "PROJECTM 4.2 GL · CINEMATIC STAGE" else "HIONE AUDIO REACTIVE STAGE",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                // Top-Right Badges: Bit-Perfect Status & Jitter Cache
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Streaming Jitter Buffer Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xCC03121E))
                            .border(1.2.dp, HioneTeal.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isBuffering) Color(0xFFFFB020) else HioneTeal)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = if (state.isBuffering)
                                    "BUFFERING CACHE: ${state.bufferedMs}ms / ${state.cacheMs}ms"
                                else
                                    "STREAM CACHE: ${(state.cacheMs / 1000f)}s ADAPTIVE",
                                color = if (state.isBuffering) Color(0xFFFFB020) else HioneTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = AudiowideFont
                            )
                        }
                    }

                    // Direct Output Badge
                    val exact = state.passthroughExact
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (exact) Color(0xCC002B18) else Color(0xCC332000))
                            .border(
                                1.2.dp,
                                if (exact) Color(0xFF00FF88) else Color(0xFFFFB020),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = if (exact) "BIT-PERFECT DIRECT" else "TV RESAMPLED",
                            color = if (exact) Color(0xFF00FF88) else Color(0xFFFFB020),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // ── CENTER STAGE: FLANKED 4K ALBUM ART & UNIMPEDED VISUALIZER VIEW ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── LEFT: MAXIMIZED 4K ALBUM ARTWORK CARD ──
                Box(
                    modifier = Modifier
                        .size(310.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0x88040C12))
                        .border(
                            2.dp,
                            Brush.linearGradient(
                                listOf(
                                    HioneOrange.copy(alpha = 0.85f),
                                    HioneTeal.copy(alpha = 0.85f)
                                )
                            ),
                            RoundedCornerShape(24.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.artBitmap != null) {
                        Image(
                            bitmap = state.artBitmap,
                            contentDescription = "Album Art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Holographic Vinyl Disc Emblem when streaming
                        Box(
                            modifier = Modifier
                                .size(240.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0A1015))
                                .border(3.dp, HioneOrange.copy(alpha = 0.8f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            // Grooves
                            Box(
                                modifier = Modifier
                                    .size(190.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, Color(0x33FFFFFF), CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(140.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, Color(0x22FFFFFF), CircleShape)
                            )
                            // Center label
                            Box(
                                modifier = Modifier
                                    .size(90.dp)
                                    .clip(CircleShape)
                                    .background(HioneTeal.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = "Music",
                                    tint = Color.Black,
                                    modifier = Modifier.size(42.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(36.dp))

                // ── CENTER UNIMPEDED VISUALIZER PORTAL ──
                // Intentionally open space where ProjectM shines through clearly
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    // Floating Center Target Reticle (Subtle cyber branding)
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .border(1.dp, HioneTeal.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .border(1.dp, HioneOrange.copy(alpha = 0.20f), CircleShape)
                        )
                    }
                }

                Spacer(Modifier.width(36.dp))

                // ── RIGHT: RICH METADATA & FORMAT BADGES CARD ──
                Box(
                    modifier = Modifier
                        .width(420.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xCC07141C))
                        .border(
                            1.5.dp,
                            Brush.verticalGradient(
                                listOf(
                                    HioneTeal.copy(alpha = 0.7f),
                                    HioneOrange.copy(alpha = 0.6f)
                                )
                            ),
                            RoundedCornerShape(22.dp)
                        )
                        .padding(horizontal = 26.dp, vertical = 22.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NOW PLAYING",
                                color = HioneTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont,
                                letterSpacing = 1.5.sp
                            )

                            // Glowing Heart Badge
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x22FF5500))
                                    .border(1.2.dp, HioneOrange, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "Liked",
                                    tint = HioneOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Track Title
                        Text(
                            text = state.title.ifBlank { if (state.connected) "Audio Stream Ready" else state.status },
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 32.sp
                        )

                        // Artist and Album
                        if (state.artist.isNotBlank() || state.album.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                if (state.artist.isNotBlank()) {
                                    Text(
                                        text = state.artist,
                                        color = Color.White.copy(alpha = 0.90f),
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (state.album.isNotBlank()) {
                                    Text(
                                        text = state.album,
                                        color = HioneOrange,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Technical Format Chips
                        val src = state.source
                        val bits = if (src.bytesPerSample > 0) src.bytesPerSample * 8 else 24
                        val khz = if (src.sampleRate > 0) "%.1fKHZ".format(src.sampleRate / 1000f) else "48.0KHZ"
                        val dur = state.durationMs

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvDataChip("FLAC", HioneOrange)
                            TvDataChip("${bits}-BIT", HioneOrange)
                            TvDataChip(khz, HioneOrange)
                            if (dur > 0L) {
                                TvDataChip(formatMs(dur), HioneTeal)
                            }
                        }
                    }
                }
            }

            // ── 4. BOTTOM SECTION: WAVY SCRUBBER, TRANSPORT DECK & TELEMETRY ──
            val dur = state.durationMs
            val pos = state.positionMs

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC050E15))
                    .border(1.2.dp, Color(0x3300E5FF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Wavy Scrubber Bar
                WavyScrubber(
                    pos = pos,
                    dur = dur,
                    playing = state.isPlaying,
                    accent = HioneOrange,
                    accent2 = HioneTeal,
                    rms = visuals.rms,
                    modifier = Modifier.fillMaxWidth()
                )

                // Timestamps and Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatMs(pos),
                        color = HioneTeal,
                        fontSize = 15.sp,
                        fontFamily = OrbitronFont,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = if (state.connected)
                            "LINKED TO M500 DAP · JITTER CACHE: ${state.cacheMs}ms"
                        else
                            "WAITING FOR STREAM",
                        color = HioneMuted.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        fontFamily = OrbitronFont,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = if (dur > 0L) formatMs(dur) else "--:--",
                        color = HioneTeal,
                        fontSize = 15.sp,
                        fontFamily = OrbitronFont,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                // Focusable TV Remote Transport Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvFocusableButton(
                        onClick = { onCommand("prev") },
                        icon = Icons.Default.SkipPrevious,
                        label = "Prev",
                        height = 42.dp
                    )
                    TvFocusableButton(
                        onClick = { onCommand("rewind") },
                        icon = Icons.Default.FastRewind,
                        label = "-15s",
                        height = 42.dp
                    )
                    TvFocusableButton(
                        onClick = { onCommand("playpause") },
                        icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        label = if (state.isPlaying) "Pause" else "Play",
                        primary = true,
                        height = 42.dp
                    )
                    TvFocusableButton(
                        onClick = { onCommand("forward") },
                        icon = Icons.Default.FastForward,
                        label = "+15s",
                        height = 42.dp
                    )
                    TvFocusableButton(
                        onClick = { onCommand("next") },
                        icon = Icons.Default.SkipNext,
                        label = "Next",
                        height = 42.dp
                    )
                    Spacer(Modifier.width(14.dp))
                    TvFocusableButton(
                        onClick = { onSwitchMode(TvViewMode.CASSETTE) },
                        icon = Icons.Default.Album,
                        label = "Cassette Deck",
                        height = 42.dp,
                        fontSize = 12.sp
                    )
                    TvFocusableButton(
                        onClick = { onSwitchMode(TvViewMode.VISUALIZER) },
                        icon = Icons.Default.GraphicEq,
                        label = "Fullscreen Stage",
                        height = 42.dp,
                        fontSize = 12.sp
                    )
                }

                // Pipeline Telemetry Ribbon
                val src = state.source
                Text(
                    text = if (src.sampleRate > 0)
                        "STREAM: ${src.sampleRate} Hz · ${src.bytesPerSample * 8}-bit · BUFFER: ${state.cacheMs}ms (${state.bufferedMs}ms held) · TV OUT: ${state.grantedRate} Hz (${state.grantedEncodingLabel})"
                    else
                        "WAITING FOR M500 DAP AUDIO STREAM",
                    color = HioneMuted.copy(alpha = 0.7f),
                    fontSize = 10.5.sp,
                    fontFamily = OrbitronFont,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun VisualizerCanvasFallback(visuals: HioneCastClient.AudioVisuals) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val spec = visuals.spectrum
        val n = spec.size
        val bw = w / (n.toFloat() * 1.4f)
        val gap = bw * 0.4f

        for (i in 0 until n) {
            val barH = (spec[i] * h * 0.4f).coerceAtLeast(4f)
            val x = i * (bw + gap) + gap
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(HioneOrange, HionePink)),
                topLeft = androidx.compose.ui.geometry.Offset(x, midY - barH),
                size = androidx.compose.ui.geometry.Size(bw, barH * 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2f, bw / 2f)
            )
        }
    }
}

internal fun formatMs(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val sec = (safe / 1000) % 60
    val min = (safe / 1000) / 60
    return "%d:%02d".format(min, sec)
}
