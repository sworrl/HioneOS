package com.hione.systemui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hione Music publishes Settings.Global `hione_power_profile` = perf | balanced | audio_only | idle.
 * In audio_only / idle SystemUI goes quiet: no idle/infinite animations (clock plasma, sparkles,
 * HUD marquee), 120ms transitions, no recents snapshot refresh, polling no faster than 5s.
 * Restores instantly on perf / balanced. No UI — behaviour only.
 */
object HionePowerProfile {
    const val KEY = "hione_power_profile"
    private val _profile = MutableStateFlow("balanced")
    val profile: StateFlow<String> = _profile
    private var observer: ContentObserver? = null

    val lowPower: Boolean get() = _profile.value == "audio_only" || _profile.value == "idle"
    /** Transition duration honouring the profile. */
    fun ms(normal: Int): Int = if (lowPower) 120 else normal
    /** Poll interval honouring the profile (never faster than 5s when quiet). */
    fun pollMs(normal: Long): Long = if (lowPower) maxOf(normal, 5000L) else normal

    fun read(ctx: Context) {
        _profile.value = runCatching { Settings.Global.getString(ctx.contentResolver, KEY) }.getOrNull()?.trim()?.lowercase()
            ?.takeIf { it.isNotBlank() } ?: "balanced"
    }

    fun observe(ctx: Context) {
        if (observer != null) return
        val app = ctx.applicationContext
        read(app)
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { read(app) }
        }.also { runCatching { app.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY), false, it) } }
    }
}
