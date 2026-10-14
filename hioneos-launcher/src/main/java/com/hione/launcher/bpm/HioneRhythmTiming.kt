package com.hione.launcher.bpm

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Beat-match judgment tier for the rhythm game, seasons engine and incremental clicker.
 *
 * Five graded tiers instead of the old three-value PERFECT/GOOD/MISS: a rhythm game teaches
 * timing through the *gradient* between tiers. With only "perfect or good" a player who is
 * 35 ms out and a player who is 85 ms out get the same word back and learn nothing.
 *
 * Every payout weight a tier carries lives here, so the economy, the season points and the
 * haptic weight all move together and there is exactly one table to tune.
 *
 * - [growsCombo]  the combo counter advances (PERFECT/GREAT/GOOD)
 * - [keepsCombo]  the combo survives but does not advance (OK — "you stayed alive")
 * - MISS does neither, unless a combo shield absorbs it (see HioneBeatClickerEngine).
 */
enum class HitAccuracy(
    val label: String,
    val emoji: String,
    /** Was this a hit at all (used for the "≥ GREAT" session grade and for fever charge). */
    val isHit: Boolean,
    val growsCombo: Boolean,
    val keepsCombo: Boolean,
    /** Clicker payout multiplier for one tap at this tier. */
    val yieldMultiplier: Double,
    /** Base season rank points before the combo bonus. */
    val seasonPoints: Long,
    /** Percent of the fever gauge charged by one tap at this tier. */
    val feverCharge: Float,
    /** Weight passed to HioneHaptics.beat(): 2 = hard snap, 1 = click, 0 = faint thud. */
    val hapticStrength: Int
) {
    PERFECT("PERFECT", "💖", true, true, true, 6.0, 20L, 10.0f, 2),
    GREAT("GREAT", "✨", true, true, true, 3.5, 12L, 6.5f, 1),
    GOOD("GOOD", "🌸", true, true, true, 2.0, 7L, 3.5f, 1),
    OK("OK", "🎵", true, false, true, 1.2, 3L, 1.5f, 0),
    MISS("MISS", "💤", false, false, false, 0.5, 0L, 0f, 0);

    /** PERFECT/GREAT — the band the session grade and the difficulty tier are scored on. */
    val isGreatOrBetter: Boolean get() = this == PERFECT || this == GREAT
}

/**
 * The rhythm game's timing model: graded hit windows, the on-beat deadzone, the adaptive
 * difficulty tiers and the BPM-comparison tolerances.
 *
 * WINDOW REASONING (HiBy M500, 60 Hz panel)
 * -----------------------------------------
 * One display frame is 16.7 ms, and a capacitive panel sampled at 60-120 Hz adds another
 * frame or so of jitter before a touch even reaches us. So a window tighter than ~2 frames
 * is not something a player can *aim* at — it is noise, and grading on it feels arbitrary.
 * Hence:
 *   DEADZONE ±18 ms  (~1 frame)   — inside this the deviation is not meaningfully measurable,
 *                                   so it reads as a clean "ON BEAT" with no early/late arrow.
 *   PERFECT  ±32 ms  (~2 frames)  — the tightest window a human can repeatedly hit here.
 *   GREAT    ±58 ms  (~3.5 frames)
 *   GOOD     ±95 ms
 *   OK       ±140 ms              — still a hit; keeps the combo alive but does not build it.
 *   beyond   = MISS
 * These are half-widths (±), matching how rhythm games quote them.
 *
 * Windows are additionally capped at [MAX_WINDOW_BEAT_FRACTION] of the beat period, because a
 * window wider than half a beat would overlap the *next* beat and every tap would "hit"
 * something. At 220 BPM (273 ms/beat) that clamps OK to ±125 ms; below ~205 BPM nothing
 * clamps at all.
 */
object HioneRhythmTiming {

    // ---- Base half-widths in milliseconds, at the neutral DIVA difficulty. Tune here. ----
    const val DEADZONE_MS = 18
    const val PERFECT_MS = 32
    const val GREAT_MS = 58
    const val GOOD_MS = 95
    const val OK_MS = 140

    /** No window may exceed this share of a beat period, or windows would overlap the next beat. */
    const val MAX_WINDOW_BEAT_FRACTION = 0.46f

    /** A judgment is only possible against a detector pulse no older than this. Matches HioneTempoLock.STALE_MS. */
    const val PULSE_FRESHNESS_MS = 12_000L

    data class Windows(
        val deadzoneMs: Int,
        val perfectMs: Int,
        val greatMs: Int,
        val goodMs: Int,
        val okMs: Int
    )

    val NEUTRAL: Windows = Windows(DEADZONE_MS, PERFECT_MS, GREAT_MS, GOOD_MS, OK_MS)

    /**
     * Effective windows for a given tempo.
     *
     * @param beatPeriodMs  beat period; <= 0 means "unknown", no beat-fraction clamp is applied.
     * @param leniency      difficulty scale (see [RhythmTier]); >1 widens every window.
     * @param bonusMs       flat widening earned from the Timing Window Expander skill.
     */
    fun windowsFor(beatPeriodMs: Long, leniency: Float = 1f, bonusMs: Int = 0): Windows {
        val len = leniency.coerceIn(0.6f, 2.0f)
        val bonus = bonusMs.coerceIn(0, 60)
        val ceiling =
            if (beatPeriodMs > 0L) (beatPeriodMs * MAX_WINDOW_BEAT_FRACTION).toInt().coerceAtLeast(12)
            else Int.MAX_VALUE

        fun scale(base: Int): Int = ((base * len).roundToInt() + bonus).coerceIn(4, ceiling)

        // Keep the tiers strictly ordered even after clamping, so `judge` can never return a
        // tier that is unreachable because a wider window swallowed it.
        val perfect = scale(PERFECT_MS)
        val great = maxOf(scale(GREAT_MS), perfect + 1)
        val good = maxOf(scale(GOOD_MS), great + 1)
        val ok = maxOf(scale(OK_MS), good + 1)
        // The deadzone follows the player's own windows but never grows past PERFECT.
        val dead = minOf(((DEADZONE_MS * len).roundToInt()).coerceAtLeast(6), perfect)
        return Windows(dead, perfect, great, good, ok)
    }

    /** Grade a signed deviation (negative = early, positive = late). */
    fun judge(signedOffsetMs: Int, w: Windows): HitAccuracy {
        val a = abs(signedOffsetMs)
        return when {
            a <= w.perfectMs -> HitAccuracy.PERFECT
            a <= w.greatMs -> HitAccuracy.GREAT
            a <= w.goodMs -> HitAccuracy.GOOD
            a <= w.okMs -> HitAccuracy.OK
            else -> HitAccuracy.MISS
        }
    }

    /**
     * The deviation as it should be SHOWN. Inside the deadzone the measurement is smaller than
     * the device's own input+display granularity, so reporting "+7 ms EARLY" would be precision
     * theatre — it reads as an exact 0. Outside the deadzone the real measured value is shown.
     */
    fun displayOffsetMs(signedOffsetMs: Int, w: Windows): Int =
        if (abs(signedOffsetMs) <= w.deadzoneMs) 0 else signedOffsetMs

    /** "slightly early" style coaching arrow — teaches the correction, not just the grade. */
    fun nudgeHint(signedOffsetMs: Int, w: Windows): String = when {
        abs(signedOffsetMs) <= w.deadzoneMs -> "ON BEAT"
        signedOffsetMs < 0 -> "◀ ${abs(signedOffsetMs)}ms EARLY"
        else -> "${signedOffsetMs}ms LATE ▶"
    }

    /**
     * Signed distance from a tap to the nearest beat of the grid anchored on a REAL detected
     * pulse, in [-period/2, +period/2]. Returns null when there is no usable beat reference —
     * the caller must then neither score nor persist anything.
     *
     * @param calibrationMs constant offset (see [HioneRhythmCalibration]) subtracted from the tap
     *        time; it compensates the fixed audio-output + touch latency of the device, it does
     *        not invent a beat.
     */
    fun signedOffsetToBeat(
        tapEpochMs: Long,
        lastPulseEpochMs: Long,
        beatPeriodMs: Long,
        calibrationMs: Int
    ): Int? {
        if (beatPeriodMs <= 0L) return null
        if (lastPulseEpochMs <= 0L) return null
        if (tapEpochMs - lastPulseEpochMs > PULSE_FRESHNESS_MS) return null
        val since = (tapEpochMs - calibrationMs - lastPulseEpochMs).mod(beatPeriodMs)
        val signed = if (since > beatPeriodMs / 2) since - beatPeriodMs else since
        return signed.toInt()
    }

    /**
     * How far a GREAT missed PERFECT by, when it was close enough to be worth saying ("SO CLOSE ·
     * 4ms off PERFECT"). Null otherwise. Near-miss feedback turns a disappointment into a
     * near-win, which is what makes the next attempt feel winnable.
     */
    fun nearPerfectMissMs(signedOffsetMs: Int, w: Windows): Int? {
        val over = abs(signedOffsetMs) - w.perfectMs
        return if (over in 1..NEAR_PERFECT_MS) over else null
    }

    /** "Close" for [nearPerfectMissMs]: well inside one frame of the PERFECT edge. */
    const val NEAR_PERFECT_MS = 7

    /**
     * Lucky (golden) notes: roughly one beat in [LUCKY_ONE_IN] is golden, decided by the beat's
     * own absolute index so the note the highway draws as golden and the beat the judge scores
     * are provably the same one. Both sides call this with the same beat time: the highway with
     * `lastPulse + n·period`, the judge with `tap − calibration − deviation`, which is that exact
     * value by construction of [signedOffsetToBeat].
     *
     * A hash rather than a counter, so the pattern is irregular (a lucky note every 19th beat on
     * the dot would be a metronome, not a surprise) but still deterministic for the drawn note.
     */
    const val LUCKY_ONE_IN = 19

    fun isLuckyBeat(beatAtEpochMs: Long, beatPeriodMs: Long): Boolean {
        if (beatPeriodMs <= 0L || beatAtEpochMs <= 0L) return false
        val k = Math.floorDiv(beatAtEpochMs + beatPeriodMs / 2, beatPeriodMs)
        var h = k * -7046029254386353131L
        h = h xor (h ushr 31)
        h *= -4658895280553007687L
        h = h xor (h ushr 29)
        return (h and 0x7fffffffL) % LUCKY_ONE_IN == 0L
    }

    // =====================================================================================
    // BPM COMPARISON — the detector is an estimator, not ground truth
    // =====================================================================================

    /** Absolute floor on BPM agreement: two tempos within 2 BPM are the same tempo. */
    const val BPM_EPSILON = 2.0f

    /** Relative tolerance, so fast tracks get proportionally the same slack (1.5%). */
    const val BPM_RELATIVE_EPSILON = 0.015f

    fun bpmTolerance(bpm: Float): Float = maxOf(BPM_EPSILON, abs(bpm) * BPM_RELATIVE_EPSILON)

    /** Within a few BPM of each other — used everywhere instead of `==` on estimates. */
    fun bpmMatches(a: Float, b: Float): Boolean =
        a > 0f && b > 0f && abs(a - b) <= maxOf(bpmTolerance(a), bpmTolerance(b))

    /**
     * Octave-aware comparison: tapping 85 against a detected 170 is CORRECT (half-time), as is
     * 340 (double-time). Returns the multiplier that relates [tapped] to [reference]
     * (0.25 / 0.5 / 1 / 2 / 4) or null when they genuinely disagree.
     */
    fun octaveMultiplier(tapped: Float, reference: Float): Float? {
        if (tapped <= 0f || reference <= 0f) return null
        for (m in floatArrayOf(1f, 0.5f, 2f, 0.25f, 4f)) {
            if (bpmMatches(tapped, reference * m)) return m
        }
        return null
    }

    fun octaveLabel(multiplier: Float): String = when {
        multiplier > 3.5f -> "4x time"
        multiplier > 1.5f -> "double-time"
        multiplier < 0.3f -> "quarter-time"
        multiplier < 0.75f -> "half-time"
        else -> "on tempo"
    }

    // =====================================================================================
    // ADAPTIVE DIFFICULTY — keep the player in the flow channel
    // =====================================================================================

    /**
     * Difficulty tiers. The game adapts to the player instead of punishing a hard track: a
     * newcomer (or someone fighting a 200 BPM drum'n'bass track) gets wide windows, and the
     * windows tighten only once they are actually landing hits. Tighter tiers pay more, so
     * graduating is a reward rather than a punishment, and dropping back is a soft landing.
     */
    enum class RhythmTier(
        val label: String,
        val badge: String,
        /** Multiplies every hit window. */
        val leniency: Float,
        /** Multiplies score/season points earned at this tier. */
        val scoreScale: Float
    ) {
        PRACTICE("PRACTICE", "🌱", 1.45f, 0.80f),
        RHYTHM("RHYTHM", "🎵", 1.20f, 1.00f),
        DIVA("DIVA", "💖", 1.00f, 1.25f),
        MASTER39("MASTER 39", "👑", 0.82f, 1.60f)
    }

    /** Judged taps needed before the game starts tightening at all. */
    const val TIER_WARMUP_TAPS = 20

    /**
     * @param judgedTaps       taps actually measured against a real beat this session
     * @param greatOrBetterPct rolling share of those taps that were GREAT or PERFECT, 0..100,
     *                         or a negative value when there is not enough data yet.
     */
    fun tierFor(judgedTaps: Int, greatOrBetterPct: Float): RhythmTier = when {
        judgedTaps < TIER_WARMUP_TAPS || greatOrBetterPct < 0f -> RhythmTier.PRACTICE
        greatOrBetterPct >= 90f -> RhythmTier.MASTER39
        greatOrBetterPct >= 72f -> RhythmTier.DIVA
        greatOrBetterPct >= 50f -> RhythmTier.RHYTHM
        else -> RhythmTier.PRACTICE
    }
}

/**
 * Constant-offset calibration for the tap → beat comparison.
 *
 * The whole signal chain adds a fixed delay between "the beat the detector timestamped" and
 * "the beat the player heard and reacted to": the mixer buffer and DAC still ahead of the
 * Visualizer capture, plus touch sampling on the way back. A constant offset makes *every*
 * tap read late and no amount of skill removes it — so it has to be measured out.
 *
 * Two sources, in order of honesty:
 *  1. [suggestion] — the median of the player's own recent JUDGED deviations. This is real
 *     measured data from this device, this player, this output path. One tap applies it.
 *  2. [latencyNote] — a best-effort platform probe, shown as information only and never
 *     applied silently, because what it reports is a lower bound (one mixer buffer), not the
 *     end-to-end latency, and it says so.
 *
 * Applying an offset re-centres a biased measurement; it never creates a beat reference. Only
 * deviations from taps that were judged against a real detected pulse are ever recorded here.
 */
object HioneRhythmCalibration {
    private const val PREFS = "hione_rhythm_calibration"
    private const val KEY_OFFSET = "user_offset_ms"

    /** Deviation samples needed before we will suggest a shift. */
    const val MIN_SAMPLES = 12

    /** Below this the bias is inside the deadzone; shifting would be superstition. */
    const val MIN_MEANINGFUL_SHIFT_MS = 8

    const val MAX_OFFSET_MS = 250

    private const val SAMPLE_WINDOW = 40

    private val _offsetMs = MutableStateFlow(0)
    /** ms subtracted from every tap time before it is compared to the beat grid. */
    val offsetMs: StateFlow<Int> = _offsetMs.asStateFlow()

    private val _latencyNote = MutableStateFlow("")
    /** Honest one-line description of what the platform could tell us — may be empty. */
    val latencyNote: StateFlow<String> = _latencyNote.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private val _suggestionMs = MutableStateFlow<Int?>(null)
    /** Extra ms to add to [offsetMs] to centre the player's taps, or null if not enough data. */
    val suggestionMs: StateFlow<Int?> = _suggestionMs.asStateFlow()

    private val samples = ArrayDeque<Int>()
    @Volatile private var appCtx: Context? = null

    fun init(context: Context) {
        if (appCtx != null) return
        val app = context.applicationContext
        appCtx = app
        _offsetMs.value = try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_OFFSET, 0).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        } catch (_: Throwable) { 0 }
        _latencyNote.value = probeOutputLatency(app)
    }

    /**
     * Record the (already offset-corrected) signed deviation of a JUDGED tap. Callers must not
     * call this for a free tap — there was no beat to deviate from.
     */
    fun recordJudgedDeviation(signedMs: Int) {
        synchronized(samples) {
            samples.addLast(signedMs)
            while (samples.size > SAMPLE_WINDOW) samples.removeFirst()
            _sampleCount.value = samples.size
            _suggestionMs.value = if (samples.size < MIN_SAMPLES) null else {
                val med = samples.sorted()[samples.size / 2]
                if (abs(med) < MIN_MEANINGFUL_SHIFT_MS) null else med
            }
        }
    }

    /** Apply the measured median. Returns the new total offset, or null if nothing to apply. */
    fun applySuggestion(): Int? {
        val s = _suggestionMs.value ?: return null
        val next = (_offsetMs.value + s).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        _offsetMs.value = next
        persist()
        synchronized(samples) {
            samples.clear()
            _sampleCount.value = 0
            _suggestionMs.value = null
        }
        return next
    }

    /** Manual trim, for players who prefer to dial it in by feel. */
    fun nudge(deltaMs: Int) {
        _offsetMs.value = (_offsetMs.value + deltaMs).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        persist()
        synchronized(samples) {
            samples.clear()
            _sampleCount.value = 0
            _suggestionMs.value = null
        }
    }

    private fun persist() {
        val c = appCtx ?: return
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_OFFSET, _offsetMs.value).apply()
        } catch (_: Throwable) {}
    }

    /**
     * Best-effort output-latency probe. Returns a human-readable note, never a silently applied
     * number — and the note states which of the two things it actually is.
     */
    private fun probeOutputLatency(ctx: Context): String {
        val am = try { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager } catch (_: Throwable) { null }
            ?: return ""
        // 1. The platform's own figure. @hide, so reflection; present on most AOSP builds and
        //    reachable here because HioneOS is platform-signed. If the hidden-API filter or a
        //    vendor build blocks it we simply fall through — we never guess in its place.
        try {
            val m = AudioManager::class.java.getMethod("getOutputLatency", Integer.TYPE)
            val v = m.invoke(am, AudioManager.STREAM_MUSIC) as? Int
            if (v != null && v in 1..500) return "output latency ${v}ms (platform)"
        } catch (_: Throwable) {}
        // 2. One mixer buffer — a LOWER BOUND on the real end-to-end latency, not the value
        //    itself. Labelled as such so nobody reads it as a measurement it is not.
        try {
            val fpb = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
            val sr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            if (fpb != null && sr != null && fpb > 0 && sr > 0) {
                val oneBuffer = (fpb * 1000.0 / sr).roundToInt()
                return "≥${oneBuffer}ms buffer (lower bound only)"
            }
        } catch (_: Throwable) {}
        return "output latency unknown, calibrate by tapping"
    }
}
