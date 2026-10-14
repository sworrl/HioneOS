package com.hione.launcher.weather

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.launcher.AudiowideFont
import com.hione.launcher.CyberGlassBorder
import com.hione.launcher.CyberGlassCard
import com.hione.launcher.ui.HioneAmbient
import com.hione.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detail sheet for the Hione Weather tile: full hourly + daily lists, air quality, provenance,
 * and settings (°C/°F, Windy API key, manual city / lat-lon override). Self-hosted in a Dialog
 * so the tile needs no launcher-level state; back / scrim tap / swipe-up dismisses.
 */
private val Teal get() = Mascot.color(0xFFFFAB2E)
private val TealBright get() = Mascot.color(0xFFFFE0B0)
private val Pink get() = Mascot.color(0xFF8EC5FF)
private val Muted = Color(0xFF8BA6A9)
private val SkyBlue = Color(0xFF80D8FF)

@Composable
fun HioneWeatherTileDetailSheet(onDismiss: () -> Unit, onOpenObservatory: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by HioneWeatherTileEngine.state.collectAsState()
    val snap = st.snapshot
    val u = st.units
    val tz = snap?.timeZone ?: java.util.TimeZone.getDefault()
    val now = System.currentTimeMillis()

    DisposableEffect(Unit) {
        HioneAmbient.pushCovered()
        onDispose { HioneAmbient.popCovered() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Mascot.color(0xF00C0D10))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .swipeUpFromBottomToDismiss(onDismiss = onDismiss)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 14.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            ) {
                // ---- header --------------------------------------------------------------
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${Mascot.nameUpper} WEATHER", color = Teal, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                        Text(
                            snap?.placeName?.ifBlank { null } ?: st.location?.name ?: "No location",
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    SheetPill(if (st.isRefreshing) "…" else "REFRESH", Teal) { HioneWeatherTileEngine.refresh(ctx) }
                    Spacer(Modifier.width(6.dp))
                    SheetPill(if (u == WxUnits.METRIC) "°C" else "°F", Pink) {
                        HioneWeatherTileEngine.setUnits(ctx, if (u == WxUnits.METRIC) WxUnits.IMPERIAL else WxUnits.METRIC)
                    }
                    Spacer(Modifier.width(6.dp))
                    SheetPill("✕", Color.White.copy(alpha = 0.7f), onClick = onDismiss)
                }
                Spacer(Modifier.height(6.dp))

                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ---- provenance + age + errors --------------------------------------
                    item {
                        SheetCard {
                            if (snap != null) {
                                val stale = now - snap.fetchedMs > HioneWeatherTileEngine.STALE_MS
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("SOURCE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                    Spacer(Modifier.width(6.dp))
                                    Text(snap.source, color = TealBright, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        (if (stale) "STALE · " else "") + "updated " + WxFmt.age(snap.fetchedMs, now),
                                        color = if (stale) Pink else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(snap.sourceDetail, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp, lineHeight = 13.sp)
                                snap.current.observedMs?.let {
                                    Text("Model/observation time: ${WxFmt.dayDate(it, tz)} ${WxFmt.clock(it, tz)} (${snap.tzId ?: "local"})", color = Muted, fontSize = 9.5.sp)
                                }
                            } else {
                                Text("No weather data has been fetched yet.", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            st.location?.let {
                                Text("Location: ${it.name} · ${String.format(java.util.Locale.US, "%.3f, %.3f", it.lat, it.lon)} · ${it.provider}", color = Muted, fontSize = 9.5.sp)
                            }
                            if (!st.online) Text("Offline. Nothing new can be fetched.", color = Pink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            st.lastError?.let { Text("$it", color = Pink, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }

                    // ---- current ---------------------------------------------------------
                    item {
                        val c = snap?.current
                        SheetCard(title = "NOW") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(HioneWmo.icon(c?.wmoCode, c?.isDay), fontSize = 40.sp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(WxFmt.tempUnit(c?.tempC, u), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                    Text(HioneWmo.text(c?.wmoCode), color = TealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            MetricRow("Feels like", WxFmt.tempUnit(c?.feelsC, u), "Humidity", WxFmt.pct(c?.humidityPct))
                            MetricRow("Wind", "${WxFmt.wind(c?.windKmh, u)} ${WxFmt.compass(c?.windDirDeg)}", "Gusts", WxFmt.wind(c?.gustKmh, u))
                            MetricRow("Pressure", WxFmt.pressure(c?.pressureHpa, u), "Cloud cover", WxFmt.pct(c?.cloudPct))
                            MetricRow("Precip (now)", WxFmt.precip(c?.precipMm, u), "UV index", WxFmt.uv(c?.uvIndex))
                            val astro = snap?.todayAstro(now)
                            MetricRow("Sunrise", WxFmt.clock(astro?.sunriseMs, tz), "Sunset", WxFmt.clock(astro?.sunsetMs, tz))
                            Spacer(Modifier.height(4.dp))
                            Text(HioneWeatherMood.line(snap), color = Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // ---- air quality -----------------------------------------------------
                    item {
                        val a = snap?.air
                        SheetCard(title = "AIR QUALITY") {
                            val tint = Color(WxFmt.aqiColorArgb(a?.usAqi))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("US AQI ${a?.usAqi?.toString() ?: WxFmt.DASH}", color = tint, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                                Spacer(Modifier.width(8.dp))
                                Text(WxFmt.aqiCategory(a?.usAqi), color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            MetricRow("EU AQI", a?.euAqi?.toString() ?: WxFmt.DASH, "PM2.5", a?.pm25?.let { String.format(java.util.Locale.US, "%.1f µg/m³", it) } ?: WxFmt.DASH)
                            MetricRow("PM10", a?.pm10?.let { String.format(java.util.Locale.US, "%.1f µg/m³", it) } ?: WxFmt.DASH, "Ozone", a?.ozone?.let { String.format(java.util.Locale.US, "%.0f µg/m³", it) } ?: WxFmt.DASH)
                            Text(
                                if (a == null) "Open-Meteo air-quality model returned nothing for this spot." else "Open-Meteo air-quality model" + (a.observedMs?.let { " · ${WxFmt.clock(it, tz)}" } ?: ""),
                                color = Muted, fontSize = 9.5.sp
                            )
                        }
                    }

                    // ---- hourly ----------------------------------------------------------
                    item {
                        val hours = snap?.hourly.orEmpty()
                        SheetCard(title = "HOURLY" + if (snap?.source?.startsWith("Windy") == true) " (3-hour model steps)" else "") {
                            if (hours.isEmpty()) {
                                Text(WxFmt.DASH, color = Muted, fontSize = 12.sp)
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(hours) { h ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(WxFmt.dayLabel(h.epochMs, tz, now).take(3), color = Muted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                            Text(WxFmt.hourLabel(h.epochMs, tz), color = TealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            Text(HioneWmo.icon(h.wmoCode, h.isDay), fontSize = 18.sp)
                                            Text(WxFmt.temp(h.tempC, u), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                                            Text("f ${WxFmt.temp(h.feelsC, u)}", color = Muted, fontSize = 9.sp)
                                            Text(
                                                h.precipProbPct?.let { "☔$it%" } ?: "☔" + WxFmt.precip(h.precipMm, u),
                                                color = SkyBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold
                                            )
                                            Text("${WxFmt.wind(h.windKmh, u)}", color = Muted, fontSize = 8.5.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ---- daily -----------------------------------------------------------
                    item {
                        val days = snap?.daily.orEmpty()
                        SheetCard(title = "7-DAY") {
                            if (days.isEmpty()) Text(WxFmt.DASH, color = Muted, fontSize = 12.sp)
                            days.forEach { d ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(WxFmt.dayDate(d.epochMs, tz), color = TealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(74.dp))
                                    Text(HioneWmo.icon(d.wmoCode, true), fontSize = 16.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(HioneWmo.text(d.wmoCode), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "Rain ${WxFmt.pct(d.precipProbPct)} · ${WxFmt.precip(d.precipMm, u)} · Wind ${WxFmt.wind(d.windMaxKmh, u)} · UV ${WxFmt.uv(d.uvMax)} · Sun ${WxFmt.clock(d.sunriseMs, tz)} to ${WxFmt.clock(d.sunsetMs, tz)}",
                                            color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text("▲${WxFmt.temp(d.maxC, u)}", color = Color(0xFFFF8A80), fontSize = 11.sp, fontWeight = FontWeight.Black)
                                    Spacer(Modifier.width(4.dp))
                                    Text("▼${WxFmt.temp(d.minC, u)}", color = SkyBlue, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }

                    // ---- settings: units · Windy key -------------------------------------
                    item {
                        if (onOpenObservatory != null) {
                            SheetCard(title = "OBSERVATORY") {
                                Text(
                                    "Open the full weather screen (radar, sun times, history)",
                                    color = Mascot.color(0xFFFFAB2E), fontSize = 12.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onDismiss(); onOpenObservatory() }
                                        .padding(vertical = 6.dp)
                                )
                            }
                        }
                        SheetCard(title = "SETTINGS") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Units", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    WxUnits.entries.forEach { opt ->
                                        val on = opt == u
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (on) Teal else Color(0x22FFFFFF))
                                                .border(1.dp, if (on) Teal else CyberGlassBorder, RoundedCornerShape(8.dp))
                                                .clickable { HioneWeatherTileEngine.setUnits(ctx, opt) }
                                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                        ) { Text(opt.label, color = if (on) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Windy Point Forecast API key", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    st.windyKey.isBlank() -> "No key. Open-Meteo only (free, no account)."
                                    st.windyKeyFromBuild -> "Using the key from the build (local.properties, hione.windy.key). Enter one here to use a different key."
                                    else -> "Key saved on this device. Clear the field and save to go back to the build key or Open-Meteo."
                                },
                                color = Muted, fontSize = 9.5.sp, lineHeight = 12.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            var keyText by remember(st.windyKeyFromBuild) { mutableStateOf(if (st.windyKeyFromBuild) "" else st.windyKey) }
                            var showKey by remember { mutableStateOf(false) }
                            OutlinedTextField(
                                value = keyText,
                                onValueChange = { keyText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("paste key from api.windy.com", color = Color.Gray, fontSize = 10.sp) },
                                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Teal,
                                    unfocusedBorderColor = Teal.copy(alpha = 0.4f),
                                    cursorColor = Teal
                                )
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SheetPill("SAVE KEY", Teal) { HioneWeatherTileEngine.setWindyKey(ctx, keyText) }
                                SheetPill(if (showKey) "HIDE" else "SHOW", Muted) { showKey = !showKey }
                                SheetPill("CLEAR", Pink) { keyText = ""; HioneWeatherTileEngine.setWindyKey(ctx, "") }
                            }
                        }
                    }

                    // ---- settings: location --------------------------------------------
                    item {
                        SheetCard(title = "LOCATION") {
                            val manual = st.manualLocation
                            Text(
                                if (manual != null) "Set by hand: ${manual.name}" else "Automatic, from the last known device position (no GPS polling)." +
                                    (st.location?.let { " Currently: ${it.name} (${it.provider})." } ?: " None resolved yet."),
                                color = if (manual != null) TealBright else Muted, fontSize = 10.sp, lineHeight = 13.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            if (manual != null) {
                                SheetPill("USE DEVICE LOCATION", Teal) { HioneWeatherTileEngine.clearManualLocation(ctx) }
                                Spacer(Modifier.height(6.dp))
                            }

                            var query by remember { mutableStateOf("") }
                            var results by remember { mutableStateOf<List<WxPlace>>(emptyList()) }
                            var searching by remember { mutableStateOf(false) }
                            var searchJob by remember { mutableStateOf<Job?>(null) }
                            OutlinedTextField(
                                value = query,
                                onValueChange = { q ->
                                    query = q
                                    searchJob?.cancel()
                                    searchJob = scope.launch {
                                        delay(350L) // debounce so a fast typist doesn't spam the geocoder
                                        if (q.trim().length < 2) { results = emptyList(); return@launch }
                                        searching = true
                                        val r = withContext(Dispatchers.IO) { HioneWeatherTileSources.geocode(q) }
                                        results = r
                                        searching = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("search a city (Open-Meteo geocoding)", color = Color.Gray, fontSize = 10.sp) },
                                trailingIcon = { if (searching) CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), color = Teal, strokeWidth = 2.dp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Teal,
                                    unfocusedBorderColor = Teal.copy(alpha = 0.4f),
                                    cursorColor = Teal
                                )
                            )
                            if (results.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Column(Modifier.fillMaxWidth().heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    results.take(6).forEach { p ->
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Teal.copy(alpha = 0.12f))
                                                .border(1.dp, Teal.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                                .clickable {
                                                    HioneWeatherTileEngine.setManualLocation(ctx, p.lat, p.lon, p.label)
                                                    results = emptyList()
                                                    query = ""
                                                }
                                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                        ) {
                                            Text(p.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            } else if (query.trim().length >= 2 && !searching) {
                                Text("No matches.", color = Muted, fontSize = 10.sp)
                            }

                            Spacer(Modifier.height(8.dp))
                            Text("Or enter coordinates", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            var latText by remember { mutableStateOf("") }
                            var lonText by remember { mutableStateOf("") }
                            var coordError by remember { mutableStateOf<String?>(null) }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = latText, onValueChange = { latText = it }, modifier = Modifier.weight(1f), singleLine = true,
                                    placeholder = { Text("lat", color = Color.Gray, fontSize = 10.sp) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Teal, unfocusedBorderColor = Teal.copy(alpha = 0.4f), cursorColor = Teal)
                                )
                                OutlinedTextField(
                                    value = lonText, onValueChange = { lonText = it }, modifier = Modifier.weight(1f), singleLine = true,
                                    placeholder = { Text("lon", color = Color.Gray, fontSize = 10.sp) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Teal, unfocusedBorderColor = Teal.copy(alpha = 0.4f), cursorColor = Teal)
                                )
                                SheetPill("SET", Teal) {
                                    val la = latText.trim().toDoubleOrNull()
                                    val lo = lonText.trim().toDoubleOrNull()
                                    if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) {
                                        coordError = "Enter a valid latitude (-90…90) and longitude (-180…180)."
                                    } else {
                                        coordError = null
                                        HioneWeatherTileEngine.setManualLocation(ctx, la, lo, "")
                                    }
                                }
                            }
                            coordError?.let { Text(it, color = Pink, fontSize = 9.5.sp, fontWeight = FontWeight.Bold) }
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SheetCard(title: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CyberGlassCard)
            .border(1.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        if (title != null) {
            Text(title, color = Teal, fontSize = 10.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            Spacer(Modifier.height(5.dp))
        }
        content()
    }
}

@Composable
private fun MetricRow(l1: String, v1: String, l2: String, v2: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(l1, color = Muted, fontSize = 10.5.sp)
            Text(v1, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(l2, color = Muted, fontSize = 10.5.sp)
            Text(v2, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SheetPill(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}
