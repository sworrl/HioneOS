package com.hione.player

import android.content.Context
import android.provider.MediaStore

/** Lean, standalone library query for alarm playback — deliberately NOT reusing MainActivity's
 *  full queryTracks() (track-number inference, bitrate lookups, etc.) since none of that matters
 *  for "what plays when the alarm fires"; this only needs enough fields to build MediaItems. Runs
 *  from [[AlarmRingService]], which has no Activity to query through.
 *
 *  Prefers the on-disk [[FastLibraryStore]] snapshot (same rows the app itself plays, including
 *  virtual CUE-sheet tracks) and falls back to a direct MediaStore query when there's no snapshot
 *  yet (fresh install, library never scanned). */
object AlarmLibrary {
    /** Ordered queue for the alarm. Empty ONLY when the library itself is empty — a vanished
     *  artist/album/playlist/track falls back to the whole library rather than silence. Callers
     *  handle [[AlarmSource.HIONE_CHIME]] themselves (no library involved). */
    fun tracksFor(ctx: Context, source: AlarmSource, sourceRef: String?): List<Track> {
        val all = allTracks(ctx)
        if (all.isEmpty()) return emptyList()
        val picked: List<Track> = when (source) {
            AlarmSource.HIONE_CHIME -> emptyList()
            AlarmSource.SHUFFLE_ALL -> all.shuffled()
            AlarmSource.DAILY_MIX -> runCatching {
                DailyHighlightEngine.generateDailyHighlight(ctx, all).tracks
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: all.shuffled()
            AlarmSource.LIKED_SONGS -> {
                val liked = PlayerPreferences.loadLikedTracks(ctx)
                all.filter { it.id in liked }.shuffled()
            }
            AlarmSource.ARTIST -> all.filter { it.artist.equals(sourceRef, ignoreCase = true) || it.albumArtist.equals(sourceRef, ignoreCase = true) }.shuffled()
            // Album plays in disc/track order — an album alarm should start at track 1, not a random cut.
            AlarmSource.ALBUM -> all.filter { it.album.equals(sourceRef, ignoreCase = true) }
                .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title }))
            AlarmSource.PLAYLIST -> {
                val ids = sourceRef?.let { PlayerPreferences.loadPlaylists(ctx)[it] } ?: emptyList()
                val byId = all.associateBy { it.id }
                ids.mapNotNull { byId[it] } // keeps the playlist's own order
            }
            AlarmSource.TRACK -> {
                val id = sourceRef?.toLongOrNull()
                val t = all.firstOrNull { it.id == id }
                // A single track loops (AlarmRingService sets REPEAT_MODE_ALL); nothing else queued.
                if (t != null) listOf(t) else emptyList()
            }
        }
        return picked.ifEmpty { all.shuffled() }
    }

    /** Human label for an alarm's sound, for the Settings list ("Liked Songs", "Album: X", ...). */
    fun describeSource(source: AlarmSource, sourceRef: String?, tracks: List<Track>? = null): String = when (source) {
        AlarmSource.HIONE_CHIME -> "${Mascot.name} Chime"
        AlarmSource.SHUFFLE_ALL -> "Shuffle library"
        AlarmSource.LIKED_SONGS -> "Liked Songs"
        AlarmSource.DAILY_MIX -> "Daily Mix"
        AlarmSource.ARTIST -> "Artist: ${sourceRef ?: "?"}"
        AlarmSource.ALBUM -> "Album: ${sourceRef ?: "?"}"
        AlarmSource.PLAYLIST -> "Playlist: ${sourceRef ?: "?"}"
        AlarmSource.TRACK -> {
            val id = sourceRef?.toLongOrNull()
            val t = tracks?.firstOrNull { it.id == id }
            if (t != null) "Track: ${t.title} · ${t.artist}" else "Track #${sourceRef ?: "?"}"
        }
    }

    private fun allTracks(ctx: Context): List<Track> {
        val cached = runCatching { FastLibraryStore.loadSync(ctx) }.getOrNull()
        if (!cached.isNullOrEmpty()) return cached
        return queryAll(ctx)
    }

    private fun queryAll(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ALBUM_ID
        )
        ctx.contentResolver.safeQuery(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iS = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iM = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iPath = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val iAlbumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val path = if (iPath >= 0 && !c.isNull(iPath)) c.getString(iPath) ?: "" else ""
                if (!mediaStoreRowLikelyValid(path)) continue // same ghost-row guard as MainActivity.queryTracks (see Model.kt doc comment — SD-card paths are trusted, not File-checked)
                out.add(
                    Track(
                        c.getLong(iId), c.getString(iT) ?: "Unknown", c.getString(iA) ?: "Unknown artist",
                        c.getString(iAl) ?: "", c.getLong(iD), c.getLong(iS),
                        0, c.getString(iM) ?: "", path, 0,
                        if (iAlbumId >= 0 && !c.isNull(iAlbumId)) c.getLong(iAlbumId) else 0L
                    )
                )
            }
        }
        return out
    }
}
