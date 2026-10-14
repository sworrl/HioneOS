package com.hione.tools.calendar

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.EmptyState
import com.hione.tools.ui.GlassDialog
import com.hione.tools.ui.GlassTextButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.HioneBackground
import com.hione.tools.ui.HioneTheme
import com.hione.tools.ui.HioneTopBar
import com.hione.tools.ui.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

data class IcsEvent(
    val summary: String,
    val description: String,
    val location: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val timeZone: String,
    val rrule: String?,
    val reminders: List<Int>,
)

/**
 * Minimal iCalendar (RFC 5545) reader for the VEVENTs people actually get mailed: SUMMARY,
 * DESCRIPTION, LOCATION, DTSTART/DTEND/DURATION (UTC, TZID or floating, date or date-time),
 * RRULE, and VALARM triggers relative to the start. Line folding and text escapes are handled.
 * Unknown properties are ignored rather than rejected.
 */
object IcsParser {
    fun parse(text: String): List<IcsEvent> {
        // Unfold: a line starting with a space or tab continues the previous one.
        val lines = ArrayList<String>()
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { l ->
            if ((l.startsWith(" ") || l.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] = lines.last() + l.substring(1)
            else if (l.isNotEmpty()) lines += l
        }
        val out = ArrayList<IcsEvent>()
        var inEvent = false
        var inAlarm = false
        var props = HashMap<String, Pair<Map<String, String>, String>>()
        var alarms = ArrayList<Int>()
        for (line in lines) {
            val colon = findValueColon(line)
            if (colon < 0) continue
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val name = head.substringBefore(';').uppercase()
            val params = head.split(';').drop(1).associate { it.substringBefore('=').uppercase() to it.substringAfter('=').trim('"') }
            when {
                name == "BEGIN" && value.equals("VEVENT", true) -> { inEvent = true; props = HashMap(); alarms = ArrayList() }
                name == "BEGIN" && value.equals("VALARM", true) -> inAlarm = true
                name == "END" && value.equals("VALARM", true) -> inAlarm = false
                name == "END" && value.equals("VEVENT", true) -> {
                    inEvent = false
                    toEvent(props, alarms)?.let { out += it }
                }
                inEvent && inAlarm && name == "TRIGGER" -> {
                    // Only start-relative triggers ("-PT15M"); absolute ones are rare in invites.
                    if (params["VALUE"]?.uppercase() != "DATE-TIME" && params["RELATED"]?.uppercase() != "END") {
                        val ms = parseDuration(value)
                        if (ms <= 0) alarms += (-ms / 60_000).toInt()
                    }
                }
                inEvent && !inAlarm -> props[name] = params to value
            }
        }
        return out
    }

    /** The colon that separates name/params from value, skipping colons inside quoted params. */
    private fun findValueColon(line: String): Int {
        var q = false
        for ((i, c) in line.withIndex()) {
            if (c == '"') q = !q
            if (c == ':' && !q) return i
        }
        return -1
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun parseTime(p: Pair<Map<String, String>, String>): Triple<Long, Boolean, String>? {
        val (params, raw) = p
        val v = raw.trim()
        return runCatching {
            if (params["VALUE"]?.uppercase() == "DATE" || (v.length == 8 && v.all { it.isDigit() })) {
                val d = LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE)
                Triple(d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, "UTC")
            } else {
                val ldt = LocalDateTime.parse(v.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                val zone = when {
                    v.endsWith("Z") -> ZoneOffset.UTC
                    params["TZID"] != null -> runCatching { ZoneId.of(params["TZID"]) }.getOrElse { windowsZone(params["TZID"]!!) }
                    else -> ZoneId.systemDefault() // floating time
                }
                Triple(ldt.atZone(zone).toInstant().toEpochMilli(), false, if (zone is ZoneOffset) "UTC" else zone.id)
            }
        }.getOrNull()
    }

    /** Outlook invites name zones the Windows way; map the common ones, else use local. */
    private fun windowsZone(id: String): ZoneId = when {
        id.contains("Pacific", true) -> ZoneId.of("America/Los_Angeles")
        id.contains("Mountain", true) -> ZoneId.of("America/Denver")
        id.contains("Central Standard", true) -> ZoneId.of("America/Chicago")
        id.contains("Eastern", true) -> ZoneId.of("America/New_York")
        id.contains("GMT Standard", true) -> ZoneId.of("Europe/London")
        id.contains("W. Europe", true) -> ZoneId.of("Europe/Berlin")
        id.contains("Romance", true) -> ZoneId.of("Europe/Paris")
        id.contains("Tokyo", true) -> ZoneId.of("Asia/Tokyo")
        id.contains("China", true) -> ZoneId.of("Asia/Shanghai")
        else -> ZoneId.systemDefault()
    }

    private fun toEvent(p: Map<String, Pair<Map<String, String>, String>>, alarms: List<Int>): IcsEvent? {
        val start = p["DTSTART"]?.let { parseTime(it) } ?: return null
        val end = p["DTEND"]?.let { parseTime(it) }?.first
            ?: p["DURATION"]?.let { start.first + parseDuration(it.second) }
            ?: (start.first + if (start.second) 86_400_000L else 3_600_000L)
        if (p["STATUS"]?.second?.equals("CANCELLED", true) == true) return null
        return IcsEvent(
            summary = unescape(p["SUMMARY"]?.second.orEmpty()),
            description = unescape(p["DESCRIPTION"]?.second.orEmpty()),
            location = unescape(p["LOCATION"]?.second.orEmpty()),
            start = start.first, end = maxOf(end, start.first), allDay = start.second,
            timeZone = if (start.second) "UTC" else start.third,
            rrule = p["RRULE"]?.second?.takeIf { it.isNotBlank() },
            reminders = alarms.distinct(),
        )
    }
}

/** VIEW of a text/calendar file: preview the events, pick a calendar, add them. */
class IcsImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) { finish(); return }
        if (!CalendarRepo.hasPermission(this)) {
            startActivity(android.content.Intent(this, CalendarActivity::class.java))
            Toast.makeText(this, "Allow calendar access, then open the file again", Toast.LENGTH_LONG).show()
            finish(); return
        }
        setContent { HioneTheme { HioneBackground { ImportScreen(uri) { finish() } } } }
    }
}

@Composable
private fun ImportScreen(uri: Uri, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<IcsEvent>?>(null) }
    var cals by remember { mutableStateOf(emptyList<CalInfo>()) }
    var calId by remember { mutableStateOf(-1L) }
    var pickCal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val (evs, list) = withContext(Dispatchers.IO) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { s ->
                    // 2 MB is far beyond any invite; refuse to read a mislabeled huge file.
                    val bytes = s.readNBytesCompat(2 * 1024 * 1024)
                    String(bytes, Charsets.UTF_8)
                }
            }.getOrNull().orEmpty()
            IcsParser.parse(text) to CalendarRepo.calendars(ctx).filter { it.writable }
        }
        events = evs; cals = list
        calId = CalendarRepo.defaultCalendar(ctx, list)?.id ?: -1L
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        HioneTopBar("Add to calendar", onBack = close)
        val evs = events
        when {
            evs == null -> Text("Reading the file", color = Hione.Muted, modifier = Modifier.padding(20.dp))
            evs.isEmpty() -> EmptyState(Icons.Outlined.EventBusy, "No events found", "This file has no events Calendar can read.")
            else -> {
                val cal = cals.firstOrNull { it.id == calId }
                Row(
                    Modifier.padding(horizontal = 14.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), rimAlpha = 0.3f)
                        .clickable { pickCal = true }.heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(cal?.color?.asColor() ?: Hione.Teal))
                    Spacer(Modifier.width(12.dp))
                    Text(cal?.name ?: "On this device", color = Hione.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("Change", color = Hione.TealGlow, fontSize = 14.sp)
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(evs) { e ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).glass(RoundedCornerShape(16.dp), rimAlpha = 0.3f).padding(14.dp)) {
                            Text(e.summary.ifBlank { "(No title)" }, color = Hione.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val day = java.time.Instant.ofEpochMilli(e.start).atZone(if (e.allDay) ZoneOffset.UTC else ZoneId.systemDefault()).toLocalDate()
                            Text(CalFormat.date(day) + ", " + CalFormat.range(ctx, e.start, e.end, e.allDay), color = Hione.TextDim, fontSize = 13.sp)
                            if (e.location.isNotBlank()) Text(e.location, color = Hione.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CalFormat.rrule(e.rrule)?.let { Text(it, color = Hione.Muted, fontSize = 13.sp) }
                        }
                    }
                }
                GlassTextButton(
                    if (evs.size == 1) "Add event" else "Add ${evs.size} events",
                    {
                        if (busy) return@GlassTextButton
                        busy = true
                        scope.launch {
                            val added = withContext(Dispatchers.IO) {
                                val target = if (calId >= 0) calId else CalendarRepo.ensureLocalCalendar(ctx) ?: -1L
                                if (target < 0) 0 else evs.count { e ->
                                    runCatching {
                                        CalendarRepo.insert(ctx, EventDraft(
                                            calendarId = target, title = e.summary, description = e.description, location = e.location,
                                            start = e.start, end = e.end, allDay = e.allDay,
                                            timeZone = e.timeZone.ifBlank { TimeZone.getDefault().id }, rrule = e.rrule, reminders = e.reminders,
                                        )) != null
                                    }.getOrDefault(false)
                                }
                            }
                            Toast.makeText(ctx, if (added == 1) "Event added" else "$added events added", Toast.LENGTH_SHORT).show()
                            close()
                        }
                    },
                    Modifier.fillMaxWidth().padding(14.dp), filled = true, enabled = !busy
                )
            }
        }
    }

    if (pickCal) {
        GlassDialog({ pickCal = false }, title = "Calendar", buttons = { GlassTextButton("Close", { pickCal = false }) }) {
            cals.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { calId = c.id; pickCal = false }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(c.color.asColor()))
                    Spacer(Modifier.width(12.dp))
                    Text(c.name, color = if (c.id == calId) Hione.TealGlow else Hione.Text, fontSize = 16.sp)
                }
            }
        }
    }
}

/** InputStream.readNBytes is API 33+; this is the same thing for older releases. */
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
