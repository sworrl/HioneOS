package com.hione.launcher.observatory

import com.hione.launcher.Mascot
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.launcher.*
import com.hione.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Vocaloid anatomy: what's inside the player, laid out as a body.
 *  - Brain: how the system is running, the HioneOS build, every Hione app's version, BPM game progress
 *  - Ears: the DAC, the listening profile and EQ, play-to-every-output, what's playing
 *  - Eyes: display and the motion/touch parts
 *  - Heart: battery, charger and the CPU
 *  - Voice: FM tuner, weather radio and NWS alerts, song ID
 *  - Nerves: network link, USB DAC mode, Google services, Android Auto, the updater
 *
 * Every live value is read, never assumed: "—" when it can't be read. The few static part names
 * are labeled "measured on this device" because that's what they are.
 */
@Composable
fun HioneAnatomicalObservatoryModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    var activeOrgan by remember { mutableStateOf("ALL") }

    // Live Telemetry Poll. Initial values are EMPTY/0 (rendered "—"), never presumed clocks or RAM.
    // The placeholder row is sized to the device's REAL core count (was a hardcoded 8, which drew
    // eight permanently-"—" bars on any other SoC). Values stay null until a real sysfs read lands.
    val cpuFreqs by produceState<List<Int?>>(
        initialValue = List(Runtime.getRuntime().availableProcessors().coerceIn(1, 16)) { null }
    ) {
        while (true) {
            withContext(Dispatchers.IO) {
                value = readLiveCpuFreqs()
            }
            delay(1200)
        }
    }

    val memoryState by produceState(initialValue = Pair(0L, 0L)) {
        while (true) {
            withContext(Dispatchers.IO) {
                value = readLiveMemory(ctx)
            }
            delay(2000)
        }
    }

    // Settings.Global readouts from Hione Music, the FM app, weather and the BPM game. A handful
    // of string reads every 2 s, off the main thread, only while this sheet is open.
    val live by produceState(initialValue = LiveAnatomy.EMPTY) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { readLiveAnatomy(ctx) }.getOrDefault(LiveAnatomy.EMPTY) }
            delay(2000)
        }
    }

    // Installed versions don't change while the sheet is open: read once.
    val apps by produceState<AppVersions?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { readAppVersions(ctx) }.getOrNull() }
    }

    // Real, cheap one-shot reads for the identity/capability pills.
    val facts = remember { readAnatomyFacts(ctx) }
    val netState by com.hione.launcher.network.HioneNetworkService.state.collectAsState()

    val uptimeSec by produceState(initialValue = SystemClock.elapsedRealtime() / 1000) {
        while (true) {
            value = SystemClock.elapsedRealtime() / 1000
            delay(1000)
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) { onDismissRequest() }

    val green = Color(0xFF00FF7F)
    val violet = Mascot.color(0xFFB9C2D0)
    val gold = com.hione.launcher.ui.HioneIdentity.Gold
    val nerveBlue = Mascot.color(0xFFFFC56B)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                // System-gesture-style dismiss: swipe up starting at the bottom edge of the card.
                .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest)
                .clip(CutCornerShape(16.dp))
                .background(Mascot.color(0xFF030D12))
                .border(
                    BorderStroke(
                        1.2.dp,
                        Brush.verticalGradient(
                            listOf(
                                HioneCyan.copy(alpha = 0.9f),
                                violet.copy(alpha = 0.4f),
                                HioneNeonPink.copy(alpha = 0.8f)
                            )
                        )
                    ),
                    CutCornerShape(16.dp)
                )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // Top Grab Handle
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(HioneCyan.copy(alpha = 0.6f))
                )

                Spacer(Modifier.height(6.dp))

                // Modal Header (Clean, no X button)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(green, Color(0xFF003319)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Psychology, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "HARDWARE",
                            color = HioneCyan,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            "What's inside, read live",
                            color = HioneTextSecondary,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Body part filter chips
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val organs = listOf(
                        Organ("ALL", "Everything", Icons.Default.AccessibilityNew, HioneCyan),
                        Organ("BRAIN", "Brain · system", Icons.Default.Psychology, green),
                        Organ("EARS", "Ears · audio", Icons.Default.Headphones, violet),
                        Organ("EYES", "Eyes · display", Icons.Default.Visibility, gold),
                        Organ("HEART", "Heart · power", Icons.Default.Favorite, HioneNeonPink),
                        Organ("VOICE", "Voice · radio", Icons.Default.Radio, HioneCyan),
                        Organ("NERVES", "Nerves · connections", Icons.Default.Hub, nerveBlue)
                    )
                    organs.forEach { organ ->
                        val isSelected = activeOrgan == organ.key
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) organ.accent.copy(alpha = 0.25f)
                                    else Mascot.color(0xFF071822)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) organ.accent else Color(0x30FFFFFF),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { activeOrgan = organ.key }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                organ.icon,
                                contentDescription = null,
                                tint = if (isSelected) organ.accent else Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = organ.label,
                                color = if (isSelected) organ.accent else Color.White,
                                fontSize = 8.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ---------------- BRAIN ----------------
                    if (activeOrgan in listOf("ALL", "BRAIN")) {
                        item {
                            AnatomicalOrganCard(
                                title = "BRAIN · HOW IT'S RUNNING",
                                icon = Icons.Default.Psychology,
                                subtitle = "Threads, app memory, system RAM and uptime",
                                accentColor = green
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricPill("THREADS", "${Thread.activeCount()}", green)
                                    val rt = Runtime.getRuntime()
                                    MetricPill("APP MEMORY", "${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)}M / ${rt.maxMemory() / (1024 * 1024)}M", HioneCyan)
                                    MetricPill("SYSTEM RAM", if (memoryState.second > 0) "${memoryState.first}M / ${memoryState.second}M" else "—", gold)
                                    val hrs = uptimeSec / 3600
                                    val mins = (uptimeSec % 3600) / 60
                                    MetricPill("UPTIME", "${hrs}h ${mins}m", violet)
                                }
                                HardwareFacts(
                                    listOf(
                                        "Chip" to "Snapdragon 680 (SM6225)",
                                        "DSPs" to "ADSP (audio), CDSP with HVX (unused), modem"
                                    )
                                )
                            }
                        }
                        item {
                            AnatomicalOrganCard(
                                title = "BRAIN · ${Mascot.osNameUpper}",
                                icon = Icons.Default.Memory,
                                subtitle = "The build and every ${Mascot.name} app's version",
                                accentColor = green
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Readout("${Mascot.osName}", facts.hioneosVersion, green)
                                    Readout("Build", facts.buildIncremental, green)
                                    Readout("Android", facts.androidVersion, green)
                                    Readout("Quick settings tiles", live.qsTiles, HioneCyan)
                                    Spacer(Modifier.height(2.dp))
                                    val list = apps
                                    if (list == null) {
                                        Readout("Apps", "—", Color.White)
                                    } else {
                                        list.hione.forEach { (label, version) ->
                                            Readout(label, version, if (version == NOT_INSTALLED) HioneTextSecondary.copy(alpha = 0.6f) else Color.White)
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            AnatomicalOrganCard(
                                title = "BRAIN · BPM GAME",
                                icon = Icons.Default.EmojiEvents,
                                subtitle = "What you've earned by tapping along",
                                accentColor = green
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricPill("REWARDS", live.rewards, green)
                                    MetricPill("SECRETS", live.secrets, gold)
                                }
                            }
                        }
                    }

                    // ---------------- EARS ----------------
                    if (activeOrgan in listOf("ALL", "EARS")) {
                        item {
                            AnatomicalOrganCard(
                                title = "EARS · DAC",
                                icon = Icons.Default.GraphicEq,
                                // CirrusLogicManager falls back to its own prefs mirror when the DAC
                                // sysfs node is unreadable, so this is not always a hardware readback.
                                subtitle = "Read from the DAC when it answers, else the last value set here",
                                accentColor = violet
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        MetricPill("GAIN", facts.dacGain, green)
                                        MetricPill("FILTER", facts.dacFilter, HioneCyan)
                                        MetricPill("DRE", facts.dacDre, Mascot.color(0xFF6FB0FF))
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        MetricPill("MAIN OUTPUT", facts.audioOutput, violet)
                                        MetricPill("MIXER", facts.mixerFormat, HioneCyan)
                                    }
                                    HardwareFacts(
                                        listOf(
                                            "DAC" to "Cirrus CS43198",
                                            "Speaker amp" to "Awinic AW883xx"
                                        )
                                    )
                                }
                            }
                        }
                        item {
                            AnatomicalOrganCard(
                                title = "EARS · LISTENING",
                                icon = Icons.Default.Headphones,
                                subtitle = "Profile, EQ and where the sound goes",
                                accentColor = violet
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Readout("Profile", live.profile, violet)
                                    Readout("Profile settings", live.profileSummary, Color.White)
                                    Readout("EQ", live.eq, HioneCyan)
                                    Readout("Play to every output", live.shareOn, green)
                                    Readout("Outputs right now", live.outputs, Color.White)
                                }
                            }
                        }
                        item {
                            AnatomicalOrganCard(
                                title = "EARS · NOW PLAYING",
                                icon = Icons.Default.MusicNote,
                                subtitle = "What ${Mascot.musicAppName} says is on",
                                accentColor = violet
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Readout("Song", live.song, Color.White)
                                    Readout("Playing", live.playing, green)
                                    Readout("Format", live.format, violet)
                                    Readout("Song BPM", live.trackBpm, HioneCyan)
                                    Readout("Live BPM", live.liveBpm, HioneCyan)
                                }
                            }
                        }
                    }

                    // ---------------- EYES ----------------
                    if (activeOrgan in listOf("ALL", "EYES")) {
                        item {
                            AnatomicalOrganCard(
                                title = "EYES · DISPLAY",
                                icon = Icons.Default.Visibility,
                                subtitle = "Light sensor, refresh rate and color range",
                                accentColor = gold
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricPill("LIGHT SENSOR", facts.lightSensor, gold)
                                    MetricPill("REFRESH", facts.refreshHz, HioneCyan)
                                    MetricPill("COLOR", facts.gamut, green)
                                }
                                HardwareFacts(
                                    listOf(
                                        "Touch" to "Goodix",
                                        "Accelerometer" to "QST qma6100",
                                        "Compass" to "QST qmc6309h"
                                    )
                                )
                            }
                        }
                    }

                    // ---------------- HEART ----------------
                    if (activeOrgan in listOf("ALL", "HEART")) {
                        item {
                            AnatomicalOrganCard(
                                title = "HEART · POWER",
                                icon = Icons.Default.Favorite,
                                subtitle = "Battery and charging",
                                accentColor = HioneNeonPink
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricPill("BATTERY", live.battery, HioneNeonPink)
                                    MetricPill("CHARGING", live.charging, green)
                                }
                                HardwareFacts(
                                    listOf(
                                        "Charger" to "MP2731",
                                        "Fuel gauge" to "CW2015"
                                    )
                                )
                            }
                        }
                        item {
                            AnatomicalOrganCard(
                                title = "HEART · CPU",
                                icon = Icons.Default.Speed,
                                // SoC name from Build.SOC_*, not a hardcoded assertion.
                                subtitle = realSocLabel()?.let { "$it · speed of each core" }
                                    ?: "Speed of each core",
                                accentColor = HioneNeonPink
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "${cpuFreqs.size} CORES (GHz)",
                                        color = HioneTextSecondary,
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        cpuFreqs.forEachIndexed { _, freqOrNull ->
                                            Column(
                                                Modifier.weight(1f),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                // null = cpufreq node unreadable for this core → flat bar + "—".
                                                val freq = freqOrNull ?: 0
                                                val barHeight = if (freqOrNull == null) 4f else (freq / 2400f * 24f).coerceIn(4f, 24f)
                                                Box(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .height(barHeight.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                        .background(
                                                            Brush.verticalGradient(
                                                                listOf(HioneNeonPink, Mascot.color(0xFF880E4F))
                                                            )
                                                        )
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    if (freqOrNull == null) "—" else "${freq / 1000f}",
                                                    color = Color.White,
                                                    fontSize = 6.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ---------------- VOICE ----------------
                    if (activeOrgan in listOf("ALL", "VOICE")) {
                        item {
                            AnatomicalOrganCard(
                                title = "VOICE · FM RADIO",
                                icon = Icons.Default.Radio,
                                subtitle = "What the FM app says it's tuned to",
                                accentColor = HioneCyan
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Readout("FM tuner", live.fm, HioneCyan)
                                    Readout("Tuner device", facts.fmNode, Color.White)
                                    Readout("Song ID", live.songId, Color.White)
                                    HardwareFacts(listOf("FM tuner" to "Si4705, 64.0 to 108.0 MHz, RDS"))
                                }
                            }
                        }
                        item {
                            AnatomicalOrganCard(
                                title = "VOICE · WEATHER RADIO",
                                icon = Icons.Default.Thunderstorm,
                                subtitle = "Nearest NOAA station and NWS alerts here",
                                accentColor = HioneCyan
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Readout("Nearest station", live.nwr, HioneCyan)
                                    Readout("Alerts", live.alerts, if (live.alertActive) HioneNeonPink else green)
                                    if (live.alertActive) {
                                        Readout("Top alert", live.alertTop, HioneNeonPink)
                                        Readout("Severity", live.alertSeverity, HioneNeonPink)
                                    }
                                }
                            }
                        }
                    }

                    // ---------------- NERVES ----------------
                    if (activeOrgan in listOf("ALL", "NERVES")) {
                        item {
                            AnatomicalOrganCard(
                                title = "NERVES · CONNECTIONS",
                                icon = Icons.Default.Hub,
                                subtitle = "Network, USB and storage",
                                accentColor = nerveBlue
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    // Live link from HioneNetworkService.
                                    val link = when {
                                        netState.wifi.isConnected -> "Wi-Fi ${netState.wifi.standard.ifBlank { netState.wifi.bandLabel }}".trim()
                                        netState.cellular.dataConnected -> "Cellular ${netState.cellular.networkType.ifBlank { "" }}".trim()
                                        netState.cellular.hasSignal -> "Cell signal, no data"
                                        netState.lastUpdated == 0L -> "—"
                                        else -> "Offline"
                                    }
                                    Readout("Network", link, nerveBlue)
                                    Readout("USB DAC mode", live.usbDac, green)
                                    Readout("SD card", facts.sdCard, Color.White)
                                    Readout("LED driver", facts.ledDriver, Color.White)
                                    HardwareFacts(listOf("USB-C controller" to "AW35615"))
                                }
                            }
                        }
                        item {
                            AnatomicalOrganCard(
                                title = "NERVES · GOOGLE AND UPDATES",
                                icon = Icons.Default.SystemUpdate,
                                subtitle = "Play services, Android Auto and the ${Mascot.osName} updater",
                                accentColor = nerveBlue
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Readout("Play services", apps?.playServices ?: "—", nerveBlue)
                                    Readout("Android Auto", apps?.androidAuto ?: "—", nerveBlue)
                                    Readout("Updater", apps?.updater ?: "—", green)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Organ(val key: String, val label: String, val icon: ImageVector, val accent: Color)

@Composable
private fun AnatomicalOrganCard(
    title: String,
    icon: ImageVector,
    subtitle: String,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp, topEnd = 4.dp, bottomStart = 4.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Mascot.color(0xFF0B1F2A),
                        Mascot.color(0xFF051118)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            accentColor.copy(alpha = 0.8f),
                            Color(0x30FFFFFF),
                            accentColor.copy(alpha = 0.3f)
                        )
                    )
                ),
                CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp, topEnd = 4.dp, bottomStart = 4.dp)
            )
            .padding(8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f))
                        .border(0.6.dp, accentColor.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        title,
                        color = accentColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        subtitle,
                        color = HioneTextSecondary,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    color: Color
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Mascot.color(0xFF030D14))
            .border(0.6.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = HioneTextSecondary,
                fontSize = 6.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont
            )
            Text(
                value,
                color = color,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}

/** One label/value line for readouts whose value can be long (song titles, profile settings). */
@Composable
private fun Readout(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = HioneTextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(104.dp)
        )
        Text(
            value,
            color = color,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Part names found on this unit. Static, so they're labeled as such rather than shown as live. */
@Composable
private fun HardwareFacts(rows: List<Pair<String, String>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x14FFFFFF))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            "MEASURED ON THIS DEVICE",
            color = HioneTextSecondary.copy(alpha = 0.7f),
            fontSize = 6.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont
        )
        rows.forEach { (label, value) -> Readout(label, value, Color.White.copy(alpha = 0.85f)) }
    }
}

// ---------------------------------------------------------------------------------------------
// Live readouts (Settings.Global, AudioManager, BatteryManager). Every field is "—" when the key
// is unset or unreadable: nothing here is guessed.
// ---------------------------------------------------------------------------------------------

private const val NOT_INSTALLED = "not installed"

private data class LiveAnatomy(
    val profile: String,
    val profileSummary: String,
    val eq: String,
    val shareOn: String,
    val outputs: String,
    val song: String,
    val playing: String,
    val format: String,
    val trackBpm: String,
    val liveBpm: String,
    val fm: String,
    val songId: String,
    val nwr: String,
    val alerts: String,
    val alertActive: Boolean,
    val alertTop: String,
    val alertSeverity: String,
    val rewards: String,
    val secrets: String,
    val qsTiles: String,
    val usbDac: String,
    val battery: String,
    val charging: String
) {
    companion object {
        val EMPTY = LiveAnatomy(
            "—", "—", "—", "—", "—", "—", "—", "—", "—", "—", "—", "—", "—", "—", false, "—", "—",
            "—", "—", "—", "—", "—", "—"
        )
    }
}

private fun readLiveAnatomy(ctx: Context): LiveAnatomy {
    val cr = ctx.contentResolver
    fun g(key: String): String? = runCatching { Settings.Global.getString(cr, key) }.getOrNull()
    fun gOrDash(key: String): String = g(key)?.trim()?.takeIf { it.isNotEmpty() } ?: "—"
    fun bpm(key: String): String = g(key)?.trim()?.toFloatOrNull()?.takeIf { it > 0f }?.let { "%.0f".format(java.util.Locale.US, it) } ?: "—"

    // Listening profile, written by Hione Music's ListeningProfileManager.
    val summaryRaw = g("hione_listening_profile_summary")?.trim()?.takeIf { it.isNotEmpty() }
    val eq = when {
        summaryRaw == null -> "—"
        else -> summaryRaw.split(",").map { it.trim() }.firstOrNull { it.startsWith("EQ") }
            ?.removePrefix("EQ")?.trim()?.ifEmpty { null }
            ?.replaceFirstChar { it.uppercase() }
            ?: "Not part of this profile"
    }

    // Play to every output. Unset means the default, which is on (HioneMirrorOutput).
    val shareRaw = g("hione_audio_share_enabled")?.trim()
    val shareOn = when {
        shareRaw.isNullOrEmpty() -> "On (default)"
        shareRaw == "0" -> "Off"
        shareRaw.toIntOrNull() != null -> "On"
        else -> "—"
    }

    val outputs = runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val skip = setOf(
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            android.media.AudioDeviceInfo.TYPE_TELEPHONY,
            android.media.AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
            android.media.AudioDeviceInfo.TYPE_FM,
            android.media.AudioDeviceInfo.TYPE_BUS,
            24, // TYPE_BUILTIN_SPEAKER_SAFE
            28  // TYPE_ECHO_REFERENCE
        )
        val names = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type !in skip }
            .map { outputName(it.type) }
            .distinct()
        if (names.isEmpty()) "None plugged in (speaker only)" else "${names.size}: ${names.joinToString(", ")}"
    }.getOrDefault("—")

    val title = g("hione_now_playing_title")?.trim()?.takeIf { it.isNotEmpty() }
    val artist = g("hione_now_playing_artist")?.trim()?.takeIf { it.isNotEmpty() }
    val song = when {
        title != null && artist != null -> "$title, $artist"
        title != null -> title
        else -> "—"
    }
    val playing = when (g("hione_is_playing")?.trim()) {
        "1" -> "Yes"
        "0" -> "No"
        else -> "—"
    }

    // FM: "off" or "101.9 WVAQ", published by the FM app.
    val fmRaw = g("hione_fm_status")?.trim()
    val fm = when {
        fmRaw.isNullOrEmpty() -> "—"
        fmRaw.equals("off", true) -> "Off"
        else -> "On, $fmRaw"
    }
    val fmInstalled = runCatching { ctx.packageManager.getPackageInfo("com.caf.fmradio", 0); true }.getOrDefault(false)
    val songId = if (fmInstalled) "Ask in the FM app" else "Needs the FM app"

    // Weather radio + NWS alerts, published by HioneNwsAlerts.
    val nwrRaw = g("hione_weather_nwr")
    val nwrKm = g("hione_weather_nwr_km")?.trim()?.toIntOrNull()
    val nwr = when {
        nwrRaw == null -> "—"
        nwrRaw.isBlank() -> "None in reach"
        nwrKm != null -> "${nwrRaw.trim()}, $nwrKm km away"
        else -> nwrRaw.trim()
    }
    val alertCount = g("hione_weather_alert_count")?.trim()?.toIntOrNull()
    val alerts = when {
        alertCount == null -> "—"
        alertCount == 0 -> "None right now"
        alertCount == 1 -> "1 active"
        else -> "$alertCount active"
    }

    // BPM game. Rewards are public; secrets show only a count, never names.
    val have = runCatching { com.hione.launcher.bpm.HioneUnlocks.unlockedIds(ctx) }.getOrNull()
    val rewards = have?.let { h ->
        val all = com.hione.launcher.bpm.HioneUnlocks.ALL
        "${all.count { it.id in h }} of ${all.size}"
    } ?: "—"
    val secrets = have?.let { h ->
        val all = com.hione.launcher.bpm.HioneSecrets.ALL
        "${all.count { it.id in h }} of ${all.size} found"
    } ?: "—"

    // Quick settings layout lives in Settings.Secure (QsTileOrder); unset = the stock set.
    val qsRaw = runCatching { Settings.Secure.getString(cr, "hione_qs_tiles") }.getOrNull()
        ?: g("hione_qs_tiles")
    val qsTiles = when {
        qsRaw == null -> "Default set"
        else -> qsRaw.split(',').count { it.isNotBlank() }.toString()
    }

    val usbDac = when (g("work_mode")?.trim()) {
        null, "" -> "—"
        "dacin" -> "On"
        else -> "Off"
    }

    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
    val battery = runCatching {
        bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }?.let { "$it%" }
    }.getOrNull() ?: "—"
    val charging = runCatching { bm?.isCharging?.let { if (it) "Yes" else "No" } }.getOrNull() ?: "—"

    return LiveAnatomy(
        profile = gOrDash("hione_listening_profile"),
        profileSummary = summaryRaw ?: "—",
        eq = eq,
        shareOn = shareOn,
        outputs = outputs,
        song = song,
        playing = playing,
        format = gOrDash("hione_now_playing_format"),
        trackBpm = bpm("hione_now_playing_bpm"),
        liveBpm = bpm("hione_live_bpm"),
        fm = fm,
        songId = songId,
        nwr = nwr,
        alerts = alerts,
        alertActive = (alertCount ?: 0) > 0,
        alertTop = gOrDash("hione_weather_alert_top"),
        alertSeverity = gOrDash("hione_weather_alert_severity"),
        rewards = rewards,
        secrets = secrets,
        qsTiles = qsTiles,
        usbDac = usbDac,
        battery = battery,
        charging = charging
    )
}

private fun outputName(type: Int): String = when (type) {
    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired jack"
    android.media.AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line out"
    android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital out"
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call"
    android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
    android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
    android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    26 -> "Bluetooth LE" // TYPE_BLE_HEADSET
    else -> "Type $type"
}

private data class AppVersions(
    val hione: List<Pair<String, String>>,
    val playServices: String,
    val androidAuto: String,
    val updater: String
)

private fun readAppVersions(ctx: Context): AppVersions {
    val pm = ctx.packageManager
    fun ver(pkg: String): String = runCatching {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(pkg, 0)
        info.versionName?.takeIf { it.isNotBlank() } ?: "installed"
    }.getOrDefault(NOT_INSTALLED)
    val hione = listOf(
        "${Mascot.musicAppName}" to "com.hione.player",
        "Launcher" to "com.hione.launcher",
        "Settings" to "com.hione.settings",
        "System UI" to "com.hione.systemui",
        "FM radio" to "com.caf.fmradio",
        "Clock and tools" to "com.hione.tools",
        "Media" to "com.hione.media",
        "Updater" to "com.hione.update",
        "Sysbridge" to "com.hione.sysbridge",
        "Hardware settings" to "com.m500.hardware"
    ).map { (label, pkg) -> label to ver(pkg) }
    return AppVersions(
        hione = hione,
        playServices = ver("com.google.android.gms"),
        androidAuto = ver("com.google.android.projection.gearhead"),
        updater = ver("com.hione.update")
    )
}

/** System property via reflection (android.os.SystemProperties is hidden). Blank → null. */
private fun sysProp(key: String): String? = runCatching {
    val c = Class.forName("android.os.SystemProperties")
    (c.getMethod("get", String::class.java).invoke(null, key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()

/**
 * Per-core MHz from cpufreq sysfs; null for a core whose node cannot be read (no presumed clock).
 * The core COUNT is now the device's real one (was a hardcoded 0..7, which drew eight bars on any
 * SoC and presented the surplus as permanently "—" cores).
 */
private fun readLiveCpuFreqs(): List<Int?> {
    val cores = try {
        java.io.File("/sys/devices/system/cpu")
            .listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }
            ?.size
            ?.takeIf { it > 0 }
            ?: Runtime.getRuntime().availableProcessors()
    } catch (_: Throwable) { Runtime.getRuntime().availableProcessors() }
    return (0 until cores.coerceIn(1, 16)).map { i ->
        try {
            val f = java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            if (f.exists() && f.canRead()) f.readText().trim().toIntOrNull()?.let { it / 1000 } else null
        } catch (_: Throwable) { null }
    }
}

/** Real SoC name from the build, never a hardcoded "Snapdragon Kryo". Blank/UNKNOWN → null. */
private fun realSocLabel(): String? = try {
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        val model = android.os.Build.SOC_MODEL
        val manu = android.os.Build.SOC_MANUFACTURER
        val combined = listOf(manu, model)
            .filter { it.isNotBlank() && !it.equals("unknown", true) }
            .joinToString(" ")
        combined.takeIf { it.isNotBlank() }
    } else null
} catch (_: Throwable) { null }

/** (used MB, total MB) from ActivityManager; (0, 0) when unavailable → rendered "—". */
private fun readLiveMemory(ctx: Context): Pair<Long, Long> {
    return try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        Pair(totalMb - availMb, totalMb)
    } catch (_: Throwable) {
        Pair(0L, 0L)
    }
}

/**
 * One-shot, real reads for the identity/capability pills. Every field is "—" or an explicit
 * "none"/"not visible" when the platform does not expose it — nothing is asserted.
 */
private data class AnatomyFacts(
    val dacGain: String,
    val dacFilter: String,
    val dacDre: String,
    val audioOutput: String,
    val lightSensor: String,
    val refreshHz: String,
    val gamut: String,
    val mixerFormat: String,
    val fmNode: String,
    val nowPlayingFormat: String,
    val ledDriver: String,
    val sdCard: String,
    val hioneosVersion: String,
    val buildIncremental: String,
    val androidVersion: String
)

private fun readAnatomyFacts(ctx: Context): AnatomyFacts {
    val dacGain = runCatching { com.hione.launcher.CirrusLogicManager.getGainModeOrNull(ctx)?.label }.getOrNull() ?: "—"
    val dacFilter = runCatching { com.hione.launcher.CirrusLogicManager.getDigitalFilterOrNull(ctx)?.label }.getOrNull() ?: "—"
    val dacDre = runCatching { com.hione.launcher.CirrusLogicManager.isDreEnabledOrNull(ctx) }.getOrNull()
        ?.let { if (it) "On" else "Off" } ?: "—"

    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
    val audioOutput = runCatching {
        val devs = am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.toList().orEmpty()
        val pick = devs.firstOrNull { it.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && it.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && it.type != android.media.AudioDeviceInfo.TYPE_TELEPHONY }
            ?: devs.firstOrNull()
        when (pick?.type) {
            null -> "—"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired jack"
            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line out"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            else -> "Type ${pick.type}"
        }
    }.getOrDefault("—")
    val mixerFormat = runCatching {
        val sr = am?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val fpb = am?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        if (sr.isNullOrBlank()) "—" else "$sr Hz · ${fpb ?: "—"} fr/buf"
    }.getOrDefault("—")

    val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
    val lightSensor = runCatching {
        sm?.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)?.name?.let { "$it" } ?: "None on this device"
    }.getOrDefault("—")

    val display = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) ctx.display
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
    }.getOrNull()
    val refreshHz = display?.let { "${"%.0f".format(it.refreshRate)} Hz" } ?: "—"
    val gamut = display?.let { if (it.isWideColorGamut) "Wide gamut" else "sRGB" } ?: "—"

    val fmNode = runCatching {
        val f = java.io.File("/dev/radio0")
        if (f.exists()) (if (f.canRead()) "present · readable" else "present · no access") else "not visible"
    }.getOrDefault("not visible")
    // "idle" was an ASSERTION: this pill read the unset key as proof of an idle device and said
    // so while music was playing. The key now has a writer — HioneNowPlayingFormat, fed from the
    // granted AudioTrack format in the player's sink — so an unset value means exactly one thing:
    // the player is not holding an output. That is reported as such, never as "idle".
    val nowPlayingFormat = runCatching {
        android.provider.Settings.Global.getString(ctx.contentResolver, "hione_now_playing_format")?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: "Not published"

    val ledDriver = runCatching {
        val nodes = listOf("/sys/class/leds/sgm31324-leds", "/sys/class/leds/red", "/sys/class/leds/blue")
        if (nodes.any { java.io.File(it).exists() }) "sysfs present" else "not visible"
    }.getOrDefault("not visible")
    val sdCard = runCatching {
        val smgr = ctx.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
        val removable = smgr?.storageVolumes?.firstOrNull { it.isRemovable }
        when {
            removable == null -> "No card"
            removable.state == android.os.Environment.MEDIA_MOUNTED -> "Mounted"
            else -> removable.state
        }
    }.getOrDefault("—")

    return AnatomyFacts(
        dacGain = dacGain, dacFilter = dacFilter, dacDre = dacDre, audioOutput = audioOutput,
        lightSensor = lightSensor, refreshHz = refreshHz, gamut = gamut,
        mixerFormat = mixerFormat, fmNode = fmNode, nowPlayingFormat = nowPlayingFormat,
        ledDriver = ledDriver, sdCard = sdCard,
        hioneosVersion = sysProp("ro.hioneos.version") ?: "—",
        buildIncremental = sysProp("ro.build.version.incremental")
            ?: android.os.Build.VERSION.INCREMENTAL?.takeIf { it.isNotBlank() } ?: "—",
        androidVersion = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
    )
}
