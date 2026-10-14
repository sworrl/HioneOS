package com.hione.player.wanted

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * A song hearted in the FM app (com.caf.fmradio, FmLikes.send).
 *
 * The manifest guards this with android:permission="com.hione.permission.SYSTEM_BRIDGE", so only a
 * platform-signed sender gets through, and the FM app sends with that same permission as the
 * receiver permission, so only a holder (this app) can hear it. Work runs off the main thread under
 * goAsync: it touches SQLite and may read the library cache from disk.
 */
class FmTrackLikedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WantedStore.ACTION_FM_TRACK_LIKED) return
        val id = intent.getStringExtra("id")?.ifBlank { null } ?: return
        val title = intent.getStringExtra("title").orEmpty()
        val artist = intent.getStringExtra("artist").orEmpty()
        val unlike = intent.getBooleanExtra("unlike", false)
        val like = WantedStore.FmLike(
            id = id,
            title = title,
            artist = artist,
            album = intent.getStringExtra("album"),
            coverUrl = intent.getStringExtra("cover_url"),
            webUrl = intent.getStringExtra("web_url"),
            station = intent.getStringExtra("station").orEmpty(),
            freqKHz = intent.getIntExtra("freq_khz", 0),
            heardAtMs = intent.getLongExtra("heard_at_ms", 0L),
            lat = if (intent.hasExtra("lat")) intent.getDoubleExtra("lat", 0.0) else null,
            lon = if (intent.hasExtra("lon")) intent.getDoubleExtra("lon", 0.0) else null,
            source = intent.getStringExtra("source") ?: "fm-songid",
        )
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                if (unlike) WantedStore.unlike(app, id, title, artist) else WantedStore.ingest(app, like)
            } catch (t: Throwable) {
                Log.w("HioneWanted", "FM like failed: $t")
            } finally {
                pending.finish()
            }
        }, "hione-wanted-in").start()
    }
}
