package com.hione.player.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hione.player.FastLibraryStore
import com.hione.player.PlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Joins the cast channel to the player: metadata out, transport commands in.
 *
 * Transmits real-time track metadata, duration, and position updates to the TV companion app.
 *
 * Kept apart from [HioneCastServer] on purpose. The server knows about sockets and framing and
 * nothing about playback; this knows about the player and nothing about the wire. That split is
 * what lets the transport be tested without a TV and the TV be tested without touching playback.
 *
 * THREADING. ExoPlayer is main-thread-only, and commands arrive on a socket thread, so every
 * control hop is posted to the main looper. Calling the player straight from the reader would
 * throw on the first button press from the remote.
 */
object HioneCastBridge {

    private const val TAG = "HioneCastBridge"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var attached = false
    @Volatile private var appContext: Context? = null
    @Volatile private var lastMetaKey = ""
    @Volatile private var lastSentPosMs = -1L
    @Volatile private var lastSentTimeMs = 0L

    // One-shot latches so a repeating cause logs once instead of once a second.
    @Volatile private var warnedNoClient = 0
    @Volatile private var warnedNoPlayer = 0

    fun attach(ctx: Context) {
        if (attached) return
        attached = true
        val app = ctx.applicationContext
        appContext = app

        // Only start casting server if explicitly enabled by the user in Settings
        val castPrefs = app.getSharedPreferences("hione_cast_prefs", Context.MODE_PRIVATE)
        if (castPrefs.getBoolean("key_cast_enabled", false)) {
            runCatching {
                if (!HioneCastServer.state.value.active) {
                    HioneCastServer.start(app)
                }
            }
        }

        // Commands from the TV remote.
        scope.launch {
            HioneCastServer.commands.filterNotNull().collect { cmd ->
                runCatching { handle(cmd) }
                    .onFailure { Log.w(TAG, "command failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }

        // When TV connects, force an immediate metadata push
        scope.launch {
            HioneCastServer.state.collect { st ->
                if (st.connected) {
                    lastMetaKey = ""
                    pushMeta(force = true)
                }
            }
        }

        // Real-time metadata and progress feed. Polled every 500ms so the TV receives
        // 1-second cadence position ticks and immediate duration/state resolution.
        scope.launch {
            while (true) {
                runCatching { pushMeta(force = false) }
                    .onFailure { Log.w(TAG, "metadata poll failed: ${it.javaClass.simpleName}: ${it.message}") }
                delay(500)
            }
        }
        Log.i(TAG, "bridge attached")
    }

    private fun handle(cmd: JSONObject) {
        val what = cmd.optString("cmd")
        Log.i(TAG, "TV sent '$what'")
        main.post {
            val p = PlayerHolder.player ?: return@post
            runCatching {
                when (what) {
                    "playpause" -> if (p.isPlaying) p.pause() else p.play()
                    "play" -> p.play()
                    "pause" -> p.pause()
                    "next" -> p.seekToNextMediaItem()
                    "prev" -> p.seekToPreviousMediaItem()
                    "seek" -> p.seekTo(cmd.optLong("positionMs", 0L))
                    "forward" -> {
                        val delta = cmd.optLong("deltaMs", 15_000L)
                        val target = (p.currentPosition + delta).coerceAtMost(if (p.duration > 0) p.duration else Long.MAX_VALUE)
                        p.seekTo(target)
                    }
                    "rewind" -> {
                        val delta = cmd.optLong("deltaMs", 15_000L)
                        val target = (p.currentPosition - delta).coerceAtLeast(0L)
                        p.seekTo(target)
                    }
                    else -> Log.d(TAG, "ignoring unknown command '$what'")
                }
            }.onFailure { Log.w(TAG, "player rejected '$what': ${it.message}") }
            // Force immediate push of updated state and position
            pushMeta(force = true)
        }
    }

    fun pushMeta(force: Boolean = false) {
        if (!HioneCastServer.state.value.connected) {
            if (warnedNoClient != 1) { warnedNoClient = 1; Log.d(TAG, "no TV attached; not sending metadata") }
            return
        }
        main.post {
            val p = PlayerHolder.player
            if (p == null) {
                if (warnedNoPlayer != 1) { warnedNoPlayer = 1; Log.w(TAG, "PlayerHolder.player is null; TV will show no metadata") }
                return@post
            }
            warnedNoPlayer = 0
            val md = runCatching { p.mediaMetadata }
                .onFailure { Log.w(TAG, "mediaMetadata threw: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull() ?: return@post

            val title = md.title?.toString().orEmpty()
            val artist = md.artist?.toString().orEmpty()
            val album = md.albumTitle?.toString().orEmpty()

            var dur = runCatching { p.duration }.getOrDefault(0L).coerceAtLeast(0L)
            val trackId = runCatching { p.currentMediaItem?.mediaId?.toLongOrNull() }.getOrNull()
            val app = appContext
            if (dur <= 0L && trackId != null) {
                val libTrack = FastLibraryStore.peek()?.firstOrNull { it.id == trackId }
                    ?: (if (app != null) runCatching { FastLibraryStore.loadSync(app)?.firstOrNull { it.id == trackId } }.getOrNull() else null)
                if (libTrack != null && libTrack.durationMs > 0L) {
                    dur = libTrack.durationMs
                }
            }

            val pos = runCatching { p.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
            val playing = runCatching { p.isPlaying }.getOrDefault(false)

            val now = System.currentTimeMillis()
            val metaKey = "$title|$artist|$album|$playing|$dur"
            val metaChanged = (metaKey != lastMetaKey)
            val seekDetected = (lastSentPosMs >= 0L && kotlin.math.abs(pos - (lastSentPosMs + (now - lastSentTimeMs))) > 2000L)
            val periodicTick = (playing && (now - lastSentTimeMs >= 1000L))

            if (!force && !metaChanged && !seekDetected && !periodicTick) {
                return@post
            }

            if (metaChanged) {
                Log.i(TAG, "pushing metadata to TV: '$title' / '$artist' (dur=$dur, playing=$playing)")
            }

            lastMetaKey = metaKey
            lastSentPosMs = pos
            lastSentTimeMs = now

            val artFile: java.io.File? = if (metaChanged && trackId != null && app != null) {
                runCatching {
                    com.hione.player.thumbFile(app, trackId).takeIf { it.exists() && it.length() in 100..90_000 }
                }.getOrNull()
            } else null

            val frame = JSONObject()
                .put("title", title)
                .put("artist", artist)
                .put("album", album)
                .put("durationMs", dur)
                .put("positionMs", pos)
                .put("playing", playing)

            if (artFile != null) {
                runCatching {
                    val bytes = artFile.readBytes()
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    frame.put("artB64", b64)
                }
            }

            HioneCastServer.sendMeta(frame)
        }
    }
}

