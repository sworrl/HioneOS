package com.hione.tools.clock

import android.app.Activity
import android.app.VoiceInteractor
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.widget.Toast

/**
 * The public AlarmClock API (android.provider.AlarmClock), the same actions stock DeskClock
 * handled: SET_ALARM, SHOW_ALARMS, DISMISS_ALARM, SNOOZE_ALARM, SET_TIMER, SHOW_TIMERS,
 * DISMISS_TIMER. Assistants, other apps and the system clock in the status bar use these.
 *
 * Runs with Theme.NoDisplay, so everything happens in onCreate and the activity finishes
 * before it would ever draw. Results go back to a voice assistant through VoiceInteractor
 * when the request came from one.
 */
class HandleApiCalls : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        // Arriving on the activity's own (unprotected) component means the caller was not
        // checked for SET_ALARM; only the screen-opening actions are honored that way. The
        // aliases carry the permission and report their own component name.
        val viaOpenEntry = i?.component?.className == HandleApiCalls::class.java.name
        if (viaOpenEntry && i?.action !in setOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SHOW_TIMERS)) {
            Log.w("HioneClock", "ignoring ${i?.action} sent to the unprotected entry")
            finish(); return
        }
        try {
            when (i?.action) {
                AlarmClock.ACTION_SET_ALARM -> setAlarm(i)
                AlarmClock.ACTION_SHOW_ALARMS -> openClock(ClockActivity.TAB_ALARMS)
                AlarmClock.ACTION_DISMISS_ALARM -> dismissAlarm(i)
                AlarmClock.ACTION_SNOOZE_ALARM -> snoozeAlarm(i)
                AlarmClock.ACTION_SET_TIMER -> setTimer(i)
                AlarmClock.ACTION_SHOW_TIMERS -> openClock(ClockActivity.TAB_TIMER)
                AlarmClock.ACTION_DISMISS_TIMER -> dismissTimer()
            }
        } catch (t: Throwable) {
            Log.w("HioneClock", "API call ${i?.action} failed", t)
            voiceDone(false, "Sorry, that did not work.")
        } finally {
            finish()
        }
    }

    private fun openClock(tab: Int, extras: Intent.() -> Unit = {}) {
        startActivity(Intent(this, ClockActivity::class.java).putExtra(ClockActivity.EXTRA_TAB, tab).apply(extras)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun say(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    private fun voiceDone(ok: Boolean, text: String) {
        if (!isVoiceInteraction) return
        val prompt = VoiceInteractor.Prompt(text)
        runCatching {
            voiceInteractor.submitRequest(
                if (ok) VoiceInteractor.CompleteVoiceRequest(prompt, null) else VoiceInteractor.AbortVoiceRequest(prompt, null)
            )
        }
    }

    // ------------------------------------------------------------------ alarms

    private fun setAlarm(i: Intent) {
        val ctx: Context = this
        // No hour: the caller wants the alarm UI to make a new alarm, not a silent one.
        if (!i.hasExtra(AlarmClock.EXTRA_HOUR)) {
            openClock(ClockActivity.TAB_ALARMS) { putExtra(ClockActivity.EXTRA_NEW_ALARM, true) }
            setResult(RESULT_OK)
            return
        }
        var hour = i.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
        val minute = i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)
        if (i.hasExtra(AlarmClock.EXTRA_IS_PM)) {
            val pm = i.getBooleanExtra(AlarmClock.EXTRA_IS_PM, false)
            if (pm && hour in 1..11) hour += 12
            if (!pm && hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) {
            say("That is not a valid time")
            voiceDone(false, "That is not a valid time.")
            setResult(RESULT_CANCELED)
            return
        }
        val label = i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty()
        val days = (i.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS) ?: arrayListOf()).filter { it in 1..7 }.toSet()
        val skipUi = i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)
        val ringtone: String? = if (i.hasExtra(AlarmClock.EXTRA_RINGTONE)) {
            val r = i.getStringExtra(AlarmClock.EXTRA_RINGTONE)
            when {
                r == null || r == AlarmClock.VALUE_RINGTONE_SILENT -> ClockAlarm.RINGTONE_SILENT
                else -> r
            }
        } else null
        val vibrate = if (i.hasExtra(AlarmClock.EXTRA_VIBRATE)) i.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, true) else true

        // Same alarm already there (same time, days and label)? Turn that one on instead of
        // creating a duplicate, as stock DeskClock does.
        val existing = ClockStore.alarms(ctx).firstOrNull { it.hour == hour && it.minute == minute && it.days == days && it.label == label }
        val alarm = if (existing != null) {
            val upd = existing.copy(
                enabled = true, snoozedUntil = 0, skipUntil = 0,
                ringtone = if (i.hasExtra(AlarmClock.EXTRA_RINGTONE)) ringtone else existing.ringtone,
                vibrate = if (i.hasExtra(AlarmClock.EXTRA_VIBRATE)) vibrate else existing.vibrate
            )
            ClockStore.upsertAlarm(ctx, upd); upd
        } else {
            val a = ClockAlarm(
                id = ClockStore.newAlarmId(ctx), hour = hour, minute = minute, days = days, label = label,
                ringtone = ringtone, vibrate = vibrate,
                deleteAfterUse = skipUi && days.isEmpty()
            )
            ClockStore.upsertAlarm(ctx, a); a
        }
        AlarmScheduler.sync(ctx)
        val next = AlarmMath.nextFire(alarm)
        val msg = "Alarm set for " + (next?.let { ClockFormat.whenText(ctx, it) } ?: ClockFormat.timeFull(ctx, hour, minute))
        if (skipUi) say(msg) else openClock(ClockActivity.TAB_ALARMS) { putExtra(ClockActivity.EXTRA_ALARM_ID, alarm.id) }
        voiceDone(true, msg)
        setResult(RESULT_OK)
    }

    /** Which alarms a DISMISS_ALARM / SNOOZE_ALARM request means. */
    private fun matchAlarms(i: Intent, pool: List<ClockAlarm>): List<ClockAlarm> {
        return when (i.getStringExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE)) {
            AlarmClock.ALARM_SEARCH_MODE_ALL -> pool
            AlarmClock.ALARM_SEARCH_MODE_NEXT -> listOfNotNull(AlarmMath.soonest(pool)?.first)
            AlarmClock.ALARM_SEARCH_MODE_LABEL -> {
                val l = i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty()
                pool.filter { it.label.equals(l, ignoreCase = true) }
            }
            AlarmClock.ALARM_SEARCH_MODE_TIME -> {
                var h = i.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
                val m = i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)
                val hasPm = i.hasExtra(AlarmClock.EXTRA_IS_PM)
                val pm = i.getBooleanExtra(AlarmClock.EXTRA_IS_PM, false)
                if (hasPm && pm && h in 1..11) h += 12
                if (hasPm && !pm && h == 12) h = 0
                // Without AM/PM, "7:00" matches both 7:00 and 19:00.
                pool.filter { it.minute == m && (it.hour == h || (!hasPm && it.hour == (h + 12) % 24)) }
            }
            else -> emptyList()
        }
    }

    private fun dismissAlarm(i: Intent) {
        val ctx: Context = this
        val ringing = RingService.state.value.alarms
        val mode = i.getStringExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE)
        if (mode == null) {
            // Plain "dismiss": whatever is ringing, else whatever is snoozed, else let the user pick.
            if (ringing.isNotEmpty()) {
                RingService.send(ctx, RingService.ACTION_DISMISS, -1)
                voiceDone(true, "Alarm dismissed")
                return
            }
            val now = System.currentTimeMillis()
            val snoozed = ClockStore.alarms(ctx).filter { it.snoozedUntil > now }
            if (snoozed.isNotEmpty()) {
                snoozed.forEach { AlarmScheduler.dismissEarly(ctx, it.id) }
                voiceDone(true, "Alarm dismissed")
                return
            }
            openClock(ClockActivity.TAB_ALARMS)
            voiceDone(true, "Here are your alarms")
            return
        }
        val enabled = ClockStore.alarms(ctx).filter { it.enabled }
        val hits = matchAlarms(i, enabled)
        if (hits.isEmpty()) {
            say("No matching alarm")
            voiceDone(false, "I could not find that alarm.")
            return
        }
        for (a in hits) {
            if (ringing.any { it.id == a.id }) RingService.send(ctx, RingService.ACTION_DISMISS, a.id)
            else AlarmScheduler.dismissEarly(ctx, a.id)
        }
        val msg = if (hits.size == 1) "Alarm dismissed" else "${hits.size} alarms dismissed"
        say(msg)
        voiceDone(true, msg)
    }

    private fun snoozeAlarm(i: Intent) {
        val ctx: Context = this
        val ringing = RingService.state.value.alarms
        if (ringing.isEmpty()) {
            say("No alarm is ringing")
            voiceDone(false, "No alarm is ringing.")
            return
        }
        val minutes = i.getIntExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, -1)
        if (minutes in 1..60) {
            // A one-off snooze length: store it on the alarm before snoozing so the service
            // uses it. The alarm keeps that length for next time, which is also what the user
            // most likely wants after asking for it.
            ringing.forEach { a -> ClockStore.updateAlarm(ctx, a.id) { it.copy(snoozeMinutes = minutes) } }
        }
        RingService.send(ctx, RingService.ACTION_SNOOZE, -1)
        voiceDone(true, "Snoozed")
    }

    // ------------------------------------------------------------------ timers

    private fun setTimer(i: Intent) {
        val ctx: Context = this
        if (!i.hasExtra(AlarmClock.EXTRA_LENGTH)) {
            openClock(ClockActivity.TAB_TIMER)
            setResult(RESULT_OK)
            return
        }
        val seconds = i.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
        if (seconds !in 1..86_400) {
            say("Timers can be 1 second to 24 hours")
            voiceDone(false, "Timers can be 1 second to 24 hours.")
            setResult(RESULT_CANCELED)
            return
        }
        val label = i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty()
        val skipUi = i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)
        val len = seconds * 1000L
        // Reuse an idle timer of the same length and label instead of piling up copies.
        val reuse = ClockStore.timers(ctx).firstOrNull { it.state == TimerState.RESET && it.lengthMs == len && it.label == label }
        val t = reuse ?: ClockStore.addTimer(ctx, len, label, deleteAfterUse = skipUi)
        TimerScheduler.start(ctx, t.id)
        val msg = "Timer set for " + ClockFormat.duration(len)
        if (skipUi) say(msg) else openClock(ClockActivity.TAB_TIMER)
        voiceDone(true, msg)
        setResult(RESULT_OK)
    }

    private fun dismissTimer() {
        val ctx: Context = this
        val expired = ClockStore.timers(ctx).filter { it.state == TimerState.EXPIRED }
        if (expired.isEmpty()) {
            openClock(ClockActivity.TAB_TIMER)
            voiceDone(false, "No timer is ringing.")
            return
        }
        expired.forEach { TimerScheduler.reset(ctx, it.id) }
        voiceDone(true, "Timer stopped")
    }
}
