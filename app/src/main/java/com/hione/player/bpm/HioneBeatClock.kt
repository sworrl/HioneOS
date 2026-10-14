package com.hione.player.bpm

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The one beat grid Hione Music publishes: a period and an anchor (a beat time on the
 * SystemClock.elapsedRealtimeNanos clock). Everything that flashes, pulses or judges a tap on
 * the beat reads this grid.
 *
 * WHERE THE GRID COMES FROM.
 *  - TEMPO. The track's whole-file tempo (tag or TempoTrackV2, see [HioneTempoAnalyzer]) when there
 *    is one. It is constant for the track, so the number on screen never wobbles. Without it the
 *    tempo comes from the live beats below, fitted over the last several beats.
 *  - PHASE. BTrack beats from the PCM the player is writing ([HioneBeatTap]), each mapped to the
 *    moment it leaves the DAC with AudioTrack timestamps. Before the first live beat arrives the
 *    phase comes from the file analysis's first-beat offset and the player position.
 *
 * SMOOTHING. A live beat does not reset the grid. It is compared with the grid's own prediction
 * and moves the anchor a fifth of the way toward it (and the period by a small, bounded amount),
 * a second-order phase-locked loop. BTrack reports beats on its 11.6 ms detection-function frames,
 * so single beats jitter by that much; the loop averages it out while still following a real
 * drift or a seek within a few beats. A beat half a period off the grid is the other octave
 * (BTrack only tracks 80..160 BPM) and is ignored unless eight come in a row. Six off-grid beats in
 * a row with a steady offset mean the grid really is wrong, and it re-anchors on them.
 */
object HioneBeatClock {

    private const val PHASE_GAIN = 0.2
    private const val PERIOD_GAIN = 0.02
    /** How far the loop may pull the period away from the file tempo. */
    private const val MAX_PERIOD_PULL = 0.02
    private const val RELOCK_AFTER = 6
    private const val LOCK_AFTER = 4

    data class Grid(
        /** Display tempo, 0 = unknown. */
        val bpm: Float,
        /** Beat period in ns, 0 = no grid. */
        val periodNs: Double,
        /** A beat on the elapsedRealtimeNanos clock, 0 = no phase yet. */
        val anchorNs: Long,
        /** True once live beats agree with the grid (or the file grid seeded it). */
        val phaseLocked: Boolean,
        val source: String,
    ) {
        fun nextBeatAtOrAfter(tNs: Long): Long {
            if (periodNs <= 0.0 || anchorNs == 0L) return 0L
            val k = Math.ceil((tNs - anchorNs) / periodNs)
            return anchorNs + (k * periodNs).roundToLong()
        }
    }

    @Volatile var grid = Grid(0f, 0.0, 0L, false, "none")
        private set

    /** File tempo in BPM (0 = none) and the period it implies. */
    private var fileBpm = 0f
    private var filePeriodNs = 0.0
    private var hits = 0
    private var misses = 0
    private var lastMissErrNs = 0.0
    private var halfOffRun = 0
    private var liveCandidate = 0.0
    private var liveAgree = 0
    private val liveBeats = ArrayDeque<Long>()

    /** New track: forget the old phase. [bpm] is the file tempo or 0. */
    @Synchronized
    fun reset(bpm: Float) {
        fileBpm = if (bpm > 0f) bpm else 0f
        filePeriodNs = if (fileBpm > 0f) 60e9 / fileBpm else 0.0
        hits = 0; misses = 0; halfOffRun = 0
        liveCandidate = 0.0; liveAgree = 0
        liveBeats.clear()
        grid = Grid(fileBpm, filePeriodNs, 0L, false, if (fileBpm > 0f) "file" else "none")
    }

    /**
     * Phase from the file analysis: the first grid beat sits [firstBeatMs] into the track, and the
     * player was at [positionMs] at [atElapsedNs]. Ignored once live beats have locked the grid.
     */
    @Synchronized
    fun seedFromFileGrid(firstBeatMs: Double, positionMs: Long, atElapsedNs: Long) {
        if (filePeriodNs <= 0.0 || firstBeatMs < 0.0) return
        if (grid.phaseLocked && grid.source == "live") return
        val periodMs = filePeriodNs / 1e6
        val k = Math.ceil((positionMs - firstBeatMs) / periodMs)
        val beatMs = firstBeatMs + k * periodMs
        val anchor = atElapsedNs + ((beatMs - positionMs) * 1e6).roundToLong()
        grid = grid.copy(anchorNs = anchor, periodNs = filePeriodNs, phaseLocked = true, source = "file-grid")
    }

    /** A seek: the phase no longer holds. The tempo does. */
    @Synchronized
    fun onDiscontinuity() {
        hits = 0; misses = 0; halfOffRun = 0
        liveBeats.clear()
        grid = grid.copy(anchorNs = 0L, phaseLocked = false, source = if (fileBpm > 0f) "file" else "none",
            periodNs = if (filePeriodNs > 0.0) filePeriodNs else grid.periodNs)
    }

    /** A live beat that will be heard at [elapsedNs]. */
    @Synchronized
    fun onLiveBeat(elapsedNs: Long) {
        liveBeats.addLast(elapsedNs)
        while (liveBeats.size > 16) liveBeats.removeFirst()

        var period = grid.periodNs
        var basePeriod = filePeriodNs
        if (fileBpm <= 0f) {
            // No file tempo: fit the period to the recent live beats instead. BTrack takes a few
            // seconds to settle, so a fit is only adopted once four in a row agree within 2 %,
            // and the adopted one is kept unless the fit moves by more than that. Until then
            // there is no tempo to show, rather than a number that runs 163, 148, 85, 81.
            val fitted = fittedLivePeriod() ?: return
            if (liveCandidate > 0.0 && abs(fitted / liveCandidate - 1.0) <= 0.02) liveAgree++ else liveAgree = 1
            liveCandidate = fitted
            if (period <= 0.0 || abs(fitted / period - 1.0) > 0.02) {
                if (liveAgree < 4) return
                period = fitted
            }
            basePeriod = period
        }
        val g = grid
        if (g.anchorNs == 0L) {
            grid = g.copy(periodNs = period, anchorNs = elapsedNs, bpm = displayBpm(period), source = "live")
            return
        }
        val k = Math.round((elapsedNs - g.anchorNs) / period).toDouble()
        val predicted = g.anchorNs + k * period
        val err = elapsedNs - predicted
        if (abs(err) <= period / 4) {
            hits++
            misses = 0
            halfOffRun = 0
            val newAnchor = (predicted + PHASE_GAIN * err).roundToLong()
            var newPeriod = period
            if (k != 0.0) {
                newPeriod = period + PERIOD_GAIN * err / maxOf(1.0, abs(k).coerceAtMost(8.0))
                newPeriod = newPeriod.coerceIn(basePeriod * (1 - MAX_PERIOD_PULL), basePeriod * (1 + MAX_PERIOD_PULL))
            }
            // The shown tempo follows the file tempo or the adopted live period, never the
            // loop's per-beat corrections.
            grid = Grid(displayBpm(basePeriod), newPeriod, newAnchor, hits >= LOCK_AFTER || g.phaseLocked,
                if (hits >= LOCK_AFTER) "live" else g.source)
        } else {
            // Half a period off is the other octave of a BTrack that is tracking at 2x the grid,
            // and those beats come interleaved with on-grid ones. A long run of them with no
            // on-grid beat in between means the grid sits on the off-beat: move it.
            val halfOff = abs(abs(err) - period / 2) < period / 8
            if (halfOff) {
                if (++halfOffRun >= RELOCK_AFTER + 2) {
                    halfOffRun = 0; hits = 0; misses = 0
                    grid = g.copy(anchorNs = elapsedNs, periodNs = period, phaseLocked = false, source = "live")
                }
                return
            }
            if (misses > 0 && abs(err - lastMissErrNs) < period / 8) misses++ else misses = 1
            lastMissErrNs = err
            if (misses >= RELOCK_AFTER) {
                hits = 0; misses = 0
                grid = g.copy(anchorNs = elapsedNs, periodNs = period, phaseLocked = false, source = "live")
            }
        }
    }

    /** The file tempo when there is one, else the live period rounded to a whole BPM. */
    private fun displayBpm(periodNs: Double): Float =
        if (fileBpm > 0f) fileBpm else if (periodNs > 0) Math.round(60e9 / periodNs).toFloat() else 0f

    /** Median inter-beat interval of the recent live beats, folded into 70..180 BPM. */
    private fun fittedLivePeriod(): Double? {
        if (liveBeats.size < 6) return null
        val d = liveBeats.zipWithNext { a, b -> (b - a).toDouble() }.filter { it > 0 }.sorted()
        if (d.isEmpty()) return null
        var p = d[d.size / 2]
        var bpm = 60e9 / p
        while (bpm >= 180.0) bpm /= 2
        while (bpm < 70.0) bpm *= 2
        p = 60e9 / bpm
        return p
    }

    /** Where in the current beat we are, 0..1, or -1 without a grid. */
    fun phaseNow(): Float {
        val g = grid
        if (g.periodNs <= 0.0 || g.anchorNs == 0L) return -1f
        val since = (SystemClock.elapsedRealtimeNanos() - g.anchorNs) / g.periodNs
        return (since - floor(since)).toFloat()
    }
}
