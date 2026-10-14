package com.hione.systemui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Read-only view of the BPM game's unlocks (Settings.Global `hione_unlocks`, written only by the
 * launcher's com.hione.launcher.bpm.HioneUnlocks):
 *
 *     {"<unlock_id>": {"at": <epochMillis>, "src": "bpm", "off": true}}
 *
 * An entry counts as ENABLED when it is present and "off" is not true; a non-object value is an
 * older writer and counts as on. The ids are copied verbatim from the launcher and must never be
 * renamed on either side. Fails closed: a missing or unreadable value enables nothing.
 */
object HioneUnlocksWatch {
    private const val KEY = "hione_unlocks"
    /** Copied from HioneSecrets.NEGI_BATTERY in the launcher. */
    private const val NEGI_BATTERY = "secret.os.battery.negi"

    private val _negiBattery = MutableStateFlow(false)
    val negiBattery: StateFlow<Boolean> = _negiBattery
    private var observer: ContentObserver? = null

    fun read(ctx: Context) {
        _negiBattery.value = runCatching {
            val raw = Settings.Global.getString(ctx.contentResolver, KEY)
            if (raw.isNullOrBlank()) false else {
                val o = JSONObject(raw)
                o.has(NEGI_BATTERY) && o.optJSONObject(NEGI_BATTERY)?.optBoolean("off", false) != true
            }
        }.getOrDefault(false)
    }

    /** Idempotent. The game can grant or switch an entry while the shade is alive. */
    fun observe(ctx: Context) {
        if (observer != null) return
        val app = ctx.applicationContext
        read(app)
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { read(app) }
        }.also { runCatching { app.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY), false, it) } }
    }
}

/**
 * Beat of whatever Hione Music is playing, for things that pulse on it (the BPM game tile).
 * Hione Music publishes `hione_is_playing` and `hione_beat_interval_ms` (0 = tempo unknown);
 * `hione_now_playing_bpm` is the fallback when there is a tempo but no interval. The values are
 * read once per change here; consumers animate from [intervalMs] in their draw phase.
 */
object HioneBeatWatch {
    private val KEYS = listOf("hione_is_playing", "hione_beat_interval_ms", "hione_now_playing_bpm")

    private val _intervalMs = MutableStateFlow(0)
    /** Beat period in ms while music plays with a known tempo, else 0. */
    val intervalMs: StateFlow<Int> = _intervalMs
    private var observer: ContentObserver? = null

    fun read(ctx: Context) {
        val cr = ctx.contentResolver
        val playing = runCatching {
            Settings.Global.getString(cr, "hione_is_playing")?.trim().let { it == "1" || it == "true" }
        }.getOrDefault(false)
        if (!playing) { _intervalMs.value = 0; return }
        val interval = runCatching { Settings.Global.getString(cr, "hione_beat_interval_ms")?.trim()?.toFloat()?.toInt() }.getOrNull() ?: 0
        val ms = if (interval > 0) interval else {
            val bpm = runCatching { Settings.Global.getString(cr, "hione_now_playing_bpm")?.trim()?.toFloat() }.getOrNull() ?: 0f
            if (bpm > 0f) (60000f / bpm).toInt() else 0
        }
        // 30..300 BPM. Anything outside that is a bad reading, and pulsing at it would look broken.
        _intervalMs.value = if (ms in 200..2000) ms else 0
    }

    fun observe(ctx: Context) {
        if (observer != null) return
        val app = ctx.applicationContext
        read(app)
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { read(app) }
        }.also { obs ->
            runCatching { KEYS.forEach { app.contentResolver.registerContentObserver(Settings.Global.getUriFor(it), false, obs) } }
        }
    }
}
