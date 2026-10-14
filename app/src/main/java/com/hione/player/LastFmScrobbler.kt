package com.hione.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.hione.player.stats.ListenSessionTracker

/**
 * Compatibility shim. The original poll-based scrobbler (10 s Handler tick, fire-and-forget
 * HTTP, no offline queue, no status) has been superseded by the queue-backed pipeline:
 *
 *   PlayerHolder → [com.hione.player.stats.ListenSessionTracker] (one Player.Listener + 1 s tick)
 *     → per-listen row in hione_listens.db (stats)
 *     → [com.hione.player.scrobble.ScrobbleManager] (now-playing, persisted queue, batch of 50,
 *       retry with backoff, real status line)
 *
 * Kept so any remaining/in-flight call site still compiles and simply forwards; the tracker's
 * attach() is idempotent, so calling both is harmless (no double listeners, no double scrobbles).
 */
@Deprecated("Use com.hione.player.stats.ListenSessionTracker", ReplaceWith("ListenSessionTracker"))
object LastFmScrobbler {
    fun reset() = ListenSessionTracker.reset()
    fun attach(context: Context, player: ExoPlayer) = ListenSessionTracker.attach(context, player)
}
