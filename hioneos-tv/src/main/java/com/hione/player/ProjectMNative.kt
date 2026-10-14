package com.hione.player

import android.content.Context
import android.util.Log

object ProjectMNative {
    private var isLoaded = false
    val available: Boolean get() = isLoaded

    init {
        try {
            System.loadLibrary("projectM-native")
            isLoaded = true
            Log.i("ProjectMNative", "libprojectM-native loaded successfully")
        } catch (t: Throwable) {
            Log.e("ProjectMNative", "Failed to load libprojectM-native", t)
        }
    }

    private val actionQueue = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    @Volatile var currentPresetName: String = ""
        private set
    @Volatile var locked: Boolean = false
        private set

    @Volatile private var lastReq = 0L
    fun requestNext() {
        val n = System.currentTimeMillis()
        if (n - lastReq > 400) {
            lastReq = n
            actionQueue.clear()
            actionQueue.add(1)
        }
    }

    fun requestPrev() {
        val n = System.currentTimeMillis()
        if (n - lastReq > 400) {
            lastReq = n
            actionQueue.clear()
            actionQueue.add(2)
        }
    }

    val projectMCredit: String by lazy {
        if (!isLoaded) "" else runCatching {
            val v = nativeVersion()
            if (v.isBlank()) "projectM" else "projectM $v"
        }.getOrDefault("projectM")
    }

    fun presetName(): String =
        if (isLoaded) try { nativePresetName().ifBlank { currentPresetName } } catch (_: Throwable) { currentPresetName } else currentPresetName

    @Volatile private var pendingSkip = 0
    fun consumeSkip(): Boolean = if (pendingSkip > 0) { pendingSkip--; true } else false

    fun drainActions() {
        if (!isLoaded) return
        val a = actionQueue.poll() ?: return
        try {
            when (a) {
                1 -> { nativeNext(); currentPresetName = nativePresetName(); pendingSkip = 4 }
                2 -> { nativePrev(); currentPresetName = nativePresetName(); pendingSkip = 4 }
            }
        } catch (_: Throwable) {}
    }

    @Synchronized fun init() { if (isLoaded) try { nativeInit() } catch (_: Throwable) {} }
    @Synchronized fun resize(w: Int, h: Int) { if (isLoaded) try { nativeResize(w, h) } catch (_: Throwable) {} }
    @Synchronized fun render(t: Float, p: Int, b: Float, tr: Float) { if (isLoaded) try { nativeRender(t, p, b, tr) } catch (_: Throwable) {} }
    fun setPreset(p: Int) { /* no-op */ }
    @Synchronized fun feedAudio(fft: FloatArray, pcm: FloatArray) { if (isLoaded) try { nativeFeedAudio(fft, pcm) } catch (_: Throwable) {} }
    @Synchronized fun loadPresets(dir: String) { if (isLoaded) try { nativeLoadPresets(dir) } catch (_: Throwable) {} }

    @Volatile private var cachedPresetPath: String? = null
    private const val PRESET_LIBRARY_ID = "v4-tv-hione80"

    @Synchronized
    fun ensurePresets(ctx: Context): String? {
        cachedPresetPath?.let { return it }
        return try {
            val out = java.io.File(ctx.filesDir, "presets")
            out.mkdirs()
            val marker = java.io.File(out, ".library_id")
            val synced = runCatching { marker.readText().trim() }.getOrNull()
            if (synced != PRESET_LIBRARY_ID) {
                listOf("presets", "hione_presets").forEach { dirName ->
                    val names = ctx.assets.list(dirName)?.toList() ?: emptyList()
                    names.forEach { n ->
                        runCatching {
                            ctx.assets.open("$dirName/$n").use { input ->
                                java.io.File(out, n).outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                    Log.i("projectM", "$dirName: ${names.size} presets copied")
                }
                runCatching { marker.writeText(PRESET_LIBRARY_ID) }
                Log.i("projectM", "preset library synced: ${out.list()?.size ?: 0} files")
            }
            cachedPresetPath = out.absolutePath
            cachedPresetPath
        } catch (e: Throwable) {
            Log.e("projectM", "ensurePresets failed", e)
            null
        }
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
    private external fun nativeVcsVersion(): String
    private external fun nativeSetBeatSensitivity(s: Float)
}
