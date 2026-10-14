package com.hione.launcher.thermal

import com.hione.launcher.Mascot
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.*
import com.hione.launcher.ui.HionePowerProfile
import com.hione.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class ThermalZoneData(
    val id: Int,
    val name: String,
    val tempC: Float,
    val type: String,
    val subsystem: String
)

data class ThermalChronologicalPoint(
    val timestampMs: Long,
    // null = that channel had no readable sensor for this sample. These were Float and stored 0f,
    // which the waveform normalised to the bottom of the plot and stroked as a solid line — a flat
    // "sub-20 °C" trace under a legend dot reading CPU Peak / Battery, for a reading never taken.
    val cpuTempC: Float?,
    val batteryTempC: Float?,
    val maxTempC: Float
)

/**
 * Oki Hione Hardware Thermal Chronological Waveform & Histogram Observatory Modal.
 * Polls all 24 HiBy M500 Qualcomm Snapdragon & PMIC thermal zones (thermal_zone0..23)
 * with a live chronological rolling time-series graph, multi-bin thermal histogram,
 * and DVFS governor controls.
 */
@Composable
fun HioneThermalObservatoryModal(
    onClose: () -> Unit,
    cpuTempC: Float,
    batteryTempC: Float
) {
    val ctx = LocalContext.current
    var thermalZones by remember { mutableStateOf<List<ThermalZoneData>>(emptyList()) }
    var cpuFrequencies by remember { mutableStateOf<List<Long?>>(emptyList()) }
    // Real power mode/profile (Settings.Global) instead of a local, non-functional selector.
    val powerMode by com.hione.launcher.ui.rememberPowerMode()
    val powerProfile by com.hione.launcher.ui.rememberPowerProfile()
    // SoC identity from the platform (Build.SOC_MODEL on API 31+), never a literal chip name.
    val socLabel = remember {
        val m = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else null
        if (m.isNullOrBlank() || m == android.os.Build.UNKNOWN) "SoC" else m
    }

    // Rolling 30-sample chronological time-series buffer
    val chronologicalHistory = remember { mutableStateListOf<ThermalChronologicalPoint>() }

    // Dynamic Poller for all 24 Thermal Zones & CPU Frequencies
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                val zones = mutableListOf<ThermalZoneData>()
                for (i in 0..23) {
                    try {
                        val typeFile = File("/sys/class/thermal/thermal_zone$i/type")
                        val tempFile = File("/sys/class/thermal/thermal_zone$i/temp")
                        if (tempFile.exists() && tempFile.canRead()) {
                            val rawText = tempFile.readText().trim()
                            val rawTemp = rawText.toFloatOrNull() ?: continue
                            // Valid reading check (filter disconnected sensors, which report 0
                            // or a sentinel like -40000). Was `&&`: since rawTemp < -20000f
                            // already implies rawTemp <= 0f, the conjunction collapsed to the
                            // -20000 test and a sensor reporting exactly 0 was never rejected here.
                            if (rawTemp <= 0f || rawTemp < -20000f) continue

                            val degC = if (rawTemp > 1000f) rawTemp / 1000f else rawTemp
                            if (degC !in 10f..115f) continue

                            val zoneType = if (typeFile.exists() && typeFile.canRead()) typeFile.readText().trim() else "zone$i"
                            val (friendlyName, subsystem) = when {
                                zoneType.startsWith("cpu-1-") -> "Kryo Core #${zoneType.removePrefix("cpu-1-")}" to "CPU"
                                zoneType.startsWith("cpuss") -> "CPU Subsystem #${zoneType.removePrefix("cpuss-")}" to "CPU"
                                zoneType == "gpu" -> "Adreno GPU Core" to "GPU"
                                zoneType == "cdsp-hvx" -> "Hexagon DSP / HVX" to "DSP"
                                zoneType == "mapss" -> "Audio Processor Subsystem" to "AUDIO"
                                zoneType == "display" -> "Display Driver" to "DISPLAY"
                                zoneType == "cw2015" -> "Battery Cell (cw2015)" to "BATTERY"
                                zoneType == "mp2731-charger" -> "MP2731 Charger PMIC" to "BATTERY"
                                zoneType == "wlan" -> "Wi-Fi Transceiver" to "RF"
                                zoneType.startsWith("mdm") -> "4G LTE Modem #${zoneType.removePrefix("mdm-")}" to "RF"
                                zoneType.contains("xo-therm") -> "Crystal Oscillator (XO)" to "SOC"
                                zoneType.contains("quiet-therm") -> "Chassis Ambient" to "SOC"
                                zoneType.contains("pa-therm") -> "RF Power Amp" to "RF"
                                else -> "Sensor $zoneType" to "OTHER"
                            }
                            zones.add(ThermalZoneData(i, friendlyName, degC, zoneType, subsystem))
                        }
                    } catch (_: Throwable) {}
                }

                // Enumerate the REAL cores and keep the list INDEXED BY CORE NUMBER (null =
                // that core's cpufreq node is missing/offline). It used to append only readable
                // cores to a flat list, so with cpu0 offline the UI printed cpu1's clock under
                // the label "C0" — and the panel always claimed exactly 4 cores regardless of
                // the SoC, because the empty case was a hardcoded List(4).
                val coreCount = try {
                    File("/sys/devices/system/cpu").listFiles()
                        ?.count { it.name.matches(Regex("cpu[0-9]+")) } ?: 0
                } catch (_: Throwable) { 0 }
                val freqs = mutableListOf<Long?>()
                for (core in 0 until coreCount) {
                    var mhz: Long? = null
                    try {
                        val freqFile = File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
                        if (freqFile.exists() && freqFile.canRead()) {
                            val khz = freqFile.readText().trim().toLongOrNull()
                            if (khz != null && khz > 0L) mhz = khz / 1000L
                        }
                    } catch (_: Throwable) {}
                    freqs.add(mhz)
                }

                thermalZones = zones
                cpuFrequencies = freqs

                // Record chronological point — ONLY from real readings (sysfs zones, or the host's
                // already-real cpu/battery values when > 0). No floor, no presumed temperature; a
                // sample with nothing readable is simply not recorded.
                val currentCpuMax = zones.filter { it.subsystem == "CPU" }.maxOfOrNull { it.tempC }
                    ?: cpuTempC.takeIf { it > 0f }
                val currentBatMax = zones.filter { it.subsystem == "BATTERY" }.maxOfOrNull { it.tempC }
                    ?: batteryTempC.takeIf { it > 0f }
                val peak = listOfNotNull(currentCpuMax, currentBatMax, zones.maxOfOrNull { it.tempC }).maxOrNull()

                if (peak != null) withContext(Dispatchers.Main) {
                    chronologicalHistory.add(
                        ThermalChronologicalPoint(
                            timestampMs = System.currentTimeMillis(),
                            cpuTempC = currentCpuMax,
                            batteryTempC = currentBatMax,
                            maxTempC = peak
                        )
                    )
                    if (chronologicalHistory.size > 30) {
                        chronologicalHistory.removeAt(0)
                    }
                }
            }
            delay(1200L)
        }
    }

    // null = no sensor has produced a reading yet → "—" and a neutral colour.
    val livePeakTemp: Float? = chronologicalHistory.lastOrNull()?.maxTempC
        ?: listOf(cpuTempC, batteryTempC).filter { it > 0f }.maxOrNull()
    val hasThermal = livePeakTemp != null
    val peakForColor = livePeakTemp ?: -1f

    val thermoclineColor = when {
        !hasThermal -> HioneTextSecondary                                  // No data: neutral
        peakForColor >= 58f -> com.hione.launcher.ui.HioneIdentity.Coral // Hot / Throttle Red
        peakForColor >= 48f -> Color(0xFFFF9100) // Warm Amber Orange
        peakForColor >= 38f -> com.hione.launcher.ui.HioneIdentity.Gold // Nominal Gold
        peakForColor >= 30f -> Mascot.color(0xFFFFC56B) // Cool Cyan
        else -> com.hione.launcher.ui.HioneIdentity.Leek                // Low Ambient Mint Green
    }

    BackHandler(enabled = true) { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xE60C0D10))
            .clickable { onClose() }
            // System-gesture-style dismiss: swipe up starting at the bottom edge of the modal.
            .swipeUpFromBottomToDismiss(onDismiss = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        // High-Tech Cyber Glass Container (Inset from physical screen edges)
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clickable(enabled = false) {}
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0xFF071922),
                            Mascot.color(0xFF041017),
                            Mascot.color(0xFA020A0E)
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.2.dp,
                        Brush.linearGradient(
                            listOf(
                                thermoclineColor.copy(alpha = 0.85f),
                                HioneCyan.copy(alpha = 0.6f),
                                HioneNeonPink.copy(alpha = 0.7f)
                            )
                        )
                    ),
                    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Grab Bar Handle
                Box(
                    Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(thermoclineColor.copy(alpha = 0.6f))
                )

                Spacer(Modifier.height(8.dp))

                // Header (Clean, no X button)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "TEMPERATURES",
                                color = thermoclineColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(thermoclineColor.copy(alpha = 0.2f))
                                    .border(0.8.dp, thermoclineColor, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    when {
                                        !hasThermal -> "NO SENSOR DATA"
                                        peakForColor >= 58f -> "THROTTLING"
                                        peakForColor >= 48f -> "WARM"
                                        else -> "NOMINAL"
                                    },
                                    color = thermoclineColor,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }
                        Text(
                            "${socLabel} thermal zones (${thermalZones.size} readable)",
                            color = HioneTextSecondary,
                            fontSize = 8.sp
                        )
                    }

                    // Live Peak Metric HUD
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (livePeakTemp != null) "${String.format("%.1f", livePeakTemp)}°C" else "—°C",
                            color = thermoclineColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            text = "PEAK CORE",
                            color = HioneTextSecondary,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 1: CHRONOLOGICAL ROLLING TIME-SERIES GRAPH (LAST 30s)
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(115.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Mascot.color(0xFF030D12))
                        .border(1.dp, CyberGlassBorder.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(8.dp)
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("HISTORY", color = HioneCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFF9100)))
                                    Spacer(Modifier.width(3.dp))
                                    Text("CPU Peak", color = Color(0xFFFF9100), fontSize = 7.sp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF00FF88)))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Battery", color = Color(0xFF00FF88), fontSize = 7.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Waveform Canvas
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            val w = size.width
                            val h = size.height
                            val pts = chronologicalHistory.toList()

                            // Grid Lines (30°C, 45°C, 60°C)
                            val y60 = h * (1f - (60f - 20f) / 60f)
                            val y45 = h * (1f - (45f - 20f) / 60f)
                            val y30 = h * (1f - (30f - 20f) / 60f)

                            drawLine(Color(0x22FF1744), Offset(0f, y60), Offset(w, y60), strokeWidth = 1f)
                            drawLine(Color(0x22FFD600), Offset(0f, y45), Offset(w, y45), strokeWidth = 1f)
                            drawLine(Mascot.color(0x22FFC56B), Offset(0f, y30), Offset(w, y30), strokeWidth = 1f)

                            if (pts.size >= 2) {
                                val dx = w / 29f
                                val cpuPath = Path()
                                val batPath = Path()
                                val fillPath = Path()
                                // A null channel is a GAP, not a zero: the pen lifts and the fill
                                // restarts. Previously every unreadable sample was drawn at 0 °C.
                                var cpuStarted = false
                                var batStarted = false
                                var fillOpen = false

                                pts.forEachIndexed { i, pt ->
                                    val x = (30 - pts.size + i) * dx
                                    val cpu = pt.cpuTempC
                                    val bat = pt.batteryTempC

                                    if (cpu != null) {
                                        val yCpu = h * (1f - ((cpu - 20f) / 60f).coerceIn(0f, 1f))
                                        if (!cpuStarted) { cpuPath.moveTo(x, yCpu); cpuStarted = true }
                                        else cpuPath.lineTo(x, yCpu)
                                        if (!fillOpen) {
                                            fillPath.moveTo(x, h); fillPath.lineTo(x, yCpu); fillOpen = true
                                        } else fillPath.lineTo(x, yCpu)
                                    } else if (fillOpen) {
                                        // close the fill at the last real x before the gap
                                        fillPath.lineTo(x - dx, h); fillPath.close(); fillOpen = false
                                        cpuStarted = false
                                    }

                                    if (bat != null) {
                                        val yBat = h * (1f - ((bat - 20f) / 60f).coerceIn(0f, 1f))
                                        if (!batStarted) { batPath.moveTo(x, yBat); batStarted = true }
                                        else batPath.lineTo(x, yBat)
                                    } else {
                                        batStarted = false
                                    }

                                    if (i == pts.size - 1 && fillOpen) {
                                        fillPath.lineTo(x, h)
                                        fillPath.close()
                                        fillOpen = false
                                    }
                                }

                                // Fill under CPU line
                                drawPath(
                                    path = fillPath,
                                    brush = Brush.verticalGradient(
                                        listOf(thermoclineColor.copy(alpha = 0.25f), Color.Transparent)
                                    )
                                )

                                // Draw CPU line
                                drawPath(
                                    path = cpuPath,
                                    color = Color(0xFFFF9100),
                                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                )

                                // Draw Battery line
                                drawPath(
                                    path = batPath,
                                    color = Color(0xFF00FF88),
                                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }

                        // Time Axis Labels
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 30 samples at the real 1.2 s poll = a 36 s window. The axis used to
                            // be labelled -30/-20/-10 s, so every time read off the chart was 20 % out.
                            Text("-36s", color = HioneTextSecondary, fontSize = 7.sp)
                            Text("-24s", color = HioneTextSecondary, fontSize = 7.sp)
                            Text("-12s", color = HioneTextSecondary, fontSize = 7.sp)
                            // "LIVE" only with a real sample — it used to label a blank chart.
                            Text(if (hasThermal) "LIVE" else "—", color = thermoclineColor, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 2: MULTI-BIN HARDWARE THERMAL HISTOGRAM
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Mascot.color(0xFF030D12))
                        .border(1.dp, CyberGlassBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("ALL SENSORS", color = HioneCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                            Text(
                                if (thermalZones.isEmpty()) "NO READABLE ZONES" else "${thermalZones.size} SENSORS READABLE",
                                color = if (thermalZones.isEmpty()) HioneTextSecondary else Color(0xFF00FF7F),
                                fontSize = 7.5.sp, fontFamily = AudiowideFont
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // Histogram Bars
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            // Real only — no fabricated zones when sysfs is blocked. Sorted by
                            // subsystem so the legend below describes the bar order truthfully.
                            val subsystemOrder = listOf("CPU", "GPU", "DSP", "AUDIO", "BATTERY", "RF", "SOC", "DISPLAY", "OTHER")
                            val displayZones = thermalZones.sortedBy { subsystemOrder.indexOf(it.subsystem).let { i -> if (i < 0) 99 else i } }

                            if (displayZones.isEmpty()) {
                                Text("thermal sysfs not readable from this process", color = HioneTextSecondary, fontSize = 7.5.sp)
                            }
                            displayZones.forEach { zone ->
                                val normHeight = ((zone.tempC - 25f) / 50f).coerceIn(0.15f, 1.0f)
                                val barColor = when {
                                    zone.tempC >= 55f -> com.hione.launcher.ui.HioneIdentity.Coral
                                    zone.tempC >= 48f -> Color(0xFFFF9100)
                                    zone.tempC >= 38f -> com.hione.launcher.ui.HioneIdentity.Gold
                                    else -> Mascot.color(0xFFFFC56B)
                                }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .padding(horizontal = 0.8.dp)
                                        .fillMaxHeight(normHeight)
                                        .clip(RoundedCornerShape(topStart = 1.5.dp, topEnd = 1.5.dp))
                                        .background(Brush.verticalGradient(listOf(barColor, barColor.copy(alpha = 0.25f))))
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Legend: real per-subsystem counts of the readable zones (bar order matches).
                        val counts = thermalZones.groupingBy { it.subsystem }.eachCount()
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("CPU ×${counts["CPU"] ?: 0}", color = Color(0xFFFF9100), fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                            Text("GPU/DSP ×${(counts["GPU"] ?: 0) + (counts["DSP"] ?: 0)}", color = HioneCyan, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                            Text("AUDIO ×${counts["AUDIO"] ?: 0}", color = Mascot.color(0xFFB9C2D0), fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                            Text("BATTERY ×${counts["BATTERY"] ?: 0}", color = Color(0xFF00FF88), fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                            Text("RF ×${counts["RF"] ?: 0}", color = HioneNeonPink, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                            Text("OTHER ×${(counts["SOC"] ?: 0) + (counts["DISPLAY"] ?: 0) + (counts["OTHER"] ?: 0)}", color = HioneTextSecondary, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 3: HARDWARE DVFS CPU CLUSTERS & SENSOR TOPOLOGY
                // ============================================================
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Mascot.color(0xFF05131A))
                        .border(1.dp, CyberGlassBorder.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(8.dp)
                ) {
                    Column {
                        Text("CPU CLOCKS (${socLabel.uppercase()})", color = HioneCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // cpufreq sysfs only; unreadable cores show "—" instead of a presumed clock.
                            // Every core the device actually has. Empty = cpufreq is not readable
                            // from this process at all, and the row says so rather than drawing
                            // four invented "C0..C3" boxes.
                            cpuFrequencies.forEachIndexed { i, freq ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Mascot.color(0xFF030A0E))
                                        .border(0.8.dp, HioneCyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("C$i", color = HioneTextSecondary, fontSize = 7.sp)
                                        Text(if (freq != null && freq > 0L) "${freq}M" else "—", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                    }
                                }
                            }
                            if (cpuFrequencies.isEmpty()) {
                                Text(
                                    "cpufreq not readable from this process",
                                    color = HioneTextSecondary,
                                    fontSize = 7.5.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ============================================================
                // TIER 4: POWER MODE — wired to the REAL Settings.Global hione_power_mode that Hione
                // Music's governor honours (save / auto / perf). Was a dead selector that stored a
                // local string and controlled nothing.
                // ============================================================
                Text(
                    "POWER MODE (hione_power_mode · live: ${HionePowerProfile.modeLabel(powerMode, powerProfile)})",
                    color = HioneCyan,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(6.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Triple("save", "Battery Saver", Mascot.color(0xFFFFC56B)),
                        Triple("auto", "Auto", com.hione.launcher.ui.HioneIdentity.Gold),
                        Triple("perf", "Performance", com.hione.launcher.ui.HioneIdentity.Coral)
                    ).forEach { (mode, label, color) ->
                        val isSelected = powerMode == mode
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) color.copy(alpha = 0.22f) else Mascot.color(0xFF041017))
                                .border(1.dp, if (isSelected) color else CyberGlassBorder.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .clickable { HionePowerProfile.setMode(ctx, mode) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (isSelected) color else HioneTextSecondary,
                                fontSize = 8.5.sp,
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
