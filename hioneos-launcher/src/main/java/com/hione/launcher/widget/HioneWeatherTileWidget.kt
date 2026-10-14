package com.hione.launcher.widget

import com.hione.launcher.Mascot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import com.hione.launcher.quiltStitch
import com.hione.launcher.weather.HioneWeatherMood
import com.hione.launcher.weather.HioneWeatherTileDetailSheet
import com.hione.launcher.weather.HioneWeatherTileEngine
import com.hione.launcher.weather.HioneWmo
import com.hione.launcher.weather.WxFmt
import com.hione.launcher.weather.WxSnapshot
import kotlinx.coroutines.delay

/**
 * Hione Weather TILE — the full-width quilt patch. Current temp + condition (WMO → text + Hione
 * glyph), feels-like, humidity, wind, real AQI, sunrise/sunset, a 6-step hourly strip and a
 * 3-day row, source chip + data age (STALE flagged past 90 min), and Hione's mood line.
 * Tap → [HioneWeatherTileDetailSheet] (hourly, daily, Windy key, units, location).
 *
 * Every number comes from [HioneWeatherTileEngine]'s snapshot; a missing value renders "—".
 */
private val Teal get() = Mascot.color(0xFFFFAB2E)
private val TealBright get() = Mascot.color(0xFFFFE0B0)
private val Pink get() = Mascot.color(0xFF8EC5FF)
private val Muted = Color(0xFF8BA6A9)

private fun tileGradient(code: Int?, isDay: Boolean?): List<Color> = when {
    code == null -> listOf(Mascot.color(0xFF10262B), Mascot.color(0xFF071417))
    HioneWmo.isStorm(code) -> listOf(Color(0xFF2B1B4E), Color(0xFF0B0A1E))
    HioneWmo.isSnow(code) -> listOf(Color(0xFF3A4E6E), Color(0xFF14202E))
    HioneWmo.isPrecip(code) -> listOf(Color(0xFF1B3A5C), Color(0xFF0A1A2A))
    HioneWmo.isFog(code) || code >= 2 -> listOf(Mascot.color(0xFF243640), Mascot.color(0xFF0E181D))
    isDay == false -> listOf(Color(0xFF1E1B4A), Color(0xFF0A0921))
    else -> listOf(Mascot.color(0xFF0F4F54), Mascot.color(0xFF06181C))
}

@Composable
fun HioneWeatherTile(modifier: Modifier = Modifier, onOpenObservatory: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { HioneWeatherTileEngine.ensureStarted(ctx) }
    val st by HioneWeatherTileEngine.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }
    var detailOpen by remember { mutableStateOf(false) }

    val snap: WxSnapshot? = st.snapshot
    val u = st.units
    val cur = snap?.current
    val tz = snap?.timeZone ?: java.util.TimeZone.getDefault()
    val stale = snap != null && now - snap.fetchedMs > HioneWeatherTileEngine.STALE_MS
    val shape = RoundedCornerShape(20.dp)
    val gradient = remember(cur?.wmoCode, cur?.isDay) { tileGradient(cur?.wmoCode, cur?.isDay) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(gradient))
            .quiltStitch(fillStart = Mascot.color(0x1AFFAB2E), fillEnd = Mascot.color(0x148EC5FF), seam = Mascot.color(0xB3FFAB2E), corner = 20.dp)
            .border(1.dp, if (stale) Pink.copy(alpha = 0.55f) else Teal.copy(alpha = 0.45f), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { detailOpen = true }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        // ---- header: place · source chip · age ----------------------------------------
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "♪ " + (snap?.placeName?.ifBlank { null } ?: st.location?.name ?: "No location"),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (snap != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Teal.copy(alpha = 0.18f))
                        .border(1.dp, Teal.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(snap.source.uppercase(), color = TealBright, fontSize = 8.5.sp, fontWeight = FontWeight.Black, maxLines = 1)
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = (if (stale) "STALE · " else "") + WxFmt.age(snap.fetchedMs, now),
                    color = if (stale) Pink else Muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            } else if (st.isRefreshing) {
                Text("fetching…", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(4.dp))

        // ---- hero: glyph · temp · condition + feels/humidity/wind ---------------------
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(HioneWmo.icon(cur?.wmoCode, cur?.isDay), fontSize = 34.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(
                WxFmt.temp(cur?.tempC, u),
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    HioneWmo.text(cur?.wmoCode),
                    color = TealBright,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "feels ${WxFmt.temp(cur?.feelsC, u)} · RH ${WxFmt.pct(cur?.humidityPct)} · ${WxFmt.wind(cur?.windKmh, u)} ${WxFmt.compass(cur?.windDirDeg)}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // ---- metrics: AQI · sunrise · sunset · UV ------------------------------------
        val astro = snap?.todayAstro(now)
        val aqi = snap?.air?.usAqi
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(WxFmt.aqiColorArgb(aqi)).copy(alpha = 0.22f))
                        .border(1.dp, Color(WxFmt.aqiColorArgb(aqi)).copy(alpha = 0.75f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        "AQI ${aqi?.toString() ?: WxFmt.DASH}",
                        color = Color(WxFmt.aqiColorArgb(aqi)),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(WxFmt.aqiCategory(aqi), color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text("Rise ${WxFmt.clock(astro?.sunriseMs, tz)}", color = Color.White.copy(alpha = 0.85f), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("Set ${WxFmt.clock(astro?.sunsetMs, tz)}", color = Color.White.copy(alpha = 0.85f), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("UV ${WxFmt.uv(cur?.uvIndex)}", color = Color.White.copy(alpha = 0.85f), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }

        // ---- 6-step hourly strip (real timestamps; 3-hourly when Windy is the source) ----
        val strip = snap?.hourly?.take(6).orEmpty()
        if (strip.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                strip.forEach { h ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(WxFmt.hourLabel(h.epochMs, tz), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(HioneWmo.icon(h.wmoCode, h.isDay), fontSize = 13.sp, maxLines = 1)
                        Text(WxFmt.temp(h.tempC, u), color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        val pp = h.precipProbPct
                        Text(
                            if (pp != null) "☔$pp%" else if ((h.precipMm ?: 0.0) > 0.0) "☔" + WxFmt.precip(h.precipMm, u) else "",
                            color = Color(0xFF80D8FF),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // ---- 3-day row -------------------------------------------------------------------
        val days = snap?.daily?.take(3).orEmpty()
        if (days.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                days.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(WxFmt.dayLabel(d.epochMs, tz, now), color = TealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Spacer(Modifier.width(3.dp))
                        Text(HioneWmo.icon(d.wmoCode, true), fontSize = 12.sp, maxLines = 1)
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "▲${WxFmt.temp(d.maxC, u)} ▼${WxFmt.temp(d.minC, u)}",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        d.precipProbPct?.let {
                            Spacer(Modifier.width(3.dp))
                            Text("☔$it%", color = Color(0xFF80D8FF), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }

        // ---- NWS alert / nearest NOAA weather radio (HioneWeatherAlertsPanel.kt) ----------
        com.hione.launcher.weather.HioneWeatherAlertTileLine()

        // ---- Hione mood / honest status line ---------------------------------------------
        Spacer(Modifier.height(5.dp))
        val status = when {
            snap == null && st.lastError != null -> st.lastError
            snap == null && st.isRefreshing -> "Fetching the first reading…"
            snap == null -> "No weather data yet. Tap to set a location or refresh."
            else -> HioneWeatherMood.line(snap)
        }
        Text(
            status ?: "",
            color = if (snap == null) Muted else Pink,
            fontSize = 10.sp,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (snap != null && st.lastError != null) {
            Text(
                "${st.lastError}",
                color = Pink.copy(alpha = 0.95f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (detailOpen) {
        HioneWeatherTileDetailSheet(onDismiss = { detailOpen = false }, onOpenObservatory = onOpenObservatory)
    }
}
