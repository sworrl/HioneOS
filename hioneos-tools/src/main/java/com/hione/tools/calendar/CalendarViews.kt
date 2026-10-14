package com.hione.tools.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.EmptyState
import com.hione.tools.ui.Hione
import com.hione.tools.ui.SectionLabel
import com.hione.tools.ui.glass
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

private fun byDay(insts: List<Inst>, from: LocalDate, days: Int): Map<LocalDate, List<Inst>> {
    val zone = ZoneId.systemDefault()
    val end = from.plusDays(days.toLong() - 1)
    val map = HashMap<LocalDate, MutableList<Inst>>()
    for (i in insts) {
        var d = maxOf(i.firstDay(zone), from)
        val last = minOf(i.lastDay(zone), end)
        while (!d.isAfter(last)) { map.getOrPut(d) { ArrayList() }.add(i); d = d.plusDays(1) }
    }
    // All-day first, then by start time.
    return map.mapValues { (_, v) -> v.sortedWith(compareByDescending<Inst> { it.allDay }.thenBy { it.begin }) }
}

// ---------------------------------------------------------------------------------------------
// Month
// ---------------------------------------------------------------------------------------------

@Composable
fun MonthView(month: YearMonth, selected: LocalDate, insts: List<Inst>, onSelect: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(first))
    val map = remember(insts, start) { byDay(insts, start, 42) }
    val today = LocalDate.now()
    val dayEvents = map[selected].orEmpty()

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            (0 until 7).forEach { i ->
                Text(start.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    color = Hione.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), rimAlpha = 0.3f).padding(4.dp)) {
            for (w in 0 until 6) {
                Row(Modifier.fillMaxWidth()) {
                    for (dIdx in 0 until 7) {
                        val d = start.plusDays((w * 7 + dIdx).toLong())
                        val inMonth = d.month == month.month
                        val sel = d == selected
                        val evs = map[d].orEmpty()
                        Column(
                            Modifier.weight(1f).height(48.dp).padding(1.5.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Hione.Teal.copy(alpha = 0.22f) else Color.Transparent)
                                .border(if (sel) 1.dp else 0.dp, if (sel) Hione.TealGlow.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { onSelect(d) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).background(if (d == today) Hione.PinkSoft else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(d.dayOfMonth.toString(), fontSize = 14.sp,
                                    color = when { d == today -> Color.White; inMonth -> Hione.Text; else -> Hione.Faint },
                                    fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal)
                            }
                            Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                evs.take(3).forEach { e -> Box(Modifier.size(5.dp).clip(CircleShape).background(e.color.asColor())) }
                                if (evs.size > 3) Text("+", color = Hione.Muted, fontSize = 8.sp)
                            }
                        }
                    }
                }
            }
        }
        SectionLabel(CalFormat.dayHeader(selected))
        if (dayEvents.isEmpty()) {
            Text("Nothing planned. Tap + to add an event.", color = Hione.Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 90.dp)) {
                items(dayEvents, key = { "${it.eventId}-${it.begin}" }) { e -> EventRow(e, onClick = { openEvent(ctx, e) }) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Week / Day time grid
// ---------------------------------------------------------------------------------------------

private data class Placed(val inst: Inst, val startMin: Int, val endMin: Int, val col: Int, val cols: Int)

/** Side-by-side layout for overlapping events: greedy columns within each overlap cluster. */
private fun layoutDay(list: List<Inst>, day: LocalDate, zone: ZoneId): List<Placed> {
    val dayStart = day.startMillis(zone)
    val dayEnd = day.plusDays(1).startMillis(zone)
    val items = list.filter { !it.allDay }.map { i ->
        val s = ((maxOf(i.begin, dayStart) - dayStart) / 60_000).toInt()
        val e = ((minOf(i.end, dayEnd) - dayStart) / 60_000).toInt().coerceAtLeast(s + 20) // tiny events stay tappable
        Triple(i, s, e.coerceAtMost(24 * 60))
    }.sortedWith(compareBy({ it.second }, { -(it.third - it.second) }))

    val out = ArrayList<Placed>()
    var cluster = ArrayList<Triple<Inst, Int, Int>>()
    val colEnds = ArrayList<Int>()
    val colOf = HashMap<Triple<Inst, Int, Int>, Int>()
    var clusterEnd = -1
    fun flush() {
        val n = colEnds.size.coerceAtLeast(1)
        cluster.forEach { t -> out += Placed(t.first, t.second, t.third, colOf[t] ?: 0, n) }
        cluster = ArrayList(); colEnds.clear(); colOf.clear(); clusterEnd = -1
    }
    for (t in items) {
        if (cluster.isNotEmpty() && t.second >= clusterEnd) flush()
        var c = colEnds.indexOfFirst { it <= t.second }
        if (c < 0) { colEnds += t.third; c = colEnds.lastIndex } else colEnds[c] = t.third
        colOf[t] = c
        cluster += t
        clusterEnd = maxOf(clusterEnd, t.third)
    }
    flush()
    return out
}

@Composable
fun TimeGridView(days: List<LocalDate>, insts: List<Inst>, compact: Boolean, onDayTap: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val density = LocalDensity.current
    val hourH = if (compact) 44.dp else 58.dp
    val gutter = 40.dp
    val map = remember(insts, days) { byDay(insts, days.first(), days.size) }
    val today = LocalDate.now()
    val scroll = rememberScrollState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); nowMs = System.currentTimeMillis() } }
    LaunchedEffect(days.first()) {
        val h = if (today in days) (Instant.ofEpochMilli(nowMs).atZone(zone).hour - 1).coerceAtLeast(0) else 7
        scroll.scrollTo(with(density) { (hourH * h).roundToPx() })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
        // Day headers
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(gutter))
            days.forEach { d ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onDayTap(d) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = Hione.Muted, fontSize = 12.sp)
                    Box(Modifier.size(30.dp).clip(CircleShape).background(if (d == today) Hione.PinkSoft else Color.Transparent), contentAlignment = Alignment.Center) {
                        Text(d.dayOfMonth.toString(), color = if (d == today) Color.White else Hione.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        // All-day strip
        val allDay = days.map { d -> map[d].orEmpty().filter { it.allDay } }
        if (allDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("all day", color = Hione.Muted, fontSize = 10.sp, modifier = Modifier.width(gutter).padding(top = 4.dp))
                allDay.forEach { list ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        list.take(if (compact) 2 else 3).forEach { e ->
                            Text(e.title, color = Color.White, fontSize = if (compact) 10.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(e.color.asColor().copy(alpha = 0.85f))
                                    .clickable { openEvent(ctx, e) }.padding(horizontal = 4.dp, vertical = 3.dp))
                        }
                        val more = list.size - (if (compact) 2 else 3)
                        if (more > 0) Text("+$more", color = Hione.Muted, fontSize = 10.sp)
                    }
                }
            }
        }
        // Hour grid
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(hourH * 24)) {
                Column(Modifier.width(gutter)) {
                    for (h in 0 until 24) {
                        Box(Modifier.height(hourH).fillMaxWidth()) {
                            if (h > 0) Text(CalFormat.hourLabel(ctx, h), color = Hione.Muted, fontSize = 10.sp, modifier = Modifier.offset(y = (-6).dp))
                        }
                    }
                }
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxSize()
                        .drawBehind {
                            val hp = hourH.toPx()
                            for (h in 0..24) drawLine(Color(0x1FFFFFFF), Offset(0f, h * hp), Offset(size.width, h * hp), 1f)
                            val cw = size.width / days.size
                            for (i in 1 until days.size) drawLine(Color(0x14FFFFFF), Offset(i * cw, 0f), Offset(i * cw, size.height), 1f)
                        }
                        .pointerInput(days) {
                            // Tap an empty slot: new event there, on the half hour.
                            detectTapGestures { pos ->
                                val cw = size.width / days.size
                                val d = days[(pos.x / cw).toInt().coerceIn(0, days.lastIndex)]
                                val minutes = ((pos.y / hourH.toPx()) * 60).toInt() / 30 * 30
                                val start = d.atStartOfDay().plusMinutes(minutes.toLong())
                                ctx.startActivity(newEventIntent(ctx, start.millis(zone), start.plusHours(1).millis(zone)))
                            }
                        }
                ) {
                    val colW = maxWidth / days.size
                    days.forEachIndexed { di, d ->
                        layoutDay(map[d].orEmpty(), d, zone).forEach { p ->
                            val w = colW / p.cols
                            val top = hourH * (p.startMin / 60f)
                            val h = hourH * ((p.endMin - p.startMin) / 60f)
                            Column(
                                Modifier.offset(x = colW * di + w * p.col, y = top).width(w).height(h).padding(1.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(p.inst.color.asColor().copy(alpha = 0.82f))
                                    .clickable { openEvent(ctx, p.inst) }
                                    .padding(horizontal = 3.dp, vertical = 2.dp)
                            ) {
                                Text(p.inst.title, color = Color.White, fontSize = if (compact) 10.sp else 13.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = if (compact) 3 else 2, overflow = TextOverflow.Ellipsis, lineHeight = if (compact) 11.sp else 15.sp)
                                if (!compact && p.endMin - p.startMin >= 45) {
                                    Text(CalFormat.range(ctx, p.inst), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                        if (d == today) {
                            val nowMin = Instant.ofEpochMilli(nowMs).atZone(zone).let { it.hour * 60 + it.minute }
                            Box(Modifier.offset(x = colW * di, y = hourH * (nowMin / 60f) - 1.dp).width(colW).height(2.dp).background(Hione.PinkSoft))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Agenda
// ---------------------------------------------------------------------------------------------

@Composable
fun AgendaView(start: LocalDate, insts: List<Inst>) {
    val ctx = LocalContext.current
    val map = remember(insts, start) { byDay(insts, start, 60) }
    val days = map.keys.sorted()
    if (days.isEmpty()) {
        EmptyState(Icons.Outlined.EventAvailable, "Nothing in the next 60 days", "Tap + to add an event.")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
        days.forEach { d ->
            item(key = "h$d") { SectionLabel(CalFormat.dayHeader(d), color = if (d == LocalDate.now()) Hione.PinkSoft else Hione.Teal) }
            items(map[d].orEmpty(), key = { "$d-${it.eventId}-${it.begin}" }) { e -> EventRow(e, onClick = { openEvent(ctx, e) }) }
        }
    }
}
