package com.hione.player

import android.content.Context
import android.provider.Settings

/**
 * Tracks whether the CURRENT play of a track qualifies to earn a heart: the user must hear at
 * least [THRESHOLD] (94%) of it WITHOUT skipping — no jump to the next track before then, and no
 * forward-scrub past the threshold to fake completion. Each qualifying play grants exactly ONE
 * heart opportunity (you can heart it once per full listen; play it through again to heart again).
 *
 * Single-process: PlayerHolder (playback), the now-playing heart UI, and LikeStore all live in
 * com.hione.player, so this in-memory state is the shared source of truth. The "heartable" flag is
 * also mirrored to Settings.Global hione_current_track_heartable for any out-of-process surface.
 */
object HionePlayQualifier {
    const val THRESHOLD = 0.94f
    private const val SEEK_SKIP_JUMP_MS = 5_000L   // a forward scrub bigger than this = a skip

    @Volatile private var trackId = -1L
    @Volatile private var qualified = false
    @Volatile private var skipped = false
    @Volatile private var heartedThisPlay = false
    @Volatile private var lastPosMs = 0L
    @Volatile private var durMs = 0L

    /** A (possibly new) track became current — reset qualification for the fresh play. */
    fun onTrackStart(id: Long) {
        if (id == trackId) return
        trackId = id; qualified = false; skipped = false; heartedThisPlay = false; lastPosMs = 0L; durMs = 0L
    }

    /** User seeked. A big forward jump disqualifies this play (can't scrub to earn a heart). */
    fun onSeek(id: Long, newPosMs: Long) {
        if (id != trackId) { onTrackStart(id); return }
        if (newPosMs - lastPosMs > SEEK_SKIP_JUMP_MS) skipped = true
        lastPosMs = newPosMs
    }

    /** Periodic progress from the player. Marks the play qualified once it passes the threshold. */
    fun onProgress(id: Long, positionMs: Long, durationMs: Long) {
        if (id != trackId) onTrackStart(id)
        lastPosMs = positionMs
        durMs = durationMs
        if (durationMs > 0 && !skipped && positionMs >= (durationMs * THRESHOLD).toLong()) qualified = true
    }

    /** True when the current play has earned a heart the user hasn't spent yet. */
    fun isHeartable(id: Long): Boolean = id == trackId && qualified && !heartedThisPlay

    /** True once the current play has crossed the threshold (whether or not hearted yet). */
    fun isQualified(id: Long): Boolean = id == trackId && qualified

    /** How much of the current play the user has heard, 0..1 — the "how much" gauge on a like. */
    fun currentFraction(id: Long): Float =
        if (id == trackId && durMs > 0) (lastPosMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

    /** Was this play skipped/scrubbed (so a like here is a lower-confidence signal)? */
    fun wasSkipped(id: Long): Boolean = id == trackId && skipped

    fun markHearted(id: Long) { if (id == trackId) heartedThisPlay = true }

    fun publish(context: Context, id: Long) {
        runCatching {
            Settings.Global.putString(
                context.contentResolver, "hione_current_track_heartable",
                if (isHeartable(id)) "1" else "0"
            )
        }
    }
}
