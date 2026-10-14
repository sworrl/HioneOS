package com.hione.player

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Persistent, silent, low-priority "Alarm · Tomorrow 07:00 · in 8h" notification — shown ONLY
 * while the soonest armed alarm is within the next 24 h, and only if the user hasn't turned it
 * off in Alarm settings. Everything it states comes from [[AlarmScheduler.soonest]] (the same
 * math that armed AlarmManager), cross-checked against [[AlarmScheduler.systemArmedNext]] so it
 * never advertises an alarm the OS doesn't actually hold.
 *
 * When the next fire is further out than 24 h, an INEXACT (`setAndAllowWhileIdle`, batched,
 * Doze-tolerant — deliberately not exact, this is cosmetic) nudge is armed for the moment the
 * alarm crosses into the window so the notification appears on time even with the app closed.
 */
object AlarmUpcomingNotifier {
    private const val CHANNEL_ID = "hione_alarm_upcoming"
    private const val NOTIF_ID = 7099
    private const val REQUEST_CODE = 41_999
    const val WINDOW_MS = 24L * 3_600_000L

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val enabled = AlarmPreferences.loadUpcomingNotificationEnabled(app)
        val soonest = AlarmScheduler.soonest(app)
        val systemNext = AlarmScheduler.systemArmedNext(app)
        val now = System.currentTimeMillis()
        // Only trust our own bookkeeping when AlarmManager agrees (within a minute) — otherwise
        // we'd be announcing an alarm that isn't really armed.
        val armed = soonest != null && systemNext != null && kotlin.math.abs(systemNext - soonest.second) < 60_000L
        if (!enabled || soonest == null || !armed) {
            nm.cancel(NOTIF_ID)
            cancelNudge(app)
            return
        }
        val (alarm, at) = soonest
        if (at - now > WINDOW_MS) {
            nm.cancel(NOTIF_ID)
            armNudge(app, at - WINDOW_MS + 5_000L)
            return
        }
        cancelNudge(app)
        ensureChannel(nm)
        val open = PendingIntent.getActivity(
            app, REQUEST_CODE,
            Intent(app, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AlarmScheduler.EXTRA_OPEN_ALARMS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val snoozed = AlarmPreferences.loadActiveSnooze(app) == alarm.id
        val title = (if (snoozed) "Snoozed: " else "Alarm: ") + alarm.label.ifBlank { "Alarm" }
        val text = "${AlarmScheduler.formatWhen(at, now)} · ${AlarmScheduler.formatCountdown(at, now)} · ${AlarmLibrary.describeSource(alarm.source, alarm.sourceRef)}"
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(at)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        cancelNudge(app)
    }

    private fun nudgeIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, REQUEST_CODE,
        Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_REFRESH_UPCOMING),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun armNudge(app: Context, at: Long) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, nudgeIntent(app)) }
    }

    private fun cancelNudge(app: Context) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(nudgeIntent(app)) }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Upcoming alarm", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Quiet reminder while a ${Mascot.musicAppName} alarm is due within 24 hours"
                    setShowBadge(false)
                }
            )
        }
    }
}
