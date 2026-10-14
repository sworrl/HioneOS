package com.hione.tools.calendar

import com.hione.tools.Mascot
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.tools.clock.TimePickDialog
import com.hione.tools.ui.GlassButton
import com.hione.tools.ui.GlassDialog
import com.hione.tools.ui.GlassRow
import com.hione.tools.ui.GlassSegmented
import com.hione.tools.ui.GlassTextButton
import com.hione.tools.ui.Hione
import com.hione.tools.ui.HioneBackground
import com.hione.tools.ui.HioneSwitch
import com.hione.tools.ui.HioneTheme
import com.hione.tools.ui.HioneTopBar
import com.hione.tools.ui.SectionLabel
import com.hione.tools.ui.glass
import com.hione.tools.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import java.util.TimeZone

/**
 * INSERT / EDIT with vnd.android.cursor.item/event (or dir/event). Honors the extras other apps
 * send with an insert: EXTRA_EVENT_BEGIN_TIME, EXTRA_EVENT_END_TIME, EXTRA_EVENT_ALL_DAY,
 * title, description, eventLocation, rrule, availability, and Intent.EXTRA_EMAIL for guests.
 */
class EditEventActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!CalendarRepo.hasPermission(this)) {
            startActivity(Intent(this, CalendarActivity::class.java))
            Toast.makeText(this, "Allow calendar access first", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val i = intent
        val editId = if (i.action == Intent.ACTION_EDIT) i.data?.let { runCatching { ContentUris.parseId(it) }.getOrNull() } else null
        val self = this
        setContent { HioneTheme { HioneBackground { EditEventScreen(self, i, editId?.takeIf { it > 0 }) } } }
    }
}

private enum class Repeat(val label: String) { NONE("Does not repeat"), DAILY("Every day"), WEEKLY("Every week"), MONTHLY("Every month"), YEARLY("Every year"), CUSTOM("Custom") }

private val BYDAY = mapOf(
    DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
    DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU"
)

private val REMINDER_CHOICES = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)

private fun Intent.longExtra(vararg keys: String): Long? {
    for (k in keys) {
        if (!hasExtra(k)) continue
        @Suppress("DEPRECATION")
        when (val v = extras?.get(k)) {
            is Long -> return v
            is Int -> return v.toLong()
            is String -> v.toLongOrNull()?.let { return it }
        }
    }
    return null
}

@Composable
private fun EditEventScreen(activity: Activity, intent: Intent, editId: Long?) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(editId == null) }
    var original by remember { mutableStateOf<EventFull?>(null) }
    val instBegin = intent.longExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME) ?: 0L

    var title by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf(LocalDate.now()) }
    var startTime by remember { mutableStateOf(LocalTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1)) }
    var endDate by remember { mutableStateOf(LocalDate.now()) }
    var endTime by remember { mutableStateOf(startTime.plusHours(1)) }
    var location by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(Repeat.NONE) }
    val weekly = remember { mutableStateListOf<DayOfWeek>() }
    var customRule by remember { mutableStateOf<String?>(null) }
    val reminders = remember { mutableStateListOf<Int>() }
    var free by remember { mutableStateOf(false) }
    var guests by remember { mutableStateOf("") }
    var calendars by remember { mutableStateOf(emptyList<CalInfo>()) }
    var calendarId by remember { mutableStateOf(-1L) }
    var timeZone by remember { mutableStateOf(TimeZone.getDefault().id) }

    var pick by remember { mutableStateOf<String?>(null) } // which picker is open
    var askScope by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun setFromRule(r: String?, start: LocalDate) {
        weekly.clear()
        if (r.isNullOrBlank()) { repeat = Repeat.NONE; return }
        val parts = r.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=', "") }
        val simple = parts.keys.all { it in setOf("FREQ", "BYDAY", "WKST") } && (parts["BYDAY"] == null || parts["FREQ"] == "WEEKLY")
        if (!simple) { repeat = Repeat.CUSTOM; customRule = r; return }
        repeat = when (parts["FREQ"]) {
            "DAILY" -> Repeat.DAILY; "WEEKLY" -> Repeat.WEEKLY; "MONTHLY" -> Repeat.MONTHLY; "YEARLY" -> Repeat.YEARLY
            else -> { customRule = r; Repeat.CUSTOM }
        }
        if (repeat == Repeat.WEEKLY) {
            parts["BYDAY"]?.split(',')?.forEach { code -> BYDAY.entries.firstOrNull { it.value == code.takeLast(2) }?.let { weekly += it.key } }
            if (weekly.isEmpty()) weekly += start.dayOfWeek
        }
        customRule = r
    }

    LaunchedEffect(Unit) {
        val cals = withContext(Dispatchers.IO) { CalendarRepo.calendars(ctx) }.filter { it.writable }
        calendars = cals
        if (editId != null) {
            val e = withContext(Dispatchers.IO) { CalendarRepo.event(ctx, editId) }
            if (e == null) { Toast.makeText(ctx, "That event no longer exists", Toast.LENGTH_SHORT).show(); activity.finish(); return@LaunchedEffect }
            original = e
            title = e.title; location = e.location; description = e.description
            allDay = e.allDay; free = e.availability == Events.AVAILABILITY_FREE
            calendarId = e.calendarId; timeZone = e.timeZone
            reminders.addAll(e.reminders)
            val b = if (instBegin > 0 && e.rrule != null) instBegin else e.dtStart
            val en = b + e.lengthMs
            if (e.allDay) {
                startDate = Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate()
                endDate = Instant.ofEpochMilli(en).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).let { if (it.isBefore(startDate)) startDate else it }
            } else {
                val s = Instant.ofEpochMilli(b).atZone(zone).toLocalDateTime(); val f = Instant.ofEpochMilli(en).atZone(zone).toLocalDateTime()
                startDate = s.toLocalDate(); startTime = s.toLocalTime(); endDate = f.toLocalDate(); endTime = f.toLocalTime()
            }
            setFromRule(e.rrule, startDate)
        } else {
            // Insert: start from whatever the caller provided.
            allDay = intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
            val b = intent.longExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, "beginTime", Events.DTSTART)
            val en = intent.longExtra(CalendarContract.EXTRA_EVENT_END_TIME, "endTime", Events.DTEND)
            if (b != null) {
                if (allDay) {
                    startDate = Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate()
                    endDate = en?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1) }?.takeIf { !it.isBefore(startDate) } ?: startDate
                } else {
                    val s = Instant.ofEpochMilli(b).atZone(zone).toLocalDateTime()
                    val f = Instant.ofEpochMilli(en?.takeIf { it > b } ?: (b + 3_600_000L)).atZone(zone).toLocalDateTime()
                    startDate = s.toLocalDate(); startTime = s.toLocalTime(); endDate = f.toLocalDate(); endTime = f.toLocalTime()
                }
            } else {
                endDate = startDate
            }
            title = intent.getStringExtra(Events.TITLE).orEmpty()
            description = intent.getStringExtra(Events.DESCRIPTION).orEmpty()
            location = intent.getStringExtra(Events.EVENT_LOCATION).orEmpty()
            free = intent.getIntExtra(Events.AVAILABILITY, Events.AVAILABILITY_BUSY) == Events.AVAILABILITY_FREE
            setFromRule(intent.getStringExtra(Events.RRULE), startDate)
            @Suppress("DEPRECATION")
            guests = when (val g = intent.extras?.get(Intent.EXTRA_EMAIL)) {
                is String -> g
                is Array<*> -> g.filterIsInstance<String>().joinToString(", ")
                else -> ""
            }
            if (!allDay) reminders += 10
            val want = intent.longExtra(Events.CALENDAR_ID)
            calendarId = cals.firstOrNull { it.id == want }?.id ?: CalendarRepo.defaultCalendar(ctx, cals)?.id ?: -1L
        }
        loaded = true
    }

    fun buildDraft(): EventDraft? {
        val startMs: Long; val endMs: Long
        if (allDay) {
            startMs = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            endMs = endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            startMs = LocalDateTime.of(startDate, startTime).millis(zone)
            endMs = LocalDateTime.of(endDate, endTime).millis(zone)
        }
        if (endMs < startMs || (allDay && endDate.isBefore(startDate))) {
            Toast.makeText(ctx, "The end is before the start", Toast.LENGTH_SHORT).show()
            return null
        }
        val rule = when (repeat) {
            Repeat.NONE -> null
            Repeat.DAILY -> "FREQ=DAILY"
            Repeat.WEEKLY -> "FREQ=WEEKLY;BYDAY=" + (weekly.ifEmpty { listOf(startDate.dayOfWeek) }).sortedBy { it.value }.joinToString(",") { BYDAY.getValue(it) }
            Repeat.MONTHLY -> "FREQ=MONTHLY"
            Repeat.YEARLY -> "FREQ=YEARLY"
            Repeat.CUSTOM -> customRule
        }
        return EventDraft(
            calendarId = calendarId, title = title.trim(), description = description.trim(), location = location.trim(),
            start = startMs, end = endMs, allDay = allDay, timeZone = if (editId == null) TimeZone.getDefault().id else timeZone,
            rrule = rule, availability = if (free) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY,
            reminders = reminders.toList(),
            guests = guests.split(',', ';', ' ').map { it.trim() }.filter { it.contains('@') },
        )
    }

    fun finishSaved(ok: Boolean) {
        saving = false
        if (ok) {
            Toast.makeText(ctx, "Event saved", Toast.LENGTH_SHORT).show()
            activity.setResult(Activity.RESULT_OK)
            activity.finish()
        } else Toast.makeText(ctx, "Could not save the event", Toast.LENGTH_SHORT).show()
    }

    fun save(scopeAll: Boolean?) {
        val d0 = buildDraft() ?: return
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    var d = d0
                    if (d.calendarId < 0) {
                        val id = CalendarRepo.ensureLocalCalendar(ctx) ?: return@runCatching false
                        d = d.copy(calendarId = id)
                    }
                    val o = original
                    when {
                        o == null -> CalendarRepo.insert(ctx, d) != null
                        o.rrule != null && instBegin > 0 && scopeAll == false ->
                            CalendarRepo.updateOccurrence(ctx, o.id, instBegin, d.copy(rrule = null, guests = emptyList())) != null
                        o.rrule != null && instBegin > 0 && d.rrule != null -> {
                            // Editing the series from one occurrence: move the series by however
                            // far this occurrence moved, keep the new length.
                            val shift = d.start - instBegin
                            val len = d.end - d.start
                            CalendarRepo.update(ctx, o.id, d.copy(start = o.dtStart + shift, end = o.dtStart + shift + len, guests = emptyList()))
                        }
                        else -> CalendarRepo.update(ctx, o.id, d.copy(guests = emptyList()))
                    }
                }.getOrDefault(false)
            }
            finishSaved(ok)
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        HioneTopBar(if (editId == null) "New event" else "Edit event", onBack = { activity.finish() }, actions = {
            GlassButton({
                if (!saving) {
                    val o = original
                    if (o?.rrule != null && instBegin > 0) askScope = true else save(null)
                }
            }, filled = true, minHeight = 46.dp) {
                Icon(Icons.Outlined.Check, null, tint = Mascot.color(0xFF041513))
                Spacer(Modifier.width(6.dp))
                Text("Save", color = Mascot.color(0xFF041513), fontWeight = FontWeight.SemiBold)
            }
        })
        if (!loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            OutlinedTextField(
                title, { title = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Title", color = Hione.Faint, fontSize = 20.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, color = Hione.Text),
                colors = fieldColors(), shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), rimAlpha = 0.3f)) {
                GlassRow("All day", trailing = { HioneSwitch(allDay, { allDay = it }) }, onClick = { allDay = !allDay })
                DateTimeRow("Starts", startDate, startTime, allDay, onDate = { pick = "sd" }, onTime = { pick = "st" })
                DateTimeRow("Ends", endDate, endTime, allDay, onDate = { pick = "ed" }, onTime = { pick = "et" })
                GlassRow("Repeat", value = if (repeat == Repeat.CUSTOM) (CalFormat.rrule(customRule) ?: "Custom") else if (repeat == Repeat.WEEKLY)
                    CalFormat.rrule("FREQ=WEEKLY;BYDAY=" + weekly.ifEmpty { listOf(startDate.dayOfWeek) }.joinToString(",") { BYDAY.getValue(it) }) else repeat.label,
                    icon = Icons.Outlined.Repeat, onClick = { pick = "repeat" })
            }

            SectionLabel("Calendar")
            val cal = calendars.firstOrNull { it.id == calendarId }
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), rimAlpha = 0.3f).clickable(enabled = editId == null) { pick = "cal" }
                    .heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(cal?.color?.asColor() ?: Hione.Teal))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(cal?.name ?: original?.calendarName ?: "On this device", color = Hione.Text, fontSize = 16.sp)
                    val acct = cal?.accountName
                    if (!acct.isNullOrBlank() && cal.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL) Text(acct, color = Hione.Muted, fontSize = 13.sp)
                }
            }

            SectionLabel("Details")
            OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Location", color = Hione.Faint) }, colors = fieldColors(), shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp),
                placeholder = { Text("Notes", color = Hione.Faint) }, colors = fieldColors(), shape = RoundedCornerShape(16.dp))
            if (editId == null) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(guests, { guests = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Guests (email addresses)", color = Hione.Faint) }, colors = fieldColors(), shape = RoundedCornerShape(16.dp))
            }

            SectionLabel("Reminders")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                reminders.sorted().forEach { m ->
                    Row(
                        Modifier.glass(RoundedCornerShape(14.dp), rimAlpha = 0.35f).pressable({ reminders.remove(m) })
                            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(CalFormat.reminder(m), color = Hione.Text, fontSize = 14.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.Close, "Remove", tint = Hione.Muted, modifier = Modifier.size(16.dp))
                    }
                }
                Row(
                    Modifier.glass(RoundedCornerShape(14.dp), accent = Hione.PinkSoft, rimAlpha = 0.35f).pressable({ pick = "rem" })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Add, null, tint = Hione.PinkSoft, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", color = Hione.PinkSoft, fontSize = 14.sp)
                }
            }

            SectionLabel("Show as")
            GlassSegmented(listOf("Busy", "Free"), if (free) 1 else 0, { free = it == 1 }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(60.dp))
        }
    }

    // ---- pickers
    when (pick) {
        "sd" -> DatePickDialog(startDate, { pick = null }) { d ->
            val shift = d.toEpochDay() - startDate.toEpochDay()
            startDate = d; endDate = endDate.plusDays(shift) // keep the length
        }
        "ed" -> DatePickDialog(endDate, { pick = null }) { d -> endDate = d }
        "st" -> TimePickDialog(startTime.hour, startTime.minute, { pick = null }) { h, m ->
            val oldStart = LocalDateTime.of(startDate, startTime)
            val newStart = LocalDateTime.of(startDate, LocalTime.of(h, m))
            val newEnd = LocalDateTime.of(endDate, endTime).plusMinutes(java.time.Duration.between(oldStart, newStart).toMinutes())
            startTime = newStart.toLocalTime(); endDate = newEnd.toLocalDate(); endTime = newEnd.toLocalTime(); pick = null
        }
        "et" -> TimePickDialog(endTime.hour, endTime.minute, { pick = null }) { h, m -> endTime = LocalTime.of(h, m); pick = null }
        "repeat" -> RepeatDialog(repeat, weekly, startDate, customRule, onDismiss = { pick = null }) { r -> repeat = r }
        "cal" -> GlassDialog({ pick = null }, title = "Calendar", buttons = { GlassTextButton("Close", { pick = null }) }) {
            if (calendars.isEmpty()) Text("No account calendars. The event goes to a calendar on this device.", color = Hione.TextDim, fontSize = 14.sp)
            calendars.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { calendarId = c.id; pick = null }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(c.color.asColor()))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = if (c.id == calendarId) Hione.TealGlow else Hione.Text, fontSize = 16.sp)
                        if (c.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL) Text(c.accountName, color = Hione.Muted, fontSize = 12.sp)
                    }
                    if (c.id == calendarId) Icon(Icons.Outlined.Check, null, tint = Hione.TealGlow)
                }
            }
        }
        "rem" -> GlassDialog({ pick = null }, title = "Add a reminder", buttons = { GlassTextButton("Close", { pick = null }) }) {
            REMINDER_CHOICES.filter { it !in reminders }.forEach { m ->
                Text(CalFormat.reminder(m), color = Hione.Text, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { reminders += m; pick = null }.padding(horizontal = 8.dp, vertical = 14.dp))
            }
        }
    }

    if (askScope) {
        GlassDialog({ askScope = false }, title = "Save changes to", buttons = {
            GlassTextButton("Cancel", { askScope = false })
        }) {
            Text("This event repeats.", color = Hione.TextDim, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            GlassTextButton("Only this event", { askScope = false; save(false) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            GlassTextButton("All events", { askScope = false; save(true) }, Modifier.fillMaxWidth(), filled = true)
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Hione.Teal, unfocusedBorderColor = Hione.Faint, cursorColor = Hione.Teal,
    focusedTextColor = Hione.Text, unfocusedTextColor = Hione.Text,
)

@Composable
private fun DateTimeRow(label: String, date: LocalDate, time: LocalTime, allDay: Boolean, onDate: () -> Unit, onTime: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Hione.Muted, fontSize = 15.sp, modifier = Modifier.width(56.dp))
        Text(CalFormat.date(date), color = Hione.Text, fontSize = 16.sp,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onDate).padding(vertical = 12.dp, horizontal = 6.dp))
        if (!allDay) {
            Text(CalFormat.time(ctx, LocalDateTime.of(date, time).millis()), color = Hione.TealGlow, fontSize = 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onTime).padding(vertical = 12.dp, horizontal = 8.dp))
        }
    }
}

@Composable
private fun RepeatDialog(current: Repeat, weekly: MutableList<DayOfWeek>, start: LocalDate, custom: String?, onDismiss: () -> Unit, onPick: (Repeat) -> Unit) {
    var sel by remember { mutableStateOf(current) }
    GlassDialog(onDismiss, title = "Repeat", buttons = {
        GlassTextButton("OK", {
            if (sel == Repeat.WEEKLY && weekly.isEmpty()) weekly += start.dayOfWeek
            onPick(sel); onDismiss()
        }, filled = true)
    }) {
        Repeat.entries.filter { it != Repeat.CUSTOM || custom != null }.forEach { r ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (r == sel) Hione.Teal.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { sel = r }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (r == Repeat.CUSTOM) (CalFormat.rrule(custom) ?: "Custom") else r.label,
                    color = if (r == sel) Hione.TealGlow else Hione.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (r == sel) Icon(Icons.Outlined.Check, null, tint = Hione.TealGlow)
            }
        }
        if (sel == Repeat.WEEKLY) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val first = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
                (0 until 7).map { first.plus(it.toLong()) }.forEach { d ->
                    val on = d in weekly
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(if (on) Hione.Teal else Color(0x22FFFFFF))
                            .clickable { if (on) weekly.remove(d) else weekly.add(d) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), color = if (on) Mascot.color(0xFF02201D) else Hione.TextDim, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
