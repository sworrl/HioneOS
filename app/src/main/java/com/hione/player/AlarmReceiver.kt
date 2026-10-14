package com.hione.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * CPU wake lock bridging the gap between AlarmManager's broadcast and [[AlarmRingService]]
 * actually holding its own locks. AlarmManager only guarantees the device stays awake for the
 * duration of `onReceive()`; on a dozing device the foreground-service start that follows is
 * asynchronous, so without this the CPU could legally drop back to suspend before
 * `onStartCommand` runs (the classic WakefulBroadcastReceiver problem). Timed (90 s) so a crash
 * anywhere in between can never pin the CPU forever; the service releases it explicitly the
 * moment it holds its own per-ring locks.
 */
object AlarmWakeHandoff {
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(ctx: Context) {
        release()
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hione:alarm:handoff").apply {
                setReferenceCounted(false)
                acquire(90_000L)
            }
        }
    }

    @Synchronized
    fun release() {
        runCatching { lock?.let { if (it.isHeld) it.release() } }
        lock = null
    }
}

/** Fired by AlarmManager (see [[AlarmScheduler]]) at the exact trigger instant. Does the minimum
 *  possible here — grab a CPU lock, hand off to the foreground [[AlarmRingService]] — since a
 *  BroadcastReceiver only gets a few seconds before the OS may kill it. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
                if (alarmId < 0) return
                AlarmWakeHandoff.acquire(context)
                val svc = Intent(context, AlarmRingService::class.java)
                    .setAction(AlarmRingService.ACTION_RING)
                    .putExtra(EXTRA_ALARM_ID, alarmId)
                runCatching { ContextCompat.startForegroundService(context, svc) }
                    .onFailure { AlarmWakeHandoff.release() }
            }
            ACTION_REFRESH_UPCOMING -> runCatching { AlarmUpcomingNotifier.refresh(context) }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.hione.player.alarm.FIRE"
        /** Inexact, Doze-tolerant nudge from [[AlarmUpcomingNotifier]] to re-evaluate the
         *  "alarm within 24 h" notification when the next fire crosses into that window. */
        const val ACTION_REFRESH_UPCOMING = "com.hione.player.alarm.REFRESH_UPCOMING"
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}

/** Exact alarms don't survive a reboot — this re-arms every enabled alarm's next occurrence the
 *  moment the device finishes booting. Also re-arms after a time-zone / wall-clock change (a
 *  clock-time alarm's absolute trigger instant moves with the zone) and after the app itself is
 *  updated, all of which are idempotent through [[AlarmScheduler.rescheduleAll]]. */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Reschedule off the main thread but inside goAsync() so the process is kept alive
                // until every setAlarmClock() call has landed (BOOT_COMPLETED is delivered to a
                // cold, "stopped" process — nothing else keeps it around).
                val pending = goAsync()
                Thread {
                    try { AlarmScheduler.rescheduleAll(context) } finally { pending.finish() }
                }.start()
            }
        }
    }
}
