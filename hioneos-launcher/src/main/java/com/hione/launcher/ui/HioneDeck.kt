package com.hione.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hione.launcher.Mascot
import com.hione.launcher.HioneCyan
import com.hione.launcher.haptics.HioneHaptics
import com.hione.launcher.holiday.FrameTarget
import com.hione.launcher.holiday.WallpaperFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * HioneDeck: the home screen's big top card (hearts clock, date, status chips, weather tile,
 * volume / battery / CONTROL / BPM row) as a pull-up sheet, so the whole wallpaper can show.
 *
 * Four layouts, set in HioneSettings > Home screen (Settings.Global [HioneDeck.KEY_MODE]):
 *  - [HioneDeckMode.TOP]      the card under the status bar, as it always was (default).
 *  - [HioneDeckMode.PULL_UP]  hidden. A grab handle sits just above the dock: tap it, or drag up
 *                             from it, and the card slides up as a sheet over the wallpaper. Drag
 *                             it down, tap outside it, or press back to put it away.
 *  - [HioneDeckMode.AUTO_POP] as PULL_UP, and it also pops up by itself for a few seconds on
 *                             unlock, a new track, charger in, a weather alert or low battery.
 *  - [HioneDeckMode.PINNED]   always shown, in the sheet position just above the dock.
 *
 * Gestures: swipe-up anywhere on the workspace and on the dock already opens the app drawer, so
 * the sheet does not take a swipe-up there. It opens only from its own handle (tap, or a drag
 * that STARTS on the handle). While the sheet is up, a swipe up outside it puts the sheet away and
 * opens the drawer, so the drawer gesture keeps working. The dock is never covered.
 */
enum class HioneDeckMode(val key: String) {
    TOP("top"),
    PULL_UP("pullup"),
    AUTO_POP("autopop"),
    PINNED("pinned");

    /** The card is off the home screen until it is pulled up. */
    val hidesCard: Boolean get() = this == PULL_UP || this == AUTO_POP

    companion object {
        fun parse(s: String?): HioneDeckMode = entries.firstOrNull { it.key == s?.trim()?.lowercase() } ?: TOP
    }
}

data class HioneDeckConfig(
    val mode: HioneDeckMode = HioneDeckMode.TOP,
    val popUnlock: Boolean = true,
    val popTrack: Boolean = true,
    val popCharger: Boolean = true,
    val popWeather: Boolean = true,
    val popBattery: Boolean = true,
    /** How long an auto pop stays up: 3, 5 or 8. */
    val popSeconds: Int = 5,
)

object HioneDeck {
    // Settings.Global keys, written by HioneSettings (pages/HomeLayoutPage.kt). Keep in sync.
    const val KEY_MODE = "hione_deck_mode"               // top | pullup | autopop | pinned
    const val KEY_POP_UNLOCK = "hione_deck_pop_unlock"   // 1 (default) / 0
    const val KEY_POP_TRACK = "hione_deck_pop_track"     // 1 (default) / 0
    const val KEY_POP_CHARGER = "hione_deck_pop_charger" // 1 (default) / 0
    const val KEY_POP_WEATHER = "hione_deck_pop_weather" // 1 (default) / 0
    const val KEY_POP_BATTERY = "hione_deck_pop_battery" // 1 (default) / 0
    const val KEY_POP_SECONDS = "hione_deck_pop_secs"    // 3 | 5 (default) | 8

    val KEYS = listOf(KEY_MODE, KEY_POP_UNLOCK, KEY_POP_TRACK, KEY_POP_CHARGER, KEY_POP_WEATHER, KEY_POP_BATTERY, KEY_POP_SECONDS)

    fun read(ctx: Context): HioneDeckConfig {
        val cr = ctx.contentResolver
        fun s(k: String): String? = try { Settings.Global.getString(cr, k) } catch (_: Throwable) { null }
        fun on(k: String): Boolean = s(k)?.trim() != "0"
        return HioneDeckConfig(
            mode = HioneDeckMode.parse(s(KEY_MODE)),
            popUnlock = on(KEY_POP_UNLOCK),
            popTrack = on(KEY_POP_TRACK),
            popCharger = on(KEY_POP_CHARGER),
            popWeather = on(KEY_POP_WEATHER),
            popBattery = on(KEY_POP_BATTERY),
            popSeconds = s(KEY_POP_SECONDS)?.trim()?.toIntOrNull()?.takeIf { it in setOf(3, 5, 8) } ?: 5,
        )
    }

    /** Where the wallpaper puts her face for this layout (see holiday/WallpaperFocus). */
    fun wallpaperTarget(mode: HioneDeckMode): FrameTarget = when (mode) {
        HioneDeckMode.TOP -> WallpaperFocus.HOME
        HioneDeckMode.PINNED -> WallpaperFocus.HOME_PINNED
        HioneDeckMode.PULL_UP, HioneDeckMode.AUTO_POP -> WallpaperFocus.HOME_CLEAR
    }

    /** Animator duration scale 0 (Developer options or Remove animations): move without animating. */
    fun reducedMotion(ctx: Context): Boolean = try {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Throwable) { false }
}

/** The live [HioneDeckConfig]; follows Settings.Global while the launcher runs. */
@Composable
fun rememberHioneDeckConfig(): State<HioneDeckConfig> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(HioneDeck.read(ctx)) }
    DisposableEffect(Unit) {
        val cr = ctx.contentResolver
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state.value = HioneDeck.read(ctx) }
        }
        HioneDeck.KEYS.forEach { k -> runCatching { cr.registerContentObserver(Settings.Global.getUriFor(k), false, obs) } }
        state.value = HioneDeck.read(ctx)
        onDispose { runCatching { cr.unregisterContentObserver(obs) } }
    }
    return state
}

/**
 * Sheet physics. [progress] 0 = put away, 1 = fully up. The sheet is translated by
 * (1 - progress) * its height and clipped at the handle, so it slides out from just above the dock.
 */
class HioneDeckState(private val scope: CoroutineScope, private val ctx: Context) {
    val progress = Animatable(0f)
    /** Last measured sheet height. Seeded so a drag before the first layout still tracks the finger. */
    var sheetHeightPx by mutableFloatStateOf(0f)
    /** Top of the handle (or the dock in PINNED), in root coordinates: the sheet's bottom edge. */
    var anchorTopPx by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)
        private set
    /** The sheet is (or is settling) up. Drives the haptic tick: one per real open or close. */
    var shown by mutableStateOf(false)
        private set
    /** Up because of an auto pop and not touched since: it tucks itself away when the timer ends. */
    var autoPopped by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var popJob: Job? = null

    /** Composition gate: the card is composed only while some of it can be seen. */
    val visible: Boolean get() = progress.value > 0.001f || dragging || shown

    private fun heightOr(): Float = sheetHeightPx.takeIf { it > 1f } ?: (ctx.resources.displayMetrics.density * 300f)

    private fun animateTo(target: Float) {
        job?.cancel()
        job = scope.launch {
            if (HioneDeck.reducedMotion(ctx)) progress.snapTo(target)
            else progress.animateTo(target, HioneMotion.settleNow)
        }
    }

    fun open(haptic: Boolean = true) {
        cancelPop()
        if (!shown && haptic) HioneHaptics.tick(ctx)
        shown = true
        animateTo(1f)
    }

    fun close(haptic: Boolean = true) {
        cancelPop()
        if (shown && haptic) HioneHaptics.tick(ctx)
        shown = false
        animateTo(0f)
    }

    fun toggle() = if (shown) close() else open()

    fun snapClosed() {
        cancelPop(); job?.cancel(); shown = false; dragging = false
        job = scope.launch { progress.snapTo(0f) }
    }

    /**
     * Auto pop for [seconds]. Leaves a sheet the user opened alone, and stays quiet (no haptic):
     * it happens without a touch, often mid-song.
     */
    fun pop(seconds: Int) {
        if (dragging) return
        if (shown && !autoPopped) return
        autoPopped = true
        shown = true
        animateTo(1f)
        popJob?.cancel()
        popJob = scope.launch {
            delay(seconds.coerceIn(1, 30) * 1000L)
            if (autoPopped && !dragging) { autoPopped = false; shown = false; animateTo(0f) }
        }
    }

    /** A touch on the sheet: it is the user's now, so the auto-pop timer stops. */
    fun hold() {
        if (autoPopped) { autoPopped = false; popJob?.cancel() }
    }

    private fun cancelPop() { autoPopped = false; popJob?.cancel() }

    /** Finger moved by [dyPx] (negative = up) while dragging the handle or the sheet. */
    fun dragBy(dyPx: Float) {
        hold()
        job?.cancel()
        dragging = true
        val target = (progress.value - dyPx / heightOr()).coerceIn(0f, 1f)
        job = scope.launch { progress.snapTo(target) }
    }

    /** Finger lifted with vertical velocity [vyPxS] (negative = up). */
    fun release(vyPxS: Float) {
        dragging = false
        val opens = vyPxS < -900f || (vyPxS < 900f && progress.value > 0.5f)
        if (opens) open() else close()
    }
}

@Composable
fun rememberHioneDeckState(): HioneDeckState {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    return remember { HioneDeckState(scope, ctx) }
}

private val GRAB_W = 44.dp
private val GRAB_H = 5.dp
private val HANDLE_H = 22.dp
private val TOUCH_SLOP = 8.dp
private val SWIPE_TRIGGER = 40.dp

/** The small grab pill, in the pack's palette, outlined so it reads on light and dark art. */
@Composable
private fun GrabPill(alpha: Float = 0.85f) {
    Box(
        Modifier
            .size(GRAB_W, GRAB_H)
            .background(HioneCyan.copy(alpha = alpha), RoundedCornerShape(50))
            .border(0.6.dp, Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
    )
}

/**
 * What sits between the workspace and the dock for each layout. PULL_UP / AUTO_POP: the grab
 * handle. PINNED: the card itself. TOP: nothing. Also publishes the sheet's anchor line.
 */
@Composable
fun HioneDeckDockSlot(
    state: HioneDeckState,
    mode: HioneDeckMode,
    content: @Composable () -> Unit,
) {
    when (mode) {
        HioneDeckMode.TOP -> Unit
        HioneDeckMode.PINNED -> Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .onGloballyPositioned { state.anchorTopPx = it.positionInRoot().y }
        ) { content() }
        HioneDeckMode.PULL_UP, HioneDeckMode.AUTO_POP -> HioneDeckHandle(state)
    }
}

/** Tap = open / close. A drag that starts here follows the finger, then settles on release. */
@Composable
private fun HioneDeckHandle(state: HioneDeckState) {
    val label = if (state.shown) "Hide status card" else "Show status card"
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 72.dp)
            .height(HANDLE_H)
            .onGloballyPositioned { state.anchorTopPx = it.positionInRoot().y }
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label = label) { state.toggle(); true }
            }
            .pointerInput(state) {
                val slop = TOUCH_SLOP.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var following = false
                    var strayed = false   // moved sideways past slop: neither a tap nor a drag
                    var lastY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            if (following) state.release(tracker.calculateVelocity().y)
                            else if (!strayed) state.toggle()
                            break
                        }
                        val dy = change.position.y - down.position.y
                        val dx = change.position.x - down.position.x
                        if (!following && abs(dx) > slop && abs(dx) >= abs(dy)) strayed = true
                        if (!following && !strayed && abs(dy) > slop && abs(dy) > abs(dx)) {
                            following = true
                            state.dragBy(dy)
                            lastY = change.position.y
                        } else if (following) {
                            state.dragBy(change.position.y - lastY)
                            lastY = change.position.y
                        }
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) { GrabPill(alpha = if (state.shown) 0.5f else 0.85f) }
}

/**
 * The pull-up sheet, drawn over the home screen from the top down to the handle and clipped there.
 * Composed only in PULL_UP / AUTO_POP. [drawerOpen] true puts the sheet away (the drawer covers
 * home); [onOpenDrawer] is called for a swipe up outside the sheet.
 */
@Composable
fun HioneDeckOverlay(
    state: HioneDeckState,
    config: HioneDeckConfig,
    drawerOpen: () -> Boolean,
    onOpenDrawer: () -> Unit,
    content: @Composable () -> Unit,
) {
    val mode = config.mode
    LaunchedEffect(mode) { if (!mode.hidesCard) state.snapClosed() }
    if (!mode.hidesCard) return

    val drawerOpenNow by rememberUpdatedState(drawerOpen)
    LaunchedEffect(state) {
        snapshotFlow { drawerOpenNow() }.distinctUntilChanged().collect { open ->
            if (open && state.shown) state.close(haptic = false)
        }
    }
    if (mode == HioneDeckMode.AUTO_POP) HioneDeckAutoPop(state, config, drawerOpen)

    // derivedStateOf: the overlay recomposes when the sheet appears or goes, not on every frame.
    val visible by remember(state) { derivedStateOf { state.visible } }
    if (!visible) return
    val density = LocalDensity.current
    var originY by remember { mutableFloatStateOf(0f) }
    val regionPx = (state.anchorTopPx - originY).coerceAtLeast(0f)
    if (regionPx <= 0f) {
        // Not laid out yet: measure our own origin, draw nothing.
        Box(Modifier.fillMaxSize().onGloballyPositioned { originY = it.positionInRoot().y })
        return
    }
    val regionDp = with(density) { regionPx.toDp() }
    val onOpenDrawerNow by rememberUpdatedState(onOpenDrawer)

    Box(
        Modifier
            .fillMaxWidth()
            .height(regionDp)
            .onGloballyPositioned { originY = it.positionInRoot().y }
            .clipToBounds()
    ) {
        // Outside the sheet: dim a little; tap or swipe down puts it away, swipe up opens the drawer.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.progress.value }
                .background(Mascot.color(0xFF0C0D10).copy(alpha = 0.30f))
                .pointerInput(state) {
                    val trig = SWIPE_TRIGGER.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var handled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!handled) state.close()
                                break
                            }
                            val dy = change.position.y - down.position.y
                            val dx = change.position.x - down.position.x
                            change.consume()
                            if (!handled && abs(dy) > trig && abs(dy) > abs(dx) * 1.4f) {
                                handled = true
                                state.close()
                                if (dy < 0) onOpenDrawerNow()
                            }
                        }
                    }
                }
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = regionDp)
                .onSizeChanged { state.sheetHeightPx = it.height.toFloat() }
                .graphicsLayer {
                    val h = state.sheetHeightPx
                    translationY = (1f - state.progress.value) * (if (h > 1f) h else size.height)
                    alpha = if (h > 1f) 1f else 0f
                }
                // Any touch on the sheet keeps an auto pop up. Initial pass, nothing consumed.
                .pointerInput(state) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        state.hold()
                    }
                }
                // Drag down on the sheet puts it away. Main pass, after the badges: a drag a badge
                // claimed (quilt long-press reorder, the weather strip) is left alone.
                .pointerInput(state) {
                    val slop = TOUCH_SLOP.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var following = false
                        var lastY = down.position.y
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (!change.pressed) {
                                if (following) state.release(tracker.calculateVelocity().y)
                                break
                            }
                            if (!following && change.isConsumed) break
                            val dy = change.position.y - down.position.y
                            val dx = change.position.x - down.position.x
                            if (!following && dy > slop && abs(dy) > abs(dx) * 1.4f) {
                                following = true
                                state.dragBy(dy)
                                lastY = change.position.y
                                change.consume()
                            } else if (following) {
                                state.dragBy(change.position.y - lastY)
                                lastY = change.position.y
                                change.consume()
                            }
                        }
                    }
                }
                .padding(bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.padding(bottom = 4.dp)) { GrabPill() }
            content()
        }
    }
}

/**
 * Auto-pop triggers. Each fires [HioneDeckState.pop] only while home is on screen and the drawer is
 * shut. Unlock / screen-on is remembered until home is next on screen (the lock screen sits on
 * top until then). A new track and a new weather alert compare against what was there before, so
 * the first reading after start or after coming back home never pops.
 */
@Composable
private fun HioneDeckAutoPop(state: HioneDeckState, config: HioneDeckConfig, drawerOpen: () -> Boolean) {
    val ctx = LocalContext.current
    val blocked by rememberUpdatedState(drawerOpen)
    val seconds = config.popSeconds
    fun popNow() {
        if (HionePowerProfile.visible.value && !blocked()) state.pop(seconds)
    }
    val wakeAt = remember { MutableStateFlow(0L) }

    DisposableEffect(config.popUnlock, config.popCharger, config.popBattery) {
        val filter = IntentFilter().apply {
            if (config.popUnlock) { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
            if (config.popCharger) addAction(Intent.ACTION_POWER_CONNECTED)
            if (config.popBattery) addAction(Intent.ACTION_BATTERY_LOW)
        }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> wakeAt.value = SystemClock.elapsedRealtime()
                    Intent.ACTION_POWER_CONNECTED, Intent.ACTION_BATTERY_LOW -> popNow()
                }
            }
        }
        val registered = filter.countActions() > 0 && runCatching {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(rx, filter)
        }.isSuccess
        onDispose { if (registered) runCatching { ctx.unregisterReceiver(rx) } }
    }

    if (config.popUnlock) {
        LaunchedEffect(seconds) {
            combine(HionePowerProfile.visible, wakeAt) { v, t -> v to t }.collect { (v, t) ->
                if (v && t > 0L && SystemClock.elapsedRealtime() - t < 60_000L) {
                    wakeAt.value = 0L
                    delay(250)   // let the lock screen's exit transition finish
                    popNow()
                } else if (t > 0L && SystemClock.elapsedRealtime() - t >= 60_000L) {
                    wakeAt.value = 0L
                }
            }
        }
    }

    if (config.popTrack) {
        LaunchedEffect(seconds) {
            var last: String? = null
            while (true) {
                if (!HionePowerProfile.visible.value) { last = null; HionePowerProfile.awaitVisible() }
                val key = withContext(Dispatchers.IO) {
                    com.hione.launcher.lockscreen.HioneMediaLink.active(ctx)?.takeIf { it.isPlaying }?.let { l ->
                        val t = l.title?.trim().orEmpty()
                        if (t.isEmpty()) null else "${l.packageName}|$t|${l.artist.orEmpty()}"
                    }
                }
                if (key != null) {
                    if (last != null && key != last) popNow()
                    last = key
                }
                delay(HionePowerProfile.refreshMs(2000L))
            }
        }
    }

    if (config.popWeather) {
        LaunchedEffect(seconds) {
            var seen: Set<String>? = null
            combine(
                com.hione.launcher.weather.HioneNwsAlerts.state.map { s -> s.alerts.map { it.id }.toSet() },
                com.hione.launcher.weather.HioneWeatherService.state.map { it.weather.severeWarning?.takeIf { w -> w.isNotBlank() } }
            ) { ids, warn -> if (warn != null) ids + "wx:$warn" else ids }
                .distinctUntilChanged()
                .collect { now ->
                    val before = seen
                    seen = now
                    if (before != null && (now.subtract(before)).isNotEmpty()) popNow()
                }
        }
    }
}
