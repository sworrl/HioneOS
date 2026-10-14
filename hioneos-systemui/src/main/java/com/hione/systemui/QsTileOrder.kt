package com.hione.systemui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Static identity of a quick-settings tile: what the editor shows, independent of live state. */
data class QsTileSpec(val id: String, val name: String, val short: String, val icon: ImageVector)

/**
 * Which tiles the shade shows, and in what order. The first [QUICK_COUNT] are the quick row of the
 * collapsed shade; the full grid shows them all in the same order.
 *
 * STORAGE: Settings.Secure `hione_qs_tiles`, a comma-separated id list, the same shape as AOSP's
 * `sysui_qs_tiles`. Chosen over SharedPreferences because:
 *   - it is the HioneOS idiom: every other knob is a settings key you can read or set from a shell
 *     (`settings put secure hione_qs_tiles wifi,bluetooth,rotation,...`) and it applies live,
 *     because this object observes the key;
 *   - it survives `pm clear` and a reinstall of this APK during development;
 *   - Hione Settings (another process) can read or reset it without an IPC surface of ours.
 * Secure rather than Global because tile layout is a per-user preference, as in AOSP. Writing it
 * needs WRITE_SECURE_SETTINGS, which this platform-signed app already holds (the boot receiver
 * writes Secure keys the same way); the root shell is the fallback, as for the Global toggles.
 *
 * `hione_qs_tiles_known` records every id the user has seen in the editor. A tile added by a later
 * build is not in it, so it is appended to the active list instead of silently landing in
 * "available" where nobody would find it.
 */
object QsTileOrder {
    private const val TAG = "QsTileOrder"
    const val KEY = "hione_qs_tiles"
    const val KEY_KNOWN = "hione_qs_tiles_known"
    /** Tiles in the collapsed shade's quick row. */
    const val QUICK_COUNT = 4

    /** Every tile the shade can build. If a builder makes an id that is not here, it is unreachable. */
    val CATALOG: List<QsTileSpec> = listOf(
        QsTileSpec("wifi", "Internet", "WI-FI", Icons.Default.Wifi),
        QsTileSpec("bluetooth", "Bluetooth", "BLUETOOTH", Icons.Default.Bluetooth),
        QsTileSpec("ingest", "Network sync", "SYNC", Icons.Default.CloudSync),
        QsTileSpec("wireless_adb", "Wireless ADB", "ADB", Icons.Default.Cable),
        QsTileSpec("rotation", "Auto-rotate", "ROTATE", Icons.Default.ScreenRotation),
        QsTileSpec("airplane_mode", "Airplane Mode", "AIRPLANE", Icons.Default.Flight),
        QsTileSpec("cs43198_filter", "DAC Filter", "FILTER", Icons.Default.GraphicEq),
        QsTileSpec("cs43198_gain", "PO Gain", "GAIN", Icons.AutoMirrored.Filled.VolumeUp),
        QsTileSpec("audio_turbo", "High power", "POWER", Icons.Default.FlashOn),
        QsTileSpec("dre_mode", "DRE", "DRE", Icons.Default.Tune),
        QsTileSpec("pause_unplug", "Pause on unplug", "UNPLUG", Icons.Default.HeadsetOff),
        QsTileSpec("track_hud", "Track HUD", "HUD", Icons.Default.MusicNote),
        QsTileSpec("dac_badge", "DAC badge", "BADGE", Icons.Default.GraphicEq),
        QsTileSpec("idle_dim", "Idle Dim", "DIM", Icons.Default.BrightnessMedium),
        QsTileSpec("bpm_game", "BPM Game", "BPM", Icons.Default.Favorite)
    )
    private val byId = CATALOG.associateBy { it.id }
    fun spec(id: String): QsTileSpec? = byId[id]

    /** In the catalog but not on by default: the user adds these from the editor. */
    private val OPT_IN = setOf("bpm_game")

    /** Every tile id, active or not. The editor's "available" section is this minus the active list. */
    val ALL_IDS: List<String> = CATALOG.map { it.id }

    /** Default: everything except [OPT_IN], and the quick row the shade had before the editor existed. */
    val DEFAULT: List<String> = ALL_IDS.filter { it !in OPT_IN }

    private val _active = MutableStateFlow(DEFAULT)
    /** Active tile ids, in display order. */
    val active: StateFlow<List<String>> = _active
    private var observer: ContentObserver? = null

    fun read(ctx: Context) {
        val cr = ctx.contentResolver
        val raw = runCatching { Settings.Secure.getString(cr, KEY) }.getOrNull()
        if (raw.isNullOrBlank()) { _active.value = DEFAULT; return }
        val known = runCatching { Settings.Secure.getString(cr, KEY_KNOWN) }.getOrNull()
            ?.split(',')?.map { it.trim() }?.toSet().orEmpty()
        val parsed = parse(raw)
        // New in this build and never seen in the editor: show it rather than hide it (opt-in
        // tiles excepted, they wait in "available").
        val fresh = DEFAULT.filter { it !in known && it !in parsed }
        _active.value = (parsed + fresh).ifEmpty { DEFAULT }
    }

    private fun parse(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it in byId }.distinct()

    /** Start observing the key (idempotent), so a shell `settings put` applies live. */
    fun observe(ctx: Context) {
        if (observer != null) return
        val app = ctx.applicationContext
        read(app)
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { read(app) }
        }.also { obs ->
            runCatching { app.contentResolver.registerContentObserver(Settings.Secure.getUriFor(KEY), false, obs) }
        }
    }

    /** Persist [ids] as the active order. The in-memory value updates first so the UI never waits. */
    fun save(ctx: Context, ids: List<String>) {
        val clean = ids.filter { it in byId }.distinct()
        if (clean.isEmpty()) return
        _active.value = clean
        writeAsync(ctx, clean.joinToString(","))
    }

    /** Back to [DEFAULT]. */
    fun reset(ctx: Context) {
        _active.value = DEFAULT
        writeAsync(ctx, DEFAULT.joinToString(","))
    }

    private fun writeAsync(ctx: Context, value: String) {
        val app = ctx.applicationContext
        val known = ALL_IDS.joinToString(",")
        // Settings writes are binder calls into system_server; keep them off the UI thread.
        Thread {
            val ok = runCatching {
                Settings.Secure.putString(app.contentResolver, KEY_KNOWN, known)
                Settings.Secure.putString(app.contentResolver, KEY, value)
            }.getOrDefault(false)
            if (!ok) {
                Log.w(TAG, "Settings.Secure write refused; falling back to the root shell")
                RootShell.execFast("settings put secure $KEY_KNOWN $known; settings put secure $KEY $value")
            }
        }.start()
    }
}
