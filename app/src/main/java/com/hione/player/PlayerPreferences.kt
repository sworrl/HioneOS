package com.hione.player

import android.content.Context
import android.content.SharedPreferences

object PlayerPreferences {
    private const val PREF_NAME = "hione_player_prefs"

    private const val KEY_LAST_TRACK_ID = "last_track_id"
    private const val KEY_LAST_POSITION_MS = "last_position_ms"
    private const val KEY_LAST_TAB = "last_tab"
    private const val KEY_PROJECTM_PRESET = "projectm_preset"
    private const val KEY_LIKED_TRACKS = "liked_tracks"
    private const val KEY_SHUFFLE = "shuffle_enabled"
    private const val KEY_REPEAT = "repeat_enabled"
    private const val KEY_IDLE_DIM = "idle_dim_enabled"
    private const val KEY_AMBIENT = "ambient_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ---- Heart score (cumulative) -----------------------------------------------------------
    // New hearting model (2026-08-28): a track's "heart" is a COUNT, not a boolean — one heart is
    // earned per qualifying play (>=94% without skipping, see HionePlayQualifier) and the total
    // feeds TasteEngine. isLiked stays "count > 0" so all existing heart UI keeps working, and
    // existing boolean likes migrate lazily to a count of 1.
    private const val KEY_HEART_PREFIX = "heart_count_"
    // Per-like event log: "<epochMs>:<fractionPct>:<q>" (q=1 if a full-listen), ";"-joined, capped.
    // This is the "how much + WHEN" signal the taste algo reads — likes are NEVER gated, the play
    // fraction is just a weight.
    private const val KEY_HEART_EVENTS = "heart_events_"

    fun appendHeartEvent(context: Context, id: Long, epochMs: Long, fractionPct: Int, qualified: Boolean) {
        val p = prefs(context); val key = KEY_HEART_EVENTS + id
        val cur = p.getString(key, "") ?: ""
        val ev = "$epochMs:${fractionPct.coerceIn(0,100)}:${if (qualified) 1 else 0}"
        val list = (if (cur.isBlank()) listOf() else cur.split(";")) + ev
        val capped = if (list.size > 60) list.takeLast(60) else list
        p.edit().putString(key, capped.joinToString(";")).apply()
    }

    /** Returns (epochMs, fraction0to1, qualified) per like, oldest first. */
    fun getHeartEvents(context: Context, id: Long): List<Triple<Long, Float, Boolean>> {
        val raw = prefs(context).getString(KEY_HEART_EVENTS + id, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull {
            val f = it.split(":"); if (f.size < 3) return@mapNotNull null
            val e = f[0].toLongOrNull() ?: return@mapNotNull null
            Triple(e, (f[1].toIntOrNull() ?: 0) / 100f, f[2] == "1")
        }
    }

    fun clearHeartEvents(context: Context, id: Long) { prefs(context).edit().remove(KEY_HEART_EVENTS + id).apply() }


    fun getHeartCount(context: Context, id: Long): Int {
        val p = prefs(context)
        val stored = p.getInt(KEY_HEART_PREFIX + id, -1)
        if (stored >= 0) return stored
        // Lazy migration: a pre-existing boolean like counts as one heart.
        return if (loadLikedTracks(context).contains(id)) 1 else 0
    }

    fun setHeartCount(context: Context, id: Long, count: Int) {
        val n = count.coerceAtLeast(0)
        prefs(context).edit().putInt(KEY_HEART_PREFIX + id, n).apply()
        // Keep the boolean liked set in lockstep (n>0 == liked) so isLiked and every heart-filled
        // UI element stay correct.
        saveLikedTrack(context, id, n > 0)
    }

    fun saveLastPlayback(context: Context, trackId: Long, positionMs: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_TRACK_ID, trackId)
            .putLong(KEY_LAST_POSITION_MS, positionMs)
            .apply()
    }

    fun loadLastTrackId(context: Context): Long =
        prefs(context).getLong(KEY_LAST_TRACK_ID, -1L)

    fun loadLastPositionMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_POSITION_MS, 0L)

    // Whether playback was active — so relaunch (e.g. after an app update kills the process) can
    // resume exactly where it left off, and only auto-resume if it was actually playing.
    fun saveWasPlaying(context: Context, playing: Boolean) {
        prefs(context).edit().putBoolean("was_playing", playing).apply()
    }
    fun loadWasPlaying(context: Context): Boolean = prefs(context).getBoolean("was_playing", false)

    // Pause-on-unplug ("audio becoming noisy") — default ON to match stock HiBy OS behavior of
    // pausing when the headphone jack is pulled. Surfaced in Sound Settings + quick settings.
    fun savePauseOnUnplug(context: Context, on: Boolean) { prefs(context).edit().putBoolean("pause_on_unplug", on).apply() }
    fun loadPauseOnUnplug(context: Context): Boolean = prefs(context).getBoolean("pause_on_unplug", true)

    fun saveIdleDimEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_IDLE_DIM, on).apply() }
    fun loadIdleDimEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_IDLE_DIM, true)
    fun saveAmbientEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_AMBIENT, on).apply() }
    fun loadAmbientEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AMBIENT, true)

    // The 4-stage idle pipeline's per-stage DURATIONS (not cumulative thresholds — IdleController
    // adds them up). Defaults match the explicit spec: 120s full bright, 120s dimmed, 60s AOD, then
    // the OS's own screen sleep.
    private const val KEY_IDLE_ACTIVE_SEC = "idle_active_sec"
    private const val KEY_IDLE_DIM_SEC = "idle_dim_sec"
    private const val KEY_IDLE_AMBIENT_SEC = "idle_ambient_sec"
    fun saveIdleActiveSec(context: Context, sec: Int) { prefs(context).edit().putInt(KEY_IDLE_ACTIVE_SEC, sec).apply() }
    fun loadIdleActiveSec(context: Context): Int = prefs(context).getInt(KEY_IDLE_ACTIVE_SEC, 120)
    fun saveIdleDimSec(context: Context, sec: Int) { prefs(context).edit().putInt(KEY_IDLE_DIM_SEC, sec).apply() }
    fun loadIdleDimSec(context: Context): Int = prefs(context).getInt(KEY_IDLE_DIM_SEC, 120)
    fun saveIdleAmbientSec(context: Context, sec: Int) { prefs(context).edit().putInt(KEY_IDLE_AMBIENT_SEC, sec).apply() }
    fun loadIdleAmbientSec(context: Context): Int = prefs(context).getInt(KEY_IDLE_AMBIENT_SEC, 60)

    // Master root/sudo toggle — default ON when available.
    private const val KEY_ROOT_ENABLED = "hione_root_enabled"
    fun saveRootEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_ROOT_ENABLED, on).apply() }
    fun loadRootEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ROOT_ENABLED, true)

    // Audio playback engine ("swappable bone"): "exoplayer" (default) or "vlc"
    private const val KEY_AUDIO_ENGINE = "hione_audio_engine"
    fun saveAudioEngine(context: Context, engine: String) { prefs(context).edit().putString(KEY_AUDIO_ENGINE, engine).apply() }
    fun loadAudioEngine(context: Context): String = prefs(context).getString(KEY_AUDIO_ENGINE, "exoplayer") ?: "exoplayer"

    // Pulsar LED (the front status light, driven by HionePulse) & CPU performance — default ON
    private const val KEY_PULSAR_ENABLED = "pulsar_light_enabled"
    private const val KEY_PULSAR_BEAT = "pulsar_light_beat"
    fun savePulsarBeatEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_PULSAR_BEAT, on).apply() }
    fun loadPulsarBeatEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_PULSAR_BEAT, true)
    private const val KEY_CPU_PERF_ENABLED = "cpu_perf_enabled"
    fun savePulsarEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_PULSAR_ENABLED, on).apply() }
    fun loadPulsarEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_PULSAR_ENABLED, true)
    fun saveCpuPerfEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_CPU_PERF_ENABLED, on).apply() }
    fun loadCpuPerfEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CPU_PERF_ENABLED, true)

    // Real per-core governor values stashed before CpuPerformance ever overrides them — null means
    // "nothing stashed, nothing to restore" (same self-healing shape as the screen-timeout stash).
    private const val KEY_STASHED_GOVERNORS = "stashed_cpu_governors"
    fun saveStashedGovernors(context: Context, map: Map<String, String>) {
        runCatching {
            val obj = org.json.JSONObject()
            map.forEach { (path, gov) -> obj.put(path, gov) }
            prefs(context).edit().putString(KEY_STASHED_GOVERNORS, obj.toString()).apply()
        }
    }
    fun loadStashedGovernors(context: Context): Map<String, String>? {
        val raw = prefs(context).getString(KEY_STASHED_GOVERNORS, null) ?: return null
        return runCatching {
            val obj = org.json.JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrNull()
    }
    fun clearStashedGovernors(context: Context) { prefs(context).edit().remove(KEY_STASHED_GOVERNORS).apply() }

    // Stash of the REAL system screen-off timeout (ms) from right before ScreenOffHelper shrinks
    // it to force an early sleep — -1 means "nothing stashed, nothing to restore." Persisted (not
    // just in-memory) so a crash/kill between shrinking and restoring can still self-heal on the
    // next launch instead of permanently leaving the user's system timeout wrong.
    private const val KEY_STASHED_TIMEOUT = "stashed_screen_timeout_ms"
    fun saveStashedScreenTimeout(context: Context, ms: Int) { prefs(context).edit().putInt(KEY_STASHED_TIMEOUT, ms).apply() }
    fun loadStashedScreenTimeout(context: Context): Int = prefs(context).getInt(KEY_STASHED_TIMEOUT, -1)
    fun clearStashedScreenTimeout(context: Context) { prefs(context).edit().remove(KEY_STASHED_TIMEOUT).apply() }

    // Full queue snapshot — distinct from saveLastPlayback (single track+position) above, which
    // only ever restores by rebuilding the queue as the WHOLE library. This is what the update
    // handler (UpdateManager.kt) uses to resume the exact queue/order/index the user was actually
    // in (an album, a filtered list, shuffle order, "play next" additions — anything) after a
    // graceful-swap app update, not just "whatever track was playing."
    private const val KEY_QUEUE_IDS = "queue_track_ids"
    private const val KEY_QUEUE_INDEX = "queue_index"
    fun saveQueue(context: Context, trackIds: List<Long>, index: Int) {
        prefs(context).edit()
            .putString(KEY_QUEUE_IDS, trackIds.joinToString(","))
            .putInt(KEY_QUEUE_INDEX, index)
            .apply()
    }
    fun saveQueueIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_QUEUE_INDEX, index).apply()
    }
    fun loadQueueIds(context: Context): List<Long> =
        prefs(context).getString(KEY_QUEUE_IDS, null)?.takeIf { it.isNotBlank() }
            ?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
    fun loadQueueIndex(context: Context): Int = prefs(context).getInt(KEY_QUEUE_INDEX, 0)
    fun clearQueue(context: Context) { prefs(context).edit().remove(KEY_QUEUE_IDS).remove(KEY_QUEUE_INDEX).apply() }

    // When ON, rotating to landscape (only possible if system auto-rotate is on) drops into tape mode.
    fun saveTapeOnLandscape(context: Context, on: Boolean) { prefs(context).edit().putBoolean("tape_on_landscape", on).apply() }
    fun loadTapeOnLandscape(context: Context): Boolean = prefs(context).getBoolean("tape_on_landscape", true)

    // ---- Play history + per-track stats ----
    private const val KEY_HISTORY = "play_history"     // JSON array of trackIds, most-recent first
    private const val HISTORY_CAP = 200

    private val playCountCache = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private val lastPlayedCache = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** Record that a track started playing: bump history, play count, and last-played timestamp. */
    fun recordPlay(context: Context, trackId: Long, nowMs: Long) {
        if (trackId <= 0) return
        val p = prefs(context)
        val hist = loadHistory(context).toMutableList()
        hist.remove(trackId)
        hist.add(0, trackId)
        while (hist.size > HISTORY_CAP) hist.removeAt(hist.size - 1)
        val newPc = loadPlayCount(context, trackId) + 1
        playCountCache[trackId] = newPc
        lastPlayedCache[trackId] = nowMs
        val histJson = org.json.JSONArray(hist).toString()
        p.edit()
            .putString(KEY_HISTORY, histJson)
            .putInt("pc_$trackId", newPc)
            .putLong("lp_$trackId", nowMs)
            .apply()
        runCatching {
            android.provider.Settings.Global.putString(
                context.contentResolver,
                "hione_play_history_v1",
                histJson
            )
        }
    }

    /** Recently played trackIds, most-recent first. */
    fun loadHistory(context: Context): List<Long> {
        var raw = prefs(context).getString(KEY_HISTORY, null)
        if (raw.isNullOrBlank()) {
            raw = runCatching {
                android.provider.Settings.Global.getString(context.contentResolver, "hione_play_history_v1")
            }.getOrNull()
            if (!raw.isNullOrBlank()) {
                prefs(context).edit().putString(KEY_HISTORY, raw).apply()
            }
        }
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }
        } catch (_: Throwable) { emptyList() }
    }

    /** Batch-restore play history, play counts, and last-played timestamps recovered from ListenStatsDb & Last.fm. */
    fun restorePlayStats(
        context: Context,
        recoveredHistory: List<Long>,
        recoveredPlayCounts: Map<Long, Int>,
        recoveredLastPlayed: Map<Long, Long>
    ) {
        val p = prefs(context)
        val ed = p.edit()
        val existingHist = loadHistory(context)
        val mergedHist = LinkedHashSet<Long>()
        // Keep most recent items first by sorting union by lastPlayed timestamp
        val combinedIds = (existingHist + recoveredHistory).distinct().filter { it > 0L }
        val sortedIds = combinedIds.sortedByDescending { id ->
            maxOf(loadLastPlayedAt(context, id), recoveredLastPlayed[id] ?: 0L)
        }
        for (id in sortedIds) {
            if (mergedHist.size < HISTORY_CAP) mergedHist.add(id)
        }
        if (mergedHist.isNotEmpty()) {
            val histJson = org.json.JSONArray(mergedHist.toList()).toString()
            ed.putString(KEY_HISTORY, histJson)
            runCatching {
                android.provider.Settings.Global.putString(
                    context.contentResolver,
                    "hione_play_history_v1",
                    histJson
                )
            }
        }
        for ((id, count) in recoveredPlayCounts) {
            val best = maxOf(loadPlayCount(context, id), count)
            if (best > 0) {
                playCountCache[id] = best
                ed.putInt("pc_$id", best)
            }
        }
        for ((id, ts) in recoveredLastPlayed) {
            val best = maxOf(loadLastPlayedAt(context, id), ts)
            if (best > 0L) {
                lastPlayedCache[id] = best
                ed.putLong("lp_$id", best)
            }
        }
        ed.apply()
    }

    /** Per-track last-listen location "lat,lon,timestamp" (single coarse fix; see LocationLogger). */
    fun saveTrackLocation(context: Context, trackId: Long, lat: Double, lon: Double, whenMs: Long) {
        prefs(context).edit().putString("loc_$trackId", "$lat,$lon,$whenMs").apply()
    }
    /** Returns Triple(lat, lon, whenMs) or null. */
    fun loadTrackLocation(context: Context, trackId: Long): Triple<Double, Double, Long>? {
        val raw = prefs(context).getString("loc_$trackId", null) ?: return null
        return try { val p = raw.split(","); Triple(p[0].toDouble(), p[1].toDouble(), p[2].toLong()) } catch (_: Throwable) { null }
    }

    fun loadPlayCount(context: Context, trackId: Long): Int {
        playCountCache[trackId]?.let { return it }
        val pc = prefs(context).getInt("pc_$trackId", 0)
        playCountCache[trackId] = pc
        return pc
    }

    fun loadLastPlayedAt(context: Context, trackId: Long): Long {
        lastPlayedCache[trackId]?.let { return it }
        val lp = prefs(context).getLong("lp_$trackId", 0L)
        lastPlayedCache[trackId] = lp
        return lp
    }

    // ---- Track Number Persistence & Cache ----
    private val trackNumberCache = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private var trackNumberFile: java.io.File? = null
    @Volatile private var trackNumberLoaded = false

    private fun ensureTrackNumbers(context: Context) {
        if (trackNumberLoaded) return
        synchronized(trackNumberCache) {
            if (trackNumberLoaded) return
            trackNumberFile = java.io.File(context.filesDir, "track_numbers.json")
            runCatching {
                val f = trackNumberFile!!
                if (f.exists()) {
                    val o = org.json.JSONObject(f.readText())
                    o.keys().forEach { k ->
                        val id = k.toLongOrNull()
                        if (id != null) trackNumberCache[id] = o.getInt(k)
                    }
                }
            }
            trackNumberLoaded = true
        }
    }

    @Volatile private var trackNumberDirty = false

    // Was: full read-parse-rewrite of the ENTIRE track_numbers.json on every single call — O(n)
    // disk I/O per call across a file that grows with the whole library, i.e. genuinely O(n²) total
    // when queryTracks() calls this once per track needing an inferred number (any FLAC/filename-
    // numbered library of any size). Now just updates the already-loaded in-memory cache and marks
    // it dirty; the actual write is batched via flushTrackNumbers(), called once after the scan
    // loop finishes instead of once per track.
    fun saveTrackNumber(context: Context, trackId: Long, trackNo: Int) {
        if (trackId <= 0 || trackNo <= 0) return
        ensureTrackNumbers(context)
        val old = trackNumberCache[trackId]
        if (old == trackNo) return
        trackNumberCache[trackId] = trackNo
        trackNumberDirty = true
    }

    /** Writes the whole in-memory track-number cache to disk in ONE pass. Call once after a batch
     *  of saveTrackNumber() calls (e.g. at the end of MainActivity.queryTracks()) — not per-call. */
    fun flushTrackNumbers(context: Context) {
        if (!trackNumberDirty) return
        val f = trackNumberFile ?: java.io.File(context.filesDir, "track_numbers.json").also { trackNumberFile = it }
        runCatching {
            val o = org.json.JSONObject()
            trackNumberCache.forEach { (id, no) -> o.put(id.toString(), no) }
            f.writeText(o.toString())
            trackNumberDirty = false
        }
    }

    fun loadTrackNumber(context: Context, trackId: Long): Int {
        ensureTrackNumbers(context)
        return trackNumberCache[trackId] ?: 0
    }

    fun clearHistory(context: Context) { prefs(context).edit().remove(KEY_HISTORY).apply() }

    fun saveLikedTrack(context: Context, trackId: Long, isLiked: Boolean) {
        val current = loadLikedTracks(context).toMutableSet()
        if (isLiked) current.add(trackId) else current.remove(trackId)
        val strSet = current.map { it.toString() }.toSet()
        prefs(context).edit().putStringSet(KEY_LIKED_TRACKS, strSet).apply()
        runCatching {
            android.provider.Settings.Global.putString(
                context.contentResolver,
                "hione_liked_tracks_v1",
                current.joinToString(",")
            )
        }
    }

    fun loadLikedTracks(context: Context): Set<Long> {
        val strSet = prefs(context).getStringSet(KEY_LIKED_TRACKS, emptySet()) ?: emptySet()
        var set = strSet.mapNotNull { it.toLongOrNull() }.toSet()
        if (set.isEmpty()) {
            runCatching {
                val globalStr = android.provider.Settings.Global.getString(
                    context.contentResolver,
                    "hione_liked_tracks_v1"
                )
                if (!globalStr.isNullOrBlank()) {
                    set = globalStr.split(",")
                        .mapNotNull { it.trim().toLongOrNull() }
                        .filter { it > 0 }
                        .toSet()
                    if (set.isNotEmpty()) {
                        prefs(context).edit().putStringSet(KEY_LIKED_TRACKS, set.map { it.toString() }.toSet()).apply()
                    }
                }
            }
        }
        return set
    }

    // A liked track is keyed on MediaStore's numeric _id — which is usually stable, but NOT
    // guaranteed across rescans (confirmed this is a real, not theoretical, concern: MediaStore
    // can reassign a row's _id if a file gets removed+reinserted rather than updated in place).
    // When that happens the like silently orphans — still "liked" in prefs, but resolves to
    // nothing, with zero indication anything's wrong. This is a lightweight (title, artist)
    // fallback so a liked track can be found again by CONTENT if its id ever drifts, same
    // "resolve by stable identity, not a brittle raw reference" fix already applied to albums/
    // artists this session (see canonicalAlbumKey). Best-effort only — recorded when liking via
    // the Track overload (every real UI call site), not the id-only overload which doesn't have a
    // title/artist to save. One JSON object, one pref key — nowhere near the write volume to
    // matter for IO/battery.
    private const val KEY_LIKED_TRACK_META = "liked_tracks_meta"
    fun saveLikedTrackMeta(context: Context, id: Long, title: String, artist: String) {
        runCatching {
            val obj = org.json.JSONObject(prefs(context).getString(KEY_LIKED_TRACK_META, null) ?: "{}")
            obj.put(id.toString(), org.json.JSONObject().put("t", title).put("a", artist))
            prefs(context).edit().putString(KEY_LIKED_TRACK_META, obj.toString()).apply()
        }
    }
    fun removeLikedTrackMeta(context: Context, id: Long) {
        runCatching {
            val obj = org.json.JSONObject(prefs(context).getString(KEY_LIKED_TRACK_META, null) ?: "{}")
            obj.remove(id.toString())
            prefs(context).edit().putString(KEY_LIKED_TRACK_META, obj.toString()).apply()
        }
    }
    fun loadLikedTrackMeta(context: Context): Map<Long, Pair<String, String>> = runCatching {
        val obj = org.json.JSONObject(prefs(context).getString(KEY_LIKED_TRACK_META, null) ?: "{}")
        obj.keys().asSequence().mapNotNull { key ->
            key.toLongOrNull()?.let { id -> val e = obj.getJSONObject(key); id to (e.optString("t") to e.optString("a")) }
        }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * Tracks the user has EXPLICITLY refused, which beat an album-level like.
     *
     * Separate from "not in liked_tracks": absence means "no opinion, follow the album", presence
     * means "I actively do not want this one". Collapsing the two would make a refusal vanish the
     * next time the album is liked.
     */
    fun saveRefusedTrack(context: Context, id: Long, refused: Boolean) {
        val cur = loadRefusedTracks(context).mapTo(HashSet()) { it.toString() }
        if (refused) cur.add(id.toString()) else cur.remove(id.toString())
        prefs(context).edit().putStringSet("refused_tracks", cur).apply()
    }
    fun loadRefusedTracks(context: Context): Set<Long> =
        (prefs(context).getStringSet("refused_tracks", emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }.toSet()

    fun saveLikedAlbum(context: Context, name: String, isLiked: Boolean) {
        val cur = loadLikedAlbums(context).toMutableSet()
        if (isLiked) cur.add(name) else cur.remove(name)
        prefs(context).edit().putStringSet("liked_albums", cur).apply()
    }
    fun loadLikedAlbums(context: Context): Set<String> =
        prefs(context).getStringSet("liked_albums", emptySet()) ?: emptySet()

    fun saveLikedArtist(context: Context, name: String, isLiked: Boolean) {
        val cur = loadLikedArtists(context).toMutableSet()
        if (isLiked) cur.add(name) else cur.remove(name)
        prefs(context).edit().putStringSet("liked_artists", cur).apply()
    }
    fun loadLikedArtists(context: Context): Set<String> =
        prefs(context).getStringSet("liked_artists", emptySet()) ?: emptySet()

    fun saveTab(context: Context, tabName: String) {
        prefs(context).edit().putString(KEY_LAST_TAB, tabName).apply()
    }

    // User-pinned "which track's art represents this artist in lists" — an artist's default cover
    // is otherwise just whatever track happens to sort first, which is often not the album the
    // user actually associates with them. Keyed by artist NAME (matches ArtistGroup.name), not id,
    // since artists don't have a stable MediaStore id of their own.
    fun saveArtistCoverTrack(context: Context, artistName: String, trackId: Long) {
        prefs(context).edit().putLong("artist_cover_$artistName", trackId).apply()
    }
    fun loadArtistCoverTrack(context: Context, artistName: String): Long? {
        val v = prefs(context).getLong("artist_cover_$artistName", -1L)
        return if (v >= 0L) v else null
    }
    fun clearArtistCoverTrack(context: Context, artistName: String) {
        prefs(context).edit().remove("artist_cover_$artistName").apply()
    }

    // Tape-mode theme: sticky until deliberately changed.
    fun saveTapeTheme(context: Context, idx: Int) { prefs(context).edit().putInt("tape_theme", idx).apply() }
    fun loadTapeTheme(context: Context): Int = prefs(context).getInt("tape_theme", 0)

    // Tape deck volume fader model. -1 means "follow the cassette", i.e. pick the fader that suits
    // the loaded shell's layout; 0..n-1 pin one model regardless of which cassette is in.
    fun saveTapeFader(context: Context, idx: Int) { prefs(context).edit().putInt("tape_fader", idx).apply() }
    fun loadTapeFader(context: Context): Int = prefs(context).getInt("tape_fader", -1)

    // Last view ("tape" | "np" | "list") — restored on relaunch.
    fun saveLastView(context: Context, v: String) { prefs(context).edit().putString("last_view", v).apply() }
    fun loadLastView(context: Context): String = prefs(context).getString("last_view", "list") ?: "list"

    fun loadTab(context: Context): String =
        prefs(context).getString(KEY_LAST_TAB, "HOME") ?: "HOME"

    fun saveProjectMPreset(context: Context, presetIndex: Int) {
        prefs(context).edit().putInt(KEY_PROJECTM_PRESET, presetIndex).apply()
    }

    fun loadProjectMPreset(context: Context): Int =
        prefs(context).getInt(KEY_PROJECTM_PRESET, 0)

    fun saveShuffle(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHUFFLE, enabled).apply()
    }

    fun loadShuffle(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHUFFLE, false)

    fun saveRepeat(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REPEAT, enabled).apply()
    }

    fun loadRepeat(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REPEAT, false)

    // Auto-visualizer: when true, art auto-fades to the visualizer after ~15s (no manual button).
    fun saveAutoViz(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("auto_viz", enabled).apply() }
    fun loadAutoViz(context: Context): Boolean = prefs(context).getBoolean("auto_viz", true)

    // ---- Now Playing look (2026-08-29 redesign; see ui/NowPlayingLook.kt) ----
    // Album-art dynamic color on the Now Playing screen (off = Hione teal identity palette).
    fun saveDynamicColor(context: Context, on: Boolean) { prefs(context).edit().putBoolean("np_dynamic_color", on).apply() }
    fun loadDynamicColor(context: Context): Boolean = prefs(context).getBoolean("np_dynamic_color", true)
    // Wavy (waveform-driven) seek bar vs the plain embossed scrubber.
    fun saveWavyBar(context: Context, on: Boolean) { prefs(context).edit().putBoolean("np_wavy_bar", on).apply() }
    fun loadWavyBar(context: Context): Boolean = prefs(context).getBoolean("np_wavy_bar", true)
    // Visualizer engine: "projectm" (native Milkdrop, heavy) or "shader" (GLES2 GLSL presets, light).
    fun saveVizEngine(context: Context, engine: String) { prefs(context).edit().putString("viz_engine", engine).apply() }
    fun loadVizEngine(context: Context): String = prefs(context).getString("viz_engine", "projectm") ?: "projectm"
    // Last GLSL preset index for the shader engine.
    fun saveShaderPreset(context: Context, idx: Int) { prefs(context).edit().putInt("viz_shader_preset", idx).apply() }
    fun loadShaderPreset(context: Context): Int = prefs(context).getInt("viz_shader_preset", 0)
    // Whether the data-verbose "Track Facts" strip was left open.
    fun saveTrackFactsExpanded(context: Context, on: Boolean) { prefs(context).edit().putBoolean("np_track_facts", on).apply() }
    fun loadTrackFactsExpanded(context: Context): Boolean = prefs(context).getBoolean("np_track_facts", false)

    // ---- Playlists (JSON: {"name":[trackId,...]}) ----
    private const val KEY_PLAYLISTS = "playlists_json"

    fun loadPlaylists(context: Context): LinkedHashMap<String, MutableList<Long>> {
        val json = prefs(context).getString(KEY_PLAYLISTS, "{}") ?: "{}"
        val out = LinkedHashMap<String, MutableList<Long>>()
        try {
            val o = org.json.JSONObject(json)
            for (name in o.keys()) {
                val arr = o.getJSONArray(name)
                val list = ArrayList<Long>(arr.length())
                for (i in 0 until arr.length()) list.add(arr.getLong(i))
                out[name] = list
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun savePlaylists(context: Context, map: Map<String, List<Long>>) {
        val o = org.json.JSONObject()
        for ((k, v) in map) o.put(k, org.json.JSONArray(v))
        prefs(context).edit().putString(KEY_PLAYLISTS, o.toString()).apply()
    }

    fun createPlaylist(context: Context, name: String) {
        val m = loadPlaylists(context); if (!m.containsKey(name)) { m[name] = ArrayList(); savePlaylists(context, m) }
    }
    fun addToPlaylist(context: Context, name: String, trackId: Long) {
        val m = loadPlaylists(context); val l = m.getOrPut(name) { ArrayList() }; if (!l.contains(trackId)) l.add(trackId); savePlaylists(context, m)
    }
    fun removeFromPlaylist(context: Context, name: String, trackId: Long) {
        val m = loadPlaylists(context); m[name]?.remove(trackId); savePlaylists(context, m)
    }
    fun deletePlaylist(context: Context, name: String) {
        val m = loadPlaylists(context); m.remove(name); savePlaylists(context, m)
    }

    // ---- "The" Artist Prefix Sorting & Display Preferences ----
    private const val KEY_SORT_IGNORE_THE = "sort_ignore_the"
    private const val KEY_DISPLAY_THE_MODE = "display_the_mode"

    fun saveSortIgnoreThe(context: Context, ignore: Boolean) {
        prefs(context).edit().putBoolean(KEY_SORT_IGNORE_THE, ignore).apply()
    }
    fun loadSortIgnoreThe(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SORT_IGNORE_THE, true) // Default: true (sort "The Crystal Method" under C)

    fun saveDisplayTheMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_DISPLAY_THE_MODE, mode).apply()
    }
    fun loadDisplayTheMode(context: Context): String =
        prefs(context).getString(KEY_DISPLAY_THE_MODE, "PREFIX") ?: "PREFIX" // Default: "PREFIX" ("The Crystal Method")

    // USB Audio routing in Car / Android Auto mode: default FALSE (strictly route to Hi-Res 3.5mm/4.4mm AUX jack)
    private const val KEY_ALLOW_USB_AUDIO = "allow_usb_audio_car"
    fun saveAllowUsbAudio(context: Context, allow: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_USB_AUDIO, allow).apply()
    }
    fun loadAllowUsbAudio(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_USB_AUDIO, false)
}
