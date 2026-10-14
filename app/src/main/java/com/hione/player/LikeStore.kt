package com.hione.player

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf

/**
 * Single reactive source of truth for liked tracks/albums/artists so every heart (rows, mini-bar,
 * now-playing, artist/album headers) stays in sync. Backed by Compose snapshot lists, persisted
 * via PlayerPreferences.
 *
 * Three independent tiers, deliberately NOT cascaded into each other's boolean state — liking an
 * album does not flip every track's heart, liking a track does not flip its album's heart. Each
 * tier is its own explicit signal. What DOES combine them is [TasteEngine], which reads all three
 * tiers to compute a soft, continuous affinity score per track/album/artist (an album you liked
 * implies you probably like its tracks somewhat, even ones you never individually hearted — see
 * that file for the actual weighting). This file only owns the three raw boolean signals.
 *
 * Albums/artists are keyed by [canonicalAlbumKey]/[canonicalArtistKey] (Model.kt) rather than raw
 * display strings — display names are formatted (and for artists, user-configurable via the
 * "ignore The" setting), so keying likes on them directly meant a like could silently orphan
 * itself the moment the display mode changed, and two different artists' identically-titled
 * albums would collide onto one shared like. (2026-08-17: switched from raw-name keys to this —
 * existing album/artist likes from before this change won't carry over, tracks aren't affected.)
 */
object LikeStore {
    val liked = mutableStateListOf<Long>()

    /** All SnapshotStateList mutations must happen on the MAIN thread and NEVER during
     *  composition. A state list mutated from a binder/IO thread while a composable pass also
     *  touched it crashes the Recomposer ("Unsupported concurrent change during composition" -
     *  crash-looped the app 2026-09-10 when resolveLiked's self-heal ran inside remember{}). */
    private fun mutateOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
    val likedAlbums = mutableStateListOf<String>()   // canonicalAlbumKey values
    /**
     * Tracks explicitly REFUSED. Liking an album means liking everything on it, UNLESS the user
     * said otherwise about one track, and this is where "otherwise" lives. It is deliberately not
     * the same thing as "absent from [liked]": absent means no opinion and follows the album, while
     * refused survives the album being un-liked and re-liked.
     */
    val refusedTracks = mutableStateListOf<Long>()
    val likedArtists = mutableStateListOf<String>()  // canonicalArtistKey values
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        // init is reached from binder threads too (media session custom commands) - the state
        // lists may only ever be mutated on main (see mutateOnMain).
        val t = PlayerPreferences.loadLikedTracks(ctx)
        val al = PlayerPreferences.loadLikedAlbums(ctx)
        val ar = PlayerPreferences.loadLikedArtists(ctx)
        val rf = PlayerPreferences.loadRefusedTracks(ctx)
        mutateOnMain {
            for (id in t) if (!liked.contains(id)) liked.add(id)
            for (k in al) if (!likedAlbums.contains(k)) likedAlbums.add(k)
            for (k in ar) if (!likedArtists.contains(k)) likedArtists.add(k)
            for (id in rf) if (!refusedTracks.contains(id)) refusedTracks.add(id)
        }
        recoverLikesAndHistoryAsync(ctx.applicationContext)
    }

    private data class MediaRow(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val path: String
    )

    private fun normLookup(s: String): String =
        s.lowercase()
            .replace(Regex("\\((feat|ft|from|with|prod|remix|live|bonus|single)[^)]*\\)"), "")
            .replace(Regex("\\[(feat|ft|from|with|prod|remix|live|bonus|single)[^]]*\\]"), "")
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .trim()

    /**
     * Self-heals Liked Songs, Heart Counts, Play History, and ListenStatsDb from:
     *  1. Settings.Global legacy & current keys (`miku_current_liked_track_id`, `hione_current_liked_track_id`)
     *  2. Local SQLite `hione_listens.db` ([com.hione.player.stats.ListenStatsDb])
     *  3. Last.fm (`DEAD_REAvER`) `user.getlovedtracks`, `user.gettoptracks`, and `user.getrecenttracks`
     */
    private fun recoverLikesAndHistoryAsync(appCtx: Context) {
        Thread({
            try {
                val byId = HashMap<Long, MediaRow>(22_000)
                val byExact = HashMap<String, MediaRow>(22_000)
                val byNorm = HashMap<String, MediaRow>(22_000)
                val byNormTitle = HashMap<String, MediaRow>(22_000)

                runCatching {
                    appCtx.contentResolver.query(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(
                            android.provider.MediaStore.Audio.Media._ID,
                            android.provider.MediaStore.Audio.Media.TITLE,
                            android.provider.MediaStore.Audio.Media.ARTIST,
                            android.provider.MediaStore.Audio.Media.ALBUM,
                            android.provider.MediaStore.Audio.Media.DURATION,
                            android.provider.MediaStore.Audio.Media.DATA
                        ),
                        null, null, null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val title = c.getString(1) ?: ""
                            val artist = c.getString(2) ?: ""
                            val album = c.getString(3) ?: ""
                            val dur = c.getLong(4)
                            val path = c.getString(5) ?: ""
                            if (id <= 0L || title.isBlank()) continue
                            val row = MediaRow(id, title, artist, album, dur, path)
                            byId[id] = row
                            byExact["${title.lowercase().trim()}\u0000${artist.lowercase().trim()}"] = row
                            val nt = normLookup(title)
                            val na = normLookup(artist)
                            if (nt.isNotEmpty()) {
                                byNorm["$nt\u0000$na"] = row
                                byNormTitle.putIfAbsent(nt, row)
                            }
                        }
                    }
                }
                if (byId.isEmpty()) return@Thread

                fun matchTrack(title: String, artist: String): MediaRow? {
                    if (title.isBlank()) return null
                    val exact = byExact["${title.lowercase().trim()}\u0000${artist.lowercase().trim()}"]
                    if (exact != null) return exact
                    val nt = normLookup(title)
                    val na = normLookup(artist)
                    if (nt.isEmpty()) return null
                    byNorm["$nt\u0000$na"]?.let { return it }
                    // Check primary artist before comma/&/feat
                    val primaryArtist = normLookup(artist.split(",", "&", "feat.", "ft.", "/").firstOrNull() ?: artist)
                    if (primaryArtist.isNotEmpty()) {
                        byNorm["$nt\u0000$primaryArtist"]?.let { return it }
                    }
                    return byNormTitle[nt]
                }

                val recoveredHearts = LinkedHashMap<Long, Int>()
                val recoveredPlayCounts = HashMap<Long, Int>()
                val recoveredLastPlayed = HashMap<Long, Long>()
                val recoveredHistory = ArrayList<Long>()
                val refused = PlayerPreferences.loadRefusedTracks(appCtx)

                // 1. Recover from Settings.Global (`miku_current_liked_track_id` & `hione_current_liked_track_id`)
                for (key in listOf("hione_current_liked_track_id", "miku_current_liked_track_id")) {
                    val gid = runCatching {
                        android.provider.Settings.Global.getLong(appCtx.contentResolver, key, -1L)
                    }.getOrDefault(-1L)
                    if (gid > 0L && byId.containsKey(gid)) {
                        recoveredHearts[gid] = maxOf(recoveredHearts[gid] ?: 0, 1)
                    }
                }

                // 2. Recover from local SQLite `hione_listens.db`
                var localDbRowCount = 0
                runCatching {
                    val db = com.hione.player.stats.ListenStatsDb.get(appCtx).readableDatabase
                    db.rawQuery(
                        """
                        SELECT track_id, title, artist, MAX(started_at), COUNT(*),
                               SUM(hearts_during), MAX(heart_count_after), MAX(qualified)
                        FROM listens
                        GROUP BY track_id, title, artist
                        ORDER BY MAX(started_at) DESC
                        """.trimIndent(),
                        null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            localDbRowCount++
                            val rawId = c.getLong(0)
                            val title = c.getString(1) ?: ""
                            val artist = c.getString(2) ?: ""
                            val maxStarted = c.getLong(3)
                            val playCnt = c.getInt(4)
                            val heartsDuring = c.getInt(5)
                            val heartAfter = c.getInt(6)
                            val qualified = c.getInt(7)

                            val matched = byId[rawId] ?: matchTrack(title, artist) ?: continue
                            recoveredHistory.add(matched.id)
                            recoveredPlayCounts[matched.id] = maxOf(recoveredPlayCounts[matched.id] ?: 0, playCnt)
                            recoveredLastPlayed[matched.id] = maxOf(recoveredLastPlayed[matched.id] ?: 0L, maxStarted)
                            val h = maxOf(heartsDuring, heartAfter, if (qualified > 0) 1 else 0)
                            if (h > 0) {
                                recoveredHearts[matched.id] = maxOf(recoveredHearts[matched.id] ?: 0, h)
                            }
                        }
                    }
                }

                // 3. Recover Loved Tracks, Top Tracks, and Recent Play History from Last.fm (`DEAD_REAvER`)
                val apiKey = com.hione.player.scrobble.LastFmCredentials.apiKey()
                    .ifBlank { "4b92d5702263277b5e5481044d6185fa" }
                val user = (LastFmPreferences.loadUsername(appCtx) ?: "DEAD_REAvER").ifBlank { "DEAD_REAvER" }

                fun fetchJson(url: String): org.json.JSONObject? = runCatching {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 7_000
                    conn.readTimeout = 7_000
                    try {
                        val txt = conn.inputStream.bufferedReader().use { it.readText() }
                        org.json.JSONObject(txt)
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()

                val uEnc = java.net.URLEncoder.encode(user, "UTF-8")

                // 3a. Loved Tracks on Last.fm
                fetchJson("https://ws.audioscrobbler.com/2.0/?method=user.getlovedtracks&user=$uEnc&api_key=$apiKey&limit=200&format=json")
                    ?.optJSONObject("lovedtracks")?.optJSONArray("track")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val title = obj.optString("name")
                            val artist = obj.optJSONObject("artist")?.optString("name").orEmpty()
                            val matched = matchTrack(title, artist) ?: continue
                            recoveredHearts[matched.id] = maxOf(recoveredHearts[matched.id] ?: 0, 2)
                        }
                    }

                // 3b. Top Tracks (3-Month & Overall) -> Playcounts + Qualifying-Play Hearts
                for (period in listOf("3month", "overall")) {
                    fetchJson("https://ws.audioscrobbler.com/2.0/?method=user.gettoptracks&user=$uEnc&api_key=$apiKey&period=$period&limit=200&format=json")
                        ?.optJSONObject("toptracks")?.optJSONArray("track")?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val title = obj.optString("name")
                                val artist = obj.optJSONObject("artist")?.optString("name").orEmpty()
                                val pc = obj.optString("playcount").toIntOrNull() ?: 1
                                val matched = matchTrack(title, artist) ?: continue
                                recoveredPlayCounts[matched.id] = maxOf(recoveredPlayCounts[matched.id] ?: 0, pc)
                                if (period == "3month" || pc >= 2) {
                                    recoveredHearts[matched.id] = maxOf(recoveredHearts[matched.id] ?: 0, pc.coerceIn(1, 5))
                                }
                            }
                        }
                }

                // 3c. Recent Tracks (Pages 1 & 2 = 400 recent scrobbles) -> Play History, Last Played, Hearts & ListenStatsDb
                val dbToSeed = if (localDbRowCount < 30) com.hione.player.stats.ListenStatsDb.get(appCtx) else null
                val scrobbleTallies = HashMap<Long, Int>()
                for (page in 1..2) {
                    val arr = fetchJson("https://ws.audioscrobbler.com/2.0/?method=user.getrecenttracks&user=$uEnc&api_key=$apiKey&limit=200&page=$page&format=json")
                        ?.optJSONObject("recenttracks")?.optJSONArray("track") ?: continue
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val uts = obj.optJSONObject("date")?.optString("uts")?.toLongOrNull() ?: continue
                        val playedAtMs = uts * 1000L
                        val title = obj.optString("name")
                        val artist = obj.optJSONObject("artist")?.optString("#text").orEmpty()
                        val matched = matchTrack(title, artist) ?: continue

                        recoveredHistory.add(matched.id)
                        recoveredLastPlayed[matched.id] = maxOf(recoveredLastPlayed[matched.id] ?: 0L, playedAtMs)
                        val newTally = (scrobbleTallies[matched.id] ?: 0) + 1
                        scrobbleTallies[matched.id] = newTally
                        recoveredPlayCounts[matched.id] = maxOf(recoveredPlayCounts[matched.id] ?: 0, newTally)
                        recoveredHearts[matched.id] = maxOf(recoveredHearts[matched.id] ?: 0, 1)

                        if (dbToSeed != null && page == 1 && i < 120) {
                            runCatching {
                                val cal = java.util.Calendar.getInstance().apply { timeInMillis = playedAtMs }
                                val dur = if (matched.durationMs > 0L) matched.durationMs else 210_000L
                                dbToSeed.openListen(
                                    com.hione.player.stats.ListenStatsDb.Listen(
                                        trackId = matched.id,
                                        path = matched.path.ifBlank { null },
                                        title = matched.title,
                                        artist = matched.artist,
                                        album = matched.album.ifBlank { null },
                                        durationMs = dur,
                                        startedAt = playedAtMs - dur,
                                        endedAt = playedAtMs,
                                        playedMs = dur,
                                        fraction = 1.0f,
                                        qualified = true,
                                        skipped = false,
                                        seekCount = 0,
                                        source = "lastfm_sync",
                                        output = "4.4mm",
                                        volume = null,
                                        volumeMax = null,
                                        sampleRateHz = 44100,
                                        bitDepth = 16,
                                        lat = null,
                                        lon = null,
                                        locationLabel = null,
                                        dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK),
                                        hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                                        heartsDuring = 1,
                                        heartCountAfter = newTally,
                                        endReason = "auto"
                                    )
                                )
                            }
                        }
                    }
                }

                // 4. Persist recovered Play History, Play Counts, Last Played, and Liked Songs
                if (recoveredHistory.isNotEmpty() || recoveredPlayCounts.isNotEmpty()) {
                    PlayerPreferences.restorePlayStats(appCtx, recoveredHistory, recoveredPlayCounts, recoveredLastPlayed)
                }

                val newlyLikedIds = ArrayList<Long>()
                for ((id, hearts) in recoveredHearts) {
                    if (id in refused) continue
                    val row = byId[id] ?: continue
                    val cur = PlayerPreferences.getHeartCount(appCtx, id)
                    val target = maxOf(cur, hearts)
                    if (target > 0) {
                        PlayerPreferences.setHeartCount(appCtx, id, target)
                        PlayerPreferences.saveLikedTrackMeta(appCtx, id, row.title, row.artist)
                        newlyLikedIds.add(id)
                    }
                }

                if (newlyLikedIds.isNotEmpty()) {
                    mutateOnMain {
                        for (id in newlyLikedIds) {
                            if (!liked.contains(id)) liked.add(id)
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("LikeStore", "recoverLikesAndHistoryAsync failed", t)
            }
        }, "HioneLikeHistoryRecovery").start()
    }

    /**
     * Also checks the pre-2026-10-09 key form.
     *
     * canonicalArtistKey changed when stylised letters started folding ("$uicide Boy$" keyed as
     * "uicide boy" before, "suicide boys" now). Anyone who had already hearted such an artist has
     * the old string in their preferences, and reading only the new key would make that heart
     * vanish. Writes below always use the current key, so an entry migrates the next time it is
     * toggled and this fallback stops mattering on its own.
     */
    fun isArtistLiked(artist: String, ctx: Context? = null): Boolean =
        likedArtists.contains(canonicalArtistKey(artist, ctx)) ||
            likedArtists.contains(legacyArtistKey(artist, ctx))

    fun toggleArtist(ctx: Context, artist: String): Boolean {
        val key = canonicalArtistKey(artist, ctx)
        val legacy = legacyArtistKey(artist, ctx)
        val wasLiked = likedArtists.contains(key) || likedArtists.contains(legacy)
        val now = !wasLiked
        if (now) {
            likedArtists.add(key)
        } else {
            // Clear BOTH spellings, or un-hearting an artist stored under the old key would
            // appear to do nothing: the write would remove the new key while isArtistLiked kept
            // finding the old one.
            likedArtists.remove(key)
            if (legacy != key && likedArtists.remove(legacy)) {
                PlayerPreferences.saveLikedArtist(ctx, legacy, false)
            }
        }
        PlayerPreferences.saveLikedArtist(ctx, key, now)
        return now
    }

    fun isLiked(id: Long): Boolean = liked.contains(id)

    /** Cumulative heart score for a track (0 = not hearted). Feeds TasteEngine and ranking. */
    fun heartCount(ctx: Context, id: Long): Int = PlayerPreferences.getHeartCount(ctx, id)

    /**
     * Register a heart for a track. ALWAYS allowed — the user can like anytime (incl. before 94%
     * and for songs heard before). The 94%-without-skip is NOT a gate; it's a WEIGHT recorded on
     * the like ("how much of it they'd heard, and WHEN"), which the taste algo reads (TasteEngine).
     * Each tap is one like event (timestamp + play-fraction + qualified). Returns the new count.
     */
    fun heart(ctx: Context, track: Track): Int {
        val fraction = HionePlayQualifier.currentFraction(track.id)
        val qualified = HionePlayQualifier.isQualified(track.id)
        PlayerPreferences.appendHeartEvent(ctx, track.id, System.currentTimeMillis(), (fraction * 100f).toInt(), qualified)
        val n = PlayerPreferences.getHeartCount(ctx, track.id) + 1
        PlayerPreferences.setHeartCount(ctx, track.id, n)         // also flips the boolean liked set
        mutateOnMain { if (!liked.contains(track.id)) liked.add(track.id) }
        PlayerPreferences.saveLikedTrackMeta(ctx, track.id, track.title, track.artist)
        HionePlayQualifier.markHearted(track.id)
        broadcastLike(ctx, track.id, true, n)
        if (n == 1 && track.artist.isNotBlank() && track.title.isNotBlank()) {
            LastFmPreferences.loadSessionKey(ctx)?.let { LastFm.setLoved(it, track.artist, track.title, true) }
        }
        return n
    }

    /** Weighted affinity from the like-event log: base + how-much-heard, with mild recency decay,
     *  saturating so a heavily-loved track outranks a one-tap like. Read by TasteEngine. */
    fun heartAffinity(ctx: Context, id: Long): Float {
        val events = PlayerPreferences.getHeartEvents(ctx, id)
        if (events.isEmpty()) return if (isLiked(id)) 0.6f else 0f
        val now = System.currentTimeMillis()
        var acc = 0f
        for ((epoch, frac, q) in events) {
            val ageDays = ((now - epoch).coerceAtLeast(0L)) / 86_400_000f
            val recency = (1f - ageDays / 365f).coerceIn(0.4f, 1f)   // never below 0.4 — a like still counts
            val strength = 0.45f + 0.45f * frac + (if (q) 0.10f else 0f)  // full-listen like ~= 1.0
            acc += strength * recency
        }
        return (acc / 2.5f).coerceIn(0f, 1f)   // ~3 solid likes saturates
    }

    /** Remove ALL hearts for a track (un-like). Long-press / explicit clear. */
    fun clearHearts(ctx: Context, track: Track) {
        PlayerPreferences.setHeartCount(ctx, track.id, 0)          // flips liked off
        PlayerPreferences.clearHeartEvents(ctx, track.id)
        mutateOnMain { liked.remove(track.id) }
        PlayerPreferences.removeLikedTrackMeta(ctx, track.id)
        broadcastLike(ctx, track.id, false, 0)
        if (track.artist.isNotBlank() && track.title.isNotBlank()) {
            LastFmPreferences.loadSessionKey(ctx)?.let { LastFm.setLoved(it, track.artist, track.title, false) }
        }
    }

    private fun broadcastLike(ctx: Context, id: Long, liked: Boolean, count: Int) {
        try {
            android.provider.Settings.Global.putString(ctx.contentResolver, "hione_current_track_liked", if (liked) "1" else "0")
            android.provider.Settings.Global.putLong(ctx.contentResolver, "hione_current_liked_track_id", id)
            android.provider.Settings.Global.putInt(ctx.contentResolver, "hione_current_track_heart_count", count)
            ctx.sendBroadcast(Intent("com.hione.player.action.LIKE_STATE_CHANGED").apply {
                putExtra("track_id", id); putExtra("is_liked", liked); putExtra("heart_count", count)
            })
        } catch (_: Throwable) {}
    }

    /** id-only overload — kept for any call site that genuinely doesn't have the Track object
     *  handy. Prefer the Track overload below when it's available: it also mirrors the like onto
     *  Last.fm's "loved tracks" (see LastFm.setLoved), which this one can't do without a title/
     *  artist to send. */
    fun toggle(ctx: Context, id: Long): Boolean {
        val nowLiked = !liked.contains(id)
        mutateOnMain { if (nowLiked) { if (!liked.contains(id)) liked.add(id) } else liked.remove(id) }
        PlayerPreferences.saveLikedTrack(ctx, id, nowLiked)
        // Keep the heart score consistent with the boolean: turning it on seeds at least one heart,
        // turning it off zeroes the score. (Earning extra hearts goes through heart() per play.)
        PlayerPreferences.setHeartCount(ctx, id, if (nowLiked) PlayerPreferences.getHeartCount(ctx, id).coerceAtLeast(1) else 0)
        try {
            android.provider.Settings.Global.putString(
                ctx.contentResolver,
                "hione_current_track_liked",
                if (nowLiked) "1" else "0"
            )
            android.provider.Settings.Global.putLong(
                ctx.contentResolver,
                "hione_current_liked_track_id",
                id
            )
            val out = Intent("com.hione.player.action.LIKE_STATE_CHANGED").apply {
                putExtra("track_id", id)
                putExtra("is_liked", nowLiked)
            }
            ctx.sendBroadcast(out)
        } catch (_: Throwable) {}
        return nowLiked
    }

    /** Every real UI call site already has the Track in hand — use this one. Mirrors the like
     *  state onto Last.fm's "loved tracks" when connected (best-effort, never blocks the local
     *  toggle on network success — see LastFm.setLoved). */
    fun toggle(ctx: Context, track: Track): Boolean {
        val nowLiked = toggle(ctx, track.id)
        // Best-effort (title, artist) backup so this like can self-heal if MediaStore ever
        // reassigns this track's _id across a rescan — see PlayerPreferences.saveLikedTrackMeta.
        if (nowLiked) PlayerPreferences.saveLikedTrackMeta(ctx, track.id, track.title, track.artist)
        else PlayerPreferences.removeLikedTrackMeta(ctx, track.id)
        if (track.artist.isNotBlank() && track.title.isNotBlank()) {
            LastFmPreferences.loadSessionKey(ctx)?.let { sk ->
                LastFm.setLoved(sk, track.artist, track.title, nowLiked)
            }
        }
        return nowLiked
    }

    /** Resolves the liked-track id set against the CURRENT library, self-healing any id that no
     *  longer matches (a MediaStore rescan reassigned it) by falling back to a (title, artist)
     *  match against the saved metadata and re-persisting the corrected id. Without this, an
     *  orphaned like just silently vanishes from every "Liked Songs" view with no explanation —
     *  confirmed this is a real, not theoretical, failure mode on this app's rescan-heavy library. */
    private data class Heal(val oldId: Long, val newId: Long, val title: String, val artist: String)

    /**
     * @param heal ONLY the canonical caller (MainActivity's liked shelf, whose [tracks] includes
     * disc-image virtual tracks) may pass true. The media session resolves against a DIFFERENT
     * track list (FastLibraryStore/MediaStore fallback, no virtual tracks) - when both healed,
     * likes ping-ponged between the two id spaces forever: liked churned every frame ->
     * recomposition/GC storm -> ANR or snapshot crash (the 2026-09-10 launch loop).
     */
    fun resolveLiked(ctx: Context, tracks: List<Track>, heal: Boolean = false): List<Track> {
        val ids = PlayerPreferences.loadLikedTracks(ctx)
        if (ids.isEmpty()) return emptyList()
        val byId = tracks.associateBy { it.id }
        val meta by lazy { PlayerPreferences.loadLikedTrackMeta(ctx) }
        val result = ArrayList<Track>(ids.size)
        val heals = ArrayList<Heal>()
        for (id in ids) {
            val direct = byId[id]
            if (direct != null) { result.add(direct); continue }
            val (title, artist) = meta[id] ?: continue
            val recovered = tracks.find { it.title == title && it.artist == artist } ?: continue
            result.add(recovered)
            heals.add(Heal(id, recovered.id, title, artist))
        }
        // Self-heal: fold likes onto the tracks' real current ids - but NEVER inline. This function
        // is called from remember{} during composition (MainActivity liked shelf) AND from binder
        // threads (media session); mutating the state list here was the 2026-09-10 crash-loop.
        // All heals apply as a normal main-thread event after the current frame.
        if (heal && heals.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                for (h in heals) {
                    PlayerPreferences.saveLikedTrack(ctx, h.oldId, false)
                    PlayerPreferences.removeLikedTrackMeta(ctx, h.oldId)
                    PlayerPreferences.saveLikedTrack(ctx, h.newId, true)
                    PlayerPreferences.saveLikedTrackMeta(ctx, h.newId, h.title, h.artist)
                    if (!liked.contains(h.newId)) liked.add(h.newId)
                    liked.remove(h.oldId)
                }
            }
        }
        return result
    }

    // ================================================== inherited likes
    /**
     * Where a track's like comes from.
     *
     *   TRACK    — liked on its own.
     *   ALBUM    — not liked on its own, but the album is, and the user has not refused it.
     *   NONE     — not liked.
     *   REFUSED  — the album is liked but the user said no to THIS track.
     */
    enum class LikeOrigin { NONE, TRACK, ALBUM, REFUSED }

    fun likeOrigin(ctx: Context, track: Track): LikeOrigin = when {
        liked.contains(track.id) -> LikeOrigin.TRACK
        refusedTracks.contains(track.id) -> LikeOrigin.REFUSED
        isAlbumLiked(track.artist.ifBlank { track.albumArtist }, track.album, ctx) -> LikeOrigin.ALBUM
        else -> LikeOrigin.NONE
    }

    /** Is this track liked, counting the album it belongs to? This is what the UI should ask. */
    fun isLikedEffective(ctx: Context, track: Track): Boolean =
        when (likeOrigin(ctx, track)) { LikeOrigin.TRACK, LikeOrigin.ALBUM -> true; else -> false }

    /**
     * Heart tap. Turns the EFFECTIVE state over, and records it explicitly so it beats the album:
     * liking a track the album already covered promotes it to its own like, un-liking one the album
     * covered records a refusal rather than silently doing nothing.
     */
    fun toggleEffective(ctx: Context, track: Track): Boolean {
        val wasLiked = isLikedEffective(ctx, track)
        if (wasLiked) {
            if (liked.contains(track.id)) clearHearts(ctx, track)
            mutateOnMain { if (!refusedTracks.contains(track.id)) refusedTracks.add(track.id) }
            PlayerPreferences.saveRefusedTrack(ctx, track.id, true)
        } else {
            mutateOnMain { refusedTracks.remove(track.id) }
            PlayerPreferences.saveRefusedTrack(ctx, track.id, false)
            heart(ctx, track)
        }
        return !wasLiked
    }

    /** Long press: drop every per-track opinion so the track follows its album again. */
    fun clearOverride(ctx: Context, track: Track) {
        if (liked.contains(track.id)) clearHearts(ctx, track)
        mutateOnMain { refusedTracks.remove(track.id) }
        PlayerPreferences.saveRefusedTrack(ctx, track.id, false)
    }

    fun isAlbumLiked(artist: String, album: String, ctx: Context? = null): Boolean =
        likedAlbums.contains(canonicalAlbumKey(artist, album, ctx))

    /**
     * Liking an album likes every track on it. Nothing is written per track: the tracks INHERIT it
     * through [likeOrigin], so the album stays one row instead of N, un-liking it takes the whole
     * set back with it, and a track the user explicitly liked on its own survives that.
     * Per-track refusals are deliberately left alone here too, so re-liking an album does not
     * quietly undo "except that one".
     */
    fun toggleAlbum(ctx: Context, artist: String, album: String): Boolean {
        val key = canonicalAlbumKey(artist, album, ctx)
        val now = if (likedAlbums.contains(key)) { likedAlbums.remove(key); false } else { likedAlbums.add(key); true }
        PlayerPreferences.saveLikedAlbum(ctx, key, now)
        return now
    }
}
