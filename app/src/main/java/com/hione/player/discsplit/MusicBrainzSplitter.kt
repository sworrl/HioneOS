package com.hione.player.discsplit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.hione.player.DiscImage
import com.hione.player.ScanProgress
import com.hione.player.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * MusicBrainz-backed [DiscImage.WholeDiscSplitter] for cue-less whole-CD images.
 *
 * How it fits the library pipeline:
 *  • [split] is called from [DiscImage.apply] (IO thread, once per image, on every library query)
 *    and must be cheap: it only consults [DiscSplitCache] (in-memory after first load). A cached
 *    match becomes virtual tracks right there; anything else returns null so the image keeps
 *    showing as ONE playable item, and — if online lookups are allowed — queues a lookup.
 *  • One low-priority daemon worker drains the queue, ≤ 1 MusicBrainz request/second (enforced in
 *    [MusicBrainzClient]), never on the UI thread, parking while the network policy says no.
 *  • When results land it calls [ScanProgress.bump] (coalesced: every 15 matches / 45 s / on
 *    drain), which is the same generation counter a card scan bumps — App() re-queries, [DiscImage.apply]
 *    runs again, and this time [split] finds the cached match. So the split is applied lazily:
 *    one item first, N tracks on the next refresh.
 *
 * Matching (see [DiscMatcher] for the numbers): search releases by artist + album, fetch up to
 * [MAX_RELEASE_FETCHES] candidates ordered by MusicBrainz score, accept the medium whose summed
 * track lengths equal the image duration within 1.5 % (+ a 2 s pregap allowance), refuse when two
 * different track lists fit equally well. No confident match → negative-cached for 30 days, the
 * image stays whole. Never guessed.
 *
 * Manual overrides win: [com.hione.player.CueSplitter] (a .cue beside the image) and
 * [TracksTxtSplitter] (a user-written tracks.txt) are registered ahead of this one.
 */
object MusicBrainzSplitter : DiscImage.WholeDiscSplitter {
    override val name = "musicbrainz"
    private const val TAG = "MusicBrainzSplitter"

    private const val SEARCH_LIMIT = 12
    private const val MIN_SEARCH_SCORE = 50
    private const val MAX_RELEASE_FETCHES = 5
    private const val MAX_QUEUE = 2_000
    private const val PARK_MS = 30_000L
    private const val BUMP_EVERY_N = 15
    private const val BUMP_EVERY_MS = 45_000L

    /** Counters from the most recent [DiscImage.apply] pass, for the Settings card. */
    data class Stats(
        val images: Int = 0,        // cue-less images this splitter was asked about
        val matched: Int = 0,       // of those, split from a cached MusicBrainz match
        val pending: Int = 0,       // no answer yet (queued, waiting for network, or lookups off)
        val unmatched: Int = 0,     // looked up, no confident match (negative-cached)
        val errors: Int = 0,        // last attempt failed (network) — retried after 6 h
    )

    /** Bumped whenever [stats], [lastEvent] or the queue change — observe it from Compose. */
    var version by mutableIntStateOf(0)
        private set
    @Volatile var stats = Stats()
        private set
    @Volatile var lastEvent: String = ""
        private set
    val queued: Int get() = lock.withLock { queue.size }
    val isParked: Boolean get() = parked

    private class Pass { var images = 0; var matched = 0; var pending = 0; var unmatched = 0; var errors = 0 }
    private val pass = ThreadLocal<Pass>()

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val queue = LinkedHashMap<String, Track>()      // path → image, FIFO; guarded by [lock]
    private var worker: Thread? = null                       // guarded by [lock]
    @Volatile private var parked = false
    @Volatile private var appCtx: Context? = null

    // ---- WholeDiscSplitter --------------------------------------------------------------------

    override fun beginPass(ctx: Context) {
        appCtx = ctx.applicationContext
        pass.set(Pass())
    }

    override fun endPass(ctx: Context) {
        val p = pass.get()
        pass.remove()
        if (p != null) stats = Stats(p.images, p.matched, p.pending + p.errors, p.unmatched, p.errors)
        version++
    }

    override fun split(ctx: Context, image: Track): List<Track>? {
        if (image.path.isBlank() || image.durationMs <= 0L || image.parentId != 0L) return null
        appCtx = ctx.applicationContext
        val p = pass.get()
        p?.let { it.images++ }
        val e = DiscSplitCache.get(ctx, image.path, fingerprint(image))
        if (e != null && e.isFresh) {
            when (e.status) {
                DiscSplitCache.MATCHED -> {
                    val v = materialize(image, e)
                    if (v != null) { p?.let { it.matched++ }; return v }
                    p?.let { it.unmatched++ }; return null
                }
                DiscSplitCache.UNMATCHED -> { p?.let { it.unmatched++ }; return null }
                else -> { p?.let { it.errors++ }; return null }
            }
        }
        p?.let { it.pending++ }
        if (DiscSplitPrefs.splitOnline(ctx)) enqueue(ctx, image)
        return null
    }

    // ---- Public controls (Settings card / prefs) ---------------------------------------------

    /** Re-evaluate: wake a parked worker and, if nothing is queued, ask the library to re-run apply(). */
    fun kick(ctx: Context) {
        appCtx = ctx.applicationContext
        val empty = lock.withLock { wake.signalAll(); queue.isEmpty() }
        if (empty) ScanProgress.bump() else ensureWorker()
    }

    /** Forget every unmatched/error record and look them up again. Returns how many were cleared. */
    fun recheckUnmatched(ctx: Context): Int {
        val n = DiscSplitCache.clearNegative(ctx)
        lastEvent = "Re-checking $n image${if (n == 1) "" else "s"}"
        version++
        kick(ctx)
        return n
    }

    /** Forget ALL lookups (including matches — the way out of a wrong match). Images collapse back
     *  to whole items on the next refresh and are looked up again. */
    fun resetAll(ctx: Context): Int {
        val n = DiscSplitCache.clearAll(ctx)
        lock.withLock { queue.clear() }
        lastEvent = "Cleared $n lookup${if (n == 1) "" else "s"}"
        version++
        ScanProgress.bump()
        return n
    }

    /** Forget the lookup for one image (long-press "wrong split" style actions can call this). */
    fun forget(ctx: Context, imagePath: String) {
        DiscSplitCache.remove(ctx, imagePath)
        lock.withLock { queue.remove(imagePath) }
        version++
        ScanProgress.bump()
    }

    // ---- Virtual tracks ------------------------------------------------------------------------

    /** Same shape [com.hione.player.CueSplitter] produces, so playback/persistence treat them identically. */
    private fun materialize(image: Track, e: DiscSplitCache.Entry): List<Track>? {
        val cuts = e.cuts
        if (cuts.size < 2) return null
        val total = image.durationMs
        return cuts.mapIndexed { i, c ->
            val end = if (c.endMs > c.startMs) c.endMs else total
            image.copy(
                id = -(image.id * 1000L + (i + 1)),          // stable synthetic id: parent id + slot
                title = c.title.ifBlank { "Track ${c.number}" },
                artist = c.artist.ifBlank { image.artist },
                durationMs = (end - c.startMs).coerceAtLeast(0L),
                sizeBytes = 0L,
                trackNumber = c.number.takeIf { it > 0 } ?: (i + 1),
                isDiscImage = true,
                parentId = image.id,
                clipStartMs = c.startMs,
                clipEndMs = if (c.endMs > c.startMs && c.endMs < total) c.endMs else 0L,
            )
        }
    }

    private fun fingerprint(t: Track): String {
        val mtime = runCatching { File(t.path).lastModified() }.getOrDefault(0L)
        return "$mtime:${t.sizeBytes}:${t.durationMs}"
    }

    // ---- Queue + worker ----------------------------------------------------------------------

    private fun enqueue(ctx: Context, image: Track) {
        lock.withLock {
            if (queue.containsKey(image.path)) return
            if (queue.size >= MAX_QUEUE) return
            queue[image.path] = image
            wake.signalAll()
        }
        ensureWorker()
    }

    private fun ensureWorker() {
        lock.withLock {
            val w = worker
            if (w != null && w.isAlive) return
            worker = Thread({ runWorker() }, "HioneDiscSplit").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    private fun runWorker() {
        val ctx = appCtx ?: return
        var landed = 0
        var lastBump = SystemClock.elapsedRealtime()
        while (true) {
            var item: Track? = null
            lock.withLock {
                if (queue.isEmpty()) { worker = null; return }
                if (!DiscSplitPrefs.splitOnline(ctx) || !networkAllowed(ctx)) {
                    parked = true
                    wake.await(PARK_MS, TimeUnit.MILLISECONDS)
                    parked = false
                } else {
                    val it = queue.entries.iterator()
                    val first = it.next(); it.remove()
                    item = first.value
                }
            }
            val image = item ?: continue
            val started = SystemClock.elapsedRealtime()
            val entry = try {
                lookup(ctx, image)
            } catch (t: Throwable) {
                Log.w(TAG, "lookup failed for ${image.path}: ${t.message}")
                DiscSplitCache.Entry(image.path, fingerprint(image), DiscSplitCache.ERROR, System.currentTimeMillis(),
                    reason = t.message ?: t.javaClass.simpleName)
            }
            DiscSplitCache.put(ctx, entry)
            val label = "${image.artist} – ${image.album}".trim(' ', '–')
            lastEvent = when (entry.status) {
                DiscSplitCache.MATCHED -> "Split \"$label\" into ${entry.cuts.size} tracks (${entry.releaseTitle}, Δ${entry.diffMs} ms)"
                DiscSplitCache.UNMATCHED -> "No match for \"$label\": ${entry.reason}"
                else -> "Lookup error for \"$label\": ${entry.reason}"
            }
            Log.i(TAG, "$lastEvent in ${SystemClock.elapsedRealtime() - started} ms")
            version++
            if (entry.status == DiscSplitCache.MATCHED) landed++
            val drained = lock.withLock { queue.isEmpty() }
            val now = SystemClock.elapsedRealtime()
            if (landed > 0 && (drained || landed >= BUMP_EVERY_N || now - lastBump >= BUMP_EVERY_MS)) {
                ScanProgress.bump()
                landed = 0; lastBump = now
            }
        }
    }

    private fun networkAllowed(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (DiscSplitPrefs.wifiOnly(ctx)) {
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        return true
    }

    // ---- The lookup ----------------------------------------------------------------------------

    private data class SearchHit(val id: String, val score: Int, val title: String, val artist: String, val mediaCount: Int)

    private fun lookup(ctx: Context, image: Track): DiscSplitCache.Entry {
        val fp = fingerprint(image)
        val now = System.currentTimeMillis()
        fun unmatched(reason: String) = DiscSplitCache.Entry(image.path, fp, DiscSplitCache.UNMATCHED, now, reason = reason)

        val artist = cleanArtist(image.albumArtist.ifBlank { image.artist })
        var album = image.album.trim()
        if (album.isBlank()) album = albumFromFilename(image.path, artist)
        if (album.isBlank()) return unmatched("no album tag to search with")
        val cleaned = cleanAlbum(album)

        // Query ladder: exact tags → cleaned title → title-only (artist checked loosely afterwards).
        val queries = LinkedHashSet<String>()
        queries += buildQuery(artist, album)
        if (cleaned.isNotBlank() && cleaned != album) queries += buildQuery(artist, cleaned)
        val titleOnly = if (artist.isNotBlank()) buildQuery("", cleaned.ifBlank { album }).also { queries += it } else null

        val hits = LinkedHashMap<String, SearchHit>()
        for (q in queries) {
            val arr = MusicBrainzClient.searchReleases(q, SEARCH_LIMIT)
            for (i in 0 until arr.length()) {
                val h = parseHit(arr.optJSONObject(i) ?: continue) ?: continue
                if (h.score < MIN_SEARCH_SCORE) continue
                if (q == titleOnly && !artistCompatible(h.artist, artist)) continue   // title-only fallback: keep the artist honest
                hits.putIfAbsent(h.id, h)
            }
            if (hits.size >= 3) break
        }
        if (hits.isEmpty()) return unmatched("no MusicBrainz release for \"${listOf(artist, album).filter { it.isNotBlank() }.joinToString(" – ")}\"")

        val ordered = hits.values.sortedWith(
            compareBy<SearchHit>({ if (image.discNumber > 1 && it.mediaCount < image.discNumber) 1 else 0 })
                .thenByDescending { it.score }
        )
        val releases = ArrayList<DiscMatcher.MbRelease>()
        var fetched = 0
        for (h in ordered) {
            if (fetched >= MAX_RELEASE_FETCHES) break
            val rel = try {
                parseRelease(MusicBrainzClient.fetchRelease(h.id), h.score)
            } catch (e: MusicBrainzClient.HttpException) {
                if (e.code == 404) null else throw e
            } ?: continue
            fetched++
            releases += rel
            val c = DiscMatcher.choose(image.durationMs, image.discNumber, releases)
            if (c != null && DiscMatcher.isTight(c.diffMs, image.durationMs)) break
        }
        val chosen = DiscMatcher.choose(image.durationMs, image.discNumber, releases)
        if (chosen == null) {
            val anyFit = DiscMatcher.fittingCandidates(image.durationMs, image.discNumber, releases).isNotEmpty()
            return unmatched(
                if (anyFit) "ambiguous: two different track lists fit ${fmtMs(image.durationMs)}"
                else "$fetched release${if (fetched == 1) "" else "s"} checked, none runs ${fmtMs(image.durationMs)}"
            )
        }
        val cuts = DiscMatcher.cuts(image.durationMs, chosen.medium, chosen.diffMs)
        if (cuts.size < 2) return unmatched("track list too short")
        return DiscSplitCache.Entry(
            path = image.path, fingerprint = fp, status = DiscSplitCache.MATCHED, atMs = now,
            releaseId = chosen.release.id, releaseTitle = chosen.release.title, releaseArtist = chosen.release.artist,
            mediumPosition = chosen.medium.position, diffMs = chosen.diffMs,
            reason = "score ${chosen.release.score}, ${chosen.release.date} ${chosen.release.country}".trim(),
            cuts = cuts,
        )
    }

    // ---- Query building ----------------------------------------------------------------------

    private val UNKNOWN_ARTIST_RE = Regex("(?i)^\\s*(<unknown>|unknown( artist)?)\\s*$")
    private val BRACKET_RE = Regex("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}")
    private val DISC_SUFFIX_RE = Regex("(?i)[\\s,\\-–:]*\\b(cd|disc|disk)\\s*\\.?\\s*\\d{1,2}\\b.*$")
    private val WS_RE = Regex("\\s+")
    private val STEM_EXT_RE = Regex("(?i)\\.(flac|ape|wav|wv|dsf|dff|m4a|mp3|ogg|opus|tta|mpc)$")
    private val LEADING_00_RE = Regex("^\\s*00[ ._-]*")
    private val MULTI_DISC_SUFFIX_RE = Regex("\\.\\d$")
    private val IMAGE_STEM_RE = Regex("(?i)^(cdimage|image|cd|disc|00)?$")

    private fun cleanArtist(a: String): String {
        val s = a.trim()
        // "Various Artists" IS a valid MusicBrainz credit; only true unknowns become blank.
        if (s.isBlank()) return ""
        if (UNKNOWN_ARTIST_RE.matches(s)) return ""
        return s
    }

    private fun cleanAlbum(album: String): String {
        var s = BRACKET_RE.replace(album, " ")
        s = DISC_SUFFIX_RE.replace(s, "")
        s = WS_RE.replace(s, " ").trim(' ', '-', '–', ':', ',')
        return s
    }

    /** "00 Arch Enemy - Wages Of Sin.flac" → "Wages Of Sin" (when the artist is known), else the stem. */
    private fun albumFromFilename(path: String, artist: String): String {
        var stem = path.substringAfterLast('/')
        stem = STEM_EXT_RE.replace(stem, "")
        stem = MULTI_DISC_SUFFIX_RE.replace(stem, "")
        stem = LEADING_00_RE.replace(stem, "")
        stem = BRACKET_RE.replace(stem, " ").let { WS_RE.replace(it, " ") }.trim()
        if (IMAGE_STEM_RE.matches(stem)) return ""
        if (artist.isNotBlank() && stem.lowercase().startsWith(artist.lowercase())) {
            val rest = stem.substring(artist.length).trim(' ', '-', '–', '_')
            if (rest.isNotBlank()) return rest
        }
        return stem
    }

    private fun buildQuery(artist: String, album: String): String {
        val parts = ArrayList<String>(2)
        if (artist.isNotBlank()) parts += "artist:${MusicBrainzClient.phrase(artist)}"
        parts += "release:${MusicBrainzClient.phrase(album)}"
        return parts.joinToString(" AND ")
    }

    private fun artistCompatible(mbArtist: String, ours: String): Boolean {
        if (ours.isBlank()) return true
        val a = DiscMatcher.norm(mbArtist); val b = DiscMatcher.norm(ours)
        if (a.isBlank() || b.isBlank()) return false
        return a.contains(b) || b.contains(a) || a == "variousartists"
    }

    private fun fmtMs(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    // ---- JSON parsing --------------------------------------------------------------------------

    private fun artistCredit(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val name = c.optString("name", "").ifBlank { c.optJSONObject("artist")?.optString("name", "") ?: "" }
            sb.append(name).append(c.optString("joinphrase", ""))
        }
        return sb.toString().trim()
    }

    private fun parseHit(o: JSONObject): SearchHit? {
        val id = o.optString("id", ""); if (id.isBlank()) return null
        return SearchHit(
            id = id, score = o.optInt("score", 0), title = o.optString("title", ""),
            artist = artistCredit(o.optJSONArray("artist-credit")),
            mediaCount = o.optJSONArray("media")?.length()?.takeIf { it > 0 } ?: 1,
        )
    }

    private fun parseRelease(o: JSONObject, score: Int): DiscMatcher.MbRelease? {
        val id = o.optString("id", ""); if (id.isBlank()) return null
        val releaseArtist = artistCredit(o.optJSONArray("artist-credit"))
        val mediaArr = o.optJSONArray("media") ?: JSONArray()
        val media = ArrayList<DiscMatcher.MbMedium>(mediaArr.length())
        for (i in 0 until mediaArr.length()) {
            val m = mediaArr.optJSONObject(i) ?: continue
            val tracksArr = m.optJSONArray("tracks") ?: JSONArray()
            val tracks = ArrayList<DiscMatcher.MbTrack>(tracksArr.length())
            for (j in 0 until tracksArr.length()) {
                val t = tracksArr.optJSONObject(j) ?: continue
                val rec = t.optJSONObject("recording")
                val title = t.optString("title", "").ifBlank { rec?.optString("title", "") ?: "" }
                val len = if (t.isNull("length")) (rec?.takeIf { !it.isNull("length") }?.optLong("length", 0L) ?: 0L) else t.optLong("length", 0L)
                val credit = artistCredit(t.optJSONArray("artist-credit")).ifBlank { artistCredit(rec?.optJSONArray("artist-credit")) }.ifBlank { releaseArtist }
                tracks += DiscMatcher.MbTrack(position = t.optInt("position", j + 1), title = title, artist = credit, lengthMs = len)
            }
            media += DiscMatcher.MbMedium(position = m.optInt("position", i + 1), format = m.optString("format", ""), tracks = tracks)
        }
        return DiscMatcher.MbRelease(
            id = id, title = o.optString("title", ""), artist = releaseArtist,
            date = o.optString("date", ""), country = o.optString("country", ""), score = score, media = media,
        )
    }
}
