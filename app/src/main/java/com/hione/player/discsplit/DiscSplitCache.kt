package com.hione.player.discsplit

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * On-disk memory of MusicBrainz lookups: one small JSON file per image path under
 * `filesDir/discsplit/`, keyed by a hash of the path, holding the file fingerprint
 * (mtime:size:duration) it was computed for, the outcome, and — for a match — the cut list.
 * Loaded once per process into a ConcurrentHashMap, so [DiscImage.apply] pays no file I/O per image.
 *
 *  • matched   — kept indefinitely (until the file changes or the user resets)
 *  • unmatched — negative cache, re-tried after [TTL_UNMATCHED_MS] (30 days) or "Re-check unmatched"
 *  • error     — network/parse failure, re-tried after [TTL_ERROR_MS]
 */
object DiscSplitCache {
    private const val TAG = "DiscSplitCache"
    private const val DIR = "discsplit"
    private const val FORMAT = 1
    const val TTL_UNMATCHED_MS = 30L * 24 * 3_600_000L
    const val TTL_ERROR_MS = 6L * 3_600_000L

    const val MATCHED = "matched"
    const val UNMATCHED = "unmatched"
    const val ERROR = "error"

    data class Entry(
        val path: String,
        val fingerprint: String,
        val status: String,
        val atMs: Long,
        val releaseId: String = "",
        val releaseTitle: String = "",
        val releaseArtist: String = "",
        val mediumPosition: Int = 0,
        val diffMs: Long = 0L,
        val reason: String = "",
        val cuts: List<DiscMatcher.Cut> = emptyList(),
    ) {
        val isFresh: Boolean get() = when (status) {
            MATCHED -> true
            UNMATCHED -> System.currentTimeMillis() - atMs < TTL_UNMATCHED_MS
            else -> System.currentTimeMillis() - atMs < TTL_ERROR_MS
        }
    }

    private val map = ConcurrentHashMap<String, Entry>()
    @Volatile private var loaded = false
    private val loadLock = Any()

    private fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR)

    private fun key(path: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(40)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val files = dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
            var bad = 0
            for (f in files) {
                val e = runCatching { fromJson(JSONObject(f.readText(Charsets.UTF_8))) }.getOrNull()
                if (e == null) { bad++; f.delete(); continue }
                map[e.path] = e
            }
            loaded = true
            if (files.isNotEmpty()) Log.i(TAG, "Loaded ${map.size} disc-split records (${bad} corrupt dropped)")
        }
    }

    /** The record for [path] if it exists and was computed for the same file ([fingerprint]). */
    fun get(ctx: Context, path: String, fingerprint: String): Entry? {
        ensureLoaded(ctx)
        val e = map[path] ?: return null
        return if (e.fingerprint == fingerprint) e else null
    }

    fun put(ctx: Context, e: Entry) {
        ensureLoaded(ctx)
        map[e.path] = e
        runCatching {
            val d = dir(ctx); if (!d.exists()) d.mkdirs()
            val target = File(d, key(e.path) + ".json")
            val tmp = File(d, key(e.path) + ".tmp")
            tmp.writeText(toJson(e).toString(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }.onFailure { Log.w(TAG, "write failed for ${e.path}", it) }
    }

    fun remove(ctx: Context, path: String) {
        ensureLoaded(ctx)
        map.remove(path)
        runCatching { File(dir(ctx), key(path) + ".json").delete() }
    }

    /** Drop every negative / error record so "Re-check unmatched" can look them up again. */
    fun clearNegative(ctx: Context): Int {
        ensureLoaded(ctx)
        val victims = map.values.filter { it.status != MATCHED }.map { it.path }
        for (p in victims) remove(ctx, p)
        return victims.size
    }

    fun clearAll(ctx: Context): Int {
        ensureLoaded(ctx)
        val n = map.size
        map.clear()
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
        return n
    }

    /** (matched, unmatched, error) record counts on disk — for the Settings card. */
    fun counts(ctx: Context): Triple<Int, Int, Int> {
        ensureLoaded(ctx)
        var m = 0; var u = 0; var e = 0
        for (v in map.values) when (v.status) { MATCHED -> m++; UNMATCHED -> u++; else -> e++ }
        return Triple(m, u, e)
    }

    // ---- JSON ------------------------------------------------------------------------------------

    private fun toJson(e: Entry): JSONObject = JSONObject().apply {
        put("v", FORMAT)
        put("path", e.path)
        put("fp", e.fingerprint)
        put("status", e.status)
        put("at", e.atMs)
        put("release", e.releaseId)
        put("title", e.releaseTitle)
        put("artist", e.releaseArtist)
        put("medium", e.mediumPosition)
        put("diff", e.diffMs)
        put("reason", e.reason)
        put("cuts", JSONArray().apply {
            for (c in e.cuts) put(JSONObject().apply {
                put("n", c.number); put("t", c.title); put("a", c.artist); put("s", c.startMs); put("e", c.endMs)
            })
        })
    }

    private fun fromJson(o: JSONObject): Entry? {
        if (o.optInt("v", 0) != FORMAT) return null
        val path = o.optString("path", ""); if (path.isBlank()) return null
        val cutsArr = o.optJSONArray("cuts") ?: JSONArray()
        val cuts = ArrayList<DiscMatcher.Cut>(cutsArr.length())
        for (i in 0 until cutsArr.length()) {
            val c = cutsArr.getJSONObject(i)
            cuts += DiscMatcher.Cut(c.optInt("n", i + 1), c.optString("t", ""), c.optString("a", ""), c.optLong("s", 0L), c.optLong("e", 0L))
        }
        return Entry(
            path = path, fingerprint = o.optString("fp", ""), status = o.optString("status", ERROR),
            atMs = o.optLong("at", 0L), releaseId = o.optString("release", ""), releaseTitle = o.optString("title", ""),
            releaseArtist = o.optString("artist", ""), mediumPosition = o.optInt("medium", 0), diffMs = o.optLong("diff", 0L),
            reason = o.optString("reason", ""), cuts = cuts,
        )
    }
}
