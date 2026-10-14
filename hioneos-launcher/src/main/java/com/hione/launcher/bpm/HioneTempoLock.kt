package com.hione.launcher.bpm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Tempo LOCK for the live output-mix beat detector.
 *
 * THE PROBLEM. [HioneLiveBeatDetector] produces one tempo ESTIMATE per detected onset, from the
 * median of the last few inter-onset intervals. That estimate is noisy by construction: a vocal
 * transient, a cymbal, a fill, a bar of silence or a slightly swung hat all move it by a few BPM.
 * Publishing every estimate made the readout flicker (e.g. 126 → 129 → 124 → 127) while a track
 * played at one constant tempo — which reads as broken, and makes a rhythm game whose target
 * visibly wobbles unplayable.
 *
 * THE FIX is a lock, not a smoother. A smoother still moves the number on every onset; a lock
 * picks one value and then holds it:
 *
 *  1. ACQUIRE — collect estimates until [ACQUIRE_SAMPLES] of them agree with each other inside
 *     the detector's own tolerance, then LOCK to their median. Until that happens the published
 *     tempo is nothing at all: the UI shows "listening…", never a confident number that no
 *     agreement backs. (Standing rule on this project: honest no-data beats a flattering guess.)
 *  2. HOLD — while locked, an agreeing estimate changes NOTHING. The displayed value is not
 *     re-derived, re-averaged or nudged; it is the same number until the lock actually breaks.
 *  3. HYSTERESIS — one disagreeing estimate is an outlier and is ignored (it only arms a counter,
 *     and a single agreeing estimate disarms it again). The lock only breaks when
 *     [RELOCK_SAMPLES] consecutive estimates disagree with the locked tempo AND agree with each
 *     other — i.e. sustained evidence of a genuinely different tempo, at which point it re-locks
 *     straight onto that new tempo.
 *  4. OCTAVE TOLERANCE — this detector is octave-ambiguous by nature (it triggers on kick energy,
 *     so it happily reports half or double time when the drum pattern changes). An estimate at
 *     ~0.5x / 2x / 4x the locked tempo is the SAME tempo and keeps the lock untouched.
 *  5. TRACK CHANGE — a new track is not an outlier, it is a new question. The lock is dropped
 *     immediately and acquisition restarts, rather than waiting out the hysteresis.
 *
 * Everything here is derived from real measured onsets. The lock never invents a tempo, and a
 * value only becomes visible once the evidence for it exists.
 */
object HioneTempoLock {

    /** Agreeing estimates needed to take a lock. */
    const val ACQUIRE_SAMPLES = 6

    /** Consecutive disagreeing-but-mutually-agreeing estimates needed to break a lock. */
    const val RELOCK_SAMPLES = 6

    /** With no estimate for this long the lock is considered stale (playback ended/changed). */
    const val STALE_MS = 12_000L

    private const val CANDIDATE_WINDOW = 10

    enum class LockState {
        /** Nothing heard yet — there is no tempo to show. */
        IDLE,

        /** Estimates are arriving but not enough of them agree yet. */
        ACQUIRING,

        /** A tempo is held. [Tempo.bpm] will not change until the lock breaks. */
        LOCKED
    }

    data class Tempo(
        val state: LockState = LockState.IDLE,
        /** The held tempo. 0 unless [state] is LOCKED — never a placeholder. */
        val bpm: Float = 0f,
        /** How many agreeing estimates are in hand (progress toward a lock, or the lock's basis). */
        val agreeing: Int = 0,
        /** Consecutive estimates arguing for a different tempo (0 = the lock is unchallenged). */
        val challenging: Int = 0,
        val lockedAtEpochMs: Long = 0L
    ) {
        val isLocked: Boolean get() = state == LockState.LOCKED && bpm > 0f
        /** Short, honest status line for any readout that wants one. */
        val label: String
            get() = when {
                isLocked && challenging > 0 -> "LOCKED (tempo change? $challenging/$RELOCK_SAMPLES)"
                isLocked -> "TEMPO LOCKED"
                state == LockState.ACQUIRING -> "listening… ($agreeing/$ACQUIRE_SAMPLES)"
                else -> "no tempo detected"
            }
    }

    private val _tempo = MutableStateFlow(Tempo())
    val tempo: StateFlow<Tempo> = _tempo.asStateFlow()

    /** Estimates supporting a *possible* tempo — during acquisition, or during a challenge. */
    private val candidates = ArrayDeque<Float>()
    private var trackKey: String? = null
    @Volatile private var lastEstimateMs = 0L

    /** True while a tempo is held — the only state in which a caller may publish/score a tempo. */
    val lockedBpm: Float get() = _tempo.value.let { if (it.isLocked) it.bpm else 0f }

    /**
     * Feed one raw per-onset estimate. Returns the tempo that should be PUBLISHED right now:
     * the locked value, or 0 when nothing is locked yet (publish no tempo, not a guess).
     */
    @Synchronized
    fun onEstimate(rawBpm: Float): Float {
        if (!rawBpm.isFinite() || rawBpm < 20f || rawBpm > 999f) return lockedBpm
        val now = System.currentTimeMillis()
        // A long gap (track ended, output stopped, launcher was away) invalidates the held tempo:
        // whatever comes back may be a different song entirely.
        if (lastEstimateMs != 0L && now - lastEstimateMs > STALE_MS) resetLocked()
        lastEstimateMs = now

        val cur = _tempo.value
        if (cur.isLocked) {
            // Octave-tolerant agreement: half/double time is the same tempo, not a change.
            if (HioneRhythmTiming.octaveMultiplier(rawBpm, cur.bpm) != null) {
                // Agreement disarms any challenge. The held value is NOT touched — that is the
                // whole point of the lock: solid until the tempo really changes.
                candidates.clear()
                if (cur.challenging != 0) _tempo.value = cur.copy(challenging = 0)
                return cur.bpm
            }
            // Disagreement: only counts if this estimate also agrees with the other recent
            // dissenters, so a scatter of unrelated outliers can never add up to a re-lock.
            if (candidates.isNotEmpty() && !HioneRhythmTiming.bpmMatches(rawBpm, candidates.last())) {
                candidates.clear()
            }
            candidates.addLast(rawBpm)
            while (candidates.size > CANDIDATE_WINDOW) candidates.removeFirst()
            if (candidates.size >= RELOCK_SAMPLES) {
                val newBpm = median(candidates)
                candidates.clear()
                _tempo.value = Tempo(LockState.LOCKED, newBpm, RELOCK_SAMPLES, 0, now)
                return newBpm
            }
            _tempo.value = cur.copy(challenging = candidates.size)
            return cur.bpm
        }

        // ----- acquiring -----
        // Keep only estimates that agree with the newest one (octave-folded), so the run that
        // finally reaches ACQUIRE_SAMPLES is genuinely one tempo and not an average of two.
        if (candidates.isNotEmpty() && HioneRhythmTiming.octaveMultiplier(rawBpm, candidates.last()) == null) {
            candidates.clear()
        }
        candidates.addLast(foldToOctaveOf(rawBpm, candidates.firstOrNull()))
        while (candidates.size > CANDIDATE_WINDOW) candidates.removeFirst()
        return if (candidates.size >= ACQUIRE_SAMPLES) {
            val locked = median(candidates)
            candidates.clear()
            _tempo.value = Tempo(LockState.LOCKED, locked, ACQUIRE_SAMPLES, 0, now)
            locked
        } else {
            _tempo.value = Tempo(LockState.ACQUIRING, 0f, candidates.size, 0, 0L)
            0f
        }
    }

    /**
     * The now-playing track changed (or playback restarted). Drops the lock at once and
     * re-acquires — a new song is not an outlier to be argued down over six onsets.
     * Passing the same key twice is a no-op, so this is safe to call from a polling loop.
     */
    @Synchronized
    fun onTrackChanged(key: String?) {
        if (key == trackKey) return
        trackKey = key
        resetLocked()
    }

    /** Output went silent / the detector detached. The next audio re-acquires from scratch. */
    @Synchronized
    fun onPlaybackStopped() {
        resetLocked()
        lastEstimateMs = 0L
    }

    private fun resetLocked() {
        candidates.clear()
        if (_tempo.value.state != LockState.IDLE) _tempo.value = Tempo()
    }

    /**
     * Express [bpm] in the same octave as [reference] so a run of half/double-time estimates
     * still produces one coherent median instead of a meaningless average of 85 and 170.
     */
    private fun foldToOctaveOf(bpm: Float, reference: Float?): Float {
        if (reference == null || reference <= 0f) return bpm
        val m = HioneRhythmTiming.octaveMultiplier(bpm, reference) ?: return bpm
        return if (abs(m - 1f) < 0.01f) bpm else bpm / m
    }

    private fun median(values: Collection<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
