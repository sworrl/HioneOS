package com.hione.player

import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.player.CyberGlassBorder
import com.hione.player.HioneCyan
import com.hione.player.HioneNeonPink
import com.hione.player.HioneTextPrimary
import com.hione.player.HioneTextSecondary
import com.hione.player.ui.swipeUpFromBottomToDismiss

/**
 * Dedicated Hione Brain & Bone Orchestration Modal.
 * Exposes real-time telemetry, thread dispatchers, heartbeats, and ANR immunity status.
 */
@Composable
fun HioneBrainModal(
    onDismissRequest: () -> Unit
) {
    val telemetry by HioneBrain.telemetry.collectAsState()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Outer 3D Beveled Modal Shell
        Box(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest)
                .clip(CutCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(CutCornerShape(15.dp))
                    .background(Mascot.color(0xFF0C0D10))
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF00FF7F).copy(alpha = 0.9f),
                                    CyberGlassBorder.copy(alpha = 0.35f),
                                    HioneCyan.copy(alpha = 0.6f)
                                )
                            )
                        ),
                        CutCornerShape(15.dp)
                    )
            ) {
                // Background Artwork
                Image(
                    painter = mascotPainter(id = R.drawable.hione_cyber_stage),
                    contentDescription = "${Mascot.name} Brain Background",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Frosted Dark Overlay
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Mascot.color(0xF20C0D10),
                                    Mascot.color(0xD80C0D10),
                                    Mascot.color(0xFA0C0D10)
                                )
                            )
                        )
                )

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                ) {
                    // Header Bar with 3D Embossed Button & Task-Specific Hione Badge
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CutCornerShape(8.dp))
                                    .background(Color(0x3300FF7F))
                                    .border(1.dp, Color(0xFF00FF7F), CutCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = mascotPainter(R.drawable.hione_audiophile_art),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "WATCHDOG",
                                    color = HioneCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    "Checks each subsystem and watches for ANRs",
                                    color = HioneTextSecondary,
                                    fontSize = 8.sp
                                )
                            }
                        }

                        com.hione.player.network.Cyber3dIconButton(
                            onClick = onDismissRequest,
                            icon = Icons.Default.Close,
                            contentDescription = "Close",
                            accentColor = HioneNeonPink
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // 3D Embossed Watchdog Status Card
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(CutCornerShape(10.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.12f), Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                )
                            )
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(1.dp)
                                .clip(CutCornerShape(9.dp))
                                .background(Mascot.color(0xEE0A1E26))
                                .border(1.dp, HioneCyan.copy(alpha = 0.5f), CutCornerShape(9.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Real counts, and an explicit "no probe yet" state. The old
                                        // label read "100% NOMINAL" off allBonesHealthy, which
                                        // defaults to true before the watchdog has probed anything,
                                        // so an empty registry rendered as a perfect score.
                                        val probed = telemetry.bones.values.filter { it.probed }
                                        val probedBones = probed.size
                                        val faultedBones = probed.count {
                                            it.state == HioneBrain.BoneState.ERROR || it.state == HioneBrain.BoneState.STALLED
                                        }
                                        val watchdogOk = probedBones > 0 && faultedBones == 0
                                        Box(
                                            Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(if (watchdogOk) Color(0xFF00FF7F) else Color(0xFFFF5252))
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            when {
                                                probedBones == 0 -> "WATCHDOG: NO PROBE YET"
                                                faultedBones == 0 -> "WATCHDOG: $probedBones/$probedBones SUBSYSTEMS OK"
                                                else -> "WATCHDOG: $faultedBones OF $probedBones SUBSYSTEMS FAULTED"
                                            },
                                            color = if (watchdogOk) Color(0xFF00FF7F) else Color(0xFFFF5252),
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AudiowideFont
                                        )
                                    }
                                    Text(
                                        "LIVE 1000ms TICK",
                                        color = HioneCyan,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    // Describe the observed condition only. The idle branch used to
                                    // claim "100% CPU clock bandwidth dynamically allocated", a
                                    // figure nothing here measures; the load branch claimed a 60 FPS
                                    // result that is likewise never sampled.
                                    if (telemetry.isUiUnderLoad) "TOUCH ACTIVE: background I/O is yielding to the UI thread."
                                    else "UI IDLE: no recent touch, so background I/O runs normally.",
                                    color = if (telemetry.isUiUnderLoad) HioneNeonPink else Color.White,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Subsystem Bones List (Live Streaming)
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HioneBrain.BoneType.values().forEach { bone ->
                            val health = telemetry.bones[bone] ?: HioneBrain.BoneHealth(type = bone)
                            item {
                                BoneCard(health = health)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Manual Override Fallback Button
                    Button(
                        onClick = {
                            // Manual diagnostic probe override for power users
                            // Manual probe: re-run the REAL checks, don't stamp "ACTIVE" with canned text.
                            HioneBrain.heartbeat(HioneBrain.BoneType.UI_RENDERER, HioneBrain.BoneState.ACTIVE, "Manual probe requested", 1)
                            val audit = HioneBrain.run { com.hione.player.CirrusLogicManager.getLiveHardwareAudit() }
                            HioneBrain.heartbeat(
                                HioneBrain.BoneType.HARDWARE_IO,
                                if (audit.isSysfsReadable) HioneBrain.BoneState.ACTIVE else HioneBrain.BoneState.ERROR,
                                if (audit.isSysfsReadable) "DAC sysfs readable · filter ${audit.kernelFilterText} · gain ${audit.kernelGainText}" else "DAC sysfs not readable",
                                if (audit.isSysfsReadable) 1 else 0
                            )
                            val playing = runCatching { com.hione.player.PlayerHolder.player?.isPlaying == true }.getOrDefault(false)
                            HioneBrain.heartbeat(HioneBrain.BoneType.AUDIO_DSP, if (playing) HioneBrain.BoneState.ACTIVE else HioneBrain.BoneState.IDLE, if (playing) "Player is playing" else "Player idle", if (playing) 1 else 0)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0xDD0A222E)),
                        border = BorderStroke(1.dp, HioneCyan.copy(alpha = 0.8f)),
                        shape = CutCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = HioneCyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("RUN CHECKS NOW", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoneCard(
    health: HioneBrain.BoneHealth
) {
    val (title, roleDesc, _) = when (health.type) {
        HioneBrain.BoneType.AUDIO_DSP -> Triple("AUDIO_DSP", "Player state (ExoPlayer)", "🎵")
        HioneBrain.BoneType.LIBRARY_SCANNER -> Triple("LIBRARY_SCANNER", "Library scanner (ScanProgress)", "📚")
        HioneBrain.BoneType.NETWORK_INGRESS -> Triple("NETWORK_INGRESS", "Network link (HioneNetworkService)", "🌐")
        HioneBrain.BoneType.HARDWARE_IO -> Triple("HARDWARE_IO", "CS43198 DAC sysfs (CirrusLogicManager)", "⚡")
        HioneBrain.BoneType.UI_RENDERER -> Triple("UI_RENDERER", "Touch activity on the Compose UI", "🎨")
    }

    val elapsedSec = ((SystemClock.elapsedRealtime() - health.lastHeartbeatMs) / 1000).coerceAtLeast(0)
    val isOk = health.state != HioneBrain.BoneState.STALLED && health.state != HioneBrain.BoneState.ERROR

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(10.dp))
            .background(Mascot.color(0xDD0A1E26))
            .border(1.dp, if (isOk) CyberGlassBorder else Color(0xFFFF5252), CutCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }

            Box(
                Modifier
                    .clip(CutCornerShape(4.dp))
                    .background(if (isOk) Color(0x3300E676) else Color(0x33FF5252))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    health.state.name,
                    color = if (isOk) Color(0xFF00E676) else Color(0xFFFF5252),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(roleDesc, color = HioneTextSecondary, fontSize = 8.5.sp)

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Active Tasks: ${health.activeTasks}", color = HioneCyan, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold)
            // A bone that has never heart-beaten has no elapsed time to report — its
            // lastHeartbeatMs is just when the registry row was created, not a heartbeat.
            Text(
                if (health.probed) "Heartbeat: ${elapsedSec}s ago (${health.lastMessage})"
                else "Heartbeat: never (${health.lastMessage})",
                color = Color.Gray,
                fontSize = 8.sp
            )
        }
    }
}
