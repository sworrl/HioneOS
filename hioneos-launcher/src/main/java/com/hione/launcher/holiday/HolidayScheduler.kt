package com.hione.launcher.holiday

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hione.launcher.Mascot
import java.time.LocalDate
import java.time.ZoneId

/**
 * Re-evaluates the holiday and season art at local midnight, at boot, and when the clock, time
 * zone or locale changes. Posts the holiday reminders while it is at it.
 */
object HolidayScheduler {
    private const val TAG = "HioneHolidays"
    const val ACTION_TICK = "com.hione.launcher.holiday.TICK"
    private const val CHANNEL = "holidays"
    private const val PREFS = "hione_holidays"

    /** An inexact alarm a minute after the next local midnight. Doze may delay it, the art then changes on wake. */
    fun scheduleNextMidnight(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val zone = ZoneId.systemDefault()
            val at = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusMinutes(1).toInstant().toEpochMilli()
            val pi = PendingIntent.getBroadcast(
                ctx, 7301,
                Intent(ACTION_TICK).setClass(ctx, HolidayReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { Log.w(TAG, "could not arm the midnight alarm: $it") }
    }

    /**
     * Post today's reminders: on the holiday, or the day before for respectful days. Plain text,
     * default importance on its own channel, so Do Not Disturb silences it like any other. Each
     * holiday is reminded at most once per year.
     */
    fun postReminders(ctx: Context) {
        if (!HolidayState.remindersEnabled(ctx)) return
        val today = LocalDate.now()
        val due = HolidayEngine.remindersDue(today, HolidayState.calendar(ctx), HolidayState.scope(ctx))
        if (due.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Holidays", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A short note on the holidays you picked in Settings, Theme & mascot"
                }
            )
        }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (o in due) {
            val key = "reminded_${o.holiday.id}_${o.date.year}"
            if (prefs.getBoolean(key, false)) continue
            val text = Mascot.fill(o.holiday.reminder ?: continue)
            val title = if (o.holiday.respectful && o.date != today) "${o.holiday.name} is tomorrow" else o.holiday.name
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify("holiday", o.holiday.id.hashCode(), n) }
                .onSuccess { prefs.edit().putBoolean(key, true).apply() }
        }
    }
}

/** Midnight tick, boot, clock, time zone and locale changes. */
class HolidayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                HolidayState.start(app)          // first call refreshes and arms the alarm
                HolidayState.refresh(app)
                HolidayScheduler.postReminders(app)
                HolidayScheduler.scheduleNextMidnight(app)
            } catch (t: Throwable) {
                Log.w("HioneHolidays", "tick failed: $t")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
