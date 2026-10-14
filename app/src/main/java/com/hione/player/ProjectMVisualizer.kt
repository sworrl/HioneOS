package com.hione.player

import android.content.Context
import android.media.audiofx.Visualizer
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

object ProjectMNative {
    private var isLoaded = false
    /** True when libprojectM-native loaded — the shader engine (visualizer/) is the fallback otherwise. */
    val available: Boolean get() = isLoaded

    init {
        try {
            System.loadLibrary("projectM-native")
            isLoaded = true
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    @Synchronized fun init() { if (isLoaded) try { nativeInit() } catch (_: Throwable) {} }
    @Synchronized fun resize(w: Int, h: Int) { if (isLoaded) try { nativeResize(w, h) } catch (_: Throwable) {} }
    @Synchronized fun render(t: Float, p: Int, b: Float, tr: Float) { if (isLoaded) try { nativeRender(t, p, b, tr) } catch (_: Throwable) {} }
    // Preset selection is driven by the playlist auto-cycle + gestures (requestNext/Prev), not this
    // legacy enum call — kept only so the AndroidView update path has something safe to call.
    fun setPreset(p: Int) { /* no-op */ }
    @Synchronized fun feedAudio(fft: FloatArray, pcm: FloatArray) { if (isLoaded) try { nativeFeedAudio(fft, pcm) } catch (_: Throwable) {} }
    /** Callers must run HioneVizPresetGates.apply on [dir] first, or locked reward presets get loaded. */
    @Synchronized fun loadPresets(dir: String) { if (isLoaded) try { nativeLoadPresets(dir) } catch (_: Throwable) {} }

    @Volatile private var cachedPresetPath: String? = null

    /**
     * Sync the bundled .milk presets from assets into a real dir, returning its path (or null).
     * Fast-paths when already synced so switching between Now Playing and Tape Mode is instant.
     */
    /**
     * Identity of the shipped preset library. Bump whenever assets/presets_pack.zip or the loose
     * assets/presets/ set changes — the marker file below is compared against it, and a mismatch
     * re-syncs. (A plain file-count comparison is not usable any more: the pack holds ~9.8k entries,
     * so listing and diffing them on every launch would cost more than the sync it guards.)
     */
    private const val PRESET_LIBRARY_ID = "v4-cotc9795+curated+hione80"
    private const val PRESET_PACK_ASSET = "presets_pack.zip"

    /**
     * Safe to call from the GL thread and from a background warm-up at the same time: the first
     * caller does the unpack while the other blocks, instead of both writing the same ~9.8k files.
     */
    @Synchronized
    fun ensurePresets(ctx: Context): String? {
        cachedPresetPath?.let { return it }
        return try {
            val out = java.io.File(ctx.filesDir, "presets"); out.mkdirs()
            val marker = java.io.File(out, ".library_id")
            val synced = runCatching { marker.readText().trim() }.getOrNull()
            if (synced != PRESET_LIBRARY_ID) {
                // 1. The bulk pack (projectM "cream of the crop", flattened). One zip stream beats
                //    ~9.8k individual AssetManager.open() calls by an order of magnitude, and this
                //    runs once per library version, not per launch.
                runCatching {
                    ctx.assets.open(PRESET_PACK_ASSET).use { raw ->
                        java.util.zip.ZipInputStream(raw.buffered()).use { zin ->
                            var n = 0
                            while (true) {
                                val e = zin.nextEntry ?: break
                                val name = e.name.substringAfterLast('/')
                                if (!e.isDirectory && name.endsWith(".milk", ignoreCase = true)) {
                                    java.io.File(out, name).outputStream().buffered().use { zin.copyTo(it) }
                                    n++
                                }
                                zin.closeEntry()
                            }
                            android.util.Log.i("projectM", "preset pack unpacked: $n presets")
                        }
                    }
                }.onFailure { android.util.Log.w("projectM", "preset pack unpack failed: $it") }

                // 2. The curated presets, then OUR OWN Hione presets, land LAST so they win any name
                //    clash with the pack. The hione_presets/ set is what replaced the second GLES2
                //    renderer: same look, delivered as real .milk files inside projectM instead of a
                //    parallel visualiser with its own engine, preset list, settings and toggle.
                listOf("presets", "hione_presets").forEach { dirName ->
                    val names = ctx.assets.list(dirName)?.toList() ?: emptyList()
                    names.forEach { n ->
                        runCatching {
                            ctx.assets.open("$dirName/$n").use { input ->
                                java.io.File(out, n).outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                    android.util.Log.i("projectM", "$dirName: ${names.size} presets copied")
                }
                runCatching { marker.writeText(PRESET_LIBRARY_ID) }
                android.util.Log.i("projectM", "preset library synced: ${out.list()?.size ?: 0} files")
            }
            cachedPresetPath = out.absolutePath
            cachedPresetPath
        } catch (_: Throwable) { null }
    }

    // projectM is NOT thread-safe: all calls must run on the GL thread. Gesture actions
    // are enqueued from the UI thread and drained inside the renderer's draw loop.
    private val actionQueue = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    @Volatile var currentPresetName: String = ""
        private set
    @Volatile var locked: Boolean = false
        private set

    @Volatile private var lastReq = 0L
    // Debounce: rapid preset switching (double-tap spam) into a mid-render state
    // segfaults inside projectM. Ignore requests that arrive too fast.
    fun requestNext() { val n = System.currentTimeMillis(); if (n - lastReq > 500) { lastReq = n; actionQueue.clear(); actionQueue.add(1) } }
    fun requestPrev() { val n = System.currentTimeMillis(); if (n - lastReq > 500) { lastReq = n; actionQueue.clear(); actionQueue.add(2) } }
    fun requestToggleLock() { /* disabled: lock toggling could stall the GL thread */ }
    // Read the live preset name (playlist query only, no GL — mutex-guarded on the native side),
    // so the name is correct even before the first gesture switch.
    /**
     * "projectM 4.2.0" — read from the loaded library, cached once.
     *
     * The fullscreen visualiser is projectM's work and says so. Blank when the native library did
     * not load, so a caller can leave the credit off rather than claim a version we do not have.
     */
    val projectMCredit: String by lazy {
        if (!isLoaded) "" else runCatching {
            val v = nativeVersion()
            if (v.isBlank()) "projectM" else "projectM $v"
        }.getOrDefault("projectM")
    }

    /** The git revision the vendored library was built from, or "" if unavailable. */
    val projectMVcs: String by lazy {
        if (!isLoaded) "" else runCatching { nativeVcsVersion() }.getOrDefault("")
    }

    fun presetName(): String =
        if (isLoaded) try { nativePresetName().ifBlank { currentPresetName } } catch (_: Throwable) { currentPresetName } else currentPresetName

    // After a preset switch projectM briefly has no valid active preset; rendering that
    // frame null-derefs. Skip a few frames so the new preset finishes loading.
    @Volatile private var pendingSkip = 0
    fun consumeSkip(): Boolean = if (pendingSkip > 0) { pendingSkip--; true } else false

    /** GL THREAD ONLY — called from the renderer each frame. Process at most ONE action/frame. */
    fun drainActions() {
        if (!isLoaded) return
        val a = actionQueue.poll() ?: return
        try {
            when (a) {
                1 -> { nativeNext(); currentPresetName = nativePresetName(); pendingSkip = 5 }
                2 -> { nativePrev(); currentPresetName = nativePresetName(); pendingSkip = 5 }
            }
        } catch (_: Throwable) {}
    }

    private external fun nativeInit()
    private external fun nativeResize(width: Int, height: Int)
    private external fun nativeRender(timeSec: Float, preset: Int, bass: Float, treble: Float)
    private external fun nativeSetPreset(preset: Int)
    private external fun nativeFeedAudio(fft: FloatArray, pcm: FloatArray)
    private external fun nativeLoadPresets(dir: String)
    private external fun nativeNext()
    private external fun nativePrev()
    private external fun nativeToggleLock(): Boolean
    private external fun nativeIsLocked(): Boolean
    private external fun nativePresetName(): String
    private external fun nativeVersion(): String
    private external fun nativePresetFailures(): Int
    private external fun nativeQueueLoadPresetPath(path: String)
    private external fun nativePendingLoadResult(): Int

    /** projectM-reported preset load failures since init (each is also logged by name). */
    fun presetFailures(): Int = if (isLoaded) runCatching { nativePresetFailures() }.getOrDefault(0) else 0

    /**
     * Self-test: load every preset matching [prefix] one after another on the GL thread and report
     * the ones projectM rejects. Runs only while the visualiser is up (projectM lives on its GL
     * context). Results go to logcat as "PRESET SELFTEST".
     */
    fun selfTest(ctx: android.content.Context, prefix: String = "Hione - "): Pair<Int, List<String>> {
        if (!isLoaded) return 0 to emptyList()
        val dir = java.io.File(ctx.filesDir, "presets")
        val files = dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".milk") }?.sortedBy { it.name } ?: emptyList()
        val bad = ArrayList<String>()
        for (f in files) {
            // Queue for the GL thread, then wait for a frame to consume it (up to ~2s each).
            runCatching { nativeQueueLoadPresetPath(f.absolutePath) }
            var result = 0; var waited = 0
            while (result == 0 && waited < 2000) { Thread.sleep(20); waited += 20; result = runCatching { nativePendingLoadResult() }.getOrDefault(2) }
            if (result != 1) bad.add(f.name + (if (result == 0) " (no frame rendered; is the visualiser open?)" else ""))
        }
        android.util.Log.i("projectM", "PRESET SELFTEST: ${files.size} tried, ${bad.size} failed" +
            (if (bad.isNotEmpty()) ": " + bad.joinToString() else ""))
        return files.size to bad
    }
    private external fun nativeVcsVersion(): String
    private external fun nativeSetBeatSensitivity(s: Float)
}

/**
 * Featherweight per-preset performance ledger. The renderer already measures fps once a second;
 * each tick lands here. A preset that stays below [LOW_FPS] for [CONSEC_LOW] consecutive seconds
 * takes ONE strike (the measured fps is recorded in the ledger); at [MAX_STRIKES] strikes the
 * preset is disabled — noted in the ledger and auto-skipped whenever the playlist lands on it.
 * Ledger lives at filesDir/preset_perf.json: { "<preset>": {strikes, fps, disabled} }.
 */
object PresetPerf {
    // Bars calibrated to this device: a 60 Hz panel with an Adreno 610. projectM at 24-30 fps is
    // perfectly watchable, so the old LOW_FPS=30 bar culled presets that looked fine — and because
    // a CPU-starved run measures every preset low, the ledger blacklisted all 145 of them and the
    // stage then skipped ~2x a second forever. Only genuinely unwatchable frame rates cull now.
    private const val LOW_FPS = 18
    private const val SEVERE_FPS = 8
    private const val CONSEC_LOW = 4          // sustained, not a one-second dip behind a GC
    private const val MAX_STRIKES = 2
    /** Never let the ledger blacklist the library out from under the user. */
    private const val MAX_DISABLED_FRACTION = 0.5f
    /** Floor between automatic preset advances, so a cull can never become a visual strobe. */
    private const val ADVANCE_COOLDOWN_MS = 8_000L
    private data class Entry(var strikes: Int, var fps: Int, var disabled: Boolean)
    private val data = HashMap<String, Entry>()
    private var file: java.io.File? = null
    @Volatile private var loaded = false
    // per-run (GL thread only) state
    private var lastName = ""
    private var lowRun = 0
    private var struckThisRun = false
    private var graceUntil = 0L
    private var lastAdvanceMs = 0L

    /** Rate-limited auto-advance: returns true if the skip was actually issued. */
    private fun advance(why: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAdvanceMs < ADVANCE_COOLDOWN_MS) return false
        lastAdvanceMs = now
        android.util.Log.i("projectM-perf", "auto-advance ($why)")
        ProjectMNative.requestNext()
        return true
    }

    @Synchronized private fun ensure(ctx: Context) {
        if (loaded) return
        file = java.io.File(ctx.filesDir, "preset_perf.json")
        runCatching {
            val f = file!!
            if (f.exists()) {
                val o = org.json.JSONObject(f.readText())
                var dirty = false
                o.keys().forEach { k ->
                    val e = o.getJSONObject(k)
                    val strikes = e.optInt("strikes")
                    val fps = e.optInt("fps")
                    val disabled = e.optBoolean("disabled")
                    // No retroactive cull. The old audit disabled everything recorded under 22 fps,
                    // which is how a single CPU-starved run permanently blacklisted the whole library.
                    data[k] = Entry(strikes, fps, disabled)
                }
                // Self-heal: a ledger that has disabled (almost) everything is measurement noise,
                // not 145 genuinely broken presets. Wipe it and let them re-qualify.
                val disabledCount = data.values.count { it.disabled }
                if (data.isNotEmpty() && disabledCount >= data.size * MAX_DISABLED_FRACTION) {
                    android.util.Log.w("projectM-perf",
                        "ledger disabled $disabledCount/${data.size} presets — resetting (starved measurement, not real)")
                    data.clear()
                    dirty = true
                }
                if (dirty) persist()
            }
        }
        loaded = true
    }

    @Synchronized private fun persist() {
        val f = file ?: return
        runCatching {
            val o = org.json.JSONObject()
            data.forEach { (k, e) ->
                o.put(k, org.json.JSONObject().put("strikes", e.strikes).put("fps", e.fps).put("disabled", e.disabled))
            }
            f.writeText(o.toString())
        }
    }

    @Synchronized fun isDisabled(name: String): Boolean {
        if (name.isBlank()) return false
        return data[name]?.disabled == true
    }

    /** GL thread, once per second, with the fps just measured for the active preset. */
    fun tick(ctx: Context, name: String, fps: Int) {
        if (name.isBlank()) return
        ensure(ctx)

        val now = System.currentTimeMillis()
        if (name != lastName) {   // new preset on stage: fresh window + a short loading grace period
            lastName = name; lowRun = 0; struckThisRun = false; graceUntil = now + 1200
        }

        // Must check isDisabled BEFORE grace period so blacklisted presets skip immediately
        if (isDisabled(name)) {
            advance("blacklisted \"$name\"")   // cooldown-limited: never a skip storm
            return
        }

        if (now < graceUntil) return

        lowRun = if (fps < LOW_FPS) lowRun + 1 else 0

        // Severe low FPS (< 18 FPS) takes ONE STRIKE to be instantly culled!
        val severe = fps in 1..SEVERE_FPS
        if ((lowRun >= CONSEC_LOW || severe) && !struckThisRun) {
            struckThisRun = true
            synchronized(this) {
                val e = data.getOrPut(name) { Entry(0, fps, false) }
                e.strikes = if (severe) MAX_STRIKES else e.strikes + 1
                e.fps = fps
                // Refuse to disable past the floor: with half the library already culled the problem
                // is the device being starved, not the presets, and culling further leaves nothing.
                val disabledCount = data.values.count { it.disabled }
                val roomToCull = disabledCount < (data.size * MAX_DISABLED_FRACTION).toInt().coerceAtLeast(4)
                if ((e.strikes >= MAX_STRIKES || severe) && roomToCull) e.disabled = true
                persist()
                android.util.Log.w("projectM-perf",
                    "low fps $fps on \"$name\" — strike ${e.strikes}" +
                        if (e.disabled) " → DISABLED" else if (!roomToCull) " (cull floor reached, kept)" else "")
                if (e.disabled) advance("culled \"$name\" at $fps fps")
            }
        }
    }
}

enum class ProjectMPreset(val title: String) {
    CYBER_TUNNEL("Cyber Tunnel"),
    HYPERDRIVE("Hyperdrive Nebula"),
    PLASMA_SPECTRUM("Plasma Wave"),
    VORTEX_CORE("Vortex Core"),
    AUDIO_MATRIX("Audio Matrix")
}

// The GL surface + native engine + 150 loaded presets are a genuinely heavy chunk of memory on
// this device (confirmed live: ~185MB of native heap/GL/EGL tracking attributable to it, on a
// device that was down to 66MB system-wide free) — the OS's lowmemorykiller doesn't negotiate, it
// just reaps the whole process, audio playback included ("either crashing or restarting" while
// just trying to listen). MainActivity.onTrimMemory flips this flag under real pressure so every
// live ProjectMVisualizerView tears its GL surface down proactively — trading the visualizer for
// staying alive — instead of waiting for the OS to kill the app outright.
object VisualizerMemoryGuard {
    var suppressed by mutableStateOf(false)
        private set
    fun suppress() { suppressed = true }
    fun release() { suppressed = false }
}

@Composable
fun ProjectMVisualizerView(
    sessionId: Int,
    preset: ProjectMPreset,
    modifier: Modifier = Modifier
) {
    // Power governor input: a live visualizer = PERF (full clocks, render hints) while playing.
    androidx.compose.runtime.DisposableEffect(Unit) {
        HionePowerGovernor.noteVisualizerVisible(true)
        onDispose { HionePowerGovernor.noteVisualizerVisible(false) }
    }
    // Idle dim/ambient means either nobody's looking or they're looking at the deliberately
    // minimal ambient screen — either way, this is the single most expensive continuous piece of
    // UI in the app (a 60fps GL render loop), so it's the first thing to stop.
    if (VisualizerMemoryGuard.suppressed || !IdleController.screenActive) {
        Box(modifier.background(Mascot.color(0xFF0A2528)))
        return
    }

    // GLSurfaceView.onPause()/onResume() are NOT wired up by the OS automatically — Compose's
    // AndroidView only calls factory/update/onRelease on recomposition, which never fires just
    // because the screen turned off. Without this observer the GL thread kept rendering
    // RENDERMODE_CONTINUOUSLY into a surface the system had already torn down on screen-off,
    // hammering the GPU/CPU the whole time the screen was "asleep" and leaving the native
    // projectM context wedged/crashed by the time the screen woke back up (root cause of the
    // random-sleep + "player breaks on wake" bug). Tie the real Activity lifecycle to the view.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val viewRef = remember { mutableStateOf<ProjectMGLSurfaceView?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewRef.value?.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewRef.value?.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { ctx ->
            ProjectMGLSurfaceView(ctx, sessionId).apply {
                setPreset(preset.ordinal)
                viewRef.value = this
            }
        },
        update = { view ->
            view.setPreset(preset.ordinal)
            view.setSessionId(sessionId)
        },
        onRelease = { view ->
            view.onPause()
            viewRef.value = null
        },
        modifier = modifier
    )
}

/**
 * One shared audio-capture for the whole app: a single Android [Visualizer] bound to the player's
 * audio session, writing into shared buffers that any GL surface reads. Using ONE instance avoids
 * two Visualizers fighting over the same session — which was leaving the fullscreen viz silent.
 */
object AudioCapture {
    private var visualizer: Visualizer? = null
    private var sessionId = Int.MIN_VALUE
    val waveform = FloatArray(64)
    val fft = FloatArray(64)
    @Volatile var bass = 0f
    @Volatile var treble = 0f
    /** True while a real Visualizer is bound and capturing; false = the buffers above are stale. */
    val active: Boolean get() = visualizer != null

    @Synchronized fun ensure(session: Int) {
        if (session == sessionId && visualizer != null) return
        release()
        sessionId = session
        try {
            visualizer = Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, wf: ByteArray?, sr: Int) {
                        if (wf == null) return
                        val step = wf.size / 64; if (step <= 0) return
                        for (i in 0 until 64) { val s = (wf[i * step].toInt() and 0xFF) - 128; waveform[i] = s / 128.0f }
                    }
                    override fun onFftDataCapture(v: Visualizer?, data: ByteArray?, sr: Int) {
                        if (data == null) return
                        var bs = 0f; var ts = 0f
                        val step = (data.size / 2) / 64; if (step <= 0) return
                        for (i in 0 until 64) {
                            val idx = i * step * 2 + 2
                            val re = if (idx < data.size) data[idx].toFloat() else 0f
                            val im = if (idx + 1 < data.size) data[idx + 1].toFloat() else 0f
                            val mag = (hypot(re, im) / 100.0f).coerceIn(0f, 1f)
                            fft[i] = mag
                            if (i < 10) bs += mag
                            if (i > 35) ts += mag
                        }
                        bass = (bs / 10f).coerceIn(0f, 1.5f); treble = (ts / 28f).coerceIn(0f, 1.5f)
                    }
                }, Visualizer.getMaxCaptureRate() / 2, true, true)
                enabled = true
            }
        } catch (_: Throwable) { visualizer = null; sessionId = Int.MIN_VALUE }
    }

    @Synchronized fun release() {
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Throwable) {}
        visualizer = null; sessionId = Int.MIN_VALUE
    }
}

class ProjectMGLSurfaceView(context: Context, private var sessionId: Int) : GLSurfaceView(context) {
    private val renderer: ProjectMRenderer

    companion object {
        // The native engine is one shared, GL-context-bound singleton (see projectm_native.cpp —
        // nativeInit tears down and rebuilds it per surface). Its own mutex only serializes access
        // to the C++ struct; it can NOT stop a stale surface's GL thread from issuing real draw
        // calls against object handles that belonged to a DIFFERENT, already-torn-down EGL context
        // — that's undefined behavior at the driver level (a silent native crash, no Java stack
        // trace, no tombstone — confirmed live: "Process ... has died: prcp FGS" with nothing else
        // in logcat). Compose does not guarantee the old AndroidView's onRelease (→ onPause, which
        // blocks until its GL thread actually stops) runs before the new one's onAttachedToWindow
        // during a screen swap (fullscreen toggle, Now Playing ↔ Tape). Enforce it explicitly here:
        // a brand-new surface pauses whatever surface came before it — synchronously, before it
        // starts producing frames of its own.
        @Volatile private var current: ProjectMGLSurfaceView? = null
    }

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        // Extract presets on the GL thread (in onSurfaceCreated), NOT here on the UI thread —
        // syncing 130 files must never block the main thread.
        renderer = ProjectMRenderer(sessionId, context.applicationContext)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private var lastPreset = -1
    fun setPreset(presetIndex: Int) {
        // AndroidView's factory/update lambdas run on the UI thread; projectM is GL-thread-only.
        // Hop to the GL thread and skip no-op repeats (update fires on every recomposition).
        if (presetIndex == lastPreset) return
        lastPreset = presetIndex
        queueEvent { renderer.setPreset(presetIndex) }
    }

    fun setSessionId(id: Int) {
        if (this.sessionId != id) {
            this.sessionId = id
            renderer.updateSessionId(id)
        }
    }

    // Cap the render-buffer resolution: the M500's GPU chokes on heavy presets at full screen res.
    // The SurfaceView upscales the smaller buffer to the view, so heavy presets still hit framerate
    // without dropping any from the pack.
    // SMALL embedded surfaces (the mini viewer in Now Playing) render at ~half res: fullscreen has
    // the GPU to itself, but the mini viz shares it with the animated UI around it — same preset,
    // same GPU, less headroom, hence the lag. Half res there is invisible (Milkdrop output is soft)
    // and quarters the pixel cost.
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        val cap = 800
        val longest = maxOf(w, h)
        val s = when {
            longest > cap -> cap.toFloat() / longest
            longest < 520 -> 0.55f
            else -> 1f
        }
        holder.setFixedSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronized(Companion) {
            val prev = current
            if (prev != null && prev !== this) runCatching { prev.onPause() }   // blocks until prev's GL thread is actually paused
            current = this
        }
        onResume()
    }

    override fun onDetachedFromWindow() {
        onPause()
        super.onDetachedFromWindow()
        renderer.release()
        synchronized(Companion) { if (current === this) current = null }
    }
}

private class ProjectMRenderer(private var audioSessionId: Int, private val appCtx: Context) : GLSurfaceView.Renderer {
    private var startTime = System.currentTimeMillis()
    private var currentPreset = 0

    fun updateSessionId(id: Int) { audioSessionId = id }   // AudioCapture re-binds on the next frame

    fun setPreset(index: Int) {
        currentPreset = index
        ProjectMNative.setPreset(index)
    }

    // Audio capture is shared (AudioCapture) and outlives any single surface, so nothing to release.
    fun release() {}

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        ProjectMNative.init()
        val path = ProjectMNative.ensurePresets(appCtx)   // GL thread: sync 130 presets off the UI thread
        if (!path.isNullOrEmpty()) {
            // The ONLY playlist load in the app (Now Playing, Tape Mode and every other stage all
            // build this renderer). Gate the BPM-game reward families right here, after any re-sync
            // and before projectM reads the dir; see HioneVizPresetGates.
            HioneVizPresetGates.apply(appCtx, java.io.File(path))
            ProjectMNative.loadPresets(path)
        }
        PresetPerf.isDisabled("") // Trigger immediate database audit & retroactive cull of low FPS presets
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        ProjectMNative.resize(w, h)
    }

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    // ---- HionePowerGovernor render-thread integration ----
    private var hintSession: android.os.PerformanceHintManager.Session? = null
    private var frameStartNs = 0L
    private var lastFrameEndNs = 0L
    private fun syncPerfHint() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val want = HionePowerGovernor.perfHintWanted
        if (want && hintSession == null) {
            hintSession = runCatching {
                (appCtx.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? android.os.PerformanceHintManager)
                    ?.createHintSession(intArrayOf(android.os.Process.myTid()), 16_666_666L)   // 60 fps budget
            }.getOrNull()
        } else if (!want && hintSession != null) {
            runCatching { hintSession?.close() }; hintSession = null
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        // FPS cap (30 when hot / user-capped / SAVE mode): sleep off the remainder of the frame
        // budget so the continuous render loop can't burn more than it's allowed to.
        val cap = HionePowerGovernor.visFpsCap
        if (cap in 1..59 && lastFrameEndNs != 0L) {
            val budgetNs = 1_000_000_000L / cap
            val waitNs = budgetNs - (System.nanoTime() - lastFrameEndNs)
            if (waitNs > 500_000L) runCatching { Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt()) }
        }
        syncPerfHint()
        frameStartNs = System.nanoTime()
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0f
        ProjectMNative.drainActions()   // GL-thread: apply queued gesture actions safely
        AudioCapture.ensure(audioSessionId)   // bind the shared capture to this player session
        if (ProjectMNative.consumeSkip()) {
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)  // preset loading: black frame, no render (avoids null-preset segfault)
        } else {
            ProjectMNative.feedAudio(AudioCapture.fft, AudioCapture.waveform)
            ProjectMNative.render(elapsed, currentPreset, AudioCapture.bass, AudioCapture.treble)
        }
        frameCount++
        val now = System.currentTimeMillis()
        val elapsedMs = now - lastFpsTime
        if (elapsedMs >= 1000) {
            val measuredFps = (frameCount * 1000L / elapsedMs.coerceAtLeast(1L)).toInt()
            android.util.Log.i("projectM-fps", "fps=$measuredFps (frames=$frameCount over ${elapsedMs}ms) preset=${ProjectMNative.presetName()}")
            // Per-preset perf ledger: strike chronically slow presets, auto-disable at 2 strikes or severe low FPS
            PresetPerf.tick(appCtx, ProjectMNative.presetName(), measuredFps)
            frameCount = 0; lastFpsTime = now
        }
        lastFrameEndNs = System.nanoTime()
        if (android.os.Build.VERSION.SDK_INT >= 31) hintSession?.let { h -> runCatching { h.reportActualWorkDuration(lastFrameEndNs - frameStartNs) } }
    }
}
