package com.hione.player.discsplit

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Minimal MusicBrainz WS/2 client (HttpURLConnection + org.json, JSON via fmt=json).
 *
 * MusicBrainz's terms: identify yourself with a real User-Agent and stay at or under ONE request
 * per second per application. Both are enforced HERE, globally, so no caller can accidentally
 * exceed them — every request goes through [throttle], which hands out send slots 1.1 s apart
 * across all threads. Every call blocks; callers run it on their own worker thread.
 */
object MusicBrainzClient {
    private const val TAG = "MusicBrainz"
    const val USER_AGENT = "HioneMusic/2.0 (https://github.com/sworrl/m500)"
    private const val ROOT = "https://musicbrainz.org/ws/2/"
    private const val MIN_INTERVAL_MS = 1_100L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_RATE_LIMIT_RETRIES = 2

    class HttpException(val code: Int, message: String) : IOException(message)

    private val gate = Any()
    private var nextSlotAt = 0L

    /** Reserve the next send slot and sleep until it arrives (≤ 1 req/s, globally). */
    private fun throttle() {
        val wait: Long
        synchronized(gate) {
            val now = SystemClock.elapsedRealtime()
            val slot = maxOf(now, nextSlotAt)
            wait = slot - now
            nextSlotAt = slot + MIN_INTERVAL_MS
        }
        if (wait > 0) Thread.sleep(wait)
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Blocking GET of `ROOT + pathAndQuery`; the caller appends `fmt=json` itself via the helpers. */
    fun getJson(pathAndQuery: String): JSONObject {
        var rateLimitRetries = 0
        while (true) {
            throttle()
            val conn = URL(ROOT + pathAndQuery).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                val code = conn.responseCode
                if (code == 503 || code == 429) {
                    // MusicBrainz answers 503 when a client exceeds its rate; back off and retry a
                    // couple of times, honouring Retry-After when it's given.
                    val retryAfterS = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 3L
                    if (rateLimitRetries++ < MAX_RATE_LIMIT_RETRIES) {
                        Log.w(TAG, "HTTP $code from MusicBrainz, backing off ${retryAfterS}s")
                        Thread.sleep((retryAfterS * 1000L).coerceIn(1_000L, 15_000L))
                        continue
                    }
                    throw HttpException(code, "MusicBrainz rate-limited (HTTP $code)")
                }
                if (code != 200) throw HttpException(code, "MusicBrainz HTTP $code for $pathAndQuery")
                val raw = conn.inputStream
                val ins = if (conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
                val text = ins.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return try { JSONObject(text) } catch (e: JSONException) { throw IOException("Bad JSON from MusicBrainz", e) }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Lucene release search. Returns the `releases` array (possibly empty). */
    fun searchReleases(luceneQuery: String, limit: Int): JSONArray =
        getJson("release/?query=${enc(luceneQuery)}&fmt=json&limit=$limit").optJSONArray("releases") ?: JSONArray()

    /** A single release with its media, tracks and per-track artist credits. */
    fun fetchRelease(mbid: String): JSONObject =
        getJson("release/$mbid?inc=recordings+artist-credits&fmt=json")

    /** Lucene phrase: `"…"` with the only two special characters inside a phrase escaped. */
    fun phrase(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
