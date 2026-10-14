package com.hione.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Idle screen — a real 4-stage pipeline, all durations user-configurable in Settings:
 *   ACTIVE  → full brightness (whatever the user has it set to), the normal screen.
 *   DIMMED  (after [activeSec])                    → brightness steps down, screen stays as-is.
 *   AMBIENT (after activeSec + [dimSec])            → minimal clock+track overlay (AOD-style).
 *   OFF     (after activeSec + dimSec + [ambientSec]) → the actual display sleeps (ScreenOffHelper).
 * Defaults: 120s / 120s / 60s, matching the explicit spec this was built to.
 *
 * All measured from the last real interaction (touch, key, button — Activity.onUserInteraction
 * fires for ANY input system-wide, no per-composable wiring needed). Any interaction at any stage
 * snaps straight back to ACTIVE and restores anything OFF touched (see ScreenOffHelper).
 */
enum class IdleTier { ACTIVE, DIMMED, AMBIENT, OFF }

object IdleController {
    const val BRIGHTNESS_DIMMED = 0.04f
    const val BRIGHTNESS_AMBIENT = 0.02f

    var enabled by mutableStateOf(true)
    var ambientEnabled by mutableStateOf(true)
    var activeSec by mutableStateOf(120)
    var dimSec by mutableStateOf(120)
    var ambientSec by mutableStateOf(60)
    var tier by mutableStateOf(IdleTier.ACTIVE)
        private set

    /** Ref count of on-screen surfaces demanding the display stay lit (see [KeepScreenAwake]). */
    var holdAwake by mutableStateOf(0)
        private set
    fun acquireAwake() { holdAwake++; publishHold() }
    fun releaseAwake() { if (holdAwake > 0) holdAwake--; publishHold() }

    /** App context, kept only so the hold flag can be published from acquire/release. */
    @Volatile private var appCtx: Context? = null

    /**
     * Tell the OS idle ladder (HioneIdleDim in com.hione.systemui) that something on screen is meant
     * to be WATCHED, not touched: tape mode and the fullscreen visualiser. That service owns the
     * real Settings.System brightness now, and this is how it knows to stand down.
     */
    private fun publishHold() {
        val c = appCtx ?: return
        runCatching {
            android.provider.Settings.Global.putInt(c.contentResolver, "hione_idle_hold_awake", if (holdAwake > 0) 1 else 0)
        }
    }

    fun attachContext(ctx: Context) { appCtx = ctx.applicationContext; publishHold() }

    @Volatile private var lastInteraction = android.os.SystemClock.elapsedRealtime()
    @Volatile private var offRequested = false

    /**
     * The REAL display state, from ACTION_SCREEN_ON/OFF (see MainActivity) — not the idle ladder.
     *
     * [tier] alone was never a safe gate: it only advances from [IdleWatcher]'s in-composition
     * once-a-second tick, and a [KeepScreenAwake] hold pins it to ACTIVE and resets the inactivity
     * clock every second. So with the panel physically off, `screenActive` stayed TRUE and every
     * per-frame loop that checks it (wavy scrubber, tape reels, transport deck, liked-heart) kept
     * running at 30-60 Hz against a display nobody could see. Measured: the player's MAIN THREAD
     * pinned at 67% with the screen dozing and the activity paused, which is what made the whole
     * device feel laggy while music played.
     */
    @Volatile private var displayOn = true

    /** Wire from ACTION_SCREEN_ON/OFF, and seed from DisplayManager so a missed broadcast can't strand it. */
    fun setDisplayOn(on: Boolean) {
        displayOn = on
        if (on) lastInteraction = android.os.SystemClock.elapsedRealtime()
    }

    /** Seed [displayOn] from the platform (call at startup / resume). */
    fun syncDisplayState(ctx: Context) {
        displayOn = runCatching {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            dm.displays.any { it.state == android.view.Display.STATE_ON }
        }.getOrDefault(true)
    }

    fun loadPrefs(ctx: Context) {
        attachContext(ctx)      // so acquire/releaseAwake can publish the hold to the OS ladder
        enabled = PlayerPreferences.loadIdleDimEnabled(ctx)
        ambientEnabled = PlayerPreferences.loadAmbientEnabled(ctx)
        activeSec = PlayerPreferences.loadIdleActiveSec(ctx)
        dimSec = PlayerPreferences.loadIdleDimSec(ctx)
        ambientSec = PlayerPreferences.loadIdleAmbientSec(ctx)
    }

    /** Any real interaction: snap back to ACTIVE and, if OFF had shrunk the system screen timeout
     *  to force an early sleep, put it back — self-heals even if the SCREEN_OFF broadcast path
     *  (the normal restore trigger, see MainActivity) was somehow missed. */
    fun poke(ctx: Context? = null) {
        lastInteraction = android.os.SystemClock.elapsedRealtime()
        if (tier != IdleTier.ACTIVE) tier = IdleTier.ACTIVE
        if (offRequested && ctx != null) {
            ScreenOffHelper.restore(ctx)
            offRequested = false
        }
    }

    /** Called once a second by [IdleWatcher]. */
    fun tick(ctx: Context) {
        // A hold beats the timer entirely: tape mode and the fullscreen visualiser are things you
        // sit and WATCH without touching the device, so the idle ladder (dim -> ambient -> screen
        // off) must not run while one is on screen.
        if (holdAwake > 0) {
            if (tier != IdleTier.ACTIVE) tier = IdleTier.ACTIVE
            lastInteraction = android.os.SystemClock.elapsedRealtime()
            if (offRequested) { ScreenOffHelper.restore(ctx); offRequested = false }
            return
        }
        if (!enabled) { if (tier != IdleTier.ACTIVE) tier = IdleTier.ACTIVE; return }
        val idleSec = (android.os.SystemClock.elapsedRealtime() - lastInteraction) / 1000
        val dimAt = activeSec.toLong()
        val ambientAt = dimAt + dimSec
        val offAt = ambientAt + (if (ambientEnabled) ambientSec else 0)
        val newTier = when {
            idleSec >= offAt -> IdleTier.OFF
            ambientEnabled && idleSec >= ambientAt -> IdleTier.AMBIENT
            idleSec >= dimAt -> IdleTier.DIMMED
            else -> IdleTier.ACTIVE
        }
        if (newTier != tier) {
            tier = newTier
            if (newTier == IdleTier.OFF && !offRequested) {
                offRequested = true
                ScreenOffHelper.requestScreenOff(ctx)
            }
        }
    }

    // The single switch every GPU/CPU-continuous piece of UI should check before doing work
    // nobody's watching — dimmed-in-place still counts as "not active" here since the point is
    // burning battery on invisible-or-barely-visible frames, not literally screen-off.
    val screenActive: Boolean get() = displayOn && tier == IdleTier.ACTIVE

    // Coarser cutoff for things that ARE still worth keeping live through DIMMED (the real screen
    // stays fully visible there, e.g. a seek-bar position) but genuinely pointless once the screen
    // is either replaced by the ambient overlay or physically dark — no reason to keep polling
    // player position at sub-second cadence against a display nobody can see at all.
    val visuallyIdle: Boolean get() = !displayOn || tier == IdleTier.AMBIENT || tier == IdleTier.OFF
}

/**
 * There is no public Android API for a normal app to just turn the display off — that's
 * deliberate OS policy (stops a malicious app DoS-ing the screen), not an oversight, and the real
 * hidden system call (PowerManager.goToSleep, DEVICE_POWER permission) is signature-protected and
 * out of reach even with this being a sideloaded app on a custom ROM (matches the same wall hit
 * building the pocket-lock feature earlier). What DOES work, and is exactly what real "quick
 * screen off" utility apps ship: briefly shrink the SYSTEM's own screen-off timeout so its own
 * power manager blanks the display almost immediately on its own next check, then restore the
 * user's real value. Requires WRITE_SETTINGS ("Modify system settings"), a special-access
 * permission grantable via Settings — same family as the MANAGE_EXTERNAL_STORAGE flow already in
 * this app, never silently granted.
 */
object ScreenOffHelper {
    private const val SHRUNK_TIMEOUT_MS = 1000

    fun hasPermission(ctx: Context): Boolean =
        runCatching { android.provider.Settings.System.canWrite(ctx) }.getOrDefault(false)

    fun requestPermission(ctx: Context) {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
            android.net.Uri.parse("package:${ctx.packageName}")
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    fun requestScreenOff(ctx: Context) {
        if (!hasPermission(ctx)) return
        if (PlayerPreferences.loadStashedScreenTimeout(ctx) != -1) return   // already shrunk, don't stomp the real stashed value
        runCatching {
            val current = android.provider.Settings.System.getInt(
                ctx.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 60_000
            )
            PlayerPreferences.saveStashedScreenTimeout(ctx, current)
            android.provider.Settings.System.putInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, SHRUNK_TIMEOUT_MS)
        }
    }

    /** Put the user's real screen-off timeout back. Safe to call even when nothing is stashed. */
    fun restore(ctx: Context) {
        val stashed = PlayerPreferences.loadStashedScreenTimeout(ctx)
        if (stashed == -1) return
        runCatching {
            android.provider.Settings.System.putInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, stashed)
        }
        PlayerPreferences.clearStashedScreenTimeout(ctx)
    }
}

@Composable
fun IdleWatcher() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            IdleController.tick(ctx)
            delay(1000)
        }
    }
}

/** Applies (or clears) the dim/ambient brightness override on the real Activity window. OFF isn't
 *  handled here — by the time tier reaches OFF the display is on its way down via ScreenOffHelper
 *  regardless of any brightness value this Window sets. */
fun applyIdleBrightness(window: android.view.Window, tier: IdleTier) {
    // NO-OP on purpose, kept so every call site does not have to change.
    //
    // This used to set window.attributes.screenBrightness, a per-WINDOW override. Justin asked for
    // idle dim on the SYSTEM brightness, and having two owners is worse than having one: the OS
    // ladder (HioneIdleDim, com.hione.systemui) stood down whenever Hione Music was in front, so
    // inside the app the only dimming was this window override, and outside it the OS ladder never
    // saw the app's holds. One owner now. HioneIdleDim writes Settings.System.SCREEN_BRIGHTNESS
    // everywhere and reads IdleController's hold flag (Settings.Global hione_idle_hold_awake) to
    // know when to leave the panel alone. The AMBIENT overlay UI below is unaffected.
    val attrs = window.attributes
    if (attrs.screenBrightness != android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
        attrs.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
    }
}

/**
 * Full-screen ambient takeover: big clock, track info if something's playing, nothing else.
 * Deliberately minimal — every extra pixel lit is battery the ambient tier exists to save. Tap
 * anywhere wakes back to the real screen underneath (which never stopped being composed).
 */
@Composable
fun AmbientOverlay(player: ExoPlayer, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            player.currentMediaItem?.mediaMetadata?.let { md ->
                title = md.title?.toString().orEmpty()
                artist = md.artist?.toString().orEmpty()
            }
            isPlaying = player.isPlaying
            delay(1000)
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { IdleController.poke(ctx) }) },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                timeFmt.format(java.util.Date(now)),
                color = Color(0xFF3A4A4C),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont
            )
            if (title.isNotBlank()) {
                Box(Modifier.padding(top = 24.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MusicNote, null,
                            tint = if (isPlaying) Mascot.color(0xFF2A3A3C) else Color(0xFF1E2626),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(title, color = Mascot.color(0xFF2A3A3C), fontSize = 14.sp, maxLines = 1)
                        if (artist.isNotBlank()) Text(artist, color = Color(0xFF1E2626), fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Keeps the panel lit for as long as [active] and this composable are on screen: sets the window's
 * FLAG_KEEP_SCREEN_ON (so Android's own display timeout cannot fire) AND takes an [IdleController]
 * hold (so OUR idle ladder cannot dim or blank it either). Both are released on dispose, so normal
 * timeout behaviour resumes the moment the surface goes away.
 */
@Composable
fun KeepScreenAwake(active: Boolean = true) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(active, view) {
        if (!active) {
            onDispose { }
        } else {
            view.keepScreenOn = true
            IdleController.acquireAwake()
            IdleController.poke()
            onDispose {
                view.keepScreenOn = false
                IdleController.releaseAwake()
            }
        }
    }
}
