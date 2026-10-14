package com.m500.hardware

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Blackjack Monitor screens. Data: BlackjackMonitor.kt. Colors: the same dacTheme palette as the
// DAC page, so the card and the Monitor match the settings that are applied.

private val BjOk = Color(0xFF1ABC9C)
private val BjWarn = Color(0xFFF1C40F)
private val BjBad = Color(0xFFFF6B6B)

/**
 * Keeps the kernel log reader running while the calling screen is shown. [start] = false only
 * watches a reader the Monitor already started, so it never triggers Android's log prompt.
 */
@Composable
fun rememberBlackjackState(start: Boolean = true): BlackjackMonitor.State {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        BlackjackMonitor.attach(ctx)
        BlackjackMonitor.acquire(start)
        onDispose { BlackjackMonitor.release() }
    }
    return BlackjackMonitor.state.collectAsState().value
}

/** Identity, re-read every few seconds (a driver can be unbound or a module reloaded). */
@Composable
fun rememberBlackjackIdentity(): BlackjackMonitor.Identity? {
    var id by remember { mutableStateOf<BlackjackMonitor.Identity?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            id = withContext(Dispatchers.IO) { BlackjackMonitor.identity() }
            delay(10_000)
        }
    }
    return id
}

/** What Android is sending, polled while shown. */
@Composable
fun rememberAndroidStream(periodMs: Long): BlackjackMonitor.Stream? {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf<BlackjackMonitor.Stream?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            s = withContext(Dispatchers.IO) { BlackjackMonitor.androidStream(ctx) }
            delay(periodMs)
        }
    }
    return s
}

/** Plain summary of the path from Android to the DAC. */
data class BjPath(val line: String, val verdict: String?, val verdictColor: Color)

fun blackjackPath(st: BlackjackMonitor.State, stream: BlackjackMonitor.Stream?): BjPath {
    val target = st.poweredTarget.takeIf { st.powered == true }
    val dacOn = target == 2 || target == 3
    val dac = st.dacRate
    val android = stream?.rate
    return when {
        st.access != BlackjackMonitor.Access.OK -> BjPath("Live state needs the kernel log", null, HwMuted)
        target == 8 -> BjPath("Speaker: the DACs are off, the speaker amp plays", null, HwMuted)
        !dacOn -> BjPath("No DAC output powered", null, HwMuted)
        android == null -> BjPath("DAC ${BlackjackMonitor.formatRate(dac)}. Nothing playing", null, HwMuted)
        dac == android -> BjPath("Android ${BlackjackMonitor.formatRate(android)} to DAC ${BlackjackMonitor.formatRate(dac)}", "Native", BjOk)
        else -> BjPath("Android ${BlackjackMonitor.formatRate(android)} to DAC ${BlackjackMonitor.formatRate(dac)}", "Resampled", BjWarn)
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
private val dateTimeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
private fun bjTime(ms: Long?): String {
    if (ms == null) return "-"
    val today = System.currentTimeMillis() - ms < 20 * 3600_000L && ms <= System.currentTimeMillis() + 60_000
    return if (today) timeFmt.format(Date(ms)) else dateTimeFmt.format(Date(ms))
}

private fun ago(ms: Long?): String {
    if (ms == null) return ""
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 0 -> ""
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

private fun driverLabel(id: BlackjackMonitor.Identity?): Pair<String, Color> = when (id?.driver) {
    null -> "Checking" to HwMuted
    BlackjackMonitor.Driver.BLACKJACK -> "Blackjack (ours)" to BjOk
    BlackjackMonitor.Driver.HIBY -> "HiBy cs43198" to BjWarn
    BlackjackMonitor.Driver.NONE -> "No DAC driver bound" to BjBad
    BlackjackMonitor.Driver.UNKNOWN -> "Driver not visible" to HwMuted
}

/**
 * Compact card for the top of the DAC page. Tap opens the full Monitor.
 */
@Composable
fun BlackjackStatusCard(primary: Color, secondary: Color, bg: Color, onOpen: () -> Unit) {
    val id = rememberBlackjackIdentity()
    val st = rememberBlackjackState(start = false)
    val stream = rememberAndroidStream(3000)
    val (drv, drvColor) = driverLabel(id)
    val live = st.access == BlackjackMonitor.Access.OK
    val path = if (live) blackjackPath(st, stream) else BjPath(
        stream?.rate?.let { "Android sends ${BlackjackMonitor.formatRate(it)}" } ?: "Nothing playing", null, HwMuted
    )
    val main = st.main

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(lerp(HwSurface1, bg, 0.5f))
            .border(1.dp, primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable { onOpen() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DAC DRIVER", color = primary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text("Monitor →", color = secondary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(drvColor))
            Spacer(Modifier.width(8.dp))
            Text(drv, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            val power = when {
                st.access != BlackjackMonitor.Access.OK -> null
                st.powered == true -> "On: ${BlackjackMonitor.targetName(st.poweredTarget)}"
                st.powered == false -> "Off"
                else -> null
            }
            if (power != null) Text(power, color = if (st.powered == true) secondary else HwMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(path.line, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
            path.verdict?.let {
                Spacer(Modifier.width(8.dp))
                BjPill(it, path.verdictColor)
            }
        }
        if (st.access == BlackjackMonitor.Access.OK && main.regs.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Filter ${BlackjackMonitor.filterName(main.reg(0x090000))} · ${BlackjackMonitor.wordLength(main.reg(0x01000C))} · " +
                    "DRE ${BlackjackMonitor.dreText(main.reg(0x0C0001))} · Vol ${BlackjackMonitor.volumeText(main.reg(0x090002))}",
                color = HwMuted, fontSize = 14.sp, lineHeight = 19.sp
            )
        }
        val warns = st.problems.count { it.kind != BlackjackMonitor.Kind.ERROR || !it.text.startsWith("Write failed") }
        val issues = st.failedWrites + warns
        if (st.access == BlackjackMonitor.Access.OK) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (issues == 0) "${st.writes} register writes, none failed" else "${st.failedWrites} of ${st.writes} writes failed, $warns warnings",
                color = if (issues == 0) HwMuted else BjWarn, fontSize = 14.sp
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Text("Open the Monitor for live power, rate and register state.", color = HwMuted, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun BjPill(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) { Text(text, color = color, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
}

class BlackjackMonitorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BlackjackMonitorScreen(onBack = { finish() }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlackjackMonitorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val id = rememberBlackjackIdentity()
    val st = rememberBlackjackState()
    val stream = rememberAndroidStream(2000)

    // Settings that are not registers, and the palette the DAC page uses.
    var filterId by remember { mutableStateOf<String?>(null) }
    var gainId by remember { mutableStateOf<String?>(null) }
    var dre by remember { mutableStateOf<Boolean?>(null) }
    var hp by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                filterId = CirrusLogicManager.getDigitalFilter(ctx)?.id
                gainId = CirrusLogicManager.getGainMode(ctx)?.id
                dre = CirrusLogicManager.isDreEnabled(ctx)
                hp = CirrusLogicManager.isHighPowerEnabled(ctx)
            }
            delay(3000)
        }
    }
    val pal = remember(filterId, gainId, dre, hp) { dacTheme(filterId, gainId, dre, hp) }
    val primary = Color(pal.primary)
    val secondary = Color(pal.secondary)
    val bg = Color(pal.background)

    // Ticks once a second so "5s ago" moves.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLACKJACK MONITOR", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = primary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg)
            )
        },
        containerColor = bg
    ) { padding ->
        @Suppress("UNUSED_EXPRESSION") tick
        val main = st.main
        val path = blackjackPath(st, stream)
        val liveOk = st.access == BlackjackMonitor.Access.OK
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---- Driver
            item {
                val (drv, drvColor) = driverLabel(id)
                BjCard(primary, bg, Brush.linearGradient(listOf(lerp(bg, primary, 0.10f), lerp(bg, secondary, 0.22f)))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(drvColor))
                        Spacer(Modifier.width(10.dp))
                        Text(drv, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (id?.driver) {
                            BlackjackMonitor.Driver.BLACKJACK -> "Our clean-room driver pair is bound to the DACs."
                            BlackjackMonitor.Driver.HIBY -> "HiBy's closed driver is bound. Blackjack is not running in this build."
                            BlackjackMonitor.Driver.NONE -> "The DAC at 0x30 has no driver bound."
                            else -> "Reading the driver links in sysfs."
                        },
                        color = HwMuted, fontSize = 14.5.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    BjRow("Codec driver", id?.codecDriver ?: "not bound", primary)
                    BjRow("Codec module", id?.codecModule ?: "unknown", primary)
                    BjRow("Platform driver", id?.hooksDriver ?: "not bound", primary)
                    BjRow("Platform module", id?.hooksModule ?: "unknown", primary)
                    listOf(0x30 to "3.5 mm and 4.4 mm +", 0x33 to "4.4 mm -").forEach { (a, role) ->
                        val c = st.chips[a]
                        BjRow(
                            "Chip 0x%02X".format(a),
                            if (c?.deviceId != null) "CS${c.deviceId} rev ${c.rev}  ($role)" else "id not seen yet  ($role)",
                            primary
                        )
                    }
                    if (st.probeLine != null) BjRow("Probe", st.probeLine, primary)
                    BjRow(
                        "Hooks",
                        when {
                            st.hooksRegistered -> "registered with the machine driver"
                            id?.hooksDriver?.startsWith("blackjack") == true -> "bound (the boot line has left the log)"
                            else -> "not seen in the log"
                        },
                        primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("MODULE PARAMETERS", color = primary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    val params = id?.params.orEmpty()
                    val inferred = inferParams(st)
                    params.forEach { (k, v) ->
                        val short = k.substringAfter('.')
                        val owner = k.substringBefore('.').substringAfter("blackjack_")
                        BjRow("$owner $short", v ?: inferred[short]?.let { "$it (from the log)" } ?: "not readable here", primary)
                    }
                    if (params.values.any { it == null }) {
                        Text("Module parameters are sysfs files this app may not read, so values come from what the log shows.", color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
                    }
                }
            }

            // ---- Live
            item {
                BjSection("Live", primary)
                BjCard(primary, bg) {
                    if (!liveOk) {
                        Text(
                            when (st.access) {
                                BlackjackMonitor.Access.STARTING -> "Reading the kernel log. Android asks once to allow log access: choose Allow one-time access."
                                else -> st.accessNote ?: "The kernel log is not readable."
                            },
                            color = BjWarn, fontSize = 14.5.sp, lineHeight = 19.sp
                        )
                        if (st.access == BlackjackMonitor.Access.DENIED || st.access == BlackjackMonitor.Access.FAILED) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(primary)
                                    .clickable { BlackjackMonitor.retry() }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) { Text("Read the kernel log", color = Color.Black, fontSize = 14.5.sp, fontWeight = FontWeight.Bold) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(path.line, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        path.verdict?.let { Spacer(Modifier.width(8.dp)); BjPill(it, path.verdictColor) }
                    }
                    Spacer(Modifier.height(8.dp))
                    BjRow("Output", st.selected?.let { BlackjackMonitor.targetName(it) + "  (target $it)" } ?: "unknown", primary, ago(st.selectedAtMs))
                    BjRow(
                        "Power",
                        when (st.powered) { true -> "on, ${BlackjackMonitor.targetName(st.poweredTarget)}"; false -> "off"; null -> "unknown" } +
                            if (st.powered == true && st.selected == 0) ", turning off" else "",
                        primary, ago(st.powerAtMs)
                    )
                    BjRow("DAC rate", BlackjackMonitor.formatRate(st.dacRate) + (main.reg(0x01000B)?.let { "  (0x01000B = %d)".format(it) } ?: ""), primary)
                    BjRow("Backend rate", st.hwRate?.let { "${BlackjackMonitor.formatRate(it)}, ${BlackjackMonitor.alsaFormat(st.hwFormat)}, ${st.hwChannels} ch" } ?: "no stream seen", primary, ago(st.hwAtMs))
                    BjRow(
                        "Android sends",
                        stream?.rate?.let { r -> BlackjackMonitor.formatRate(r) + (stream.format?.let { ", $it" } ?: "") + (stream.device?.let { ", $it" } ?: "") }
                            ?: (stream?.source ?: "not readable"),
                        primary
                    )
                    if (st.hwRate != null && st.dacRate != null && st.hwRate != st.dacRate && st.powered == true && (st.poweredTarget == 2 || st.poweredTarget == 3)) {
                        Text("The DAC rate does not match the backend rate.", color = BjBad, fontSize = 14.sp)
                    }
                    BjRow("Word length", BlackjackMonitor.wordLength(main.reg(0x01000C)) + (st.chips[0x33]?.reg(0x01000C)?.let { if (it != main.reg(0x01000C)) ", 0x33: ${BlackjackMonitor.wordLength(it)}" else "" } ?: ""), primary)
                    BjRow("Filter", BlackjackMonitor.filterName(main.reg(0x090000)), primary)
                    BjRow("Volume", "A ${BlackjackMonitor.volumeText(main.reg(0x090002))}, B ${BlackjackMonitor.volumeText(main.reg(0x090001))}", primary)
                    BjRow("Mute", BlackjackMonitor.muteText(main.reg(0x090003)), primary)
                    BjRow("DRE", BlackjackMonitor.dreText(main.reg(0x0C0001)), primary)
                    BjRow("Chip power", BlackjackMonitor.powerText(main.reg(0x020000)), primary)
                    BjRow("Gain", when (gainId) { "low" -> "low, -12 dB"; "middle" -> "middle, -6 dB"; "high" -> "high, 0 dB"; null -> "not set"; else -> gainId!! } + "  (setting)", primary)
                    BjRow("High power", when (hp) { true -> "on"; false -> "off"; null -> "not set" } + "  (setting)", primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Register values are the last ones the driver wrote, from its log. Gain is an offset inside the volume value and high power is board switches, so both show the setting the DAC was last told.",
                        color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp
                    )
                }
            }

            // ---- Registers per chip
            item {
                BjSection("Registers", primary)
                BjCard(primary, bg) {
                    Row {
                        Text("Register", color = primary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
                        Text("0x30", color = primary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("0x33", color = primary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    KEY_REGS.forEach { (reg, name) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("%06X %s".format(reg, name), color = Color.White, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.4f))
                            listOf(0x30, 0x33).forEach { a ->
                                val v = st.chips[a]?.reg(reg)
                                Text(v?.let { "%02X".format(it) } ?: "--", color = if (v == null) HwMuted else Color.White, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Writes seen: 0x30 ${st.chips[0x30]?.writes ?: 0}, 0x33 ${st.chips[0x33]?.writes ?: 0}", color = HwMuted, fontSize = 13.5.sp)
                }
            }

            // ---- Problems
            item {
                BjSection("Problems", primary)
                BjCard(primary, bg) {
                    BjRow("Failed writes", "${st.failedWrites} of ${st.writes}", primary)
                    st.lastFailure?.let { Text(it, color = BjBad, fontSize = 14.sp) }
                    if (st.problems.isEmpty()) {
                        Text("No Blackjack warnings or errors in the kernel log.", color = HwMuted, fontSize = 14.sp)
                    } else {
                        // Same message repeated (one per power-off, say): one row with a count.
                        st.problems.groupBy { it.text }.values
                            .map { g -> g.last() to g.size }
                            .sortedByDescending { it.first.atMs }
                            .take(10)
                            .forEach { (e, n) -> BjEventRow(if (n > 1) e.copy(text = "${e.text}  (x$n)") else e) }
                    }
                }
            }

            // ---- Timeline
            item { BjSection("Timeline", primary) }
            val events = st.events.asReversed().take(120)
            if (events.isEmpty()) {
                item { Text(if (liveOk) "No driver events in the kernel log yet." else "Waiting for the kernel log.", color = HwMuted, fontSize = 14.sp) }
            }
            items(events) { e -> BjEventRow(e) }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sources: driver links in sysfs, the kernel log (logcat -b kernel), and AudioFlinger's output threads. Read-only: this screen never writes to the driver.",
                    color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Registers worth showing, with short names (spec 7.3.1, 8, 9). */
private val KEY_REGS = listOf(
    0x010006 to "MCLK",
    0x01000B to "rate",
    0x01000C to "word",
    0x020000 to "power",
    0x080000 to "full scale",
    0x090000 to "filter",
    0x090001 to "vol B",
    0x090002 to "vol A",
    0x090003 to "path 1",
    0x090004 to "path 2",
    0x09000A to "SOS en",
    0x09000B to "SOS src",
    0x0B0000 to "class H",
    0x0C0001 to "DRE",
)

/** What the write log says about parameters that sysfs will not show this app. */
private fun inferParams(st: BlackjackMonitor.State): Map<String, String> {
    val out = HashMap<String, String>()
    if (st.writes > 0) out["log_writes"] = "1"
    val a = st.chips[0x30]?.reg(0x09000A)
    val b = st.chips[0x30]?.reg(0x09000B)
    if (a == 0xF0 && b == 0x0C) out["sos"] = "1"
    return out
}

@Composable
private fun BjSection(title: String, color: Color) {
    Text(title.uppercase(), color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
}

@Composable
private fun BjCard(primary: Color, bg: Color, brush: Brush? = null, content: @Composable ColumnScope.() -> Unit) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
    Column(
        (if (brush != null) base.background(brush) else base.background(lerp(HwSurface1, bg, 0.5f)))
            .border(1.dp, primary.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun BjRow(label: String, value: String, accent: Color, note: String = "") {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = HwMuted, fontSize = 14.sp, modifier = Modifier.width(126.dp))
        Text(value, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (note.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(note, color = accent.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun BjEventRow(e: BlackjackMonitor.Event) {
    val c = when (e.kind) {
        BlackjackMonitor.Kind.ERROR -> BjBad
        BlackjackMonitor.Kind.WARN -> BjWarn
        BlackjackMonitor.Kind.POWER -> HwHioneTeal
        BlackjackMonitor.Kind.STREAM -> HwHionePink
        else -> Color.White
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(bjTime(e.atMs), color = HwMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(104.dp))
        Text(e.text, color = c, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}
