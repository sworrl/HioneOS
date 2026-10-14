package com.hione.player.api

import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.media3.common.Player
import com.hione.player.FastLibraryStore
import com.hione.player.PlayerHolder
import com.hione.player.PlayerPreferences
import com.hione.player.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Handles REST routing and JSON payload processing for Hione Remote Control & Telemetry API.
 */
class HioneApiRouter(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun handleRequest(
        method: String,
        path: String,
        queryParams: Map<String, String>,
        body: ByteArray
    ): ApiResponse {
        val cleanPath = path.substringBefore("?").trimEnd('/')
        val bodyStr = if (body.isNotEmpty()) String(body, Charsets.UTF_8) else "{}"
        val jsonBody = try { JSONObject(bodyStr) } catch (_: Throwable) { JSONObject() }

        return when {
            // Web Remote & TV Stage HTML
            (cleanPath.isEmpty() || cleanPath == "/" || cleanPath == "/remote" || cleanPath == "/connect") && method == "GET" -> {
                ApiResponse.html(200, HioneWebRemoteHtml.getRemoteHtml(isTvMode = false))
            }

            (cleanPath == "/tv" || cleanPath == "/stage") && method == "GET" -> {
                ApiResponse.html(200, HioneWebRemoteHtml.getRemoteHtml(isTvMode = true))
            }

            // Health Ping
            cleanPath == "/api/v1/ping" && method == "GET" -> {
                val res = JSONObject().apply {
                    put("status", "ok")
                    put("app", "HioneMusic")
                    put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    put("version", com.hione.player.BuildConfig.VERSION_NAME)   // real app version, not a literal
                    put("timestamp", System.currentTimeMillis())
                }
                ApiResponse.json(200, res)
            }

            // Real audio levels for the web remote's bars: the shared Visualizer's 64-bin FFT.
            // "active" is false whenever no Visualizer is bound — the page must then draw nothing.
            cleanPath == "/api/v1/levels" && method == "GET" -> {
                val active = com.hione.player.AudioCapture.active
                val res = JSONObject().apply {
                    put("active", active)
                    put("is_playing", runCatching { PlayerHolder.player?.isPlaying == true }.getOrDefault(false))
                    val arr = org.json.JSONArray()
                    if (active) com.hione.player.AudioCapture.fft.forEach { arr.put((it * 1000).toInt() / 1000.0) }
                    put("fft", arr)
                }
                ApiResponse.json(200, res)
            }

            // Status / Telemetry
            cleanPath == "/api/v1/status" && method == "GET" -> {
                getStatus()
            }

            // Artwork
            cleanPath.startsWith("/api/v1/artwork") && method == "GET" -> {
                getArtworkResponse(cleanPath)
            }

            // Audio Stream for TV / Browser / DLNA Speakers
            cleanPath.startsWith("/api/v1/audio/stream") && method == "GET" -> {
                getAudioStreamResponse(cleanPath)
            }

            // Playback Controls
            cleanPath == "/api/v1/playback/play" && method == "POST" -> {
                runOnMainSync { PlayerHolder.player?.play() }
                getStatus()
            }

            cleanPath == "/api/v1/playback/pause" && method == "POST" -> {
                runOnMainSync { PlayerHolder.player?.pause() }
                getStatus()
            }

            cleanPath == "/api/v1/playback/toggle" && method == "POST" -> {
                runOnMainSync {
                    val p = PlayerHolder.player
                    if (p != null) {
                        if (p.isPlaying) p.pause() else p.play()
                    }
                }
                getStatus()
            }

            cleanPath == "/api/v1/playback/next" && method == "POST" -> {
                runOnMainSync { PlayerHolder.player?.seekToNextMediaItem() }
                getStatus()
            }

            cleanPath == "/api/v1/playback/previous" && method == "POST" -> {
                runOnMainSync { PlayerHolder.player?.seekToPreviousMediaItem() }
                getStatus()
            }

            cleanPath == "/api/v1/playback/like" && method == "POST" -> {
                runOnMainSync {
                    val trackId = jsonBody.optLong("track_id", -1L).takeIf { it > 0 }
                        ?: PlayerHolder.player?.currentMediaItem?.mediaId?.toLongOrNull()
                        ?: PlayerPreferences.loadLastTrackId(context)
                    if (trackId > 0) {
                        com.hione.player.LikeStore.init(context)
                        com.hione.player.LikeStore.toggle(context, trackId)
                    }
                }
                getStatus()
            }

            cleanPath == "/api/v1/playback/seek" && method == "POST" -> {
                val posMs = jsonBody.optLong("position_ms", -1L)
                if (posMs >= 0) {
                    runOnMainSync { PlayerHolder.player?.seekTo(posMs) }
                }
                getStatus()
            }

            cleanPath == "/api/v1/playback/volume" && method == "POST" -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVol = jsonBody.optInt("volume", -1)
                val delta = jsonBody.optInt("delta", 0)

                if (targetVol in 0..100) {
                    val streamVol = (targetVol * max) / 100
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, streamVol, 0)
                } else if (delta != 0) {
                    val dir = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                }
                getStatus()
            }

            cleanPath == "/api/v1/playback/mode" && method == "POST" -> {
                val repeatStr = jsonBody.optString("repeat", "")
                val shuffleVal = if (jsonBody.has("shuffle")) jsonBody.getBoolean("shuffle") else null

                runOnMainSync {
                    val p = PlayerHolder.player ?: return@runOnMainSync
                    when (repeatStr.lowercase()) {
                        "all" -> p.repeatMode = Player.REPEAT_MODE_ALL
                        "one" -> p.repeatMode = Player.REPEAT_MODE_ONE
                        "off" -> p.repeatMode = Player.REPEAT_MODE_OFF
                    }
                    if (shuffleVal != null) {
                        p.shuffleModeEnabled = shuffleVal
                    }
                }
                getStatus()
            }

            // Queue
            cleanPath == "/api/v1/queue" && method == "GET" -> {
                getQueue()
            }

            cleanPath == "/api/v1/queue/play" && method == "POST" -> {
                val index = jsonBody.optInt("index", -1)
                if (index >= 0) {
                    runOnMainSync { PlayerHolder.player?.seekTo(index, 0L) }
                }
                getStatus()
            }

            // Library Search
            cleanPath == "/api/v1/library/search" && method == "GET" -> {
                val query = queryParams["q"] ?: ""
                val limit = (queryParams["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                searchLibrary(query, limit)
            }


            // 404 Not Found
            else -> {
                ApiResponse.json(404, JSONObject().put("error", "Endpoint not found: $method $cleanPath"))
            }
        }
    }

    private fun getStatus(): ApiResponse {
        var snap: PlayerHolder.PlayerSnapshot? = null
        var repeatMode = Player.REPEAT_MODE_OFF
        var shuffleEnabled = false

        runOnMainSync {
            snap = PlayerHolder.snapshot()
            PlayerHolder.player?.let { p ->
                repeatMode = p.repeatMode
                shuffleEnabled = p.shuffleModeEnabled
            }
        }

        val s = snap
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volPct = if (maxVol > 0) (curVol * 100) / maxVol else 0

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = run {
            val bi = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val l = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val sc = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (l >= 0 && sc > 0) l * 100 / sc else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }
        val isCharging = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING

        val dacRate = getDacSampleRate()
        val fnStatus = Settings.Global.getInt(context.contentResolver, "fn_status", 0) == 1
        val buttonLock = Settings.Global.getInt(context.contentResolver, "button_lock", 0) == 1

        val root = JSONObject().apply {
            put("playback", JSONObject().apply {
                put("is_playing", s?.isPlaying ?: false)
                put("position_ms", s?.positionMs ?: 0L)
                put("duration_ms", s?.durationMs ?: 0L)
                put("progress_pct", if ((s?.durationMs ?: 0L) > 0) (s?.positionMs ?: 0L).toDouble() / (s?.durationMs ?: 1L) else 0.0)
                put("volume_pct", volPct)
                put("repeat_mode", when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> "all"
                    Player.REPEAT_MODE_ONE -> "one"
                    else -> "off"
                })
                put("shuffle_enabled", shuffleEnabled)
            })

            put("track", JSONObject().apply {
                put("id", s?.trackId ?: -1L)
                put("title", s?.title ?: "No Track")
                put("artist", s?.artist ?: "Unknown Artist")
                put("album", s?.album ?: "Unknown Album")
                put("sample_rate", dacRate)
                put("is_hi_res", dacRate >= 88200)
                put("is_dsd", dacRate > 768000)
                put("position_formatted", formatTime(s?.positionMs ?: 0L))
                put("duration_formatted", formatTime(s?.durationMs ?: 0L))
            })

            // Signed, expiring URLs for <img> and <audio>, which can't send the bearer token.
            // Each names one track id, so a URL can't be replayed for a different file.
            put("urls", JSONObject().apply {
                val id = s?.trackId ?: -1L
                if (id > 0) {
                    HioneApiSecurity.signUrl(context, "/api/v1/artwork/$id", 30 * 60_000L)?.let { put("artwork", it) }
                    val ttl = ((s?.durationMs ?: 0L).coerceAtLeast(0L) + 30 * 60_000L).coerceAtMost(12 * 3_600_000L)
                    HioneApiSecurity.signUrl(context, "/api/v1/audio/stream/$id", ttl)?.let { put("stream", it) }
                }
            })

            put("hardware", JSONObject().apply {
                put("dac_sample_rate_hz", dacRate)
                put("pocket_lock_active", fnStatus)
                put("button_lock_active", buttonLock)
                put("battery_pct", batteryLevel)
                put("is_charging", isCharging)
            })

            put("library", JSONObject().apply {
                val cached = FastLibraryStore.loadSync(context) ?: emptyList()
                put("track_count", cached.size)
                put("album_count", cached.map { it.album }.distinct().size)
                put("artist_count", cached.map { it.artist }.distinct().size)
            })
        }

        return ApiResponse.json(200, root)
    }

    private fun getQueue(): ApiResponse {
        val root = JSONObject()
        val queueArr = JSONArray()
        var curIdx = 0

        runOnMainSync {
            val p = PlayerHolder.player ?: return@runOnMainSync
            curIdx = p.currentMediaItemIndex
            val count = p.mediaItemCount
            for (i in 0 until count) {
                val item = p.getMediaItemAt(i)
                val meta = item.mediaMetadata
                queueArr.put(JSONObject().apply {
                    put("index", i)
                    put("id", item.mediaId)
                    put("title", meta.title?.toString() ?: "")
                    put("artist", meta.artist?.toString() ?: "")
                    put("album", meta.albumTitle?.toString() ?: "")
                    put("is_current", i == curIdx)
                })
            }
        }

        root.put("current_index", curIdx)
        root.put("queue_size", queueArr.length())
        root.put("items", queueArr)
        return ApiResponse.json(200, root)
    }

    private fun searchLibrary(query: String, limit: Int): ApiResponse {
        val q = query.lowercase().trim()
        val qf = com.hione.player.searchFold(query)
        val cached: List<Track> = FastLibraryStore.loadSync(context) ?: emptyList()
        val results = JSONArray()

        val matched = if (q.isEmpty()) {
            cached.take(limit)
        } else {
            cached.filter {
                it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q) ||
                // Same stylised-name folding the UI search uses, so the API does not quietly
                // disagree with the app about whether a library contains "$uicide Boy$".
                (qf.isNotEmpty() && (
                    com.hione.player.searchFold(it.title).contains(qf) ||
                    com.hione.player.searchFold(it.artist).contains(qf) ||
                    com.hione.player.searchFold(it.album).contains(qf)))
            }.take(limit)
        }

        matched.forEach { t ->
            results.put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("duration_ms", t.durationMs)
                put("path", t.path)
            })
        }

        val root = JSONObject().apply {
            put("query", query)
            put("count", results.length())
            put("results", results)
        }
        return ApiResponse.json(200, root)
    }

    private fun getCurrentTrackId(): Long {
        var id = -1L
        runOnMainSync {
            id = PlayerHolder.snapshot()?.trackId ?: -1L
        }
        if (id <= 0L) {
            id = PlayerPreferences.loadLastTrackId(context)
        }
        return id
    }

    private fun getArtworkResponse(path: String): ApiResponse {
        val trackId = if (path.endsWith("/current")) {
            getCurrentTrackId()
        } else {
            path.substringAfterLast("/").toLongOrNull() ?: getCurrentTrackId()
        }

        val cached = FastLibraryStore.loadSync(context) ?: emptyList()
        val track = if (trackId > 0) cached.find { it.id == trackId } else null
        val trackPath = track?.path

        val bytes = if (!trackPath.isNullOrEmpty()) {
            try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(trackPath)
                val pic = mmr.embeddedPicture
                mmr.release()
                // A cover shared across many albums is a mis-tag (see SharedCoverGuard): no art beats wrong art.
                com.hione.player.SharedCoverGuard.init(context)
                pic?.takeUnless { com.hione.player.SharedCoverGuard.isSuspectBytes(it) }
            } catch (_: Throwable) { null }
        } else null

        return if (bytes != null && bytes.isNotEmpty()) {
            ApiResponse.bytes(200, "image/jpeg", bytes)
        } else {
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="300" viewBox="0 0 300 300"><rect width="300" height="300" fill="#05141c"/><circle cx="150" cy="150" r="80" fill="#08202d" stroke="#FFC56B" stroke-width="4"/><text x="150" y="160" font-family="sans-serif" font-size="28" fill="#FFAB2E" text-anchor="middle">HIONE</text></svg>"""
            ApiResponse.bytes(200, "image/svg+xml", svg.toByteArray(Charsets.UTF_8))
        }
    }

    private fun getAudioStreamResponse(path: String): ApiResponse {
        val trackId = if (path.endsWith("/current") || path.endsWith("/stream")) {
            getCurrentTrackId()
        } else {
            path.substringAfterLast("/").toLongOrNull() ?: return ApiResponse.error(404, "Track audio file not found")
        }

        val cached = FastLibraryStore.loadSync(context) ?: emptyList()
        val track = if (trackId > 0) cached.find { it.id == trackId } else null
        val trackPath = track?.path

        if (trackPath != null) {
            val file = java.io.File(trackPath)
            if (file.isFile && file.canRead()) {
                val mimeType = when {
                    trackPath.endsWith(".flac", ignoreCase = true) -> "audio/flac"
                    trackPath.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                    trackPath.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                    trackPath.endsWith(".m4a", ignoreCase = true) || trackPath.endsWith(".aac", ignoreCase = true) -> "audio/mp4"
                    trackPath.endsWith(".dsf", ignoreCase = true) || trackPath.endsWith(".dff", ignoreCase = true) -> "audio/x-dsd"
                    else -> "application/octet-stream"
                }
                // Streamed from disk with Range support by the server, never read into memory.
                return ApiResponse.file(mimeType, file)
            }
        }
        return ApiResponse.error(404, "Track audio file not found")
    }

    private fun getDacSampleRate(): Int {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java, String::class.java)
            val v = m.invoke(null, "vendor.audio.hiby.hw.sample_rate", "0") as String
            v.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    private fun runOnMainSync(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(2000, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {}
    }
}

data class ApiResponse(
    val statusCode: Int,
    val contentType: String,
    val body: ByteArray,
    /** When set, the server streams this file (with Range support) instead of [body]. */
    val file: java.io.File? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        fun file(contentType: String, file: java.io.File): ApiResponse =
            ApiResponse(200, contentType, ByteArray(0), file, mapOf("Cache-Control" to "private, max-age=300"))

        fun json(code: Int, json: JSONObject): ApiResponse {
            val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
            return ApiResponse(code, "application/json; charset=utf-8", bytes)
        }

        fun html(code: Int, html: String): ApiResponse {
            val bytes = html.toByteArray(Charsets.UTF_8)
            return ApiResponse(code, "text/html; charset=utf-8", bytes)
        }

        fun bytes(code: Int, contentType: String, data: ByteArray): ApiResponse {
            return ApiResponse(code, contentType, data)
        }

        fun error(code: Int, msg: String): ApiResponse {
            val err = JSONObject().put("error", msg).put("status_code", code)
            return json(code, err)
        }
    }
}
