package com.hione.tools.calendar

import com.hione.tools.Mascot
import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

data class CalInfo(
    val id: Long,
    val name: String,
    val color: Int,
    val accountName: String,
    val accountType: String,
    val accessLevel: Int,
    val visible: Boolean,
    val primary: Boolean,
) {
    val writable: Boolean get() = accessLevel >= Calendars.CAL_ACCESS_CONTRIBUTOR
}

/** One occurrence of an event, as the Instances table expands recurrences. */
data class Inst(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val location: String,
    val allDay: Boolean,
    val color: Int,
    val calendarId: Long,
    val recurring: Boolean,
) {
    /** First and last calendar day this occurrence covers, in the device zone. All-day events
     *  are stored at UTC midnights, so their days are read in UTC: an all-day event on the 5th
     *  must stay on the 5th whatever zone the device is in. End is exclusive. */
    fun firstDay(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        if (allDay) Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
        else Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()

    fun lastDay(zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val f = firstDay(zone)
        val l = if (allDay) Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        else Instant.ofEpochMilli((end - 1).coerceAtLeast(begin)).atZone(zone).toLocalDate()
        return if (l.isBefore(f)) f else l
    }

    fun covers(d: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = !d.isBefore(firstDay(zone)) && !d.isAfter(lastDay(zone))
}

data class Guest(val name: String, val email: String, val status: Int, val organizer: Boolean)

/** A full event row plus its reminders and guests. */
data class EventFull(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val description: String,
    val location: String,
    val dtStart: Long,
    val dtEnd: Long,
    val duration: String?,
    val allDay: Boolean,
    val timeZone: String,
    val rrule: String?,
    val availability: Int,
    val reminders: List<Int>,
    val guests: List<Guest>,
    val organizer: String?,
    val calendarName: String,
    val calendarColor: Int,
    val color: Int,
    val canEdit: Boolean,
) {
    /** Length of one occurrence. Recurring events store DURATION instead of DTEND. */
    val lengthMs: Long get() = when {
        dtEnd > dtStart -> dtEnd - dtStart
        duration != null -> parseDuration(duration)
        allDay -> 86_400_000L
        else -> 3_600_000L
    }
}

/** RFC 5545 duration ("P1D", "PT1H30M", "P3600S", "-PT15M") to milliseconds. */
fun parseDuration(d: String): Long {
    var s = d.trim().uppercase()
    var sign = 1
    if (s.startsWith("-")) { sign = -1; s = s.substring(1) } else if (s.startsWith("+")) s = s.substring(1)
    if (!s.startsWith("P")) return 0
    var total = 0L
    var num = StringBuilder()
    var inTime = false
    for (c in s.substring(1)) {
        when {
            c == 'T' -> inTime = true
            c.isDigit() -> num.append(c)
            else -> {
                val n = num.toString().toLongOrNull() ?: 0
                num = StringBuilder()
                total += when (c) {
                    'W' -> n * 7 * 86_400_000L
                    'D' -> n * 86_400_000L
                    'H' -> n * 3_600_000L
                    'M' -> if (inTime) n * 60_000L else n * 30 * 86_400_000L
                    'S' -> n * 1000L
                    else -> 0
                }
            }
        }
    }
    return sign * total
}

/** What the editor saves. Times are epoch millis; all-day events use UTC midnights. */
data class EventDraft(
    val calendarId: Long,
    val title: String,
    val description: String = "",
    val location: String = "",
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val timeZone: String = TimeZone.getDefault().id,
    val rrule: String? = null,
    val availability: Int = Events.AVAILABILITY_BUSY,
    val reminders: List<Int> = emptyList(),
    val guests: List<String> = emptyList(),
)

/**
 * Thin layer over CalendarContract. Everything here does provider I/O and belongs off the main
 * thread. Google calendars arrive through the GMS sync adapter; anything written here is
 * synced back by it on its own schedule.
 */
object CalendarRepo {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<CalInfo> {
        val out = ArrayList<CalInfo>()
        runCatching {
            ctx.contentResolver.query(
                Calendars.CONTENT_URI,
                arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, Calendars.ACCOUNT_NAME,
                    Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE, Calendars.IS_PRIMARY, Calendars.OWNER_ACCOUNT),
                null, null, "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}"
            )?.use { c ->
                while (c.moveToNext()) {
                    val acct = c.getString(3).orEmpty()
                    out += CalInfo(
                        id = c.getLong(0), name = c.getString(1) ?: "Calendar", color = c.getInt(2),
                        accountName = acct, accountType = c.getString(4).orEmpty(), accessLevel = c.getInt(5),
                        visible = c.getInt(6) == 1,
                        // IS_PRIMARY is only filled in by some sync adapters; the owner matching the
                        // account is the older, reliable way to spot "the" calendar of an account.
                        primary = c.getInt(7) == 1 || (c.getString(8) != null && c.getString(8) == acct),
                    )
                }
            }
        }
        return out
    }

    fun setVisible(ctx: Context, id: Long, visible: Boolean) {
        runCatching {
            ctx.contentResolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, id),
                ContentValues().apply { put(Calendars.VISIBLE, if (visible) 1 else 0) }, null, null)
        }
    }

    fun defaultCalendar(ctx: Context, list: List<CalInfo> = calendars(ctx)): CalInfo? {
        val w = list.filter { it.writable }
        val remembered = ctx.getSharedPreferences("calendar", Context.MODE_PRIVATE).getLong("lastCalendar", -1)
        return w.firstOrNull { it.id == remembered }
            ?: w.firstOrNull { it.primary && it.accountType == "com.google" }
            ?: w.firstOrNull { it.primary }
            ?: w.firstOrNull()
    }

    fun rememberCalendar(ctx: Context, id: Long) =
        ctx.getSharedPreferences("calendar", Context.MODE_PRIVATE).edit().putLong("lastCalendar", id).apply()

    /**
     * With no account at all there is nowhere to save an event. Create an on-device calendar
     * the standard way: as a sync adapter for a LOCAL account, which any app holding
     * WRITE_CALENDAR may do. It never leaves the device.
     */
    fun ensureLocalCalendar(ctx: Context): Long? {
        calendars(ctx).firstOrNull { it.writable }?.let { return it.id }
        val acct = "Hione"
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, acct)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, acct)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "hione_local")
            put(Calendars.CALENDAR_DISPLAY_NAME, "On this device")
            put(Calendars.CALENDAR_COLOR, Mascot.argb(0xFFFFAB2E.toInt()))
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, acct)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return runCatching { ctx.contentResolver.insert(uri, v)?.let { ContentUris.parseId(it) } }.getOrNull()
    }

    /** Ask every account's sync adapter to sync calendars now (the refresh button). */
    fun requestSync(ctx: Context) {
        val accounts = calendars(ctx).map { it.accountName to it.accountType }.distinct()
            .filter { it.second != CalendarContract.ACCOUNT_TYPE_LOCAL }
        for ((name, type) in accounts) {
            runCatching {
                ContentResolver.requestSync(Account(name, type), CalendarContract.AUTHORITY, Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                })
            }
        }
    }

    fun instances(ctx: Context, startMs: Long, endMs: Long): List<Inst> {
        val out = ArrayList<Inst>()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startMs); ContentUris.appendId(it, endMs)
        }.build()
        runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.EVENT_LOCATION,
                    Instances.ALL_DAY, Instances.DISPLAY_COLOR, Instances.CALENDAR_ID, Instances.RRULE, Instances.RDATE, Instances.ORIGINAL_ID),
                "${Instances.VISIBLE}=1 AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS}!=${Events.STATUS_CANCELED})",
                null, "${Instances.BEGIN} ASC, ${Instances.ALL_DAY} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Inst(
                        eventId = c.getLong(0), begin = c.getLong(1), end = c.getLong(2),
                        title = c.getString(3)?.takeIf { it.isNotBlank() } ?: "(No title)",
                        location = c.getString(4).orEmpty(), allDay = c.getInt(5) == 1, color = c.getInt(6),
                        calendarId = c.getLong(7),
                        recurring = !c.getString(8).isNullOrBlank() || !c.getString(9).isNullOrBlank() || !c.isNull(10),
                    )
                }
            }
        }
        return out
    }

    fun event(ctx: Context, id: Long): EventFull? {
        val cr = ctx.contentResolver
        val reminders = ArrayList<Int>()
        runCatching {
            cr.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID}=?", arrayOf(id.toString()), "${Reminders.MINUTES} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val method = c.getInt(1)
                    if (method == Reminders.METHOD_ALERT || method == Reminders.METHOD_DEFAULT) reminders += c.getInt(0)
                }
            }
        }
        val guests = ArrayList<Guest>()
        runCatching {
            cr.query(Attendees.CONTENT_URI, arrayOf(Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_RELATIONSHIP),
                "${Attendees.EVENT_ID}=?", arrayOf(id.toString()), null)?.use { c ->
                while (c.moveToNext()) {
                    guests += Guest(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getInt(2), c.getInt(3) == Attendees.RELATIONSHIP_ORGANIZER)
                }
            }
        }
        return runCatching {
            cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, id),
                arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART,
                    Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.RRULE, Events.AVAILABILITY,
                    Events.ORGANIZER, Events.CALENDAR_DISPLAY_NAME, Events.CALENDAR_COLOR, Events.DISPLAY_COLOR, Events.CALENDAR_ACCESS_LEVEL),
                null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else EventFull(
                    id = c.getLong(0), calendarId = c.getLong(1), title = c.getString(2).orEmpty(),
                    description = c.getString(3).orEmpty(), location = c.getString(4).orEmpty(),
                    dtStart = c.getLong(5), dtEnd = if (c.isNull(6)) 0 else c.getLong(6), duration = c.getString(7),
                    allDay = c.getInt(8) == 1, timeZone = c.getString(9) ?: TimeZone.getDefault().id,
                    rrule = c.getString(10)?.takeIf { it.isNotBlank() }, availability = c.getInt(11),
                    reminders = reminders.distinct(), guests = guests, organizer = c.getString(12),
                    calendarName = c.getString(13).orEmpty(), calendarColor = c.getInt(14), color = c.getInt(15),
                    canEdit = c.getInt(16) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }.getOrNull()
    }

    private fun draftValues(d: EventDraft, forException: Boolean = false): ContentValues = ContentValues().apply {
        put(Events.CALENDAR_ID, d.calendarId)
        put(Events.TITLE, d.title)
        put(Events.DESCRIPTION, d.description)
        put(Events.EVENT_LOCATION, d.location)
        put(Events.ALL_DAY, if (d.allDay) 1 else 0)
        put(Events.AVAILABILITY, d.availability)
        // All-day events must be stored at UTC midnights in the UTC zone, or the provider and
        // Google Calendar disagree about which day they fall on.
        put(Events.EVENT_TIMEZONE, if (d.allDay) "UTC" else d.timeZone)
        put(Events.DTSTART, d.start)
        if (d.rrule != null && !forException) {
            // Recurring events carry DURATION and no DTEND; the provider rejects both together.
            put(Events.RRULE, d.rrule)
            val len = (d.end - d.start).coerceAtLeast(if (d.allDay) 86_400_000L else 0)
            put(Events.DURATION, if (d.allDay) "P${len / 86_400_000L}D" else "P${len / 1000}S")
            putNull(Events.DTEND)
        } else {
            put(Events.DTEND, d.end)
            if (!forException) { putNull(Events.RRULE); putNull(Events.DURATION) }
        }
    }

    fun insert(ctx: Context, d: EventDraft): Long? {
        val cr = ctx.contentResolver
        val uri = cr.insert(Events.CONTENT_URI, draftValues(d)) ?: return null
        val id = ContentUris.parseId(uri)
        writeReminders(ctx, id, d.reminders)
        for (email in d.guests) {
            runCatching {
                cr.insert(Attendees.CONTENT_URI, ContentValues().apply {
                    put(Attendees.EVENT_ID, id); put(Attendees.ATTENDEE_EMAIL, email)
                    put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
                    put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
                    put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_INVITED)
                })
            }
        }
        rememberCalendar(ctx, d.calendarId)
        return id
    }

    fun update(ctx: Context, id: Long, d: EventDraft): Boolean {
        val n = ctx.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), draftValues(d), null, null)
        writeReminders(ctx, id, d.reminders)
        return n > 0
    }

    /** Change one occurrence of a recurring event: the provider stores it as an exception. */
    fun updateOccurrence(ctx: Context, id: Long, instanceBegin: Long, d: EventDraft): Long? {
        val v = draftValues(d, forException = true).apply { put(Events.ORIGINAL_INSTANCE_TIME, instanceBegin); put(Events.STATUS, Events.STATUS_CONFIRMED) }
        val uri = ctx.contentResolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), v) ?: return null
        val newId = ContentUris.parseId(uri)
        if (newId > 0) writeReminders(ctx, newId, d.reminders)
        return newId
    }

    fun delete(ctx: Context, id: Long): Boolean =
        ctx.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0

    /** Delete one occurrence: a cancelled exception at that instance. */
    fun deleteOccurrence(ctx: Context, id: Long, instanceBegin: Long): Boolean {
        val v = ContentValues().apply { put(Events.ORIGINAL_INSTANCE_TIME, instanceBegin); put(Events.STATUS, Events.STATUS_CANCELED) }
        return ctx.contentResolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), v) != null
    }

    private fun writeReminders(ctx: Context, eventId: Long, minutes: List<Int>) {
        val cr = ctx.contentResolver
        runCatching { cr.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID}=?", arrayOf(eventId.toString())) }
        for (m in minutes.distinct()) {
            runCatching {
                cr.insert(Reminders.CONTENT_URI, ContentValues().apply {
                    put(Reminders.EVENT_ID, eventId); put(Reminders.MINUTES, m); put(Reminders.METHOD, Reminders.METHOD_ALERT)
                })
            }
        }
    }

    fun eventUri(id: Long): Uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)
}

/** Recomposes when anything in the calendar provider changes (sync, edits from other apps). */
@Composable
fun rememberCalendarRevision(): Int {
    val ctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { rev++ }
        }
        runCatching { ctx.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, obs) }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(obs) } }
    }
    return rev
}
