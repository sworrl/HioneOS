package com.hione.player.discsplit

import android.content.Context
import android.content.SharedPreferences

/**
 * User switches for the online whole-disc splitter (Settings → Disc Images).
 *
 *  • "Split disc images online" (default ON) — allow MusicBrainz lookups at all. Turning it OFF
 *    never throws away splits that already landed: cached matches keep applying, only new network
 *    lookups stop.
 *  • "Wi-Fi only" (default ON) — skip lookups on mobile data / hotspot.
 */
object DiscSplitPrefs {
    private const val PREF = "hione_disc_split_prefs"
    private const val KEY_ONLINE = "split_online"
    private const val KEY_WIFI_ONLY = "wifi_only"

    private fun p(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun splitOnline(ctx: Context): Boolean = p(ctx).getBoolean(KEY_ONLINE, true)
    fun setSplitOnline(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_ONLINE, on).apply()
        // Flipping it back on should pick up every image that was skipped while it was off.
        if (on) MusicBrainzSplitter.kick(ctx)
    }

    fun wifiOnly(ctx: Context): Boolean = p(ctx).getBoolean(KEY_WIFI_ONLY, true)
    fun setWifiOnly(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_WIFI_ONLY, on).apply()
        if (!on) MusicBrainzSplitter.kick(ctx)
    }
}
