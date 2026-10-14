package com.hione.launcher.ingest

import android.content.Context
import android.util.Log
import com.hione.launcher.HioneIngestConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the host relay is doing right now, read from m500d.
 *
 * WHY. The ingest tile used to fire `POST /api/sync` and then show one static line, usually
 * "Sync triggered on Host Daemon. It will push via ADB." That is the last thing it ever said. The
 * relay meanwhile publishes a detailed picture every second: which album each worker is on, files
 * and bytes done against totals, how much is cached, what failed. None of it reached the device,
 * so from the DAP a sync was indistinguishable from a no-op until tracks eventually appeared.
 *
 * POLLING, NOT THE STREAM. m500d also serves `/api/stream`, which is server-sent events pushing
 * the whole status once a second. That is the better fit for a desktop WebUI and the wrong fit
 * here: it holds a connection open for as long as it is subscribed, on a battery-powered player
 * whose radio we work hard to let sleep. This polls instead, only while the observatory is on
 * screen, and stops the moment it closes.
 */
data class HioneRelayStatus(
    val reachable: Boolean = false,
    /** Why we have nothing, when [reachable] is false. Shown verbatim rather than hidden. */
    val error: String = "",
    val deviceOnline: Boolean = false,
    val deviceVia: String = "",

    val running: Boolean = false,
    /** m500d's own word for the phase: "fetching", "pushing", and so on. */
    val stage: String = "",
    val lastEvent: String = "",

    val currentArtist: String = "",
    val currentAlbum: String = "",
    val currentFile: String = "",
    val albumIndex: Int = 0,
    val albumTotal: Int = 0,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesSent: Long = 0L,
    val bytesTotal: Long = 0L,
    val albumsDone: Int = 0,
    val albumsFailed: Int = 0,
    val albumsCached: Int = 0,

    val cacheBytes: Long = 0L,
    val fetchActive: Int = 0,
    val pushActive: Int = 0,
    /** Workers as "fetching Led Zeppelin / Celebration Day", in the relay's own order. */
    val workers: List<String> = emptyList(),
    val failures: List<String> = emptyList(),

    /**
     * Bytes per second, measured HERE from successive byte counts rather than taken from the
     * relay. m500d reports `fetch_rate_bps` and `push_rate_bps` as 0 even while it is demonstrably
     * moving data (confirmed 2026-10-08 against cache growth of ~2.9 MB/s), so trusting its number
     * would mean showing a confident zero during a working transfer. Differencing what it does
     * report honestly is better than echoing a field that is wrong.
     *
     * Null until two samples exist. A dash on screen beats a fabricated 0.
     */
    val measuredRateBps: Long? = null,
) {
    val progress: Float
        get() = if (bytesTotal > 0L) (bytesSent.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

    /** Seconds remaining at the measured rate, or null when we cannot honestly say. */
    val etaSeconds: Long?
        get() {
            val r = measuredRateBps ?: return null
            if (r <= 0L || bytesTotal <= bytesSent) return null
            return (bytesTotal - bytesSent) / r
        }
}

object HioneRelayPoller {

    private const val TAG = "HioneRelayPoller"
    private const val POLL_MS = 2000L
    /** Short: this is a LAN service and a slow answer is worse than no answer on a 2s cadence. */
    private const val TIMEOUT_MS = 2500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Previous sample, for the rate we measure ourselves. */
    private var lastBytes = -1L
    private var lastAtMs = 0L

    /** Begin polling. Safe to call repeatedly; only one loop ever runs. */
    @Synchronized
    fun start(ctx: Context, onUpdate: (HioneRelayStatus) -> Unit) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        lastBytes = -1L
        lastAtMs = 0L
        job = scope.launch {
            while (isActive) {
                val s = fetch(app)
                onUpdate(s)
                delay(POLL_MS)
            }
        }
        Log.i(TAG, "relay status polling started")
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        Log.i(TAG, "relay status polling stopped")
    }

    /** One read of the relay's status. Never throws; failure comes back as `reachable = false`. */
    fun fetch(ctx: Context): HioneRelayStatus {
        val host = HioneIngestConfig.syncHost(ctx).takeIf { it.isNotBlank() } ?: "192.168.13.202"
        val body = try {
            val conn = (URL("http://$host:8787/api/status").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) {
                return HioneRelayStatus(error = "Relay answered HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            // The message matters: "Cleartext HTTP traffic not permitted" and "Connection refused"
            // send you to completely different places, and the tile used to show neither.
            return HioneRelayStatus(error = t.localizedMessage ?: t.javaClass.simpleName)
        }

        return try { parse(body) } catch (t: Throwable) {
            HioneRelayStatus(error = "Could not read the relay's reply: ${t.javaClass.simpleName}")
        }
    }

    private fun parse(body: String): HioneRelayStatus {
        val root = JSONObject(body)
        val dev = root.optJSONObject("device")
        val tr = root.optJSONObject("transfer")
        val live = root.optJSONObject("live")

        val sent = tr?.optLong("bytes_sent", 0L) ?: 0L
        val now = System.currentTimeMillis()

        // Rate from our own two samples. Only when bytes went UP: a restarted transfer resets the
        // counter, and a negative delta would otherwise print as a huge bogus number.
        var rate: Long? = null
        if (lastBytes >= 0L && now > lastAtMs && sent >= lastBytes) {
            val dt = (now - lastAtMs).coerceAtLeast(1L)
            rate = ((sent - lastBytes) * 1000L) / dt
        }
        lastBytes = sent
        lastAtMs = now

        val workers = buildList {
            tr?.optJSONArray("workers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONObject(i) ?: continue
                    val album = w.optString("album", "").trim()
                    val status = w.optString("status", "").trim()
                    if (album.isNotEmpty()) add(if (status.isEmpty()) album else "$status  $album")
                }
            }
        }
        val failures = buildList {
            tr?.optJSONArray("failures")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
        }

        return HioneRelayStatus(
            reachable = true,
            deviceOnline = dev?.optBoolean("online", false) ?: false,
            deviceVia = dev?.optString("via", "") ?: "",
            running = tr?.optBoolean("running", false) ?: false,
            stage = tr?.optString("stage", "") ?: "",
            lastEvent = tr?.optString("last_event", "") ?: "",
            currentArtist = tr?.optString("current_artist", "") ?: "",
            currentAlbum = tr?.optString("current_album", "") ?: "",
            currentFile = tr?.optString("current_file", "") ?: "",
            albumIndex = tr?.optInt("album_index", 0) ?: 0,
            albumTotal = tr?.optInt("album_total", 0) ?: 0,
            filesDone = tr?.optInt("files_done", 0) ?: 0,
            filesTotal = tr?.optInt("files_total", 0) ?: 0,
            bytesSent = sent,
            // bytes_total is 0 before the plan is costed; bytes_total_plan carries the real figure.
            bytesTotal = (tr?.optLong("bytes_total", 0L) ?: 0L)
                .takeIf { it > 0L } ?: (tr?.optLong("bytes_total_plan", 0L) ?: 0L),
            albumsDone = tr?.optInt("albums_done", 0) ?: 0,
            albumsFailed = tr?.optInt("albums_failed", 0) ?: 0,
            albumsCached = tr?.optInt("albums_cached", 0) ?: 0,
            cacheBytes = live?.optLong("cache_bytes", 0L) ?: 0L,
            fetchActive = live?.optInt("fetch_active", 0) ?: 0,
            pushActive = live?.optInt("push_active", 0) ?: 0,
            workers = workers,
            failures = failures,
            measuredRateBps = rate,
        )
    }
}
