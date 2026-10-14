package com.hione.tools.clock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.GlassDialog
import com.hione.tools.ui.GlassIconButton
import com.hione.tools.ui.GlassTextButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.HioneMono
import com.hione.tools.ui.HioneTopBar
import com.hione.tools.ui.glass
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WorldClockTab(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val rev = rememberClockRevision()
    val cities = remember(rev) { ClockStore.cities(ctx) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // One tick per second, aligned to the second boundary so the hand never looks late.
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000 - now % 1000) } }
    var adding by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 96.dp)) {
            item { HioneTopBar("Clock", actions = { GlassIconButton(Icons.Outlined.Settings, "Clock settings", onSettings) }) }
            item { LocalClock(now) }
            items(cities, key = { it }) { id ->
                CityRow(id, now, onRemove = { ClockStore.saveCities(ctx, cities - id) })
            }
            if (cities.isEmpty()) item {
                Text("Add cities to see their time here.", color = Hione.Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(18.dp)) {
            GlassIconButton(Icons.Filled.Add, "Add city", { adding = true }, size = 64.dp, tint = Hione.PinkSoft)
        }
    }
    if (adding) CityPickerDialog(cities, onPick = { ClockStore.saveCities(ctx, (cities + it).distinct()) }, onDismiss = { adding = false })
}

@Composable
private fun LocalClock(now: Long) {
    val ctx = LocalContext.current
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(220.dp).glass(RoundedCornerShape(110.dp)), contentAlignment = Alignment.Center) {
            AnalogFace(zdt, Modifier.fillMaxSize().padding(14.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ClockFormat.time(ctx, zdt.hour, zdt.minute), color = Hione.Text, fontSize = 54.sp, fontFamily = HioneMono, fontWeight = FontWeight.Light)
            Text(String.format(Locale.US, ":%02d", zdt.second), color = Hione.TealGlow, fontSize = 22.sp, fontFamily = HioneMono, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
            val ap = ClockFormat.amPm(ctx, zdt.hour)
            if (ap.isNotEmpty()) Text(" $ap", color = Hione.Muted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(zdt.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())), color = Hione.TextDim, fontSize = 16.sp)
        Text(ZoneId.systemDefault().getDisplayName(TextStyle.FULL, Locale.getDefault()), color = Hione.Muted, fontSize = 13.sp)
    }
}

/** Analog face: twelve tick marks, hour and minute hands in white, a pink second hand. */
@Composable
fun AnalogFace(t: ZonedDateTime, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val major = i % 5 == 0
            val inner = r * if (major) 0.84f else 0.9f
            drawLine(
                if (major) Hione.TealGlow else Hione.Faint,
                Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                Offset(c.x + r * 0.96f * cos(a).toFloat(), c.y + r * 0.96f * sin(a).toFloat()),
                strokeWidth = if (major) 3.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round
            )
        }
        val sec = t.second.toDouble()
        val min = t.minute + sec / 60
        val hr = (t.hour % 12) + min / 60
        hand(hr * 30, r * 0.5f, 5.dp.toPx(), Hione.Text)
        hand(min * 6, r * 0.74f, 3.5.dp.toPx(), Hione.TextDim)
        hand(sec * 6, r * 0.82f, 1.6.dp.toPx(), Hione.PinkSoft)
        drawCircle(Hione.PinkSoft, radius = 4.dp.toPx())
        drawCircle(Brush.radialGradient(listOf(Hione.Teal.copy(alpha = 0.18f), Color.Transparent)), radius = r, style = Stroke(r * 0.12f))
    }
}

private fun DrawScope.hand(deg: Double, len: Float, width: Float, color: Color) {
    val a = Math.toRadians(deg - 90)
    drawLine(color, center, Offset(center.x + len * cos(a).toFloat(), center.y + len * sin(a).toFloat()), strokeWidth = width, cap = StrokeCap.Round)
}

fun cityName(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

@Composable
private fun CityRow(zoneId: String, now: Long, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return
    val there = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
    val here = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
    val diffMin = (there.offset.totalSeconds - here.offset.totalSeconds) / 60
    val dayDiff = there.toLocalDate().toEpochDay() - here.toLocalDate().toEpochDay()
    val rel = buildString {
        append(when { dayDiff > 0 -> "Tomorrow"; dayDiff < 0 -> "Yesterday"; else -> "Today" })
        if (diffMin != 0) {
            val h = diffMin / 60; val m = kotlin.math.abs(diffMin % 60)
            append(", ").append(if (diffMin > 0) "+" else "−").append(kotlin.math.abs(h))
            if (m != 0) append(":").append(String.format(Locale.US, "%02d", m))
            append(" h")
        } else append(", same time")
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).glass(RoundedCornerShape(20.dp)).padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(cityName(zoneId), color = Hione.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rel, color = Hione.Muted, fontSize = 13.sp)
        }
        Text(ClockFormat.timeFull(ctx, there.hour, there.minute), color = Hione.TealGlow, fontSize = 26.sp, fontFamily = HioneMono)
        GlassIconButton(Icons.Outlined.Close, "Remove", onRemove, plain = true, tint = Hione.Faint)
    }
}

private val ALL_CITIES: List<String> by lazy {
    // Region/City IDs only: skip "Etc/GMT+3", "SystemV/..." and bare abbreviations.
    val regions = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")
    ZoneId.getAvailableZoneIds().filter { id -> id.substringBefore('/') in regions && id.contains('/') }
        .sortedBy { cityName(it) }
}

@Composable
private fun CityPickerDialog(existing: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(q) {
        val s = q.trim()
        if (s.isEmpty()) ALL_CITIES else ALL_CITIES.filter { it.replace('_', ' ').contains(s, ignoreCase = true) }
    }
    GlassDialog(onDismiss, title = "Add a city", scrollable = false, buttons = { GlassTextButton("Close", onDismiss) }) {
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Hione.Muted) },
            placeholder = { Text("Search", color = Hione.Faint) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hione.Teal, unfocusedBorderColor = Hione.Faint, cursorColor = Hione.Teal),
            shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(8.dp))
        val today = LocalDate.now()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            items(filtered, key = { it }) { id ->
                val added = id in existing
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .clickable(enabled = !added) { onPick(id); onDismiss() }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(cityName(id), color = if (added) Hione.Faint else Hione.Text, fontSize = 16.sp)
                        Text(id.substringBefore('/'), color = Hione.Muted, fontSize = 12.sp)
                    }
                    val off = runCatching { ZoneId.of(id).rules.getOffset(today.atStartOfDay(ZoneId.of(id)).toInstant()).id.replace("Z", "+00:00") }.getOrDefault("")
                    Text("UTC$off", color = Hione.Muted, fontSize = 12.sp, fontFamily = HioneMono)
                }
            }
        }
    }
}
