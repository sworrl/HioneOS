package com.hione.player

import android.content.Context
import java.util.Random

/**
 * Intelligent Daily Highlight & Mix recommendation engine for Hione Music.
 * Generates a curated, balanced playlist of 30 to 50 tracks every day that "gets smarter"
 * as play counts, likes, and listening timestamps build up in the local database.
 */
object DailyHighlightEngine {

    enum class TrackCategory {
        FAVORITE,
        AFFINITY_DISCOVERY,
        FORGOTTEN_GEM,
        FRESH_DISCOVERY
    }

    private data class ScoredTrack(
        val track: Track,
        val favScore: Double,
        val discScore: Double,
        val gemScore: Double,
        val compositeScore: Double,
        val category: TrackCategory
    )

    data class HighlightResult(
        val tracks: List<Track>,
        val favCount: Int,
        val discCount: Int,
        val gemCount: Int
    )

    fun generateDailyHighlight(context: Context, allTracks: List<Track>, targetCount: Int = 40): HighlightResult {
        if (allTracks.isEmpty()) return HighlightResult(emptyList(), 0, 0, 0)
        val count = targetCount.coerceIn(30, 50).coerceAtMost(allTracks.size)
        if (allTracks.size <= count) return HighlightResult(allTracks, 0, allTracks.size, 0)

        val now = System.currentTimeMillis()
        val epochDay = now / (1000 * 86400) // stable daily epoch seed

        // 1. Collect user preference signals from local DB
        val likedIds = LikeStore.liked.toSet()
        val likedAlbums = PlayerPreferences.loadLikedAlbums(context)
        val likedArtists = PlayerPreferences.loadLikedArtists(context)

        val artistPlayCounts = mutableMapOf<String, Int>()
        val trackPlayCounts = mutableMapOf<Long, Int>()
        val trackLastPlayed = mutableMapOf<Long, Long>()

        allTracks.forEach { t ->
            val pc = PlayerPreferences.loadPlayCount(context, t.id)
            if (pc > 0) {
                trackPlayCounts[t.id] = pc
                val aKey = t.artist.trim().lowercase()
                artistPlayCounts[aKey] = (artistPlayCounts[aKey] ?: 0) + pc
            }
            val lp = PlayerPreferences.loadLastPlayedAt(context, t.id)
            if (lp > 0) trackLastPlayed[t.id] = lp
        }

        // 2. Score every track on multi-dimensional axes
        // PERF: the daily jitter below used to build a brand-new java.util.Random PER TRACK — on an
        // 11k-track library that is 11k Random objects (each wrapping an AtomicLong) plus three
        // atomic CAS ops per nextDouble(), all inside the cold-start CPU burst. One instance is
        // re-seeded per track instead: Random.setSeed() applies the exact same initial scramble the
        // constructor does, so nextDouble() returns bit-identical values and today's mix is
        // unchanged. Safe to share because this is a function-local instance on one thread.
        val jitterRng = Random()
        val scoredTracks = allTracks.map { t ->
            val pc = trackPlayCounts[t.id] ?: 0
            val lp = trackLastPlayed[t.id] ?: 0L
            val isLiked = t.id in likedIds
            val isArtistLiked = likedArtists.contains(t.artist)
            val isAlbumLiked = likedAlbums.contains(t.album)
            val artistTotalPlays = artistPlayCounts[t.artist.trim().lowercase()] ?: 0

            // A. Favorite Affinity Signal
            var fav = 0.0
            if (isLiked) fav += 40.0
            if (isArtistLiked) fav += 16.0
            if (isAlbumLiked) fav += 10.0
            fav += (pc * 7.0).coerceAtMost(35.0)

            // B. Discovery Signal (unplayed/underplayed tracks by preferred or related artists)
            var disc = 0.0
            if (pc == 0) disc += 28.0
            else if (pc in 1..2) disc += 14.0
            if (artistTotalPlays > 0) {
                disc += (artistTotalPlays * 2.5).coerceAtMost(25.0)
            }

            // C. Forgotten Gem Signal (previously played, but not in last 7+ days)
            var gem = 0.0
            if (pc > 0 && lp > 0) {
                val daysSincePlayed = (now - lp) / (1000 * 86400)
                if (daysSincePlayed >= 7) {
                    gem += (daysSincePlayed * 2.0).coerceAtMost(40.0)
                }
            }

            // D. Recency Fatigue Penalty (played in the last 12 hours)
            var recencyPenalty = 0.0
            if (lp > 0 && (now - lp) < (12 * 3600 * 1000)) {
                recencyPenalty = 45.0
            }

            // E. Audiophile & Hi-Res Format Bonus
            val fmt = t.mime.substringAfterLast('/').lowercase()
            val audioBonus = when (fmt) {
                "dsd", "dff", "dsf" -> 8.0
                "flac", "x-flac", "wav", "x-wav" -> 5.0
                else -> 0.0
            }

            // F. Daily Deterministic Jitter (seeded by track ID + current date)
            jitterRng.setSeed(t.id xor (epochDay * 31L))
            val jitter = jitterRng.nextDouble() * 24.0

            val composite = (fav * 1.25) + (disc * 1.1) + (gem * 1.15) + audioBonus + jitter - recencyPenalty

            val category = when {
                fav >= 25.0 -> TrackCategory.FAVORITE
                gem >= 20.0 -> TrackCategory.FORGOTTEN_GEM
                disc >= 22.0 -> TrackCategory.AFFINITY_DISCOVERY
                else -> TrackCategory.FRESH_DISCOVERY
            }

            ScoredTrack(t, fav, disc, gem, composite, category)
        }

        // 3. Smart Stratified Bucket Quotas
        val favTarget = (count * 0.35).toInt().coerceAtLeast(4)
        val discTarget = (count * 0.35).toInt().coerceAtLeast(4)
        val gemTarget = (count * 0.20).toInt().coerceAtLeast(2)

        val favorites = scoredTracks.filter { it.category == TrackCategory.FAVORITE }.sortedByDescending { it.compositeScore }
        val discoveries = scoredTracks.filter { it.category == TrackCategory.AFFINITY_DISCOVERY }.sortedByDescending { it.compositeScore }
        val gems = scoredTracks.filter { it.category == TrackCategory.FORGOTTEN_GEM }.sortedByDescending { it.compositeScore }

        val selected = mutableListOf<ScoredTrack>()
        val artistCounts = mutableMapOf<String, Int>()
        // Canonical key, not a raw lowercase trim — a fragmented artist (several differently-
        // tagged collab strings for what's really one artist) was both inflating this diversity
        // count and, worse, letting the same real artist blow past MAX_PER_ARTIST by hiding
        // behind several different raw spellings that each got their own quota.
        // PERF: this used to materialize an 11k-element String list and then a second distinct()
        // list purely to compare the count against 15. Streamed into a HashSet with an early exit
        // the moment the threshold is reached — same MAX_PER_ARTIST decision, no bulk allocation.
        val artistKeySet = HashSet<String>()
        for (t in allTracks) {
            artistKeySet.add(canonicalArtistKey(t.artist, context))
            if (artistKeySet.size >= 15) break
        }
        val MAX_PER_ARTIST = if (artistKeySet.size >= 15) 2 else 4

        fun tryAdd(st: ScoredTrack): Boolean {
            val aKey = canonicalArtistKey(st.track.artist, context)
            val cur = artistCounts[aKey] ?: 0
            if (cur >= MAX_PER_ARTIST && selected.size < count - 4) return false
            if (selected.any { it.track.id == st.track.id }) return false
            selected.add(st)
            artistCounts[aKey] = cur + 1
            return true
        }

        favorites.forEach { if (selected.count { it.category == TrackCategory.FAVORITE } < favTarget) tryAdd(it) }
        discoveries.forEach { if (selected.count { it.category == TrackCategory.AFFINITY_DISCOVERY } < discTarget) tryAdd(it) }
        gems.forEach { if (selected.count { it.category == TrackCategory.FORGOTTEN_GEM } < gemTarget) tryAdd(it) }

        // Fill remaining quota with highest composite scores
        val remainingPool = (scoredTracks - selected.toSet()).sortedByDescending { it.compositeScore }
        for (st in remainingPool) {
            if (selected.size >= count) break
            tryAdd(st)
        }

        // Fallback fill if artist constraints were tight
        if (selected.size < count) {
            for (st in remainingPool) {
                if (selected.size >= count) break
                if (selected.none { it.track.id == st.track.id }) selected.add(st)
            }
        }

        // 4. Interleaved Sequencing (Daily Deterministic Flow)
        val finalTracks = selected.map { it.track }.toMutableList()
        val rng = Random(epochDay * 7919L)
        for (i in finalTracks.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val temp = finalTracks[i]
            finalTracks[i] = finalTracks[j]
            finalTracks[j] = temp
        }

        val fCount = selected.count { it.category == TrackCategory.FAVORITE }
        val dCount = selected.count { it.category == TrackCategory.AFFINITY_DISCOVERY || it.category == TrackCategory.FRESH_DISCOVERY }
        val gCount = selected.count { it.category == TrackCategory.FORGOTTEN_GEM }

        return HighlightResult(finalTracks, fCount, dCount, gCount)
    }
}
