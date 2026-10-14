package com.caf.fmradio

import android.content.Context
import android.util.Log
import java.io.File

/**
 * libfmprojectm: libprojectM 4.2 (the same vendored tree Hione Music's cassette mode uses) behind a
 * JNI wrapper bound to this package. See src/main/cpp/fm_projectm_jni.cpp.
 *
 * Everything except [available], [version] and [ensurePresets] must be called on the GL thread
 * that owns the context; the renderer in FmProjectMRenderer.kt is the only caller.
 *
 * The library loads from this APK (lib/arm64-v8a, stored uncompressed so it maps in place). It
 * only needs liblog, libEGL and libGLESv3, which every app namespace can see, so unlike
 * libqcomfm_jni it loads from /data as well as from the system image.
 */
object FmProjectMNative {
    private const val TAG = "FmProjectM"

    val available: Boolean = runCatching { System.loadLibrary("fmprojectm"); true }
        .onFailure { Log.w(TAG, "projectM library not loadable, background falls back to the art: $it") }
        .getOrDefault(false)

    /** "projectM 4.2.x", or "" when the library did not load. */
    val version: String by lazy {
        if (!available) "" else runCatching { nativeVersion() }.getOrDefault("").let { if (it.isBlank()) "projectM" else "projectM $it" }
    }

    // ---- presets

    /**
     * The curated set shipped in assets/fm_presets. Bump when that set changes; the marker in the
     * extracted dir is compared against it and a mismatch re-syncs.
     */
    private const val LIBRARY_ID = "fm-v1-hione56"
    private const val ASSET_DIR = "fm_presets"

    @Volatile private var presetDir: String? = null

    /**
     * Copy the bundled presets into a real directory projectM can read. Once per library version;
     * after that it is a single marker read. Safe from any thread.
     */
    @Synchronized
    fun ensurePresets(ctx: Context): String? {
        presetDir?.let { return it }
        return runCatching {
            val out = File(ctx.filesDir, ASSET_DIR)
            val marker = File(out, ".library_id")
            if (runCatching { marker.readText().trim() }.getOrNull() != LIBRARY_ID) {
                out.deleteRecursively()
                out.mkdirs()
                val names = ctx.assets.list(ASSET_DIR)?.filter { it.endsWith(".milk", true) }.orEmpty()
                for (n in names) {
                    ctx.assets.open("$ASSET_DIR/$n").use { i -> File(out, n).outputStream().use { i.copyTo(it) } }
                }
                marker.writeText(LIBRARY_ID)
                Log.i(TAG, "presets synced: ${names.size}")
            }
            out.absolutePath.also { presetDir = it }
        }.onFailure { Log.w(TAG, "preset sync failed: $it") }.getOrNull()
    }

    // ---- GL thread only

    fun init(fps: Int, meshW: Int, meshH: Int, presetSeconds: Float): Boolean =
        available && runCatching { nativeInit(fps, meshW, meshH, presetSeconds) }.getOrDefault(false)
    fun loadPresets(dir: String): Int = if (available) runCatching { nativeLoadPresets(dir) }.getOrDefault(0) else 0
    fun resize(w: Int, h: Int) { if (available) runCatching { nativeResize(w, h) } }
    fun feedPcm(pcm: ShortArray, count: Int) { if (available && count > 0) runCatching { nativeFeedPcm(pcm, count) } }
    fun render(timeSec: Double) { if (available) runCatching { nativeRender(timeSec) } }
    fun next() { if (available) runCatching { nativeNext() } }
    fun presetName(): String = if (available) runCatching { nativePresetName() }.getOrDefault("") else ""
    fun failures(): Int = if (available) runCatching { nativeFailures() }.getOrDefault(0) else 0
    fun destroy() { if (available) runCatching { nativeDestroy() } }

    private external fun nativeInit(fps: Int, meshW: Int, meshH: Int, presetSeconds: Float): Boolean
    private external fun nativeLoadPresets(dir: String): Int
    private external fun nativeResize(w: Int, h: Int)
    private external fun nativeFeedPcm(pcm: ShortArray, count: Int)
    private external fun nativeRender(timeSec: Double)
    private external fun nativeNext()
    private external fun nativePresetName(): String
    private external fun nativeFailures(): Int
    private external fun nativeDestroy()
    private external fun nativeVersion(): String
}
