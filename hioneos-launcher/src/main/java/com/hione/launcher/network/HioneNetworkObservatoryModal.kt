package com.hione.launcher.network
import com.hione.launcher.Mascot
import com.hione.launcher.mascotPainter
import com.hione.launcher.*

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.hione.launcher.*
import com.hione.launcher.metrics.HioneMetricDatabase
import androidx.activity.compose.rememberLauncherForActivityResult
import com.hione.launcher.vpn.HioneWireGuardManager
import com.hione.launcher.vpn.HioneVpnStore
import com.hione.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 3D Embossed Tactile Action Icon Button.
 * Replaces blown-out flat neon circles with a tactile beveled cyber button
 * featuring a top specular highlight rim, dark recessed inner bed, and soft luminescent icon tint.
 */
@Composable
fun Cyber3dIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    accentColor: Color = HioneCyan,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false
) {
    val buttonShape = remember { CutCornerShape(6.dp) }
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(buttonShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.25f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.65f)
                    )
                )
            )
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(1.dp)
                .clip(CutCornerShape(5.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0xEE16303E),
                            Mascot.color(0xFF081822)
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                accentColor.copy(alpha = 0.75f),
                                CyberGlassBorder.copy(alpha = 0.25f),
                                accentColor.copy(alpha = 0.45f)
                            )
                        )
                    ),
                    CutCornerShape(5.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = accentColor,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = accentColor,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * Oki Hione Fully Interactive 3D Embossed RF Network & Wi-Fi Hardware Control Station.
 * Full modern replacement for AOSP Wi-Fi Settings with unlimited hardware telemetry,
 * real-time deduplicated AP scanner, 3D embossed tactile controls, network auth/connect modal,
 * RF channel spectrogram, and low-level radio power controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HioneNetworkObservatoryModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val networkState by HioneNetworkService.state.collectAsState()
    val wifi = networkState.wifi
    val cell = networkState.cellular
    val scannedAPs = networkState.scannedAPs
    val channelOccupancies = networkState.channelOccupancies

    // Filter out the active connected network from the available list to prevent duplication
    val availableAPs = remember(scannedAPs, wifi.isConnected, wifi.ssid) {
        if (wifi.isConnected && wifi.ssid.isNotEmpty()) {
            scannedAPs.filter { it.ssid.isNotBlank() && it.ssid != wifi.ssid && !it.isConnected }
        } else {
            scannedAPs.filter { it.ssid.isNotBlank() }
        }
    }

    var selectedAPForConnect by remember { mutableStateOf<HioneNetworkService.ScannedAccessPoint?>(null) }
    var isAddHiddenNetworkOpen by remember { mutableStateOf(false) }
    var hiddenSsidInput by remember { mutableStateOf("") }
    var hiddenPasswordInput by remember { mutableStateOf("") }
    var connectPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isWgQrModalOpen by remember { mutableStateOf(false) }
    var historyRecords by remember { mutableStateOf<List<HioneMetricDatabase.NetworkRecord>>(emptyList()) }

    LaunchedEffect(Unit) {
        historyRecords = HioneMetricDatabase.getInstance(ctx).getRecentNetworkHistory(20)
        HioneNetworkService.triggerScan(ctx)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweepPulse")
    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Pulse"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xEB0C0D10))
            .clickable { onDismissRequest() }
            // System-gesture-style dismiss: swipe up starting at the bottom edge of the modal.
            .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest)
    ) {
        // Outer 3D Beveled Modal Shell
        Box(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .align(Alignment.Center)
                .clickable(enabled = false) {}
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
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Mascot.color(0xFA09222E),
                                Mascot.color(0xFF04121A),
                                Mascot.color(0xFF02090D)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    HioneCyan.copy(alpha = pulseGlow),
                                    CyberGlassBorder.copy(alpha = 0.35f),
                                    Color(0xFF2979FF).copy(alpha = 0.7f)
                                )
                            )
                        ),
                        CutCornerShape(15.dp)
                    )
                    .padding(14.dp)
            ) {
                // Frosted Watermark of Task-Specific Telecom & RF Announcer Hione Art
                Image(
                    painter = mascotPainter(R.drawable.hione_dj_megaphone),
                    contentDescription = "${Mascot.name} with a megaphone",
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center)
                        .padding(16.dp),
                    alpha = 0.08f,
                    contentScale = ContentScale.Fit
                )

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // ============================================================
                    // HEADER BAR WITH 3D EMBOSSED TACTILE BUTTONS & HIONE AVATAR
                    // ============================================================
                    HioneNetObsHeaderBar(
                        networkState = networkState,
                        wifi = wifi,
                        cell = cell,
                        onDismissRequest = onDismissRequest
                    )

                    // ============================================================
                    // 3D EMBOSSED CONNECTED WI-FI HERO POD (SHOWN ONLY ONCE)
                    // ============================================================
                    HioneNetObsConnectedWifiHero(wifi = wifi)

                    // ============================================================
                    // 3D EMBOSSED AVAILABLE WI-FI ACCESS POINTS
                    // ============================================================
                    HioneNetObsAvailableApList(
                        networkState = networkState,
                        wifi = wifi,
                        availableAPs = availableAPs,
                        onSelectApForConnect = { selectedAPForConnect = it },
                        onConnectPasswordChange = { connectPassword = it },
                        onAddHiddenNetworkOpenChange = { isAddHiddenNetworkOpen = it }
                    )

                    // ============================================================
                    // 3D EMBOSSED RF CHANNEL OCCUPANCY & CONGESTION SPECTROGRAM
                    // ============================================================
                    HioneNetObsChannelSpectrogram(channelOccupancies = channelOccupancies)

                    // ============================================================
                    // 3D EMBOSSED UNRESTRICTED HARDWARE WIRELESS SETTINGS CARD
                    // ============================================================
                    HioneNetObsHardwareControls(wifi = wifi)

                    // ============================================================
                    // 3D EMBOSSED UDR WIREGUARD SPLIT-TUNNEL & WAN BRIDGE
                    // ============================================================
                    HioneNetObsWireGuardCard(
                        wifi = wifi,
                        cell = cell,
                        onOpenWgQrModal = { isWgQrModalOpen = true }
                    )
                }
            }
        }

        // ============================================================
        // 3D EMBOSSED INTERACTIVE NETWORK AUTH / CONNECTION DIALOG
        // ============================================================
        HioneNetObsConnectDialog(
            selectedAPForConnect = selectedAPForConnect,
            onSelectedApChange = { selectedAPForConnect = it },
            connectPassword = connectPassword,
            onConnectPasswordChange = { connectPassword = it },
            showPassword = showPassword,
            onShowPasswordChange = { showPassword = it }
        )

        // ============================================================
        // 3D EMBOSSED ADD HIDDEN NETWORK MODAL
        // ============================================================
        HioneNetObsAddHiddenDialog(
            isAddHiddenNetworkOpen = isAddHiddenNetworkOpen,
            onAddHiddenNetworkOpenChange = { isAddHiddenNetworkOpen = it },
            hiddenSsidInput = hiddenSsidInput,
            onHiddenSsidInputChange = { hiddenSsidInput = it },
            hiddenPasswordInput = hiddenPasswordInput,
            onHiddenPasswordInputChange = { hiddenPasswordInput = it },
            showPassword = showPassword,
            onShowPasswordChange = { showPassword = it }
        )

        // ============================================================
        // 3D EMBOSSED WIREGUARD SPLIT-TUNNEL QR CODE MODAL
        // ============================================================
        HioneNetObsWireGuardQrModal(
            isWgQrModalOpen = isWgQrModalOpen,
            onWgQrModalOpenChange = { isWgQrModalOpen = it }
        )
    }
}

// ============================================================================
// Sections extracted out of HioneNetworkObservatoryModal.
//
// HioneNetworkObservatoryModal compiled to ~46k dex instructions in one method.
// ART's JIT refuses to compile any method over 16384 instructions, so the whole
// modal was re-interpreted on every recomposition and pinned the main thread.
// Each private composable below is one of the modal's major visual sections,
// moved verbatim so every resulting method lands well under that ceiling.
// ============================================================================

/**
 * Header bar of the RF network observatory: Hione avatar, link/ping summary, the
 * Wi-Fi master power switch, and the 3D embossed scan and close buttons.
 *
 * Extracted out of [HioneNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HioneNetObsHeaderBar(
    networkState: HioneNetworkService.NetworkState,
    wifi: HioneNetworkService.WifiGranularState,
    cell: HioneNetworkService.CellularGranularState,
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current

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
                    .background(Mascot.color(0x33FFC56B))
                    .border(1.dp, HioneCyan, CutCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = mascotPainter(R.drawable.hione_dj_megaphone),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "NETWORK",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "CV01 RF Link: ${
                        if (wifi.isConnected) "Wi-Fi ${wifi.bandLabel.ifBlank { "" }}${if (wifi.rssiDbm > -100) " (${wifi.rssiDbm}dBm)" else ""}".trim()
                        else if (cell.hasSignal) "Cellular ${cell.networkType.ifBlank { "—" }}"
                        else "no link"
                    } · Ping: ${if (networkState.latencyMs > 0) "${networkState.latencyMs}ms" else "—"}",
                    color = HioneCyan,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Wi-Fi Master Power Toggle. Inert until the service has actually read the
            // radio once (lastUpdated != 0L) so it never shows a guessed position.
            val wifiStateKnown = networkState.lastUpdated != 0L
            Switch(
                checked = wifi.isEnabled,
                enabled = wifiStateKnown,
                onCheckedChange = { HioneNetworkService.setWifiEnabled(ctx, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = HioneCyan,
                    checkedTrackColor = Mascot.color(0x33FFC56B),
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color(0x22FFFFFF)
                ),
                modifier = Modifier.scale(0.8f)
            )

            // 3D Embossed Refresh / Scan Button
            Cyber3dIconButton(
                onClick = { HioneNetworkService.triggerScan(ctx) },
                icon = Icons.Default.Refresh,
                contentDescription = "Scan APs",
                accentColor = HioneCyan,
                isLoading = networkState.isScanning
            )

            // 3D Embossed Close Button
            Cyber3dIconButton(
                onClick = onDismissRequest,
                icon = Icons.Default.Close,
                contentDescription = "Close",
                accentColor = HioneNeonPink
            )
        }
    }

    Spacer(Modifier.height(10.dp))
}

/**
 * 3D embossed hero pod for the currently connected Wi-Fi network: SSID/BSSID,
 * band and PHY chips, RSSI bars, IP/gateway/DNS readout and the disconnect /
 * forget actions.
 *
 * Extracted out of [HioneNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HioneNetObsConnectedWifiHero(
    wifi: HioneNetworkService.WifiGranularState
) {
    val ctx = LocalContext.current

    if (wifi.isConnected) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CutCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.65f)
                        )
                    )
                )
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(1.dp)
                    .clip(CutCornerShape(11.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Mascot.color(0xEE0C2936),
                                Mascot.color(0xFF04141E),
                                Mascot.color(0xFF020B10)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.linearGradient(listOf(HioneCyan, Mascot.color(0x33FFC56B), Color(0xFF2979FF)))
                        ),
                        CutCornerShape(11.dp)
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = HioneCyan, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    // Blank = the framework hid the SSID from us; say so, never a stand-in name.
                                    text = wifi.ssid.ifBlank { "SSID unavailable" },
                                    color = if (wifi.ssid.isBlank()) HioneTextSecondary else Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(com.hione.launcher.ui.HioneIdentity.Leek)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "CONNECTED · ${wifi.bssid.ifBlank { "BSSID —" }}",
                                        color = com.hione.launcher.ui.HioneIdentity.Leek,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier
                                    .clip(CutCornerShape(4.dp))
                                    .background(HioneCyan.copy(alpha = 0.2f))
                                    .border(0.5.dp, HioneCyan, CutCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    if (wifi.frequencyMhz > 0) "${wifi.bandLabel} Ch ${wifi.channel}" else "Band —",
                                    color = HioneCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Black
                                )
                            }
                            // 802.11 generation chip only when the framework reported it.
                            if (wifi.standard.isNotBlank()) Box(
                                Modifier
                                    .clip(CutCornerShape(4.dp))
                                    .background(Color(0x3300E676))
                                    .border(0.5.dp, com.hione.launcher.ui.HioneIdentity.Leek, CutCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(wifi.standard, color = com.hione.launcher.ui.HioneIdentity.Leek, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 5-Stage Micro Bars & Link Speed
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.height(16.dp)
                        ) {
                            repeat(5) { i ->
                                val active = (i + 1) <= wifi.signalLevel5
                                Box(
                                    Modifier
                                        .width(4.dp)
                                        .height(((i + 1) * 3.2).dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(if (active) HioneCyan else Color.White.copy(alpha = 0.2f))
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = (if (wifi.rssiDbm > -100) "${wifi.rssiDbm} dBm · ${wifi.signalPct}%" else "RSSI —") +
                                " · Tx ${if (wifi.txLinkSpeedMbps > 0) "${wifi.txLinkSpeedMbps}M" else "—"} / Rx ${if (wifi.rxLinkSpeedMbps > 0) "${wifi.rxLinkSpeedMbps}M" else "—"}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // 3D Recessed Hardware IP & Routing Grid
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(CutCornerShape(6.dp))
                            .background(Mascot.color(0x66020A0E))
                            .border(0.5.dp, CyberGlassBorder.copy(alpha = 0.4f), CutCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("IPv4 Address:", color = HioneTextSecondary, fontSize = 12.sp)
                            Text(wifi.ipAddress.ifBlank { "—" }, color = Color.White, fontSize = 12.sp, fontFamily = AudiowideFont)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Gateway:", color = HioneTextSecondary, fontSize = 12.sp)
                            Text(wifi.gateway.ifBlank { "—" }, color = Color.White, fontSize = 12.sp, fontFamily = AudiowideFont)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("DNS:", color = HioneTextSecondary, fontSize = 12.sp)
                            Text(
                                listOf(wifi.dns1, wifi.dns2).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "—" },
                                color = Color.White, fontSize = 12.sp, fontFamily = AudiowideFont
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Frequency:", color = HioneTextSecondary, fontSize = 12.sp)
                            Text(if (wifi.frequencyMhz > 0) "${wifi.frequencyMhz} MHz" else "—", color = HioneCyan, fontSize = 12.sp, fontFamily = AudiowideFont)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 3D Embossed Disconnect & Forget Actions
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(CutCornerShape(6.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                    )
                                )
                                .clickable { HioneNetworkService.disconnectWifi(ctx) }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(1.dp)
                                    .clip(CutCornerShape(5.dp))
                                    .background(Color(0xEE2A0A10))
                                    .border(1.dp, Color(0xFFFF5252), CutCornerShape(5.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("DISCONNECT", color = Color(0xFFFF5252), fontSize = 12.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                            }
                        }

                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(CutCornerShape(6.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                    )
                                )
                                .clickable { HioneNetworkService.forgetNetwork(ctx, wifi.ssid) }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(1.dp)
                                    .clip(CutCornerShape(5.dp))
                                    .background(Mascot.color(0xEE102430))
                                    .border(1.dp, CyberGlassBorder, CutCornerShape(5.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("FORGET NETWORK", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Available Wi-Fi access point list (deduplicated scan results) plus the
 * "ADD HIDDEN" entry point and the empty/scanning placeholder.
 *
 * Extracted out of [HioneNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HioneNetObsAvailableApList(
    networkState: HioneNetworkService.NetworkState,
    wifi: HioneNetworkService.WifiGranularState,
    availableAPs: List<HioneNetworkService.ScannedAccessPoint>,
    onSelectApForConnect: (HioneNetworkService.ScannedAccessPoint?) -> Unit,
    onConnectPasswordChange: (String) -> Unit,
    onAddHiddenNetworkOpenChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "WI-FI NETWORKS (${availableAPs.size})",
            color = HioneCyan,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont,
            letterSpacing = 1.sp
        )

        TextButton(
            onClick = { onAddHiddenNetworkOpenChange(true) },
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("ADD HIDDEN", color = HioneNeonPink, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
        }
    }

    Spacer(Modifier.height(6.dp))

    if (availableAPs.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CutCornerShape(8.dp))
                .background(Mascot.color(0x220A222C))
                .border(1.dp, CyberGlassBorder, CutCornerShape(8.dp))
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                // "Scanning..." is now tied to the real scan flag instead of being
                // appended permanently to an idle, finished, empty result.
                text = when {
                    networkState.lastUpdated == 0L -> "Reading Wi-Fi state…"
                    networkState.isScanning -> "Scanning…"
                    !wifi.isEnabled -> "Wi-Fi is turned off."
                    else -> "No other Wi-Fi networks in range"
                },
                color = HioneTextSecondary,
                fontSize = 12.5.sp
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            availableAPs.forEach { ap ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(CutCornerShape(8.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.12f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.5f)
                                )
                            )
                        )
                        .clickable {
                            if (ap.isSaved) {
                                HioneNetworkService.connectToNetwork(ctx, ap.ssid, "", ap.securityType)
                            } else if (ap.securityType.contains("Open")) {
                                HioneNetworkService.connectToNetwork(ctx, ap.ssid, "", "Open")
                            } else {
                                onSelectApForConnect(ap)
                                onConnectPasswordChange("")
                            }
                        }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(1.dp)
                            .clip(CutCornerShape(7.dp))
                            .background(Mascot.color(0xEE091F2A))
                            .border(1.dp, if (ap.isSaved) HioneCyan.copy(alpha = 0.6f) else CyberGlassBorder.copy(alpha = 0.4f), CutCornerShape(7.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (ap.securityType.contains("Open")) Icons.Default.Wifi else Icons.Default.WifiLock,
                                    contentDescription = null,
                                    tint = if (ap.isSaved) Color(0xFF00FF7F) else if (ap.bandLabel == "5GHz") HioneCyan else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = ap.ssid,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (ap.isSaved) {
                                            Box(
                                                Modifier
                                                    .clip(CutCornerShape(3.dp))
                                                    .background(Color(0x3300FF7F))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text("SAVED", color = Color(0xFF00FF7F), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                            }
                                        }
                                        Box(
                                            Modifier
                                                .clip(CutCornerShape(3.dp))
                                                .background(if (ap.bandLabel == "5GHz") Mascot.color(0x33FFC56B) else Color(0x22FFFFFF))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(ap.bandLabel, color = if (ap.bandLabel == "5GHz") HioneCyan else Color.LightGray, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Text(
                                            text = "Ch ${ap.channel} · ${ap.securityType}",
                                            color = HioneTextSecondary,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Signal bars
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.Bottom,
                                    modifier = Modifier.height(12.dp)
                                ) {
                                    repeat(5) { i ->
                                        val active = (i + 1) <= ap.signalLevel5
                                        Box(
                                            Modifier
                                                .width(2.5.dp)
                                                .height(((i + 1) * 2.4).dp)
                                                .clip(RoundedCornerShape(0.5.dp))
                                                .background(if (active) HioneCyan else Color.White.copy(alpha = 0.2f))
                                        )
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${ap.rssiDbm}dBm",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
}

/**
 * RF channel occupancy / congestion spectrogram section.
 *
 * Extracted out of [HioneNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HioneNetObsChannelSpectrogram(
    channelOccupancies: List<HioneNetworkService.ChannelOccupancy>
) {
    Text(
        text = "CHANNEL USE",
        color = HioneCyan,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AudiowideFont,
        letterSpacing = 1.sp
    )

    Spacer(Modifier.height(6.dp))

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
                .background(Mascot.color(0xFF031018))
                .border(1.dp, CyberGlassBorder.copy(alpha = 0.5f), CutCornerShape(9.dp))
                .padding(10.dp)
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("2.4 GHz Band (Channels 1-11)", color = HioneTextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    Text("5 GHz Band (Channels 36-165)", color = HioneTextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))

                if (channelOccupancies.isEmpty()) {
                    Text("No scan data yet.", color = HioneTextSecondary, fontSize = 11.5.sp)
                } else {
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(channelOccupancies) { ch ->
                            val barHeight = (ch.apCount * 12).coerceIn(16, 48)
                            Box(
                                Modifier
                                    .width(42.dp)
                                    .clip(CutCornerShape(4.dp))
                                    .background(Mascot.color(0x22FFC56B))
                                    .border(0.5.dp, HioneCyan.copy(alpha = 0.4f), CutCornerShape(4.dp))
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "${ch.apCount} APs",
                                        color = if (ch.apCount > 3) com.hione.launcher.ui.HioneIdentity.Coral else HioneCyan,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Box(
                                        Modifier
                                            .width(12.dp)
                                            .height(barHeight.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                if (ch.apCount > 3) com.hione.launcher.ui.HioneIdentity.Coral
                                                else if (ch.apCount > 1) com.hione.launcher.ui.HioneIdentity.Gold
                                                else com.hione.launcher.ui.HioneIdentity.Leek
                                            )
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "Ch ${ch.channel}",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
}

/**
 * Unrestricted hardware wireless controls card: 802.11 power-save toggle and
 * radio band steering preference.
 *
 * Extracted out of [HioneNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HioneNetObsHardwareControls(
    wifi: HioneNetworkService.WifiGranularState
) {
    Text(
        text = "WI-FI RADIO",
        color = HioneCyan,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AudiowideFont,
        letterSpacing = 1.sp
    )

    Spacer(Modifier.height(6.dp))

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
                .background(Mascot.color(0xEE0A232F))
                .border(1.dp, CyberGlassBorder.copy(alpha = 0.5f), CutCornerShape(9.dp))
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Wi-Fi 802.11 Power Save Mode
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Wi-Fi power save", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Text("Lets the Wi-Fi radio sleep between packets (iw power_save)", color = HioneTextSecondary, fontSize = 15.sp)
                    }
                    Switch(
                        checked = wifi.isPowerSaveOn,
                        onCheckedChange = { HioneNetworkService.setPowerSaveMode(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = HioneCyan, checkedTrackColor = Mascot.color(0x33FFC56B)),
                        modifier = Modifier.scale(0.8f)
                    )
                }

                HorizontalDivider(color = Mascot.color(0x22FFC56B))

                // Radio Band Preference
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Preferred band", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Text("5GHz is faster, 2.4GHz reaches farther", color = HioneTextSecondary, fontSize = 15.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("AUTO", "5G", "2.4G").forEach { mode ->
                            val active = wifi.bandPreference == mode
                            Box(
                                Modifier
                                    .clip(CutCornerShape(4.dp))
                                    .background(if (active) HioneCyan else Mascot.color(0x22FFC56B))
                                    .border(0.5.dp, HioneCyan, CutCornerShape(4.dp))
                                    .clickable { HioneNetworkService.setBandPreference(mode) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    mode,
                                    color = if (active) Color.Black else HioneCyan,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
}

/**
 * UDR WireGuard split-tunnel / WAN bridge card: real Tunnel.State headline,
 * endpoint / assigned IP / routes readout, QR-CONF entry point and the tunnel
 * policy explainer.
 *
 * Extracted out of [HioneNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HioneNetObsWireGuardCard(
    wifi: HioneNetworkService.WifiGranularState,
    cell: HioneNetworkService.CellularGranularState,
    onOpenWgQrModal: () -> Unit
) {
    val ctx = LocalContext.current

    Text(
        text = "WIREGUARD TO THE UDR",
        color = com.hione.launcher.ui.HioneIdentity.Leek,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = AudiowideFont,
        letterSpacing = 1.sp
    )

    Spacer(Modifier.height(6.dp))

    val isHomeWifi = remember(wifi.isConnected, wifi.ssid, wifi.ipAddress) {
        HioneIngestConfig.isHomeNetwork(ctx, wifi.isConnected, wifi.ssid, wifi.ipAddress)
    }
    // REAL tunnel state. This card used to print "SPILT-TUNNEL ONLINE (4G / WAN)"
    // whenever you simply were NOT on home Wi-Fi — with the tunnel down, with no keys
    // generated and with the endpoint/routes literally printed as "not configured" on
    // the next two lines. It now reads the WireGuard backend's own Tunnel.State, and
    // the transport name comes from the modem instead of a hardcoded "4G".
    val wgCardStatus by HioneWireGuardManager.status.collectAsState()
    val wgCardUp = wgCardStatus.state == com.wireguard.android.backend.Tunnel.State.UP
    val wgConfigured = HioneIngestConfig.vpnEndpoint(ctx).isNotBlank()
    val wanTransport = when {
        wifi.isConnected -> "Wi-Fi"
        cell.dataConnected -> cell.networkType.ifBlank { "mobile data" }
        else -> "no uplink"
    }
    val tunnelHeadline = when {
        isHomeWifi -> "ON HOME LAN (TUNNEL NOT NEEDED)"
        wgCardUp -> "SPLIT-TUNNEL UP ($wanTransport)"
        !wgConfigured -> "TUNNEL NOT CONFIGURED"
        else -> "TUNNEL DOWN"
    }
    val tunnelDotColor = when {
        isHomeWifi -> Mascot.color(0xFFFFC56B)
        wgCardUp -> com.hione.launcher.ui.HioneIdentity.Leek
        else -> HioneTextSecondary
    }
    val tunnelTextColor = when {
        isHomeWifi -> HioneCyan
        wgCardUp -> com.hione.launcher.ui.HioneIdentity.Leek
        else -> HioneTextSecondary
    }

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
                .background(Mascot.color(0xEE081F26))
                .border(1.dp, com.hione.launcher.ui.HioneIdentity.Leek.copy(alpha = 0.5f), CutCornerShape(9.dp))
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Tunnel Status & Endpoint
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(tunnelDotColor)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tunnelHeadline,
                                color = tunnelTextColor,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "WAN Endpoint: ${HioneIngestConfig.vpnEndpoint(ctx).ifBlank { "not configured" }} · Assigned IP: ${HioneIngestConfig.vpnAssignedIp(ctx).ifBlank { "auto" }}",
                            color = HioneTextSecondary,
                            fontSize = 11.5.sp
                        )
                        Text(
                            "Routes: ${HioneIngestConfig.vpnRoutes(ctx).ifBlank { "not configured" }}",
                            color = HioneTextSecondary,
                            fontSize = 15.sp
                        )
                    }

                    Button(
                        onClick = { onOpenWgQrModal() },
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = com.hione.launcher.ui.HioneIdentity.Leek.copy(alpha = 0.2f)),
                        border = BorderStroke(1.dp, com.hione.launcher.ui.HioneIdentity.Leek),
                        shape = CutCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, tint = com.hione.launcher.ui.HioneIdentity.Leek, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("QR / CONF", color = com.hione.launcher.ui.HioneIdentity.Leek, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    }
                }

                HorizontalDivider(color = Color(0x2200E676))

                // Dynamic Auto-Tunnel Rules Explanation
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(CutCornerShape(4.dp))
                        .background(Mascot.color(0xFF04131A))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Column {
                        Text(
                            "WHEN THE TUNNEL IS USED:",
                            color = HioneCyan,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            "On home Wi-Fi, WireGuard is skipped so traffic doesn't loop through double NAT.\nOn LTE or other Wi-Fi, the configured LAN subnets go through the tunnel to the WAN endpoint${HioneIngestConfig.vpnEndpoint(ctx).let { if (it.isNotBlank()) " at $it" else "" }}.",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 15.sp,
                            lineHeight = 10.sp
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
}

/**
 * Interactive network auth / connection dialog overlay for a tapped access point.
 *
 * Extracted out of [HioneNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HioneNetObsConnectDialog(
    selectedAPForConnect: HioneNetworkService.ScannedAccessPoint?,
    onSelectedApChange: (HioneNetworkService.ScannedAccessPoint?) -> Unit,
    connectPassword: String,
    onConnectPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onShowPasswordChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current

    if (selectedAPForConnect != null) {
        val ap = selectedAPForConnect!!
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xC8000000))
                .clickable { onSelectedApChange(null) }
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.90f)
                    .align(Alignment.Center)
                    .clickable(enabled = false) {}
                    .clip(CutCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.2f), Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(1.dp)
                        .clip(CutCornerShape(13.dp))
                        .background(Mascot.color(0xFF031620))
                        .border(1.dp, HioneCyan, CutCornerShape(13.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "CONNECT TO NETWORK",
                            color = HioneCyan,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = ap.ssid,
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            text = "BSSID: ${ap.bssid} · ${ap.bandLabel} Ch ${ap.channel} (${ap.securityType})",
                            color = HioneTextSecondary,
                            fontSize = 11.5.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        if (!ap.securityType.contains("Open")) {
                            OutlinedTextField(
                                value = connectPassword,
                                onValueChange = { onConnectPasswordChange(it) },
                                label = { Text("Password", fontSize = 13.5.sp) },
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { onShowPasswordChange(!showPassword) }) {
                                        Icon(
                                            if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = HioneCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = HioneCyan,
                                    unfocusedBorderColor = CyberGlassBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = HioneCyan
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text("This is an open network. No password needed.", color = com.hione.launcher.ui.HioneIdentity.Leek, fontSize = 12.5.sp)
                        }

                        Spacer(Modifier.height(14.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                                    .clickable { onSelectedApChange(null) }
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(1.dp)
                                        .clip(CutCornerShape(5.dp))
                                        .background(Color(0xEE12222E))
                                        .border(1.dp, CyberGlassBorder, CutCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("CANCEL", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                }
                            }

                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                                    .clickable {
                                        HioneNetworkService.connectToNetwork(ctx, ap.ssid, connectPassword, ap.securityType)
                                        onSelectedApChange(null)
                                    }
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(1.dp)
                                        .clip(CutCornerShape(5.dp))
                                        .background(Mascot.color(0xEE09303E))
                                        .border(1.dp, HioneCyan, CutCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("CONNECT", color = HioneCyan, fontSize = 12.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Add-hidden-network dialog overlay (manual SSID + passphrase entry).
 *
 * Extracted out of [HioneNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HioneNetObsAddHiddenDialog(
    isAddHiddenNetworkOpen: Boolean,
    onAddHiddenNetworkOpenChange: (Boolean) -> Unit,
    hiddenSsidInput: String,
    onHiddenSsidInputChange: (String) -> Unit,
    hiddenPasswordInput: String,
    onHiddenPasswordInputChange: (String) -> Unit,
    showPassword: Boolean,
    onShowPasswordChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current

    if (isAddHiddenNetworkOpen) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xC8000000))
                .clickable { onAddHiddenNetworkOpenChange(false) }
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.90f)
                    .align(Alignment.Center)
                    .clickable(enabled = false) {}
                    .clip(CutCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(1.dp)
                        .clip(CutCornerShape(13.dp))
                        .background(Mascot.color(0xFF031620))
                        .border(1.dp, HioneNeonPink, CutCornerShape(13.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "ADD HIDDEN WI-FI NETWORK",
                            color = HioneNeonPink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = hiddenSsidInput,
                            onValueChange = { onHiddenSsidInputChange(it) },
                            label = { Text("Network name (SSID)", fontSize = 13.5.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = HioneCyan,
                                unfocusedBorderColor = CyberGlassBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = HioneCyan
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = hiddenPasswordInput,
                            onValueChange = { onHiddenPasswordInputChange(it) },
                            label = { Text("Password (leave blank if open)", fontSize = 13.5.sp) },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { onShowPasswordChange(!showPassword) }) {
                                    Icon(
                                        if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        tint = HioneCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = HioneCyan,
                                unfocusedBorderColor = CyberGlassBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = HioneCyan
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(14.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                                    .clickable { onAddHiddenNetworkOpenChange(false) }
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(1.dp)
                                        .clip(CutCornerShape(5.dp))
                                        .background(Color(0xEE12222E))
                                        .border(1.dp, CyberGlassBorder, CutCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("CANCEL", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                }
                            }

                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(CutCornerShape(6.dp))
                                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                                    .clickable {
                                        if (hiddenSsidInput.isNotBlank()) {
                                            HioneNetworkService.connectToNetwork(ctx, hiddenSsidInput, hiddenPasswordInput, if (hiddenPasswordInput.isNotEmpty()) "WPA2 Personal" else "Open Network")
                                            onAddHiddenNetworkOpenChange(false)
                                            onHiddenSsidInputChange("")
                                            onHiddenPasswordInputChange("")
                                        }
                                    }
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(1.dp)
                                        .clip(CutCornerShape(5.dp))
                                        .background(Mascot.color(0xEE3A091E))
                                        .border(1.dp, HioneNeonPink, CutCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("CONNECT", color = HioneNeonPink, fontSize = 12.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * WireGuard split-tunnel configuration modal: native tunnel up/down, on-device
 * key generation, .conf editor, always-on registration and the UniFi Teleport
 * hand-off to WiFiman.
 *
 * Extracted out of [HioneNetworkObservatoryModal]: that composable compiled to far
 * more than ART's 16384-dex-instruction JIT ceiling, so the JIT refused it and the
 * whole modal ran interpreted on every recomposition, pinning the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HioneNetObsWireGuardQrModal(
    isWgQrModalOpen: Boolean,
    onWgQrModalOpenChange: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    if (isWgQrModalOpen) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xD8000000))
                .clickable { onWgQrModalOpenChange(false) }
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.90f)
                    .align(Alignment.Center)
                    .clickable(enabled = false) {}
                    .clip(CutCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.2f), Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(1.dp)
                        .clip(CutCornerShape(13.dp))
                        .background(Mascot.color(0xFF031620))
                        .border(1.dp, com.hione.launcher.ui.HioneIdentity.Leek, CutCornerShape(13.dp))
                        .padding(14.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "WIREGUARD VPN",
                                color = com.hione.launcher.ui.HioneIdentity.Leek,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                            IconButton(onClick = { onWgQrModalOpenChange(false) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Functional native WireGuard client (com.hione.launcher.vpn). Brings a
                        // real tunnel up/down in-process. Config is user-supplied (prefilled from
                        // HioneIngestConfig if set) — the private key lives only in the user's
                        // pasted .conf / encrypted store, never bundled.
                        run {
                            val wgStatus by HioneWireGuardManager.status.collectAsState()
                            val isUp = wgStatus.state == com.wireguard.android.backend.Tunnel.State.UP
                            // Prefer a previously-saved encrypted tunnel so keys persist across
                            // modal closes; otherwise seed from the (PII-free) ingest config.
                            var confText by remember {
                                val saved = HioneVpnStore.getConf(ctx, "hione")
                                val seed = saved ?: run {
                                    val addr = HioneIngestConfig.vpnAssignedIp(ctx)
                                    val ep = HioneIngestConfig.vpnEndpoint(ctx)
                                    val routes = HioneIngestConfig.vpnRoutes(ctx)
                                    if (addr.isNotBlank() || ep.isNotBlank() || routes.isNotBlank())
                                        "[Interface]\nPrivateKey = \nAddress = $addr\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = \nEndpoint = $ep\nAllowedIPs = ${routes.ifBlank { "0.0.0.0/0" }}\nPersistentKeepalive = 25\n"
                                    else ""
                                }
                                mutableStateOf(seed)
                            }
                            var myPublicKey by remember { mutableStateOf("") }
                            var alwaysOn by remember { mutableStateOf(false) }
                            var pendingConf by remember { mutableStateOf<String?>(null) }
                            val consentLauncher = rememberLauncherForActivityResult(
                                androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
                            ) { result ->
                                if (result.resultCode == android.app.Activity.RESULT_OK) {
                                    pendingConf?.let { c -> scope.launch {
                                        HioneWireGuardManager.saveAndConnect(ctx, "hione", c)
                                        // The checkbox used to stay ticked whether or not the
                                        // system accepted us as the always-on VPN app. Untick and
                                        // say so when the verified registration fails.
                                        if (alwaysOn && !HioneWireGuardManager.registerAlwaysOn(ctx)) {
                                            alwaysOn = false
                                            android.widget.Toast.makeText(
                                                ctx, "Couldn't register always-on VPN on this build",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } }
                                }
                                pendingConf = null
                            }

                            Text(
                                text = when {
                                    isUp -> "CONNECTED" + (wgStatus.activeName?.let { " ($it)" } ?: "")
                                    wgStatus.lastError != null -> "${wgStatus.lastError}"
                                    else -> "Disconnected"
                                },
                                color = if (isUp) com.hione.launcher.ui.HioneIdentity.Leek else HioneTextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))

                            // On-device key generation — the user never has to hand-make keys.
                            // Generating fills PrivateKey into the .conf and surfaces the PUBLIC
                            // key to add as this device's peer on the home router/relay.
                            OutlinedButton(
                                onClick = {
                                    val (priv, pub) = HioneWireGuardManager.generateKeyPair()
                                    myPublicKey = pub
                                    confText = if (confText.contains("PrivateKey"))
                                        confText.replace(Regex("(?m)^PrivateKey\\s*=.*$"), "PrivateKey = $priv")
                                    else
                                        "[Interface]\nPrivateKey = $priv\nAddress = \nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = \nEndpoint = \nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25\n"
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("GENERATE KEYS", fontFamily = AudiowideFont, fontSize = 16.sp) }

                            if (myPublicKey.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text("This device's PUBLIC key (add as a peer on your router):",
                                    color = HioneTextSecondary, fontSize = 12.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        myPublicKey,
                                        color = Color(0xFF9EE6C8), fontSize = 12.sp,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("wg-pubkey", myPublicKey))
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = com.hione.launcher.ui.HioneIdentity.Leek, modifier = Modifier.size(14.dp)) }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = confText,
                                onValueChange = { confText = it },
                                label = { Text("WireGuard .conf", fontSize = 16.sp) },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF9EE6C8), fontSize = 16.sp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 200.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            // Always-on: since we own the OS, register as the system always-on VPN
                            // so the tunnel back home auto-reconnects (and survives reboots) when
                            // the device is off the home network — no per-launch consent tap.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = alwaysOn,
                                    onCheckedChange = { alwaysOn = it },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00A86B))
                                )
                                Text("Keep the tunnel on (reconnects when away from home)",
                                    color = HioneTextSecondary, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (isUp) {
                                        scope.launch {
                                            // Manual disconnect clears any always-on registration so
                                            // the framework doesn't immediately re-dial the tunnel.
                                            runCatching {
                                                android.provider.Settings.Secure.putString(ctx.contentResolver, "always_on_vpn_app", "")
                                            }
                                            HioneWireGuardManager.disconnect(ctx)
                                        }
                                    } else {
                                        val intent = HioneWireGuardManager.consentIntent(ctx)
                                        if (intent != null) { pendingConf = confText; consentLauncher.launch(intent) }
                                        else scope.launch {
                                            HioneWireGuardManager.saveAndConnect(ctx, "hione", confText)
                                            // The checkbox used to stay ticked whether or not the
                                        // system accepted us as the always-on VPN app. Untick and
                                        // say so when the verified registration fails.
                                        if (alwaysOn && !HioneWireGuardManager.registerAlwaysOn(ctx)) {
                                            alwaysOn = false
                                            android.widget.Toast.makeText(
                                                ctx, "Couldn't register always-on VPN on this build",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isUp) Color(0xFFB71C1C) else Color(0xFF00A86B)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isUp) "DISCONNECT" else "CONNECT", fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // UniFi Teleport handoff — Teleport's relay traverses CGNAT but runs only
                        // in Ubiquiti's WiFiman app, so we hand the link off rather than fake it.
                        run {
                            var teleportLink by remember { mutableStateOf("") }
                            Text("UNIFI TELEPORT", color = com.hione.launcher.ui.HioneIdentity.Leek, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                            Text(
                                text = "Teleport works through CGNAT, but only inside WiFiman. Paste your Teleport link to open it there. To tunnel from here through CGNAT instead, point CONNECT above at a VPS relay that your UDR also connects to.",
                                color = HioneTextSecondary, fontSize = 12.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = teleportLink, onValueChange = { teleportLink = it },
                                label = { Text("Teleport / WiFiman link", fontSize = 16.sp) },
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = {
                                    val target = teleportLink.trim()
                                    try {
                                        if (target.isNotBlank())
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        else
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${HioneVpnStore.WIFIMAN_PKG}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (_: Throwable) {
                                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${HioneVpnStore.WIFIMAN_PKG}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("OPEN IN WIFIMAN", fontFamily = AudiowideFont, fontSize = 16.sp) }
                        }
                    }
                }
            }
        }
    }
}
