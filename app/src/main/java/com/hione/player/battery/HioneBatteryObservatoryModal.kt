package com.hione.player.battery

import com.hione.player.Mascot
import com.hione.player.mascotPainter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.player.R
import com.hione.player.AudiowideFont
import com.hione.player.CyberGlassBorder
import com.hione.player.HioneCyan
import com.hione.player.HioneNeonPink
import com.hione.player.HioneTextSecondary
import com.hione.player.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Maps battery percentage (0..100) to its precise per-percentage-point spectral hue.
 */
fun getBatteryColorForPercent(pct: Int, isCharging: Boolean): Color {
    if (isCharging) return Color(0xFF00E676)
    val p = pct.coerceIn(0, 100)
    val hue = when {
        p <= 15 -> (p / 15f) * 20f // 0 (Crimson Red) to 20 (Orange-Red)
        p <= 35 -> 20f + ((p - 15) / 20f) * 25f // 20 (Orange) to 45 (Amber)
        p <= 55 -> 45f + ((p - 35) / 20f) * 25f // 45 (Amber) to 70 (Electric Yellow)
        p <= 80 -> 70f + ((p - 55) / 25f) * 110f // 70 (Yellow) to 180 (Cyan)
        else -> 180f - ((p - 80) / 20f) * 35f // 180 (Cyan) to 145 (Spring Emerald Green)
    }
    return Color.hsv(hue, 0.92f, 1.0f)
}

/**
 * Real battery telemetry only. Every field is nullable: null means the source (BATTERY_CHANGED,
 * the fuel-gauge / charger power_supply sysfs nodes, the thermal zones) did not report it, and
 * the UI shows "—" for it. Nothing here is estimated, defaulted or modelled.
 */
data class RealHardwareBatteryTelemetry(
    val level: Int? = null,
    val isCharging: Boolean = false,
    val status: String? = null,
    val plugType: String? = null,
    val voltageMv: Int? = null,
    val currentMa: Int? = null, // mA (negative = discharge), from the charger/fuel-gauge sysfs only
    val powerMw: Int? = null,   // mW, derived only when BOTH voltage and current are real
    val tempC: Float? = null,
    val chargerTempC: Float? = null,
    val cpuTempC: Float? = null,
    val health: String? = null,
    val tech: String? = null,
    val fuelGaugeChip: String? = null,   // present only when its power_supply node exists
    val chargerPmicChip: String? = null,
    val maxChargeCurrentMa: Int? = null,
    val inputCurrentLimitMa: Int? = null,
    val termVoltageMv: Int? = null,
    val designCapacityMah: Int? = null,  // charge_full_design from sysfs, else unknown
    val remainingMah: Int? = null,       // charge_now from sysfs, else unknown
    val estTimeToEmptyMin: Int? = null,  // BatteryManager.computeChargeTimeRemaining / real current only
    val estTimeToFullMin: Int? = null
)

private fun readSysfsLong(path: String): Long? = try {
    val f = File(path)
    if (f.exists()) f.readText().trim().toLongOrNull() else null
} catch (_: Throwable) { null }

private fun readSysfsText(path: String): String? = try {
    val f = File(path)
    if (f.exists()) f.readText().trim().takeIf { it.isNotEmpty() } else null
} catch (_: Throwable) { null }

/** First directory under /sys/class/power_supply whose name contains any of [needles]. */
private fun findPowerSupply(vararg needles: String): File? = try {
    File("/sys/class/power_supply").listFiles()?.firstOrNull { d ->
        needles.any { d.name.contains(it, ignoreCase = true) }
    }
} catch (_: Throwable) { null }

/** First thermal zone whose `type` matches [pred]; resolved by name, never by a fixed index. */
private fun findThermalZone(pred: (String) -> Boolean): File? = try {
    File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.firstOrNull { z ->
        val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrDefault("")
        pred(type)
    }?.let { File(it, "temp") }
} catch (_: Throwable) { null }

private fun readThermalC(zone: File?): Float? {
    val raw = runCatching { zone?.takeIf { it.exists() }?.readText()?.trim()?.toFloatOrNull() }.getOrNull() ?: return null
    return if (raw > 1000f) raw / 1000f else raw
}

fun readRealHardwareBattery(context: Context): RealHardwareBatteryTelemetry {
    var level: Int? = null
    var isCharging = false
    var status: String? = null
    var plugType: String? = null
    var voltageMv: Int? = null
    var currentMa: Int? = null
    var tempC: Float? = null
    var health: String? = null
    var tech: String? = null
    var inputCurrentLimitMa: Int? = null
    var maxChargeCurrentMa: Int? = null
    var termVoltageMv: Int? = null
    var designCapacityMah: Int? = null
    var remainingMah: Int? = null

    // 1. Sticky BATTERY_CHANGED — the framework's own reading.
    try {
        val bIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bIntent != null) {
            val rawLevel = bIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (rawLevel >= 0 && scale > 0) level = (rawLevel * 100) / scale
            val st = bIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            status = when (st) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                else -> null
            }
            bIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }?.let { voltageMv = it }
            bIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { tempC = it / 10f }
            tech = bIntent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }

            val plugged = bIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            plugType = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "Type-C AC charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "Type-C USB host"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                0 -> "On battery"
                else -> null
            }

            health = when (bIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                else -> null
            }
        }
    } catch (_: Throwable) {}

    // 2. Fuel gauge power_supply node (CW2015 on this board) — only what it actually exposes.
    val gauge = findPowerSupply("cw2015", "bms", "fuel")
    if (gauge != null) {
        readSysfsLong("${gauge.path}/voltage_now")?.takeIf { it > 1000 }?.let { voltageMv = (it / 1000).toInt() }
        readSysfsLong("${gauge.path}/temp")?.let { tempC = it / 10f }
        readSysfsLong("${gauge.path}/charge_full_design")?.takeIf { it > 0 }?.let { designCapacityMah = (it / 1000).toInt() }
        readSysfsLong("${gauge.path}/charge_now")?.takeIf { it > 0 }?.let { remainingMah = (it / 1000).toInt() }
        readSysfsLong("${gauge.path}/current_now")?.takeIf { it != 0L }?.let { currentMa = (it / 1000).toInt() }
    }

    // 3. Switch-charger power_supply node (MP2731 on this board).
    val charger = findPowerSupply("mp2731", "charger")
    if (charger != null) {
        readSysfsLong("${charger.path}/current_now")?.takeIf { it != 0L }?.let { currentMa = (it / 1000).toInt() }
        readSysfsLong("${charger.path}/input_current_limit")?.takeIf { it > 0 }?.let { inputCurrentLimitMa = (it / 1000).toInt() }
        readSysfsLong("${charger.path}/constant_charge_current_max")?.takeIf { it > 0 }?.let { maxChargeCurrentMa = (it / 1000).toInt() }
        readSysfsLong("${charger.path}/constant_charge_voltage")?.takeIf { it > 0 }?.let { termVoltageMv = (it / 1000).toInt() }
    }

    // 3b. Generic "battery" node as a further real source for capacity / current.
    val batteryNode = findPowerSupply("battery")
    if (batteryNode != null) {
        if (designCapacityMah == null) readSysfsLong("${batteryNode.path}/charge_full_design")?.takeIf { it > 0 }?.let { designCapacityMah = (it / 1000).toInt() }
        if (remainingMah == null) readSysfsLong("${batteryNode.path}/charge_now")?.takeIf { it > 0 }?.let { remainingMah = (it / 1000).toInt() }
        if (currentMa == null) readSysfsLong("${batteryNode.path}/current_now")?.takeIf { it != 0L }?.let { currentMa = (it / 1000).toInt() }
    }
    // BatteryManager's own current property (µA) is a real reading too; use it if sysfs had none.
    if (currentMa == null) runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (ua != 0 && ua != Int.MIN_VALUE) currentMa = ua / 1000
    }
    if (remainingMah == null) runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (uah > 0 && uah != Int.MIN_VALUE) remainingMah = uah / 1000
    }

    // 4. Thermal zones resolved by type name (hottest cpu zone; the charger PMIC zone).
    val cpuTempC = try {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.filter { z -> runCatching { File(z, "type").readText().trim().lowercase() }.getOrDefault("").let { it.startsWith("cpu") } }
            ?.mapNotNull { readThermalC(File(it, "temp")) }?.maxOrNull()
    } catch (_: Throwable) { null }
    val chargerTempC = readThermalC(findThermalZone { it.contains("mp2731") || it.contains("charger") || it.contains("chg") })

    val powerMw = if (voltageMv != null && currentMa != null) ((voltageMv!!.toFloat() * kotlin.math.abs(currentMa!!)) / 1000f).roundToInt() else null

    // Time estimates: the framework's own estimate when it has one, otherwise a pure
    // (real remaining charge / real measured current) ratio — never a guessed current.
    var estTimeToEmptyMin: Int? = null
    var estTimeToFullMin: Int? = null
    if (isCharging) {
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val ms = bm.computeChargeTimeRemaining()
            if (ms > 0) estTimeToFullMin = (ms / 60_000L).toInt()
        }
        if (estTimeToFullMin == null && designCapacityMah != null && remainingMah != null && currentMa != null && currentMa!! > 0) {
            estTimeToFullMin = (((designCapacityMah!! - remainingMah!!).toFloat() / currentMa!!) * 60f).roundToInt().coerceAtLeast(0)
        }
    } else if (remainingMah != null && currentMa != null && currentMa!! < 0) {
        estTimeToEmptyMin = ((remainingMah!!.toFloat() / kotlin.math.abs(currentMa!!)) * 60f).roundToInt().coerceAtLeast(0)
    }

    return RealHardwareBatteryTelemetry(
        level = level,
        isCharging = isCharging,
        status = status,
        plugType = plugType,
        voltageMv = voltageMv,
        currentMa = currentMa,
        powerMw = powerMw,
        tempC = tempC,
        chargerTempC = chargerTempC,
        cpuTempC = cpuTempC,
        health = health,
        tech = tech,
        fuelGaugeChip = gauge?.let { readSysfsText("${it.path}/model_name") ?: it.name },
        chargerPmicChip = charger?.let { readSysfsText("${it.path}/model_name") ?: it.name },
        maxChargeCurrentMa = maxChargeCurrentMa,
        inputCurrentLimitMa = inputCurrentLimitMa,
        termVoltageMv = termVoltageMv,
        designCapacityMah = designCapacityMah,
        remainingMah = remainingMah,
        estTimeToEmptyMin = estTimeToEmptyMin,
        estTimeToFullMin = estTimeToFullMin
    )
}

private const val NA = "—"
private fun Int?.orNa(suffix: String = ""): String = this?.let { "$it$suffix" } ?: NA
private fun Float?.fmt1(suffix: String = ""): String = this?.let { String.format(Locale.US, "%.1f", it) + suffix } ?: NA
private fun minutesLabel(min: Int?): String = min?.let { "${it / 60}h ${it % 60}m" } ?: NA

/**
 * Oki Hione Quantum Power Cell & Battery Observatory Cockpit Modal.
 * 100% full-screen high-density telemetry utilizing real CW2015 / MP2731 hardware metrics.
 */
@Composable
fun HioneBatteryObservatoryModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    var telemetry by remember { mutableStateOf(readRealHardwareBattery(ctx)) }
    // Sparkline history starts EMPTY and fills only with real samples (no seeded fake curve).
    var currentHistory by remember { mutableStateOf(listOf<Int>()) }
    var voltageHistory by remember { mutableStateOf(listOf<Int>()) }
    // The profile selector drives the REAL power governor (Settings.Global hione_power_mode).
    LaunchedEffect(Unit) { com.hione.player.HionePowerGovernor.init(ctx) }
    val governorMode = com.hione.player.HionePowerGovernor.mode

    LaunchedEffect(Unit) {
        while (true) {
            val t = withContext(Dispatchers.IO) { readRealHardwareBattery(ctx) }
            telemetry = t
            t.currentMa?.let { ma -> currentHistory = (currentHistory + kotlin.math.abs(ma)).takeLast(12) }
            t.voltageMv?.let { mv -> voltageHistory = (voltageHistory + mv).takeLast(12) }
            delay(1200L)
        }
    }

    val batteryColor = getBatteryColorForPercent(telemetry.level ?: 0, telemetry.isCharging)
    val tempF = telemetry.tempC?.let { (it * 9f / 5f) + 32f }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF502090E))
                .clickable { onDismissRequest() }
                .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest),
            contentAlignment = Alignment.Center
        ) {
            // Full Screen 3D Chamfered Cockpit Container (Uses 98% Width & 97% Height)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.98f)
                    .fillMaxHeight(0.97f)
                    .clip(CutCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.25f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp)
                        .clip(CutCornerShape(15.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Mascot.color(0xF8051923),
                                    Mascot.color(0xF8030F16),
                                    Mascot.color(0xFF010609)
                                )
                            )
                        )
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        batteryColor.copy(alpha = 0.95f),
                                        CyberGlassBorder.copy(alpha = 0.4f),
                                        HioneNeonPink.copy(alpha = 0.75f)
                                    )
                                )
                            ),
                            CutCornerShape(15.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    // Hione Pose Cyber Watermark
                    Image(
                        painter = mascotPainter(id = R.drawable.hione_pose_dance),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.Center),
                        alpha = 0.08f
                    )

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // ==========================================
                        // 1. TOP HEADER BAR WITH HARDWARE IC CHIP IDS
                        // ==========================================
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CutCornerShape(8.dp))
                                        .background(batteryColor.copy(alpha = 0.25f))
                                        .border(1.dp, batteryColor, CutCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (telemetry.isCharging) Icons.Default.Bolt else Icons.Default.BatteryChargingFull,
                                        contentDescription = null,
                                        tint = batteryColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "BATTERY",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont,
                                        letterSpacing = 0.8.sp
                                    )
                                    Text(
                                        text = "HW: ${telemetry.fuelGaugeChip ?: "gauge —"} · ${telemetry.chargerPmicChip ?: "charger —"}",
                                        color = HioneCyan,
                                        fontSize = 7.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Mascot.color(0xFF071B24))
                                    .border(BorderStroke(1.dp, HioneNeonPink.copy(alpha = 0.6f)), CircleShape)
                                    .clickable { onDismissRequest() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = HioneNeonPink,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // ==========================================
                        // 2. HERO POWER CELL CARD WITH 7-CELL SPECTRAL DISPLAY
                        // ==========================================
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CutCornerShape(12.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            batteryColor.copy(alpha = 0.22f),
                                            Mascot.color(0xFF031118)
                                        )
                                    )
                                )
                                .border(1.dp, batteryColor.copy(alpha = 0.8f), CutCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = telemetry.level?.let { "$it%" } ?: NA,
                                            color = batteryColor,
                                            fontSize = 38.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = AudiowideFont
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "${telemetry.remainingMah.orNa()} / ${telemetry.designCapacityMah.orNa()} mAh",
                                            color = Color.White.copy(alpha = 0.9f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AudiowideFont,
                                            modifier = Modifier.padding(bottom = 6.dp)
                                        )
                                    }
                                    Text(
                                        text = if (telemetry.isCharging) "${telemetry.status ?: "Charging"} · ${telemetry.estTimeToFullMin?.let { "${it}m to full" } ?: "time to full —"}"
                                               else "${telemetry.status ?: "On battery"} · runtime ${minutesLabel(telemetry.estTimeToEmptyMin)} · ${telemetry.powerMw.orNa(" mW")}",
                                        color = if (telemetry.isCharging) Color(0xFF00E676) else Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                }

                                // 7-Cell Vertical Glowing Quantum Core Meter
                                val cellColors = remember {
                                    listOf(
                                        Mascot.color(0xFF6FB0FF), // Cell 7 (85-100%)
                                        Mascot.color(0xFFFFC56B), // Cell 6 (71-85%)
                                        Color(0xFF00E676), // Cell 5 (57-71%)
                                        Color(0xFFAEEA00), // Cell 4 (42-57%)
                                        Color(0xFFFFD600), // Cell 3 (28-42%)
                                        Color(0xFFFF6D00), // Cell 2 (14-28%)
                                        Color(0xFFFF1744)  // Cell 1 (0-14%)
                                    )
                                }
                                val lvl = telemetry.level ?: 0
                                val filledCount = ((lvl.coerceIn(0, 100) * 7 + 50) / 100).coerceIn(if (lvl > 0) 1 else 0, 7)

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    repeat(7) { segIdx ->
                                        val isLit = (7 - segIdx) <= filledCount
                                        val c = cellColors[segIdx]
                                        Box(
                                            modifier = Modifier
                                                .width(32.dp)
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(if (isLit) c else Color(0x22FFFFFF))
                                                .border(0.5.dp, if (isLit) c else Color(0x11FFFFFF), RoundedCornerShape(1.dp))
                                        )
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // 3. LIVE MULTI-CHANNEL SPARKLINE: CURRENT & VOLTAGE
                        // ==========================================
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CutCornerShape(10.dp))
                                .background(Mascot.color(0xFF020E15))
                                .border(0.8.dp, batteryColor.copy(alpha = 0.45f), CutCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (currentHistory.isEmpty() && voltageHistory.isEmpty()) "VOLTAGE & CURRENT (NO SAMPLES YET)" else "VOLTAGE & CURRENT (LAST ${maxOf(currentHistory.size, voltageHistory.size)} SAMPLES)",
                                        color = batteryColor,
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                    Text(
                                        text = "${telemetry.currentMa?.let { (if (it > 0) "+" else "") + it } ?: NA} mA · ${telemetry.powerMw.orNa(" mW")}",
                                        color = if (telemetry.isCharging) Color(0xFF00E676) else Color(0xFFFFD600),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                }

                                Spacer(Modifier.height(4.dp))

                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                ) {
                                    val w = size.width
                                    val h = size.height

                                    // Baseline zero grid line
                                    drawLine(
                                        color = Color(0x33FFFFFF),
                                        start = Offset(0f, h * 0.85f),
                                        end = Offset(w, h * 0.85f),
                                        strokeWidth = 0.8.dp.toPx()
                                    )

                                    // Current curve — only real samples; a single sample draws a dot, none draws nothing.
                                    if (currentHistory.isNotEmpty()) {
                                        val maxVal = (currentHistory.maxOrNull() ?: 1).coerceAtLeast(1)
                                        val step = w / (currentHistory.size - 1).coerceAtLeast(1)
                                        val currentPath = Path()
                                        currentHistory.forEachIndexed { idx, v ->
                                            val x = idx * step
                                            val y = (1f - (v.toFloat() / maxVal)) * (h * 0.85f)
                                            if (idx == 0) currentPath.moveTo(x, y) else currentPath.lineTo(x, y)
                                        }
                                        if (currentHistory.size == 1) {
                                            drawCircle(if (telemetry.isCharging) Color(0xFF00E676) else Color(0xFFFF9100), radius = 2.dp.toPx(), center = Offset(0f, (1f - (currentHistory[0].toFloat() / maxVal)) * (h * 0.85f)))
                                        } else drawPath(
                                            path = currentPath,
                                            color = if (telemetry.isCharging) Color(0xFF00E676) else Color(0xFFFF9100),
                                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                        )
                                    }

                                    // Voltage curve overlay — scaled to the real sampled min/max.
                                    if (voltageHistory.size >= 2) {
                                        val step = w / (voltageHistory.size - 1).coerceAtLeast(1)
                                        val minV = (voltageHistory.minOrNull() ?: 0) - 5f
                                        val maxV = (voltageHistory.maxOrNull() ?: 1) + 5f
                                        val voltPath = Path()
                                        voltageHistory.forEachIndexed { idx, v ->
                                            val x = idx * step
                                            val y = (1f - ((v - minV) / (maxV - minV)).coerceIn(0f, 1f)) * (h * 0.85f)
                                            if (idx == 0) voltPath.moveTo(x, y) else voltPath.lineTo(x, y)
                                        }
                                        drawPath(
                                            path = voltPath,
                                            color = HioneCyan.copy(alpha = 0.7f),
                                            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                                        )
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // 4. 8-CARD DETAILED HARDWARE TELEMETRY GRID
                        // ==========================================
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Row 1: Voltage & Cell Power
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "PACK VOLTAGE",
                                    value = telemetry.voltageMv?.let { "${String.format(Locale.US, "%.3f", it / 1000.0)} V" } ?: NA,
                                    sub = "Term: ${telemetry.termVoltageMv.orNa(" mV")}",
                                    icon = Icons.Default.Speed,
                                    color = HioneCyan
                                )
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "POWER DRAW",
                                    value = telemetry.powerMw.orNa(" mW"),
                                    sub = telemetry.powerMw?.let { "${String.format(Locale.US, "%.2f", it / 1000.0)} W" } ?: "current not reported",
                                    icon = Icons.Default.ElectricBolt,
                                    color = Color(0xFFFFD600)
                                )
                            }

                            // Row 2: Cell Thermal & PMIC Thermal
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "CELL TEMPERATURE",
                                    value = telemetry.tempC.fmt1("°C"),
                                    sub = tempF?.let { String.format(Locale.US, "%.1f", it) + "°F" } ?: "not reported",
                                    icon = Icons.Default.DeviceThermostat,
                                    color = if ((telemetry.tempC ?: 0f) > 40f) Color(0xFFFF1744) else Color(0xFF00E676)
                                )
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "CHARGER / CPU TEMP",
                                    value = telemetry.chargerTempC.fmt1("°C"),
                                    sub = "CPU: ${telemetry.cpuTempC.fmt1("°C")}",
                                    icon = Icons.Default.Memory,
                                    color = if ((telemetry.cpuTempC ?: 0f) > 65f) Color(0xFFFF1744) else HioneNeonPink
                                )
                            }

                            // Row 3: Charge Limits & Health
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "INPUT CURRENT LIMIT",
                                    value = telemetry.inputCurrentLimitMa.orNa(" mA"),
                                    sub = "Charge max: ${telemetry.maxChargeCurrentMa.orNa(" mA")}",
                                    icon = Icons.Default.Usb,
                                    color = Color(0xFF2979FF)
                                )
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "BATTERY HEALTH",
                                    value = telemetry.health ?: NA,
                                    sub = telemetry.tech ?: "chemistry not reported",
                                    icon = Icons.Default.CheckCircle,
                                    color = Color(0xFF00E676)
                                )
                            }
                        }

                        // ==========================================
                        // 5. SUBSYSTEM POWER DISTRIBUTION — this hardware exposes no per-rail
                        //    power telemetry (no PMIC rail counters, no batterystats access from
                        //    here), so there is nothing real to show. Say so instead of inventing
                        //    percentages.
                        // ==========================================
                        Column {
                            Text(
                                text = "POWER BY SUBSYSTEM",
                                color = HioneCyan,
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(CutCornerShape(8.dp))
                                    .background(Mascot.color(0xFF03141C))
                                    .border(0.8.dp, CyberGlassBorder.copy(alpha = 0.4f), CutCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SubsystemDrainItem("DAC", NA, Color(0xFF7C4DFF))
                                SubsystemDrainItem("DISPLAY", NA, HioneCyan)
                                SubsystemDrainItem("SOC", NA, Color(0xFFFFD600))
                                SubsystemDrainItem("RADIO", NA, HioneNeonPink)
                            }
                            Text(
                                text = "per-rail power is not measurable on this hardware",
                                color = HioneTextSecondary,
                                fontSize = 6.sp
                            )
                        }

                        // ==========================================
                        // 6. POWER GOVERNOR — the real HionePowerGovernor override
                        //    (auto / perf / save), same switch as the Settings card and launcher tiles.
                        // ==========================================
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val profiles = listOf(
                                com.hione.player.HionePowerGovernor.Mode.AUTO to "Auto",
                                com.hione.player.HionePowerGovernor.Mode.PERF to "Performance",
                                com.hione.player.HionePowerGovernor.Mode.SAVE to "Battery Saver"
                            )
                            profiles.forEach { (m, prof) ->
                                val isSel = governorMode == m
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(CutCornerShape(6.dp))
                                        .background(if (isSel) HioneCyan.copy(alpha = 0.25f) else Mascot.color(0xFF031016))
                                        .border(0.8.dp, if (isSel) HioneCyan else CyberGlassBorder.copy(alpha = 0.3f), CutCornerShape(6.dp))
                                        .clickable { com.hione.player.HionePowerGovernor.setMode(ctx, m) }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = prof,
                                        color = if (isSel) Color.White else HioneTextSecondary,
                                        fontSize = 6.5.sp,
                                        fontWeight = if (isSel) FontWeight.Black else FontWeight.Bold,
                                        fontFamily = AudiowideFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RealBatteryMetricTile(
    title: String,
    value: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CutCornerShape(8.dp))
            .background(Mascot.color(0xFF041620))
            .border(0.8.dp, color.copy(alpha = 0.45f), CutCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = title,
                    color = HioneTextSecondary,
                    fontSize = 6.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sub,
                    color = color,
                    fontSize = 6.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun SubsystemDrainItem(name: String, percent: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = HioneTextSecondary, fontSize = 6.sp, fontWeight = FontWeight.Bold)
        Text(percent, color = color, fontSize = 8.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
    }
}

