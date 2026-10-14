package com.hione.launcher.weather
import com.hione.launcher.Mascot
import com.hione.launcher.mascotPainter
import com.hione.launcher.*

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.launcher.R
import com.hione.launcher.*
import com.hione.launcher.metrics.HioneMetricDatabase
import com.hione.launcher.ui.swipeUpFromBottomToDismiss
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Oki Hione Meteorological Observatory & Live Telemetry Modal.
 * Lifted directly from the OnThe8s (RetroWeather) meteorological pipeline:
 * 1. National Weather Service (NWS) direct station observations & lookaheads.
 * 2. Astronomical Solar Arc sunrise/sunset progression & day/night transit.
 * 3. 8-Point temperature trend sparkline with range boundaries.
 * 4. High-granularity environmental telemetry: Dew Point, Barometric Pressure, Cloud Cover, Visibility, AQI, UV.
 * 5. Interactive 360-degree Cyber Doppler Radar precipitation sweep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HioneWeatherObservatoryModal(
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val weatherState by HioneWeatherService.state.collectAsState()
    val weather = weatherState.weather
    val gps = weatherState.gps

    // Honest empty state: the WeatherCondition defaults (72°F / "Clear Sky" / AQI 30) are placeholders
    // until the first successful fetch - never render them as observations.
    if (weather.lastUpdatedTime == 0L) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { HioneWeatherService.refreshLocationNow(ctx); HioneWeatherService.refreshWeather(ctx) }) {
                    androidx.compose.material3.Text(if (weatherState.isLoading) "Fetching…" else "Fetch now")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismissRequest) { androidx.compose.material3.Text("Close") }
            },
            title = { androidx.compose.material3.Text("No weather data yet") },
            text = {
                androidx.compose.material3.Text(
                    weatherState.error?.let { "Last attempt failed: $it" }
                        ?: "Nothing has been fetched from the weather service on this boot yet."
                )
            }
        )
        return
    }

    var historyRecords by remember { mutableStateOf<List<HioneMetricDatabase.WeatherRecord>>(emptyList()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showLocationPicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<HioneWeatherService.CitySearchResult>>(emptyList()) }

    LaunchedEffect(Unit) {
        historyRecords = HioneMetricDatabase.getInstance(ctx).getRecentWeatherHistory(24)
    }
    // Widget port (HioneWeatherExtras / HioneNowcastEngine): air quality + pollen, frost, the rain
    // nowcast and, with a key, Windy data. Each one skips itself when its data is still fresh.
    LaunchedEffect(gps.latitude, gps.longitude) { HioneWeatherExtras.refresh(ctx) }
    val extras by HioneWeatherExtras.state.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xEB0C0D10))
            .clickable { onDismissRequest() }
            // System-gesture-style dismiss: swipe up starting at the bottom edge of the modal.
            .swipeUpFromBottomToDismiss(onDismiss = onDismissRequest)
    ) {
        // Outer 3D Beveled Modal Shell
        Box(
            Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .align(Alignment.Center)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .clip(CutCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(1.dp)
                    .clip(CutCornerShape(15.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Mascot.color(0xFA09222E),
                                Mascot.color(0xFF04121A),
                                Mascot.color(0xFF02090D)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    HioneCyan.copy(alpha = 0.95f),
                                    CyberGlassBorder.copy(alpha = 0.35f),
                                    HioneNeonPink.copy(alpha = 0.7f)
                                )
                            )
                        ),
                        CutCornerShape(15.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Full Modal-Sized Transparent Hione Inlay Background
                Image(
                    painter = mascotPainter(R.drawable.hione_pose_dance),
                    contentDescription = "${Mascot.name} weather art",
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
                    alpha = 0.20f,
                    contentScale = ContentScale.Crop
                )

                Column(
                    Modifier.fillMaxSize()
                ) {
                    // Header Bar with 3D Embossed Buttons & Task-Specific Hione Badge
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CutCornerShape(8.dp))
                                    .background(Mascot.color(0x33FFC56B))
                                    .border(1.dp, HioneCyan, CutCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = mascotPainter(R.drawable.hione_pose_dance),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.clickable { showLocationPicker = true }) {
                                Text(
                                    text = "WEATHER",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.8.sp
                                )
                                val isManual = HioneWeatherService.isManualLocationEnabled(ctx)
                                val providerBadge = if (isManual) "MANUAL" else "GPS"
                                Text(
                                    text = "$providerBadge: ${if (weather.nwsStationId.isNotEmpty()) weather.nwsStationId + " · " else ""}${if (gps.fuzzyLocation.isNotEmpty()) gps.fuzzyLocation else if (gps.city.isNotEmpty()) gps.city else "Local Station"} · tap to change",
                                    color = if (isManual) Mascot.color(0xFFFF80AB) else HioneCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            com.hione.launcher.network.Cyber3dIconButton(
                                onClick = { HioneWeatherService.refreshLocationNow(ctx); HioneWeatherService.refreshWeather(ctx) },
                                icon = Icons.Default.Refresh,
                                contentDescription = "Refresh weather",
                                accentColor = HioneCyan,
                                isLoading = weatherState.isLoading
                            )
                            com.hione.launcher.network.Cyber3dIconButton(
                                onClick = onDismissRequest,
                                icon = Icons.Default.Close,
                                contentDescription = "Close",
                                accentColor = HioneNeonPink
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Cyber 3-Tab Switcher (Overview vs Meteogram vs Live Doppler Radar)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("NOW", "FORECAST", "RADAR", "MAP", "CAMS").forEachIndexed { i, title ->
                            TabButton(
                                title = title,
                                isSelected = selectedTab == i,
                                modifier = Modifier.weight(if (title == "FORECAST") 1.35f else 1f),
                                fontSize = 9.5.sp
                            ) { selectedTab = i }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Live NWS alerts + nearest NOAA Weather Radio (HioneWeatherAlertsPanel.kt).
                    HioneWeatherAlertsPanel(Modifier.padding(bottom = 6.dp))

                    when (selectedTab) {
                        0 -> {
                            // TAB 0: OVERVIEW. Scrolls since the widget port added the rain line, pollen,
                            // frost and the Windy card.
                            Column(
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Multi-Source Feed Attribution Banner
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(CutCornerShape(4.dp))
                                        .background(Mascot.color(0x22FFC56B))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "SOURCES: ${weather.sourcesUsed.uppercase()}",
                                        color = HioneCyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                    if (weather.nextPrecipLabel.isNotEmpty()) {
                                        Text(
                                            text = "${weather.nextPrecipLabel}",
                                            color = com.hione.launcher.ui.HioneIdentity.Gold,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = AudiowideFont
                                        )
                                    }
                                }

                                // "Rain in ~25 min from the SW" from the radar nowcast; tap for the radar.
                                HioneRainNowcastLine(onOpenRadar = { selectedTab = 2 })

                                // Main Current Conditions Hero Card
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(CutCornerShape(12.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Mascot.color(0x44FFC56B),
                                                    Mascot.color(0x22041D28),
                                                    Mascot.color(0xFF031016)
                                                )
                                            )
                                        )
                                        .border(
                                            BorderStroke(
                                                1.dp,
                                                Brush.linearGradient(listOf(HioneCyan, Mascot.color(0x33FFC56B), HioneNeonPink))
                                            ),
                                            CutCornerShape(12.dp)
                                        )
                                        .padding(10.dp)
                                    ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.Bottom) {
                                                Text(
                                                    text = "${weather.tempF.roundToInt()}°",
                                                    color = Color.White,
                                                    fontSize = 32.sp,
                                                    fontWeight = FontWeight.Black,
                                                    fontFamily = AudiowideFont
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text(
                                                    text = "F",
                                                    color = HioneCyan,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = AudiowideFont,
                                                    modifier = Modifier.padding(bottom = 4.dp)
                                                )
                                            }
                                            Text(
                                                text = "${weather.summary} · Feels ${weather.feelsLikeF.roundToInt()}°F",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(Modifier.height(1.dp))
                                            Text(
                                                // 0 = no daily block in the last fetch (and a
                                                // partial cache restore leaves it 0 too). It used
                                                // to print "▲ High 0°F ▼ Low 0°F" as a forecast.
                                                text = if (weather.highTempF != 0f || weather.lowTempF != 0f)
                                                    "▲ High ${weather.highTempF.roundToInt()}°F  ▼ Low ${weather.lowTempF.roundToInt()}°F"
                                                else "▲ High —  ▼ Low —",
                                                color = HioneTextSecondary,
                                                fontSize = 11.5.sp
                                            )
                                        }

                                        // Lunar & Solar Astronomical Status Badge
                                        Column(horizontalAlignment = Alignment.End) {
                                            Box(
                                                Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .background(Mascot.color(0x33FFC56B))
                                                    .border(1.2.dp, HioneCyan, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = weather.icon,
                                                    fontSize = 24.sp
                                                )
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = "${weather.moonPhase.phaseIcon} ${weather.moonPhase.phaseName}",
                                                color = Mascot.color(0xFFFFD59A),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = "${weather.moonPhase.illuminationPct}% illuminated",
                                                color = HioneTextSecondary,
                                                fontSize = 9.5.sp
                                            )
                                        }
                                    }
                                }

                                // 8-Hour Temperature Sparkline Trend
                                if (weather.hourlySparklineTemps.isNotEmpty()) {
                                    HioneTemperatureSparkline(
                                        temps = weather.hourlySparklineTemps,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                // Astronomical Solar Arc
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(CutCornerShape(10.dp))
                                        .background(Mascot.color(0xFF03141C))
                                        .border(0.8.dp, HioneCyan.copy(alpha = 0.4f), CutCornerShape(10.dp))
                                        .padding(8.dp)
                                ) {
                                    val hasAstro = weather.sunrise.isNotBlank() && weather.sunset.isNotBlank()
                                    Column {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Blank sunrise/sunset = the fetch carried no astro
                                            // (or this is a partial cache restore). It used to
                                            // render "🌅 SUNRISE " / "🌇 SUNSET " with an empty
                                            // time and a 0 %-filled arc as if they were readings.
                                            Text(
                                                text = "SUNRISE ${weather.sunrise.ifBlank { "—" }}",
                                                color = com.hione.launcher.ui.HioneIdentity.Gold,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = if (!hasAstro) "DAY OR NIGHT UNKNOWN"
                                                    else if (weather.isDay) "DAYTIME" else "NIGHT",
                                                color = if (hasAstro && weather.isDay) com.hione.launcher.ui.HioneIdentity.Gold else Mascot.color(0xFFFFD59A),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = "SUNSET ${weather.sunset.ifBlank { "—" }}",
                                                color = Color(0xFFFF9100),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(Mascot.color(0xFF0A222C))
                                        ) {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth(if (hasAstro) weather.solarFraction.coerceIn(0f, 1f) else 0f)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(
                                                        Brush.horizontalGradient(
                                                            listOf(com.hione.launcher.ui.HioneIdentity.Gold, Color(0xFFFF9100), HioneCyan)
                                                        )
                                                    )
                                            )
                                        }
                                    }
                                }

                                // 4 High-Density Environmental Telemetry Tiles (2x2 Grid)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "WIND / GUSTS",
                                        // 0 gust = wind_gusts_10m was not reported for this hour.
                                        // It used to print windSpeed * 1.35 as a measured gust.
                                        value = "${weather.windSpeedMph.roundToInt()} mph ${weather.windDirectionCompass}",
                                        sub = if (weather.windGustMph > 0f) "Gusts to ${weather.windGustMph.roundToInt()} mph" else "Gusts —",
                                        icon = Icons.Default.Air,
                                        color = HioneCyan
                                    )
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "HUMIDITY / DEW",
                                        // 0 = never reported (see WeatherCondition's neutral
                                        // defaults); the dew point used to be faked as temp-15°F.
                                        value = if (weather.humidityPct > 0) "${weather.humidityPct}%" else "—",
                                        sub = if (weather.dewPointF != 0f) "Dew point ${weather.dewPointF.roundToInt()}°F" else "Dew point —",
                                        icon = Icons.Default.WaterDrop,
                                        color = Color(0xFF2979FF)
                                    )
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "BAROMETER / CLOUD",
                                        // 0 = no surface_pressure in the response. It used to fall
                                        // back to 1013.25 hPa — the standard atmosphere — and print
                                        // "29.92 inHg" as a barometer reading.
                                        value = if (weather.pressureInHg > 0f)
                                            "${String.format(Locale.US, "%.2f", weather.pressureInHg)} inHg" else "— inHg",
                                        sub = "${weather.cloudCoverPct}% cloud cover",
                                        icon = Icons.Default.Speed,
                                        color = com.hione.launcher.ui.HioneIdentity.Gold
                                    )
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "UV AND AIR",
                                        // UV 0 = not reported (was a flat 4). AQI is the -1
                                        // "no AQI source wired up" sentinel — it used to be printed
                                        // raw, so the tile literally read "AQI -1".
                                        // AQI now comes from Open-Meteo air quality (HioneWeatherExtras)
                                        // when the service has none, which is always so far.
                                        value = "UV " + (if (weather.uvIndex > 0f) "${weather.uvIndex.roundToInt()}" else "—") +
                                            " · AQI " + (if (weather.aqi >= 0) "${weather.aqi}" else HioneWeatherExtras.aqiLabel(extras.air)),
                                        sub = when {
                                            weather.aqiCategory.isNotBlank() -> "Air quality: ${weather.aqiCategory}"
                                            extras.air?.usAqi != null -> "Air quality: ${WxFmt.aqiCategory(extras.air?.usAqi)}"
                                            else -> "Air quality: —"
                                        },
                                        icon = Icons.Default.WbSunny,
                                        color = Color(0xFFFF9100)
                                    )
                                }

                                HioneWeatherExtraBadges()

                                // What Windy adds, the links, and the key fields. Folds to one line.
                                HioneWindyCard()
                            }
                        }

                        1 -> {
                            // TAB 1: METEOGRAM & MULTI-DAY FORECAST
                            Column(
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                HioneMeteogramForecastView(
                                    weather = weather,
                                    gps = gps,
                                    modifier = Modifier.fillMaxWidth(),
                                    isCompact = false
                                )

                                // Windy GFS point forecast next to the free one (only with a point key).
                                HioneWindyPointStrip()

                                Spacer(Modifier.height(4.dp))

                                // Historical Weather Timeline
                                Text(
                                    text = "LAST 24 HOURS",
                                    color = HioneCyan,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.8.sp
                                )

                                if (historyRecords.isEmpty()) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(CutCornerShape(8.dp))
                                            .background(Mascot.color(0x220A222C))
                                            .border(1.dp, CyberGlassBorder, CutCornerShape(8.dp))
                                            .padding(10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Not enough readings saved yet. Check back later.",
                                            color = HioneTextSecondary,
                                            fontSize = 8.5.sp
                                        )
                                    }
                                } else {
                                    val sdf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                                    LazyRow(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(historyRecords.take(12)) { rec ->
                                            Box(
                                                Modifier
                                                    .width(78.dp)
                                                    .clip(CutCornerShape(8.dp))
                                                    .background(Mascot.color(0x33FFC56B))
                                                    .border(1.dp, HioneCyan.copy(alpha = 0.5f), CutCornerShape(8.dp))
                                                    .padding(6.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(
                                                        text = sdf.format(Date(rec.timestamp)),
                                                        color = HioneCyan,
                                                        fontSize = 7.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = AudiowideFont
                                                    )
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        text = "${rec.tempF.roundToInt()}°F",
                                                        color = getWeatherTempColor(rec.tempF.roundToInt()),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Black,
                                                        fontFamily = AudiowideFont
                                                    )
                                                    Text(
                                                        text = "${rec.humidityPct}% RH",
                                                        color = HioneTextSecondary,
                                                        fontSize = 7.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            // TAB 2: RAIN NOWCAST RADAR (RainViewer, storm motion, arrival time)
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                HioneNowcastRadarTab(gps = gps)
                            }
                        }

                        3 -> {
                            // TAB 3: MAP (free OSM/CARTO + RainViewer; Windy layers with a key).
                            // Not in a scroll column: the map takes drags for panning.
                            HioneWeatherMapTab(gps = gps)
                        }

                        4 -> {
                            // TAB 4: WINDY WEBCAMS (needs the webcam key)
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                HioneWebcamTab()
                            }
                        }
                    }
                }
            }
        }

        if (showLocationPicker) {
            Dialog(
                onDismissRequest = { showLocationPicker = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.92f)
                        .clip(CutCornerShape(12.dp))
                        .background(Mascot.color(0xFF04121A))
                        .border(1.dp, HioneCyan, CutCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "SET WEATHER LOCATION",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                            IconButton(onClick = { showLocationPicker = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = HioneNeonPink, modifier = Modifier.size(18.dp))
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Button: Use Live GPS Auto-Detect
                        Button(
                            onClick = {
                                HioneWeatherService.clearManualLocation(ctx)
                                showLocationPicker = false
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x33FFC56B)),
                            border = BorderStroke(1.dp, HioneCyan)
                        ) {
                            Text("USE GPS", color = HioneCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        }

                        Spacer(Modifier.height(8.dp))

                        Text("OR SEARCH A CITY OR ZIP CODE:", color = HioneTextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                HioneWeatherService.searchCity(it) { list ->
                                    searchResults = list
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Seattle, Tokyo, 75201", color = Color.Gray, fontSize = 10.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = HioneCyan,
                                unfocusedBorderColor = Mascot.color(0x66FFC56B)
                            )
                        )

                        Spacer(Modifier.height(6.dp))

                        if (searchResults.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(searchResults) { res ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Mascot.color(0x22FFC56B))
                                            .border(0.5.dp, HioneCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                            .clickable {
                                                HioneWeatherService.setManualLocation(
                                                    ctx,
                                                    res.latitude,
                                                    res.longitude,
                                                    res.name,
                                                    res.region,
                                                    res.country
                                                )
                                                showLocationPicker = false
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                "${res.name}, ${if (res.region.isNotEmpty()) res.region + ", " else ""}${res.country}",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                "Lat: ${String.format(Locale.US, "%.2f", res.latitude)}° Lon: ${String.format(Locale.US, "%.2f", res.longitude)}°",
                                                color = HioneCyan,
                                                fontSize = 7.sp
                                            )
                                        }
                                        Text("SELECT", color = HioneCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                                    }
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
fun TabButton(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) Mascot.color(0x44FFC56B) else Mascot.color(0x1104121A))
            .border(
                1.dp,
                if (isSelected) HioneCyan else CyberGlassBorder.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) Color.White else HioneTextSecondary,
            fontSize = fontSize,
            maxLines = 1,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont
        )
    }
}

fun getWeatherTempColor(tempF: Int): Color {
    return when {
        tempF < 32 -> Color(0xFF7C4DFF) // Freezing (Deep Purple)
        tempF < 50 -> Color(0xFF2979FF) // Cold (Sky Blue)
        tempF < 65 -> Mascot.color(0xFFFFC56B) // Cool (Cyan)
        tempF < 75 -> com.hione.launcher.ui.HioneIdentity.Leek // Mild (Green)
        tempF < 85 -> com.hione.launcher.ui.HioneIdentity.Gold // Warm (Electric Yellow)
        tempF < 95 -> Color(0xFFFF6D00) // Hot (Orange)
        else -> com.hione.launcher.ui.HioneIdentity.Coral       // Extreme Heat (Crimson)
    }
}

/**
 * 8-Point Temperature Trend Sparkline (from OnThe8s SparklineView).
 * Standard meteorological color coding: Purple/Blue (cold) to Yellow/Orange/Red (hot).
 */
@Composable
fun HioneTemperatureSparkline(
    temps: List<Int>,
    modifier: Modifier = Modifier
) {
    if (temps.size < 2) return
    val minT = temps.minOrNull() ?: 50
    val maxT = temps.maxOrNull() ?: 80
    val range = (maxT - minT).coerceAtLeast(1)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CutCornerShape(8.dp))
            .background(Mascot.color(0x33020A0F))
            .border(0.6.dp, HioneCyan.copy(alpha = 0.4f), CutCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NEXT 8 HOURS",
                    color = HioneCyan,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = "RANGE: $minT°F - $maxT°F",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }

            Spacer(Modifier.height(4.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                val w = size.width
                val h = size.height
                val padH = 14f
                val padV = 6f
                val chartW = w - 2 * padH
                val chartH = h - 2 * padV
                val step = chartW / (temps.size - 1)

                val path = Path()
                temps.forEachIndexed { idx, t ->
                    val x = padH + idx * step
                    val y = padV + (1f - (t - minT).toFloat() / range) * chartH
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                val startColor = getWeatherTempColor(temps.first())
                val endColor = getWeatherTempColor(temps.last())

                // Dynamic Thermocline Gradient stroke
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        listOf(startColor, endColor)
                    ),
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )

                // Point dots colored by temperature
                temps.forEachIndexed { idx, t ->
                    val x = padH + idx * step
                    val y = padV + (1f - (t - minT).toFloat() / range) * chartH
                    val ptColor = getWeatherTempColor(t)
                    drawCircle(color = ptColor, radius = 3.2.dp.toPx(), center = Offset(x, y))
                    drawCircle(color = Color.White, radius = 1.4.dp.toPx(), center = Offset(x, y))
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                temps.forEach { t ->
                    val ptColor = getWeatherTempColor(t)
                    Text(
                        text = "$t°",
                        color = ptColor,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }
            }
        }
    }
}

@Composable
fun WeatherMetricBadge(
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
            .background(Mascot.color(0x33FFC56B))
            .border(1.dp, color.copy(alpha = 0.45f), CutCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
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
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sub,
                    color = color,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
