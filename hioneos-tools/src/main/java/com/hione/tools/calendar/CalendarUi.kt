package com.hione.tools.calendar

import com.hione.tools.Mascot
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.hione.tools.ui.GlassIconButton
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.ui.EmptyState
import com.hione.tools.ui.GlassDialog
import com.hione.tools.ui.GlassTextButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.glass
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Int.asColor(): Color = Color(this or 0xFF000000.toInt())

object CalFormat {
    fun time(ctx: Context, ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        return if (DateFormat.is24HourFormat(ctx)) String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
        else String.format(Locale.US, "%d:%02d %s", if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "AM" else "PM")
    }

    fun hourLabel(ctx: Context, h: Int): String =
        if (DateFormat.is24HourFormat(ctx)) String.format(Locale.US, "%02d", h)
        else "${if (h % 12 == 0) 12 else h % 12}${if (h < 12) "a" else "p"}"

    private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
    private val DAY_YEAR = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())

    fun date(d: LocalDate): String = if (d.year == LocalDate.now().year) d.format(DAY) else d.format(DAY_YEAR)

    fun dayHeader(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> "Today, " + date(d)
            today.plusDays(1) -> "Tomorrow, " + date(d)
            today.minusDays(1) -> "Yesterday, " + date(d)
            else -> date(d)
        }
    }

    /** "All day", "10:00 AM – 11:30 AM", or a multi-day range. */
    fun range(ctx: Context, i: Inst): String = range(ctx, i.begin, i.end, i.allDay)

    fun range(ctx: Context, begin: Long, end: Long, allDay: Boolean): String {
        val zone = ZoneId.systemDefault()
        if (allDay) {
            val s = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
            val e = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
            return if (!e.isAfter(s)) "All day" else "All day, ${date(s)} – ${date(e)}"
        }
        val sd = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
        val ed = Instant.ofEpochMilli((end - 1).coerceAtLeast(begin)).atZone(zone).toLocalDate()
        return if (sd == ed) "${time(ctx, begin)} – ${time(ctx, end)}"
        else "${date(sd)} ${time(ctx, begin)} – ${date(ed)} ${time(ctx, end)}"
    }

    fun reminder(m: Int): String = when {
        m == 0 -> "At the start"
        m % 10080 == 0 -> "${m / 10080} week" + (if (m / 10080 > 1) "s" else "") + " before"
        m % 1440 == 0 -> "${m / 1440} day" + (if (m / 1440 > 1) "s" else "") + " before"
        m % 60 == 0 -> "${m / 60} hour" + (if (m / 60 > 1) "s" else "") + " before"
        else -> "$m minutes before"
    }

    /** Short plain description of the common RRULE shapes; anything fancier says "Repeats". */
    fun rrule(r: String?): String? {
        if (r.isNullOrBlank()) return null
        val parts = r.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=', "") }
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val base = when (parts["FREQ"]) {
            "DAILY" -> if (interval == 1) "Every day" else "Every $interval days"
            "WEEKLY" -> {
                val days = parts["BYDAY"]?.split(',')?.mapNotNull { BYDAY_NAMES[it.takeLast(2)] }
                val every = if (interval == 1) "Every week" else "Every $interval weeks"
                if (days.isNullOrEmpty()) every else "$every on ${days.joinToString(", ")}"
            }
            "MONTHLY" -> if (interval == 1) "Every month" else "Every $interval months"
            "YEARLY" -> if (interval == 1) "Every year" else "Every $interval years"
            else -> "Repeats"
        }
        val until = parts["UNTIL"]?.take(8)?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
        val count = parts["COUNT"]?.toIntOrNull()
        return base + when {
            until != null -> ", until ${date(until)}"
            count != null -> ", $count times"
            else -> ""
        }
    }

    val BYDAY_NAMES = mapOf("SU" to "Sun", "MO" to "Mon", "TU" to "Tue", "WE" to "Wed", "TH" to "Thu", "FR" to "Fri", "SA" to "Sat")
}

fun LocalDate.startMillis(zone: ZoneId = ZoneId.systemDefault()): Long = atStartOfDay(zone).toInstant().toEpochMilli()
fun LocalDateTime.millis(zone: ZoneId = ZoneId.systemDefault()): Long = atZone(zone).toInstant().toEpochMilli()

/** One event in a list: color bar, title, time, location. 60dp minimum height. */
@Composable
fun EventRow(i: Inst, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp)
            .glass(RoundedCornerShape(16.dp), accent = i.color.asColor(), rimAlpha = 0.35f)
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(6.dp).height(60.dp).background(i.color.asColor()))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(i.title, color = Hione.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(CalFormat.range(ctx, i), color = Hione.TextDim, fontSize = 13.sp, maxLines = 1)
            if (i.location.isNotBlank()) Text(i.location, color = Hione.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun openEvent(ctx: Context, i: Inst) {
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW, CalendarRepo.eventUri(i.eventId), ctx, EventInfoActivity::class.java)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, i.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, i.end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, i.allDay)
    )
}

fun newEventIntent(ctx: Context, begin: Long, end: Long, allDay: Boolean = false): Intent =
    Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI, ctx, EditEventActivity::class.java)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)

/** Shown instead of a screen until READ/WRITE_CALENDAR are granted. */
@Composable
fun PermissionGate(activity: Activity, deniedForever: Boolean, onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        EmptyState(Icons.Outlined.CalendarMonth, "Calendar needs access",
            "Allow access to your calendars to see and edit events. Events stay in the system calendar and sync with your accounts.")
        Spacer(Modifier.height(16.dp))
        if (deniedForever) {
            GlassTextButton("Open app settings", {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName)))
            }, Modifier.fillMaxWidth(), filled = true)
        } else {
            GlassTextButton("Allow", onRequest, Modifier.fillMaxWidth(), filled = true)
        }
        Spacer(Modifier.weight(1.2f))
    }
}

/** Compact month-grid date picker. Our own rather than Material's DatePicker, which wants a
 *  360dp-wide container and clips inside a dialog on this 360dp-wide screen. */
@Composable
fun DatePickDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    var picked by remember { mutableStateOf(initial) }
    GlassDialog(onDismiss, buttons = {
        GlassTextButton("Cancel", onDismiss)
        GlassTextButton("OK", { onPick(picked); onDismiss() }, filled = true)
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", { month = month.minusMonths(1) }, plain = true)
            Text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                color = Hione.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center
            )
            GlassIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", { month = month.plusMonths(1) }, plain = true)
        }
        Spacer(Modifier.height(6.dp))
        MonthGrid(month, selected = picked, cellHeight = 42.dp, onPick = { picked = it })
    }
}

/** Plain 6x7 month grid; used by the date picker. */
@Composable
fun MonthGrid(month: YearMonth, selected: LocalDate, cellHeight: Dp, onPick: (LocalDate) -> Unit) {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(first))
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth()) {
        (0 until 7).forEach { i ->
            Text(start.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                color = Hione.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
    }
    for (w in 0 until 6) {
        Row(Modifier.fillMaxWidth()) {
            for (dIdx in 0 until 7) {
                val d = start.plusDays((w * 7 + dIdx).toLong())
                val inMonth = d.month == month.month
                val sel = d == selected
                Box(
                    Modifier.weight(1f).height(cellHeight).padding(2.dp)
                        .clip(CircleShape)
                        .background(if (sel) Hione.Teal else Color.Transparent)
                        .clickable { onPick(d) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        d.dayOfMonth.toString(),
                        color = when { sel -> Mascot.color(0xFF02201D); d == today -> Hione.PinkSoft; inMonth -> Hione.Text; else -> Hione.Faint },
                        fontSize = 15.sp, fontWeight = if (d == today || sel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
