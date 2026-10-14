package com.hione.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.CyberDarkBg
import com.hione.player.CyberGlassBorder
import com.hione.player.HioneCyan
import com.hione.player.HioneNeonPink
import com.hione.player.HioneTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hione Music's playback settings: listening profiles, the direct route, car audio and the
 * external USB DAC output. These are the player's own options.
 *
 * The DAC itself (filter, gain, DRE, high power, output, USB DAC mode) is set on the Hardware
 * app's page, the only DAC page on HioneOS. The first row here opens it (DacSettingsLink).
 * Listening profiles still apply their DAC settings through HibyDacBridge on their own.
 */
class HardwareSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        setContent {
            HardwareSettingsScreen(onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // DTA (bit-perfect DIRECT-to-DAC) live status + playback behavior prefs
    var dtaStatus by remember { mutableStateOf<HioneDirectAudio.DirectStatus?>(null) }
    var pauseOnUnplug by remember { mutableStateOf(PlayerPreferences.loadPauseOnUnplug(ctx)) }

    // Poll the HAL's direct flags while this screen is open so the readout is live truth,
    // not a stale snapshot. Start or stop playback and watch it flip.
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { dtaStatus = HioneDirectAudio.status(ctx) }
            kotlinx.coroutines.delay(2000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        Image(
            painter = mascotPainter(id = R.drawable.hione_audiophile_art),
            contentDescription = "${Mascot.name} artwork",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0xEE121318),
                            Color(0x99000000),
                            Mascot.color(0xF80C0D10)
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.hione.player.ui.HioneBackButton(onClick = onBack)
                Text(
                    "PLAYBACK SETTINGS",
                    color = HioneCyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.width(40.dp))
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ============================================================
                // DAC SETTINGS: the Hardware app's page
                // ============================================================
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Mascot.color(0xDD0A1E26))
                            .border(1.dp, HioneCyan, RoundedCornerShape(16.dp))
                            .clickable { DacSettingsLink.open(ctx) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("DAC SETTINGS", color = HioneCyan, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            Spacer(Modifier.height(4.dp))
                            Text("Filter, gain, DRE, high power, output and USB DAC mode. Opens the Hardware app.", color = HioneTextSecondary, fontSize = 13.5.sp, lineHeight = 18.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("OPEN", color = HioneCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    }
                }

                // ============================================================
                // LISTENING PROFILES ENTRY (per-headphone EQ + DAC settings)
                // ============================================================

                item {
                    remember { com.hione.player.profiles.ListeningProfileStore.load(ctx) }
                    val current by com.hione.player.profiles.ListeningProfileStore.currentId.collectAsState()
                    val currentName = remember(current) {
                        com.hione.player.profiles.ListeningProfileStore.get(ctx, current)?.name
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Mascot.color(0xDD0A1E26))
                            .border(1.dp, HioneNeonPink, RoundedCornerShape(16.dp))
                            .clickable {
                                ctx.startActivity(android.content.Intent(ctx, com.hione.player.profiles.ListeningProfilesActivity::class.java))
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("LISTENING PROFILES", color = HioneNeonPink, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                currentName?.let { "Current: $it" } ?: "Pick your headphones: EQ and DAC settings that follow them",
                                color = HioneTextSecondary, fontSize = 13.5.sp, lineHeight = 18.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("OPEN", color = HioneNeonPink, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    }
                }

                // ============================================================
                // SECTION 1.3: CAR & ANDROID AUTO HI-RES AUDIO POLICY
                // ============================================================
                item {
                    var allowUsbAudio by remember { mutableStateOf(PlayerPreferences.loadAllowUsbAudio(ctx)) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Mascot.color(0xDD0A1E26))
                            .border(1.dp, if (allowUsbAudio) Color(0xFFFF9100) else HioneCyan, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("CAR AND ANDROID AUTO", color = HioneCyan, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                Spacer(Modifier.height(4.dp))
                                Text("Keeps audio on the 3.5mm/4.4mm AUX output in car mode instead of USB.", color = HioneTextSecondary, fontSize = 13.5.sp, lineHeight = 18.sp)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Block USB Audio in Car / Android Auto", color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (!allowUsbAudio) "On: audio stays on the 3.5mm/4.4mm AUX jack (recommended)."
                                    else "Off: audio goes to the car head unit over USB.",
                                    color = if (!allowUsbAudio) HioneCyan else Color(0xFFFF9100),
                                    fontSize = 13.5.sp, lineHeight = 18.sp
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Switch(
                                checked = !allowUsbAudio,
                                onCheckedChange = { blockUsb ->
                                    val allow = !blockUsb
                                    allowUsbAudio = allow
                                    PlayerPreferences.saveAllowUsbAudio(ctx, allow)
                                    HioneCarAudioRouter.ensureAnalogIfCar(ctx, "settings-toggle")
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = HioneCyan, checkedTrackColor = Mascot.color(0xFF00695C))
                            )
                        }
                    }
                }

                // ============================================================
                // SECTION 1.5: DTA — DIRECT TRANSPORT AUDIO (BIT-PERFECT PIPELINE)
                // ============================================================
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Mascot.color(0xCC07131A))
                            .border(1.dp, HioneCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text("DIRECT TRANSPORT AUDIO (DTA)", color = HioneCyan, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Bit-perfect route: the native sample rate goes straight to the dual CS43198 DACs and skips the Android 48 kHz mixer. The state below is read back live from the audio HAL.",
                            color = HioneTextSecondary, fontSize = 13.5.sp, lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        val st = dtaStatus
                        val direct = st?.weHoldDirect == true
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(11.dp).clip(CircleShape)
                                    .background(if (direct) Color(0xFF00E676) else if (st?.directFlagEnabled == true) Color(0xFFFFD600) else Color(0xFFFF1744))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    st == null -> "Reading HAL…"
                                    direct -> "DIRECT ● bit-perfect to DAC (this app holds the route)"
                                    st.directFlagEnabled -> "DIRECT held by: ${st.holderProcess.ifBlank { "unknown" }}"
                                    else -> "MIXED: Android 48 kHz pipeline (no direct route active)"
                                },
                                color = if (direct) Color(0xFF00E676) else Color.White,
                                fontSize = 14.5.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Allow-list: " + if (st?.allowListed == true) "com.hione.player granted ✓" else "NOT in direct_support_app_list ✗",
                            color = if (st?.allowListed == true) HioneCyan else Color(0xFFFF1744),
                            fontSize = 13.5.sp
                        )
                        Text(
                            "Direct engages while music is PLAYING on a wired output (never A2DP/speaker).",
                            color = HioneTextSecondary, fontSize = 13.sp, lineHeight = 17.sp
                        )
                        if (st?.allowListed != true) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        HioneDirectAudio.ensureAllowListed(ctx)
                                        dtaStatus = HioneDirectAudio.status(ctx)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A86B)),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("GRANT DIRECT ROUTE", fontWeight = FontWeight.Black, fontFamily = AudiowideFont, fontSize = 13.sp) }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(Modifier.height(14.dp))

                        // Playback behavior — granular, lives here + quick settings (NOT volume modal)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Pause on headphone unplug", color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                                Text("Stock HiBy behavior: pulling the jack pauses playback instead of switching to the speaker.", color = HioneTextSecondary, fontSize = 13.5.sp, lineHeight = 18.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Switch(
                                checked = pauseOnUnplug,
                                onCheckedChange = {
                                    pauseOnUnplug = it
                                    PlayerHolder.applyPauseOnUnplug(ctx, it)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = HioneCyan, checkedTrackColor = Mascot.color(0xFF00695C))
                            )
                        }
                    }
                }

                // ============================================================
                // SECTION 1.6: AUDIO PLAYBACK ENGINE ("SWAPPABLE BONE")
                // ============================================================
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Mascot.color(0xDD0A1E26))
                            .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            "PLAYBACK ENGINE",
                            color = HioneCyan,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = HioneCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("DirectPCM Bit-Perfect DTA (ExoPlayer)", color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Plays straight to the dual CS43198 DACs: 24/32-bit integer passthrough, float to 24-bit, no resampling, and the DTA direct route when granted. There is no fallback engine.",
                                    color = HioneTextSecondary, fontSize = 13.5.sp, lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }

                // ---------------- USB DAC OUTPUT (M500 → external USB DAC) ----------------
                item {
                    LaunchedEffect(Unit) { HioneUsbDacOutput.init(ctx) }
                    val dac = HioneUsbDacOutput.device
                    val routed = HioneUsbDacOutput.routed
                    val accent = if (routed) HioneCyan else HioneTextSecondary
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Mascot.color(0xDD0A1E26))
                            .border(1.dp, if (routed) HioneCyan.copy(alpha = 0.6f) else CyberGlassBorder, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("USB DAC OUTPUT", color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (dac == null) "Plug a USB DAC / dongle into the M500 (OTG). Playback follows it bit-perfect at the DAC's native rate."
                                    else HioneUsbDacOutput.statusLine(),
                                    color = accent, fontSize = 13.5.sp, lineHeight = 18.sp
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(if (routed) "● LIVE" else if (dac != null) "○ IDLE" else "—", color = accent, fontSize = 13.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                        }
                        if (dac != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Device", color = HioneTextSecondary, fontSize = 13.5.sp)
                                Text(dac.name, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Supported", color = HioneTextSecondary, fontSize = 13.5.sp)
                                Text("${dac.bitsLabel} · ${dac.ratesLabel}" + (if (dac.sampleRates.isNotEmpty()) " (${dac.sampleRates.sorted().joinToString("/") { if (it % 1000 == 0) "${it / 1000}" else "%.1f".format(it / 1000f) }} kHz)" else ""), color = HioneCyan, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Mixer", color = HioneTextSecondary, fontSize = 13.5.sp)
                                Text(HioneUsbDacOutput.mixerMode.ifBlank { "not routed" }, color = if (HioneUsbDacOutput.mixerMode == "BIT-PERFECT") HioneCyan else HioneTextSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Prefer USB DAC (exclusive, bit-perfect)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("Route playback to the USB DAC when attached; native rate / 24-32-bit, no resampling", color = HioneTextSecondary, fontSize = 13.5.sp, lineHeight = 18.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Switch(checked = HioneUsbDacOutput.preferUsb, onCheckedChange = { HioneUsbDacOutput.setPreferUsb(ctx, it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = HioneCyan, checkedTrackColor = Mascot.color(0xFF00695C)))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Volume passthrough", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("Hold media volume at 100% and let the DAC's own control set loudness", color = HioneTextSecondary, fontSize = 13.5.sp, lineHeight = 18.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Switch(checked = HioneUsbDacOutput.volumePassthrough, onCheckedChange = { HioneUsbDacOutput.setVolumePassthrough(ctx, it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = HioneCyan, checkedTrackColor = Mascot.color(0xFF00695C)))
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
