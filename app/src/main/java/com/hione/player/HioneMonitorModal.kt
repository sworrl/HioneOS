package com.hione.player

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.player.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.launch
import java.util.Locale

private val CyberNeonCyan get() = Mascot.color(0xFF00FFCC)
private val CyberNeonPink get() = Mascot.color(0xFFFF0055)
private val CyberModalDark = Color(0xFF05080C)
private val CyberTerminalBg = Color(0xFF000B06)
private val CyberMutedCyan get() = Mascot.color(0xFF008888)
private val CyberGridLine get() = Mascot.color(0xFF003838)

@Composable
fun HioneMonitorModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val syncState by HioneSyncTransceiver.state.collectAsState()
    val daemon = syncState.daemon
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: TELEMETRY, 1: UPLINK_CFG
    var daemonHostInput by remember { mutableStateOf(HioneSyncTransceiver.activeHost) }
    // Real configured values (HioneIngestConfig) — no hardcoded host names or library paths.
    var souffleHostInput by remember { mutableStateOf(HioneIngestConfig.syncHost(ctx)) }
    var musicDirInput by remember { mutableStateOf(HioneSyncTransceiver.getSdMusicPath(ctx)) }
    var statusFeedback by remember { mutableStateOf("") }

    // This device's real address, or "—" when there is no non-loopback IPv4 yet.
    val devIp = remember(syncState.ipAddress) {
        if (syncState.ipAddress.isNotEmpty() && syncState.ipAddress != "127.0.0.1") syncState.ipAddress else "—"
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xBB000000))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
                .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest),
            contentAlignment = Alignment.Center
        ) {
            // Main Cyberdeck Window Outer Container
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .fillMaxHeight(0.95f)
                    .widthIn(max = 560.dp)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {}
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyberModalDark)
                    .border(2.dp, CyberNeonCyan, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                // ============================================================
                // INGRESS ENGINE MASTER SWITCH (Settings.Global hione_ingest_enabled, default OFF)
                // ============================================================
                var ingestOn by remember { mutableStateOf(HioneIngestGate.isEnabled(ctx)) }
                LaunchedEffect(Unit) { while (true) { ingestOn = HioneIngestGate.isEnabled(ctx); kotlinx.coroutines.delay(1500) } }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (ingestOn) CyberNeonCyan.copy(alpha = 0.10f) else Color(0x33FF5C5C))
                        .border(1.dp, if (ingestOn) CyberNeonCyan else Color(0xFFFF5C5C), RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (ingestOn) "NETWORK SYNC: ON" else "NETWORK SYNC: OFF (SD CARD ONLY)",
                            color = if (ingestOn) CyberNeonCyan else Color(0xFFFF8A80),
                            fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace
                        )
                        Text(
                            if (ingestOn) "m500d discovery and rsync running" else "No network ingest runs. Library updates come from the SD card scan (Force Scan / periodic).",
                            color = Color(0xFFB0BEC5), fontSize = 9.5.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = ingestOn,
                        onCheckedChange = { on -> HioneIngestGate.setEnabled(ctx, on); ingestOn = on },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyberNeonCyan, checkedTrackColor = CyberNeonCyan.copy(alpha = 0.35f))
                    )
                }

                // ============================================================
                // HEADER BAR: CYBERDECK // HIONE_RELAY_NODE // V.2.0     [ X ]
                // ============================================================
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SYNC MONITOR",
                        color = CyberNeonPink,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp
                    )

                    // Top-right [ X ] Close Box
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(CyberNeonPink.copy(alpha = 0.12f))
                            .border(1.dp, CyberNeonPink, RoundedCornerShape(3.dp))
                            .clickable { onDismissRequest() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "X",
                            color = CyberNeonPink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // ============================================================
                // TAB BAR: [ TELEMETRY ]  [ UPLINK_CFG ]
                // ============================================================
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val tabs = listOf("TELEMETRY", "UPLINK_CFG")
                    tabs.forEachIndexed { index, title ->
                        val isSelected = selectedTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isSelected) CyberNeonCyan.copy(alpha = 0.18f) else CyberNeonCyan.copy(alpha = 0.04f))
                                .border(
                                    1.dp,
                                    if (isSelected) CyberNeonCyan else CyberMutedCyan,
                                    RoundedCornerShape(3.dp)
                                )
                                .clickable { selectedTab = index },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                color = if (isSelected) CyberNeonCyan else CyberMutedCyan,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }

                // ============================================================
                // TAB CONTENT
                // ============================================================
                if (selectedTab == 0) {
                    // TELEMETRY VIEW
                    TelemetryView(
                        daemon = daemon,
                        syncState = syncState,
                        devIp = devIp,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    // UPLINK_CFG VIEW
                    UplinkConfigView(
                        daemonHost = daemonHostInput,
                        onDaemonHostChange = { daemonHostInput = it },
                        souffleHost = souffleHostInput,
                        onSouffleHostChange = { souffleHostInput = it },
                        musicDir = musicDirInput,
                        onMusicDirChange = { musicDirInput = it },
                        onDiscoverBeacon = {
                            HioneSyncTransceiver.wake()
                            statusFeedback = "Beacon sent on UDP 8788"
                        },
                        onSpeedTest = {
                            statusFeedback = "Running speed test…"
                            HioneSyncTransceiver.runSpeedTest { res ->
                                statusFeedback = "Measured: ${String.format(Locale.US, "%.1f", res.speedMBs)} MB/s (${res.latencyMs}ms)"
                            }
                        },
                        workersLabel = if (daemon.online && (daemon.fetchActive > 0 || daemon.workers.isNotEmpty())) "${maxOf(daemon.fetchActive, daemon.workers.size)} active" else "—",
                        cacheLabel = if (daemon.online && (daemon.cacheAlbums > 0 || daemon.cacheBytes > 0)) "${daemon.cacheAlbums} albums · ${formatBytes(daemon.cacheBytes)}" else "—",
                        modifier = Modifier.weight(1f)
                    )
                }

                if (statusFeedback.isNotEmpty()) {
                    Text(
                        text = statusFeedback,
                        color = CyberNeonCyan,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // ============================================================
                // BOTTOM BUTTONS: [ OVERRIDE: INIT SYNC ]  [ ABORT: HALT SYNC ]
                // ============================================================
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Override Init Sync (Cyan)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(CyberNeonCyan.copy(alpha = 0.12f))
                            .border(1.2.dp, CyberNeonCyan, RoundedCornerShape(4.dp))
                            .clickable {
                                coroutineScope.launch {
                                    HioneSyncTransceiver.triggerDaemonSync(true) { ok, msg ->
                                        statusFeedback = if (ok) "Sync started" else "Sync failed: $msg"
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[ START SYNC ]",
                            color = CyberNeonCyan,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Abort Halt Sync (Pink/Red)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(CyberNeonPink.copy(alpha = 0.12f))
                            .border(1.2.dp, CyberNeonPink, RoundedCornerShape(4.dp))
                            .clickable {
                                coroutineScope.launch {
                                    HioneSyncTransceiver.triggerDaemonSync(false) { ok, msg ->
                                        statusFeedback = if (ok) "Sync stopped" else "Stop failed: $msg"
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[ STOP SYNC ]",
                            color = CyberNeonPink,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryView(
    daemon: HioneSyncTransceiver.DaemonStatus,
    syncState: HioneSyncTransceiver.SyncState,
    devIp: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ============================================================
        // 1. TOP TELEMETRY GRID (4 Rows x 2 Columns)
        // ============================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, CyberNeonCyan, RoundedCornerShape(4.dp))
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Row 1: NET_LINK & DEVICE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Link state is the daemon probe result; the transport is what ConnectivityManager reports.
                    GridCell(
                        text = "NET_LINK: ${if (daemon.online) "DAEMON_ONLINE" else "DAEMON_OFFLINE"} · ${syncState.transport.badge}",
                        modifier = Modifier.weight(1f)
                    )
                    GridCell(
                        text = "DEVICE: M500 ($devIp)",
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 2: BEACON & THROUGHPUT
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GridCell(
                        text = "BEACON: ${syncState.beaconAck.ifEmpty { "UDP 8788 (ack not tracked)" }}",
                        modifier = Modifier.weight(1f)
                    )
                    val tpText = if (daemon.isTransferring || syncState.transferRateMBs > 0f) {
                        String.format(Locale.US, "THROUGHPUT: %.1f MB/s", syncState.transferRateMBs)
                    } else "THROUGHPUT: idle"
                    GridCell(
                        text = tpText,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 3: STAGE & ETA — both straight from the daemon; "—" when it hasn't reported.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val workersStr = if (daemon.fetchActive > 0) "${daemon.fetchActive} WORKERS" else "workers —"
                    val stageStr = when {
                        daemon.stage.isNotEmpty() -> daemon.stage.uppercase()
                        !daemon.online -> "OFFLINE"
                        daemon.isTransferring -> "TRANSFERRING"
                        else -> "IDLE"
                    }
                    GridCell(
                        text = "STAGE: $stageStr ($workersStr)",
                        modifier = Modifier.weight(1f)
                    )
                    // ETA = remaining planned bytes / measured rate, only when both are real.
                    val remainingBytes = daemon.bytesTotalPlan - (daemon.filesDone.toLong().takeIf { daemon.filesTotal > 0 }?.let { daemon.bytesTotalPlan * it / daemon.filesTotal } ?: 0L)
                    val etaText = if (daemon.isTransferring && daemon.transferRateBps > 0.0 && daemon.bytesTotalPlan > 0 && daemon.filesTotal > 0) {
                        val secs = (remainingBytes / daemon.transferRateBps).toLong().coerceAtLeast(0)
                        "ETA: ${secs / 60}m ${secs % 60}s"
                    } else "ETA: —"
                    GridCell(
                        text = etaText,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 4: SD_TOTAL & SD_FREE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val sdTotalStr = if (daemon.sdCardTotalBytes > 0) formatBytes(daemon.sdCardTotalBytes) else "—"
                    val sdFreeStr = if (daemon.sdCardFreeBytes > 0) formatBytes(daemon.sdCardFreeBytes) else "—"
                    GridCell(
                        text = "SD_TOTAL: $sdTotalStr",
                        modifier = Modifier.weight(1f)
                    )
                    GridCell(
                        text = "SD_FREE: $sdFreeStr",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ============================================================
        // 2. TARGET BLOCK (Artist, Album, Album Progress)
        // ============================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, CyberNeonCyan, RoundedCornerShape(4.dp))
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Target Artist Row
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .border(1.dp, CyberNeonCyan)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "TARGET_ARTIST: ",
                            color = CyberNeonCyan,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = daemon.currentArtist.ifEmpty { "—" },
                            color = CyberNeonPink,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Target Album Row
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .border(1.dp, CyberNeonCyan)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "TARGET_ALBUM:  ",
                            color = CyberNeonCyan,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = daemon.currentAlbum.ifEmpty { "—" },
                            color = CyberNeonPink,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Album Count Progress Row
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .border(1.dp, CyberNeonCyan),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (daemon.albumsTotal > 0) "${daemon.albumsDone} / ${daemon.albumsTotal} ALBUMS" else "ALBUMS: — (daemon has not reported a plan)",
                        color = CyberNeonCyan,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // ============================================================
        // 3. THROUGHPUT WAVEFORM GRAPH
        // ============================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, CyberNeonCyan, RoundedCornerShape(4.dp))
                .padding(4.dp)
        ) {
            ThroughputWaveformCanvas(
                history = syncState.throughputHistory,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ============================================================
        // 4. TERMINAL LOG WINDOW
        // ============================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(CyberTerminalBg)
                .border(1.dp, CyberNeonCyan, RoundedCornerShape(4.dp))
                .padding(6.dp)
        ) {
            val listState = rememberLazyListState()
            // Real event log only; an empty log shows one honest line instead of canned history.
            val logs = if (syncState.eventLogs.isNotEmpty()) syncState.eventLogs else listOf("[--:--:--] no events yet, waiting for daemon or beacon traffic")

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs) { logLine ->
                    Text(
                        text = logLine,
                        color = when {
                            logLine.contains("⚡") -> Color(0xFFFFD600)
                            logLine.contains("⚠️") || logLine.contains("FAIL") -> CyberNeonPink
                            logLine.contains("📡") -> Color(0xFF80D8FF)
                            else -> CyberNeonCyan
                        },
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun GridCell(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(22.dp)
            .border(1.dp, CyberNeonCyan)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            color = CyberNeonCyan,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThroughputWaveformCanvas(
    history: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        // Draw 3 horizontal dashed grid lines
        val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
        val yLines = listOf(height * 0.25f, height * 0.50f, height * 0.75f)
        yLines.forEach { y ->
            drawLine(
                color = CyberGridLine,
                start = Offset(24f, y),
                end = Offset(width, y),
                strokeWidth = 1f,
                pathEffect = dashedEffect
            )
        }

        // Draw Y-axis marker lines on left
        drawLine(color = CyberGridLine, start = Offset(20f, 4f), end = Offset(20f, height - 4f), strokeWidth = 1f)

        // Draw live waveform line — real throughput samples only; nothing drawn until there are some.
        if (history.isEmpty()) return@Canvas
        val samplePoints = history
        val maxVal = (samplePoints.maxOrNull() ?: 1f).coerceAtLeast(1f)

        val path = Path()
        val stepX = (width - 24f) / (samplePoints.size - 1).coerceAtLeast(1)

        samplePoints.forEachIndexed { i, sample ->
            val x = 24f + i * stepX
            val normY = (sample / maxVal).coerceIn(0f, 1f)
            val y = height - 6f - (normY * (height - 16f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = CyberNeonPink,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun UplinkConfigView(
    daemonHost: String,
    onDaemonHostChange: (String) -> Unit,
    souffleHost: String,
    onSouffleHostChange: (String) -> Unit,
    musicDir: String,
    onMusicDirChange: (String) -> Unit,
    onDiscoverBeacon: () -> Unit,
    onSpeedTest: () -> Unit,
    /** Live daemon figures; "—" when the daemon hasn't reported them. */
    workersLabel: String,
    cacheLabel: String,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            ConfigBox(label = "DAEMON_HOST (HTTP 8787)", value = daemonHost, onValueChange = onDaemonHostChange)
        }
        item {
            ConfigBox(label = "BEACON_PORT (UDP)", value = "8788", onValueChange = {})
        }
        item {
            ConfigBox(label = "SYNC_HOST (configured)", value = souffleHost.ifEmpty { "(not configured)" }, onValueChange = onSouffleHostChange)
        }
        item {
            ConfigBox(label = "SD_MUSIC_DIR (resolved)", value = musicDir, onValueChange = onMusicDirChange)
        }
        item {
            ConfigBox(label = "SYNC_WORKERS (daemon)", value = workersLabel, onValueChange = {})
        }
        item {
            ConfigBox(label = "CACHE (daemon)", value = cacheLabel, onValueChange = {})
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CyberNeonCyan.copy(alpha = 0.12f))
                        .border(1.dp, CyberNeonCyan, RoundedCornerShape(4.dp))
                        .clickable { onDiscoverBeacon() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("BEACON DISCOVERY", color = CyberNeonCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFFFD600).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFFFFD600), RoundedCornerShape(4.dp))
                        .clickable { onSpeedTest() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("RUN SPEED TEST", color = Color(0xFFFFD600), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun ConfigBox(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, CyberNeonCyan, RoundedCornerShape(4.dp))
            .padding(6.dp)
    ) {
        Text(
            text = label,
            color = CyberNeonCyan,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb)
    else String.format(Locale.US, "%.0f MB", bytes.toDouble() / (1024.0 * 1024.0))
}

@Composable
fun CyberTactileButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Mascot.color(0xFF00FFCC),
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isDanger: Boolean = false
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.15f))
            .border(
                1.dp,
                if (isDanger) Mascot.color(0xFFFF0055) else accentColor.copy(alpha = 0.7f),
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (isDanger) Mascot.color(0xFFFF0055) else accentColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                color = if (isDanger) Mascot.color(0xFFFF0055) else Color.White,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont,
                maxLines = 1
            )
        }
    }
}

