package com.hione.systemui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Now-playing album accent published by Hione Music as Settings.Global
 * `hione_np_accent` / `hione_np_accent2` (ARGB ints as decimal strings, 0 = none) +
 * `hione_np_accent_ts`. SystemUI bleeds it SUBTLY into the OS chrome:
 *   pill glow + back capsule ≈25% into Hione teal · shade header underline / active tile /
 *   slider thumb ≈30% · Hione Music recents card halo · track HUD glow (100%, it's the art).
 * Every consumer animates changes over 400ms and falls back to pure teal when the value is 0.
 */
object HioneAccent {
    const val KEY_ACCENT = "hione_np_accent"
    const val KEY_ACCENT2 = "hione_np_accent2"
    const val KEY_TS = "hione_np_accent_ts"
    val TEAL get() = Mascot.argb(0xFFFFAB2E.toInt())
    val TEAL_BRIGHT get() = Mascot.argb(0xFFFFC56B.toInt())

    private val _accent = MutableStateFlow(0)
    private val _accent2 = MutableStateFlow(0)
    /** ARGB, 0 = none (use teal). */
    val accent: StateFlow<Int> = _accent
    val accent2: StateFlow<Int> = _accent2
    private var observer: ContentObserver? = null

    fun read(ctx: Context) {
        _accent.value = readGlobal(ctx, KEY_ACCENT)
        _accent2.value = readGlobal(ctx, KEY_ACCENT2)
    }

    private fun readGlobal(ctx: Context, key: String): Int = runCatching {
        val s = Settings.Global.getString(ctx.contentResolver, key) ?: return 0
        val v = s.trim().toLongOrNull() ?: return 0
        v.toInt()
    }.getOrDefault(0)

    /** Push values that arrived in a TRACK_CHANGED broadcast (extras `accent` / `accent2`). */
    fun push(a: Int, a2: Int) { _accent.value = a; _accent2.value = a2 }

    /** Start observing the Globals (idempotent). */
    fun observe(ctx: Context) {
        if (observer != null) return
        val app = ctx.applicationContext
        read(app)
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { read(app) }
        }.also { obs ->
            runCatching {
                app.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY_ACCENT), false, obs)
                app.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY_ACCENT2), false, obs)
                app.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY_TS), false, obs)
            }
        }
    }

    /** Mix [accentArgb] into [base] by [fraction] (0 = base). accent==0 → base unchanged. */
    fun mix(base: Int, accentArgb: Int, fraction: Float): Int {
        if (accentArgb == 0 || fraction <= 0f) return base
        val f = fraction.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((base shr shift) and 0xFF) * (1f - f) + ((accentArgb shr shift) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (((base ushr 24) and 0xFF) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Teal tinted toward the accent — the "OS accent" every surface uses. */
    fun tealTinted(accentArgb: Int, fraction: Float): Int = mix(TEAL, accentArgb, fraction)
    fun tealBrightTinted(accentArgb: Int, fraction: Float): Int = mix(TEAL_BRIGHT, accentArgb, fraction)
}
