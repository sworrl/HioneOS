package com.hione.tools.clock

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** What an alarm plays. The two MUSIC modes hand off to Hione Music (see [HioneMusicBridge]) and
 *  fall back to the alarm tone if nothing starts playing, so an alarm is never silent. */
enum class WakeSound { TONE, MUSIC_SHUFFLE, MUSIC_RESUME }

/**
 * One alarm. `days` holds java.util.Calendar DAY_OF_WEEK values (1 = Sunday .. 7 = Saturday),
 * which is also what AlarmClock.EXTRA_DAYS uses, so intents map across without translation.
 * Empty `days` means a one-shot alarm that turns itself off after it rings.
 *
 * `ringtone`: null = the system's default alarm sound, [RINGTONE_SILENT], [RINGTONE_CHIME], or
 * any content URI string.
 */
data class ClockAlarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val label: String = "",
    val enabled: Boolean = true,
    val ringtone: String? = null,
    val vibrate: Boolean = true,
    val gradualSeconds: Int = 30,
    val snoozeMinutes: Int = 10,
    val sound: WakeSound = WakeSound.TONE,
    /** Wall-clock instant a pending snooze fires, 0 when not snoozed. */
    val snoozedUntil: Long = 0,
    /** Repeating alarms only: occurrences at or before this instant are skipped. */
    val skipUntil: Long = 0,
    /** The instant last handed to AlarmManager, used after a reboot to spot missed alarms. */
    val armedFor: Long = 0,
    /** Set for alarms created by SET_ALARM with SKIP_UI and no repeat: they are removed once
     *  done, like stock DeskClock does, so voice-assistant alarms do not pile up in the list. */
    val deleteAfterUse: Boolean = false,
) {
    val repeating: Boolean get() = days.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("h", hour); put("m", minute); put("days", JSONArray(days.toList()))
        put("label", label); put("on", enabled); put("tone", ringtone ?: JSONObject.NULL)
        put("vib", vibrate); put("grad", gradualSeconds); put("snz", snoozeMinutes)
        put("sound", sound.name); put("snoozedUntil", snoozedUntil); put("skipUntil", skipUntil)
        put("armedFor", armedFor); put("dau", deleteAfterUse)
    }

    companion object {
        const val RINGTONE_SILENT = "silent"
        const val RINGTONE_CHIME = "hione:chime"

        fun fromJson(o: JSONObject): ClockAlarm {
            val d = o.optJSONArray("days")
            val days = if (d == null) emptySet() else (0 until d.length()).map { d.getInt(it) }.filter { it in 1..7 }.toSet()
            return ClockAlarm(
                id = o.getInt("id"), hour = o.optInt("h", 7), minute = o.optInt("m", 0), days = days,
                label = o.optString("label", ""), enabled = o.optBoolean("on", true),
                ringtone = if (o.isNull("tone")) null else o.optString("tone").ifBlank { null },
                vibrate = o.optBoolean("vib", true), gradualSeconds = o.optInt("grad", 30),
                snoozeMinutes = o.optInt("snz", 10),
                sound = runCatching { WakeSound.valueOf(o.optString("sound", "TONE")) }.getOrDefault(WakeSound.TONE),
                snoozedUntil = o.optLong("snoozedUntil", 0), skipUntil = o.optLong("skipUntil", 0),
                armedFor = o.optLong("armedFor", 0), deleteAfterUse = o.optBoolean("dau", false)
            )
        }
    }
}

enum class TimerState { RESET, RUNNING, PAUSED, EXPIRED }

/**
 * A countdown. While running, the end is kept both as elapsedRealtime (immune to the user or
 * network changing the wall clock) and as wall time (survives a reboot, which resets
 * elapsedRealtime to zero). [ClockInitReceiver] rebuilds one from the other.
 */
data class ClockTimer(
    val id: Int,
    val lengthMs: Long,
    val label: String = "",
    val state: TimerState = TimerState.RESET,
    /** Remaining when PAUSED or RESET (equals lengthMs when RESET). */
    val remainingMs: Long = lengthMs,
    val endElapsed: Long = 0,
    val endWall: Long = 0,
    val deleteAfterUse: Boolean = false,
) {
    fun remainingNow(nowElapsed: Long = SystemClock.elapsedRealtime()): Long = when (state) {
        TimerState.RUNNING -> endElapsed - nowElapsed
        TimerState.EXPIRED -> endElapsed - nowElapsed // negative: overtime
        else -> remainingMs
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("len", lengthMs); put("label", label); put("state", state.name)
        put("rem", remainingMs); put("endE", endElapsed); put("endW", endWall); put("dau", deleteAfterUse)
    }

    companion object {
        fun fromJson(o: JSONObject) = ClockTimer(
            id = o.getInt("id"), lengthMs = o.getLong("len"), label = o.optString("label", ""),
            state = runCatching { TimerState.valueOf(o.optString("state")) }.getOrDefault(TimerState.RESET),
            remainingMs = o.optLong("rem", o.getLong("len")), endElapsed = o.optLong("endE", 0),
            endWall = o.optLong("endW", 0), deleteAfterUse = o.optBoolean("dau", false)
        )
    }
}

data class Stopwatch(val running: Boolean, val startElapsed: Long, val accumulatedMs: Long, val laps: List<Long>) {
    fun totalNow(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
        accumulatedMs + if (running) nowElapsed - startElapsed else 0
}

/**
 * All Clock state, in device-protected storage so alarms can be read and re-armed during
 * LOCKED_BOOT_COMPLETED, before the user unlocks after a reboot (an encrypted-storage alarm
 * would sit unarmed until the first unlock, and a reboot at 3 am would eat the 7 am alarm).
 *
 * Writes go through @Synchronized read-modify-write helpers that always start from what is on
 * disk, never from a snapshot a UI is holding: the ring service flips alarms off after they
 * fire while the Clock UI may be open, and a whole-list overwrite from the UI would undo it.
 */
object ClockStore {
    private const val PREFS = "clock"

    fun prefs(ctx: Context): SharedPreferences {
        val dp = ctx.applicationContext.createDeviceProtectedStorageContext()
        return dp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun bump(e: SharedPreferences.Editor, p: SharedPreferences) = e.putLong("rev", p.getLong("rev", 0) + 1)

    // ---------------------------------------------------------------- alarms

    fun alarms(ctx: Context): List<ClockAlarm> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("alarms", "[]"))
        (0 until arr.length()).mapNotNull { runCatching { ClockAlarm.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun alarm(ctx: Context, id: Int) = alarms(ctx).firstOrNull { it.id == id }

    private fun saveAlarms(ctx: Context, list: List<ClockAlarm>) {
        val p = prefs(ctx)
        val arr = JSONArray()
        list.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id })).forEach { arr.put(it.toJson()) }
        p.edit().putString("alarms", arr.toString()).also { bump(it, p) }.commit()
    }

    @Synchronized
    fun updateAlarm(ctx: Context, id: Int, f: (ClockAlarm) -> ClockAlarm?) {
        saveAlarms(ctx, alarms(ctx).mapNotNull { if (it.id == id) f(it) else it })
    }

    @Synchronized
    fun upsertAlarm(ctx: Context, a: ClockAlarm) {
        val cur = alarms(ctx)
        saveAlarms(ctx, if (cur.any { it.id == a.id }) cur.map { if (it.id == a.id) a else it } else cur + a)
    }

    @Synchronized
    fun removeAlarm(ctx: Context, id: Int) = saveAlarms(ctx, alarms(ctx).filterNot { it.id == id })

    @Synchronized
    fun newAlarmId(ctx: Context): Int {
        val used = alarms(ctx).map { it.id }.toSet()
        var i = 1
        while (i in used) i++
        return i
    }

    // ---------------------------------------------------------------- timers

    fun timers(ctx: Context): List<ClockTimer> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("timers", "[]"))
        (0 until arr.length()).mapNotNull { runCatching { ClockTimer.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun timer(ctx: Context, id: Int) = timers(ctx).firstOrNull { it.id == id }

    private fun saveTimers(ctx: Context, list: List<ClockTimer>) {
        val p = prefs(ctx)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        p.edit().putString("timers", arr.toString()).also { bump(it, p) }.commit()
    }

    @Synchronized
    fun updateTimer(ctx: Context, id: Int, f: (ClockTimer) -> ClockTimer?) {
        saveTimers(ctx, timers(ctx).mapNotNull { if (it.id == id) f(it) else it })
    }

    @Synchronized
    fun updateTimers(ctx: Context, f: (ClockTimer) -> ClockTimer?) = saveTimers(ctx, timers(ctx).mapNotNull(f))

    @Synchronized
    fun addTimer(ctx: Context, lengthMs: Long, label: String, deleteAfterUse: Boolean = false): ClockTimer {
        val cur = timers(ctx)
        val used = cur.map { it.id }.toSet()
        var i = 1
        while (i in used) i++
        val t = ClockTimer(i, lengthMs, label, deleteAfterUse = deleteAfterUse)
        saveTimers(ctx, cur + t)
        return t
    }

    // ---------------------------------------------------------------- stopwatch

    fun stopwatch(ctx: Context): Stopwatch {
        val p = prefs(ctx)
        val laps = runCatching {
            val a = JSONArray(p.getString("swLaps", "[]")); (0 until a.length()).map { a.getLong(it) }
        }.getOrDefault(emptyList())
        return Stopwatch(p.getBoolean("swRun", false), p.getLong("swStart", 0), p.getLong("swAcc", 0), laps)
    }

    fun saveStopwatch(ctx: Context, s: Stopwatch) {
        val p = prefs(ctx)
        p.edit().putBoolean("swRun", s.running).putLong("swStart", s.startElapsed).putLong("swAcc", s.accumulatedMs)
            .putString("swLaps", JSONArray(s.laps).toString()).also { bump(it, p) }.apply()
    }

    // ---------------------------------------------------------------- settings + cities

    fun autoSilenceMinutes(ctx: Context) = prefs(ctx).getInt("autoSilence", 10)
    fun setAutoSilenceMinutes(ctx: Context, v: Int) = prefs(ctx).edit().putInt("autoSilence", v).apply()

    /** What the volume keys do while ringing: "snooze", "dismiss" or "none". */
    fun volumeKeyAction(ctx: Context) = prefs(ctx).getString("volKeys", "snooze") ?: "snooze"
    fun setVolumeKeyAction(ctx: Context, v: String) = prefs(ctx).edit().putString("volKeys", v).apply()

    fun timerRingtone(ctx: Context): String? = prefs(ctx).getString("timerTone", null)
    fun setTimerRingtone(ctx: Context, v: String?) = prefs(ctx).edit().putString("timerTone", v).apply()

    fun cities(ctx: Context): List<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString("cities", "[]")); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun saveCities(ctx: Context, ids: List<String>) {
        val p = prefs(ctx)
        p.edit().putString("cities", JSONArray(ids).toString()).also { bump(it, p) }.apply()
    }
}

/** Compose hook: a number that changes every time anything in [ClockStore] is written, so
 *  screens re-read after the ring service or a notification action changed something. */
@Composable
fun rememberClockRevision(): Long {
    val ctx = LocalContext.current
    var rev by remember { mutableLongStateOf(ClockStore.prefs(ctx).getLong("rev", 0)) }
    DisposableEffect(Unit) {
        val p = ClockStore.prefs(ctx)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ -> rev = sp.getLong("rev", 0) }
        p.registerOnSharedPreferenceChangeListener(l)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(l) }
    }
    return rev
}

/** Pure alarm-time math, kept separate so it can be reasoned about without Android state. */
object AlarmMath {
    /** Next time this alarm's clock time comes round, strictly after [after]. */
    fun nextOccurrence(a: ClockAlarm, after: Long): Long {
        val base = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (a.days.isEmpty()) {
            if (base.timeInMillis <= after) base.add(Calendar.DAY_OF_YEAR, 1)
            // Calendar.add keeps the wall-clock time across a DST change, which is what an
            // alarm clock wants: 7:00 stays 7:00.
            base.set(Calendar.HOUR_OF_DAY, a.hour); base.set(Calendar.MINUTE, a.minute)
            return base.timeInMillis
        }
        for (offset in 0..7) {
            val c = (base.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute)
            }
            if (c.get(Calendar.DAY_OF_WEEK) in a.days && c.timeInMillis > after) return c.timeInMillis
        }
        return base.timeInMillis + 7 * 86_400_000L
    }

    /** When this alarm will next actually ring (snooze and skip aware), or null when off. */
    fun nextFire(a: ClockAlarm, now: Long = System.currentTimeMillis()): Long? {
        if (!a.enabled) return null
        if (a.snoozedUntil > now) return a.snoozedUntil
        val after = if (a.repeating && a.skipUntil > now) a.skipUntil else now
        return nextOccurrence(a, after)
    }

    fun soonest(list: List<ClockAlarm>, now: Long = System.currentTimeMillis()): Pair<ClockAlarm, Long>? =
        list.mapNotNull { a -> nextFire(a, now)?.let { a to it } }.minByOrNull { it.second }
}
