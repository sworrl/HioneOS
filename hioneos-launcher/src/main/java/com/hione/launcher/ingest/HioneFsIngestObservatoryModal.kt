package com.hione.launcher.ingest

import com.hione.launcher.Mascot
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.*
import com.hione.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.launch

private val HioneMuted get() = Mascot.color(0xFF9EC4C7)

/**
 * Oki Hione File System & Audio Ingestion Observatory Modal.
 * High-legibility scaled dashboard for compact Hi-DPI DAP screens.
 * Displays storage volumes, codec taxonomy, live MediaScanner rescan,
 * rsync ingestion server telemetry, and real-time indexing logs.
 */
@Composable
fun HioneFsIngestObservatoryModal(
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { HioneIngestEngine.init(ctx) }

    // Watch the host relay for as long as this sheet is open, and no longer. The relay publishes
    // a full picture every second; polling it only while someone is looking keeps that off the
    // battery. DisposableEffect rather than LaunchedEffect so the stop actually runs on dismiss.
    DisposableEffect(Unit) {
        HioneIngestEngine.startRelayWatch(ctx)
        onDispose { HioneIngestEngine.stopRelayWatch() }
    }

    val ingestState by HioneIngestEngine.state.collectAsState()

    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Mascot.color(0xDD030A0E))
            .clickable { onClose() }
            .swipeUpFromBottomToDismiss { onClose() }
            .padding(top = 18.dp, bottom = 6.dp, start = 8.dp, end = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0xF207171C),
                            Mascot.color(0xFA030C10)
                        )
                    )
                )
                .border(
                    1.4.dp,
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF00FF88).copy(alpha = 0.85f),
                            CyberGlassBorder.copy(alpha = 0.45f),
                            HioneCyan.copy(alpha = 0.8f)
                        )
                    ),
                    RoundedCornerShape(18.dp)
                )
                .clickable(enabled = false) {}
                .padding(14.dp)
        ) {
            Column(Modifier.fillMaxSize()) {

                // =========================================================================
                // 1. MODAL HEADER: Title, Subtitle, Close Button
                // =========================================================================
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x3300FF88))
                                .border(1.2.dp, Color(0xFF00FF88), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💽", fontSize = 22.sp)
                        }

                        Spacer(Modifier.width(12.dp))

                        Column {
                            Text(
                                "STORAGE AND SYNC",
                                color = Color.White,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont,
                                letterSpacing = 0.4.sp
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                "Storage, formats and MediaScanner",
                                color = Color(0xFF00FF88),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x28FFFFFF))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // =========================================================================
                // 2. SCROLLABLE TELEMETRY CARDS (High-Legibility Scaled Typography)
                // =========================================================================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ── Card 1: Ingest Status & Live Rescan Actions ─────────────────────────
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Mascot.color(0xFF071B20))
                                .border(1.2.dp, Color(0xFF00FF88).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(
                                        Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(if (ingestState.isScanning) Color(0xFFFFD54F) else Color(0xFF00FF88))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        // "INDEXED & BIT-PERFECT" was shown with zero tracks, with
                                        // lastScanTime "Never", and for an all-lossy library — the
                                        // bit-perfect claim was never checked against anything.
                                        when {
                                            ingestState.isScanning -> "ACTIVE MEDIA SCAN"
                                            !ingestState.engineEnabled -> "NETWORK SYNC OFF · SD CARD ONLY"
                                            ingestState.totalTracks == 0 -> "NO TRACKS INDEXED"
                                            else -> "${ingestState.totalTracks} INDEXED · ${ingestState.hiResPercent}% LOSSLESS"
                                        },
                                        color = when {
                                            ingestState.isScanning -> Color(0xFFFFD54F)
                                            !ingestState.engineEnabled -> HioneMuted
                                            ingestState.totalTracks == 0 -> HioneMuted
                                            else -> Color(0xFF00FF88)
                                        },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont,
                                        letterSpacing = 0.3.sp
                                    )
                                }

                                Text(
                                    "${ingestState.totalTracks} Tracks",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }

                            Spacer(Modifier.height(6.dp))
                            Text(
                                ingestState.statusMessage,
                                color = HioneMuted,
                                fontSize = 12.5.sp,
                                lineHeight = 16.sp
                            )

                            // ---- Host relay, verbose ----------------------------------------
                            // Everything here is read from m500d. Where it has not told us
                            // something we show a dash rather than a zero, because during a
                            // working transfer a confident 0 is worse than an honest blank.
                            ingestState.relay?.let { r ->
                                Spacer(Modifier.height(10.dp))
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0x14000000))
                                        .padding(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when {
                                                        !r.reachable -> Color(0xFF6B7A80)
                                                        r.running -> Color(0xFF00FF88)
                                                        else -> Color(0xFF7FE3FF)
                                                    }
                                                )
                                        )
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            when {
                                                !r.reachable -> "RELAY UNREACHABLE"
                                                r.running -> "RELAY · ${r.stage.uppercase().ifBlank { "RUNNING" }}"
                                                else -> "RELAY IDLE"
                                            },
                                            color = HioneCyan,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AudiowideFont
                                        )
                                    }

                                    if (!r.reachable) {
                                        Spacer(Modifier.height(5.dp))
                                        Text(r.error, color = Color(0xFFFF8A80), fontSize = 11.sp, lineHeight = 14.sp)
                                    } else {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            if (r.deviceOnline) "relay sees this device via ${r.deviceVia}"
                                            else "relay cannot see this device over adb",
                                            color = if (r.deviceOnline) HioneMuted else Color(0xFFFFB020),
                                            fontSize = 10.5.sp
                                        )

                                        if (r.running) {
                                            if (r.currentArtist.isNotBlank() || r.currentAlbum.isNotBlank()) {
                                                Spacer(Modifier.height(5.dp))
                                                Text(
                                                    listOf(r.currentArtist, r.currentAlbum)
                                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                                    color = Color.White, fontSize = 12.sp,
                                                    maxLines = 1, softWrap = false
                                                )
                                            }
                                            if (r.currentFile.isNotBlank()) {
                                                Text(
                                                    r.currentFile, color = HioneMuted, fontSize = 10.sp,
                                                    maxLines = 1, softWrap = false
                                                )
                                            }

                                            Spacer(Modifier.height(7.dp))
                                            LinearProgressIndicator(
                                                progress = { r.progress },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = Color(0xFF00FF88),
                                                trackColor = Color(0x3300FF88)
                                            )

                                            Spacer(Modifier.height(6.dp))
                                            val rate = r.measuredRateBps
                                                ?.let { HioneIngestEngine.formatBytes(it) + "/s" } ?: "—"
                                            val eta = r.etaSeconds?.let { sec ->
                                                val m = sec / 60; val h = m / 60
                                                if (h > 0) "${h}h ${m % 60}m" else if (m > 0) "${m}m" else "${sec}s"
                                            } ?: "—"
                                            Text(
                                                "album ${r.albumIndex}/${r.albumTotal} · " +
                                                    "files ${r.filesDone}/${r.filesTotal} · " +
                                                    "${HioneIngestEngine.formatBytes(r.bytesSent)} of " +
                                                    HioneIngestEngine.formatBytes(r.bytesTotal),
                                                color = HioneMuted, fontSize = 10.5.sp
                                            )
                                            Text(
                                                "$rate · eta $eta · fetch ${r.fetchActive} push ${r.pushActive} · " +
                                                    "cached ${HioneIngestEngine.formatBytes(r.cacheBytes)}",
                                                color = HioneMuted, fontSize = 10.5.sp
                                            )

                                            r.workers.take(3).forEach { w ->
                                                Text(
                                                    "· $w", color = Color(0xFF7FE3FF), fontSize = 10.sp,
                                                    maxLines = 1, softWrap = false
                                                )
                                            }
                                        }

                                        if (r.lastEvent.isNotBlank()) {
                                            Spacer(Modifier.height(5.dp))
                                            Text(r.lastEvent, color = HioneMuted, fontSize = 10.5.sp, lineHeight = 13.sp)
                                        }
                                        if (r.albumsDone > 0 || r.albumsFailed > 0) {
                                            Text(
                                                "albums done ${r.albumsDone}" +
                                                    if (r.albumsFailed > 0) " · failed ${r.albumsFailed}" else "",
                                                color = if (r.albumsFailed > 0) Color(0xFFFFB020) else HioneMuted,
                                                fontSize = 10.5.sp
                                            )
                                        }
                                        r.failures.take(3).forEach { f ->
                                            Text("! $f", color = Color(0xFFFF8A80), fontSize = 10.sp,
                                                maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }

                            if (ingestState.isScanning) {
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { ingestState.scanProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFF00FF88),
                                    trackColor = Color(0x3300FF88)
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            // Ingest engine master switch — mirrors the INGRESS ENGINE shade tile.
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "NETWORK SYNC (RSYNC)",
                                        color = if (ingestState.engineEnabled) Color(0xFF00FF88) else HioneMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                    Text(
                                        if (ingestState.engineEnabled) "On · resumes on its own when the server is reachable"
                                        else "Off · only SD card scans update the library",
                                        color = HioneMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = ingestState.engineEnabled,
                                    onCheckedChange = { HioneIngestEngine.setEngineEnabled(ctx, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Mascot.color(0xFF031410),
                                        checkedTrackColor = Color(0xFF00FF88),
                                        uncheckedThumbColor = HioneMuted,
                                        uncheckedTrackColor = Mascot.color(0xFF0B2228)
                                    )
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            // Action Buttons (Scaled for Easy Tapping)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { HioneIngestEngine.triggerForceScan(ctx) },
                                    enabled = !ingestState.isScanning,
                                    colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0xFFFFC56B)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Mascot.color(0xFF030D10), modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("FORCE SCAN", color = Mascot.color(0xFF030D10), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Button(
                                    onClick = { HioneIngestEngine.triggerRsyncSync(ctx) },
                                    enabled = !ingestState.isScanning && ingestState.engineEnabled,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Rsync sync", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // ── Card 2: Storage Volumes & File System Hierarchy ────────────────────
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Mascot.color(0xFF07141A))
                                .border(1.2.dp, HioneCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                "STORAGE",
                                color = HioneCyan,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont,
                                letterSpacing = 0.3.sp
                            )
                            Spacer(Modifier.height(12.dp))

                            // 1. Internal Flash Storage
                            val intTotal = ingestState.internalTotalBytes
                            val intUsed = ingestState.internalUsedBytes
                            val intPct = if (intTotal > 0) ((intUsed.toFloat() / intTotal) * 100).toInt() else 0

                            VolumeProgressRow(
                                title = "Internal storage",
                                path = "/storage/emulated/0",
                                usedStr = HioneIngestEngine.formatBytes(intUsed),
                                totalStr = HioneIngestEngine.formatBytes(intTotal),
                                pct = intPct,
                                barColor = HioneCyan
                            )

                            Spacer(Modifier.height(14.dp))

                            // 2. MicroSD Removable Card
                            if (ingestState.isSdCardMounted) {
                                val sdTotal = ingestState.sdCardTotalBytes
                                val sdUsed = ingestState.sdCardUsedBytes
                                val sdPct = if (sdTotal > 0) ((sdUsed.toFloat() / sdTotal) * 100).toInt() else 0

                                VolumeProgressRow(
                                    title = "MicroSD card",
                                    path = ingestState.sdCardPath,
                                    usedStr = HioneIngestEngine.formatBytes(sdUsed),
                                    totalStr = HioneIngestEngine.formatBytes(sdTotal),
                                    pct = sdPct,
                                    barColor = Mascot.color(0xFF6FB0FF)
                                )
                            } else {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Mascot.color(0xFF0E1A20))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.SdCard, contentDescription = null, tint = HioneMuted, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("MicroSD slot", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(2.dp))
                                        Text("No MicroSD card in", color = HioneMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // ── Card 3: Ingested Audio Codec & Taxonomy Breakdown ──────────────────
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Mascot.color(0xFF07141A))
                                .border(1.2.dp, Mascot.color(0xFFB9C2D0).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "AUDIO FORMATS",
                                    color = Mascot.color(0xFFB9C2D0),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.3.sp
                                )
                                Text(
                                    "${ingestState.hiResPercent}% lossless",
                                    color = Color(0xFF00FF88),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            // 2x2 Grid of Codec Counts (Scaled Up)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CodecMetricPill(
                                    modifier = Modifier.weight(1f),
                                    badge = "DSD",
                                    count = ingestState.dsdCount,
                                    desc = "Direct Stream 1-bit",
                                    color = Color(0xFFFFD54F)
                                )
                                CodecMetricPill(
                                    modifier = Modifier.weight(1f),
                                    badge = "FLAC",
                                    count = ingestState.flacCount,
                                    desc = "Free Lossless Audio Codec",
                                    color = Mascot.color(0xFFFFC56B)
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CodecMetricPill(
                                    modifier = Modifier.weight(1f),
                                    badge = "WAV / ALAC",
                                    count = ingestState.wavCount + ingestState.alacCount,
                                    desc = "Lossless PCM",
                                    color = Mascot.color(0xFFB9C2D0)
                                )
                                CodecMetricPill(
                                    modifier = Modifier.weight(1f),
                                    badge = "MP3 / AAC",
                                    count = ingestState.mp3Count + ingestState.aacCount,
                                    desc = "Lossy",
                                    color = Mascot.color(0xFFFF80AB)
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Total Artists: ${ingestState.totalArtists}", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                Text("Total Albums: ${ingestState.totalAlbums}", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // ── Card 4: Ingestion Sync Server & Config ──────────────────────────────
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Mascot.color(0xFF07141A))
                                .border(1.2.dp, Color(0xFFFFD54F).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Text(
                                "SYNC SERVER",
                                color = Color(0xFFFFD54F),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont,
                                letterSpacing = 0.3.sp
                            )
                            Spacer(Modifier.height(10.dp))

                            // No invented host. HioneIngestConfig's contract is that a blank host means
                            // "not configured / auto-discovery" — the old fallback printed a personal
                            // hostname plus "(Auto-Active)", asserting a link that cannot exist
                            // (probeAndAutoResume returns early on a blank host).
                            val configuredHost = HioneIngestConfig.syncHost(ctx)
                            val rsyncPort = HioneIngestConfig.rsyncPort(ctx)

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Sync server", color = HioneMuted, fontSize = 12.5.sp)
                                Text(
                                    if (configuredHost.isBlank()) "Not set · finds it on the network"
                                    else "$configuredHost:$rsyncPort",
                                    color = if (configuredHost.isBlank()) HioneMuted else Color.White,
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Protocol", color = HioneMuted, fontSize = 12.5.sp)
                                // The version was the literal "rsync v3.2.7 (uid=0 direct)" — nothing
                                // ever ran `rsync --version`, and rsync may not exist on the device.
                                // The engine now reports what it actually found in statusMessage.
                                Text(
                                    "rsync (version not checked)",
                                    color = HioneMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Last indexed", color = HioneMuted, fontSize = 12.5.sp)
                                Text(ingestState.lastScanTime, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // ── Card 5: Real-Time Ingestion Console Log ─────────────────────────────
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Mascot.color(0xFF03090D))
                                .border(1.2.dp, Color(0x4400FF88), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("SYNC LOG", color = Color(0xFF00FF88), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                Text("Log", color = HioneMuted, fontSize = 11.sp)
                            }

                            Spacer(Modifier.height(10.dp))

                            ingestState.logMessages.takeLast(8).forEach { line ->
                                Text(
                                    line,
                                    color = if (line.contains("Completed") || line.contains("Ready")) Color(0xFF00FF88) else Color(0xFFA6C5C8),
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                )
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }

                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
fun VolumeProgressRow(
    title: String,
    path: String,
    usedStr: String,
    totalStr: String,
    pct: Int,
    barColor: Color
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(1.dp))
                Text(path, color = HioneMuted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text("$usedStr / $totalStr", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, maxLines = 1, softWrap = false)
                Spacer(Modifier.height(1.dp))
                Text("$pct% used", color = barColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { (pct / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = Color(0x28FFFFFF)
        )
    }
}

@Composable
fun CodecMetricPill(
    modifier: Modifier = Modifier,
    badge: String,
    count: Int,
    desc: String,
    color: Color
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.2.dp, color.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(badge, color = color, fontSize = 13.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                Text(count.toString(), color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            }
            Spacer(Modifier.height(3.dp))
            Text(desc, color = HioneMuted, fontSize = 11.sp)
        }
    }
}
