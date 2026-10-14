package com.hione.player

import com.hione.player.bpm.HioneTempoAnalyzer
import com.hione.player.bpm.HioneTempoDb

/**
 * Library-wide tempo lookup for features that rank or filter many tracks at once (Vibe Alchemy).
 *
 * This object used to run its own analysis: an FFT spectral flux over the first 20 s, with a
 * hard 120 BPM answer for anything it could not decode. Nothing ever called that analysis, so the
 * cache it read from was always empty and every lookup returned null. Tempo now comes from
 * [HioneTempoDb], which the now-playing analysis and the library job fill (see HioneTempoAnalyzer).
 * Only shown-quality tempos are returned; anything else is null, never a guess.
 */
object BpmEngine {
    private const val REFRESH_MS = 10 * 60_000L

    @Volatile private var snapshot: Map<String, Int> = emptyMap()
    @Volatile private var snapshotAt = 0L

    /** Whole-number tempo for [trackPath], or null when the library job has not reached it. */
    fun getCachedBpm(trackPath: String): Int? {
        refreshIfStale()
        return snapshot[trackPath]
    }

    private fun refreshIfStale() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (snapshotAt != 0L && now - snapshotAt < REFRESH_MS) return
        val ctx = HioneTempoAnalyzer.appContext ?: return
        snapshotAt = now
        snapshot = runCatching { HioneTempoDb.get(ctx).allShown(HioneTempoAnalyzer.MIN_SHOW_CONFIDENCE) }
            .getOrDefault(emptyMap())
    }
}
