package com.hione.launcher.lockscreen

import android.content.Context

/**
 * User-tunable settings store for the HioneOS **system** lockscreen, persisted to the
 * SharedPreferences file `"hione_lockscreen_prefs"`.
 *
 * Every value below is deliberately exposed as a runtime-settable getter/setter so a Settings
 * screen (or a shell `settings`/`am` poke) can retune the lockscreen live without a rebuild.
 * All setters persist **immediately** via `apply()`.
 *
 * Brightness lifecycle state machine — run by [HioneLockscreenActivity] on every wake and again
 * after any user interaction:
 * ```
 *   Full phase -> hold the window at FULL brightness (1.0) for  fullBrightMillis   (T1)
 *   Fade phase -> smoothly fade brightness 1.0 -> halfBrightnessFraction over  fadeToHalfMillis (T2)
 *   Off  phase -> if thenScreenOff, turn the screen off
 * ```
 * Any tap / drag / wake resets the machine back to the Full phase.
 */
object HioneLockscreenPrefs {

    /** Backing SharedPreferences file name (shared with [HioneLockscreenManager]). */
    const val PREFS = "hione_lockscreen_prefs"

    // ---- Preference keys ----
    private const val KEY_FULL_BRIGHT_MILLIS = "full_bright_millis"
    private const val KEY_FADE_TO_HALF_MILLIS = "fade_to_half_millis"
    private const val KEY_HALF_BRIGHTNESS_FRACTION = "half_brightness_fraction"
    private const val KEY_THEN_SCREEN_OFF = "then_screen_off"
    private const val KEY_POWER_DOUBLE_PRESS_ACTION = "power_double_press_action"
    private const val KEY_ENABLED = "enabled"

    // ---- Defaults (user's exact spec — differs from the app source's 7s/7s) ----
    /** T1 default: 15s at full brightness after a wake/lock. */
    const val DEFAULT_FULL_BRIGHT_MILLIS = 15_000L
    /** T2 default: 10s to fade from full down to [halfBrightnessFraction]. */
    const val DEFAULT_FADE_TO_HALF_MILLIS = 10_000L
    /** The "half" brightness target the fade lands on. */
    const val DEFAULT_HALF_BRIGHTNESS_FRACTION = 0.5f
    /** After the fade completes, turn the screen off. */
    const val DEFAULT_THEN_SCREEN_OFF = true
    /** Double power-press target: "camera" | "music" | "radio". */
    const val DEFAULT_POWER_DOUBLE_PRESS_ACTION = "camera"
    /** Master enable for the whole Hione lockscreen brightness lifecycle. */
    const val DEFAULT_ENABLED = true

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // T1 — user-tunable: hold the window at FULL brightness this long (ms)
    //      after a wake/lock before the fade begins.
    // ------------------------------------------------------------------
    fun getFullBrightMillis(context: Context): Long =
        prefs(context).getLong(KEY_FULL_BRIGHT_MILLIS, DEFAULT_FULL_BRIGHT_MILLIS)
            .coerceIn(0L, 600_000L)

    fun setFullBrightMillis(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_FULL_BRIGHT_MILLIS, value.coerceIn(0L, 600_000L)).apply()

    // ------------------------------------------------------------------
    // T2 — user-tunable: fade the window brightness down to the "half"
    //      target over this duration (ms).
    // ------------------------------------------------------------------
    fun getFadeToHalfMillis(context: Context): Long =
        prefs(context).getLong(KEY_FADE_TO_HALF_MILLIS, DEFAULT_FADE_TO_HALF_MILLIS)
            .coerceIn(0L, 600_000L)

    fun setFadeToHalfMillis(context: Context, value: Long) =
        prefs(context).edit().putLong(KEY_FADE_TO_HALF_MILLIS, value.coerceIn(0L, 600_000L)).apply()

    // ------------------------------------------------------------------
    // user-tunable: the "half" brightness target as a 0..1 window-brightness
    //      fraction that the fade lands on.
    // ------------------------------------------------------------------
    fun getHalfBrightnessFraction(context: Context): Float =
        prefs(context).getFloat(KEY_HALF_BRIGHTNESS_FRACTION, DEFAULT_HALF_BRIGHTNESS_FRACTION)
            .coerceIn(0.01f, 1.0f)

    fun setHalfBrightnessFraction(context: Context, value: Float) =
        prefs(context).edit()
            .putFloat(KEY_HALF_BRIGHTNESS_FRACTION, value.coerceIn(0.01f, 1.0f)).apply()

    // ------------------------------------------------------------------
    // user-tunable: after the fade completes, turn the screen off.
    // ------------------------------------------------------------------
    fun getThenScreenOff(context: Context): Boolean =
        prefs(context).getBoolean(KEY_THEN_SCREEN_OFF, DEFAULT_THEN_SCREEN_OFF)

    fun setThenScreenOff(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_THEN_SCREEN_OFF, value).apply()

    // ------------------------------------------------------------------
    // user-tunable: what a double power-press launches
    //      ("camera" | "music" | "radio"). Carried over from the app source.
    // ------------------------------------------------------------------
    fun getPowerDoublePressAction(context: Context): String =
        prefs(context).getString(KEY_POWER_DOUBLE_PRESS_ACTION, DEFAULT_POWER_DOUBLE_PRESS_ACTION)
            ?: DEFAULT_POWER_DOUBLE_PRESS_ACTION

    fun setPowerDoublePressAction(context: Context, value: String) =
        prefs(context).edit().putString(KEY_POWER_DOUBLE_PRESS_ACTION, value).apply()

    // ------------------------------------------------------------------
    // AOD (Always-On Display) — opt-in. "off" | "charging" | "always".
    // "charging" keeps the panel-on cost off the battery; "always" is the
    // full experience. Content toggles pick what the AOD face shows.
    // ------------------------------------------------------------------
    const val AOD_OFF = "off"
    const val AOD_CHARGING = "charging"
    const val AOD_ALWAYS = "always"

    fun getAodMode(context: Context): String =
        prefs(context).getString("aod_mode", AOD_OFF) ?: AOD_OFF

    fun setAodMode(context: Context, value: String) =
        prefs(context).edit().putString("aod_mode", value).apply()

    fun getAodShowWeather(context: Context): Boolean =
        prefs(context).getBoolean("aod_show_weather", true)

    fun setAodShowWeather(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("aod_show_weather", value).apply()

    fun getAodShowNowPlaying(context: Context): Boolean =
        prefs(context).getBoolean("aod_show_now_playing", true)

    fun setAodShowNowPlaying(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("aod_show_now_playing", value).apply()

    /** Whether AOD should engage right now given the mode + charge state. */
    fun aodShouldEngage(context: Context): Boolean = when (getAodMode(context)) {
        AOD_ALWAYS -> true
        AOD_CHARGING -> try {
            val bi = context.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            (bi?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        } catch (_: Throwable) { false }
        else -> false
    }

    // ------------------------------------------------------------------
    // user-tunable: master enable for the whole Hione lockscreen brightness
    //      lifecycle. Carried over from the app source.
    // ------------------------------------------------------------------
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
}
