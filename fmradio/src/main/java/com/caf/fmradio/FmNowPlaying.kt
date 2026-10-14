package com.caf.fmradio

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Tells the rest of HioneOS what the radio is playing, the same way Hione Music does.
 *
 *  - SystemUI's now-playing HUD (the "toast" that slides in over any app) listens for
 *    `com.hione.player.action.TRACK_CHANGED`. Radio songs go out on that broadcast with
 *    `openPackage=com.caf.fmradio`, so a tap opens the tuner, and the HUD stays quiet while the
 *    tuner is itself on screen.
 *  - The launcher widgets, AOD and lockscreen read Settings.Global `hione_now_playing_title` /
 *    `hione_now_playing_artist`. Those are written too, so they follow the radio while it plays.
 *
 * WHAT COUNTS AS A SONG. RT+ is the station saying "this is the artist, this is the title", so
 * a change there is a song change. Without RT+, RadioText is the best there is, but stations
 * also rotate slogans and phone numbers through it, so a plain RT change only announces when it
 * has been stable for [RT_SETTLE_MS] and differs from what was last announced, and never more
 * than once per [MIN_GAP_MS].
 */
object FmNowPlaying {

    private const val TAG = "FmNowPlaying"
    private const val ACTION = "com.hione.player.action.TRACK_CHANGED"
    private const val SYSTEMUI = "com.hione.systemui"
    private const val MIN_GAP_MS = 15_000L
    private const val RT_SETTLE_MS = 4_000L

    private var lastAnnounced = ""
    private var lastAnnouncedAt = 0L
    private var pendingRt = ""
    private var pendingRtSince = 0L

    /** New station: forget what was announced, so the first song on it is announced. */
    @Synchronized
    fun newStation() {
        lastAnnounced = ""
        pendingRt = ""
    }

    /**
     * Offer the latest RDS state. [station] is what to call the station (PS, call letters, or
     * the frequency). Cheap to call on every RDS update.
     */
    @Synchronized
    fun offer(ctx: Context, station: String, freqLabel: String, artist: String?, title: String?, rt: String, stereo: Boolean?) {
        val now = SystemClock.elapsedRealtime()
        val (a, t, tagged) = when {
            !title.isNullOrBlank() -> Triple(artist.orEmpty(), title, true)
            rt.isNotBlank() -> Triple("", rt, false)
            else -> return
        }
        val signature = "$a|$t"
        if (signature == lastAnnounced) return
        if (!tagged) {
            // Untagged text: wait for it to hold still before calling it a song.
            if (rt != pendingRt) { pendingRt = rt; pendingRtSince = now; return }
            if (now - pendingRtSince < RT_SETTLE_MS) return
        }
        if (now - lastAnnouncedAt < MIN_GAP_MS && !tagged) return
        lastAnnounced = signature
        lastAnnouncedAt = now
        publish(ctx, station, freqLabel, a.ifBlank { station }, t, stereo)
    }

    private fun publish(ctx: Context, station: String, freqLabel: String, artist: String, title: String, stereo: Boolean?) {
        val app = ctx.applicationContext
        val enabled = runCatching { Settings.Global.getInt(app.contentResolver, "hione_track_hud_enabled", 1) == 1 }
            .getOrDefault(true)
        if (enabled) {
            val i = Intent(ACTION).setPackage(SYSTEMUI)
                .putExtra("title", title)
                .putExtra("artist", artist)
                .putExtra("album", "$station · $freqLabel")
                .putExtra("quality", "FM $freqLabel" + when (stereo) { true -> " · STEREO"; false -> " · MONO"; null -> "" })
                .putExtra("isPlaying", true)
                .putExtra("durationMs", 0L)
                .putExtra("positionMs", 0L)
                .putExtra("reason", "fm-rds")
                .putExtra("openPackage", app.packageName)
            runCatching { app.sendBroadcast(i) }.onFailure { Log.w(TAG, "HUD broadcast failed: $it") }
        }
        runCatching {
            Settings.Global.putString(app.contentResolver, "hione_now_playing_title", title)
            Settings.Global.putString(app.contentResolver, "hione_now_playing_artist", artist)
        }.onFailure { Log.w(TAG, "now-playing keys not written: $it") }
        Log.i(TAG, "now playing on $station: $artist - $title")
    }
}
