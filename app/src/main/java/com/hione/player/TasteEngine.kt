package com.hione.player

import android.content.Context

/**
 * Local, on-device taste/affinity scoring — the "DNA" the three like tiers (LikeStore) build up.
 * Deliberately NOT a persisted feature store or a trained model: every score here is a cheap
 * weighted formula computed live off three tiny boolean sets (a handful of liked tracks/albums/
 * artists) plus data already sitting in memory (the current track/album/artist lists) — "neural
 * smart, IO easy," per the design brief. Nothing here writes to disk; LikeStore already owns the
 * only persisted state this needs.
 *
 * The idea: liking things at different tiers is correlated signal, not independent facts. Liking
 * a Track says a little about the Album and Artist it's on. Liking an Album says a LOT about its
 * Tracks (you didn't say you love all 12 songs individually, but you basically meant it) and a fair
 * amount about the Artist. Liking an Artist says a little about everything they've made. None of
 * this cascades into LikeStore's actual booleans — a liked album never flips individual track
 * hearts (that's a deliberate, separate design choice, see LikeStore's doc comment) — but it DOES
 * feed a continuous 0..1 "affinity" score per entity, which is what a future recommendation/
 * highlight feature would actually rank on, instead of just the boolean "is this exact thing
 * liked."
 *
 * Weights are tuned so a single explicit like at any tier is a strong signal on its own, and
 * alignment across tiers (you liked the song AND its album AND you like the artist) saturates
 * affinity at 1.0 well before "liked literally everything" — the whole point is that the more
 * signals point the same direction, the more confident the score gets.
 */
object TasteEngine {
    // How much liking a TRACK implies about the album/artist it belongs to.
    private const val TRACK_TO_ALBUM = 0.35f
    private const val TRACK_TO_ARTIST = 0.15f
    // How much liking an ALBUM implies about its tracks/artist.
    private const val ALBUM_TO_TRACK = 0.5f
    private const val ALBUM_TO_ARTIST = 0.4f
    // How much liking an ARTIST implies about their albums/tracks.
    private const val ARTIST_TO_ALBUM = 0.25f
    private const val ARTIST_TO_TRACK = 0.1f

    /**
     * Normalised cumulative heart strength for a track (0..1), saturating at ~10 hearts. This is
     * the new per-play heart score (LikeStore.heartCount) folded into the taste model — a track
     * you played through and hearted many times outranks one hearted once, which is exactly what
     * feeds smart shuffle / recommendations.
     */
    fun heartScore(ctx: Context, id: Long): Float = LikeStore.heartAffinity(ctx, id)

    /** Heart-weighted fraction of an entity's tracks: each track contributes by heart intensity
     *  (min 3 hearts = full weight) rather than a flat liked/not-liked, so heavily-hearted tracks
     *  pull their album/artist affinity up more. Falls back to the boolean count without a ctx. */
    private fun heartWeightedFrac(tracks: List<Track>, ctx: Context?): Float {
        if (tracks.isEmpty()) return 0f
        if (ctx == null) return tracks.count { LikeStore.isLiked(it.id) }.toFloat() / tracks.size
        var acc = 0f
        for (t in tracks) acc += LikeStore.heartAffinity(ctx, t.id)   // weighted by how-much + when
        return (acc / tracks.size).coerceIn(0f, 1f)
    }

    /** 1.0 if explicitly liked; otherwise the implied score from its album/artist likes. Ranking
     *  consumers additionally read [heartScore] to break ties between two liked tracks. */
    fun trackAffinity(track: Track, ctx: Context? = null): Float {
        if (LikeStore.isLiked(track.id)) return 1f
        val albumArtist = track.albumArtist.ifBlank { track.artist }
        var score = 0f
        if (LikeStore.isAlbumLiked(albumArtist, track.album, ctx)) score += ALBUM_TO_TRACK
        if (LikeStore.isArtistLiked(track.artist, ctx)) score += ARTIST_TO_TRACK
        return score.coerceIn(0f, 1f)
    }

    /** 1.0 if explicitly liked; otherwise implied from the artist's like plus how much of the
     *  album's own tracks are individually liked (proportional — 3 of 12 liked pulls less than
     *  10 of 12). */
    fun albumAffinity(album: AlbumGroup, ctx: Context? = null): Float {
        if (LikeStore.isAlbumLiked(album.artist, album.name, ctx)) return 1f
        var score = 0f
        if (LikeStore.isArtistLiked(album.artist, ctx)) score += ARTIST_TO_ALBUM
        if (album.tracks.isNotEmpty()) {
            score += heartWeightedFrac(album.tracks, ctx) * TRACK_TO_ALBUM
        }
        return score.coerceIn(0f, 1f)
    }

    /** 1.0 if explicitly liked; otherwise implied from what fraction of their tracks are liked and
     *  whether any of their albums are liked outright. */
    fun artistAffinity(artist: ArtistGroup, ctx: Context? = null): Float {
        if (LikeStore.isArtistLiked(artist.name, ctx)) return 1f
        var score = 0f
        if (artist.tracks.isNotEmpty()) {
            val likedFrac = heartWeightedFrac(artist.tracks, ctx)
            score += likedFrac * TRACK_TO_ARTIST
        }
        val albumNames = artist.tracks.map { it.album }.distinct()
        if (albumNames.any { LikeStore.isAlbumLiked(artist.name, it, ctx) }) score += ALBUM_TO_ARTIST
        return score.coerceIn(0f, 1f)
    }

    /** Short human label for a score — used anywhere the raw float would be meaningless to show. */
    fun label(score: Float): String = when {
        score >= 0.999f -> "Loved"
        score >= 0.6f -> "Strong affinity"
        score >= 0.3f -> "Some affinity"
        score > 0f -> "Slight affinity"
        else -> "No signal yet"
    }

    // ---- Full local model (taste/ package) ------------------------------------------------------
    // The functions above are the cheap, live, hearts-only view. The taste/ package layers the
    // rest of the on-device signal on top — completed plays, skips, completion, recency, session
    // co-occurrence, time-of-day — into a single explainable affinity (TasteModel), the "For You"
    // shelf (TasteShelves / ForYouShelf), Hione Radio station mode (StationEngine) and smart shuffle
    // (SmartShuffle). Player events reach it through ONE hook object (TasteHooks, called from
    // PlayerHolder's Player.Listener). These bridges keep TasteEngine the single entry point.

    /** Model affinity 0..1 for a track from the last built snapshot (0 until one exists). Cheap:
     *  a map lookup; the snapshot itself is built off-thread by [modelSnapshot]. */
    fun modelAffinity(id: Long): Float = com.hione.player.taste.TasteModel.peek()?.affinity(id) ?: 0f

    /** Best available affinity: the full model when a snapshot exists, else the hearts-only view. */
    fun bestAffinity(track: Track, ctx: Context? = null): Float {
        val snap = com.hione.player.taste.TasteModel.peek() ?: return trackAffinity(track, ctx)
        return maxOf(snap.affinity(track.id), trackAffinity(track, ctx) * 0.6f)
    }

    /** Human "why this?" line for a track ("" when there is no signal — never invented). */
    fun whyThis(id: Long): String = com.hione.player.taste.TasteModel.whyThis(com.hione.player.taste.TasteModel.peek()?.scored(id))

    /** Time-of-day fit 0..1 for the current day-part (0 without enough listens to say). */
    fun timeFit(id: Long): Float = com.hione.player.taste.TasteModel.peek()?.timeFit(id) ?: 0f

    /** Build/refresh the model snapshot for [tracks] off the main thread. */
    suspend fun modelSnapshot(ctx: Context, tracks: List<Track>) = com.hione.player.taste.TasteModel.snapshot(ctx, tracks)

    /** Mark the model stale (a listen landed, a heart changed) so the next reader rebuilds. */
    fun invalidateModel() = com.hione.player.taste.TasteModel.invalidate()
}
