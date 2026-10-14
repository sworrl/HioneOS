package com.hione.player.taste

import android.content.Context
import com.hione.player.Track
import java.util.Random

/**
 * The "For You" rows, each derived from the local model with an explicit, honest gate. When a
 * row doesn't have enough real signal behind it, it comes back LOCKED with a hint saying what
 * would unlock it — the shelf never pads a row with arbitrary picks to look full.
 *
 * Gates (also documented in the coordinator report):
 *   Heavy rotation       ≥ [HEAVY_MIN_PLAYS] completed plays in the last 30 days (falls back
 *                        to the legacy start counter + last-played while the listen log is
 *                        still short); needs [HEAVY_MIN_ITEMS] such tracks.
 *   Forgotten favourites base affinity ≥ [FORGOTTEN_MIN_AFFINITY] (hearted / played through
 *                        a lot) AND not played for ≥ [FORGOTTEN_DAYS] days; needs
 *                        [FORGOTTEN_MIN_ITEMS].
 *   Good right now       ≥ [GOOD_NOW_MIN_LISTENS] logged listens overall so day-part lift
 *                        means something; time fit ≥ [GOOD_NOW_MIN_FIT]; needs
 *                        [GOOD_NOW_MIN_ITEMS].
 *   Discover             ≥ [DISCOVER_MIN_SIGNAL] signals (listens + hearts); never-played
 *                        tracks whose artist/album/genre affinity ≥ [DISCOVER_MIN_CONTEXT];
 *                        needs [DISCOVER_MIN_ITEMS]. Rotates daily among the top candidates.
 *   Taste profile card   ≥ [PROFILE_MIN_SIGNAL] signals.
 */
object TasteShelves {
    const val HEAVY_MIN_PLAYS = 3
    const val HEAVY_MIN_ITEMS = 5
    const val FORGOTTEN_MIN_AFFINITY = 0.45f
    const val FORGOTTEN_DAYS = 60
    const val FORGOTTEN_MIN_ITEMS = 3
    const val GOOD_NOW_MIN_LISTENS = 20
    const val GOOD_NOW_MIN_FIT = 0.35f
    const val GOOD_NOW_MIN_ITEMS = 4
    const val DISCOVER_MIN_SIGNAL = 10
    const val DISCOVER_MIN_CONTEXT = 0.3f
    const val DISCOVER_MIN_ITEMS = 4
    const val PROFILE_MIN_SIGNAL = 10
    const val ROW_MAX = 16

    class Item(val track: Track, val why: String)
    class Row(val key: String, val title: String, val subtitle: String, val items: List<Item>, val unlockHint: String?) {
        val locked: Boolean get() = unlockHint != null
        val tracks: List<Track> get() = items.map { it.track }
    }
    class Result(val rows: List<Row>, val snapshot: TasteModel.Snapshot) {
        val signalCount: Int get() = snapshot.signalCount
        val profileReady: Boolean get() = signalCount >= PROFILE_MIN_SIGNAL
    }

    suspend fun compute(ctx: Context, tracks: List<Track>): Result? {
        if (tracks.isEmpty()) return null
        val snap = TasteModel.snapshot(ctx, tracks) ?: return null
        val now = System.currentTimeMillis()
        val slot = TasteDb.currentSlot()
        val all = snap.byId.values
        val rows = ArrayList<Row>(4)

        // ---- Heavy rotation ----
        val logShort = snap.totalListens < GOOD_NOW_MIN_LISTENS
        val heavy = all.asSequence().filter { s ->
            val f = s.f
            if (f.recent30 >= HEAVY_MIN_PLAYS) true
            else logShort && f.startCount >= HEAVY_MIN_PLAYS && f.lastPlayedAt > 0L && now - f.lastPlayedAt <= 30L * 86_400_000L
        }.sortedWith(compareByDescending<TasteModel.Scored> { it.f.recent30 }.thenByDescending { it.f.effectivePlays }.thenByDescending { it.affinity })
            .take(ROW_MAX).toList()
        rows.add(
            if (heavy.size >= HEAVY_MIN_ITEMS) Row(
                "heavy", "Your heavy rotation", "played the most these last 30 days",
                heavy.map { Item(it.f.track, heavyWhy(it)) }, null
            ) else Row(
                "heavy", "Your heavy rotation", "", emptyList(),
                "Listen more to unlock. ${heavy.size} of $HEAVY_MIN_ITEMS tracks played $HEAVY_MIN_PLAYS+ times this month"
            )
        )

        // ---- Forgotten favourites ----
        val cutoff = now - FORGOTTEN_DAYS * 86_400_000L
        val forgotten = all.asSequence().filter { s ->
            s.f.lastPlayedAt in 1 until cutoff && s.baseAffinity >= FORGOTTEN_MIN_AFFINITY
        }.sortedByDescending { it.baseAffinity }.take(ROW_MAX).toList()
        rows.add(
            if (forgotten.size >= FORGOTTEN_MIN_ITEMS) Row(
                "forgotten", "Forgotten favorites", "you loved these, not played in $FORGOTTEN_DAYS+ days",
                forgotten.map { Item(it.f.track, TasteModel.whyThis(it, listOf("last played ${daysAgo(now, it.f.lastPlayedAt)}"))) }, null
            ) else Row(
                "forgotten", "Forgotten favorites", "", emptyList(),
                if (snap.totalHearts + snap.totalListens < 5) "Heart and play things. Favorites you haven't touched in $FORGOTTEN_DAYS days show up here"
                else "Nothing qualifies yet. ${forgotten.size} of $FORGOTTEN_MIN_ITEMS favorites untouched for $FORGOTTEN_DAYS+ days"
            )
        )

        // ---- Good right now (time of day) ----
        val slotName = TasteDb.SLOT_NAMES[slot]
        val slotListens = snap.slotTotals[slot]
        if (snap.totalListens >= GOOD_NOW_MIN_LISTENS) {
            val good = all.asSequence().map { it to snap.timeFit(it.f, slot) }
                .filter { (s, tf) -> tf >= GOOD_NOW_MIN_FIT && s.affinity >= 0.15f }
                .sortedByDescending { (s, tf) -> tf * (0.5f + s.affinity) }
                .take(ROW_MAX).toList()
            rows.add(
                if (good.size >= GOOD_NOW_MIN_ITEMS) Row(
                    "goodnow", "Good right now", "what you tend to play in the $slotName · $slotListens listens in this slot",
                    good.map { (s, _) -> Item(s.f.track, TasteModel.whyThis(s, listOf("you play ${s.f.track.artist} in the $slotName"))) }, null
                ) else Row(
                    "goodnow", "Good right now", "", emptyList(),
                    "No clear $slotName pattern yet. ${good.size} of $GOOD_NOW_MIN_ITEMS tracks lean into this time of day"
                )
            )
        } else {
            rows.add(Row("goodnow", "Good right now", "", emptyList(),
                "Listen more to unlock. ${snap.totalListens} of $GOOD_NOW_MIN_LISTENS listens logged for time-of-day patterns"))
        }

        // ---- Discover in your library ----
        if (snap.signalCount >= DISCOVER_MIN_SIGNAL) {
            val day = now / 86_400_000L
            val rnd = Random(day)
            val pool = all.asSequence().filter { s ->
                val f = s.f
                !f.everPlayed && !(f.track.isDiscImage && f.track.parentId == 0L) && f.track.durationMs <= 20L * 60_000L
            }.map { s ->
                val f = s.f
                val a = snap.artistAffinity[f.artistKey] ?: 0f
                val al = snap.albumAffinity[f.albumKey] ?: 0f
                val g = if (f.genreKey.isNotBlank()) (snap.genreAffinity[f.genreKey] ?: 0f) * 0.8f else 0f
                val best = maxOf(a, al, g)
                val why = when (best) {
                    a -> "new to you · you play ${f.track.artist} a lot"
                    al -> "new to you · from an album you love"
                    else -> "new to you · ${f.genre} is one of your genres"
                }
                Triple(s, best, why)
            }.filter { it.second >= DISCOVER_MIN_CONTEXT }
                .sortedByDescending { it.second }.take(60).toList()
            // Daily rotation among the qualified pool, at most two per artist so one big
            // discography doesn't fill the row.
            val shuffled = pool.shuffled(rnd)
            val perArtist = HashMap<String, Int>()
            val disc = ArrayList<Triple<TasteModel.Scored, Float, String>>()
            for (c in shuffled) {
                val k = c.first.f.artistKey
                if ((perArtist[k] ?: 0) >= 2) continue
                perArtist[k] = (perArtist[k] ?: 0) + 1
                disc.add(c); if (disc.size >= ROW_MAX) break
            }
            rows.add(
                if (disc.size >= DISCOVER_MIN_ITEMS) Row(
                    "discover", "Discover in your library", "never played, by artists, albums and genres you already love",
                    disc.map { Item(it.first.f.track, it.third) }, null
                ) else Row("discover", "Discover in your library", "", emptyList(),
                    "Nothing unplayed left near your taste (${disc.size} of $DISCOVER_MIN_ITEMS candidates)")
            )
        } else {
            rows.add(Row("discover", "Discover in your library", "", emptyList(),
                "Listen more to unlock. ${snap.signalCount} of $DISCOVER_MIN_SIGNAL plays/hearts needed"))
        }

        return Result(rows, snap)
    }

    private fun heavyWhy(s: TasteModel.Scored): String {
        val f = s.f
        val base = if (f.recent30 > 0) "${f.recent30}× this month" else "${f.startCount}× played"
        return TasteModel.whyThis(s, listOf(base)).let { w -> if (w.startsWith(base)) w else "$base · $w".trimEnd(' ', '·') }
    }

    fun daysAgo(now: Long, then: Long): String {
        val d = ((now - then) / 86_400_000L).toInt()
        return when {
            d < 1 -> "today"
            d < 30 -> "${d}d ago"
            d < 365 -> "${d / 30}mo ago"
            else -> "${d / 365}y ago"
        }
    }

    // ---- profile helpers ----------------------------------------------------------------------

    class Share(val name: String, val fraction: Float, val detail: String)

    /** Top genres by listened time (falls back to play volume while the listen log is short). */
    fun topGenres(snap: TasteModel.Snapshot, n: Int = 6): List<Share> {
        val byMs = snap.genreListenedMs
        val totalMs = byMs.values.sum()
        if (totalMs > 0L) {
            return byMs.entries.sortedByDescending { it.value }.take(n).map { (k, ms) ->
                Share(displayGenre(snap, k), ms.toFloat() / totalMs, "${ms / 60_000L} min")
            }
        }
        // Fallback: effective plays per genre from the per-track features.
        val plays = HashMap<String, Float>()
        for (s in snap.byId.values) if (s.f.genreKey.isNotBlank()) plays.merge(s.f.genreKey, s.f.effectivePlays, Float::plus)
        val tot = plays.values.sum()
        if (tot <= 0f) return emptyList()
        return plays.entries.sortedByDescending { it.value }.take(n).map { (k, p) -> Share(displayGenre(snap, k), p / tot, "${Math.round(p)} plays") }
    }

    fun topArtists(snap: TasteModel.Snapshot, n: Int = 6): List<Share> {
        val byMs = snap.artistListenedMs
        val totalMs = byMs.values.sum()
        if (totalMs > 0L) {
            return byMs.entries.sortedByDescending { it.value }.take(n).map { (k, ms) ->
                Share(snap.artistDisplay[k] ?: k, ms.toFloat() / totalMs, "${ms / 60_000L} min")
            }
        }
        val plays = HashMap<String, Float>()
        for (s in snap.byId.values) plays.merge(s.f.artistKey, s.f.effectivePlays, Float::plus)
        val tot = plays.values.sum()
        if (tot <= 0f) return emptyList()
        return plays.entries.sortedByDescending { it.value }.take(n).map { (k, p) -> Share(snap.artistDisplay[k] ?: k, p / tot, "${Math.round(p)} plays") }
    }

    private fun displayGenre(snap: TasteModel.Snapshot, key: String): String =
        snap.byId.values.firstOrNull { it.f.genreKey == key }?.f?.genre?.ifBlank { key } ?: key
}
