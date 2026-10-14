package com.hione.tools.calendar

import com.hione.tools.Mascot
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.CalendarContract.CalendarAlerts
import android.util.Log
import com.hione.tools.R

/**
 * Event reminders. The calendar provider computes when each reminder is due and broadcasts
 * EVENT_REMINDER at that moment, but it shows nothing itself: posting the notification was
 * stock Calendar's job, so this receiver has to exist or reminders vanish with stock Calendar.
 *
 * Follows the provider's alert protocol the same way stock did: alerts due now are in
 * CalendarAlerts with STATE_SCHEDULED; we notify and mark them FIRED, and mark them DISMISSED
 * when the user dismisses, so other calendar apps and a reboot see a consistent state.
 */
open class ReminderReceiver : BroadcastReceiver() {
    /** This receiver is exported (the provider's broadcast must reach it); the notification
     *  buttons go to the unexported [ReminderActionReceiver] subclass instead, so another app
     *  cannot post fake reminders through ACTION_SNOOZED. */
    protected open val handlesInternal = false

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        if (!CalendarRepo.hasPermission(ctx)) return
        val internal = intent.action in setOf(ACTION_DISMISS, ACTION_SNOOZE, ACTION_SNOOZED)
        if (internal != handlesInternal) return
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    CalendarContract.ACTION_EVENT_REMINDER -> fireDue(ctx, silent = false)
                    Intent.ACTION_BOOT_COMPLETED -> fireDue(ctx, silent = true, includeFired = true)
                    ACTION_DISMISS -> dismiss(ctx, intent.getLongExtra(EXTRA_EVENT_ID, -1), intent.getLongExtra(EXTRA_BEGIN, 0))
                    ACTION_SNOOZE -> snooze(ctx, intent)
                    ACTION_SNOOZED -> post(ctx, Alert(
                        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1), begin = intent.getLongExtra(EXTRA_BEGIN, 0),
                        end = intent.getLongExtra(EXTRA_END, 0), title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                        location = intent.getStringExtra(EXTRA_LOCATION).orEmpty(), allDay = intent.getBooleanExtra(EXTRA_ALL_DAY, false)
                    ), silent = false)
                }
            } catch (t: Throwable) {
                Log.w("HioneCalendar", "reminder ${intent.action} failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private data class Alert(val eventId: Long, val begin: Long, val end: Long, val title: String, val location: String, val allDay: Boolean)

    private fun fireDue(ctx: Context, silent: Boolean, includeFired: Boolean = false) {
        val now = System.currentTimeMillis()
        val states = if (includeFired) "${CalendarAlerts.STATE_SCHEDULED},${CalendarAlerts.STATE_FIRED}" else "${CalendarAlerts.STATE_SCHEDULED}"
        val cr = ctx.contentResolver
        val due = ArrayList<Pair<Long, Alert>>()
        cr.query(
            CalendarAlerts.CONTENT_URI,
            arrayOf(CalendarAlerts._ID, CalendarAlerts.EVENT_ID, CalendarAlerts.BEGIN, CalendarAlerts.END,
                CalendarAlerts.TITLE, CalendarAlerts.EVENT_LOCATION, CalendarAlerts.ALL_DAY),
            "${CalendarAlerts.STATE} IN ($states) AND ${CalendarAlerts.ALARM_TIME}<=? AND ${CalendarAlerts.END}>=?",
            arrayOf((now + 60_000).toString(), (now - 6 * 3_600_000L).toString()),
            "${CalendarAlerts.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                due += c.getLong(0) to Alert(c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getInt(6) == 1)
            }
        }
        // Several reminders for one occurrence (30 min and 10 min before) share one notification.
        due.groupBy { it.second.eventId to it.second.begin }.forEach { (_, list) ->
            post(ctx, list.first().second, silent)
            list.forEach { (alertId, _) ->
                runCatching {
                    cr.update(ContentUris.withAppendedId(CalendarAlerts.CONTENT_URI, alertId),
                        ContentValues().apply { put(CalendarAlerts.STATE, CalendarAlerts.STATE_FIRED) }, null, null)
                }
            }
        }
    }

    private fun post(ctx: Context, a: Alert, silent: Boolean) {
        if (a.eventId < 0) return
        ensureChannel(ctx)
        val nid = notifId(a.eventId, a.begin)
        val open = PendingIntent.getActivity(
            ctx, nid,
            Intent(Intent.ACTION_VIEW, CalendarRepo.eventUri(a.eventId), ctx, EventInfoActivity::class.java)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, a.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, a.end)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = buildString {
            append(CalFormat.range(ctx, a.begin, a.end, a.allDay))
            if (a.location.isNotBlank()) append("  ·  ").append(a.location)
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_event)
            .setColor(Mascot.argb(0xFFFFAB2E.toInt()))
            .setContentTitle(a.title.ifBlank { "(No title)" })
            .setContentText(text)
            .setCategory(Notification.CATEGORY_EVENT)
            .setWhen(a.begin).setShowWhen(true)
            .setContentIntent(open)
            .setDeleteIntent(action(ctx, ACTION_DISMISS, a, nid + 1))
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .addAction(Notification.Action.Builder(null, "Snooze 5 min", action(ctx, ACTION_SNOOZE, a, nid + 2)).build())
            .addAction(Notification.Action.Builder(null, "Dismiss", action(ctx, ACTION_DISMISS, a, nid + 3)).build())
            .apply { if (silent) setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).setGroup("silent_reminders") }
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(nid, n) }
    }

    private fun action(ctx: Context, action: String, a: Alert, req: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, req,
        Intent(ctx, ReminderActionReceiver::class.java).setAction(action)
            .putExtra(EXTRA_EVENT_ID, a.eventId).putExtra(EXTRA_BEGIN, a.begin).putExtra(EXTRA_END, a.end)
            .putExtra(EXTRA_TITLE, a.title).putExtra(EXTRA_LOCATION, a.location).putExtra(EXTRA_ALL_DAY, a.allDay),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun dismiss(ctx: Context, eventId: Long, begin: Long) {
        if (eventId < 0) return
        ctx.getSystemService(NotificationManager::class.java).cancel(notifId(eventId, begin))
        runCatching {
            ctx.contentResolver.update(CalendarAlerts.CONTENT_URI,
                ContentValues().apply { put(CalendarAlerts.STATE, CalendarAlerts.STATE_DISMISSED) },
                "${CalendarAlerts.EVENT_ID}=? AND ${CalendarAlerts.BEGIN}=? AND ${CalendarAlerts.STATE}=${CalendarAlerts.STATE_FIRED}",
                arrayOf(eventId.toString(), begin.toString()))
        }
    }

    private fun snooze(ctx: Context, i: Intent) {
        val eventId = i.getLongExtra(EXTRA_EVENT_ID, -1)
        val begin = i.getLongExtra(EXTRA_BEGIN, 0)
        dismiss(ctx, eventId, begin)
        val again = PendingIntent.getBroadcast(
            ctx, notifId(eventId, begin) + 4,
            Intent(ctx, ReminderActionReceiver::class.java).setAction(ACTION_SNOOZED).putExtras(i),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + 5 * 60_000L
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, again) }
            .onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, again) } }
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Event reminders", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    /** Stable per occurrence, spaced by 8 so the action request codes above never collide. */
    private fun notifId(eventId: Long, begin: Long): Int = 100_000 + ((eventId * 31 + begin / 60_000) and 0x00FFFFFF).toInt() * 8

    companion object {
        private const val CHANNEL = "event_reminders"
        const val ACTION_DISMISS = "com.hione.tools.calendar.REMINDER_DISMISS"
        const val ACTION_SNOOZE = "com.hione.tools.calendar.REMINDER_SNOOZE"
        const val ACTION_SNOOZED = "com.hione.tools.calendar.REMINDER_SNOOZED"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_BEGIN = "begin"
        const val EXTRA_END = "end"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_ALL_DAY = "all_day"
    }
}

/** Unexported twin of [ReminderReceiver] for the notification's own buttons and snooze alarm. */
class ReminderActionReceiver : ReminderReceiver() {
    override val handlesInternal = true
}
