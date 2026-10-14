package com.hione.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.os.Bundle

/**
 * HioneOS "KDE compositing" window-transition controller.
 *
 * A palette of KDE-desktop-style window effects (dissolve / zoom / glide / burn /
 * whirl / slide / curtain), each backed by an enter+exit res/anim pair. Transitions
 * are LINKED TO EVENTS (a lock event drops a curtain, opening an app zooms, a modal
 * dissolves, …) and VARIED so repeated actions don't feel mechanical — each event
 * class rotates through a small weighted set of effects.
 *
 * This is the testbed the user asked for: everything is wired so we can flash it and
 * see which effects read well on the device, then prune. Apply at an activity launch
 * via [optionsFor] (ActivityOptions bundle passed to startActivity) or after the fact
 * via [applyTo] (overridePendingTransition).
 */
enum class KdeEffect(val enter: Int, val exit: Int) {
    DISSOLVE(R.anim.kde_dissolve_enter, R.anim.kde_dissolve_exit),
    ZOOM(R.anim.kde_zoom_enter, R.anim.kde_zoom_exit),
    GLIDE(R.anim.kde_glide_enter, R.anim.kde_glide_exit),
    BURN(R.anim.kde_burn_enter, R.anim.kde_burn_exit),
    WHIRL(R.anim.kde_whirl_enter, R.anim.kde_whirl_exit),
    SLIDE(R.anim.kde_slide_right_enter, R.anim.kde_slide_right_exit),
    CURTAIN(R.anim.lockscreen_curtain_down, R.anim.lockscreen_curtain_up);
}

/** What triggered the transition — each event maps to a weighted palette of effects. */
enum class HioneTransitionEvent { LOCK, UNLOCK, APP_OPEN, APP_CLOSE, MODAL, SETTINGS, NAV }

object HioneCompositing {

    // Per-event weighted palettes. First entry is the "signature" effect for that
    // event; the rest add variety. Keep lock/unlock on the curtain (system-shade feel).
    private val palettes: Map<HioneTransitionEvent, List<KdeEffect>> = mapOf(
        HioneTransitionEvent.LOCK    to listOf(KdeEffect.CURTAIN),
        HioneTransitionEvent.UNLOCK  to listOf(KdeEffect.CURTAIN),
        HioneTransitionEvent.APP_OPEN to listOf(
            KdeEffect.ZOOM, KdeEffect.DISSOLVE, KdeEffect.GLIDE, KdeEffect.BURN, KdeEffect.WHIRL
        ),
        HioneTransitionEvent.APP_CLOSE to listOf(KdeEffect.DISSOLVE, KdeEffect.ZOOM, KdeEffect.GLIDE),
        HioneTransitionEvent.MODAL    to listOf(KdeEffect.DISSOLVE, KdeEffect.GLIDE),
        HioneTransitionEvent.SETTINGS to listOf(KdeEffect.SLIDE, KdeEffect.GLIDE),
        HioneTransitionEvent.NAV      to listOf(KdeEffect.SLIDE, KdeEffect.DISSOLVE),
    )

    // Rotation cursor per event so repeated triggers cycle through the palette
    // (deterministic + varied, and no Math.random which is unavailable in some sandboxes).
    private val cursor = HashMap<HioneTransitionEvent, Int>()

    const val PREFS = "hione_compositing"
    // Per-event pref value: a KdeEffect name to pin one effect, or "VARIED" to cycle
    // the palette. The KDE menu writes these; this reader honors them.
    private var prefs: android.content.SharedPreferences? = null
    fun attach(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
    private fun choiceFor(event: HioneTransitionEvent): String =
        prefs?.getString(event.name, "VARIED") ?: "VARIED"
    fun setChoice(ctx: Context, event: HioneTransitionEvent, value: String) {
        attach(ctx); prefs?.edit()?.putString(event.name, value)?.apply()
    }

    /** Pick the effect for [event]: the user's pinned choice, else cycle the palette. */
    @Synchronized
    fun nextEffect(event: HioneTransitionEvent): KdeEffect {
        val choice = choiceFor(event)
        if (choice != "VARIED") {
            runCatching { return KdeEffect.valueOf(choice) }
        }
        val palette = palettes[event] ?: listOf(KdeEffect.DISSOLVE)
        val i = (cursor[event] ?: 0) % palette.size
        cursor[event] = i + 1
        return palette[i]
    }

    /** All effects, for the menu's picker/preview grid. */
    fun allEffects(): List<KdeEffect> = KdeEffect.entries
    fun allEvents(): List<HioneTransitionEvent> = HioneTransitionEvent.entries

    /** ActivityOptions bundle for launching an activity with the event's next effect. */
    fun optionsFor(ctx: Context, event: HioneTransitionEvent): Bundle {
        val fx = nextEffect(event)
        return ActivityOptions.makeCustomAnimation(ctx, fx.enter, fx.exit).toBundle()
    }

    /**
     * Pixel "open from icon": when the launch started from an icon ([source] = its window
     * bounds, taken from [com.hione.launcher.ui.HioneLaunchSource]) and the palette picked the
     * ZOOM or DISSOLVE effect, scale the new window up out of that icon instead; the other KDE
     * effects (glide / burn / whirl / slide) keep their own choreography.
     */
    fun optionsForLaunch(ctx: Context, event: HioneTransitionEvent, source: androidx.compose.ui.geometry.Rect?): Bundle {
        val fx = nextEffect(event)
        if (source != null && (fx == KdeEffect.ZOOM || fx == KdeEffect.DISSOLVE)) {
            val decor = (ctx as? Activity)?.window?.decorView
            if (decor != null) {
                return try {
                    ActivityOptions.makeScaleUpAnimation(
                        decor,
                        source.left.toInt(), source.top.toInt(),
                        source.width.toInt().coerceAtLeast(1), source.height.toInt().coerceAtLeast(1)
                    ).toBundle()
                } catch (_: Throwable) {
                    ActivityOptions.makeCustomAnimation(ctx, fx.enter, fx.exit).toBundle()
                }
            }
        }
        return ActivityOptions.makeCustomAnimation(ctx, fx.enter, fx.exit).toBundle()
    }

    /** Force a specific effect (e.g. the lock curtain) rather than the varied palette. */
    fun optionsFor(ctx: Context, effect: KdeEffect): Bundle =
        ActivityOptions.makeCustomAnimation(ctx, effect.enter, effect.exit).toBundle()

    /** Apply the event's next effect to an in-progress transition (post startActivity/finish). */
    fun applyTo(activity: Activity, event: HioneTransitionEvent) {
        val fx = nextEffect(event)
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(fx.enter, fx.exit)
    }
}
