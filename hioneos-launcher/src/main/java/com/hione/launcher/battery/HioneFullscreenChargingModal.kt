package com.hione.launcher.battery

import com.hione.launcher.Mascot
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.*
import com.hione.launcher.ui.swipeUpFromBottomToDismiss
import java.io.File

/**
 * Oki Hione Fullscreen Quantum Charging Modal.
 * High-tech holographic power reactor displaying real-time charging wattage,
 * battery cell temperature, MP2731 PMIC status, and fluid wave charge animations.
 */
@Composable
fun HioneFullscreenChargingModal(
    onDismiss: () -> Unit,
    batteryPct: Int,
    isCharging: Boolean
) {
    val ctx = LocalContext.current

    // Live Charging Telemetry — 0 = not read yet / not readable, rendered as "—". No seeded
    // 4.20 V / 1850 mA / 32.5 °C placeholders.
    var voltageMv by remember { mutableIntStateOf(0) }
    var currentMa by remember { mutableIntStateOf(0) }   // signed: negative = discharging
    var haveCurrentRead by remember { mutableStateOf(false) }
    var batteryTempC by remember { mutableFloatStateOf(0f) }
    var healthLabel by remember { mutableStateOf("") }

    // Read real battery telemetry: BatteryManager properties + the sticky battery broadcast
    // (both work unprivileged), with the power_supply sysfs nodes as an extra source when readable.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                if (bm != null) {
                    val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    if (cur != Int.MIN_VALUE && cur != 0) {
                        // KEEP THE SIGN. abs() was discarding it, so a plugged-but-net-draining
                        // device showed its drain as a positive green "charging" current.
                        val mag = kotlin.math.abs(cur)
                        val scaled = mag / (if (mag > 10000) 1000 else 1)
                        currentMa = if (cur < 0) -scaled else scaled
                        haveCurrentRead = true
                    }
                }
                val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (sticky != null) {
                    val v = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                    if (v > 0) voltageMv = if (v > 10000) v / 1000 else v
                    val t = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    if (t != Int.MIN_VALUE && t > -300) batteryTempC = t / 10f
                    healthLabel = when (sticky.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
                        BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
                        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                        BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
                        BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
                        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER-VOLTAGE"
                        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
                        else -> ""
                    }
                }
                val voltFile = File("/sys/class/power_supply/battery/voltage_now")
                if (voltFile.exists() && voltFile.canRead()) {
                    voltFile.readText().trim().toIntOrNull()?.takeIf { it > 0 }?.let { rawV ->
                        voltageMv = if (rawV > 10000) rawV / 1000 else rawV
                    }
                }
                val tempFile = File("/sys/class/power_supply/battery/temp")
                if (tempFile.exists() && tempFile.canRead()) {
                    // This node is unambiguously deci-°C. The old `if (rawT > 100f)` heuristic
                    // meant any genuinely cold cell (≤ 10.0 °C, i.e. raw ≤ 100) was printed
                    // straight through as up to "100.0°C • HOT".
                    tempFile.readText().trim().toFloatOrNull()?.let { rawT ->
                        val c = rawT / 10f
                        if (c in 10f..115f) batteryTempC = c
                    }
                }
            } catch (_: Throwable) {}
            kotlinx.coroutines.delay(1200)
        }
    }

    val haveVoltage = voltageMv > 0
    val haveCurrent = haveCurrentRead && currentMa != 0
    val haveTemp = batteryTempC != 0f
    // Magnitude only for the wattage figure; the CURRENT card shows the signed value.
    val wattage = if (haveVoltage && haveCurrent)
        (voltageMv.toFloat() / 1000f) * (kotlin.math.abs(currentMa).toFloat() / 1000f) else 0f
    // batteryPct < 0 = the level could not be read (it used to arrive here as a confident 0).
    val pctKnown = batteryPct >= 0

    val infiniteTransition = rememberInfiniteTransition(label = "ChargingAnim")

    // Slow rotating cybernetic reactor ring
    val reactorAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ReactorAngle"
    )

    // Fluid wave horizontal offset
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WaveOffset"
    )

    // Pulsing quantum reactor glow
    val reactorGlow by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ReactorGlow"
    )

    BackHandler(enabled = true) { onDismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xF502090D))
            .clickable { onDismiss() }
            // System-gesture-style dismiss: swipe up starting at the bottom edge. Replaces the
            // old any-direction fling dismiss so a stray graze can't kill the modal.
            .swipeUpFromBottomToDismiss(onDismiss = onDismiss)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header: Hione Quantum Reactor Banner
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Top Dismiss Grab Handle
                Box(
                    Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(HioneCyan.copy(alpha = 0.7f))
                )

                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "CHARGING",
                        color = HioneCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    "Battery telemetry · BatteryManager + power_supply sysfs",
                    color = HioneTextSecondary,
                    fontSize = 8.5.sp
                )
            }

            // Center: Holographic Power Reactor & Fluid Wave Tank
            Box(
                Modifier
                    .size(230.dp),
                contentAlignment = Alignment.Center
            ) {
                // Layer 1: Rotating Concentric Plasma Energy Halo
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = reactorAngle }
                ) {
                    val strokeW = 3.dp.toPx()
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                HioneCyan,
                                HioneNeonPink,
                                Color(0xFF00FF88),
                                Mascot.color(0xFFB9C2D0),
                                HioneCyan
                            )
                        ),
                        radius = (size.width / 2f) - 4.dp.toPx(),
                        style = Stroke(width = strokeW)
                    )
                }

                // Layer 2: Quantum Wave Battery Chamber
                Box(
                    Modifier
                        .size(190.dp)
                        .scale(reactorGlow)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Mascot.color(0xFF041C24),
                                    Mascot.color(0xFF020E14),
                                    Mascot.color(0xFF010609)
                                )
                            )
                        )
                        .border(
                            BorderStroke(
                                2.dp,
                                Brush.linearGradient(
                                    listOf(
                                        HioneCyan,
                                        Color.White.copy(alpha = 0.8f),
                                        HioneNeonPink
                                    )
                                )
                            ),
                            CircleShape
                        )
                ) {
                    // Fluid Charging Wave Canvas
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        // Unknown level = empty tank (no fluid), never a 5%-looking sliver.
                        // The clamp used to be 0.05..0.98, which made a real 0-4 % look identical
                        // to 5 % and a real 99-100 % look like 98 % — distorting exactly the
                        // readings that matter most.
                        val fillHeight = if (pctKnown)
                            h * (1f - (batteryPct / 100f).coerceIn(0f, 1f)) else h

                        val wavePath = Path().apply {
                            moveTo(0f, h)
                            lineTo(0f, fillHeight)
                            for (x in 0..w.toInt() step 10) {
                                val xNorm = x.toFloat() / w
                                val yOffset = kotlin.math.sin((xNorm + waveOffset) * 6.28f) * 6.dp.toPx()
                                lineTo(x.toFloat(), fillHeight + yOffset.toFloat())
                            }
                            lineTo(w, h)
                            close()
                        }

                        drawPath(
                            path = wavePath,
                            brush = Brush.verticalGradient(
                                listOf(
                                    HioneCyan.copy(alpha = 0.85f),
                                    Color(0xFF00B0FF).copy(alpha = 0.65f),
                                    Mascot.color(0xFF004D40)
                                )
                            )
                        )
                    }

                    // Numeric Percentage HUD in Center
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (pctKnown) "$batteryPct%" else "—%",
                            color = Color.White,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                        Text(
                            // The framework "charging" status is not the same thing as current
                            // flowing IN: on this device plugged-but-net-draining is a real,
                            // documented state, and this label used to shout "CHARGING" in green
                            // directly above a CURRENT card reading e.g. "-350mA".
                            text = when {
                                haveCurrent && currentMa > 10 -> "CHARGING"
                                haveCurrent && currentMa < -10 -> "NET DRAIN ON CABLE"
                                haveCurrent -> "HOLDING"
                                isCharging -> "CHARGING (status only)"
                                else -> "DISCHARGING"
                            },
                            color = if (haveCurrent && currentMa < -10) Color(0xFFFF9100)
                                    else if (isCharging) Color(0xFF00FF88) else HioneTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            // Bottom: Real-Time Power & Diagnostics Grid
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Voltage, Current, Wattage Cards
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Voltage
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Mascot.color(0xFF05151E))
                            .border(1.dp, HioneCyan.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("VOLTAGE", color = HioneTextSecondary, fontSize = 7.sp, fontFamily = AudiowideFont)
                            Text(if (haveVoltage) "${String.format("%.2f", voltageMv / 1000f)}V" else "—V", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        }
                    }

                    // Current
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Mascot.color(0xFF05151E))
                            .border(1.dp, Color(0xFF00FF88).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CURRENT", color = HioneTextSecondary, fontSize = 7.sp, fontFamily = AudiowideFont)
                            Text(
                                if (haveCurrent) "${if (currentMa > 0) "+" else ""}${currentMa}mA" else "—mA",
                                color = if (haveCurrent && currentMa < 0) HioneNeonPink else Color(0xFF00FF88),
                                fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont
                            )
                        }
                    }

                    // Wattage
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Mascot.color(0xFF05151E))
                            .border(1.dp, HioneNeonPink.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("POWER", color = HioneTextSecondary, fontSize = 7.sp, fontFamily = AudiowideFont)
                            Text(if (wattage > 0f) "${String.format("%.1f", wattage)}W" else "—W", color = HioneNeonPink, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        }
                    }
                }

                // Row 2: Battery Temp & Health
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Cell Temperature
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Mascot.color(0xFF05151E))
                            .border(1.dp, CyberGlassBorder.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🌡️", fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text("CELL TEMPERATURE", color = HioneTextSecondary, fontSize = 7.sp)
                                // Status word is derived from the real temperature, not asserted.
                                val tempStatus = when {
                                    !haveTemp -> "—"
                                    batteryTempC >= 45f -> "HOT"
                                    batteryTempC >= 40f -> "WARM"
                                    else -> "NOMINAL"
                                }
                                Text(
                                    if (haveTemp) "${String.format("%.1f", batteryTempC)}°C • $tempStatus" else "—°C",
                                    color = when (tempStatus) { "HOT" -> Color(0xFFFF5252); "WARM" -> Color(0xFFFFD600); "NOMINAL" -> Color(0xFF00FF88); else -> HioneTextSecondary },
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Chemistry & Controller
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Mascot.color(0xFF05151E))
                            .border(1.dp, CyberGlassBorder.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔋", fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text("BATTERY HEALTH", color = HioneTextSecondary, fontSize = 7.sp)
                                // Framework EXTRA_HEALTH only; no invented state-of-health percentage.
                                Text(healthLabel.ifBlank { "—" }, color = if (healthLabel.isBlank()) HioneTextSecondary else HioneCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Dismiss hint
                Text(
                    "SWIPE UP OR TAP TO CLOSE",
                    color = HioneTextSecondary.copy(alpha = 0.7f),
                    fontSize = 8.sp,
                    fontFamily = AudiowideFont,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
    }
}
