package com.hione.player.taste

import android.content.Context
import com.hione.player.FastLibraryStore
import com.hione.player.LikeStore
import com.hione.player.PlayerPreferences
import com.hione.player.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln

/**
 * The local affinity model: turns the raw signals on this device into ONE explainable 0..1
 * affinity per track (plus artist/album/genre aggregates and a time-of-day profile).
 *
 * Signals, all local (see TasteDb / LikeStore / PlayerPreferences):
 *   hearts      LikeStore.heartAffinity — how many times, how much was heard, how recent
 *   plays       completed listens from the listen log (+ the legacy start-count in prefs)
 *   completion  average fraction heard; skips (ended by the user before 50%) count against
 *   recency     days since last play (mild — recency mostly matters for "heavy rotation")
 *   context     artist / album / genre aggregates of the above (a track you never played by an
 *               artist you play through all the time still gets some credit)
 *   time        day-part lift: does this artist/genre get played MORE in the current slot than
 *               the owner's overall listening would predict
 *
 * Every component that contributed is kept as a [Reason] so a "why this?" line can be shown
 * instead of an opaque number. Nothing is fabricated: a track with no signal scores 0 and its
 * explanation is empty.
 *
 * The snapshot is rebuilt off the main thread (Dispatchers.Default) and cached; TasteHooks
 * invalidates it as listens land so the next reader sees fresh data.
 */
object TasteModel {
    data class Reason(val weight: Float, val text: String)

    class Features(
        val track: Track,
        val artistKey: String,
        val albumKey: String,
        val genre: String,
        val genreKey: String,
        val startCount: Int,          // legacy PlayerPreferences play count (track starts)
        val listens: Int,
        val completed: Int,
        val skips: Int,
        val avgFraction: Float,
        val recent30: Int,
        val lastPlayedAt: Long,
        val heart: Float,
        val isLiked: Boolean,
        val slotCounts: IntArray,
        val lossless: Boolean,
        val hiRes: Boolean
    ) {
        /** Best-effort "times played through" merging the listen log with the legacy counter. */
        val effectivePlays: Float
            get() = if (listens > 0) completed + 0.3f * (startCount - listens).coerceAtLeast(0)
                    else startCount * 0.7f
        val everPlayed: Boolean get() = startCount > 0 || listens > 0 || lastPlayedAt > 0L
        val skipRatio: Float get() = if (listens >= 3) skips.toFloat() / listens else 0f
    }

    class Scored(val f: Features, val affinity: Float, val reasons: List<Reason>) {
        /** Affinity with the recency term removed — what "forgotten favourites" ranks on. */
        val baseAffinity: Float get() = reasons.filter { it.text != RECENCY_TAG }.sumOf { it.weight.toDouble() }.toFloat().coerceIn(0f, 1f)
    }

    class Snapshot(
        val builtAt: Long,
        val tracks: List<Track>,
        val byId: Map<Long, Scored>,
        val artistAffinity: Map<String, Float>,
        val albumAffinity: Map<String, Float>,
        val genreAffinity: Map<String, Float>,
        val artistListens: Map<String, Int>,
        val genreListens: Map<String, Int>,
        val artistListenedMs: Map<String, Long>,
        val genreListenedMs: Map<String, Long>,
        val artistSlot: Map<String, IntArray>,
        val genreSlot: Map<String, IntArray>,
        val slotTotals: IntArray,
        val totalListens: Int,
        val totalHearts: Int,
        val heatmap: Array<LongArray>,
        val coocEdges: Int,
        val artistDisplay: Map<String, String>
    ) {
        fun affinity(id: Long): Float = byId[id]?.affinity ?: 0f
        fun scored(id: Long): Scored? = byId[id]

        /** How much signal there is overall — shelves and the station gate on this honestly. */
        val signalCount: Int get() = totalListens + totalHearts

        /**
         * Time-of-day fit (0..1) for a track in day-part [slot]: lift of its artist (and genre)
         * in that slot over the owner's baseline slot share. Needs a few listens of that artist
         * to say anything (else 0) — a single evening play is not a pattern.
         */
        fun timeFit(f: Features, slot: Int): Float {
            val slotTotal = slotTotals[slot]
            val all = slotTotals.sum()
            if (all < 10 || slotTotal == 0) return 0f
            val baseline = slotTotal.toFloat() / all
            fun lift(counts: IntArray?, minTotal: Int): Float {
                if (counts == null) return 0f
                val tot = counts.sum()
                if (tot < minTotal) return 0f
                val share = counts[slot].toFloat() / tot
                val l = share / baseline
                return ((l - 1f) / 1.5f).coerceIn(0f, 1f)
            }
            val a = lift(artistSlot[f.artistKey], 3)
            val g = if (f.genreKey.isNotBlank()) lift(genreSlot[f.genreKey], 6) else 0f
            val own = lift(f.slotCounts, 3)
            return (0.5f * a + 0.2f * g + 0.3f * own).coerceIn(0f, 1f)
        }

        fun timeFit(id: Long, slot: Int = TasteDb.currentSlot()): Float = byId[id]?.let { timeFit(it.f, slot) } ?: 0f
    }

    private const val RECENCY_TAG = "recency"
    private const val CACHE_TTL_MS = 5L * 60_000L

    @Volatile private var cached: Snapshot? = null
    @Volatile private var dirty = true

    fun invalidate() { dirty = true }

    /** Last built snapshot (may be stale or null). UI reads this synchronously. */
    fun peek(): Snapshot? = cached

    /** Snapshot for the current library, rebuilding off-thread when stale. */
    suspend fun snapshot(ctx: Context, tracks: List<Track>? = null): Snapshot? {
        val lib = tracks ?: FastLibraryStore.loadSync(ctx) ?: return cached
        if (lib.isEmpty()) return cached
        val c = cached
        val now = System.currentTimeMillis()
        if (c != null && !dirty && now - c.builtAt < CACHE_TTL_MS && c.tracks.size == lib.size) return c
        return withContext(Dispatchers.Default) { build(ctx, lib) }
    }

    /** Blocking build — background threads only. */
    fun build(ctx: Context, tracks: List<Track>): Snapshot {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        LikeStore.init(app)
        val db = TasteDb.get(app)
        runCatching { GenreIndex.ensureFresh(app, tracks) }
        val agg = db.trackAggregates(now)
        val slotTotals = db.slotTotals()
        val heat = db.hourHeatmap()
        val totalListens = agg.values.sumOf { it.listens }

        // ---- per-track features ----
        val feats = ArrayList<Features>(tracks.size)
        val artistDisplay = HashMap<String, String>()
        var hearts = 0
        for (t in tracks) {
            val aKey = artistKey(t)
            artistDisplay.putIfAbsent(aKey, t.artist)
            val a = agg[t.id]
            val heart = LikeStore.heartAffinity(app, t.id)
            val liked = LikeStore.isLiked(t.id)
            if (liked) hearts++
            val lp = maxOf(PlayerPreferences.loadLastPlayedAt(app, t.id), a?.lastEnd ?: 0L)
            val mime = t.mime.lowercase(); val path = t.path.lowercase()
            val lossless = mime.contains("flac") || mime.contains("wav") || mime.contains("alac") || mime.contains("aiff") ||
                path.endsWith(".flac") || path.endsWith(".wav") || path.endsWith(".aiff") || path.endsWith(".ape") || path.endsWith(".dsf") || path.endsWith(".dff")
            val genre = GenreIndex.genreOf(app, t)
            feats.add(
                Features(
                    track = t, artistKey = aKey, albumKey = albumKey(t), genre = genre, genreKey = GenreIndex.key(genre),
                    startCount = PlayerPreferences.loadPlayCount(app, t.id),
                    listens = a?.listens ?: 0, completed = a?.completed ?: 0, skips = a?.skips ?: 0,
                    avgFraction = if (a != null && a.listens > 0) a.fractionSum / a.listens else 0f,
                    recent30 = a?.recent30 ?: 0, lastPlayedAt = lp, heart = heart, isLiked = liked,
                    slotCounts = a?.slotCounts ?: IntArray(6),
                    lossless = lossless, hiRes = lossless && t.bitrateKbps >= 1800
                )
            )
        }

        // ---- context aggregates (artist / album / genre) ----
        val artistPlays = HashMap<String, Float>(); val artistHeart = HashMap<String, Float>(); val artistN = HashMap<String, Int>()
        val albumPlays = HashMap<String, Float>(); val albumHeart = HashMap<String, Float>(); val albumN = HashMap<String, Int>()
        val genrePlays = HashMap<String, Float>(); val genreHeart = HashMap<String, Float>(); val genreN = HashMap<String, Int>()
        val artistListens = HashMap<String, Int>(); val genreListens = HashMap<String, Int>()
        val artistMs = HashMap<String, Long>(); val genreMs = HashMap<String, Long>()
        val artistSlot = HashMap<String, IntArray>(); val genreSlot = HashMap<String, IntArray>()
        for (f in feats) {
            val plays = f.effectivePlays
            artistPlays.merge(f.artistKey, plays, Float::plus); artistHeart.merge(f.artistKey, f.heart, Float::plus); artistN.merge(f.artistKey, 1, Int::plus)
            albumPlays.merge(f.albumKey, plays, Float::plus); albumHeart.merge(f.albumKey, f.heart, Float::plus); albumN.merge(f.albumKey, 1, Int::plus)
            val a = agg[f.track.id]
            if (a != null) {
                artistListens.merge(f.artistKey, a.listens - a.skips, Int::plus)
                artistMs.merge(f.artistKey, a.listenedMs, Long::plus)
                val sa = artistSlot.getOrPut(f.artistKey) { IntArray(6) }
                for (i in 0 until 6) sa[i] += a.slotCounts[i]
            }
            if (f.genreKey.isNotBlank()) {
                genrePlays.merge(f.genreKey, plays, Float::plus); genreHeart.merge(f.genreKey, f.heart, Float::plus); genreN.merge(f.genreKey, 1, Int::plus)
                if (a != null) {
                    genreListens.merge(f.genreKey, a.listens - a.skips, Int::plus)
                    genreMs.merge(f.genreKey, a.listenedMs, Long::plus)
                    val sg = genreSlot.getOrPut(f.genreKey) { IntArray(6) }
                    for (i in 0 until 6) sg[i] += a.slotCounts[i]
                }
            }
        }
        // Aggregate affinity: saturating play volume + mean heart intensity, plus an explicit
        // artist/album like read straight from LikeStore.
        fun aggAffinity(plays: Float, heartSum: Float, n: Int, explicit: Boolean): Float {
            val vol = 1f - exp(-plays / 12f)
            val heart = (heartSum / n.coerceAtLeast(1)) * 2f
            return maxOf(if (explicit) 0.85f else 0f, (0.6f * vol + 0.4f * heart).coerceIn(0f, 1f))
        }
        val artistAffinity = HashMap<String, Float>()
        for ((k, p) in artistPlays) {
            val name = artistDisplay[k] ?: k
            artistAffinity[k] = aggAffinity(p, artistHeart[k] ?: 0f, artistN[k] ?: 1, LikeStore.isArtistLiked(name, app))
        }
        val albumAffinity = HashMap<String, Float>()
        val albumExplicit = HashMap<String, Boolean>()
        for (f in feats) if (!albumExplicit.containsKey(f.albumKey)) {
            val aa = f.track.albumArtist.ifBlank { f.track.artist }
            albumExplicit[f.albumKey] = LikeStore.isAlbumLiked(aa, f.track.album, app)
        }
        for ((k, p) in albumPlays) albumAffinity[k] = aggAffinity(p, albumHeart[k] ?: 0f, albumN[k] ?: 1, albumExplicit[k] == true)
        val genreAffinity = HashMap<String, Float>()
        for ((k, p) in genrePlays) genreAffinity[k] = aggAffinity(p, genreHeart[k] ?: 0f, genreN[k] ?: 1, false)

        // ---- per-track affinity + reasons ----
        val byId = HashMap<Long, Scored>(feats.size * 2)
        for (f in feats) {
            val reasons = ArrayList<Reason>(6)
            // Hearts (max 0.34)
            if (f.heart > 0f) {
                val n = LikeStore.heartCount(app, f.track.id)
                reasons.add(Reason(0.34f * f.heart, if (n > 1) "hearted ${n}×" else "you hearted this"))
            }
            // Plays (max 0.26)
            val plays = f.effectivePlays
            if (plays > 0f) {
                val s = 1f - exp(-plays / 4f)
                val label = if (f.completed > 0) "played through ${f.completed}×" else "played ${f.startCount}×"
                reasons.add(Reason(0.26f * s, label))
            }
            // Completion (max 0.12) / skip penalty
            if (f.listens > 0) {
                if (f.avgFraction >= 0.75f) reasons.add(Reason(0.12f * f.avgFraction, "you usually let it play out"))
                if (f.skipRatio > 0.4f) reasons.add(Reason(-0.25f * f.skipRatio, "often skipped"))
            }
            // Context (max 0.10 artist, 0.05 album, 0.05 genre)
            val aAff = artistAffinity[f.artistKey] ?: 0f
            if (aAff >= 0.25f) reasons.add(Reason(0.10f * aAff, "you play ${f.track.artist} a lot"))
            val alAff = albumAffinity[f.albumKey] ?: 0f
            if (alAff >= 0.3f && f.track.album.isNotBlank()) reasons.add(Reason(0.05f * alAff, "from an album you love"))
            val gAff = genreAffinity[f.genreKey] ?: 0f
            if (gAff >= 0.3f && f.genre.isNotBlank()) reasons.add(Reason(0.05f * gAff, "${f.genre} is one of your genres"))
            // Recency (max 0.08) — tagged so baseAffinity can strip it.
            if (f.lastPlayedAt > 0L) {
                val days = (now - f.lastPlayedAt).coerceAtLeast(0L) / 86_400_000f
                val r = exp(-days / 45f)
                if (r > 0.05f) reasons.add(Reason(0.08f * r, RECENCY_TAG))
            }
            val aff = reasons.sumOf { it.weight.toDouble() }.toFloat().coerceIn(0f, 1f)
            byId[f.track.id] = Scored(f, aff, reasons.sortedByDescending { it.weight })
        }

        val snap = Snapshot(
            builtAt = now, tracks = tracks, byId = byId,
            artistAffinity = artistAffinity, albumAffinity = albumAffinity, genreAffinity = genreAffinity,
            artistListens = artistListens, genreListens = genreListens,
            artistListenedMs = artistMs, genreListenedMs = genreMs,
            artistSlot = artistSlot, genreSlot = genreSlot, slotTotals = slotTotals,
            totalListens = totalListens, totalHearts = hearts, heatmap = heat,
            coocEdges = db.coocEdgeCount(), artistDisplay = artistDisplay
        )
        cached = snap
        dirty = false
        return snap
    }

    // ---- explanations ------------------------------------------------------------------------

    /** Human "why this?" line for a track, from its top contributing reasons. "" if no signal. */
    fun whyThis(scored: Scored?, extra: List<String> = emptyList()): String {
        if (scored == null) return extra.joinToString(" · ")
        val parts = scored.reasons.filter { it.weight > 0.02f && it.text != RECENCY_TAG }.map { it.text }.take(2) + extra
        return parts.distinct().take(3).joinToString(" · ")
    }

    /** Short label for an affinity value. */
    fun label(aff: Float): String = when {
        aff >= 0.8f -> "Loved"
        aff >= 0.55f -> "Strong"
        aff >= 0.3f -> "Some"
        aff > 0.05f -> "Slight"
        else -> "No signal yet"
    }

    // ---- keys ---------------------------------------------------------------------------------

    /** Cheap grouping key (the canonical NFD/regex key in Model.kt is too slow for 10k tracks
     *  on every rebuild; this is only used to aggregate, never to display). */
    fun artistKey(t: Track): String = t.artist.trim().lowercase()
    fun artistKey(name: String): String = name.trim().lowercase()
    fun albumKey(t: Track): String = (t.albumArtist.ifBlank { t.artist }).trim().lowercase() + "|" + t.album.trim().lowercase()

    /** Year distance similarity: same year 1.0, falls off over ~12 years; 0 when either unknown. */
    fun yearSim(a: Int, b: Int): Float {
        if (a <= 0 || b <= 0) return 0f
        val d = kotlin.math.abs(a - b)
        return exp(-d / 8f)
    }

    /** log-scale helper for skew-heavy counts. */
    fun logScale(x: Float, k: Float = 1f): Float = if (x <= 0f) 0f else ln(1f + x / k)
}
