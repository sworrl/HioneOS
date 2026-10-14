package com.hione.player.taste

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import com.hione.player.FastLibraryStore
import com.hione.player.InstantRandom
import com.hione.player.PlayerHolder
import com.hione.player.PlayerPreferences
import com.hione.player.Track
import com.hione.player.mediaItemFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Random
import kotlin.math.ln

/**
 * "Hione Radio" — station mode. An ADDITIONAL queue mode (default off, never touches the normal
 * queue semantics unless started): seed it from a track / artist / album / genre / "my taste
 * now", it builds a queue of similar tracks and keeps refilling it as playback nears the end.
 *
 * Similarity is purely local and explainable:
 *   feature match   same artist / album / genre, close year
 *   co-occurrence   tracks heard in the same sessions as the seed (and as what just played)
 *   affinity        the TasteModel score (hearts, plays, completion, context)
 *   time fit        day-part lift for the current hour
 *   discovery       the slider (0-100) is the share of each batch drawn from tracks the owner
 *                   has never / barely played — ranked by the same similarity, so "discover"
 *                   still means "like the seed", just unheard
 * Diversity rules: no artist twice within three consecutive picks, at most two tracks per
 * artist and per album per batch (artist/album seeds relax the per-artist cap), never repeats
 * anything already played in this station, nothing from the last 40 listens, no whole-disc
 * images or >20-minute files.
 *
 * Stopping leaves whatever is in the queue playing and simply stops refilling; the queue the
 * station replaced is remembered so it can be restored from the sheet.
 */
object StationEngine {
    sealed class Seed(val label: String, val kind: String) {
        class FromTrack(val track: Track) : Seed(track.title, "song")
        /** [tracks] — the group's own tracks when the caller has them (artist/album headers do);
         *  otherwise resolved by name against the library. */
        class FromArtist(val name: String, val tracks: List<Track> = emptyList()) : Seed(name, "artist")
        class FromAlbum(val name: String, val artist: String, val tracks: List<Track> = emptyList()) : Seed(name, "album")
        class FromGenre(val genre: String) : Seed(genre, "genre")
        object MyTasteNow : Seed("My taste right now", "taste")
    }

    class Pick(val track: Track, val why: String)

    private const val INITIAL_BATCH = 14
    private const val REFILL_BATCH = 10
    private const val REFILL_WHEN_REMAINING = 2
    private const val MAX_DURATION_MS = 20L * 60_000L
    private const val PREF_DISCOVERY = "station_discovery"

    var active by mutableStateOf(false); private set
    var seed by mutableStateOf<Seed?>(null); private set
    var discovery by mutableStateOf(35); private set
    var sheetOpen by mutableStateOf(false)
    var picks by mutableStateOf<List<Pick>>(emptyList()); private set
    var nowWhy by mutableStateOf(""); private set
    var lastMessage by mutableStateOf(""); private set
    var hasPreviousQueue by mutableStateOf(false); private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val played = LinkedHashSet<Long>()
    private val queued = LinkedHashSet<Long>()
    private val whyById = HashMap<Long, String>()
    @Volatile private var refilling = false
    private var previousIds: List<Long> = emptyList()
    private var previousIndex = 0
    private var previousPos = 0L
    private var seedTracks: List<Track> = emptyList()
    private var libraryOverride: List<Track>? = null

    fun openSheet() { sheetOpen = true }
    fun closeSheet() { sheetOpen = false }

    fun loadPrefs(ctx: Context) {
        discovery = ctx.getSharedPreferences("hione_taste", Context.MODE_PRIVATE).getInt(PREF_DISCOVERY, 35)
    }

    fun setDiscovery(ctx: Context, value: Int) {
        discovery = value.coerceIn(0, 100)
        ctx.getSharedPreferences("hione_taste", Context.MODE_PRIVATE).edit().putInt(PREF_DISCOVERY, discovery).apply()
    }

    fun whyFor(trackId: Long): String = whyById[trackId] ?: ""

    /** Starts a station. [library] lets the caller pass the live track list (Home has it);
     *  otherwise the fast binary cache is used. Returns false when there is nothing to seed. */
    fun start(ctx: Context, newSeed: Seed, library: List<Track>? = null): Boolean {
        val app = ctx.applicationContext
        val lib = library ?: FastLibraryStore.loadSync(app) ?: emptyList()
        if (lib.isEmpty()) { lastMessage = "The library is empty. Run a scan first."; return false }
        libraryOverride = library
        loadPrefs(app)
        val player = PlayerHolder.ensure(app)
        runCatching { PlayerHolder.ensureSession(app); PlayerHolder.ensureControllerConnected(app) }
        // Remember what the station is replacing so "restore previous queue" can bring it back.
        val prevIds = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        if (prevIds.isNotEmpty()) {
            previousIds = prevIds; previousIndex = player.currentMediaItemIndex; previousPos = player.currentPosition
            hasPreviousQueue = true
        }
        played.clear(); queued.clear(); whyById.clear(); picks = emptyList(); nowWhy = ""
        seed = newSeed
        seedTracks = seedTracksFor(app, newSeed, lib)
        if (seedTracks.isEmpty() && newSeed !is Seed.MyTasteNow) { lastMessage = "Nothing in the library matches that seed"; return false }
        active = true
        lastMessage = ""
        val first = (newSeed as? Seed.FromTrack)?.track
        scope.launch {
            val batch = pickBatch(app, lib, INITIAL_BATCH - (if (first != null) 1 else 0))
            main.post {
                if (!active) return@post
                val list = ArrayList<Pick>()
                if (first != null) list.add(Pick(first, "your seed"))
                list.addAll(batch)
                if (list.isEmpty()) {
                    active = false
                    lastMessage = "Not enough listening history for this station yet. Listen more to unlock it."
                    return@post
                }
                val p = PlayerHolder.player ?: return@post
                p.shuffleModeEnabled = false
                PlayerPreferences.saveShuffle(app, false)
                InstantRandom.clear()
                queued.addAll(list.map { it.track.id })
                list.forEach { whyById[it.track.id] = it.why }
                picks = list
                p.setMediaItems(list.map { mediaItemFor(it.track) }, 0, 0L)
                p.prepare(); p.play()
                PlayerPreferences.saveQueue(app, list.map { it.track.id }, 0)
                nowWhy = list.first().why
            }
        }
        return true
    }

    /** Stop refilling. With [restorePrevious] the queue the station replaced comes back, paused
     *  at the position it was at. */
    fun stop(ctx: Context, restorePrevious: Boolean = false) {
        val app = ctx.applicationContext
        active = false
        sheetOpen = false
        nowWhy = ""
        if (restorePrevious && previousIds.isNotEmpty()) {
            val lib = libraryOverride ?: FastLibraryStore.loadSync(app) ?: emptyList()
            val byId = lib.associateBy { it.id }
            val tracks = previousIds.mapNotNull { byId[it] }
            val p = PlayerHolder.player
            if (p != null && tracks.isNotEmpty()) {
                val idx = previousIndex.coerceIn(0, tracks.lastIndex)
                p.setMediaItems(tracks.map { mediaItemFor(it) }, idx, previousPos.coerceAtLeast(0L))
                p.prepare()
                PlayerPreferences.saveQueue(app, tracks.map { it.id }, idx)
            }
        }
        previousIds = emptyList(); hasPreviousQueue = false
    }

    // ---- player events (via TasteHooks) ------------------------------------------------------

    fun onTransition(app: Context, player: ExoPlayer, newId: Long) {
        if (!active) return
        if (newId > 0L) { played.add(newId); nowWhy = whyById[newId] ?: "" }
        maybeRefill(app, player)
    }

    fun onQueueEnded(app: Context, player: ExoPlayer) {
        if (!active) return
        maybeRefill(app, player, force = true)
    }

    fun onTimelineChanged(app: Context, player: ExoPlayer) {
        if (!active) return
        val ids = (0 until player.mediaItemCount).mapNotNull { runCatching { player.getMediaItemAt(it).mediaId.toLongOrNull() }.getOrNull() }
        if (ids.isEmpty()) return
        // An explicit play elsewhere replaced the queue: fewer than half its items are ours.
        val ours = ids.count { it in queued }
        if (ours * 2 < ids.size) { active = false; nowWhy = "" }
    }

    private fun maybeRefill(app: Context, player: ExoPlayer, force: Boolean = false) {
        val remaining = player.mediaItemCount - 1 - player.currentMediaItemIndex
        if (!force && remaining > REFILL_WHEN_REMAINING) return
        if (refilling) return
        refilling = true
        val lib = libraryOverride ?: FastLibraryStore.loadSync(app) ?: emptyList()
        scope.launch {
            val batch = runCatching { pickBatch(app, lib, REFILL_BATCH) }.getOrDefault(emptyList())
            main.post {
                refilling = false
                if (!active) return@post
                val p = PlayerHolder.player ?: return@post
                if (batch.isEmpty()) { lastMessage = "Station ran out of similar tracks"; return@post }
                queued.addAll(batch.map { it.track.id })
                batch.forEach { whyById[it.track.id] = it.why }
                picks = picks + batch
                p.addMediaItems(batch.map { mediaItemFor(it.track) })
                if (force && p.playbackState == androidx.media3.common.Player.STATE_ENDED) { p.seekTo(p.mediaItemCount - batch.size, 0L); p.prepare(); p.play() }
                val ids = (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).mediaId.toLongOrNull() }
                PlayerPreferences.saveQueue(app, ids, p.currentMediaItemIndex)
            }
        }
    }

    // ---- seed resolution --------------------------------------------------------------------

    private fun seedTracksFor(app: Context, s: Seed, lib: List<Track>): List<Track> = when (s) {
        is Seed.FromTrack -> listOf(s.track)
        is Seed.FromArtist -> s.tracks.ifEmpty { val k = TasteModel.artistKey(s.name); lib.filter { TasteModel.artistKey(it) == k } }
        is Seed.FromAlbum -> s.tracks.ifEmpty {
            val an = s.name.trim().lowercase(); val ar = s.artist.trim().lowercase()
            lib.filter { t ->
                t.album.trim().lowercase() == an &&
                    ((t.albumArtist.ifBlank { t.artist }).trim().lowercase() == ar || t.artist.trim().lowercase() == ar)
            }
        }
        is Seed.FromGenre -> { val k = GenreIndex.key(s.genre); lib.filter { GenreIndex.key(GenreIndex.genreOf(app, it)) == k } }
        Seed.MyTasteNow -> emptyList()
    }

    // ---- candidate scoring ------------------------------------------------------------------

    private class Cand(val track: Track, val score: Float, val why: String, val heard: Boolean)

    private suspend fun pickBatch(app: Context, lib: List<Track>, n: Int): List<Pick> {
        val s = seed ?: return emptyList()
        val snap = TasteModel.snapshot(app, lib) ?: withContext(Dispatchers.Default) { TasteModel.build(app, lib) }
        val db = TasteDb.get(app)
        val now = System.currentTimeMillis()
        val slot = TasteDb.currentSlot()

        // Seed context. For "my taste now" the seed is the owner's top artists in this day-part
        // plus what they've been playing lately — honest, and it changes through the day.
        val recentIds = db.recentTrackIds(now, 14L * 86_400_000L, 40)
        val recentSet = recentIds.toHashSet()
        val seedList: List<Track> = if (s is Seed.MyTasteNow) {
            val top = snap.byId.values.asSequence()
                .filter { it.affinity > 0.2f }
                .sortedByDescending { it.affinity + 0.5f * snap.timeFit(it.f, slot) }
                .take(24).map { it.f.track }.toList()
            val byId = lib.associateBy { it.id }
            (top + recentIds.take(10).mapNotNull { byId[it] }).distinctBy { it.id }
        } else seedTracks
        if (seedList.isEmpty()) return emptyList()

        val seedArtists = seedList.map { TasteModel.artistKey(it) }.toHashSet()
        val seedAlbums = seedList.map { TasteModel.albumKey(it) }.toHashSet()
        val seedGenres = seedList.map { GenreIndex.key(GenreIndex.genreOf(app, it)) }.filter { it.isNotBlank() }.toHashSet()
        val seedYears = seedList.map { it.year }.filter { it > 0 }
        val seedYear = if (seedYears.isEmpty()) 0 else seedYears.sorted()[seedYears.size / 2]
        val seedGenreName = seedList.map { GenreIndex.genreOf(app, it) }.firstOrNull { it.isNotBlank() } ?: ""

        // Co-occurrence neighbourhood of the seed (capped) plus of the last few station plays,
        // so the station drifts with what's actually playing rather than orbiting one point.
        val coocSources = (seedList.take(8).map { it.id } + played.toList().takeLast(3)).distinct()
        val cooc = HashMap<Long, Float>()
        for (id in coocSources) for ((o, w) in db.neighbours(id, 120)) cooc.merge(o, w, Float::plus)
        val coocMax = cooc.values.maxOrNull() ?: 0f
        val coocArtists = HashMap<String, Float>()
        if (coocMax > 0f) {
            val byId = lib.associateBy { it.id }
            for ((id, w) in cooc) byId[id]?.let { coocArtists.merge(TasteModel.artistKey(it), w, Float::plus) }
        }
        val coocArtistMax = coocArtists.values.maxOrNull() ?: 0f

        val queueIds = HashSet<Long>().apply { addAll(queued); addAll(played) }
        val artistSeed = s is Seed.FromArtist
        val albumSeed = s is Seed.FromAlbum
        val single = s is Seed.FromTrack

        val cands = ArrayList<Cand>()
        for (t in lib) {
            if (t.id in queueIds || t.id in recentSet) continue
            if (t.isDiscImage && t.parentId == 0L) continue
            if (t.durationMs > MAX_DURATION_MS || t.durationMs in 1..25_000L) continue
            val sc = snap.scored(t.id) ?: continue
            val f = sc.f
            val whys = ArrayList<String>(3)
            var sim = 0f
            val sameArtist = f.artistKey in seedArtists
            val sameAlbum = f.albumKey in seedAlbums
            val sameGenre = f.genreKey.isNotBlank() && f.genreKey in seedGenres
            if (sameArtist) { sim += if (artistSeed) 0.45f else 0.30f; whys.add(if (single) "same artist as the seed" else if (artistSeed) "by ${s.label}" else "same artist") }
            if (sameAlbum && !albumSeed) { sim += 0.12f; whys.add("same album") }
            if (albumSeed && sameAlbum) { sim += 0.40f; whys.add("from ${s.label}") }
            if (sameGenre) { sim += if (s is Seed.FromGenre) 0.40f else 0.18f; whys.add(f.genre.ifBlank { seedGenreName }) }
            val ys = TasteModel.yearSim(seedYear, t.year)
            if (ys > 0.3f) { sim += 0.10f * ys; if (ys > 0.7f && t.year > 0) whys.add("${t.year}, like the seed") }
            val cw = cooc[t.id]
            if (cw != null && coocMax > 0f) { val c = cw / coocMax; sim += 0.30f * c; if (c > 0.25f) whys.add("you play these together") }
            val caw = coocArtists[f.artistKey]
            if (caw != null && coocArtistMax > 0f && !sameArtist) { val c = caw / coocArtistMax; sim += 0.15f * c; if (c > 0.4f) whys.add("${t.artist} shows up in the same sessions") }
            if (s is Seed.MyTasteNow) {
                // No fixed seed: similarity here is "fits your taste in this day-part".
                val tf = snap.timeFit(f, slot)
                sim += 0.35f * tf
                if (tf > 0.3f) whys.add("you play this in the ${TasteDb.SLOT_NAMES[slot]}")
                val aAff = snap.artistAffinity[f.artistKey] ?: 0f
                sim += 0.30f * aAff
            }
            if (sim <= 0.02f) continue
            val heard = f.everPlayed
            val ctxAff = maxOf(snap.artistAffinity[f.artistKey] ?: 0f, snap.albumAffinity[f.albumKey] ?: 0f, (snap.genreAffinity[f.genreKey] ?: 0f) * 0.8f)
            val tfit = snap.timeFit(f, slot)
            val quality = if (f.hiRes) 0.02f else if (f.lossless) 0.01f else 0f
            val score = if (heard) 0.55f * sim + 0.30f * sc.affinity + 0.15f * tfit + quality
                        else 0.70f * sim + 0.30f * ctxAff + quality
            val why = if (heard) TasteModel.whyThis(sc, whys.take(1)).ifBlank { whys.firstOrNull() ?: "" }
                      else (listOf("new to you") + whys.take(2)).joinToString(" · ")
            cands.add(Cand(t, score.coerceAtLeast(0.001f), why, heard))
        }
        if (cands.isEmpty()) return emptyList()

        // Discovery share: that fraction of the batch comes from unheard/barely-played tracks.
        val d = discovery / 100f
        val wantNew = Math.round(n * d)
        val heardPool = cands.filter { it.heard }.sortedByDescending { it.score }.take(n * 4)
        val newPool = cands.filter { !it.heard }.sortedByDescending { it.score }.take(n * 4)
        val rnd = Random(now xor played.size.toLong())
        fun sample(pool: List<Cand>, k: Int): List<Cand> {
            if (pool.isEmpty() || k <= 0) return emptyList()
            // Weighted sampling without replacement (exponential race on score²).
            return pool.map { c -> c to (-ln(rnd.nextDouble().coerceAtLeast(1e-9)) / (c.score * c.score + 1e-4)) }
                .sortedBy { it.second }.map { it.first }
        }
        val newOrdered = sample(newPool, wantNew)
        val heardOrdered = sample(heardPool, n)
        // Interleave: spread the discovery picks through the batch (in proportion to the
        // slider) rather than clumping them; over-generate so the diversity pass has slack.
        val merged = ArrayList<Cand>(n * 2)
        var ni = 0; var hi = 0
        while (merged.size < n * 2 && (ni < newOrdered.size || hi < heardOrdered.size)) {
            val newSoFar = merged.count { !it.heard }
            val targetNew = Math.round((merged.size + 1) * d)
            val takeNew = ni < newOrdered.size && (newSoFar < targetNew || hi >= heardOrdered.size)
            if (takeNew) merged.add(newOrdered[ni++]) else merged.add(heardOrdered[hi++])
        }

        // Diversity: artist spacing + per-batch caps (artist/album seeds relax the artist cap:
        // an artist station is mostly that artist, but never the same artist back-to-back
        // unless it's an album station, which is meant to stay inside the record).
        val maxPerArtist = if (artistSeed || albumSeed) 5 else 2
        val maxPerAlbum = if (albumSeed) 5 else 2
        val out = ArrayList<Pick>(n)
        val artistCount = HashMap<String, Int>(); val albumCount = HashMap<String, Int>()
        val lastArtists = ArrayDeque<String>()
        val deferred = ArrayList<Cand>()
        fun tryPlace(c: Cand): Boolean {
            val ak = TasteModel.artistKey(c.track); val alk = TasteModel.albumKey(c.track)
            if ((artistCount[ak] ?: 0) >= maxPerArtist) return false
            if ((albumCount[alk] ?: 0) >= maxPerAlbum) return false
            val spacingOk = when {
                albumSeed -> true
                artistSeed -> lastArtists.lastOrNull() != ak
                else -> ak !in lastArtists
            }
            if (!spacingOk) return false
            out.add(Pick(c.track, c.why))
            artistCount[ak] = (artistCount[ak] ?: 0) + 1; albumCount[alk] = (albumCount[alk] ?: 0) + 1
            lastArtists.addLast(ak); while (lastArtists.size > 2) lastArtists.removeFirst()
            return true
        }
        for (c in merged) {
            if (out.size >= n) break
            if (!tryPlace(c)) deferred.add(c)
            val it = deferred.iterator()
            while (it.hasNext() && out.size < n) { val dc = it.next(); if (tryPlace(dc)) it.remove() }
        }
        return out
    }
}
