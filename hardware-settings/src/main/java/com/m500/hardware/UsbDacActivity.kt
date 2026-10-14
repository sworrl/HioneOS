package com.m500.hardware

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UsbDacActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VerboseUsbDacScreen(onExit = { finish() })
        }
    }
}

/**
 * REAL UAC2 gadget telemetry. Every field is null when the kernel does not expose it — the HUD
 * then prints "—" instead of a made-up value. Sources:
 *  - sys.usb.state / sys.usb.config      → which gadget functions are actually composed
 *  - /sys/class/udc/＊/state, current_speed → host attach state + negotiated bus speed
 *  - /proc/asound/cards + card＊/pcm＊/sub0/{hw_params,status} → the UAC2Gadget ALSA card's live
 *    stream (format / channels / rate / state / hw_ptr).
 */
data class UacSnapshot(
    val gadgetFunctions: String? = null,
    val uac2Composed: Boolean = false,
    val udcName: String? = null,
    val udcState: String? = null,
    val udcSpeed: String? = null,
    val cardIndex: Int? = null,
    val cardId: String? = null,
    val pcmNode: String? = null,
    val streamState: String? = null,
    val format: String? = null,
    val channels: Int? = null,
    val rateHz: Int? = null,
    val periodSize: Int? = null,
    val bufferSize: Int? = null,
    val hwPtrFrames: Long? = null
) {
    val hostAttached: Boolean get() = udcState != null && udcState != "not attached"
    val streaming: Boolean get() = streamState.equals("RUNNING", ignoreCase = true)
    val bitDepth: Int? get() = format?.let { Regex("S(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    /** Bytes per frame from the live format, when both are known. */
    val frameBytes: Int? get() {
        val f = format ?: return null; val ch = channels ?: return null
        val bytes = when {
            f.startsWith("S32") || f.startsWith("FLOAT") -> 4
            f.startsWith("S24_3") -> 3
            f.startsWith("S24") -> 4
            f.startsWith("S16") -> 2
            else -> return null
        }
        return bytes * ch
    }
}

object UsbDacProbe {
    private fun readTrim(path: String): String? = try {
        File(path).takeIf { it.canRead() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    fun snapshot(): UacSnapshot {
        val functions = UsbDacManager.sysProp("sys.usb.state") ?: UsbDacManager.sysProp("sys.usb.config")
        val uac2 = functions?.split(',')?.any { it.trim().equals("uac2", true) } == true

        // UDC (USB device controller) — real attach state and negotiated speed.
        val udcDir = try { File("/sys/class/udc").listFiles()?.firstOrNull() } catch (_: Throwable) { null }
        val udcState = udcDir?.let { readTrim("${it.path}/state") }
        val udcSpeed = udcDir?.let { readTrim("${it.path}/current_speed") }?.takeIf { !it.equals("UNKNOWN", true) }

        // ALSA gadget card: /proc/asound/cards lines look like " 1 [UAC2Gadget     ]: UAC2_Gadget - UAC2_Gadget"
        var cardIdx: Int? = null; var cardId: String? = null
        readTrim("/proc/asound/cards")?.lines()?.forEach { line ->
            val m = Regex("^\\s*(\\d+)\\s*\\[([^\\]]+)\\]").find(line) ?: return@forEach
            val id = m.groupValues[2].trim()
            if (cardIdx == null && (id.contains("UAC", true) || id.contains("Gadget", true))) {
                cardIdx = m.groupValues[1].toIntOrNull(); cardId = id
            }
        }

        var pcmNode: String? = null; var state: String? = null; var format: String? = null
        var channels: Int? = null; var rate: Int? = null; var period: Int? = null; var buffer: Int? = null
        var hwPtr: Long? = null
        val idx = cardIdx
        if (idx != null) {
            val cardDir = File("/proc/asound/card$idx")
            // Host playback arrives on the gadget's CAPTURE stream (pcm0c); check capture first.
            val pcms = try {
                cardDir.listFiles { f -> f.name.startsWith("pcm") }?.sortedBy { if (it.name.endsWith("c")) 0 else 1 }
            } catch (_: Throwable) { null } ?: emptyList()
            for (pcm in pcms) {
                val hw = readTrim("${pcm.path}/sub0/hw_params") ?: continue
                val st = readTrim("${pcm.path}/sub0/status")
                val stState = st?.let { Regex("state:\\s*(\\S+)").find(it)?.groupValues?.get(1) }
                val open = !hw.equals("closed", true)
                if (pcmNode == null || open) {
                    pcmNode = pcm.path
                    state = if (open) (stState ?: "OPEN") else "closed"
                    if (open) {
                        format = Regex("format:\\s*(\\S+)").find(hw)?.groupValues?.get(1)
                        channels = Regex("channels:\\s*(\\d+)").find(hw)?.groupValues?.get(1)?.toIntOrNull()
                        rate = Regex("rate:\\s*(\\d+)").find(hw)?.groupValues?.get(1)?.toIntOrNull()
                        period = Regex("period_size:\\s*(\\d+)").find(hw)?.groupValues?.get(1)?.toIntOrNull()
                        buffer = Regex("buffer_size:\\s*(\\d+)").find(hw)?.groupValues?.get(1)?.toIntOrNull()
                        hwPtr = st?.let { Regex("hw_ptr\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    }
                }
                if (open) break
            }
        }
        return UacSnapshot(
            gadgetFunctions = functions, uac2Composed = uac2,
            udcName = udcDir?.name, udcState = udcState, udcSpeed = udcSpeed,
            cardIndex = cardIdx, cardId = cardId, pcmNode = pcmNode, streamState = state,
            format = format, channels = channels, rateHz = rate, periodSize = period, bufferSize = buffer,
            hwPtrFrames = hwPtr
        )
    }
}

private const val NA = "—"

@Composable
fun VerboseUsbDacScreen(onExit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Configured (what we asked the gadget for) — shown ONLY as "configured", never as the live stream.
    var cfgRate by remember { mutableStateOf(UsbDacManager.getSampleRate(ctx)) }
    var cfgBits by remember { mutableStateOf(UsbDacManager.getBitDepth(ctx)) }
    var filter by remember { mutableStateOf<CirrusLogicManager.DigitalFilter?>(null) }
    var gain by remember { mutableStateOf<CirrusLogicManager.GainMode?>(null) }
    var outputMode by remember { mutableStateOf<CirrusLogicManager.OutputMode?>(null) }
    var isDreActive by remember { mutableStateOf<Boolean?>(null) }
    var bridgeOk by remember { mutableStateOf<Boolean?>(null) }
    var highPower by remember { mutableStateOf<Boolean?>(null) }
    var snap by remember { mutableStateOf<UacSnapshot?>(null) }   // null until the first real probe

    // Refresh live stats — REAL: kernel UDC + UAC2 gadget ALSA card; nothing is simulated.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                filter = CirrusLogicManager.getDigitalFilter(ctx)
                gain = CirrusLogicManager.getGainMode(ctx)
                outputMode = CirrusLogicManager.getOutputMode(ctx)
                isDreActive = CirrusLogicManager.isDreEnabled(ctx)
                highPower = CirrusLogicManager.isHighPowerEnabled(ctx)
                bridgeOk = DacBridge.available(ctx)
                cfgRate = UsbDacManager.getSampleRate(ctx); cfgBits = UsbDacManager.getBitDepth(ctx)
                snap = UsbDacProbe.snapshot()
                delay(1000)
            }
        }
    }

    val s = snap
    val probed = s != null
    val streaming = s?.streaming == true
    val hostAttached = s?.hostAttached == true
    val liveRate = s?.rateHz
    val liveBits = s?.bitDepth
    val streamStatus = when {
        !probed -> "PROBING…"
        streaming -> "STREAMING"
        s?.streamState != null && s.streamState != "closed" -> "STREAM ${s.streamState}"
        hostAttached -> "HOST ATTACHED · IDLE"
        s?.udcState == null -> "UDC STATE UNREADABLE"
        else -> "IDLE · NO USB HOST"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "dacGlow")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Stream-activity bars: full while the kernel reports the PCM RUNNING, empty otherwise.
    // The kernel exposes no per-channel level for the gadget stream, so this is an ACTIVITY
    // indicator, not a level meter (the old sine "VU" animation was fabricated).
    val activityFill = if (streaming) 1f else 0f

    // Nominal throughput from the LIVE format only (rate × frame bytes) — null when not streaming.
    val frameBytes = s?.frameBytes
    val nominalBytesPerSec: Long? = if (streaming && liveRate != null && frameBytes != null) liveRate.toLong() * frameBytes else null

    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xFF030D11))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Header Bar
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (streaming) HwHioneTeal else if (hostAttached) HwHionePink else HwMuted)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "USB DAC",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp
                        )
                    }

                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable {
                                scope.launch {
                                    UsbDacManager.setUsbDacMode(ctx, false)
                                    onExit()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Exit", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Hero Stream Dial & Format Card
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Mascot.color(0xFF07242B), Mascot.color(0xFF05171C))
                            )
                        )
                        .border(1.dp, HwHioneTeal.copy(alpha = pulseAlpha), RoundedCornerShape(18.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (streaming) "UAC2 USB AUDIO STREAM · LIVE" else "UAC2 USB AUDIO STREAM · $streamStatus",
                        color = if (streaming) HwHioneTeal else HwMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )

                    Spacer(Modifier.height(14.dp))

                    // Center Circular Display — LIVE values when the kernel reports a stream,
                    // otherwise the CONFIGURED values, explicitly labelled as such.
                    val showRate = liveRate ?: cfgRate
                    val showBits = liveBits ?: cfgBits
                    val live = liveRate != null
                    Box(
                        Modifier
                            .size(170.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(HwHioneTeal.copy(alpha = 0.20f * pulseAlpha), Mascot.color(0xFF031114))
                                )
                            )
                            .border(2.dp, HwHioneTeal.copy(alpha = pulseAlpha), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                formatKhz(showRate),
                                color = if (live) Color.White else HwMuted,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (showBits >= 32) "32-bit" else "$showBits-bit PCM",
                                color = if (live) HwHioneTeal else HwMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (live) "LIVE" else "CONFIGURED · NO STREAM",
                                color = if (live) HwHioneTeal else HwMuted,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(HwHionePink.copy(alpha = 0.25f))
                                    .border(1.dp, HwHionePink, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    filter?.label?.substringBefore(",")?.uppercase() ?: "FILTER UNKNOWN",
                                    color = HwHionePink,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Stream activity bars (kernel exposes no per-channel level for the gadget PCM)
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("STREAM ACTIVITY (no level meter source)", color = HwMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            Text(
                                nominalBytesPerSec?.let { "${String.format("%.1f", it / 1024f)} KB/s nominal" } ?: "$NA KB/s",
                                color = if (nominalBytesPerSec != null) HwHioneTeal else HwMuted,
                                fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("L", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Mascot.color(0xFF030D10))) {
                                Box(Modifier.fillMaxWidth(activityFill).fillMaxHeight().background(Brush.horizontalGradient(listOf(HwHioneTeal, HwHionePink))))
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("R", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Mascot.color(0xFF030D10))) {
                                Box(Modifier.fillMaxWidth(activityFill).fillMaxHeight().background(Brush.horizontalGradient(listOf(HwHioneTeal, HwHionePink))))
                            }
                        }
                    }
                }
            }

            // Section 1: Host / USB controller — every row from the UDC sysfs or gadget props
            item {
                Text(
                    "HOST AND USB CONTROLLER",
                    color = HwHioneTeal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwHioneTeal.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val udcState = s?.udcState
                    val stateLabel = when {
                        !probed -> NA
                        udcState == null -> "$NA (udc state unreadable)"
                        udcState == "configured" -> "CONFIGURED (host enumerated)"
                        udcState == "not attached" -> "NO HOST ATTACHED"
                        else -> udcState.uppercase()
                    }
                    VerboseDiagnosticRow("Host Connection State", stateLabel, if (udcState == "configured") HwHioneTeal else HwMuted)
                    VerboseDiagnosticRow("Host Bus Speed", when (s?.udcSpeed?.lowercase()) {
                        null -> NA
                        "high-speed" -> "High-Speed (480 Mbps)"
                        "super-speed" -> "SuperSpeed (5 Gbps)"
                        "super-speed-plus" -> "SuperSpeed+ (10 Gbps)"
                        "full-speed" -> "Full-Speed (12 Mbps)"
                        "low-speed" -> "Low-Speed (1.5 Mbps)"
                        else -> s?.udcSpeed ?: NA
                    }, Color.White)
                    VerboseDiagnosticRow("USB Gadget Functions", s?.gadgetFunctions ?: NA, Color.White)
                    VerboseDiagnosticRow("Host Interface Protocol", if (s?.uac2Composed == true) "UAC2 (USB Audio Class 2.0)" else "$NA (uac2 not composed)", Color.White)
                    VerboseDiagnosticRow("USB Device Controller", s?.udcName ?: NA, Color.White)
                    VerboseDiagnosticRow("ALSA Gadget Card", s?.cardId?.let { "card${s?.cardIndex} [$it]" } ?: NA, HwHioneTeal)
                    VerboseDiagnosticRow("PCM Period / Buffer", if (s?.periodSize != null && s.bufferSize != null) "${s.periodSize} / ${s.bufferSize} frames" else NA, Color.White)
                    VerboseDiagnosticRow("Underrun Counter", "not exposed by kernel", HwMuted)
                }
            }

            // Section 2: Real-time Audio Stream Parameters (live PCM only; "—" when closed)
            item {
                Text(
                    "LIVE PCM STREAM",
                    color = HwHioneTeal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwHioneTeal.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VerboseDiagnosticRow("Sampling Frequency", liveRate?.let { "$it Hz (${formatKhz(it)})" } ?: "$NA (configured $cfgRate Hz)", if (liveRate != null) HwHioneTeal else HwMuted)
                    VerboseDiagnosticRow("Sample Format", s?.format?.let { f -> liveBits?.let { "$f ($it-bit)" } ?: f } ?: "$NA (configured $cfgBits-bit)", if (s?.format != null) Color.White else HwMuted)
                    VerboseDiagnosticRow("Stream Channel Map", s?.channels?.let { if (it == 2) "2.0 Stereo" else "$it ch" } ?: NA, Color.White)
                    VerboseDiagnosticRow("Nominal Bitrate", nominalBytesPerSec?.let { "${it * 8 / 1000} kbps (${String.format("%.2f", it * 8 / 1_000_000f)} Mbps)" } ?: NA, if (nominalBytesPerSec != null) HwHionePink else HwMuted)
                    val ingress = s?.hwPtrFrames?.let { fr -> s?.frameBytes?.let { fb -> fr * fb } }
                    VerboseDiagnosticRow("Ingress Transferred (hw_ptr)", ingress?.let { "${String.format("%.2f", it / (1024f * 1024f))} MB" } ?: NA, Color.White)
                    VerboseDiagnosticRow("Kernel PCM Node", s?.pcmNode?.removePrefix("/proc/asound/")?.let { "$it (${s?.streamState ?: NA})" } ?: NA, HwMuted)
                }
            }

            // Section 3: Hardware Output Stage (persist.vendor.audio.hione.* / Settings.Global; "unknown" when neither answers)
            item {
                Text(
                    "CIRRUS LOGIC DUAL CS43198 DAC",
                    color = HwHioneTeal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwHioneTeal.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VerboseDiagnosticRow("DAC Architecture", "Dual CS43198 MasterHIFI, parallel", HwHioneTeal)
                    VerboseDiagnosticRow("Reconstruction Filter", filter?.label ?: "unknown", if (filter != null) Color.White else HwMuted)
                    VerboseDiagnosticRow("Gain (digital offset)", gain?.label ?: "unknown", if (gain != null) Color.White else HwMuted)
                    VerboseDiagnosticRow("Physical Output Port", when (outputMode) {
                        CirrusLogicManager.OutputMode.LINE_OUT -> "Line Out (LO)"
                        CirrusLogicManager.OutputMode.HEADPHONE_OUT -> "Headphone Out (PO)"
                        null -> "unknown"
                    }, if (outputMode != null) HwHionePink else HwMuted)
                    VerboseDiagnosticRow("Dynamic Range Enhancement (DRE)", when (isDreActive) { true -> "Enabled"; false -> "Disabled"; null -> "unknown" }, if (isDreActive == true) HwHioneTeal else HwMuted)
                    VerboseDiagnosticRow("High power", when (highPower) { true -> "On"; false -> "Off"; null -> "unknown" }, if (highPower == true) HwHioneTeal else HwMuted)
                    VerboseDiagnosticRow("DAC control path", when (bridgeOk) { true -> "${Mascot.osName} system bridge"; false -> "bridge not installed, settings cannot apply"; null -> NA }, if (bridgeOk == true) Color.White else HwMuted)
                }
            }

            // Section 4: Live Controls & Disconnect
            item {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(25.dp))
                        .background(Brush.horizontalGradient(listOf(Mascot.color(0xFF6FB0FF), Color(0xFFFF6E40))))
                        .clickable {
                            scope.launch {
                                UsbDacManager.setUsbDacMode(ctx, false)
                                onExit()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "DISCONNECT & EXIT USB DAC MODE",
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun formatKhz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format("%.1f kHz", hz / 1000f)

@Composable
fun VerboseDiagnosticRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = HwMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}
