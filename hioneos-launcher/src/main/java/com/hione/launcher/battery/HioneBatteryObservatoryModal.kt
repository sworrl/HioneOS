package com.hione.launcher.battery
import com.hione.launcher.Mascot
import com.hione.launcher.mascotPainter
import com.hione.launcher.*

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
import com.hione.launcher.R
import com.hione.launcher.AudiowideFont
import com.hione.launcher.CyberGlassBorder
import com.hione.launcher.HioneCyan
import com.hione.launcher.HioneNeonPink
import com.hione.launcher.HioneTextSecondary
import com.hione.launcher.ui.swipeUpFromBottomToDismiss
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
    if (isCharging) return com.hione.launcher.ui.HioneIdentity.Leek
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
 * Battery telemetry as actually read on this boot. Every numeric field is 0 / -1 / blank when its
 * source (sticky battery broadcast, BatteryManager properties, power_supply sysfs, thermal sysfs)
 * could not be read — the modal renders those as "—". Nothing here is seeded with a plausible
 * value, no current is estimated, and the chip/capacity fields say what was found.
 */
data class RealHardwareBatteryTelemetry(
    val level: Int = -1,
    val isCharging: Boolean = false,
    val isPlugged: Boolean = false,
    val status: String = "",
    val plugType: String = "",
    val voltageMv: Int = 0,
    val hasCurrent: Boolean = false,
    val currentMa: Int = 0,      // signed mA (negative = discharge); valid only when hasCurrent
    val powerMw: Int = 0,        // 0 when voltage or current is unknown
    val tempC: Float = 0f,       // 0 = unknown
    val chargerTempC: Float = 0f,
    val cpuTempC: Float = 0f,
    val health: String = "",
    val tech: String = "",
    val fuelGaugeChip: String = "",
    val chargerPmicChip: String = "",
    val maxChargeCurrentMa: Int = 0,
    val inputCurrentLimitMa: Int = 0,
    val termVoltageMv: Int = 0,
    val designCapacityMah: Int = 0,   // from charge_full_design; 0 = not exposed
    val remainingMah: Int = 0,        // from BATTERY_PROPERTY_CHARGE_COUNTER; 0 = not exposed
    val estTimeToEmptyMin: Int = 0,   // only when real current + real remaining charge exist
    val estTimeToFullMin: Int = 0
)

private fun readSysfsInt(path: String): Int? = try {
    val f = File(path)
    if (f.exists() && f.canRead()) f.readText().trim().toIntOrNull() else null
} catch (_: Throwable) { null }

private fun readSysfsText(path: String): String? = try {
    val f = File(path)
    if (f.exists() && f.canRead()) f.readText().trim() else null
} catch (_: Throwable) { null }

fun readRealHardwareBattery(context: Context): RealHardwareBatteryTelemetry {
    var level = -1
    var isCharging = false
    var isPlugged = false
    var status = ""
    var plugType = ""
    var voltageMv = 0
    var hasCurrent = false
    var currentMa = 0
    var tempC = 0f
    var chargerTempC = 0f
    var cpuTempC = 0f
    var health = ""
    var tech = ""
    var inputCurrentLimitMa = 0
    var maxChargeCurrentMa = 0
    var termVoltageMv = 0
    var designCapacityMah = 0
    var remainingMah = 0

    // 1. Sticky battery broadcast (always available unprivileged)
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
                else -> ""
            }
            val v = bIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (v > 0) voltageMv = if (v > 10000) v / 1000 else v
            val rawTemp = bIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (rawTemp != Int.MIN_VALUE && rawTemp > -300) tempC = rawTemp / 10f
            tech = bIntent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""

            val plugged = bIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            isPlugged = plugged != 0
            plugType = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "USB (AC charger)"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB (host port)"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                0 -> "On battery"
                else -> "Plugged (type $plugged)"
            }

            health = when (bIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over-voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                else -> ""
            }
        }
    } catch (_: Throwable) {}

    // 2. BatteryManager properties (unprivileged): instantaneous current + remaining charge
    try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (bm != null) {
            val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (cur != Int.MIN_VALUE && cur != 0) {
                // Vendors report µA or mA; normalise to mA. Sign convention: + = charging.
                currentMa = if (kotlin.math.abs(cur) > 10000) cur / 1000 else cur
                hasCurrent = true
            }
            val counterUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (counterUah != Int.MIN_VALUE && counterUah > 0) remainingMah = counterUah / 1000
        }
    } catch (_: Throwable) {}

    // 3. power_supply sysfs (whichever nodes this process may read). The chip names are only
    //    reported when their class node is actually present — never asserted.
    val fuelGaugeDir = listOf("/sys/class/power_supply/cw2015", "/sys/class/power_supply/battery")
        .firstOrNull { File(it).exists() }
    val chargerDir = listOf("/sys/class/power_supply/mp2731-charger", "/sys/class/power_supply/usb")
        .firstOrNull { File(it).exists() }
    if (fuelGaugeDir != null) {
        readSysfsInt("$fuelGaugeDir/voltage_now")?.takeIf { it > 1000 }?.let { voltageMv = it / 1000 }
        // deci-°C. It used to overwrite the already-good ACTION_BATTERY_CHANGED value with no
        // sanity gate at all, so a node reporting 0 (unsupported) erased a real temperature and a
        // milli-°C node would have rendered "350.0 °C".
        readSysfsInt("$fuelGaugeDir/temp")?.let { it / 10f }?.takeIf { it in 10f..115f }?.let { tempC = it }
        readSysfsInt("$fuelGaugeDir/charge_full_design")?.takeIf { it > 0 }?.let { designCapacityMah = it / 1000 }
        readSysfsInt("$fuelGaugeDir/charge_now")?.takeIf { it > 0 }?.let { if (remainingMah == 0) remainingMah = it / 1000 }
        if (!hasCurrent) readSysfsInt("$fuelGaugeDir/current_now")?.takeIf { it != 0 }?.let {
            currentMa = if (kotlin.math.abs(it) > 10000) it / 1000 else it; hasCurrent = true
        }
    }
    if (chargerDir != null) {
        if (!hasCurrent) readSysfsInt("$chargerDir/current_now")?.takeIf { it != 0 }?.let {
            currentMa = if (kotlin.math.abs(it) > 10000) it / 1000 else it; hasCurrent = true
        }
        readSysfsInt("$chargerDir/input_current_limit")?.takeIf { it > 0 }?.let { inputCurrentLimitMa = it / 1000 }
        readSysfsInt("$chargerDir/constant_charge_current_max")?.takeIf { it > 0 }?.let { maxChargeCurrentMa = it / 1000 }
        readSysfsInt("$chargerDir/constant_charge_voltage")?.takeIf { it > 0 }?.let { termVoltageMv = it / 1000 }
    }
    val fuelGaugeChip = when {
        fuelGaugeDir == null -> "Fuel gauge: not exposed"
        fuelGaugeDir.endsWith("cw2015") -> "CellWise CW2015 (sysfs)"
        else -> readSysfsText("$fuelGaugeDir/model_name")?.takeIf { it.isNotBlank() }?.let { "$it (sysfs)" } ?: "battery (sysfs)"
    }
    val chargerPmicChip = when {
        chargerDir == null -> "Charger IC: not exposed"
        chargerDir.endsWith("mp2731-charger") -> "MPS MP2731 (sysfs)"
        else -> "usb (sysfs)"
    }

    // 4. Thermal zones by TYPE (never a hard-coded zone index): hottest cpu* zone + the charger.
    try {
        var cpuMax = 0f
        for (i in 0..31) {
            val type = readSysfsText("/sys/class/thermal/thermal_zone$i/type") ?: continue
            val raw = readSysfsInt("/sys/class/thermal/thermal_zone$i/temp") ?: continue
            val deg = if (raw > 1000) raw / 1000f else raw.toFloat()
            if (deg !in 10f..115f) continue
            if (type.startsWith("cpu")) cpuMax = maxOf(cpuMax, deg)
            if (type.contains("charger") || type.contains("mp2731")) chargerTempC = deg
        }
        cpuTempC = cpuMax
    } catch (_: Throwable) {}

    // Status text from measured facts only.
    val isNetDrainOnUsb = isPlugged && hasCurrent && currentMa < -10
    val isEquilibrium = isPlugged && hasCurrent && kotlin.math.abs(currentMa) <= 10
    val refinedStatus = when {
        isNetDrainOnUsb -> "USB connected · net drain (${currentMa} mA)"
        isEquilibrium -> "USB connected · equilibrium"
        isPlugged && hasCurrent && currentMa > 10 -> "Charging (+${currentMa} mA)"
        isPlugged -> if (status.isNotBlank()) "USB connected · $status" else "USB connected"
        hasCurrent -> "Discharging (${currentMa} mA)"
        else -> status.ifBlank { "" }
    }

    val powerMw = if (voltageMv > 0 && hasCurrent) ((voltageMv.toFloat() * kotlin.math.abs(currentMa)) / 1000f).roundToInt() else 0

    val estTimeToEmptyMin = if (hasCurrent && currentMa < 0 && remainingMah > 0)
        ((remainingMah.toFloat() / kotlin.math.abs(currentMa)) * 60f).roundToInt() else 0
    val estTimeToFullMin = if (hasCurrent && currentMa > 10 && designCapacityMah > 0 && remainingMah > 0 && designCapacityMah > remainingMah)
        (((designCapacityMah - remainingMah).toFloat() / currentMa) * 60f).roundToInt() else 0

    return RealHardwareBatteryTelemetry(
        level = level,
        isCharging = isCharging,
        isPlugged = isPlugged,
        status = refinedStatus,
        plugType = plugType,
        voltageMv = voltageMv,
        hasCurrent = hasCurrent,
        currentMa = currentMa,
        powerMw = powerMw,
        tempC = tempC,
        chargerTempC = chargerTempC,
        cpuTempC = cpuTempC,
        health = health,
        tech = tech,
        fuelGaugeChip = fuelGaugeChip,
        chargerPmicChip = chargerPmicChip,
        maxChargeCurrentMa = maxChargeCurrentMa,
        inputCurrentLimitMa = inputCurrentLimitMa,
        termVoltageMv = termVoltageMv,
        designCapacityMah = designCapacityMah,
        remainingMah = remainingMah,
        estTimeToEmptyMin = estTimeToEmptyMin,
        estTimeToFullMin = estTimeToFullMin
    )
}

/**
 * Oki Hione Quantum Power Cell & Battery Observatory Cockpit Modal.
 * High-density telemetry utilizing real CW2015 / MP2731 hardware metrics with large typography and live histograms.
 */
@Composable
fun HioneBatteryObservatoryModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    var telemetry by remember { mutableStateOf(readRealHardwareBattery(ctx)) }
    // Histories start EMPTY and fill with real samples only (was seeded with 20 invented points).
    var currentHistory by remember { mutableStateOf(listOf<Int>()) }
    var voltageHistory by remember { mutableStateOf(listOf<Int>()) }

    LaunchedEffect(Unit) {
        while (true) {
            val t = withContext(Dispatchers.IO) { readRealHardwareBattery(ctx) }
            telemetry = t
            // SIGNED. It stored abs(), so the bar colour had to come from the INSTANTANEOUS
            // current instead — and the moment the user unplugged, the previous 24 s of charging
            // bars all repainted as discharge (and vice versa), asserting a direction for past
            // samples that was never recorded.
            if (t.hasCurrent) currentHistory = (currentHistory + t.currentMa).takeLast(20)
            if (t.voltageMv > 0) voltageHistory = (voltageHistory + t.voltageMv).takeLast(20)
            delay(1200L)
        }
    }

    val isNetDischargingOnUsb = telemetry.isPlugged && telemetry.hasCurrent && telemetry.currentMa < -10
    val levelKnown = telemetry.level >= 0
    val batteryColor = when {
        isNetDischargingOnUsb -> Color(0xFFFF9100)
        !levelKnown -> HioneTextSecondary
        else -> getBatteryColorForPercent(telemetry.level, telemetry.isCharging)
    }
    val tempF = (telemetry.tempC * 9f / 5f) + 32f
    val tempKnown = telemetry.tempC != 0f
    fun mah(v: Int) = if (v > 0) "$v" else "—"

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF502090E))
                .clickable { onDismissRequest() }
                // System-gesture-style dismiss: swipe up starting at the bottom edge of the modal.
                .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest),
            contentAlignment = Alignment.Center
        ) {
            // Full Screen 3D Chamfered Cockpit Container
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
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    // Hione Watermark
                    Image(
                        painter = mascotPainter(id = R.drawable.hione_pose_dance),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.Center),
                        alpha = 0.06f
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
                                        .size(38.dp)
                                        .clip(CutCornerShape(8.dp))
                                        .background(batteryColor.copy(alpha = 0.25f))
                                        .border(1.dp, batteryColor, CutCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (telemetry.isCharging) Icons.Default.Bolt else Icons.Default.BatteryChargingFull,
                                        contentDescription = null,
                                        tint = batteryColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "BATTERY",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont,
                                        letterSpacing = 0.8.sp
                                    )
                                    Text(
                                        text = "${telemetry.fuelGaugeChip} · ${telemetry.chargerPmicChip}",
                                        color = HioneCyan,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(34.dp)
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
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // ==========================================
                        // 2. HERO POWER CELL CARD WITH NET DRAIN/CHARGE AWARENESS
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
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = if (levelKnown) "${telemetry.level}%" else "—%",
                                            color = batteryColor,
                                            fontSize = 42.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = AudiowideFont
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            // charge_counter / charge_full_design when exposed; "—" otherwise.
                                            text = "${mah(telemetry.remainingMah)} / ${mah(telemetry.designCapacityMah)} mAh",
                                            color = Color.White.copy(alpha = 0.9f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AudiowideFont,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                    }
                                    Text(
                                        // Runtime / time-to-full only when REAL current and REAL charge
                                        // counters exist; otherwise the status alone, no estimate.
                                        text = when {
                                            isNetDischargingOnUsb -> "${telemetry.status}" +
                                                (if (telemetry.estTimeToEmptyMin > 0) " · ${telemetry.estTimeToEmptyMin / 60}h ${telemetry.estTimeToEmptyMin % 60}m to empty" else "")
                                            telemetry.status.contains("equilibrium") -> "${telemetry.status} · battery holding steady"
                                            telemetry.isCharging -> "${telemetry.status.ifBlank { "Charging" }}" +
                                                (if (telemetry.estTimeToFullMin > 0) " · ${telemetry.estTimeToFullMin}m to full" else " · time to full not measurable")
                                            telemetry.estTimeToEmptyMin > 0 -> "${telemetry.status} · ~${telemetry.estTimeToEmptyMin / 60}h ${telemetry.estTimeToEmptyMin % 60}m (${telemetry.powerMw} mW)"
                                            telemetry.status.isNotBlank() -> "${telemetry.status}" + (if (telemetry.powerMw > 0) " (${telemetry.powerMw} mW)" else "")
                                            else -> "Battery status not reported"
                                        },
                                        color = if (isNetDischargingOnUsb) Color(0xFFFF9100) else if (telemetry.isCharging) com.hione.launcher.ui.HioneIdentity.Leek else Color.White,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont,
                                        lineHeight = 14.sp
                                    )
                                }

                                // 7-Cell Vertical Meter
                                val cellColors = remember {
                                    listOf(
                                        Mascot.color(0xFF6FB0FF),
                                        Mascot.color(0xFFFFC56B),
                                        com.hione.launcher.ui.HioneIdentity.Leek,
                                        Color(0xFFAEEA00),
                                        com.hione.launcher.ui.HioneIdentity.Gold,
                                        Color(0xFFFF6D00),
                                        com.hione.launcher.ui.HioneIdentity.Coral
                                    )
                                }
                                val filledCount = if (!levelKnown) 0 else ((telemetry.level.coerceIn(0, 100) * 7 + 50) / 100).coerceIn(if (telemetry.level > 0) 1 else 0, 7)

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.5.dp)
                                ) {
                                    repeat(7) { segIdx ->
                                        val isLit = (7 - segIdx) <= filledCount
                                        val c = cellColors[segIdx]
                                        Box(
                                            modifier = Modifier
                                                .width(36.dp)
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(if (isLit) c else Color(0x22FFFFFF))
                                                .border(0.5.dp, if (isLit) c else Color(0x11FFFFFF), RoundedCornerShape(1.dp))
                                        )
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // 3. LIVE MULTI-CHANNEL HISTOGRAM: CURRENT & VOLTAGE
                        // ==========================================
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CutCornerShape(10.dp))
                                .background(Mascot.color(0xFF020E15))
                                .border(0.8.dp, batteryColor.copy(alpha = 0.45f), CutCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        // "REAL-TIME" only over an actual series; it used to head
                                        // a permanently empty canvas on a unit that exposes no
                                        // current node.
                                        text = if (currentHistory.isEmpty() && voltageHistory.isEmpty())
                                            "POWER AND VOLTAGE · NO SAMPLES YET"
                                        else "POWER AND VOLTAGE",
                                        color = batteryColor,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                    Text(
                                        text = if (telemetry.hasCurrent) "${if (telemetry.currentMa > 0) "+" else ""}${telemetry.currentMa} mA · ${if (telemetry.powerMw > 0) "${telemetry.powerMw} mW" else "— mW"}"
                                               else "current not exposed",
                                        color = if (telemetry.currentMa > 0) com.hione.launcher.ui.HioneIdentity.Leek else if (isNetDischargingOnUsb) Color(0xFFFF9100) else com.hione.launcher.ui.HioneIdentity.Gold,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = AudiowideFont
                                    )
                                }

                                Spacer(Modifier.height(6.dp))

                                // Real-time 20-bar power histogram Canvas
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                ) {
                                    val w = size.width
                                    val h = size.height
                                    // Fixed 20-slot axis; only real samples are drawn (right-aligned as they arrive).
                                    val slots = 20
                                    val barWidth = (w / slots) * 0.75f
                                    val barGap = (w / slots) * 0.25f
                                    val maxVal = (currentHistory.maxOfOrNull { kotlin.math.abs(it) } ?: 1).coerceAtLeast(1).toFloat()

                                    // Draw histogram bars
                                    val offset = slots - currentHistory.size
                                    currentHistory.forEachIndexed { i, curVal ->
                                        val idx = offset + i
                                        val barHeight = ((kotlin.math.abs(curVal).toFloat() / maxVal) * (h * 0.82f)).coerceIn(3f, h)
                                        val x = idx * (barWidth + barGap)
                                        val y = h - barHeight
                                        // Colour from THIS sample's own sign, not from whatever the
                                        // current happens to be right now.
                                        val barColor = if (curVal > 0) com.hione.launcher.ui.HioneIdentity.Leek
                                                       else if (telemetry.isPlugged) Color(0xFFFF9100)
                                                       else Mascot.color(0xFFFFC56B).copy(alpha = 0.75f)

                                        drawRect(
                                            color = barColor,
                                            topLeft = Offset(x, y),
                                            size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                                        )
                                    }

                                    // Voltage Curve Overlay (Electric Cyan)
                                    val voltPath = Path()
                                    val minV = 3600f
                                    val maxV = 4400f
                                    val step = w / (slots - 1)
                                    val vOffset = slots - voltageHistory.size
                                    voltageHistory.forEachIndexed { i, v ->
                                        val x = (vOffset + i) * step
                                        val y = (1f - ((v - minV) / (maxV - minV)).coerceIn(0f, 1f)) * (h * 0.80f)
                                        if (i == 0) voltPath.moveTo(x, y) else voltPath.lineTo(x, y)
                                    }
                                    if (voltageHistory.size >= 2) drawPath(
                                        path = voltPath,
                                        color = Mascot.color(0xFF6FB0FF).copy(alpha = 0.85f),
                                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                }
                            }
                        }

                        // ==========================================
                        // 4. 8-CARD DETAILED HARDWARE TELEMETRY GRID (LARGE FONTS)
                        // ==========================================
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Row 1: Voltage & Cell Power
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "PACK VOLTAGE",
                                    value = if (telemetry.voltageMv > 0) "${String.format(Locale.US, "%.3f", telemetry.voltageMv / 1000.0)} V" else "— V",
                                    sub = "Term: ${if (telemetry.termVoltageMv > 0) "${telemetry.termVoltageMv} mV" else "—"}",
                                    icon = Icons.Default.Speed,
                                    color = HioneCyan
                                )
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "POWER DRAW",
                                    value = if (telemetry.powerMw > 0) "${telemetry.powerMw} mW" else "— mW",
                                    sub = if (telemetry.powerMw > 0) "${String.format(Locale.US, "%.2f", telemetry.powerMw / 1000.0)} W total" else "needs V + I",
                                    icon = Icons.Default.ElectricBolt,
                                    color = com.hione.launcher.ui.HioneIdentity.Gold
                                )
                            }

                            // Row 2: Cell Thermal & PMIC Thermal
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "CELL TEMPERATURE",
                                    value = if (tempKnown) "${String.format(Locale.US, "%.1f", telemetry.tempC)}°C" else "—°C",
                                    sub = if (tempKnown) "${String.format(Locale.US, "%.1f", tempF)}°F · battery sensor" else "not reported",
                                    icon = Icons.Default.DeviceThermostat,
                                    color = if (!tempKnown) HioneTextSecondary else if (telemetry.tempC > 40f) com.hione.launcher.ui.HioneIdentity.Coral else com.hione.launcher.ui.HioneIdentity.Leek
                                )
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "CHARGER / CPU TEMP",
                                    value = if (telemetry.chargerTempC > 0f) "${String.format(Locale.US, "%.1f", telemetry.chargerTempC)}°C" else "—°C",
                                    sub = "CPU: ${if (telemetry.cpuTempC > 0f) "${String.format(Locale.US, "%.1f", telemetry.cpuTempC)}°C" else "—"}",
                                    icon = Icons.Default.Memory,
                                    color = if (telemetry.cpuTempC > 65f) com.hione.launcher.ui.HioneIdentity.Coral else HioneNeonPink
                                )
                            }

                            // Row 3: Fast Charge Limits & Health Index
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "USB INPUT LIMIT",
                                    value = if (telemetry.inputCurrentLimitMa > 0) "${telemetry.inputCurrentLimitMa} mA" else "— mA",
                                    sub = "Charge max: ${if (telemetry.maxChargeCurrentMa > 0) "${telemetry.maxChargeCurrentMa} mA" else "—"}",
                                    icon = Icons.Default.Usb,
                                    color = Color(0xFF2979FF)
                                )
                                RealBatteryMetricTile(
                                    modifier = Modifier.weight(1f),
                                    title = "BATTERY HEALTH",
                                    value = telemetry.health.ifBlank { "—" },
                                    sub = telemetry.tech.ifBlank { "chemistry not reported" },
                                    icon = Icons.Default.CheckCircle,
                                    color = if (telemetry.health == "Good") com.hione.launcher.ui.HioneIdentity.Leek else if (telemetry.health.isBlank()) HioneTextSecondary else com.hione.launcher.ui.HioneIdentity.Coral
                                )
                            }
                        }

                        // ==========================================
                        // 5. SOURCES & PLUG STATE (was a fabricated per-subsystem % split — the M500
                        //    exposes no per-rail power sensors, so that split cannot be measured)
                        // ==========================================
                        Column {
                            Text(
                                text = "SOURCES AND PLUG",
                                color = HioneCyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(Modifier.height(3.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(CutCornerShape(8.dp))
                                    .background(Mascot.color(0xFF03141C))
                                    .border(0.8.dp, CyberGlassBorder.copy(alpha = 0.4f), CutCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SubsystemDrainItem("PLUG", telemetry.plugType.ifBlank { "—" }, Color(0xFF7C4DFF))
                                SubsystemDrainItem("CURRENT", if (telemetry.hasCurrent) "measured" else "not exposed", HioneCyan)
                                SubsystemDrainItem("CHARGE CTR", if (telemetry.remainingMah > 0) "measured" else "not exposed", com.hione.launcher.ui.HioneIdentity.Gold)
                                SubsystemDrainItem("RAIL SPLIT", "not measurable", HioneNeonPink)
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
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    color = HioneTextSecondary,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sub,
                    color = color,
                    fontSize = 8.sp,
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
        Text(name, color = HioneTextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(percent, color = color, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
    }
}

