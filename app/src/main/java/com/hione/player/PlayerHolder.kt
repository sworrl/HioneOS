package com.hione.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Process-wide owner of the ExoPlayer + its Media3 MediaSession. The Activity drives the
 * player directly (same process → audioSessionId stays reachable for the visualizer), while
 * PlaybackService exposes the session so the system draws our branded lockscreen/notification
 * media control instead of leaving the stage to Spotify.
 */
object PlayerHolder {
    @Volatile var player: ExoPlayer? = null
        private set
    @Volatile var session: MediaLibrarySession? = null
        private set

    // A process-wide MediaController bound to PlaybackService. We drive the ExoPlayer directly for
    // the in-app UI, but WITHOUT a connected controller Media3 never reliably promotes the service
    // to foreground / posts its MediaStyle notification, so the session (and therefore hardware &
    // Bluetooth media-button routing) died the moment the app was backgrounded or the screen went
    // off — the reported "media keys only work inside the app with the screen on" bug. Holding this
    // controller connection keeps the MediaLibraryService alive and foreground while playing, which
    // is what lets the OS route media buttons to HioneLibraryCallback.onMediaButtonEvent everywhere.
    @Volatile private var controllerFuture: ListenableFuture<MediaController>? = null
    @Volatile var controller: MediaController? = null
        private set

    /** Immutable snapshot of the fields the widgets need — exists so widget rendering (which does
     *  slow work: ContentResolver I/O + bitmap drawing, dispatched to a background thread via
     *  WidgetUpdateExecutor for exactly that reason) never touches the live ExoPlayer itself off
     *  its own application thread. Media3's Player contract requires every call to originate on
     *  the thread the player was built on (main, here) — reading player.isPlaying/.duration/etc.
     *  from a background thread is undefined behavior per that contract, confirmed via code audit
     *  as a real bug once widget rendering moved off-thread. Call snapshot() ONLY from the main
     *  thread (every current call site already is: Player.Listener callbacks, BroadcastReceiver.
     *  onReceive, AppWidgetProvider.onUpdate all run there) — it's the safe, cheap field-read part;
     *  everything slow happens afterward against this plain data snapshot instead. */
    data class PlayerSnapshot(
        val title: String?,
        val artist: String?,
        val album: String?,
        val isPlaying: Boolean,
        val durationMs: Long,
        val positionMs: Long,
        val trackId: Long?
    )

    fun snapshot(): PlayerSnapshot? {
        val p = player ?: return null
        val meta = p.mediaMetadata
        return PlayerSnapshot(
            title = meta.title?.toString(),
            artist = meta.artist?.toString(),
            album = meta.albumTitle?.toString(),
            isPlaying = p.isPlaying,
            durationMs = p.duration,
            positionMs = p.currentPosition,
            trackId = p.currentMediaItem?.mediaId?.toLongOrNull()
        )
    }

    // "Restore last session's track/position on launch" must run exactly ONCE per process, ever
    // — not once per Activity recomposition. It used to live as a plain composable var keyed on
    // the track list, so a library rescan refreshing that list (even finding nothing new) could
    // re-trigger the whole restore path and yank live playback back to a stale saved track/
    // position — verified as the actual cause of "scan finished, it just started playing
    // something random" mid-listen. Owning the flag here, on the process-wide player singleton
    // rather than composable state, makes it immune to that regardless of what causes the
    // Compose side to recompose or re-key.
    @Volatile var sessionRestored: Boolean = false
        private set
    fun markSessionRestored() { sessionRestored = true }

    @Synchronized
    fun ensure(context: Context): ExoPlayer {
        val existing = player
        if (existing != null) return existing
        val app = context.applicationContext
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 45_000,
                /* bufferForPlaybackMs = */ 200,
                /* bufferForPlaybackAfterRebufferMs = */ 400
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // DTA: make sure we're in the platform's direct-output allow-list BEFORE the first
        // AudioTrack is created — the framework checks it per-track at construction. This is what
        // routes playback bit-perfect (native rate, no mixer/SRC) to the dual CS43198 DACs.
        // A fresh player means nothing has produced an output format yet, and the key we publish
        // it to is a Settings.Global row that OUTLIVES this process. Left alone, the format of
        // whatever played before the app was last killed would sit there being read as live — the
        // anatomical observatory shows it unconditionally. Clear it here so the surfaces show
        // "not published" until an AudioTrack actually exists again.
        runCatching { HioneNowPlayingFormat.clear(app) }
        runCatching { HionePulse.attach(app) }
        runCatching { HioneDirectAudio.ensureAllowListed(app) }
        // Full-range volume: kill HiBy's "volume lock" (Settings.Global vendor.audio.hw.volume_lock)
        // which caps STREAM_MUSIC at index 35/40 on the phone-out jacks — see HioneDirectAudio.
        runCatching { HioneDirectAudio.ensureFullVolumeRange(app) }
        // Best-audio mode: max gain / DRE / high-power unless the user lowered one in settings
        // (see HioneDirectAudio.ensureBestAudio).
        runCatching { HioneDirectAudio.ensureBestAudio(app) }
        // BT: bind the A2DP proxy so the codec policy (LDAC 990 + best codec, unless lowered) is
        // re-asserted on every connect while the player is alive.
        runCatching { com.hione.player.bluetooth.HioneBluetoothController.init(app) }
        // Hi-fi pipeline is the ONLY pipeline: the bit-perfect DirectPCM sink (24/32-bit int
        // passthrough, float→24-bit, DTA DIRECT when allow-listed). The old "LibVLC / OpenSL ES"
        // engine preference was a 16-bit resampled path — it is no longer selectable, and any
        // stale stored value is overwritten here so a prior tap can never silently degrade audio.
        runCatching { if (PlayerPreferences.loadAudioEngine(app) != "exoplayer") PlayerPreferences.saveAudioEngine(app, "exoplayer") }
        runCatching { HionePowerGovernor.init(app) }
        // External USB DAC output: route + bit-perfect mixer when a USB sink enumerates (HioneUsbDacOutput).
        runCatching { HioneUsbDacOutput.init(app) }
        runCatching { HioneMirrorOutput.init(app) }
        runCatching { com.hione.player.volume.HioneOutputVolumes.init(app) }
        // Listening profiles: restores the current profile's EQ and follows BT/USB/wired outputs.
        runCatching { com.hione.player.profiles.ListeningProfileManager.init(app) }

        // Integer PCM output with bit-perfect DIRECT support for dual CS43198 DACs:
        // Media3's stock DefaultAudioSink either downsamples 24/32-bit to 16-bit (float=false)
        // or produces float AudioTracks (float=true) which the HiBy direct_pcm HAL rejects.
        // HioneDirectAudioSink passes 16-bit, 24-bit packed, and 32-bit integer PCM directly to AudioTrack
        // at native sample rate, matching the vendor direct_pcm profiles for true bit-perfect hardware output.
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.HioneDirectAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    // Listening-profile EQ. Inactive (out of the pipeline, bit-perfect) whenever
                    // the curve is off or flat. The sink runs this chain for 16-bit input only;
                    // hi-res needs the processor added in the sink's own hi-res branch (HioneEq).
                    .setAudioProcessors(arrayOf<androidx.media3.common.audio.AudioProcessor>(com.hione.player.profiles.HioneEq.processor))
                    .build()
            }

            // Hi-res decode path: the platform MediaCodec decoders cannot emit 24-bit integer
            // PCM (KEY_PCM_ENCODING only accepts 8/16-bit and float), so with float output off a
            // 24-bit FLAC would be TRUNCATED to 16-bit inside the codec before the sink ever saw
            // it. For sources whose container declares a >16-bit depth (FlacExtractor etc. set
            // Format.pcmEncoding from the stream header), ask the codec for float output — a
            // lossless carrier for <=24-bit samples — and HioneDirectAudioSink converts it back to
            // 24-bit packed integer PCM for the DACs' direct_pcm profile. 16-bit sources are
            // untouched (stay 16-bit end to end); a codec that ignores the key just keeps
            // emitting 16-bit, which the sink also handles.
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: androidx.media3.exoplayer.audio.AudioSink,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                out.add(object : androidx.media3.exoplayer.audio.MediaCodecAudioRenderer(
                    context,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    audioSink
                ) {
                    override fun getMediaFormat(
                        format: androidx.media3.common.Format,
                        codecMimeType: String,
                        codecMaxInputSize: Int,
                        codecOperatingRate: Float
                    ): android.media.MediaFormat {
                        val mediaFormat = super.getMediaFormat(
                            format, codecMimeType, codecMaxInputSize, codecOperatingRate
                        )
                        if (androidx.media3.common.util.Util.SDK_INT >= 24 &&
                            format.pcmEncoding != androidx.media3.common.Format.NO_VALUE &&
                            androidx.media3.common.util.Util.isEncodingHighResolutionPcm(format.pcmEncoding)
                        ) {
                            mediaFormat.setInteger(
                                android.media.MediaFormat.KEY_PCM_ENCODING,
                                android.media.AudioFormat.ENCODING_PCM_FLOAT
                            )
                        }
                        return mediaFormat
                    }
                })
            }
        }

        val p = ExoPlayer.Builder(app, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
            .build()
        // Pause-on-unplug ("audio becoming noisy"): stock HiBy OS pauses when the headphone jack
        // is pulled; we'd hard-disabled it over false-pause worries. Now a user preference
        // (default ON to match stock) surfaced in the Sound Settings page and the quick-settings
        // shade — deliberately NOT in the volume modal. Applied live via applyPauseOnUnplug().
        p.setHandleAudioBecomingNoisy(PlayerPreferences.loadPauseOnUnplug(app))
        // Keep home-screen widgets in lock-step with playback (track + play/pause state).
        var lastErrorAtMs = 0L
        var errorRetryStreak = 0
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPositionDiscontinuity(
                oldPos: androidx.media3.common.Player.PositionInfo,
                newPos: androidx.media3.common.Player.PositionInfo,
                reason: Int
            ) {
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) {
                    newPos.mediaItem?.mediaId?.toLongOrNull()?.let { HionePlayQualifier.onSeek(it, newPos.positionMs) }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                WidgetUpdateExecutor.push(app)
                HioneTrackHud.publish(app, p, if (isPlaying) "play" else "pause")
                HioneAccentPublisher.onPlaybackState(app, isPlaying)
                HionePowerGovernor.onPlaybackState(app, isPlaying)
                if (isPlaying) p.currentMediaItem?.mediaId?.toLongOrNull()?.let { HioneArtTheme.updateForTrackId(app, it) }
                PlayerPreferences.saveWasPlaying(app, isPlaying)
                com.hione.player.bpm.HioneBpmEngine.onPlaybackChanged(app, p.currentMediaItem, isPlaying)
                HionePulse.onPlayback(app, p.currentMediaItem, isPlaying)
                val trackId = p.currentMediaItem?.mediaId?.toLongOrNull()
                if (trackId != null) {
                    PlayerPreferences.saveLastPlayback(app, trackId, p.currentPosition)
                    PlayerPreferences.saveQueueIndex(app, p.currentMediaItemIndex)
                    if (isPlaying) {
                        val meta = p.mediaMetadata
                        LocationLogger.logForTrack(
                            ctx = app,
                            trackId = trackId,
                            title = meta.title?.toString() ?: "",
                            artist = meta.artist?.toString() ?: "",
                            album = meta.albumTitle?.toString() ?: ""
                        )
                    }
                }
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // Taste engine (taste/TasteHooks): logs the PREVIOUS track's listen (how much was
                // heard, skip, session) and lets Hione Radio refill. Must run BEFORE the qualifier
                // reset on the next line — it reads the previous track's fraction from it.
                runCatching { com.hione.player.taste.TasteHooks.onMediaItemTransition(app, p, mediaItem, reason) }
                mediaItem?.mediaId?.toLongOrNull()?.let { HionePlayQualifier.onTrackStart(it); HionePlayQualifier.publish(app, it) }
                WidgetUpdateExecutor.push(app)
                HioneTrackHud.publish(app, p, "transition:$reason")
                mediaItem?.mediaId?.toLongOrNull()?.let { HioneArtTheme.updateForTrackId(app, it) }
                com.hione.player.bpm.HioneBpmEngine.onPlaybackChanged(app, mediaItem, p.isPlaying)
                HionePulse.onPlayback(app, mediaItem, p.isPlaying)
                val trackId = mediaItem?.mediaId?.toLongOrNull()
                if (trackId != null) {
                    PlayerPreferences.saveLastPlayback(app, trackId, p.currentPosition)
                    PlayerPreferences.saveQueueIndex(app, p.currentMediaItemIndex)
                    val meta = mediaItem.mediaMetadata
                    LocationLogger.logForTrack(
                        ctx = app,
                        trackId = trackId,
                        title = meta.title?.toString() ?: "",
                        artist = meta.artist?.toString() ?: "",
                        album = meta.albumTitle?.toString() ?: ""
                    )
                    LikeStore.init(app)
                    val isLiked = LikeStore.isLiked(trackId)
                    try {
                        android.provider.Settings.Global.putString(
                            app.contentResolver,
                            "hione_current_track_liked",
                            if (isLiked) "1" else "0"
                        )
                        android.provider.Settings.Global.putLong(
                            app.contentResolver,
                            "hione_current_track_id",
                            trackId
                        )
                        val out = Intent("com.hione.player.action.LIKE_STATE_CHANGED").apply {
                            putExtra("track_id", trackId)
                            putExtra("is_liked", isLiked)
                        }
                        app.sendBroadcast(out)
                    } catch (_: Throwable) {}
                }
            }
            override fun onMediaMetadataChanged(m: androidx.media3.common.MediaMetadata) { WidgetUpdateExecutor.push(app) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                android.util.Log.d("HionePlayer", "onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reason")
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                android.util.Log.d("HionePlayer", "onPlaybackStateChanged: state=$playbackState")
                runCatching { com.hione.player.taste.TasteHooks.onPlaybackStateChanged(app, p, playbackState) }   // taste: last item played out
            }
            // Taste engine: smart shuffle re-orders (only when its toggle is on) and Hione Radio
            // notices when an explicit play elsewhere replaces its queue.
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                runCatching { com.hione.player.taste.TasteHooks.onShuffleModeEnabledChanged(app, p, shuffleModeEnabled) }
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                runCatching { com.hione.player.taste.TasteHooks.onTimelineChanged(app, p) }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("HionePlayer", "onPlayerError: ${error.errorCodeName} (${error.errorCode})", error)
                // Auto-recover from ANY playback error, not just an allow-list of codes. Verified
                // live 2026-08-16: a DAC/HAL hiccup surfaced as ERROR_CODE_FAILED_RUNTIME_CHECK
                // (IllegalArgumentException in DefaultAudioSink.handleBuffer after a big-FLAC
                // decoder buffer grow) — a code the old 3-item allow-list didn't cover — and the
                // player just sat dead in STATE_IDLE forever with no way back short of force-
                // killing the app. The M500 resets its audio HAL on sleep/wake and after other
                // route churn, and that can surface as any number of different error codes, so no
                // fixed allow-list is exhaustive; recover from all of them instead. prepare() from
                // STATE_IDLE keeps the current MediaItem + position, so this reliably resumes
                // exactly where playback died. Guarded by a rolling retry cap so a persistently
                // broken track can't spin the player in an infinite crash loop.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastErrorAtMs > 5_000) errorRetryStreak = 0
                lastErrorAtMs = now
                if (++errorRetryStreak <= 3) {
                    p.prepare()
                    p.play()
                } else {
                    android.util.Log.e("HionePlayer", "onPlayerError: giving up after $errorRetryStreak errors within 5s")
                }
            }
        })
        player = p
        // Heart-qualification progress feed: while playing, tick the qualifier so a track that
        // reaches >=94% without skipping becomes heartable (see HionePlayQualifier).
        run {
            val mainH = android.os.Handler(android.os.Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() {
                    val pl = player
                    if (pl != null && pl.isPlaying) {
                        pl.currentMediaItem?.mediaId?.toLongOrNull()?.let { id ->
                            HionePlayQualifier.onProgress(id, pl.currentPosition, pl.duration)
                            HionePlayQualifier.publish(app, id)
                        }
                    }
                    mainH.postDelayed(this, 1000L)
                }
            }
            mainH.postDelayed(tick, 1000L)
        }
        // Work deferred by the power governor (art/palette/HUD while the screen was off) catches up
        // the moment a profile that allows background work is back.
        HionePowerGovernor.addListener { prof ->
            if (prof == HionePowerGovernor.Profile.PERF || prof == HionePowerGovernor.Profile.BALANCED) {
                p.currentMediaItem?.mediaId?.toLongOrNull()?.let { id ->
                    HioneArtTheme.updateForTrackId(app, id)
                    HioneTrackHud.publish(app, p, "resume-work")
                }
                WidgetUpdateExecutor.push(app)
            }
        }
        // Per-listen stats DB + Last.fm scrobbling: one self-registering hook (its own Player.Listener
        // + 1 s tick) — see com.hione.player.stats.ListenSessionTracker.
        com.hione.player.stats.ListenSessionTracker.attach(app, p)
        // Redundant hardware gate (see MainActivity.isSupportedDevice) — deliberately different
        // fields/logic so patching just the Activity's check doesn't also unlock playback here.
        // Also screens out emulators/VMs (goldfish/ranchu kernel, generic build fingerprints) —
        // this app leans on real M500 hardware (DAC/amp, side keys) an emulator can't provide.
        if (!brandDeviceGateOk()) { p.playWhenReady = false; p.stop() }
        return p
    }

    private fun brandDeviceGateOk(): Boolean {
        val brandOk = android.os.Build.BRAND.equals("HiBy", ignoreCase = true) &&
            android.os.Build.DEVICE.contains("M500", ignoreCase = true)
        val hw = android.os.Build.HARDWARE.lowercase()
        val fp = android.os.Build.FINGERPRINT.lowercase()
        val looksVirtual = hw.contains("goldfish") || hw.contains("ranchu") ||
            fp.startsWith("generic") || fp.startsWith("unknown")
        return brandOk && !looksVirtual
    }

    @Synchronized
    fun ensureSession(context: Context): MediaLibrarySession {
        session?.let { return it }
        val p = ensure(context)
        val launch = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // A MediaLibrarySession (a superset of MediaSession) so Android Auto can BROWSE the on-device
        // library and drive THIS SAME player — see HioneLibraryCallback for the browse tree, the
        // play-resolution, the hands-free player-mode switches, and the analog-audio routing hook.
        // The media-button handling that used to live in an inline MediaSession.Callback here now
        // lives in that callback (identical logic), so hardware/BT transport keys behave the same.
        val s = MediaLibrarySession.Builder(context.applicationContext, p, HioneLibraryCallback(context))
            .setSessionActivity(pi)
            .build()
        session = s
        return s
    }

    /** Live-apply the pause-on-unplug preference to the running player (and persist it).
     *  Also mirrored to Settings.Global "hione_pause_on_unplug" so the launcher's quick-settings
     *  tile can read/flip the same state cross-app (root fallback matches CirrusLogicManager). */
    fun applyPauseOnUnplug(context: Context, enabled: Boolean) {
        PlayerPreferences.savePauseOnUnplug(context, enabled)
        player?.setHandleAudioBecomingNoisy(enabled)
        val v = if (enabled) 1 else 0
        val ok = runCatching {
            android.provider.Settings.Global.putInt(context.contentResolver, "hione_pause_on_unplug", v)
        }.getOrDefault(false)
        if (!ok) android.util.Log.w("PlayerHolder", "hione_pause_on_unplug publish refused (WRITE_SECURE_SETTINGS missing?) - in-app behaviour still applied")
    }

    /**
     * Start PlaybackService as a FOREGROUND service and bind a MediaController to its session.
     * Called from MainActivity when playback begins. The controller isn't used to drive transport
     * (the Activity keeps direct ExoPlayer access for the visualizer's audioSessionId); its whole
     * job is the connection itself — with a controller connected, Media3 posts the media
     * notification, keeps the service foreground while playing, and routes system media-button
     * events to the session when the app is backgrounded or the screen is off.
     */
    @Synchronized
    fun ensureControllerConnected(context: Context) {
        if (controller != null || controllerFuture != null) return
        val app = context.applicationContext
        try {
            ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
        } catch (_: Throwable) {
            // Fall through — the controller bind below also starts the service.
        }
        try {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val future = MediaController.Builder(app, token).buildAsync()
            controllerFuture = future
            future.addListener({
                try {
                    controller = future.get()
                } catch (t: Throwable) {
                    android.util.Log.e("PlayerHolder", "MediaController connect failed", t)
                    controllerFuture = null
                }
            }, ContextCompat.getMainExecutor(app))
        } catch (t: Throwable) {
            android.util.Log.e("PlayerHolder", "MediaController setup failed", t)
            controllerFuture = null
        }
    }

    @Synchronized
    fun release() {
        try { controller?.release() } catch (_: Throwable) {}
        controller = null
        controllerFuture = null
        session?.release(); session = null
        val released = player
        released?.release(); player = null
        // A released player plays nothing; the listener above will not hear about it.
        if (released != null) HionePulse.onStopped()
        com.hione.player.stats.ListenSessionTracker.reset() // closes the open listen + so a later ensure() re-attaches to the NEW player instance
    }
}
