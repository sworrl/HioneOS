package com.caf.fmradio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The WEATHER tab's own look: a Hione sky instead of the cyber panel. Teal-to-cyan daylight,
 * a soft gold sun, drifting clouds and a light rain; a pink bolt when the Weather Service has
 * something severe out. Everything it lists is still reference data: NOAA Weather Radio is at
 * 162 MHz and this tuner stops at 108.
 */

private val SkyTop = Color(0xFF0B4553)
private val SkyMid = Color(0xFF1C8C93)
private val SkyLow = Color(0xFF5FCFC6)
private val SunGold = Color(0xFFFFD27A)
private val CloudWhite = Color(0xFFEAFDFF)
private val RainCyan = Color(0xFF9BEFFF)
private val WxCard = Color(0xB3102B36)
private val WxAccent = Color(0xFF7FDBFF)

/** Severity colour: the NWS's own scale, in this palette. */
fun alertColor(severity: String?): Color = when (severity?.lowercase(Locale.US)) {
    "extreme" -> Color(0xFFFF3B6B)
    "severe" -> Color(0xFFFF7A45)
    "moderate" -> CyberAmber
    "minor" -> WxAccent
    else -> CyberMuted
}

private fun severityRank(s: String?): Int = when (s?.lowercase(Locale.US)) {
    "extreme" -> 0; "severe" -> 1; "moderate" -> 2; "minor" -> 3; else -> 4
}

fun worstSeverity(alerts: List<FmWeatherRadio.Alert>): String? = alerts.minByOrNull { severityRank(it.severity) }?.severity

@Composable
fun ColumnScope.FmWeatherRadioList(st: FmState) {
    val alerts = st.weatherAlerts
    val severe = alerts?.any { severityRank(it.severity) <= 1 } == true
    WeatherSky(severe, Modifier.fillMaxWidth().height(92.dp))
    Spacer(Modifier.height(8.dp))

    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val sorted = remember(alerts) { alerts?.sortedBy { severityRank(it.severity) } }
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "alerts-h") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ALERTS", color = SunGold, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    Text("National Weather Service", color = HioneTextSecondary, fontSize = 7.5.sp)
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).pressable { FmRadioManager.refreshWeatherAlerts() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Refresh, "Check the Weather Service again", tint = WxAccent, modifier = Modifier.size(20.dp))
                }
            }
        }
        when {
            sorted == null -> item(key = "alerts-null") {
                WxNote(
                    if (st.listenerPlace == null) "Needs a position to ask the Weather Service about alerts here."
                    else "Could not reach the National Weather Service. Alerts will appear when it answers."
                )
            }
            sorted.isEmpty() -> item(key = "alerts-none") { WxNote("No active alerts for your area.") }
            else -> items(sorted, key = { "a" + it.event + (it.effective ?: "") + (it.areas ?: "") }) { a ->
                val k = a.event + (a.effective ?: "") + (a.areas ?: "")
                AlertCard(a, expanded[k] == true) { expanded[k] = expanded[k] != true }
            }
        }

        item(key = "tx-h") {
            Column(Modifier.padding(top = 6.dp)) {
                Text("WEATHER RADIO TRANSMITTERS", color = SunGold, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                Text(
                    "For a weather radio or ham handheld. This tuner can't receive 162 MHz (the Si4705 " +
                        "stops at 108 MHz), so these are listed, not tunable.",
                    color = HioneTextSecondary, fontSize = 7.5.sp, lineHeight = 10.sp,
                )
            }
        }
        if (st.weatherRadios.isEmpty()) {
            item(key = "tx-none") {
                WxNote(
                    if (st.listenerPlace == null) "Needs a position to find the nearest transmitters."
                    else "No NOAA transmitters listed within 120 km."
                )
            }
        }
        items(st.weatherRadios.distinctBy { it.call + it.mhz }, key = { "t" + it.call + it.mhz }) { t -> TransmitterRow(t) }
    }
}

@Composable
private fun WxNote(text: String) {
    Text(
        text, color = HioneTextPrimary, fontSize = 9.sp,
        modifier = Modifier.fillMaxWidth()
            .glass(RoundedCornerShape(12.dp), accent = WxAccent, accent2 = SunGold, fill = WxCard, rimAlpha = 0.35f, shine = 0.6f)
            .padding(12.dp)
    )
}

@Composable
private fun AlertCard(a: FmWeatherRadio.Alert, open: Boolean, onToggle: () -> Unit) {
    val c = alertColor(a.severity)
    Column(
        Modifier.fillMaxWidth()
            .glass(RoundedCornerShape(12.dp), accent = c, accent2 = c, fill = c.copy(alpha = 0.14f), rimAlpha = 0.9f)
            .pressable(pressedScale = 0.98f, onClick = onToggle)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = c, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(a.event, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(a.severity, a.urgency, a.expires?.let { "until ${shortTime(it)}" }).joinToString(" · "),
                    color = HioneTextSecondary, fontSize = 8.sp,
                )
            }
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = c, modifier = Modifier.size(20.dp))
        }
        a.headline?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = HioneTextPrimary, fontSize = 8.5.sp, lineHeight = 11.sp, maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis)
        }
        if (open) {
            a.areas?.let { Spacer(Modifier.height(4.dp)); Text("Areas: $it", color = HioneTextSecondary, fontSize = 8.sp, lineHeight = 10.5.sp) }
            a.description?.let { Spacer(Modifier.height(6.dp)); Text(it.trim(), color = Color.White, fontSize = 8.5.sp, lineHeight = 11.5.sp) }
            a.instruction?.let {
                Spacer(Modifier.height(6.dp))
                Text("WHAT TO DO", color = c, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                Text(it.trim(), color = Color.White, fontSize = 8.5.sp, lineHeight = 11.5.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(a.sender ?: "National Weather Service", color = HioneTextSecondary, fontSize = 7.5.sp)
        }
    }
}

/** "2026-10-10T18:00:00-04:00" to "Oct 10 18:00"; the raw string if it is not that shape. */
private fun shortTime(iso: String): String = runCatching {
    val t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
    t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US))
}.getOrDefault(iso)

@Composable
private fun TransmitterRow(t: FmWeatherRadio.Transmitter) {
    val statusColor = when (t.status) { "NORMAL" -> HioneTeal; "DEGRADED" -> CyberAmber; else -> HioneNeonPink }
    var open by remember(t.call, t.mhz) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .alpha(if (t.inService) 1f else 0.55f)
            .glass(RoundedCornerShape(14.dp), accent = WxAccent, accent2 = SunGold, fill = WxCard, rimAlpha = 0.4f, shine = 0.7f)
            .pressable(pressedScale = 0.98f) { open = !open }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.width(64.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(SunGold.copy(alpha = 0.28f), SunGold.copy(alpha = 0.08f))))
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(t.wx.ifBlank { "WX?" }, color = SunGold, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
            Text(String.format(Locale.US, "%.3f", t.mhz), color = Color.White, fontSize = 8.5.sp, fontFamily = AudiowideFont)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${t.call} · ${t.site}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull("${t.city}, ${t.state}", t.watts?.let { w -> if (w >= 1000) "${w / 1000.0} kW" else "$w W" },
                              t.wfo?.let { "NWS $it" }).joinToString(" · "),
                color = HioneTextSecondary, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(String.format(Locale.US, "%.0f km", t.distanceKm), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(t.status, color = statusColor, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (open) {
        Spacer(Modifier.height(6.dp))
        Text(
            String.format(Locale.US,
                "%.3f MHz is past this tuner. With the driver unlocked we measured the Si4705 at " +
                "64.0 to 108.0 MHz, and every frequency above 108 lands on 108.0. On a weather " +
                "radio or scanner, set %s.", t.mhz, t.wx.ifBlank { "this frequency" }),
            color = HioneTextPrimary, fontSize = 9.sp,
        )
        t.wfo?.let { Text("Programmed by NWS $it. Its alerts for your area are listed above.", color = HioneTextSecondary, fontSize = 8.sp) }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(SunGold.copy(alpha = 0.22f))
                .pressable { FmRadioManager.tune(Math.round(t.mhz * 1000).toInt()) }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("TRY ANYWAY (tunes 108.0)", color = SunGold, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
        }
    }
    }
}

/**
 * The sky banner. Animated at 20 frames a second through one state that only the draw lambda
 * reads, so it redraws this canvas and never recomposes the sheet; it exists only while the
 * weather tab is on screen.
 */
@Composable
private fun WeatherSky(severe: Boolean, modifier: Modifier) {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            time.floatValue = (System.nanoTime() - start) / 1e9f
            delay(50)
        }
    }
    val cloud = remember { Path() }
    val bolt = remember { Path() }
    Box(modifier.glass(RoundedCornerShape(16.dp), accent = SunGold, accent2 = WxAccent, fill = Color.Transparent, rimAlpha = 0.8f)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = time.floatValue
            drawRect(Brush.verticalGradient(listOf(SkyTop, SkyMid, SkyLow.copy(alpha = 0.85f))))
            // Sun with slowly turning rays.
            val sun = Offset(size.width * 0.84f, size.height * 0.34f)
            drawCircle(Brush.radialGradient(listOf(SunGold.copy(alpha = 0.55f), Color.Transparent), sun, 46.dp.toPx()), 46.dp.toPx(), sun)
            for (i in 0 until 10) {
                val a = t * 0.25f + i * (2 * PI.toFloat() / 10)
                val r0 = 15.dp.toPx(); val r1 = 22.dp.toPx()
                drawLine(SunGold.copy(alpha = 0.7f), Offset(sun.x + cos(a) * r0, sun.y + sin(a) * r0),
                         Offset(sun.x + cos(a) * r1, sun.y + sin(a) * r1), 2.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(SunGold, 11.dp.toPx(), sun)
            // Clouds drifting left to right and wrapping.
            val drift = (t * 9.dp.toPx()) % (size.width + 120.dp.toPx())
            drawCloud(cloud, Offset(drift - 60.dp.toPx(), size.height * 0.42f), 1f)
            drawCloud(cloud, Offset((drift * 0.6f + size.width * 0.45f) % (size.width + 120.dp.toPx()) - 60.dp.toPx(), size.height * 0.28f), 0.7f)
            // Light rain under the low cloud.
            val rainX = drift - 60.dp.toPx()
            for (i in 0 until 12) {
                val x = rainX - 22.dp.toPx() + i * 4.dp.toPx()
                val phase = ((t * 1.6f + i * 0.37f) % 1f)
                val y = size.height * 0.52f + phase * size.height * 0.45f
                drawLine(RainCyan.copy(alpha = 0.55f * (1f - phase)), Offset(x, y), Offset(x - 2.dp.toPx(), y + 6.dp.toPx()), 1.2.dp.toPx(), StrokeCap.Round)
            }
            // Severe weather out: a pink bolt that flickers now and then.
            if (severe && (t % 3.2f) < 0.35f && ((t * 20).toInt() % 2 == 0)) {
                val bx = size.width * 0.32f; val by = size.height * 0.30f
                bolt.reset()
                bolt.moveTo(bx, by); bolt.lineTo(bx - 8.dp.toPx(), by + 22.dp.toPx()); bolt.lineTo(bx - 1.dp.toPx(), by + 22.dp.toPx())
                bolt.lineTo(bx - 7.dp.toPx(), by + 44.dp.toPx()); bolt.lineTo(bx + 10.dp.toPx(), by + 15.dp.toPx())
                bolt.lineTo(bx + 2.dp.toPx(), by + 15.dp.toPx()); bolt.close()
                drawPath(bolt, HionePink)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("ミクの天気", color = Color.White, fontSize = 15.sp, fontFamily = MochiyPopFont)
            Text("HIONE WEATHER · NOAA WEATHER RADIO", color = Color.White.copy(alpha = 0.85f), fontSize = 7.5.sp,
                 fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
        }
    }
}

private fun DrawScope.drawCloud(p: Path, at: Offset, scale: Float) {
    val u = 10.dp.toPx() * scale
    p.reset()
    p.addOval(androidx.compose.ui.geometry.Rect(at.x - 3 * u, at.y - 0.6f * u, at.x + 3 * u, at.y + 1.2f * u))
    p.addOval(androidx.compose.ui.geometry.Rect(at.x - 2.2f * u, at.y - 1.8f * u, at.x - 0.2f * u, at.y + 0.6f * u))
    p.addOval(androidx.compose.ui.geometry.Rect(at.x - 0.9f * u, at.y - 2.6f * u, at.x + 1.7f * u, at.y + 0.4f * u))
    drawPath(p, CloudWhite.copy(alpha = 0.85f))
}
