package com.caf.fmradio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Per-preset performance ledger for the projectM background, ported from Hione Music's PresetPerf
 * (ProjectMVisualizer.kt) with the same bars, plus one thing the player does not need: the
 * render scale steps down BEFORE any preset is blamed.
 *
 * The renderer measures fps once a second and calls [tick]. The order of blame is:
 *
 *  1. The device. [SCALE_LOW_RUN] slow seconds in a row, whatever preset is up, and the render
 *     resolution drops one step ([SCALES]). The step is remembered in the FM prefs, so the next
 *     launch starts where this device settled instead of re-learning it. The FM screen's own UI
 *     is already GPU-bound (64-70% janky frames measured), so the background gives way first.
 *  2. The preset. Only at the lowest scale: below [LOW_FPS] for [CONSEC_LOW] seconds is a strike,
 *     at or below [SEVERE_FPS] is an immediate cull, and [MAX_STRIKES] strikes disables it. A
 *     disabled preset is skipped whenever the playlist lands on it.
 *
 * Guards carried over from the player, each learned the hard way there: a cull floor
 * ([MAX_DISABLED_FRACTION]) so a CPU-starved run cannot blacklist the library, a self-heal on
 * load that wipes a ledger which did exactly that, and a cooldown between automatic advances so a
 * cull never turns into a strobe.
 *
 * Ledger: filesDir/fm_preset_perf.json, { "<preset>": {strikes, fps, disabled} }. Its own file,
 * not the player's: different app, different GPU load around it.
 *
 * GL thread only, apart from [startScale].
 */
internal object FmProjectMPerf {
    private const val TAG = "FmProjectM-perf"

    // Same bars as the player's ledger (calibrated on this device: 60 Hz panel, Adreno 610).
    private const val LOW_FPS = 18
    private const val SEVERE_FPS = 8
    private const val CONSEC_LOW = 4
    private const val MAX_STRIKES = 2
    private const val MAX_DISABLED_FRACTION = 0.5f
    private const val ADVANCE_COOLDOWN_MS = 8_000L

    /** Render scale steps, as a fraction of the view's size. Starts at index 0. */
    val SCALES = floatArrayOf(0.6f, 0.5f, 0.4f)
    private const val SCALE_LOW_RUN = 3
    /** Seconds at the cap before trying one step back up. Long, so it does not oscillate. */
    private const val SCALE_UP_AFTER_S = 90
    private const val PREF_SCALE = "projectm_scale_step"

    private data class Entry(var strikes: Int, var fps: Int, var disabled: Boolean)
    private val data = HashMap<String, Entry>()
    private var file: File? = null
    private var loaded = false

    private var lastName = ""
    private var lowRun = 0
    private var struckThisRun = false
    private var graceUntil = 0L
    private var lastAdvanceMs = 0L
    private var deviceLowRun = 0
    private var goodRun = 0

    /** Presets in the playlist, so the cull floor is half the LIBRARY, not half of the ledger. */
    @Volatile var librarySize = 0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hione_fm", Context.MODE_PRIVATE)

    /** The scale step this device settled on last time. */
    fun startScale(ctx: Context): Int =
        prefs(ctx).getInt(PREF_SCALE, 0).coerceIn(0, SCALES.size - 1)

    private fun saveScale(ctx: Context, step: Int) {
        prefs(ctx).edit().putInt(PREF_SCALE, step).apply()
    }

    private fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.filesDir, "fm_preset_perf.json").also { file = it }
        runCatching {
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                data[k] = Entry(e.optInt("strikes"), e.optInt("fps"), e.optBoolean("disabled"))
            }
            val disabled = data.values.count { it.disabled }
            if (data.isNotEmpty() && disabled >= data.size * MAX_DISABLED_FRACTION) {
                Log.w(TAG, "ledger disabled $disabled/${data.size}, resetting (starved measurement, not real)")
                data.clear()
                persist()
            }
        }
    }

    private fun persist() {
        val f = file ?: return
        runCatching {
            val o = JSONObject()
            data.forEach { (k, e) ->
                o.put(k, JSONObject().put("strikes", e.strikes).put("fps", e.fps).put("disabled", e.disabled))
            }
            f.writeText(o.toString())
        }
    }

    private fun isDisabled(name: String) = name.isNotBlank() && data[name]?.disabled == true

    private fun advance(why: String) {
        val now = System.currentTimeMillis()
        if (now - lastAdvanceMs < ADVANCE_COOLDOWN_MS) return
        lastAdvanceMs = now
        Log.i(TAG, "auto-advance ($why)")
        FmProjectMNative.next()
    }

    /**
     * Once a second with the fps just measured. [scaleStep] is the current step; the return
     * value is the step the renderer should use from now on (unchanged most of the time).
     * Only called while the radio is feeding audio at the full frame cap: idle frames are
     * deliberately slow and would read as a struggling device.
     */
    fun tick(ctx: Context, name: String, fps: Int, capFps: Int, scaleStep: Int): Int {
        ensure(ctx)
        val now = System.currentTimeMillis()
        var step = scaleStep

        // 1. Device first.
        if (fps < LOW_FPS) {
            deviceLowRun++; goodRun = 0
            if (deviceLowRun >= SCALE_LOW_RUN && step < SCALES.size - 1) {
                step++
                saveScale(ctx, step)
                Log.w(TAG, "sustained $fps fps, render scale down to ${SCALES[step]}")
                deviceLowRun = 0; lowRun = 0
                graceUntil = now + 2000   // let the new size settle before judging the preset
                return step
            }
        } else {
            deviceLowRun = 0
            if (fps >= capFps - 2) goodRun++ else goodRun = 0
            if (goodRun >= SCALE_UP_AFTER_S && step > 0) {
                step--
                saveScale(ctx, step)
                goodRun = 0
                Log.i(TAG, "steady at the cap, render scale back up to ${SCALES[step]}")
                graceUntil = now + 2000
                return step
            }
        }

        // 2. Then the preset.
        if (name.isBlank()) return step
        if (name != lastName) {
            lastName = name; lowRun = 0; struckThisRun = false; graceUntil = now + 1200
        }
        if (isDisabled(name)) { advance("blacklisted \"$name\""); return step }
        if (now < graceUntil) return step
        // Not the preset's fault while the resolution still has somewhere to go.
        if (step < SCALES.size - 1) return step

        lowRun = if (fps < LOW_FPS) lowRun + 1 else 0
        val severe = fps in 1..SEVERE_FPS
        if ((lowRun >= CONSEC_LOW || severe) && !struckThisRun) {
            struckThisRun = true
            val e = data.getOrPut(name) { Entry(0, fps, false) }
            e.strikes = if (severe) MAX_STRIKES else e.strikes + 1
            e.fps = fps
            val disabledCount = data.values.count { it.disabled }
            val roomToCull = disabledCount < (maxOf(data.size, librarySize) * MAX_DISABLED_FRACTION).toInt().coerceAtLeast(4)
            if (e.strikes >= MAX_STRIKES && roomToCull) e.disabled = true
            persist()
            Log.w(TAG, "low fps $fps on \"$name\", strike ${e.strikes}" +
                if (e.disabled) ", DISABLED" else if (!roomToCull) " (cull floor reached, kept)" else "")
            if (e.disabled) advance("culled \"$name\" at $fps fps")
        }
        return step
    }

}
