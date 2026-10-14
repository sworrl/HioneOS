package com.hione.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Push all the power to the DACs, starve everything else." A small state machine evaluated on
 * screen on/off, playback, visualizer / Now Playing visibility, charging and thermal state:
 *
 *  PERF        screen ON + playing + (visualizer or Now Playing/Tape visible, or charging):
 *              Window SUSTAINED_PERFORMANCE_MODE on, PerformanceHintManager session on the GL
 *              thread (target 16.6 ms, API 31+), animations on, BPM/art/palette work allowed,
 *              root CPU governor (CpuPerformance) applied when the user enabled it.
 *  BALANCED    screen ON, not visualizing (or thermal ≥ 42 °C / low headroom — vis FPS capped to
 *              30 instead of killed): sustained-perf off, hint session closed, normal background.
 *  AUDIO_ONLY  screen OFF + playing: everything non-audio starves — vis GL paused by lifecycle,
 *              BPM / art / palette / HUD / weather / ingest / library rescans / location / Wi-Fi
 *              and BT scans deferred (allowBackgroundWork=false), widget repaint ≥ 30 s, system
 *              battery saver ON (Settings.Global low_power=1, previous value restored on exit).
 *              Audio is untouched: the player's WAKE_MODE_LOCAL lock + foreground service stay,
 *              DTA/DIRECT + full volume range are re-asserted on entry, vendor.audio.hiby.* never
 *              touched.
 *  IDLE        not playing: battery saver on while the screen is off, all engines idle, library
 *              daemon on a slow schedule.
 *
 * Transitions debounce 3 s. Charging biases toward PERF/BALANCED. Manual override: Mode.PERF pins
 * PERF/BALANCED semantics (never saver), Mode.SAVE pins AUDIO_ONLY/IDLE semantics (saver even
 * with the screen on, vis capped). The active profile is published to Settings.Global
 * `hione_power_profile` ("perf"|"balanced"|"audio_only"|"idle") + `hione_power_profile_ts` for the
 * ROM's root daemon (CPU/GPU governance) and the launcher/systemui (animation damping).
 */
object HionePowerGovernor {
    private const val TAG = "HionePowerGovernor"
    const val KEY_PROFILE = "hione_power_profile"
    const val KEY_PROFILE_TS = "hione_power_profile_ts"
    /** Manual override — a Settings.Global so the HioneOS Settings widgets / launcher tiles flip the
     *  SAME switch the in-app selector uses: "auto" | "perf" | "save". Observed live. */
    const val KEY_OVERRIDE = "hione_power_mode"
    /** Pre-2.0.237 key, still read once so an old value is not lost. */
    private const val KEY_OVERRIDE_LEGACY = "hione_power_override"
    private const val KEY_LOW_POWER = "low_power"
    private const val PREFS = "hione_power_prefs"
    private const val DEBOUNCE_MS = 3_000L
    private const val THERMAL_HOT_C = 42f
    private const val THERMAL_POLL_MS = 20_000L
    const val WIDGET_MIN_INTERVAL_SAVING_MS = 30_000L
    const val DAEMON_DEBOUNCE_SAVING_MS = 5 * 60_000L

    enum class Profile(val key: String) { PERF("perf"), BALANCED("balanced"), AUDIO_ONLY("audio_only"), IDLE("idle") }
    enum class Mode(val key: String) { AUTO("auto"), PERF("perf"), SAVE("save") }

    // ---- inputs (Compose state so the Settings readout is live) ----
    var screenOn by mutableStateOf(true); private set
    var playing by mutableStateOf(false); private set
    var visualizerVisible by mutableStateOf(false); private set
    var nowPlayingVisible by mutableStateOf(false); private set
    var charging by mutableStateOf(false); private set
    var thermalHot by mutableStateOf(false); private set
    var cpuTempC by mutableStateOf(0f); private set
    var mode by mutableStateOf(Mode.AUTO); private set

    // ---- outputs ----
    var profile by mutableStateOf(Profile.BALANCED); private set
    /** BPM / art / palette / HUD / weather / ingest / scans may run. */
    val allowBackgroundWork: Boolean get() = profile == Profile.PERF || profile == Profile.BALANCED
    val allowLocation: Boolean get() = allowBackgroundWork
    /** Visualizer frame cap: 60 normally, 30 when hot or the user asked for it, 30 in SAVE. */
    val visFpsCap: Int get() = if (thermalHot || userVisFpsCap30 || mode == Mode.SAVE) 30 else 60
    /** PerformanceHintManager session wanted on the render thread. */
    val perfHintWanted: Boolean get() = profile == Profile.PERF && !thermalHot
    val sustainedPerfWanted: Boolean get() = profile == Profile.PERF

    // ---- per-profile user toggles ----
    var saverInAudioOnly by mutableStateOf(true); private set
    var userVisFpsCap30 by mutableStateOf(false); private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Profile) -> Unit>()
    fun addListener(l: (Profile) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Profile) -> Unit) { listeners.remove(l) }

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var app: Context? = null
    @Volatile private var prevLowPower: Int? = null   // value to restore when we leave saver
    private var pendingEval: Runnable? = null
    private var lastAppliedAt = 0L
    private val thermalTick = object : Runnable {
        override fun run() { pollThermal(); if (profile == Profile.PERF || profile == Profile.BALANCED) main.postDelayed(this, THERMAL_POLL_MS) }
    }

    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = readOverride(a)
        saverInAudioOnly = p.getBoolean("saver_audio_only", true)
        userVisFpsCap30 = p.getBoolean("vis_fps_30", false)
        prevLowPower = p.getInt("prev_low_power", -1).takeIf { it >= 0 }
        runCatching {
            val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
            screenOn = pm.isInteractive
        }
        runCatching {
            val bs = a.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            charging = bs?.let { isCharging(it) } ?: false
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> screenOn = true
                    Intent.ACTION_SCREEN_OFF -> screenOn = false
                    Intent.ACTION_POWER_CONNECTED -> charging = true
                    Intent.ACTION_POWER_DISCONNECTED -> charging = false
                    Intent.ACTION_BATTERY_CHANGED -> charging = isCharging(i)
                }
                scheduleEval("broadcast ${i.action?.substringAfterLast('.')}")
            }
        }
        runCatching {
            a.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            })
        }
        // Live-observe the override Global (HioneOS Settings widgets / launcher tiles write it too).
        runCatching {
            a.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(KEY_OVERRIDE), false,
                object : android.database.ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        val m = readOverride(a)
                        if (m != mode) { mode = m; applyNow("override-global=${m.key}") }
                    }
                })
        }
        playing = runCatching { PlayerHolder.player?.isPlaying == true }.getOrDefault(false)
        // If a previous process died inside saver, put the user's value back.
        prevLowPower?.let { restoreLowPower(a, it) }
        pollThermal()
        applyNow("init")
    }

    private fun isCharging(i: Intent): Boolean {
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
    }

    // ---- input setters ----
    fun onPlaybackState(ctx: Context, isPlaying: Boolean) { init(ctx); if (playing != isPlaying) { playing = isPlaying; scheduleEval("playback=$isPlaying") } }
    fun noteVisualizerVisible(v: Boolean) { if (visualizerVisible != v) { visualizerVisible = v; scheduleEval("vis=$v") } }
    fun noteNowPlayingVisible(v: Boolean) { if (nowPlayingVisible != v) { nowPlayingVisible = v; scheduleEval("np=$v") } }
    private fun readOverride(a: Context): Mode = runCatching {
        Settings.Global.getString(a.contentResolver, KEY_OVERRIDE)
            ?: Settings.Global.getString(a.contentResolver, KEY_OVERRIDE_LEGACY)
    }.getOrNull().let { v -> Mode.values().firstOrNull { it.key == v } ?: Mode.AUTO }

    /** "72% · 31.4 °C · charging" for the settings summary card (sticky BATTERY_CHANGED, no receiver). */
    fun batterySummary(ctx: Context): String = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "battery: n/a"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val pct = if (level >= 0) "${level * 100 / scale}%" else "?%"
        val t = if (temp > 0) String.format("%.1f °C", temp / 10f) else "-- °C"
        "$pct · $t · ${if (isCharging(i)) "charging" else "on battery"}"
    }.getOrDefault("battery: n/a")

    fun setMode(ctx: Context, m: Mode) {
        init(ctx); mode = m
        val a = app ?: return
        io.launch {
            val ok = runCatching { Settings.Global.putString(a.contentResolver, KEY_OVERRIDE, m.key) }.getOrDefault(false)
            if (!ok) Log.w(TAG, "could not publish $KEY_OVERRIDE=${m.key} (WRITE_SECURE_SETTINGS missing?)")
        }
        applyNow("mode=$m")
    }
    fun setSaverInAudioOnly(ctx: Context, on: Boolean) {
        init(ctx); saverInAudioOnly = on
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("saver_audio_only", on)?.apply()
        applyNow("saverToggle=$on")
    }
    fun setUserVisFpsCap30(ctx: Context, on: Boolean) {
        init(ctx); userVisFpsCap30 = on
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("vis_fps_30", on)?.apply()
    }

    // ---- evaluation ----
    fun desiredProfile(): Profile {
        val visual = visualizerVisible || nowPlayingVisible
        val auto = when {
            !playing -> Profile.IDLE
            !screenOn -> Profile.AUDIO_ONLY
            thermalHot -> Profile.BALANCED
            visual || charging -> Profile.PERF
            else -> Profile.BALANCED
        }
        return when (mode) {
            Mode.AUTO -> auto
            // Contract (ROM-wide): "perf" pins PERF; "save" pins AUDIO_ONLY while playing, IDLE
            // otherwise. Sustained-perf is inert while the window isn't visible, so a pinned PERF
            // with the screen off costs little; a pinned SAVE with the screen on keeps the
            // visualizer alive but fps-capped (visFpsCap) with background work starved + saver on.
            Mode.PERF -> Profile.PERF
            Mode.SAVE -> if (playing) Profile.AUDIO_ONLY else Profile.IDLE
        }
    }

    private fun scheduleEval(reason: String) {
        pendingEval?.let { main.removeCallbacks(it) }
        val r = Runnable { pendingEval = null; applyNow(reason) }
        pendingEval = r
        // 3 s hysteresis — except the very first apply and an explicit "screen just went off while
        // playing" (saver should engage promptly, it's the whole point).
        main.postDelayed(r, DEBOUNCE_MS)
    }

    @Synchronized
    private fun applyNow(reason: String) {
        val a = app ?: return
        val next = desiredProfile()
        val prev = profile
        if (next == prev && lastAppliedAt != 0L) return
        profile = next
        lastAppliedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "profile $prev → $next ($reason) screenOn=$screenOn playing=$playing vis=$visualizerVisible np=$nowPlayingVisible charging=$charging hot=$thermalHot mode=$mode")

        // 1. Publish for the ROM daemon / launcher / systemui.
        io.launch {
            val ok = runCatching {
                Settings.Global.putString(a.contentResolver, KEY_PROFILE, next.key)
                Settings.Global.putString(a.contentResolver, KEY_PROFILE_TS, System.currentTimeMillis().toString())
            }.getOrDefault(false)
            if (!ok) Log.w(TAG, "could not publish $KEY_PROFILE=${next.key} (WRITE_SECURE_SETTINGS missing?)")
        }

        // 2. System battery saver — never in PERF/BALANCED, on in AUDIO_ONLY (if enabled) and IDLE-screen-off.
        val wantSaver = when (next) {
            Profile.AUDIO_ONLY -> saverInAudioOnly || mode == Mode.SAVE
            Profile.IDLE -> !screenOn || mode == Mode.SAVE
            else -> mode == Mode.SAVE && !charging
        }
        io.launch { setSaver(a, wantSaver) }

        // 3. Audio path: re-assert hi-fi on entering the starved profiles (saver must never touch it).
        if (next == Profile.AUDIO_ONLY || next == Profile.IDLE) io.launch {
            runCatching { HioneDirectAudio.ensureAllowListed(a) }
            runCatching { HioneDirectAudio.ensureFullVolumeRange(a) }
        }

        // 4. Root CPU governor (only if the user enabled it in Settings): PERF applies, everything else restores.
        io.launch {
            runCatching {
                if (next == Profile.PERF) CpuPerformance.applyIfEnabled(a) else CpuPerformance.onBackground(a)
            }
        }

        // 5. Stop radios' discovery in the starved profiles.
        if (!allowBackgroundWork) runCatching { com.hione.player.bluetooth.HioneBluetoothController.stopScan() }

        // 6. Thermal polling only while the screen is on.
        main.removeCallbacks(thermalTick)
        if (next == Profile.PERF || next == Profile.BALANCED) main.postDelayed(thermalTick, THERMAL_POLL_MS)

        listeners.forEach { l -> runCatching { l(next) } }
    }

    private fun setSaver(a: Context, on: Boolean) {
        val cr = a.contentResolver
        val cur = runCatching { Settings.Global.getInt(cr, KEY_LOW_POWER, 0) }.getOrDefault(0)
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (on) {
            if (prevLowPower == null) { prevLowPower = cur; p.edit().putInt("prev_low_power", cur).apply() }
            if (cur != 1) writeLowPower(a, 1)
        } else {
            val restore = prevLowPower ?: return
            prevLowPower = null
            p.edit().remove("prev_low_power").apply()
            if (cur != restore) writeLowPower(a, restore)
        }
    }

    private fun restoreLowPower(a: Context, v: Int) {
        prevLowPower = null
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("prev_low_power").apply()
        io.launch { writeLowPower(a, v) }
    }

    private fun writeLowPower(a: Context, v: Int) {
        val ok = runCatching { Settings.Global.putInt(a.contentResolver, KEY_LOW_POWER, v) }.getOrDefault(false)
        Log.i(TAG, "battery saver (low_power) → $v (ok=$ok)")
    }

    // Thermal sensing. The SoC junction sensors (cpu-1-*, cpuss-*, gpu) sit at ~48 °C on this
    // Snapdragon 665 even idling on the charger, so a flat 42 °C gate on them would pin the
    // governor in BALANCED forever. "≥ 42 °C" is applied to the board/skin proxy (xo-therm), with
    // the framework's own throttling signals (thermal status ≥ MODERATE, headroom ≥ 0.95) and a
    // 60 °C junction backstop on top. CPU temperature shown in Settings = hottest cpu-*/cpuss-* zone.
    private const val SKIN_HOT_C = THERMAL_HOT_C
    private const val JUNCTION_HOT_C = 60f
    @Volatile private var zonesScanned = false
    private var skinZone: File? = null
    private val cpuZones = ArrayList<File>()
    private fun scanZones() {
        if (zonesScanned) return
        zonesScanned = true
        runCatching {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.forEach { z ->
                val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrDefault("")
                val temp = File(z, "temp")
                when {
                    type == "xo-therm" || type.contains("skin") || type == "quiet-therm" -> if (skinZone == null) skinZone = temp
                    type.startsWith("cpu") -> cpuZones.add(temp)
                }
            }
        }
    }
    private fun readC(f: File?): Float? {
        val raw = runCatching { f?.takeIf { it.exists() }?.readText()?.trim()?.toFloatOrNull() }.getOrNull() ?: return null
        return if (raw > 1000f) raw / 1000f else raw
    }

    private fun pollThermal() {
        val a = app ?: return
        io.launch {
            scanZones()
            var hot = false
            val cpuMax = cpuZones.mapNotNull { readC(it) }.maxOrNull() ?: 0f
            val skin = readC(skinZone)
            if (skin != null && skin >= SKIN_HOT_C) hot = true
            if (cpuMax >= JUNCTION_HOT_C) hot = true
            var status = 0; var headroom = Float.NaN
            if (Build.VERSION.SDK_INT >= 29) runCatching {
                val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
                status = pm.currentThermalStatus
                if (status >= PowerManager.THERMAL_STATUS_MODERATE) hot = true
                // Headroom only counts once the framework itself reports SOME thermal status — this
                // HAL exposes no skin temperature, and headroom on its own read ≥ 0.95 while the
                // thermal status was NONE (verified live), which pinned the governor in BALANCED.
                if (Build.VERSION.SDK_INT >= 30 && status > PowerManager.THERMAL_STATUS_NONE) {
                    headroom = pm.getThermalHeadroom(10)
                    if (!headroom.isNaN() && headroom >= 0.95f) hot = true
                }
            }
            Log.d(TAG, "thermal cpuMax=${cpuMax}°C skin=${skin ?: -1f}°C status=$status headroom=$headroom → hot=$hot")
            val temp = cpuMax
            if (hot != thermalHot || temp != cpuTempC) main.post {
                cpuTempC = temp
                if (hot != thermalHot) { thermalHot = hot; scheduleEval("thermal hot=$hot cpu=${temp}°C skin=${skin ?: -1f}°C") }
            }
        }
    }

    /** Human readout for the Settings card. */
    fun describe(): String {
        val why = buildList {
            add(if (screenOn) "screen on" else "screen off")
            add(if (playing) "playing" else "not playing")
            if (visualizerVisible) add("visualizer")
            if (nowPlayingVisible) add("now playing")
            if (charging) add("charging")
            if (thermalHot) add("HOT ${"%.0f".format(cpuTempC)}°C")
            if (mode != Mode.AUTO) add("override: ${mode.key}")
        }
        return why.joinToString(" · ")
    }
}
