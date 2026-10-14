package com.hione.player

import org.json.JSONArray
import org.json.JSONObject

/** How an alarm decides when to fire. CLOCK_TIME uses hour/minute directly; the SUN_* modes derive
 *  a fresh trigger time each day from [[SunCalc]] using the last cached coarse location. */
enum class AlarmTriggerMode { CLOCK_TIME, SUNRISE, SUNSET, SUNRISE_OR_SUNSET }

/** What plays when the alarm fires. `sourceRef` meaning per source:
 *  ARTIST/ALBUM = name, PLAYLIST = playlist name, TRACK = MediaStore track id (decimal string),
 *  everything else = unused. HIONE_CHIME is the built-in default — a synthesized Hione-style chime
 *  ([[HioneAlarmChime]]) that never depends on the library having been scanned. */
enum class AlarmSource { HIONE_CHIME, SHUFFLE_ALL, LIKED_SONGS, DAILY_MIX, ARTIST, ALBUM, PLAYLIST, TRACK }

/**
 * One user-configured alarm. `repeatDays` uses [java.util.Calendar] DAY_OF_WEEK values (1=Sunday
 * .. 7=Saturday) directly — no translation layer needed against Calendar-based scheduling math in
 * [[AlarmScheduler]]. An empty set means "one-shot, next matching time only," matching how every
 * mainstream alarm clock treats an unrepeated alarm (fires once, disables itself).
 *
 * `volumeCap` is the player-gain ceiling the fade-in climbs to (0.1..1.0 of the current
 * STREAM_MUSIC level) — it never changes the hardware/stream volume or the audio path, so the
 * alarm still plays bit-perfect through the normal player pipeline; it only scales the final gain.
 */
data class Alarm(
    val id: Int,
    val enabled: Boolean = true,
    val hour: Int = 7,
    val minute: Int = 0,
    val mode: AlarmTriggerMode = AlarmTriggerMode.CLOCK_TIME,
    val repeatDays: Set<Int> = emptySet(),
    val label: String = "Alarm",
    val fadeInSeconds: Int = 30,
    val snoozeMinutes: Int = 9,
    val source: AlarmSource = AlarmSource.HIONE_CHIME,
    val sourceRef: String? = null,
    val volumeCap: Float = 1f,
    val vibrate: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("enabled", enabled); put("hour", hour); put("minute", minute)
        put("mode", mode.name); put("repeatDays", JSONArray(repeatDays.toList()))
        put("label", label); put("fadeInSeconds", fadeInSeconds); put("snoozeMinutes", snoozeMinutes)
        put("source", source.name); put("sourceRef", sourceRef ?: JSONObject.NULL)
        put("volumeCap", volumeCap.toDouble()); put("vibrate", vibrate)
    }

    companion object {
        const val MIN_VOLUME_CAP = 0.1f

        fun fromJson(o: JSONObject): Alarm {
            val days = mutableSetOf<Int>()
            val arr = o.optJSONArray("repeatDays")
            if (arr != null) for (i in 0 until arr.length()) days.add(arr.getInt(i))
            return Alarm(
                id = o.getInt("id"),
                enabled = o.optBoolean("enabled", true),
                hour = o.optInt("hour", 7),
                minute = o.optInt("minute", 0),
                mode = runCatching { AlarmTriggerMode.valueOf(o.optString("mode", "CLOCK_TIME")) }.getOrDefault(AlarmTriggerMode.CLOCK_TIME),
                repeatDays = days,
                label = o.optString("label", "Alarm"),
                fadeInSeconds = o.optInt("fadeInSeconds", 30),
                snoozeMinutes = o.optInt("snoozeMinutes", 9),
                // Alarms saved before HIONE_CHIME existed default to SHUFFLE_ALL (their old default) —
                // never silently change what an existing alarm plays.
                source = runCatching { AlarmSource.valueOf(o.optString("source", "SHUFFLE_ALL")) }.getOrDefault(AlarmSource.SHUFFLE_ALL),
                sourceRef = o.optString("sourceRef", "").takeIf { it.isNotBlank() },
                volumeCap = o.optDouble("volumeCap", 1.0).toFloat().coerceIn(MIN_VOLUME_CAP, 1f),
                vibrate = o.optBoolean("vibrate", true)
            )
        }

        /** Parse a whole list; malformed entries are skipped rather than failing the whole list. */
        fun listFromJson(raw: String): List<Alarm> {
            val arr = JSONArray(raw)
            return (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
        }
    }
}
