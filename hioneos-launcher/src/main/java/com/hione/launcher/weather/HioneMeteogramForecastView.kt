package com.hione.launcher.weather
import com.hione.launcher.Mascot
import com.hione.launcher.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.launcher.AudiowideFont
import com.hione.launcher.CyberGlassBorder
import com.hione.launcher.HioneCyan
import com.hione.launcher.HioneNeonPink
import com.hione.launcher.theme.HioneDiurnalTheme
import kotlin.math.roundToInt

/**
 * Windy-Style High-Precision Multi-Day Meteogram Forecast Strip.
 * Directly integrates the OnThe8s astronomical solar arc and 24-hour meteorological table:
 * 1. Astronomical Solar Arc (Sunrise/Sunset progress track, day/night transit).
 * 2. Look-ahead precipitation arrival ("RAIN IN 2 HRS", "TSTORM NOW", "CLEAR NEXT 24H").
 * 3. Future-indexed 24-Hour time headers starting from NOW.
 * 4. Meteorological condition icons (Day Sun vs Night Moon).
 * 5. Temperature values, rain gauges, and directional wind vectors.
 */
@Composable
fun HioneMeteogramForecastView(
    weather: HioneWeatherService.WeatherCondition,
    gps: HioneWeatherService.GpsTelemetry,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    val palette by HioneDiurnalTheme.rememberDiurnalPalette()
    val scrollState = rememberScrollState()

    // Hourly points come ONLY from the fetched Open-Meteo hourly series. With no series there is
    // no forecast to draw — the strip shows an honest empty row instead of a synthesized curve.
    val hasData = weather.lastUpdatedTime > 0L
    val points = remember(weather.hourlyMeteogram) {
        if (isCompact) weather.hourlyMeteogram.take(12) else weather.hourlyMeteogram.take(24)
    }

    val itemWidth = if (isCompact) 44.dp else 52.dp

    Box(
        modifier = modifier
            .clip(CutCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Mascot.color(0xF0061822),
                        Mascot.color(0xFA030D14)
                    )
                )
            )
            .border(1.dp, palette.primary.copy(alpha = 0.75f), CutCornerShape(10.dp))
            .padding(vertical = 6.dp, horizontal = 6.dp)
    ) {
        Column {
            // Top Meteogram Title & Location Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📊", fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "FORECAST",
                        color = palette.primary,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    if (weather.nextPrecipLabel.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(CutCornerShape(3.dp))
                                .background(Mascot.color(0xFFFFC56B).copy(alpha = 0.25f))
                                .border(0.6.dp, Mascot.color(0xFFFFC56B), CutCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = weather.nextPrecipLabel,
                                color = Mascot.color(0xFFFFC56B),
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                        }
                    }
                }

                Text(
                    text = "${if (hasData) "${weather.tempF.roundToInt()}°F" else "—°F"} · ${if (gps.city.isNotEmpty()) gps.city else "No location"}",
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }

            Spacer(Modifier.height(4.dp))

            // Horizontally Scrollable Meteogram Strip Table
            Box(Modifier.fillMaxWidth()) {
                if (points.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (hasData) "No hourly series in the last fetch" else "No forecast fetched yet",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                }
                Row(
                    Modifier
                        .horizontalScroll(scrollState)
                        .padding(horizontal = 4.dp)
                ) {
                    // Day column grouping headers
                    val dayGroups = remember(points) {
                        points.groupBy { it.dayLabel }
                    }

                    dayGroups.forEach { (dayName, dayPoints) ->
                        Column(
                            Modifier
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Mascot.color(0x4404141E))
                                .border(0.6.dp, CyberGlassBorder.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        ) {
                            // Day Name Header
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(palette.primary.copy(alpha = 0.15f))
                                    .padding(vertical = 2.dp, horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = dayName.uppercase(),
                                    color = palette.primary,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = AudiowideFont
                                )
                            }

                            // Hourly Data Columns Row
                            Row {
                                dayPoints.forEach { pt ->
                                    Column(
                                        Modifier
                                            .width(itemWidth)
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        // 1. Time Label (24-Hour Military Time)
                                        Text(
                                            text = pt.timeLabel,
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = AudiowideFont
                                        )

                                        Spacer(Modifier.height(2.dp))

                                        // 2. Weather Condition Icon (Sun vs Moon based on day state)
                                        Text(
                                            text = pt.icon,
                                            fontSize = 12.sp
                                        )

                                        Spacer(Modifier.height(2.dp))

                                        // 3. Temperature Value
                                        Text(
                                            text = "${pt.tempF.roundToInt()}°",
                                            color = when {
                                                pt.tempF >= 85f -> Color(0xFFFF5252)
                                                pt.tempF >= 70f -> com.hione.launcher.ui.HioneIdentity.Gold
                                                pt.tempF >= 55f -> com.hione.launcher.ui.HioneIdentity.Leek
                                                else -> Mascot.color(0xFFFFC56B)
                                            },
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = AudiowideFont
                                        )

                                        Spacer(Modifier.height(3.dp))

                                        // 4. Rain Precipitation Percentage & Drop
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Text("💧", fontSize = 7.sp)
                                            Spacer(Modifier.width(1.dp))
                                            Text(
                                                text = "${pt.precipProbPct}%",
                                                color = if (pt.precipProbPct > 20) Mascot.color(0xFFFFC56B) else Color.White.copy(alpha = 0.6f),
                                                fontSize = 7.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                        }

                                        Spacer(Modifier.height(3.dp))

                                        // 5. Wind Speed Cell (Color Coded Speed)
                                        val windSpd = pt.windSpeedMph.roundToInt()
                                        val windCellColor = when {
                                            windSpd >= 25 -> com.hione.launcher.ui.HioneIdentity.Coral // Severe gale
                                            windSpd >= 15 -> Color(0xFFFF9100) // Moderate
                                            windSpd >= 8  -> com.hione.launcher.ui.HioneIdentity.Leek // Light breeze
                                            else          -> Mascot.color(0xFFFFC56B) // Gentle
                                        }
                                        Box(
                                            Modifier
                                                .width(itemWidth - 6.dp)
                                                .clip(CutCornerShape(3.dp))
                                                .background(windCellColor.copy(alpha = 0.35f))
                                                .padding(vertical = 1.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$windSpd",
                                                color = Color.White,
                                                fontSize = 7.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont
                                            )
                                        }

                                        Spacer(Modifier.height(2.dp))

                                        // 6. Wind Direction Arrow
                                        Text(
                                            text = "↑",
                                            color = palette.primary,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier.rotate(pt.windDirectionDeg.toFloat())
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Integrated Solar Arc Bar from OnThe8s
            HioneSolarArcTrack(
                sunrise = weather.sunrise,
                sunset = weather.sunset,
                solarFraction = weather.solarFraction,
                isDay = weather.isDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Astronomical Solar Arc Progress Bar (Direct Lift from OnThe8s SolarArcView).
 * Shows sunrise to sunset trajectory, glowing solar traveler, and day/night progress.
 */
@Composable
fun HioneSolarArcTrack(
    sunrise: String,
    sunset: String,
    solarFraction: Float,
    isDay: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Mascot.color(0x33020A0F))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Blank sunrise/sunset = no fetched astronomy. Hoisted out of the Row so the Canvas below
        // shares the gate: it used to draw a gold "daytime sun" parked at sunrise (solarFraction
        // defaults to 0f and isDay to true) right next to the label "NO SUN DATA". Note that
        // lastUpdatedTime > 0 is NOT enough here — restoreLastWeather() republishes a cached
        // condition with blank sunrise/sunset and solarFraction 0f.
        val hasAstro = sunrise.isNotBlank() && sunset.isNotBlank()
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SUNRISE ${sunrise.ifBlank { "—" }}",
                    color = Color(0xFFFFB300),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = when {
                        !hasAstro -> "NO SUN DATA"
                        isDay -> "DAYLIGHT · ${(solarFraction * 100).roundToInt()}% GONE"
                        else -> "NIGHT"
                    },
                    color = if (!hasAstro) Color.White.copy(alpha = 0.5f) else if (isDay) HioneCyan else Mascot.color(0xFFB9C2D0),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = "SUNSET ${sunset.ifBlank { "—" }}",
                    color = Mascot.color(0xFFFF80AB),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }

            Spacer(Modifier.height(3.dp))

            // Graphical Solar Arc Bar
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            ) {
                val w = size.width
                val h = size.height
                val trackY = h / 2f

                // Base Track Line
                drawLine(
                    color = Mascot.color(0x33FFC56B),
                    start = Offset(0f, trackY),
                    end = Offset(w, trackY),
                    strokeWidth = h,
                    cap = StrokeCap.Round
                )

                // Progress fill + sun/moon marker ONLY with real astronomy. Without it the strip is
                // just the empty base track — no marker is placed at a transit that was never known.
                if (hasAstro) {
                    val gradientBrush = Brush.horizontalGradient(
                        listOf(
                            Color(0xFFFF6D00),
                            com.hione.launcher.ui.HioneIdentity.Gold,
                            Mascot.color(0xFFFFC56B),
                            Mascot.color(0xFFFF80AB)
                        )
                    )

                    drawLine(
                        brush = gradientBrush,
                        start = Offset(0f, trackY),
                        end = Offset(w * solarFraction.coerceIn(0f, 1f), trackY),
                        strokeWidth = h,
                        cap = StrokeCap.Round
                    )

                    // Sun / Moon Indicator Dot with Glow
                    val dotX = (w * solarFraction.coerceIn(0f, 1f)).coerceIn(h, w - h)
                    val dotColor = if (isDay) com.hione.launcher.ui.HioneIdentity.Gold else Mascot.color(0xFFB9C2D0)

                    drawCircle(
                        color = dotColor.copy(alpha = 0.35f),
                        radius = h * 1.8f,
                        center = Offset(dotX, trackY)
                    )
                    drawCircle(
                        color = dotColor,
                        radius = h * 0.9f,
                        center = Offset(dotX, trackY)
                    )
                }
            }
        }
    }
}
