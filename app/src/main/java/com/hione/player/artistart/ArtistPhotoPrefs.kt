package com.hione.player.artistart

import android.content.Context
import android.content.SharedPreferences

/**
 * User-facing switches for the real-artist-photo feature (Settings > "Artist Photos").
 *
 *  - fetchOnline (default ON): allowed to ask Wikidata / Wikipedia / Wikimedia Commons for a photo.
 *    OFF never touches the network; photos already cached on disk and user-curated local
 *    artist.jpg files keep showing (they're offline data the user already has).
 *  - wifiOnly (default OFF): when ON, lookups only run while the active network is Wi-Fi/Ethernet.
 */
object ArtistPhotoPrefs {
    private const val PREF = "hione_artist_photo_prefs"
    private const val KEY_FETCH_ONLINE = "fetch_online"
    private const val KEY_WIFI_ONLY = "wifi_only"

    private fun p(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun fetchOnline(ctx: Context): Boolean = p(ctx).getBoolean(KEY_FETCH_ONLINE, true)
    fun setFetchOnline(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_FETCH_ONLINE, on).apply()
        // Flipping the switch back on should retry artists that were skipped while it was off.
        if (on) ArtistPhotoCache.clearTransientMisses()
    }

    fun wifiOnly(ctx: Context): Boolean = p(ctx).getBoolean(KEY_WIFI_ONLY, false)
    fun setWifiOnly(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_WIFI_ONLY, on).apply()
        if (!on) ArtistPhotoCache.clearTransientMisses()
    }
}
