package com.hione.player

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-wide "color of what's playing". One process-global palette extracted from the CURRENT
 * track's art (not whatever happened to be in the memory cache at first composition — that was
 * why the mini bar so often stayed default teal while the art was clearly orange). Every chrome
 * surface reads [colors] and animates toward the new palette on track change, so the whole app
 * shifts with the album instead of only the Now Playing card.
 *
 * Fallback is always the Hione teal identity palette (no art → nothing changes visibly).
 */
object HioneArtTheme {
    /** Page backdrop ceiling. Ground #121318 sits at ~0.009, so this still leaves room to tint. */
    private const val GROUND_MAX_LUM = 0.030f
    /** Raised card/glass surfaces sit above the ground but still under light body text. */
    private const val SURFACE_MAX_LUM = 0.075f
    /** Accents are read as text, so they get the body-text floor, not the large-text one. */
    private const val ACCENT_MIN_CONTRAST = 4.5f

    var palette by mutableStateOf(ArtPalette())
        private set
    var trackId by mutableStateOf(-1L)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var appCtx: Context? = null

    fun update(ctx: Context, track: Track?) {
        appCtx = ctx.applicationContext
        if (track == null) { trackId = -1L; palette = ArtPalette(); return }
        if (track.id == trackId) return
        trackId = track.id
        job?.cancel()
        val app = ctx.applicationContext
        job = scope.launch {
            val bm = AlbumArtCache.get(track.id) ?: loadArtThumb(app, track.id, track.path)
            val p = extractArtPalette(bm)
            withContext(Dispatchers.Main) { if (trackId == track.id) { palette = p; HioneAccentPublisher.onPalette(app, p) } }
        }
    }

    /** Push a palette computed elsewhere (Now Playing already has the hi-res art decoded, the HUD
     *  publisher already has the thumb). Also what lets the OS accents update when art loads late. */
    fun push(id: Long, p: ArtPalette, ctx: Context? = null) {
        val changed = id != trackId || p != palette
        trackId = id; palette = p
        val app = (ctx ?: appCtx)?.applicationContext
        if (changed && app != null) HioneAccentPublisher.onPalette(app, p)
    }

    /** Resolve + publish the palette for a track id even when no UI is up (background playback,
     *  PLAY_RANDOM from the launcher). Cheap: in-memory index lookup + the cached thumb. */
    fun updateForTrackId(ctx: Context, trackId: Long) {
        if (HioneDbg.off(ctx, "arttheme")) return
        val app = ctx.applicationContext
        if (trackId == this.trackId) return
        if (!HionePowerGovernor.allowBackgroundWork) return   // deferred until the screen is back
        scope.launch {
            val t = FastLibraryStore.loadSync(app)?.firstOrNull { it.id == trackId } ?: return@launch
            withContext(Dispatchers.Main) { update(app, t) }
        }
    }

    /** Chrome-ready, contrast-safe derivations of the raw palette. */
    class Colors(
        /** Vibrant accent (tabs, pills, highlights). Lifted if the art's vibrant is too dark. */
        val accent: Color,
        /** Secondary accent (tape icon, secondary highlights). */
        val accent2: Color,
        /** Deep page backdrop tinted by the art's deep tone. */
        val ground: Color,
        /** Slightly raised surface tint (cards, header glass). */
        val surface: Color,
        /** Text color guaranteed readable on [accent]. */
        val onAccent: Color,
    )

    /** WCAG relative contrast between two opaque colors. 4.5 is the readable floor for body text. */
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun towardWhite(c: Color, t: Float): Color = Color(
        red = c.red + (1f - c.red) * t,
        green = c.green + (1f - c.green) * t,
        blue = c.blue + (1f - c.blue) * t,
        alpha = 1f
    )

    /** Darken a color until its luminance is at or under [maxLum], keeping the hue. */
    private fun capLuminance(c: Color, maxLum: Float): Color {
        var lo = 0f; var hi = 1f
        if (c.luminance() <= maxLum) return c
        repeat(12) {
            val mid = (lo + hi) / 2f
            if (Color(c.red * mid, c.green * mid, c.blue * mid).luminance() > maxLum) hi = mid else lo = mid
        }
        return Color(c.red * lo, c.green * lo, c.blue * lo, 1f)
    }

    /**
     * Blend [c] toward white until it actually CLEARS [minContrast] against [over].
     *
     * The old version lifted to an absolute luminance floor (0.22), which says nothing about
     * readability: the ground is art-tinted too, so a bright album pushed the ground up under an
     * accent that had already "passed" and the text went unreadable. Contrast is measured against
     * the ground we are really drawing on.
     */
    private fun liftFor(c: Color, over: Color, minContrast: Float): Color {
        if (contrast(c, over) >= minContrast) return c
        var lo = 0f; var hi = 1f
        repeat(12) {
            val mid = (lo + hi) / 2f
            if (contrast(towardWhite(c, mid), over) >= minContrast) hi = mid else lo = mid
        }
        return towardWhite(c, hi)
    }

    private fun mix(a: Color, b: Color, t: Float): Color = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f
    )

    fun derive(p: ArtPalette): Colors {
        // Ground first: every static text color in the app (Muted, HioneTextSecondary, the accent
        // pills) is drawn over it, so it is the thing that has to stay dark. A bright album used to
        // drag it up and take the whole UI's contrast down with it. Hard luminance ceilings keep the
        // art tint visible without ever eating legibility.
        val deep = p.color2
        val deepDark = Color(deep.red * 0.28f, deep.green * 0.28f, deep.blue * 0.28f)
        val ground = capLuminance(mix(Ground, deepDark, 0.55f), GROUND_MAX_LUM)
        val surface = capLuminance(
            mix(Surface1, Color(deep.red * 0.42f, deep.green * 0.42f, deep.blue * 0.42f), 0.5f),
            SURFACE_MAX_LUM
        )
        // Accents carry text (tabs, pills, values), so they are lifted against the ground they land
        // on until they actually clear the body-text floor, not to a fixed luminance.
        val accent = liftFor(p.color1, ground, ACCENT_MIN_CONTRAST)
        val accent2 = liftFor(p.color3, ground, ACCENT_MIN_CONTRAST)
        val onAccent = if (accent.luminance() > 0.45f) Mascot.color(0xFF07201F) else Color.White
        return Colors(accent, accent2, ground, surface, onAccent)
    }

    /** Animated, composition-aware colors — call once per screen/section and read fields. */
    @Composable
    fun colors(durationMs: Int = 650): Colors {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val isUnlocked = HioneUnlocksReader.isUnlocked(ctx, HioneUnlocksReader.PLAYER_DYNAMIC_COLOR)
        val p = palette
        val target = androidx.compose.runtime.remember(p, isUnlocked) {
            val effectiveP = if (!isUnlocked) com.hione.player.ui.desaturatePalette(p, 0.08f) else p
            derive(effectiveP)
        }
        val accent by animateColorAsState(target.accent, tween(durationMs), label = "artAccent")
        val accent2 by animateColorAsState(target.accent2, tween(durationMs), label = "artAccent2")
        val ground by animateColorAsState(target.ground, tween(durationMs + 250), label = "artGround")
        val surface by animateColorAsState(target.surface, tween(durationMs + 250), label = "artSurface")
        val onAccent by animateColorAsState(target.onAccent, tween(durationMs), label = "artOnAccent")
        return Colors(accent, accent2, ground, surface, onAccent)
    }
}
