package com.hione.player.cast

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.hione.player.AudioCapture
import com.hione.player.Mascot
import com.hione.player.Track
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.*

/**
 * Cyberpunk 3D Network Dataflow Conduit Visualizer.
 *
 * Visualizes the physical transmission medium as a high-tech cylindrical containment conduit
 * receding in 3D perspective toward the destination node (Sony Bravia TV). The outer containment
 * boundary represents the physical Wi-Fi connection limit (PHY link speed). Inside the conduit,
 * the active bit-perfect audio stream is pushed through as a roaring, turbulent, flaming hot plasma
 * core whose cross-section and flaring energy expand with live audio dynamics and transmission payload.
 */
@Composable
fun HioneCyberTunnelVisualizer(
    targetName: String,
    targetAddress: String,
    track: Track,
    player: ExoPlayer?,
    accent: Color,
    accent2: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val wifiManager = remember(context) {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    var linkSpeedMbps by remember { mutableIntStateOf(72) }
    var rssiDbm by remember { mutableIntStateOf(-52) }
    var freqMhz by remember { mutableIntStateOf(2437) }

    // Live Wi-Fi connection telemetry
    LaunchedEffect(Unit) {
        while (true) {
            val info = wifiManager?.connectionInfo
            if (info != null && info.linkSpeed > 0) {
                linkSpeedMbps = info.linkSpeed
                rssiDbm = info.rssi
                freqMhz = info.frequency
            }
            delay(2000L)
        }
    }

    // Live byte throughput calculation
    var measuredPayloadMbps by remember { mutableFloatStateOf(0f) }
    var lastBytes by remember { mutableLongStateOf(HioneCastServer.bytesSent.get()) }
    var lastTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var totalBytesDispatched by remember { mutableLongStateOf(HioneCastServer.bytesSent.get()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(400L)
            val now = System.currentTimeMillis()
            val currentBytes = HioneCastServer.bytesSent.get()
            val dt = ((now - lastTimeMs).coerceAtLeast(1)) / 1000f
            val dBytes = (currentBytes - lastBytes).coerceAtLeast(0L)
            val bps = (dBytes * 8f) / dt
            measuredPayloadMbps = bps / 1_000_000f
            lastBytes = currentBytes
            lastTimeMs = now
            totalBytesDispatched = currentBytes
        }
    }

    // Audio format theoretical payload from active cast tap or track bitrate
    val theoreticalPayloadMbps = remember(track.id, track.bitrateKbps) {
        val tapFmt = HioneCastTap.format
        if (tapFmt != null && tapFmt.sampleRate > 0) {
            (tapFmt.sampleRate.toLong() * tapFmt.bytesPerSample * 8 * tapFmt.channelCount) / 1_000_000f
        } else if (track.bitrateKbps > 0) {
            (track.bitrateKbps.toFloat() / 1000f).coerceAtLeast(1.41f)
        } else {
            1.41f // 44.1/16 stereo
        }
    }
    val activePayloadMbps = if (measuredPayloadMbps > 0.05f) measuredPayloadMbps else theoreticalPayloadMbps
    val safeLinkSpeed = linkSpeedMbps.coerceAtLeast(10).toFloat()
    val bandwidthRatio = (activePayloadMbps / safeLinkSpeed).coerceIn(0.01f, 1.0f)
    val headroomMbps = (safeLinkSpeed - activePayloadMbps).coerceAtLeast(0f)

    // Audio capture hook
    LaunchedEffect(player?.audioSessionId) {
        val sid = player?.audioSessionId ?: 0
        if (sid > 0) AudioCapture.ensure(sid)
    }

    // Continuous 3D animation progression
    val infiniteTransition = rememberInfiniteTransition(label = "cyberTunnel")
    val tunnelPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tunnelPhase"
    )
    val plasmaSpin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "plasmaSpin"
    )

    // Cyber particle sparks
    val particles = remember {
        List(36) { i ->
            CyberSpark(
                angle = (i * (2 * PI / 36)).toFloat() + (i % 3) * 0.2f,
                speed = 0.35f + (i % 5) * 0.15f,
                offset = (i * 0.027f) % 1f,
                size = 2f + (i % 4) * 1.5f
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF03070E))
    ) {
        // 3D Canvas Tunnel
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f - 14.dp.toPx()
            val maxR = hypot(cx, cy) * 0.92f

            val bass = AudioCapture.bass.coerceIn(0f, 1.5f)
            val treble = AudioCapture.treble.coerceIn(0f, 1.5f)
            val liveEnergy = (bass * 0.7f + treble * 0.3f).coerceIn(0f, 1.5f)

            // 1. Draw Longitudinal Conduit Rails (12 cybernetic guide rails)
            val railCount = 12
            for (i in 0 until railCount) {
                val theta = (i * (2 * PI / railCount)).toFloat()
                val rx = cx + cos(theta) * maxR
                val ry = cy + sin(theta) * maxR

                drawLine(
                    color = Color(0x3300E5FF),
                    start = Offset(cx, cy),
                    end = Offset(rx, ry),
                    strokeWidth = 1.2f
                )

                // Rail energy pulse moving inward
                val railPulseZ = ((tunnelPhase * 1.5f + i * 0.08f) % 1.0f)
                val pulseScale = 1.0f - railPulseZ.pow(0.85f)
                val px = cx + cos(theta) * maxR * pulseScale
                val py = cy + sin(theta) * maxR * pulseScale
                drawCircle(
                    color = Color(0xAA0DF2C8),
                    radius = 2.5f * (1f - railPulseZ),
                    center = Offset(px, py)
                )
            }

            // 2. Draw Concentric Conduit Containment Collars (Physical Bandwidth Limit)
            val ringCount = 9
            for (i in 0 until ringCount) {
                val z = ((i.toFloat() / ringCount) + tunnelPhase) % 1.0f
                val ringScale = (1.0f - z.pow(0.7f)).coerceIn(0.05f, 1.0f)
                val ringR = maxR * ringScale
                val ringAlpha = (sin(z * PI).toFloat() * 0.85f).coerceIn(0.1f, 0.9f)

                // Outer polygon collar (8-sided containment chamber)
                val sides = 8
                val collarPath = Path()
                for (s in 0 until sides) {
                    val a = (s * (2 * PI / sides)).toFloat()
                    val vx = cx + cos(a) * ringR
                    val vy = cy + sin(a) * ringR
                    if (s == 0) collarPath.moveTo(vx, vy) else collarPath.lineTo(vx, vy)
                }
                collarPath.close()

                drawPath(
                    path = collarPath,
                    color = Color(0xFF00E5FF).copy(alpha = ringAlpha * 0.45f),
                    style = Stroke(width = 1.8f * (1f - z * 0.5f))
                )

                // Hazard / Warning Accent Markers on containment rings
                if (i % 2 == 0) {
                    drawCircle(
                        color = Color(0xFFFF0055).copy(alpha = ringAlpha * 0.6f),
                        radius = 3.5f * (1f - z * 0.6f),
                        center = Offset(cx + ringR, cy)
                    )
                    drawCircle(
                        color = Color(0xFFFF0055).copy(alpha = ringAlpha * 0.6f),
                        radius = 3.5f * (1f - z * 0.6f),
                        center = Offset(cx - ringR, cy)
                    )
                }
            }

            // 3. Draw The Flaming Hot Plasma Data Core (Payload Stream)
            // The plasma radius fits inside the conduit based on bandwidth ratio and flaring audio energy
            val plasmaBaseRatio = (0.28f + 0.45f * sqrt(bandwidthRatio)).coerceIn(0.25f, 0.80f)
            val plasmaPulse = (1.0f + 0.28f * liveEnergy)

            // Draw multiple perspective slices of the plasma column
            val plasmaSlices = 14
            for (s in 0 until plasmaSlices) {
                val pz = (s.toFloat() / plasmaSlices)
                val sliceScale = (1.0f - pz.pow(0.72f)).coerceIn(0.08f, 1.0f)
                val conduitR = maxR * sliceScale
                val pr = (conduitR * plasmaBaseRatio * plasmaPulse * (1f - pz * 0.25f)).coerceAtLeast(6f)

                val sliceAlpha = (1f - pz * 0.75f).coerceIn(0.2f, 1f)
                val flameOffset = sin(plasmaSpin * 2f + s * 0.8f) * 4f * (1f - pz)

                // Layer A: Outer Plasma Corona / Arc Aura (Electric Cyan -> Magenta)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFF0077).copy(alpha = 0.55f * sliceAlpha),
                            Color(0xFF7928CA).copy(alpha = 0.25f * sliceAlpha),
                            Color.Transparent
                        ),
                        center = Offset(cx + flameOffset, cy),
                        radius = pr * 1.55f
                    ),
                    radius = pr * 1.55f,
                    center = Offset(cx + flameOffset, cy)
                )

                // Layer B: Mid Mantle — Roaring Molten Solar Plasma (Incandescent Gold / Orange Flame)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFD700).copy(alpha = 0.85f * sliceAlpha),
                            Color(0xFFFF5500).copy(alpha = 0.65f * sliceAlpha),
                            Color(0xFFFF0033).copy(alpha = 0.15f * sliceAlpha),
                            Color.Transparent
                        ),
                        center = Offset(cx + flameOffset * 0.5f, cy),
                        radius = pr
                    ),
                    radius = pr,
                    center = Offset(cx + flameOffset * 0.5f, cy)
                )

                // Layer C: Core Blinding White-Hot Filament
                drawCircle(
                    color = Color.White.copy(alpha = 0.95f * sliceAlpha),
                    radius = pr * 0.32f,
                    center = Offset(cx + flameOffset * 0.2f, cy)
                )
            }

            // 4. Electric Arc Discharges (when energy peaks)
            if (liveEnergy > 0.45f) {
                val arcCount = (liveEnergy * 5).toInt().coerceIn(1, 4)
                for (a in 0 until arcCount) {
                    val arcAngle = (plasmaSpin * 3f + a * 1.57f).toFloat()
                    val pInner = Offset(cx + cos(arcAngle) * 30f, cy + sin(arcAngle) * 30f)
                    val pMid = Offset(
                        cx + cos(arcAngle + 0.15f) * (maxR * 0.35f),
                        cy + sin(arcAngle - 0.15f) * (maxR * 0.35f)
                    )
                    val pOuter = Offset(
                        cx + cos(arcAngle) * (maxR * 0.58f),
                        cy + sin(arcAngle) * (maxR * 0.58f)
                    )

                    val arcPath = Path().apply {
                        moveTo(pInner.x, pInner.y)
                        lineTo(pMid.x, pMid.y)
                        lineTo(pOuter.x, pOuter.y)
                    }
                    drawPath(
                        path = arcPath,
                        color = Color(0xFF00FFFF).copy(alpha = 0.8f),
                        style = Stroke(width = 2f)
                    )
                }
            }

            // 5. Plasma Embers / Data Packets streaking down the conduit
            particles.forEach { p ->
                val pz = ((p.offset + tunnelPhase * p.speed) % 1.0f)
                val pScale = (1.0f - pz.pow(0.7f)).coerceIn(0.05f, 1.0f)
                val dist = (maxR * plasmaBaseRatio * 0.85f) * pScale
                val px = cx + cos(p.angle + plasmaSpin) * dist
                val py = cy + sin(p.angle + plasmaSpin) * dist
                val alpha = (sin(pz * PI).toFloat()).coerceIn(0f, 1f)

                drawCircle(
                    color = Color(0xFFFFF07A).copy(alpha = alpha * 0.9f),
                    radius = p.size * (1f - pz * 0.5f),
                    center = Offset(px, py)
                )
            }

            // 6. Destination Gateway Reticle at Center (TV Receiving Node)
            val gateR = 38.dp.toPx()
            drawCircle(
                color = Color(0x3300E5FF),
                radius = gateR * 1.3f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color(0xFF0DF2C8),
                radius = gateR,
                center = Offset(cx, cy),
                style = Stroke(width = 2.2f)
            )
            drawCircle(
                color = Color(0xFF00FF88).copy(alpha = 0.3f),
                radius = gateR * 0.85f,
                center = Offset(cx, cy)
            )
        }

        // Central Destination Emblem (Sony Bravia TV Node)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-14).dp)
                .size(68.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Tv,
                contentDescription = "Destination TV Gateway",
                tint = Color(0xFF00FFA6),
                modifier = Modifier.size(34.dp)
            )
        }

        // Cyber HUD Overlays
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // TOP BAR: System & Medium Telemetry
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CutCornerShape(8.dp))
                    .background(Color(0xCC050E18))
                    .border(1.dp, Color(0x4400E5FF), CutCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00FF88))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "QUANTUM CONDUIT // ACTIVE",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Wi-Fi Medium",
                        tint = Color(0xFF0DF2C8),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "PHY ${linkSpeedMbps} Mbps · ${if (freqMhz > 4000) "5 GHz" else "2.4 GHz"}",
                        color = Color(0xFFD4F8F2),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // MIDDLE CONDUIT METERS: Physical Ceiling vs Active Flow
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left Pill: Target Ingest
                Box(
                    modifier = Modifier
                        .clip(CutCornerShape(6.dp))
                        .background(Color(0x99040D15))
                        .border(1.dp, Color(0x5500FF88), CutCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Column {
                        Text(
                            text = "DESTINATION GATEWAY",
                            color = Color(0xFF00FFA6),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = targetName.uppercase(Locale.US),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (targetAddress.isNotBlank()) {
                            Text(
                                text = targetAddress,
                                color = Color(0x99FFFFFF),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Right Pill: Bandwidth Containment Telemetry
                Box(
                    modifier = Modifier
                        .clip(CutCornerShape(6.dp))
                        .background(Color(0x99040D15))
                        .border(1.dp, Color(0x55FF5500), CutCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "PHYSICAL CONTAINMENT",
                            color = Color(0xFFFF9100),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "CEILING: ${linkSpeedMbps} Mbps",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "FREE: ${headroomMbps.roundToInt()} Mbps (${(100f - bandwidthRatio * 100).roundToInt()}%)",
                            color = Color(0xFF0DF2C8),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // BOTTOM BAR: Live Audio Payload Telemetry & Throughput Ribbon
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CutCornerShape(10.dp))
                    .background(Color(0xDD040C16))
                    .border(1.dp, Color(0x660DF2C8), CutCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "STREAMING PAYLOAD FLOW",
                            color = Color(0xFFFF5500),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.1.sp
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = String.format(Locale.US, "%.2f", activePayloadMbps),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Mbps DIRECT PCM",
                                color = Color(0xFFFFD54F),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "BIT-PERFECT FORMAT",
                            color = Color(0xFF00E5FF),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        val tapFmt = HioneCastTap.format
                        val srKHz = if (tapFmt != null && tapFmt.sampleRate > 0) {
                            tapFmt.sampleRate / 1000f
                        } else 44.1f
                        val bit = if (tapFmt != null && tapFmt.bytesPerSample > 0) {
                            tapFmt.bytesPerSample * 8
                        } else 16
                        Text(
                            text = "${srKHz} kHz · ${bit}-BIT LOSSLESS",
                            color = Color(0xFFD4F8F2),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Plasma Bandwidth Containment Gauge Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF071926))
                ) {
                    // Hot plasma payload fill
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(bandwidthRatio.coerceIn(0.04f, 1.0f))
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFFFF0055),
                                        Color(0xFFFF5500),
                                        Color(0xFFFFD700)
                                    )
                                )
                            )
                    )
                }

                // Bottom Ribbon Stats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "DISPATCHED: ${formatBytes(totalBytesDispatched)}",
                        color = Color(0x99FFFFFF),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "CONDUIT STATUS: 100% NOMINAL",
                        color = Color(0xFF00FFA6),
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private class CyberSpark(
    val angle: Float,
    val speed: Float,
    val offset: Float,
    val size: Float
)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024f * 1024f)
    return if (mb >= 1000f) {
        String.format(Locale.US, "%.2f GB", mb / 1024f)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}
