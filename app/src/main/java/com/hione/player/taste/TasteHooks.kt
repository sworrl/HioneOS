package com.hione.player.taste

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hione.player.FastLibraryStore
import com.hione.player.HionePlayQualifier
import java.util.Calendar

/**
 * The ONE place the player feeds the taste engine. PlayerHolder's Player.Listener calls these
 * (wrapped in runCatching there — nothing here may ever take playback down) and everything
 * else in the taste package hangs off them:
 *
 *  - onMediaItemTransition: the PREVIOUS track just ended (naturally, skipped, or the queue was
 *    replaced). Reads how much of it was heard from HionePlayQualifier BEFORE PlayerHolder resets
 *    the qualifier for the new track — so this must be the first statement in that callback —
 *    and logs the listen + session co-occurrence. Then lets the station refill.
 *  - onPlaybackStateChanged(STATE_ENDED): the last item of the queue played out.
 *  - onShuffleModeEnabledChanged / onTimelineChanged: smart shuffle re-orders, station watches
 *    for its queue being replaced by an explicit play elsewhere.
 */
object TasteHooks {
    @Volatile private var curId = -1L
    @Volatile private var curStartedAt = 0L

    fun onMediaItemTransition(app: Context, player: ExoPlayer, mediaItem: MediaItem?, reason: Int) {
        if (com.hione.player.HioneDbg.off(app, "taste")) return
        val newId = mediaItem?.mediaId?.toLongOrNull() ?: -1L
        val prevId = curId
        val now = System.currentTimeMillis()
        if (prevId > 0L) {
            val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            val fraction = if (auto) 1f else HionePlayQualifier.currentFraction(prevId)
            finishListen(app, prevId, curStartedAt, now, fraction, auto, userEnded = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        }
        curId = newId
        curStartedAt = now
        StationEngine.onTransition(app, player, newId)
    }

    fun onPlaybackStateChanged(app: Context, player: ExoPlayer, state: Int) {
        if (com.hione.player.HioneDbg.off(app, "taste")) return
        if (state != Player.STATE_ENDED) return
        val prevId = curId
        if (prevId > 0L) {
            finishListen(app, prevId, curStartedAt, System.currentTimeMillis(), 1f, auto = true, userEnded = false)
            curId = -1L
        }
        StationEngine.onQueueEnded(app, player)
    }

    fun onShuffleModeEnabledChanged(app: Context, player: ExoPlayer, enabled: Boolean) {
        SmartShuffle.onShuffleChanged(app, player, enabled)
    }

    fun onTimelineChanged(app: Context, player: ExoPlayer) {
        StationEngine.onTimelineChanged(app, player)
        SmartShuffle.onTimelineChanged(app, player)
    }

    /** Which track the hooks currently consider "playing" (for the station's why-this). */
    fun currentTrackId(): Long = curId

    private fun finishListen(app: Context, id: Long, startedAt: Long, endedAt: Long, fraction: Float, auto: Boolean, userEnded: Boolean) {
        // Less than ~3% heard and not a natural end: a queue rebuild or an instant skip-past,
        // not a listen — and not a skip worth penalising either.
        if (!auto && fraction < 0.03f) return
        val elapsed = (endedAt - startedAt).coerceAtLeast(0L)
        if (!auto && elapsed < 4_000L) return
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
        val skipped = !auto && userEnded && fraction < TasteDb.SKIP_FRACTION
        val appCtx = app.applicationContext
        TasteDb.writer.execute {
            runCatching {
                val db = TasteDb.get(appCtx)
                val last = db.lastListenEnd()
                val sessionId = if (last != null && startedAt - last.first <= TasteDb.SESSION_GAP_MS) last.second else startedAt
                val durMs = FastLibraryStore.loadSync(appCtx)?.firstOrNull { it.id == id }?.durationMs ?: 0L
                val listenedMs = if (durMs > 0L) (durMs * fraction.coerceIn(0f, 1f)).toLong() else minOf(elapsed, 20L * 60_000L)
                db.insertListen(
                    TasteDb.Listen(
                        trackId = id, startedAt = startedAt, endedAt = endedAt,
                        fraction = fraction.coerceIn(0f, 1f), listenedMs = listenedMs,
                        skipped = skipped, auto = auto, sessionId = sessionId, hour = hour, dow = dow
                    )
                )
                TasteModel.invalidate()
            }.onFailure { android.util.Log.w("TasteHooks", "listen log failed", it) }
        }
    }
}
