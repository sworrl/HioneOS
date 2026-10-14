package com.hione.player.weather

import com.hione.player.Mascot
import com.hione.player.mascotPainter
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hione.player.R
import com.hione.player.*
import com.hione.player.metrics.HioneMetricDatabase
import com.hione.player.ui.swipeUpFromBottomToDismiss
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

    var historyRecords by remember { mutableStateOf<List<HioneMetricDatabase.WeatherRecord>>(emptyList()) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        historyRecords = HioneMetricDatabase.getInstance(ctx).getRecentWeatherHistory(24)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xEB0C0D10))
            .clickable { onDismissRequest() }
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
                    contentDescription = "${Mascot.name} artwork",
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            Column {
                                Text(
                                    text = "WEATHER",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont,
                                    letterSpacing = 0.8.sp
                                )
                                // FAKE-DATA FIX: with no fix this line read "Station: Local Station ·
                                // Lat: 0.000° Lon: 0.000°" — an invented station name and the
                                // unset-coordinate sentinel formatted as a real position (0,0 is in
                                // the Gulf of Guinea). No fix now reads "awaiting GPS fix".
                                Text(
                                    text = run {
                                        val hasFix = gps.isLocked && (gps.latitude != 0.0 || gps.longitude != 0.0)
                                        val stationPrefix = if (weather.nwsStationId.isNotEmpty()) weather.nwsStationId + " · " else ""
                                        val place = when {
                                            gps.fuzzyLocation.isNotEmpty() -> gps.fuzzyLocation
                                            gps.city.isNotEmpty() -> gps.city
                                            else -> "—"
                                        }
                                        val coords = if (hasFix)
                                            " · Lat: ${String.format(Locale.US, "%.3f", gps.latitude)}° Lon: ${String.format(Locale.US, "%.3f", gps.longitude)}°"
                                        else " · awaiting GPS fix"
                                        "Station: $stationPrefix$place$coords"
                                    },
                                    color = HioneCyan,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // 3D Embossed Close Button (Automated weather - no manual refresh needed)
                        com.hione.player.network.Cyber3dIconButton(
                            onClick = onDismissRequest,
                            icon = Icons.Default.Close,
                            contentDescription = "Close",
                            accentColor = HioneNeonPink
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Cyber 3-Tab Switcher (Overview vs Meteogram vs Live Doppler Radar)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TabButton(
                            title = "OVERVIEW",
                            isSelected = selectedTab == 0,
                            modifier = Modifier.weight(1f)
                        ) { selectedTab = 0 }

                        TabButton(
                            title = "FORECAST",
                            isSelected = selectedTab == 1,
                            modifier = Modifier.weight(1f)
                        ) { selectedTab = 1 }

                        TabButton(
                            title = "RADAR",
                            isSelected = selectedTab == 2,
                            modifier = Modifier.weight(1f)
                        ) { selectedTab = 2 }
                    }

                    Spacer(Modifier.height(8.dp))

                    when (selectedTab) {
                        0 -> {
                            // TAB 0: HIGH-DENSITY OBSERVATORY OVERVIEW (No scrolling needed)
                            Column(
                                modifier = Modifier.fillMaxSize(),
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
                                        fontSize = 7.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = AudiowideFont
                                    )
                                    if (weather.nextPrecipLabel.isNotEmpty()) {
                                        Text(
                                            text = "${weather.nextPrecipLabel}",
                                            color = Color(0xFFFFD600),
                                            fontSize = 7.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = AudiowideFont
                                        )
                                    }
                                }

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
                                                    text = weather.tempF.wxInt("°"),
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
                                                text = "${weather.summary} · Feels ${weather.feelsLikeF.wxInt("°F")}",
                                                color = Color.White,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(Modifier.height(1.dp))
                                            Text(
                                                text = "▲ High ${weather.highTempF.wxInt("°F")}  ▼ Low ${weather.lowTempF.wxInt("°F")}",
                                                color = HioneTextSecondary,
                                                fontSize = 8.5.sp
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
                                                text = "${weather.moonPhase.phaseName}",
                                                color = Mascot.color(0xFFFFD59A),
                                                fontSize = 7.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = "${weather.moonPhase.illuminationPct}% illuminated",
                                                color = HioneTextSecondary,
                                                fontSize = 6.5.sp
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
                                    Column {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "SUNRISE ${weather.sunrise}",
                                                color = Color(0xFFFFD600),
                                                fontSize = 7.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = if (weather.isDay) "DAYTIME" else "NIGHT",
                                                color = if (weather.isDay) Color(0xFFFFD600) else Mascot.color(0xFFFFD59A),
                                                fontSize = 7.5.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = AudiowideFont
                                            )
                                            Text(
                                                text = "SUNSET ${weather.sunset}",
                                                color = Color(0xFFFF9100),
                                                fontSize = 7.5.sp,
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
                                                    .fillMaxWidth(weather.solarFraction)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(
                                                        Brush.horizontalGradient(
                                                            listOf(Color(0xFFFFD600), Color(0xFFFF9100), HioneCyan)
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
                                        value = "${weather.windSpeedMph.wxInt(" mph")} ${weather.windDirectionCompass}",
                                        sub = if (weather.windGustMph.isNaN()) "Gusts not reported" else "Gusts to ${weather.windGustMph.wxInt(" mph")}",
                                        icon = Icons.Default.Air,
                                        color = HioneCyan
                                    )
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "HUMIDITY / DEW",
                                        value = if (weather.humidityPct < 0) "—" else "${weather.humidityPct}%",
                                        sub = "Dew Point ${weather.dewPointF.wxInt("°F")}",
                                        icon = Icons.Default.WaterDrop,
                                        color = Color(0xFF2979FF)
                                    )
                                }

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "BAROMETER / CLOUD",
                                        value = "${String.format(Locale.US, "%.2f", weather.pressureInHg)} inHg",
                                        sub = "${weather.cloudCoverPct}% Cloud Cover",
                                        icon = Icons.Default.Speed,
                                        color = Color(0xFFFFD600)
                                    )
                                    WeatherMetricBadge(
                                        modifier = Modifier.weight(1f),
                                        title = "SOLAR / UV & AIR",
                                        value = "UV ${weather.uvIndex.wxInt()} · AQI ${if (weather.aqi < 0) "—" else weather.aqi.toString()}",
                                        sub = if (weather.aqiCategory.isEmpty()) "Air quality: not available" else "Air Quality: ${weather.aqiCategory}",
                                        icon = Icons.Default.WbSunny,
                                        color = Color(0xFFFF9100)
                                    )
                                }
                            }
                        }

                        1 -> {
                            // TAB 1: METEOGRAM & MULTI-DAY FORECAST
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                HioneMeteogramForecastView(
                                    weather = weather,
                                    gps = gps,
                                    modifier = Modifier.fillMaxWidth(),
                                    isCompact = false
                                )

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
                                            text = "Collecting readings. Check back in a bit.",
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
                                                        text = "RH ${rec.humidityPct}%",
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
                            // TAB 2: LIVE DOPPLER RADAR SWEEP
                            HioneLiveDopplerRadarTab(gps = gps, weather = weather)
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
            fontSize = 8.sp,
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
        tempF < 75 -> Color(0xFF00E676) // Mild (Green)
        tempF < 85 -> Color(0xFFFFD600) // Warm (Electric Yellow)
        tempF < 95 -> Color(0xFFFF6D00) // Hot (Orange)
        else -> Color(0xFFFF1744)       // Extreme Heat (Crimson)
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
                    text = "8-HOUR TEMPERATURE TREND",
                    color = HioneCyan,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = "RANGE: $minT°F - $maxT°F",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 7.sp,
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
                        fontSize = 7.sp,
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
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sub,
                    color = color,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

data class RadarFrameInfo(
    val time: Long,
    val timeLabel: String,
    val bitmap: ImageBitmap?
)

/**
 * Oki Hione Live Doppler Radar Suite (RainViewer High-Res Composite).
 * Fetches real timestamped Doppler radar tiles over dark basemap with playback scrubber and dBZ scale.
 */
@Composable
fun HioneLiveDopplerRadarTab(
    gps: HioneWeatherService.GpsTelemetry,
    weather: HioneWeatherService.WeatherCondition
) {
    var pastFrames by remember { mutableStateOf<List<RadarFrameInfo>>(emptyList()) }
    var basemapBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var currentFrameIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }

    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarAngle"
    )

    // Load Live RainViewer Radar Frames & CartoDB Dark Basemap
    LaunchedEffect(gps.latitude, gps.longitude) {
        isLoading = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val lat = if (gps.latitude != 0.0) gps.latitude else 35.0844
                val lon = if (gps.longitude != 0.0) gps.longitude else -106.6504
                val zoom = 6
                val tileX = ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
                val latRad = Math.toRadians(lat)
                val tileY = ((1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * (1 shl zoom)).toInt()

                // 1. Download CartoDB Dark basemap tile
                try {
                    val baseMapUrl = "https://a.basemaps.cartocdn.com/dark_all/$zoom/$tileX/$tileY.png"
                    val baseReq = java.net.URL(baseMapUrl).openConnection() as java.net.HttpURLConnection
                    baseReq.setRequestProperty("User-Agent", "HioneMeteorology/1.0 (contact@falcontechnix.com)")
                    baseReq.connectTimeout = 6000
                    baseReq.readTimeout = 6000
                    val baseBytes = baseReq.inputStream.use { it.readBytes() }
                    val baseBmp = BitmapFactory.decodeByteArray(baseBytes, 0, baseBytes.size)
                    basemapBitmap = baseBmp?.asImageBitmap()
                } catch (_: Throwable) {}

                // 2. Fetch RainViewer radar metadata
                val rvReq = java.net.URL("https://api.rainviewer.com/public/weather-maps.json").openConnection() as java.net.HttpURLConnection
                rvReq.setRequestProperty("User-Agent", "HioneMeteorology/1.0 (contact@falcontechnix.com)")
                rvReq.connectTimeout = 6000
                rvReq.readTimeout = 6000
                val rvJsonStr = rvReq.inputStream.bufferedReader().use { it.readText() }
                val rootJson = org.json.JSONObject(rvJsonStr)
                val host = rootJson.optString("host", "https://tilecache.rainviewer.com")
                val radarObj = rootJson.optJSONObject("radar")
                val pastArray = radarObj?.optJSONArray("past")

                val frames = mutableListOf<RadarFrameInfo>()
                if (pastArray != null) {
                    val count = pastArray.length()
                    val startIndex = (count - 6).coerceAtLeast(0)
                    for (i in startIndex until count) {
                        val item = pastArray.getJSONObject(i)
                        val t = item.getLong("time")
                        val p = item.getString("path")
                        val tileUrl = "$host$p/256/$zoom/$tileX/$tileY/2/1_1.png"

                        try {
                            val tileReq = java.net.URL(tileUrl).openConnection() as java.net.HttpURLConnection
                            tileReq.setRequestProperty("User-Agent", "HioneMeteorology/1.0 (contact@falcontechnix.com)")
                            tileReq.connectTimeout = 5000
                            tileReq.readTimeout = 5000
                            val tileBytes = tileReq.inputStream.use { it.readBytes() }
                            val tileBmp = BitmapFactory.decodeByteArray(tileBytes, 0, tileBytes.size)

                            val cal = java.util.Calendar.getInstance()
                            cal.timeInMillis = t * 1000L
                            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                            val label = sdf.format(cal.time)

                            frames.add(RadarFrameInfo(time = t, timeLabel = label, bitmap = tileBmp?.asImageBitmap()))
                        } catch (_: Throwable) {}
                    }
                }
                pastFrames = frames
                currentFrameIndex = if (frames.isNotEmpty()) frames.size - 1 else 0
            } catch (t: Throwable) {
                android.util.Log.e("HioneWeatherRadar", "Failed to fetch live radar frames", t)
            } finally {
                isLoading = false
            }
        }
    }

    // Playback loop timer
    LaunchedEffect(isPlaying, pastFrames.size) {
        if (isPlaying && pastFrames.isNotEmpty()) {
            while (true) {
                kotlinx.coroutines.delay(650L)
                currentFrameIndex = (currentFrameIndex + 1) % pastFrames.size
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // Radar Header & Metadata
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "RADAR",
                    color = HioneCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = "RANGE: 80NM · BASE REFLECTIVITY (0.5°) · dBZ SCALE",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (pastFrames.isNotEmpty() && currentFrameIndex in pastFrames.indices) {
                Box(
                    Modifier
                        .clip(CutCornerShape(4.dp))
                        .background(Mascot.color(0x33FFC56B))
                        .border(1.dp, HioneCyan, CutCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "FRAME ${pastFrames[currentFrameIndex].timeLabel}",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Radar Map Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(CutCornerShape(12.dp))
                .background(Color(0xFF02090E))
                .border(1.dp, HioneCyan.copy(alpha = 0.8f), CutCornerShape(12.dp))
        ) {
            // 1. Dark Basemap Tile (if loaded)
            basemapBitmap?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = "Radar Basemap",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // 2. Live RainViewer Doppler Radar Tile (Current Frame)
            if (pastFrames.isNotEmpty() && currentFrameIndex in pastFrames.indices) {
                pastFrames[currentFrameIndex].bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "Radar Echo Tile",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.85f
                    )
                }
            }

            // 3. Cyber Range Rings & Tactical Scanner Grid
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = kotlin.math.min(size.width, size.height) / 2f - 8f

                // Range Rings: 20NM, 40NM, 60NM, 80NM
                val rings = listOf(0.25f, 0.50f, 0.75f, 1.0f)
                rings.forEach { frac ->
                    drawCircle(
                        color = HioneCyan.copy(alpha = 0.25f),
                        radius = maxRadius * frac,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Crosshairs
                drawLine(
                    color = HioneCyan.copy(alpha = 0.35f),
                    start = Offset(center.x - maxRadius, center.y),
                    end = Offset(center.x + maxRadius, center.y),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = HioneCyan.copy(alpha = 0.35f),
                    start = Offset(center.x, center.y - maxRadius),
                    end = Offset(center.x, center.y + maxRadius),
                    strokeWidth = 1.dp.toPx()
                )

                // Station Center Point Marker
                drawCircle(Color(0xFFFF1744), radius = 4.dp.toPx(), center = center)
                drawCircle(Color.White, radius = 2.dp.toPx(), center = center)

                // Rotating 360-degree radar beam sweep
                val sweepRad = Math.toRadians(sweepAngle.toDouble())
                val beamEndX = center.x + (maxRadius * kotlin.math.cos(sweepRad)).toFloat()
                val beamEndY = center.y + (maxRadius * kotlin.math.sin(sweepRad)).toFloat()
                drawLine(
                    color = HioneCyan.copy(alpha = 0.9f),
                    start = center,
                    end = Offset(beamEndX, beamEndY),
                    strokeWidth = 2.dp.toPx()
                )
            }

            if (isLoading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = HioneCyan, modifier = Modifier.size(24.dp))
                }
            }

            // Radar Range Labels
            Text(
                "80NM",
                color = HioneCyan,
                fontSize = 7.sp,
                fontFamily = AudiowideFont,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
            )
            Text(
                "40NM",
                color = HioneCyan.copy(alpha = 0.7f),
                fontSize = 6.5.sp,
                fontFamily = AudiowideFont,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-32).dp)
            )
            Text(
                "LOCATION: ${if (gps.city.isNotEmpty()) gps.city else "STATION"}",
                color = Color.White,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Playback Controls & Frame Scrubber
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { isPlaying = !isPlaying },
                colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x33FFC56B)),
                border = BorderStroke(1.dp, HioneCyan),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(if (isPlaying) "PAUSE" else "PLAY", color = Color.White, fontSize = 8.sp, fontFamily = AudiowideFont)
            }

            if (pastFrames.isNotEmpty()) {
                Slider(
                    value = currentFrameIndex.toFloat(),
                    onValueChange = {
                        isPlaying = false
                        currentFrameIndex = it.toInt().coerceIn(0, pastFrames.size - 1)
                    },
                    valueRange = 0f..(pastFrames.size - 1).toFloat(),
                    steps = (pastFrames.size - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = HioneCyan,
                        activeTrackColor = HioneCyan,
                        inactiveTrackColor = Mascot.color(0x33FFC56B)
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // dBZ Reflectivity Scale Legend Bar
        Column(
            Modifier
                .fillMaxWidth()
                .clip(CutCornerShape(6.dp))
                .background(Mascot.color(0x9904121A))
                .border(0.8.dp, CyberGlassBorder.copy(alpha = 0.5f), CutCornerShape(6.dp))
                .padding(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("REFLECTIVITY (dBZ)", color = HioneCyan, fontSize = 7.sp, fontFamily = AudiowideFont)
                Text("15    30    45    55    65+", color = Color.White.copy(alpha = 0.7f), fontSize = 7.sp, fontFamily = AudiowideFont)
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Mascot.color(0xFFFFC56B),
                                Color(0xFF00E676),
                                Color(0xFFFFD600),
                                Color(0xFFFF9100),
                                Color(0xFFFF1744),
                                Mascot.color(0xFFFF007F)
                            )
                        )
                    )
            )
        }
    }
}
