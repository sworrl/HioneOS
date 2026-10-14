package com.hione.tools.clock

import com.hione.tools.Mascot
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import com.hione.tools.R
import java.util.Calendar
import java.util.Locale

/** Time formatting that follows the system 12/24-hour setting. */
object ClockFormat {
    fun is24(ctx: Context) = DateFormat.is24HourFormat(ctx)

    fun time(ctx: Context, hour: Int, minute: Int): String =
        if (is24(ctx)) String.format(Locale.US, "%02d:%02d", hour, minute)
        else String.format(Locale.US, "%d:%02d", if (hour % 12 == 0) 12 else hour % 12, minute)

    fun amPm(ctx: Context, hour: Int): String = if (is24(ctx)) "" else if (hour < 12) "AM" else "PM"

    fun timeFull(ctx: Context, hour: Int, minute: Int): String =
        (time(ctx, hour, minute) + " " + amPm(ctx, hour)).trim()

    fun instant(ctx: Context, millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return timeFull(ctx, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** "Today 7:00 AM", "Tomorrow 7:00 AM", "Fri 7:00 AM". */
    fun whenText(ctx: Context, millis: Long, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val t = Calendar.getInstance().apply { timeInMillis = now }
        val day = when {
            sameDay(c, t) -> "Today"
            sameDay(c, (t.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }) -> "Tomorrow"
            else -> DAY_SHORT[c.get(Calendar.DAY_OF_WEEK) - 1]
        }
        return "$day ${instant(ctx, millis)}"
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** "in 7 h 12 min" */
    fun until(millis: Long, now: Long = System.currentTimeMillis()): String {
        val totalMin = ((millis - now).coerceAtLeast(0) + 59_999) / 60_000
        val d = totalMin / 1440; val h = (totalMin % 1440) / 60; val m = totalMin % 60
        return when {
            d > 0 -> "in $d d $h h"
            h > 0 -> "in $h h $m min"
            m > 0 -> "in $m min"
            else -> "in under a minute"
        }
    }

    /** Countdown or stopwatch text. Hours only when needed. */
    fun duration(ms: Long, showHundredths: Boolean = false): String {
        val neg = ms < 0
        val a = kotlin.math.abs(ms)
        val totalSec = if (showHundredths || neg) a / 1000 else (a + 999) / 1000 // countdowns round up
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        val base = if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
        val hs = if (showHundredths) String.format(Locale.US, ".%02d", (a % 1000) / 10) else ""
        return (if (neg) "−" else "") + base + hs
    }

    val DAY_SHORT = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    fun daysSummary(days: Set<Int>): String = when {
        days.isEmpty() -> "Once"
        days.size == 7 -> "Every day"
        days == setOf(2, 3, 4, 5, 6) -> "Weekdays"
        days == setOf(1, 7) -> "Weekends"
        else -> listOf(2, 3, 4, 5, 6, 7, 1).filter { it in days }.joinToString(", ") { DAY_SHORT[it - 1] }
    }
}

object ClockNotify {
    const val CH_RING = "ringing"
    const val CH_UPCOMING = "upcoming"
    const val CH_TIMERS = "timers"
    const val CH_MISSED = "missed"
    const val CH_STOPWATCH = "stopwatch"

    const val ID_RING = 1
    const val ID_STOPWATCH = 4000
    fun idUpcoming(alarmId: Int) = 2000 + alarmId
    fun idMissed(alarmId: Int) = 2500 + alarmId
    fun idTimer(timerId: Int) = 3000 + timerId

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_RING) != null) return
        nm.createNotificationChannels(listOf(
            // Sound and vibration come from RingService itself (with gradual volume and the
            // alarm stream); a channel sound on top would double up.
            NotificationChannel(CH_RING, "Ringing alarms and timers", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(CH_UPCOMING, "Upcoming and snoozed alarms", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            NotificationChannel(CH_TIMERS, "Running timers", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            NotificationChannel(CH_MISSED, "Missed alarms", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_STOPWATCH, "Stopwatch", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        ))
    }

    fun openClock(ctx: Context, tab: Int, req: Int): PendingIntent = PendingIntent.getActivity(
        ctx, req,
        Intent(ctx, ClockActivity::class.java).putExtra(ClockActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun action(ctx: Context, action: String, id: Int, req: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, req,
        Intent(ctx, ClockReceiver::class.java).setAction(action).putExtra(ClockReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun builder(ctx: Context, channel: String): Notification.Builder =
        Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_stat_alarm).setColor(Mascot.argb(0xFFFFAB2E.toInt()))

    fun nm(ctx: Context): NotificationManager = ctx.getSystemService(NotificationManager::class.java)
}

/**
 * Arms alarms with AlarmManager.setAlarmClock: exact, exempt from Doze and app standby, and the
 * only kind that shows in the status bar and on the lock screen as "next alarm". Every alarm
 * has one stable PendingIntent, so arming again replaces rather than duplicates.
 */
object AlarmScheduler {
    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun firePi(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, 10_000 + id,
        Intent(ctx, ClockReceiver::class.java).setAction(ClockReceiver.ACTION_ALARM_FIRE).putExtra(ClockReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun upcomingPi(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, 11_000 + id,
        Intent(ctx, ClockReceiver::class.java).setAction(ClockReceiver.ACTION_ALARM_UPCOMING).putExtra(ClockReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || runCatching { am(ctx).canScheduleExactAlarms() }.getOrDefault(true)

    private const val UPCOMING_WINDOW = 2 * 3_600_000L

    /** Bring AlarmManager and the notifications in line with what the store says. Idempotent
     *  and cheap; called after every change and from boot / time-change broadcasts. */
    fun sync(ctx: Context) {
        val now = System.currentTimeMillis()
        val nm = ClockNotify.nm(ctx)
        ClockNotify.ensureChannels(ctx)
        for (a in ClockStore.alarms(ctx)) {
            val next = AlarmMath.nextFire(a, now)
            if (next == null) {
                am(ctx).cancel(firePi(ctx, a.id))
                am(ctx).cancel(upcomingPi(ctx, a.id))
                nm.cancel(ClockNotify.idUpcoming(a.id))
                if (a.armedFor != 0L) ClockStore.updateAlarm(ctx, a.id) { it.copy(armedFor = 0) }
                continue
            }
            val show = PendingIntent.getActivity(
                ctx, 12_000 + a.id,
                Intent(ctx, ClockActivity::class.java).putExtra(ClockActivity.EXTRA_TAB, ClockActivity.TAB_ALARMS)
                    .putExtra(ClockActivity.EXTRA_ALARM_ID, a.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            runCatching { am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(next, show), firePi(ctx, a.id)) }
                .onFailure {
                    // No exact-alarm permission (should not happen for a system alarm app): an
                    // alarm a few minutes late beats one that never rings.
                    runCatching { am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, firePi(ctx, a.id)) }
                }
            if (a.armedFor != next) ClockStore.updateAlarm(ctx, a.id) { it.copy(armedFor = next) }

            // Upcoming / snoozed notification with a "Dismiss now" button, as stock does, so an
            // alarm you woke up before can be stopped without waiting for it.
            if (a.snoozedUntil > now || next - now <= UPCOMING_WINDOW) {
                am(ctx).cancel(upcomingPi(ctx, a.id))
                postUpcoming(ctx, a, next, snoozed = a.snoozedUntil > now)
            } else {
                nm.cancel(ClockNotify.idUpcoming(a.id))
                // Inexact is fine for a heads-up two hours early.
                runCatching { am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next - UPCOMING_WINDOW, upcomingPi(ctx, a.id)) }
            }
        }
    }

    private fun postUpcoming(ctx: Context, a: ClockAlarm, next: Long, snoozed: Boolean) {
        val title = if (snoozed) "Snoozed until ${ClockFormat.instant(ctx, next)}" else "Upcoming alarm"
        val text = buildString {
            if (!snoozed) append(ClockFormat.whenText(ctx, next))
            if (a.label.isNotBlank()) { if (isNotEmpty()) append("  ·  "); append(a.label) }
        }
        val n = ClockNotify.builder(ctx, ClockNotify.CH_UPCOMING)
            .setContentTitle(title)
            .setContentText(text.ifBlank { "Alarm" })
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(snoozed)
            .setShowWhen(false)
            .setContentIntent(ClockNotify.openClock(ctx, ClockActivity.TAB_ALARMS, 13_000 + a.id))
            .addAction(Notification.Action.Builder(null, "Dismiss now",
                ClockNotify.action(ctx, ClockReceiver.ACTION_ALARM_DISMISS_EARLY, a.id, 14_000 + a.id)).build())
            .build()
        runCatching { ClockNotify.nm(ctx).notify(ClockNotify.idUpcoming(a.id), n) }
    }

    /** "Dismiss now" before an alarm rings: a one-shot turns off, a repeating one skips just
     *  this occurrence. Also how DISMISS_ALARM treats an alarm that is not ringing. */
    fun dismissEarly(ctx: Context, id: Int) {
        val now = System.currentTimeMillis()
        ClockStore.updateAlarm(ctx, id) { a ->
            when {
                a.snoozedUntil > now -> if (a.repeating) a.copy(snoozedUntil = 0) else if (a.deleteAfterUse) null else a.copy(snoozedUntil = 0, enabled = false)
                a.repeating -> a.copy(skipUntil = AlarmMath.nextFire(a, now) ?: now)
                a.deleteAfterUse -> null
                else -> a.copy(enabled = false)
            }
        }
        ClockNotify.nm(ctx).cancel(ClockNotify.idUpcoming(id))
        sync(ctx)
    }

    /** After a reboot or clock change: an alarm whose armed instant passed while we could not
     *  ring it gets a "Missed alarm" notification, and a one-shot one is turned off. */
    fun checkMissed(ctx: Context) {
        val now = System.currentTimeMillis()
        for (a in ClockStore.alarms(ctx)) {
            val t = a.armedFor
            if (!a.enabled || t == 0L || t > now - 60_000 || t < now - 12 * 3_600_000L) continue
            if (RingService.isRingingAlarm(a.id)) continue
            postMissed(ctx, a, t)
            ClockStore.updateAlarm(ctx, a.id) {
                if (it.repeating) it.copy(armedFor = 0, snoozedUntil = 0)
                else if (it.deleteAfterUse) null
                else it.copy(armedFor = 0, snoozedUntil = 0, enabled = false)
            }
        }
    }

    fun postMissed(ctx: Context, a: ClockAlarm, at: Long) {
        ClockNotify.ensureChannels(ctx)
        val n = ClockNotify.builder(ctx, ClockNotify.CH_MISSED)
            .setContentTitle("Missed alarm")
            .setContentText(ClockFormat.instant(ctx, at) + if (a.label.isNotBlank()) "  ·  ${a.label}" else "")
            .setCategory(Notification.CATEGORY_ALARM)
            .setWhen(at).setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(ClockNotify.openClock(ctx, ClockActivity.TAB_ALARMS, 15_000 + a.id))
            .build()
        runCatching { ClockNotify.nm(ctx).notify(ClockNotify.idMissed(a.id), n) }
    }
}

/** Timers: one exact elapsed-realtime alarm per running timer, plus its ongoing notification. */
object TimerScheduler {
    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun expirePi(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, 20_000 + id,
        Intent(ctx, ClockReceiver::class.java).setAction(ClockReceiver.ACTION_TIMER_EXPIRED).putExtra(ClockReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun sync(ctx: Context) {
        ClockNotify.ensureChannels(ctx)
        val nm = ClockNotify.nm(ctx)
        val timers = ClockStore.timers(ctx)
        val known = timers.map { it.id }.toSet()
        // Clean up notifications for timers that were deleted.
        runCatching {
            nm.activeNotifications.filter { it.id in 3001..3999 && (it.id - 3000) !in known }.forEach { nm.cancel(it.id) }
        }
        for (t in timers) {
            when (t.state) {
                TimerState.RUNNING -> {
                    // setExactAndAllowWhileIdle, not setAlarmClock: a 5-minute pasta timer has no
                    // business replacing the 7 am alarm as the status bar's "next alarm".
                    val pi = expirePi(ctx, t.id)
                    runCatching { am(ctx).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, t.endElapsed, pi) }
                        .onFailure { runCatching { am(ctx).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, t.endElapsed, pi) } }
                    postTimer(ctx, t)
                }
                TimerState.PAUSED -> { am(ctx).cancel(expirePi(ctx, t.id)); postTimer(ctx, t) }
                TimerState.RESET -> { am(ctx).cancel(expirePi(ctx, t.id)); nm.cancel(ClockNotify.idTimer(t.id)) }
                TimerState.EXPIRED -> { am(ctx).cancel(expirePi(ctx, t.id)); nm.cancel(ClockNotify.idTimer(t.id)) }
            }
        }
    }

    private fun postTimer(ctx: Context, t: ClockTimer) {
        val running = t.state == TimerState.RUNNING
        val b = ClockNotify.builder(ctx, ClockNotify.CH_TIMERS)
            .setContentTitle(t.label.ifBlank { "Timer" } + if (running) "" else "  (paused)")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentIntent(ClockNotify.openClock(ctx, ClockActivity.TAB_TIMER, 21_000 + t.id))
        if (running) {
            b.setUsesChronometer(true).setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + (t.endElapsed - SystemClock.elapsedRealtime()))
                .setShowWhen(true)
        } else {
            b.setContentText(ClockFormat.duration(t.remainingMs) + " left").setShowWhen(false)
        }
        b.addAction(Notification.Action.Builder(null, if (running) "Pause" else "Resume",
            ClockNotify.action(ctx, if (running) ClockReceiver.ACTION_TIMER_PAUSE else ClockReceiver.ACTION_TIMER_START, t.id, 22_000 + t.id)).build())
        b.addAction(Notification.Action.Builder(null, "+1 min",
            ClockNotify.action(ctx, ClockReceiver.ACTION_TIMER_ADD_MINUTE, t.id, 23_000 + t.id)).build())
        b.addAction(Notification.Action.Builder(null, "Reset",
            ClockNotify.action(ctx, ClockReceiver.ACTION_TIMER_RESET, t.id, 24_000 + t.id)).build())
        runCatching { ClockNotify.nm(ctx).notify(ClockNotify.idTimer(t.id), b.build()) }
    }

    // ---- state transitions, shared by the UI, notification actions and the API handler

    fun start(ctx: Context, id: Int) {
        val nowE = SystemClock.elapsedRealtime(); val nowW = System.currentTimeMillis()
        ClockStore.updateTimer(ctx, id) { t ->
            if (t.state == TimerState.RUNNING) t
            else {
                val rem = if (t.state == TimerState.EXPIRED || t.remainingMs <= 0) t.lengthMs else t.remainingMs
                t.copy(state = TimerState.RUNNING, endElapsed = nowE + rem, endWall = nowW + rem, remainingMs = rem)
            }
        }
        sync(ctx)
    }

    fun pause(ctx: Context, id: Int) {
        val nowE = SystemClock.elapsedRealtime()
        ClockStore.updateTimer(ctx, id) { t ->
            if (t.state != TimerState.RUNNING) t else t.copy(state = TimerState.PAUSED, remainingMs = (t.endElapsed - nowE).coerceAtLeast(0))
        }
        sync(ctx)
    }

    fun reset(ctx: Context, id: Int) {
        ClockStore.updateTimer(ctx, id) { t ->
            if (t.deleteAfterUse && t.state == TimerState.EXPIRED) null
            else t.copy(state = TimerState.RESET, remainingMs = t.lengthMs, endElapsed = 0, endWall = 0)
        }
        RingService.timerHandled(ctx, id)
        sync(ctx)
    }

    fun addMinute(ctx: Context, id: Int) {
        val nowE = SystemClock.elapsedRealtime(); val nowW = System.currentTimeMillis()
        ClockStore.updateTimer(ctx, id) { t ->
            when (t.state) {
                TimerState.RUNNING -> t.copy(endElapsed = t.endElapsed + 60_000, endWall = t.endWall + 60_000)
                TimerState.PAUSED, TimerState.RESET -> t.copy(remainingMs = t.remainingMs + 60_000, state = if (t.state == TimerState.RESET) TimerState.PAUSED else t.state)
                // A ringing timer given another minute starts counting that minute.
                TimerState.EXPIRED -> t.copy(state = TimerState.RUNNING, endElapsed = nowE + 60_000, endWall = nowW + 60_000, remainingMs = 60_000)
            }
        }
        RingService.timerHandled(ctx, id)
        sync(ctx)
    }

    fun delete(ctx: Context, id: Int) {
        ClockStore.updateTimer(ctx, id) { null }
        am(ctx).cancel(expirePi(ctx, id))
        ClockNotify.nm(ctx).cancel(ClockNotify.idTimer(id))
        RingService.timerHandled(ctx, id)
        sync(ctx)
    }

    /** elapsedRealtime restarts at zero on boot: rebuild running timers from their wall end. */
    fun fixAfterBoot(ctx: Context) {
        val nowE = SystemClock.elapsedRealtime(); val nowW = System.currentTimeMillis()
        ClockStore.updateTimers(ctx) { t ->
            if (t.state == TimerState.RUNNING || t.state == TimerState.EXPIRED) t.copy(endElapsed = nowE + (t.endWall - nowW)) else t
        }
    }

    /** The wall clock moved (TIME_SET) but elapsedRealtime did not: refresh the wall end. */
    fun fixAfterTimeChange(ctx: Context) {
        val nowE = SystemClock.elapsedRealtime(); val nowW = System.currentTimeMillis()
        ClockStore.updateTimers(ctx) { t ->
            if (t.state == TimerState.RUNNING || t.state == TimerState.EXPIRED) t.copy(endWall = nowW + (t.endElapsed - nowE)) else t
        }
    }
}

/** Ongoing stopwatch notification so a running stopwatch is not forgotten in the background. */
object StopwatchNotifier {
    fun sync(ctx: Context) {
        val sw = ClockStore.stopwatch(ctx)
        val nm = ClockNotify.nm(ctx)
        if (!sw.running) { nm.cancel(ClockNotify.ID_STOPWATCH); return }
        ClockNotify.ensureChannels(ctx)
        val n = ClockNotify.builder(ctx, ClockNotify.CH_STOPWATCH)
            .setContentTitle("Stopwatch")
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - sw.totalNow())
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentIntent(ClockNotify.openClock(ctx, ClockActivity.TAB_STOPWATCH, 30_000))
            .addAction(Notification.Action.Builder(null, "Pause", ClockNotify.action(ctx, ClockReceiver.ACTION_SW_PAUSE, 0, 30_001)).build())
            .build()
        runCatching { nm.notify(ClockNotify.ID_STOPWATCH, n) }
    }
}
