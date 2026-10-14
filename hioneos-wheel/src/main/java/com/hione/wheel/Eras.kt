package com.hione.wheel

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/**
 * The four skins. Each is its own hidden secret in the BPM game (Settings.Global `hione_unlocks`,
 * written only by the launcher's HioneUnlocks). This app only ever READS that key.
 */
enum class Era(
    val id: String,
    val title: String,
    val blurb: String,
    val unlockKey: String,
    /** Shown while locked. Never names the reward. */
    val hint: String,
    /** Native screen size of the original, in pixels. */
    val w: Int,
    val h: Int,
) {
    MONO_2001(
        "2001", "${Mascot.name}Pod 2001", "Mono, 160 x 128. Where it started.",
        "secret.os.wheel.2001",
        "Play along to something from the year the wheel first turned.",
        160, 128
    ),
    COLOR_2004(
        "2004", "${Mascot.name}Pod 2004", "Color, 220 x 176. Album art arrives.",
        "secret.os.wheel.2004",
        "A whole album from 2004, beat by beat.",
        220, 176
    ),
    VIDEO_2005(
        "2005", "${Mascot.name}Pod 2005", "320 x 240, the big glossy one.",
        "secret.os.wheel.2005",
        "Five thousand taps. Keep your rhythm.",
        320, 240
    ),
    CLASSIC_2007(
        "2007", "${Mascot.name}Pod 2007", "Split menus and flipping covers.",
        "secret.os.wheel.2007",
        "Flip through seven years of music in one sitting.",
        320, 240
    );

    companion object {
        fun byId(id: String?): Era? = entries.firstOrNull { it.id == id || it.name.equals(id, true) }
    }
}

object Unlocks {
    const val SETTINGS_KEY = "hione_unlocks"

    /** Unlocked = an entry exists for the key and it is not switched off ({"off": true}). */
    fun unlocked(ctx: Context): Set<Era> {
        val raw = try {
            Settings.Global.getString(ctx.contentResolver, SETTINGS_KEY)
        } catch (_: Throwable) { null }
        if (raw.isNullOrBlank()) return emptySet()
        val obj = try { JSONObject(raw) } catch (_: Throwable) { return emptySet() }
        val out = HashSet<Era>()
        for (e in Era.entries) {
            if (!obj.has(e.unlockKey)) continue
            val entry = obj.optJSONObject(e.unlockKey)
            if (entry != null && entry.optBoolean("off", false)) continue
            out.add(e)
        }
        return out
    }
}

/** Per-device settings for the shell. Shuffle and repeat live in the player, not here. */
class WheelPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("hionepod", Context.MODE_PRIVATE)

    var lastEra: String?
        get() = p.getString("last_era", null)
        set(v) { p.edit().putString("last_era", v).apply() }

    /** Seconds; 0 = off (always dark), -1 = always on. */
    var backlightSecs: Int
        get() = p.getInt("backlight", 10)
        set(v) { p.edit().putInt("backlight", v).apply() }

    /**
     * The era's Clicker setting, kept per era. Falls back to the era's default, or Off when the
     * old single "clicker" switch was turned off.
     */
    fun clickerRoute(e: Era): ClickRoute {
        val saved = p.getString("clicker_${e.id}", null)
        ClickRoute.entries.firstOrNull { it.name == saved }?.let { return it }
        return if (!p.getBoolean("clicker", true)) ClickRoute.OFF else ClickRoute.default(e)
    }

    fun setClickerRoute(e: Era, r: ClickRoute) { p.edit().putString("clicker_${e.id}", r.name).apply() }

    var radioRegion: String?
        get() = p.getString("radio_region", null)
        set(v) { p.edit().putString("radio_region", v).apply() }

    /** Favorites kept here while the FM app has no way to take them (kHz). */
    var radioFavorites: Set<Int>
        get() = p.getStringSet("radio_favs", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) { p.edit().putStringSet("radio_favs", v.map { it.toString() }.toSet()).apply() }

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) { p.edit().putBoolean("haptics", v).apply() }

    /** 2004 skin only: false = the gray screen of the 2004 mono models. */
    var color2004: Boolean
        get() = p.getBoolean("color_2004", true)
        set(v) { p.edit().putBoolean("color_2004", v).apply() }

    var brickBest: Int
        get() = p.getInt("brick_best", 0)
        set(v) { p.edit().putInt("brick_best", v).apply() }

    /** 1 to 3: the 2001 screen's contrast setting. */
    var contrast: Int
        get() = p.getInt("contrast", 2)
        set(v) { p.edit().putInt("contrast", v).apply() }
}
