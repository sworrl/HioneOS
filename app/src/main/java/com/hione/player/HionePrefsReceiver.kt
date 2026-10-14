package com.hione.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Cross-app preference and playback control bridge:
 * Lets the HioneOS lockscreen, launcher, and quick-settings flip player behavior live
 * (pause-on-unplug, toggle/set track like) without binding the player process.
 */
open class HionePrefsReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SET_PAUSE_ON_UNPLUG = "com.hione.player.SET_PAUSE_ON_UNPLUG"
        const val ACTION_TOGGLE_LIKE = "com.hione.player.action.TOGGLE_LIKE"
        const val ACTION_SET_LIKE = "com.hione.player.action.SET_LIKE"
        /** Launcher "Force Scan" → LOCAL rescan of every storage volume (SD + internal): re-mirrors
         *  MediaStore into the FastLibraryStore index AND walks the volumes for files MediaStore
         *  hasn't indexed yet. Never touches the network; works with the ingress engine OFF. */
        const val ACTION_PROFILE = "com.hione.player.action.PROFILE"
        const val ACTION_PRESET_SELFTEST = "com.hione.player.action.PRESET_SELFTEST"
        const val ACTION_FORCE_LIBRARY_SCAN = "com.hione.player.action.FORCE_LIBRARY_SCAN"
        const val ACTION_RESCAN_LIBRARY = "com.hione.player.action.RESCAN_LIBRARY"
        /** Flip the ingress engine (Settings.Global hione_ingest_enabled) — extra "enabled". */
        const val ACTION_SET_INGEST_ENABLED = "com.hione.player.action.SET_INGEST_ENABLED"
        /** Instant random mode: start a random library track immediately (see InstantRandom). */
        const val ACTION_PLAY_RANDOM = "com.hione.player.action.PLAY_RANDOM"
        const val EXTRA_ENABLED = "enabled"

        /** The one local-rescan entry point everything funnels through (receiver, Force Scan
         *  button, storage modal). Foreground-service start is legal from a receiver on 14. */
        fun triggerLocalRescan(context: Context, reason: String) {
            android.util.Log.i("HionePrefsReceiver", "Local library rescan requested ($reason)")
            AlbumArtCache.clearMisses()
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, LibraryScanService::class.java)
                )
            }
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, LibraryDaemonService::class.java).apply { action = LibraryDaemonService.ACTION_FORCE_SYNC }
                )
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Sampling profiler from inside the process, because `am profile` writes nothing on
            // this device while the app is under load and nothing else works without root.
            //   adb shell am broadcast -a com.hione.player.action.PROFILE --ei seconds 8
            //   adb shell run-as com.hione.player cat files/profile.trace > profile.trace
            // Debug builds only. A profiler trigger in a release build is a foot-gun.
            // adb shell am broadcast -a com.hione.player.action.PRESET_SELFTEST -p com.hione.player
            // Needs the visualiser open (projectM lives on its GL thread). Logs "PRESET SELFTEST".
            ACTION_PRESET_SELFTEST -> Thread {
                ProjectMNative.selfTest(context.applicationContext)
            }.start()
            ACTION_PROFILE -> if (BuildConfig.DEBUG) {
                val secs = intent.getIntExtra("seconds", 8).coerceIn(1, 60)
                val f = java.io.File(context.filesDir, "profile.trace")
                runCatching {
                    android.os.Debug.startMethodTracingSampling(f.absolutePath, 64 * 1024 * 1024, 1000)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        runCatching { android.os.Debug.stopMethodTracing() }
                        android.util.Log.i("HioneProfile", "sampling trace written: ${f.absolutePath} (${f.length()} bytes)")
                    }, secs * 1000L)
                    android.util.Log.i("HioneProfile", "sampling for ${secs}s")
                }.onFailure { android.util.Log.w("HioneProfile", "start failed: $it") }
            }
            ACTION_FORCE_LIBRARY_SCAN, ACTION_RESCAN_LIBRARY -> triggerLocalRescan(context, intent.action ?: "broadcast")
            ACTION_SET_INGEST_ENABLED -> HioneIngestGate.setEnabled(context, intent.getBooleanExtra(EXTRA_ENABLED, false))
            ACTION_PLAY_RANDOM -> {
                val ok = InstantRandom.start(context)
                if (!ok) android.util.Log.w("HionePrefsReceiver", "PLAY_RANDOM: library index empty")
            }
            ACTION_SET_PAUSE_ON_UNPLUG -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                PlayerHolder.applyPauseOnUnplug(context, enabled)
            }
            ACTION_TOGGLE_LIKE, "com.hione.player.action.TOGGLE_LIKE" -> {
                LikeStore.init(context)
                val p = PlayerHolder.player
                val trackId = intent.getLongExtra("track_id", -1L).takeIf { it > 0 }
                    ?: p?.currentMediaItem?.mediaId?.toLongOrNull()
                    ?: PlayerPreferences.loadLastTrackId(context)
                if (trackId > 0) {
                    LikeStore.toggle(context, trackId)
                }
            }
            ACTION_SET_LIKE, "com.hione.player.action.SET_LIKE" -> {
                LikeStore.init(context)
                val p = PlayerHolder.player
                val trackId = intent.getLongExtra("track_id", -1L).takeIf { it > 0 }
                    ?: p?.currentMediaItem?.mediaId?.toLongOrNull()
                    ?: PlayerPreferences.loadLastTrackId(context)
                val targetLiked = intent.getBooleanExtra("is_liked", false)
                if (trackId > 0) {
                    val currentlyLiked = LikeStore.isLiked(trackId)
                    if (currentlyLiked != targetLiked) {
                        LikeStore.toggle(context, trackId)
                    }
                }
            }
        }
    }
}

/**
 * adb entry for the dev and test actions (manifest: android.permission.DUMP, which the shell
 * holds and other apps cannot get). [HionePrefsReceiver] itself needs the HioneOS signature
 * permission, so `adb shell am broadcast` no longer reaches it.
 */
class HionePrefsShellReceiver : HionePrefsReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PROFILE, ACTION_PRESET_SELFTEST, ACTION_FORCE_LIBRARY_SCAN, ACTION_RESCAN_LIBRARY ->
                super.onReceive(context, intent)
            MeasureShell.ACTION_PLAY_FILE, MeasureShell.ACTION_STOP, MeasureShell.ACTION_STATUS,
            MeasureShell.ACTION_DAC_SET -> {
                val (code, data) = runCatching { MeasureShell.handle(context.applicationContext, intent) }
                    .getOrElse { 3 to "error: ${it.message ?: it.javaClass.simpleName}" }
                android.util.Log.i(MeasureShell.TAG, "${intent.action} -> $code $data")
                // `am broadcast` prints these: "Broadcast completed: result=1, data=..."
                resultCode = code
                resultData = data
            }
        }
    }
}

/**
 * adb commands for hionemeasure (audio measurement from a PC), through [HionePrefsShellReceiver]
 * so only the shell (DUMP) can send them:
 *
 *   am broadcast -p com.hione.player -a com.hione.player.action.PLAY_FILE --es path /sdcard/Music/hionemeasure/x.wav [--el start_ms 0] [--ez eq_off true]
 *   am broadcast -p com.hione.player -a com.hione.player.action.MEASURE_STOP
 *   am broadcast -p com.hione.player -a com.hione.player.action.MEASURE_STATUS
 *   am broadcast -p com.hione.player -a com.hione.player.action.DAC_SET --es digital_filter nos --es gain high
 *
 * PLAY_FILE replaces the queue with the one file (repeat and shuffle off) and turns the live EQ
 * off unless eq_off is false. The user's queue, position and EQ state are kept and come back on
 * MEASURE_STOP. DAC_SET forwards the knobs to com.hione.sysbridge like HibyDacBridge does and
 * returns at once; the caller checks Settings.Global hione_dac_state for what was applied.
 * Result codes: 1 ok, 2 bad request, 3 failed.
 */
object MeasureShell {
    const val TAG = "HioneMeasure"
    const val ACTION_PLAY_FILE = "com.hione.player.action.PLAY_FILE"
    const val ACTION_STOP = "com.hione.player.action.MEASURE_STOP"
    const val ACTION_STATUS = "com.hione.player.action.MEASURE_STATUS"
    const val ACTION_DAC_SET = "com.hione.player.action.DAC_SET"

    private val KNOBS: Map<String, Set<String>> = mapOf(
        "high_power" to setOf("hpower_enable", "hpower_disable"),
        "dre_mode" to setOf("dremode_enable", "dremode_disable"),
        "digital_filter" to setOf(
            "fast_rolloff_low_latency", "fast_rolloff_phase_compensated",
            "slow_rolloff_low_latency", "slow_rolloff_phase_compensated", "nos",
        ),
        "gain" to setOf("low", "middle", "high"),
    )

    private class Saved(
        val items: List<androidx.media3.common.MediaItem>,
        val index: Int,
        val positionMs: Long,
        val repeat: Int,
        val shuffle: Boolean,
        val eqEnabled: Boolean,
    )

    @Volatile private var saved: Saved? = null

    /** Runs on the main thread (receiver callback), where the player lives. */
    fun handle(ctx: Context, intent: Intent): Pair<Int, String> = when (intent.action) {
        ACTION_PLAY_FILE -> play(ctx, intent)
        ACTION_STOP -> stop(ctx)
        ACTION_STATUS -> 1 to status(ctx)
        ACTION_DAC_SET -> dacSet(ctx, intent)
        else -> 2 to "unknown action"
    }

    private fun play(ctx: Context, intent: Intent): Pair<Int, String> {
        val path = intent.getStringExtra("path") ?: return 2 to "missing --es path"
        val f = java.io.File(path)
        if (!f.isFile || !f.canRead()) return 2 to "cannot read $path"
        val player = PlayerHolder.ensure(ctx)
        runCatching { PlayerHolder.ensureSession(ctx); PlayerHolder.ensureControllerConnected(ctx) }
        if (saved == null) {
            val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            saved = Saved(items, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0L),
                player.repeatMode, player.shuffleModeEnabled, com.hione.player.profiles.HioneEq.state.value.enabled)
        }
        if (intent.getBooleanExtra("eq_off", true)) com.hione.player.profiles.HioneEq.setEnabled(false)
        val item = androidx.media3.common.MediaItem.Builder()
            .setUri(android.net.Uri.fromFile(f))
            .setMediaId("hionemeasure:$path")
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                .setTitle(f.nameWithoutExtension).setArtist("hionemeasure").build())
            .build()
        player.shuffleModeEnabled = false
        player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
        player.setMediaItem(item, intent.getLongExtra("start_ms", 0L).coerceAtLeast(0L))
        player.prepare()
        player.play()
        return 1 to "playing $path"
    }

    private fun stop(ctx: Context): Pair<Int, String> {
        val player = PlayerHolder.player ?: return 1 to "stopped (no player)"
        player.pause()
        val s = saved ?: return 1 to "stopped"
        saved = null
        if (s.items.isNotEmpty()) {
            player.setMediaItems(s.items, s.index.coerceIn(0, s.items.size - 1), s.positionMs)
            player.prepare()
        } else {
            player.clearMediaItems()
        }
        player.repeatMode = s.repeat
        player.shuffleModeEnabled = s.shuffle
        com.hione.player.profiles.HioneEq.setEnabled(s.eqEnabled)
        return 1 to "stopped, queue restored (${s.items.size} items)"
    }

    private fun status(ctx: Context): String {
        // Creating the player here, before a run, means its start-up DAC policy has already been
        // applied by the time the caller sets the DAC.
        val p = PlayerHolder.ensure(ctx)
        val state = when (p.playbackState) {
            androidx.media3.common.Player.STATE_IDLE -> "idle"
            androidx.media3.common.Player.STATE_BUFFERING -> "buffering"
            androidx.media3.common.Player.STATE_READY -> "ready"
            else -> "ended"
        }
        return "state=$state playing=${p.isPlaying} position_ms=${p.currentPosition} " +
            "item=${p.currentMediaItem?.mediaId ?: ""} eq=${com.hione.player.profiles.HioneEq.state.value.enabled} " +
            "measuring=${saved != null}"
    }

    private fun dacSet(ctx: Context, intent: Intent): Pair<Int, String> {
        val out = Intent("com.hione.sysbridge.AUDIO").setPackage("com.hione.sysbridge")
        val sent = ArrayList<String>()
        for ((knob, allowed) in KNOBS) {
            val v = intent.getStringExtra(knob) ?: continue
            if (v !in allowed) return 2 to "refused $knob=$v (allowed: ${allowed.joinToString()})"
            out.putExtra(knob, v)
            sent.add("$knob=$v")
        }
        if (sent.isEmpty()) return 2 to "no knob given (${KNOBS.keys.joinToString()})"
        ctx.sendBroadcast(out)
        return 1 to "sent ${sent.joinToString(" ")}"
    }
}
