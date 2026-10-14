package com.hione.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Schedules/cancels alarms via `AlarmManager.setAlarmClock` — the strongest wake guarantee
 * Android offers a third-party app (fully Doze/battery-restriction exempt, guaranteed exact,
 * shows the platform's own alarm-clock status bar icon so the user can see it's really armed).
 * Requires SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM, both declared in the manifest.
 *
 * Each `Alarm` is one-shot at the OS level even when it repeats at the app level: `setAlarmClock`
 * fires once, so [[AlarmRingService]] re-arms the same alarm's NEXT occurrence after it fires
 * (or after a snooze), and [[AlarmBootReceiver]] re-arms everything after a reboot / time-zone
 * change / app update (exact alarms do not survive a reboot).
 *
 * The request code per alarm is stable, so re-scheduling replaces (never duplicates) the pending
 * fire; a pending snooze uses the SAME code, and rescheduleAll() deliberately re-arms the snooze
 * instant rather than the regular next occurrence while a snooze is outstanding.
 */
object AlarmScheduler {
    /** Re-arm every enabled alarm (idempotent). Safe to call from any thread; cheap. */
    fun rescheduleAll(ctx: Context) {
        runCatching { AlarmLocation.refreshIfStale(ctx) }
        val alarms = AlarmPreferences.loadAlarms(ctx)
        val snoozeId = AlarmPreferences.loadActiveSnooze(ctx)
        val snoozeUntil = AlarmPreferences.loadSnoozeUntil(ctx)
        val now = System.currentTimeMillis()
        // A stale snooze marker (its instant already passed while the process was dead / the app
        // was force-stopped) is just noise — drop it so the alarm goes back to its normal cadence.
        if (snoozeId != null && (snoozeUntil <= now || alarms.none { it.id == snoozeId && it.enabled })) {
            AlarmPreferences.saveActiveSnooze(ctx, null)
        }
        alarms.forEach { a ->
            when {
                !a.enabled -> cancel(ctx, a.id)
                a.id == snoozeId && snoozeUntil > now -> setExact(ctx, a.id, snoozeUntil)
                else -> scheduleNext(ctx, a)
            }
        }
        runCatching { AlarmUpcomingNotifier.refresh(ctx) }
    }

    /** Arms the alarm's next occurrence. Returns the armed trigger time, or null if nothing could
     *  be armed (no exact-alarm permission, or a sun-based alarm with no cached location). */
    fun scheduleNext(ctx: Context, alarm: Alarm): Long? {
        val triggerAt = nextTriggerMillis(ctx, alarm) ?: return null
        return if (setExact(ctx, alarm.id, triggerAt)) triggerAt else null
    }

    fun scheduleSnooze(ctx: Context, alarm: Alarm): Long? {
        val triggerAt = System.currentTimeMillis() + alarm.snoozeMinutes.coerceAtLeast(1) * 60_000L
        if (!setExact(ctx, alarm.id, triggerAt)) return null
        AlarmPreferences.saveActiveSnooze(ctx, alarm.id, triggerAt)
        runCatching { AlarmUpcomingNotifier.refresh(ctx) }
        return triggerAt
    }

    private fun setExact(ctx: Context, alarmId: Int, triggerAt: Long): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!canScheduleExact(am)) return false
        val fireIntent = firePendingIntent(ctx, alarmId)
        // showIntent: what the system launches when the user taps the status-bar alarm icon /
        // the "upcoming alarm" affordance on the lockscreen — straight into our Alarms settings.
        val showIntent = PendingIntent.getActivity(
            ctx, alarmRequestCode(alarmId) + 100_000,
            Intent(ctx, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_OPEN_ALARMS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), fireIntent)
            true
        }.getOrDefault(false)
    }

    fun cancel(ctx: Context, alarmId: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(ctx, alarmId))
        if (AlarmPreferences.loadActiveSnooze(ctx) == alarmId) AlarmPreferences.saveActiveSnooze(ctx, null)
    }

    private fun firePendingIntent(ctx: Context, alarmId: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, alarmRequestCode(alarmId),
        Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun alarmRequestCode(alarmId: Int) = 42_000 + alarmId

    fun canScheduleExact(ctx: Context): Boolean = canScheduleExact(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private fun canScheduleExact(am: AlarmManager): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 || runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)

    // ---- Truth from the OS -----------------------------------------------------------------

    /** The next alarm-clock the SYSTEM has registered for this app (via getNextAlarmClock), or
     *  null. This is the source of truth the Settings screen shows as "armed" — never our own
     *  bookkeeping — so the UI can't claim an alarm is set when AlarmManager disagrees. */
    fun systemArmedNext(ctx: Context): Long? {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val info = runCatching { am.nextAlarmClock }.getOrNull() ?: return null
        val creator = runCatching { info.showIntent?.creatorPackage }.getOrNull()
        return if (creator == ctx.packageName) info.triggerTime else null
    }

    /** Effective next fire for one alarm as the app expects it (snooze-aware). */
    fun effectiveNextMillis(ctx: Context, alarm: Alarm): Long? {
        if (!alarm.enabled) return null
        val now = System.currentTimeMillis()
        if (AlarmPreferences.loadActiveSnooze(ctx) == alarm.id) {
            val until = AlarmPreferences.loadSnoozeUntil(ctx)
            if (until > now) return until
        }
        return nextTriggerMillis(ctx, alarm)
    }

    /** Soonest effective next fire across all enabled alarms, with the alarm it belongs to. */
    fun soonest(ctx: Context, alarms: List<Alarm> = AlarmPreferences.loadAlarms(ctx)): Pair<Alarm, Long>? =
        alarms.mapNotNull { a -> effectiveNextMillis(ctx, a)?.let { a to it } }.minByOrNull { it.second }

    // ---- Formatting helpers (shared by Settings UI + upcoming notification) ----------------

    fun formatWhen(triggerAt: Long, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = triggerAt }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val hm = String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val sameDay = c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val isTomorrow = c.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR)
        val day = when {
            sameDay -> "Today"
            isTomorrow -> "Tomorrow"
            triggerAt - now < 6L * 86_400_000L -> DAY_NAMES[c.get(Calendar.DAY_OF_WEEK) - 1]
            else -> "${DAY_NAMES[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1}"
        }
        return "$day $hm"
    }

    fun formatCountdown(triggerAt: Long, now: Long = System.currentTimeMillis()): String {
        val ms = (triggerAt - now).coerceAtLeast(0L)
        val totalMin = (ms + 59_999L) / 60_000L
        val d = totalMin / (24 * 60); val h = (totalMin % (24 * 60)) / 60; val m = totalMin % 60
        return when {
            d > 0 -> "in ${d}d ${h}h"
            h > 0 -> "in ${h}h ${m}m"
            m > 0 -> "in ${m}m"
            else -> "now"
        }
    }

    private val DAY_NAMES = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    const val EXTRA_OPEN_ALARMS = "open_alarms"

    /** Next trigger time strictly after now, honoring repeatDays for every mode (a one-shot
     *  clock-time alarm with an empty repeat set just returns tomorrow's occurrence if today's
     *  has already passed — standard "it already rang, wait for tomorrow" alarm-clock behavior). */
    fun nextTriggerMillis(ctx: Context, alarm: Alarm): Long? = when (alarm.mode) {
        AlarmTriggerMode.CLOCK_TIME -> nextClockTime(alarm.hour, alarm.minute, alarm.repeatDays)
        else -> nextSunEvent(ctx, alarm.mode, alarm.repeatDays)
    }

    private fun nextClockTime(hour: Int, minute: Int, repeatDays: Set<Int>): Long {
        val now = System.currentTimeMillis()
        val base = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (repeatDays.isEmpty()) {
            if (base.timeInMillis <= now) base.add(Calendar.DAY_OF_YEAR, 1)
            return base.timeInMillis
        }
        for (offset in 0..7) {
            val c = base.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, offset)
            if (c.get(Calendar.DAY_OF_WEEK) in repeatDays && c.timeInMillis > now) return c.timeInMillis
        }
        return base.timeInMillis + 7L * 24 * 3_600_000L
    }

    private fun nextSunEvent(ctx: Context, mode: AlarmTriggerMode, repeatDays: Set<Int>): Long? {
        val (lat, lon) = AlarmPreferences.loadCachedLocation(ctx) ?: return null
        val now = System.currentTimeMillis()
        for (offset in 0..8) {
            val dayStart = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (repeatDays.isNotEmpty() && dayStart.get(Calendar.DAY_OF_WEEK) !in repeatDays) continue
            val (sunrise, sunset) = SunCalc.sunriseSunset(dayStart, lat, lon) ?: continue
            val candidates = when (mode) {
                AlarmTriggerMode.SUNRISE -> listOf(sunrise)
                AlarmTriggerMode.SUNSET -> listOf(sunset)
                AlarmTriggerMode.SUNRISE_OR_SUNSET -> listOf(sunrise, sunset)
                AlarmTriggerMode.CLOCK_TIME -> emptyList()
            }.filter { it > now }.sorted()
            if (candidates.isNotEmpty()) return candidates.first()
        }
        return null
    }
}
