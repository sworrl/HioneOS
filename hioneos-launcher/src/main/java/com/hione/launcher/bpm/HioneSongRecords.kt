package com.hione.launcher.bpm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Per-song runs and personal bests.
 *
 * WHY. A session score that never resets and never compares is a counter, not a goal. The
 * single strongest replay hook in every rhythm game is "beat your own score on THIS song",
 * because the song is the level: the player knows where the hard part is and wants another go.
 * So the game now keeps a RUN per track (judged taps since the track started) and a best per
 * track, and says so the moment a run passes the best.
 *
 * GRADE. Weighted accuracy over the run, the usual rhythm-game shape: PERFECT counts in full,
 * GREAT 80 %, GOOD 50 %, OK 20 %, MISS nothing. A grade needs [MIN_GRADED_TAPS] judged taps; a
 * handful of lucky taps is not an S. "SS" additionally requires a full combo (no MISS at all).
 *
 * Only judged taps reach [onJudgedTap], so a best can never be set by tapping with nothing playing.
 * Records are keyed by the published artist|title; with no track identity there is no record.
 */
object HioneSongRecords {
    private const val PREFS = "hione_song_records"
    const val MIN_GRADED_TAPS = 20
    private const val MAX_RECORDS = 400

    data class Run(
        val key: String? = null,
        val taps: Int = 0,
        val score: Long = 0L,
        val weighted: Float = 0f,
        val misses: Int = 0,
        val bestCombo: Int = 0,
        /** True once this run has passed the stored best (celebrated once). */
        val newBest: Boolean = false
    ) {
        val accuracyPct: Float get() = if (taps > 0) weighted * 100f / taps else -1f
        val fullCombo: Boolean get() = taps >= MIN_GRADED_TAPS && misses == 0
        val grade: String get() = gradeFor(taps, accuracyPct, fullCombo)
    }

    data class Best(
        val score: Long = 0L,
        val grade: String = "",
        val combo: Int = 0,
        val fullCombo: Boolean = false
    )

    private val _run = MutableStateFlow(Run())
    val run: StateFlow<Run> = _run.asStateFlow()

    private val _best = MutableStateFlow<Best?>(null)
    /** Stored best for the current track, or null when none (or no track identity). */
    val best: StateFlow<Best?> = _best.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun keyFor(artist: String?, title: String?): String? {
        if (artist.isNullOrBlank() || title.isNullOrBlank()) return null
        return "${artist.trim().lowercase()}|${title.trim().lowercase()}"
    }

    /** New track (or the stage just opened): start a fresh run and load that track's best. */
    @Synchronized
    fun beginRun(key: String?) {
        if (key == _run.value.key && _run.value.taps > 0) return
        _run.value = Run(key = key)
        _best.value = key?.let { load(it) }
    }

    /**
     * One judged tap of the current run. Returns true exactly once per run: on the tap where the
     * run first beats a stored best (and only after [MIN_GRADED_TAPS], so the first few taps of a
     * fresh song never fire it).
     */
    @Synchronized
    fun onJudgedTap(accuracy: HitAccuracy, points: Long, combo: Int): Boolean {
        val r = _run.value
        val weight = when (accuracy) {
            HitAccuracy.PERFECT -> 1f
            HitAccuracy.GREAT -> 0.8f
            HitAccuracy.GOOD -> 0.5f
            HitAccuracy.OK -> 0.2f
            HitAccuracy.MISS -> 0f
        }
        var next = r.copy(
            taps = r.taps + 1,
            score = r.score + points.coerceAtLeast(0L),
            weighted = r.weighted + weight,
            misses = r.misses + if (accuracy == HitAccuracy.MISS) 1 else 0,
            bestCombo = maxOf(r.bestCombo, combo)
        )
        var fire = false
        val key = next.key
        if (key != null && next.taps >= MIN_GRADED_TAPS) {
            val b = _best.value
            if (b == null || next.score > b.score) {
                // Only celebrate beating a REAL previous best. The first run on a song just sets one.
                if (b != null && b.score > 0 && !next.newBest) { fire = true; next = next.copy(newBest = true) }
                val nb = Best(next.score, next.grade, maxOf(next.bestCombo, b?.combo ?: 0), next.fullCombo || (b?.fullCombo == true))
                _best.value = nb
                if (next.taps % 5 == 0 || fire || b == null) save(key, nb)
            } else if (next.bestCombo > b.combo || (next.fullCombo && !b.fullCombo)) {
                val nb = b.copy(combo = maxOf(b.combo, next.bestCombo), fullCombo = b.fullCombo || next.fullCombo)
                _best.value = nb
                save(key, nb)
            }
        }
        _run.value = next
        return fire
    }

    private fun load(key: String): Best? {
        val raw = prefs?.getString(key, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Best(o.optLong("s"), o.optString("g"), o.optInt("c"), o.optBoolean("fc"))
        }.getOrNull()
    }

    private fun save(key: String, b: Best) {
        val p = prefs ?: return
        runCatching {
            val o = JSONObject().put("s", b.score).put("g", b.grade).put("c", b.combo).put("fc", b.fullCombo)
            val e = p.edit().putString(key, o.toString())
            // Bounded: a library is big, a prefs file should not be. Oldest-insertion order is not
            // tracked, so when over the cap drop arbitrary entries; a lost best on a song not
            // played in ages is an acceptable price for never growing without limit.
            if (p.all.size > MAX_RECORDS) p.all.keys.take(p.all.size - MAX_RECORDS).forEach { if (it != key) e.remove(it) }
            e.apply()
        }
    }

    fun gradeFor(taps: Int, accuracyPct: Float, fullCombo: Boolean): String = when {
        taps < MIN_GRADED_TAPS || accuracyPct < 0f -> "-"
        accuracyPct >= 95f && fullCombo -> "SS"
        accuracyPct >= 90f -> "S"
        accuracyPct >= 80f -> "A"
        accuracyPct >= 65f -> "B"
        accuracyPct >= 50f -> "C"
        else -> "D"
    }
}
