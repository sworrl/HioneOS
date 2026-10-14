package com.hione.player.scrobble

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.hione.player.LastFmPreferences
import com.hione.player.stats.ListenStatsDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Queue-backed Last.fm scrobbler.
 *
 *  - [nowPlaying] fires track.updateNowPlaying immediately (best effort; never queued — a
 *    "now playing" that arrives later is meaningless).
 *  - [enqueue] persists a scrobble in the stats DB's scrobble_queue and triggers a [flush].
 *    Because it's on disk, an offline listen survives reboots and gets submitted when the M500
 *    next sees a network (ConnectivityManager default-network callback) or on the next tick.
 *  - [flush] submits due items oldest-first in batches of up to 50 (Last.fm's per-call cap).
 *    Accepted AND ignored items leave the queue (an ignored scrobble is Last.fm's final verdict —
 *    e.g. timestamp too old / artist blacklisted — retrying it can't change that). A temporary
 *    error defers the batch with exponential backoff (30 s → 1 h); a hard error (bad key, expired
 *    session) parks the queue until the user changes credentials, so a broken key never spins
 *    the radio every 30 s.
 *
 * Every field the settings card shows comes from a real API response or a real queue count —
 * see [ScrobblePreferences.Status]. Nothing is estimated.
 *
 * All work runs on one daemon thread so DB writes and HTTP calls are serialized; [state] is a
 * StateFlow the Compose card collects.
 */
object ScrobbleManager {
    private const val TAG = "ScrobbleManager"
    private const val MIN_FLUSH_INTERVAL_MS = 15_000L

    data class State(
        val enabled: Boolean = true,
        val connected: Boolean = false,
        val username: String? = null,
        val configured: Boolean = false,
        val credentialSource: String = "none",
        val queueSize: Int = 0,
        val flushing: Boolean = false,
        val lastScrobbleAt: Long = 0L,
        val lastNowPlayingAt: Long = 0L,
        val lastError: String? = null,
        val lastErrorAt: Long = 0L,
        val hardError: String? = null,
        val acceptedTotal: Int = 0,
        val ignoredTotal: Int = 0,
        val lastFlushAt: Long = 0L
    ) {
        val active: Boolean get() = enabled && connected && configured && hardError == null
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "hione-scrobble").apply { isDaemon = true } }
    @Volatile private var appCtx: Context? = null
    @Volatile private var lastFlushAttemptAt = 0L
    @Volatile private var networkCallbackRegistered = false

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        if (appCtx == null) appCtx = app
        ScrobblePreferences.init(app)
        registerNetworkCallback(app)
        executor.execute {
            runCatching { ListenStatsDb.get(app).purgeStaleScrobbles(System.currentTimeMillis() / 1000) }
            refreshState()
            flushInternal(force = false)
        }
    }

    /** Recompute the published state from prefs + DB. Cheap; safe from any thread. */
    fun refreshState() {
        val app = appCtx ?: return
        val st = ScrobblePreferences.status(app)
        val q = runCatching { ListenStatsDb.get(app).queueSize() }.getOrDefault(_state.value.queueSize)
        _state.value = _state.value.copy(
            enabled = ScrobblePreferences.isEnabled(app),
            connected = LastFmPreferences.isConnected(app),
            username = LastFmPreferences.loadUsername(app),
            configured = LastFmCredentials.isConfigured,
            credentialSource = LastFmCredentials.source(),
            queueSize = q,
            lastScrobbleAt = st.lastScrobbleAt,
            lastNowPlayingAt = st.lastNowPlayingAt,
            lastError = st.lastError,
            lastErrorAt = st.lastErrorAt,
            hardError = st.hardError,
            acceptedTotal = st.acceptedTotal,
            ignoredTotal = st.ignoredTotal
        )
    }

    private fun active(app: Context): Boolean =
        ScrobblePreferences.isEnabled(app) && LastFmPreferences.isConnected(app) &&
            LastFmCredentials.isConfigured && ScrobblePreferences.status(app).hardError == null

    // ------------------------------------------------------------------ public entry points

    fun nowPlaying(artist: String, track: String, album: String?, durationSec: Int?) {
        val app = appCtx ?: return
        if (artist.isBlank() || track.isBlank()) return
        executor.execute {
            if (!active(app)) return@execute
            val sk = LastFmPreferences.loadSessionKey(app) ?: return@execute
            try {
                LastFmApi.updateNowPlaying(sk, artist, track, album, durationSec)
                ScrobblePreferences.recordNowPlaying(app, System.currentTimeMillis())
            } catch (e: LastFmApi.ApiException) {
                handleError(app, e.error, "now playing")
            } catch (t: Throwable) {
                ScrobblePreferences.recordError(app, "now playing: ${t.message ?: t.javaClass.simpleName}")
            }
            refreshState()
        }
    }

    /** Persist a scrobble (standard rule already satisfied by the caller) and try to send it. */
    fun enqueue(listenId: Long?, artist: String, track: String, album: String?, durationSec: Int?, timestampSec: Long) {
        val app = appCtx ?: return
        if (artist.isBlank() || track.isBlank()) return
        // Only queue when scrobbling is on + signed in: an unsigned-in user turning it on later
        // shouldn't get a month of backdated scrobbles they never asked for.
        if (!ScrobblePreferences.isEnabled(app) || !LastFmPreferences.isConnected(app)) return
        executor.execute {
            runCatching {
                ListenStatsDb.get(app).enqueueScrobble(
                    ListenStatsDb.QueuedScrobble(
                        listenId = listenId, artist = artist, track = track, album = album, durationSec = durationSec,
                        timestampSec = timestampSec, attempts = 0, lastError = null, nextAttemptAt = 0L,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }.onFailure { Log.w(TAG, "enqueue failed", it) }
            refreshState()
            flushInternal(force = false)
        }
    }

    /** Cheap periodic nudge from the listen tracker (~every 30 s while playing). */
    fun onTick() {
        if (_state.value.queueSize == 0) return
        executor.execute { flushInternal(force = false) }
    }

    /** Settings "Retry now": clears backoff and a hard error, then flushes. */
    fun retryNow() {
        val app = appCtx ?: return
        executor.execute {
            ScrobblePreferences.clearHardError(app)
            runCatching { ListenStatsDb.get(app).writableDatabase.execSQL("UPDATE ${ListenStatsDb.TABLE_SCROBBLE_QUEUE} SET next_attempt_at = 0") }
            lastFlushAttemptAt = 0L
            refreshState()
            flushInternal(force = true)
        }
    }

    fun clearQueue() {
        val app = appCtx ?: return
        executor.execute {
            runCatching { ListenStatsDb.get(app).clearScrobbleQueue() }
            refreshState()
        }
    }

    /** Called by the settings card after any credential / toggle / login change. */
    fun onSettingsChanged() {
        val app = appCtx ?: return
        executor.execute {
            ScrobblePreferences.clearHardError(app)
            lastFlushAttemptAt = 0L
            refreshState()
            flushInternal(force = true)
        }
    }

    // ------------------------------------------------------------------ flush

    private fun flushInternal(force: Boolean) {
        val app = appCtx ?: return
        if (!active(app)) { refreshState(); return }
        val now = System.currentTimeMillis()
        if (!force && now - lastFlushAttemptAt < MIN_FLUSH_INTERVAL_MS) return
        lastFlushAttemptAt = now
        val db = ListenStatsDb.get(app)
        val sk = LastFmPreferences.loadSessionKey(app) ?: return
        _state.value = _state.value.copy(flushing = true)
        try {
            var rounds = 0
            while (rounds++ < 20) {   // at most 1000 scrobbles per flush; the rest next time
                val batch = runCatching { db.dueScrobbles(System.currentTimeMillis(), 50) }.getOrDefault(emptyList())
                if (batch.isEmpty()) break
                val items = batch.map { LastFmApi.ScrobbleItem(it.artist, it.track, it.album, it.timestampSec, it.durationSec) }
                try {
                    val r = LastFmApi.scrobble(sk, items)
                    db.removeScrobbles(batch.map { it.id })
                    ScrobblePreferences.recordScrobbled(app, System.currentTimeMillis(), r.accepted, r.ignored)
                    if (r.ignoredMessages.isNotEmpty()) ScrobblePreferences.recordError(app, "ignored: " + r.ignoredMessages.first())
                    Log.i(TAG, "scrobbled ${r.accepted} accepted / ${r.ignored} ignored")
                } catch (e: LastFmApi.ApiException) {
                    handleError(app, e.error, "scrobble")
                    if (e.error.retryable) {
                        val attempts = batch.maxOf { it.attempts } + 1
                        val backoff = min(30_000L * (1L shl (attempts - 1).coerceIn(0, 7)), 3_600_000L)
                        db.deferScrobbles(batch.map { it.id }, e.error.message, System.currentTimeMillis() + backoff)
                    }
                    break   // one failure ends this flush; the next trigger retries
                } catch (t: Throwable) {
                    ScrobblePreferences.recordError(app, "scrobble: ${t.message ?: t.javaClass.simpleName}")
                    db.deferScrobbles(batch.map { it.id }, t.message ?: "error", System.currentTimeMillis() + 60_000L)
                    break
                }
            }
        } finally {
            _state.value = _state.value.copy(flushing = false, lastFlushAt = System.currentTimeMillis())
            refreshState()
        }
    }

    private fun handleError(app: Context, err: LastFmApi.ApiError, what: String) {
        Log.w(TAG, "$what failed: ${err.message} (retryable=${err.retryable})")
        if (err.retryable) {
            ScrobblePreferences.recordError(app, "$what: ${err.message}")
        } else {
            ScrobblePreferences.setHardError(app, "$what: ${err.message}")
            if (err.invalidatesSession) runCatching { LastFmPreferences.disconnect(app) }
        }
    }

    // ------------------------------------------------------------------ network trigger

    private fun registerNetworkCallback(app: Context) {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    executor.execute {
                        lastFlushAttemptAt = 0L
                        flushInternal(force = false)
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "network callback unavailable", t)
            networkCallbackRegistered = false
        }
    }
}
