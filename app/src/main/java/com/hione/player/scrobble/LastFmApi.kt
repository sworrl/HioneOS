package com.hione.player.scrobble

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Synchronous Last.fm Audioscrobbler API 2.0 transport used by [ScrobbleManager]. Dependency-free
 * (HttpURLConnection + org.json). Every call is blocking — callers run it on their own IO thread.
 *
 * Only the pieces the manager needs live here: batched track.scrobble (up to 50 per call, the
 * indexed `artist[i]`/`track[i]`/`timestamp[i]` form) and track.updateNowPlaying. Login and
 * love/unlove stay in the original [[com.hione.player.LastFm]] client, which now signs with the
 * same [LastFmCredentials] resolver so a user-entered key applies everywhere.
 *
 * Error codes (https://www.last.fm/api/errorcodes) are classified so the manager can tell
 * "retry later" (11 service offline, 16 temporary error, 29 rate limit, any network failure) from
 * "stop until the user fixes something" (4 auth failed, 9 invalid session key, 10 invalid API key,
 * 13 invalid signature, 26 key suspended).
 */
object LastFmApi {
    private const val API_ROOT = "https://ws.audioscrobbler.com/2.0/"
    private const val TIMEOUT_MS = 10_000

    sealed class ApiError(val message: String, val retryable: Boolean, val invalidatesSession: Boolean = false) {
        class Network(message: String) : ApiError(message, retryable = true)
        class Temporary(code: Int, message: String) : ApiError("[$code] $message", retryable = true)
        class Hard(code: Int, message: String, invalidatesSession: Boolean = false) : ApiError("[$code] $message", retryable = false, invalidatesSession = invalidatesSession)
    }

    class ApiException(val error: ApiError) : IOException(error.message)

    data class ScrobbleResult(val accepted: Int, val ignored: Int, val ignoredMessages: List<String>)

    data class ScrobbleItem(val artist: String, val track: String, val album: String?, val timestampSec: Long, val durationSec: Int?)

    /** @throws ApiException on any failure, classified. */
    fun updateNowPlaying(sk: String, artist: String, track: String, album: String?, durationSec: Int?) {
        val params = sortedMapOf(
            "method" to "track.updateNowPlaying",
            "artist" to artist,
            "track" to track,
            "api_key" to LastFmCredentials.apiKey(),
            "sk" to sk
        )
        if (!album.isNullOrBlank()) params["album"] = album
        if (durationSec != null && durationSec > 0) params["duration"] = durationSec.toString()
        call(params)
    }

    /** Batched track.scrobble. Last.fm caps a single call at 50 items. @throws ApiException. */
    fun scrobble(sk: String, items: List<ScrobbleItem>): ScrobbleResult {
        require(items.isNotEmpty() && items.size <= 50) { "1..50 scrobbles per call" }
        val params = sortedMapOf(
            "method" to "track.scrobble",
            "api_key" to LastFmCredentials.apiKey(),
            "sk" to sk
        )
        items.forEachIndexed { i, it ->
            params["artist[$i]"] = it.artist
            params["track[$i]"] = it.track
            params["timestamp[$i]"] = it.timestampSec.toString()
            if (!it.album.isNullOrBlank()) params["album[$i]"] = it.album
            if (it.durationSec != null && it.durationSec > 0) params["duration[$i]"] = it.durationSec.toString()
        }
        val json = call(params)
        val scrobbles = json.optJSONObject("scrobbles")
        val attr = scrobbles?.optJSONObject("@attr")
        val accepted = attr?.optInt("accepted", -1) ?: -1
        val ignored = attr?.optInt("ignored", -1) ?: -1
        val ignoredMsgs = ArrayList<String>()
        // "scrobble" is an object for a single item and an array for a batch.
        val single = scrobbles?.optJSONObject("scrobble")
        val many = scrobbles?.optJSONArray("scrobble")
        fun collect(o: JSONObject?) {
            val im = o?.optJSONObject("ignoredMessage") ?: return
            val code = im.optString("code", "0")
            if (code != "0") ignoredMsgs.add(im.optString("#text").ifBlank { "ignored (code $code)" })
        }
        if (single != null) collect(single)
        if (many != null) for (i in 0 until many.length()) collect(many.optJSONObject(i))
        if (accepted < 0 && ignored < 0) {
            // No @attr at all: treat the whole batch as accepted only if the server didn't error —
            // call() already threw on an error object — but record that we couldn't verify.
            return ScrobbleResult(items.size, 0, emptyList())
        }
        return ScrobbleResult(accepted.coerceAtLeast(0), ignored.coerceAtLeast(0), ignoredMsgs)
    }

    // ------------------------------------------------------------------ plumbing

    private fun call(params: Map<String, String>): JSONObject {
        if (!LastFmCredentials.isConfigured) throw ApiException(ApiError.Hard(10, "No API key configured", invalidatesSession = false))
        val sig = sign(params)
        val json = try {
            post(params + mapOf("api_sig" to sig, "format" to "json"))
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(ApiError.Network(e.message ?: e.javaClass.simpleName))
        }
        if (json.has("error")) {
            val code = json.optInt("error", -1)
            val msg = json.optString("message").ifBlank { "Last.fm error" }
            throw ApiException(classify(code, msg))
        }
        return json
    }

    private fun classify(code: Int, msg: String): ApiError = when (code) {
        11, 16, 29, 8 -> ApiError.Temporary(code, msg)        // offline / temporary / rate-limited / op failed
        9 -> ApiError.Hard(code, "Session expired. Sign in again ($msg)", invalidatesSession = true)
        4, 10, 13, 26 -> ApiError.Hard(code, msg)             // auth failed / bad key / bad sig / key suspended
        else -> ApiError.Hard(code, msg)
    }

    /** Last.fm's signing recipe: params sorted by name, concatenated name+value, + secret, MD5 hex.
     *  Excludes format/api_sig — they're added after signing. */
    private fun sign(params: Map<String, String>): String {
        val base = params.toSortedMap().entries.joinToString("") { (k, v) -> k + v } + LastFmCredentials.apiSecret()
        val digest = MessageDigest.getInstance("MD5").digest(base.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun post(params: Map<String, String>): JSONObject {
        val conn = URL(API_ROOT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "HioneMusic/M500 (+https://www.last.fm/api)")
            val body = params.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (text.isBlank()) {
                if (code in 200..299) return JSONObject()
                throw ApiException(if (code >= 500 || code == 429) ApiError.Temporary(code, "HTTP $code") else ApiError.Hard(code, "HTTP $code"))
            }
            return try { JSONObject(text) } catch (e: Exception) {
                throw ApiException(if (code >= 500) ApiError.Temporary(code, "HTTP $code (unparseable body)") else ApiError.Network("Unparseable response (HTTP $code)"))
            }
        } finally {
            conn.disconnect()
        }
    }
}
