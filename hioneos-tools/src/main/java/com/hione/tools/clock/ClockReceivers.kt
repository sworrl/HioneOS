package com.hione.tools.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * AlarmManager keeps the CPU awake only for the length of onReceive(). Starting the ring
 * service is asynchronous, so without a lock of our own a dozing device can legally suspend
 * again before the service runs. Timed so a crash in between can never pin the CPU awake.
 */
object WakeHandoff {
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(ctx: Context) {
        release()
        runCatching {
            lock = ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hione:clock:handoff").apply {
                    setReferenceCounted(false); acquire(60_000L)
                }
        }
    }

    @Synchronized
    fun release() {
        runCatching { lock?.let { if (it.isHeld) it.release() } }
        lock = null
    }
}

/** Internal broadcasts: AlarmManager fires and notification buttons. Not exported. */
class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val id = intent.getIntExtra(EXTRA_ID, -1)
        when (intent.action) {
            ACTION_ALARM_FIRE -> {
                // Start the foreground service right here, inside onReceive: the exemption that
                // allows a background foreground-service start comes from this alarm broadcast
                // and only lasts while it is being delivered.
                WakeHandoff.acquire(ctx)
                RingService.startRing(ctx, RingService.ACTION_RING_ALARM, id)
                return
            }
            ACTION_TIMER_EXPIRED -> {
                val t = ClockStore.timer(ctx, id)
                if (t == null || t.state != TimerState.RUNNING) return
                val left = t.endElapsed - SystemClock.elapsedRealtime()
                if (left > 1_500) { TimerScheduler.sync(ctx); return } // extended since it was armed
                ClockStore.updateTimer(ctx, id) { it.copy(state = TimerState.EXPIRED) }
                WakeHandoff.acquire(ctx)
                RingService.startRing(ctx, RingService.ACTION_RING_TIMER, id)
                TimerScheduler.sync(ctx)
                return
            }
        }
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    ACTION_ALARM_UPCOMING -> AlarmScheduler.sync(ctx)
                    ACTION_ALARM_DISMISS_EARLY -> AlarmScheduler.dismissEarly(ctx, id)
                    ACTION_TIMER_START -> TimerScheduler.start(ctx, id)
                    ACTION_TIMER_PAUSE -> TimerScheduler.pause(ctx, id)
                    ACTION_TIMER_RESET -> TimerScheduler.reset(ctx, id)
                    ACTION_TIMER_ADD_MINUTE -> TimerScheduler.addMinute(ctx, id)
                    ACTION_SW_PAUSE -> {
                        val sw = ClockStore.stopwatch(ctx)
                        if (sw.running) ClockStore.saveStopwatch(ctx, sw.copy(running = false, accumulatedMs = sw.totalNow()))
                        StopwatchNotifier.sync(ctx)
                    }
                }
            } catch (t: Throwable) {
                Log.w("HioneClock", "receiver ${intent.action} failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val ACTION_ALARM_FIRE = "com.hione.tools.clock.ALARM_FIRE"
        const val ACTION_ALARM_UPCOMING = "com.hione.tools.clock.ALARM_UPCOMING"
        const val ACTION_ALARM_DISMISS_EARLY = "com.hione.tools.clock.ALARM_DISMISS_EARLY"
        const val ACTION_TIMER_EXPIRED = "com.hione.tools.clock.TIMER_EXPIRED"
        const val ACTION_TIMER_START = "com.hione.tools.clock.TIMER_START"
        const val ACTION_TIMER_PAUSE = "com.hione.tools.clock.TIMER_PAUSE"
        const val ACTION_TIMER_RESET = "com.hione.tools.clock.TIMER_RESET"
        const val ACTION_TIMER_ADD_MINUTE = "com.hione.tools.clock.TIMER_ADD_MINUTE"
        const val ACTION_SW_PAUSE = "com.hione.tools.clock.SW_PAUSE"
    }
}

/**
 * Exact alarms do not survive a reboot, and clock-time alarms are armed as absolute instants
 * that a time or zone change moves. Re-arm everything on each of those. LOCKED_BOOT_COMPLETED
 * arrives before the first unlock; the store lives in device-protected storage so this works
 * then, and BOOT_COMPLETED repeats it harmlessly afterwards.
 */
class ClockInitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val action = intent.action ?: return
        val pending = goAsync()
        Thread {
            try {
                when (action) {
                    Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED -> {
                        TimerScheduler.fixAfterBoot(ctx)
                        AlarmScheduler.checkMissed(ctx)
                        // elapsedRealtime restarted, so a running stopwatch's start point is
                        // meaningless now. Keep what it had banked and leave it paused.
                        val sw = ClockStore.stopwatch(ctx)
                        if (sw.running) {
                            ClockStore.saveStopwatch(ctx, sw.copy(running = false, startElapsed = 0))
                        }
                    }
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                        TimerScheduler.fixAfterTimeChange(ctx)
                        AlarmScheduler.checkMissed(ctx)
                    }
                }
                AlarmScheduler.sync(ctx)
                TimerScheduler.sync(ctx)
                StopwatchNotifier.sync(ctx)
                // A timer that ran out while the device was off rings now.
                val nowE = SystemClock.elapsedRealtime()
                ClockStore.timers(ctx).filter { it.state == TimerState.RUNNING && it.endElapsed <= nowE }.forEach { t ->
                    ClockStore.updateTimer(ctx, t.id) { it.copy(state = TimerState.EXPIRED) }
                    RingService.startRing(ctx, RingService.ACTION_RING_TIMER, t.id)
                }
            } catch (t: Throwable) {
                Log.w("HioneClock", "init $action failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
