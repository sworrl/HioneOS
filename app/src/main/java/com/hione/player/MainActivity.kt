package com.hione.player

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import com.hione.player.ui.hioneGlassPanel
import com.hione.player.ui.hioneHazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.hione.player.ui.VERTICAL_GESTURE_EDGES
import com.hione.player.ui.detectVerticalDragGesturesEdgeSafe
import com.hione.player.ui.edgeSafePointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.hione.player.ui.bpmBeatPulse
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.hione.player.artistart.ArtistPhotoCreditButton
import com.hione.player.artistart.ArtistPhotoImage
import com.hione.player.artistart.ArtistPhotoSettingsCard

private val HioneColors get() = darkColorScheme(
    primary = HioneTeal, onPrimary = Mascot.color(0xFF00201D), secondary = HionePink,
    background = Ground, surface = Surface1, onBackground = Mascot.color(0xFFE8F4F2), onSurface = Mascot.color(0xFFE8F4F2),
)

// Every M3 type-scale role mapped to the bundled Hione font kit instead of the default Typography()
// falling back to the platform's stock system font wherever a Text() doesn't set its own
// fontFamily explicitly — which was most of the app's body/list text. Reuses the same fonts
// already reserved for these exact roles (see Fonts.kt): Audiowide for the biggest brand
// moments, Righteous for headers, Baloo 2 as the readable UI/body workhorse, Orbitron for
// labels/metrics (matches the existing DataChip numeric badges). Sizes/line-heights/spacing are
// kept at Material3's own defaults — only the typeface changes.
private val HioneTypography = Typography().let { d ->
    d.copy(
        displayLarge = d.displayLarge.copy(fontFamily = AudiowideFont, fontSize = 62.sp),
        displayMedium = d.displayMedium.copy(fontFamily = AudiowideFont, fontSize = 50.sp),
        displaySmall = d.displaySmall.copy(fontFamily = AudiowideFont, fontSize = 42.sp),
        headlineLarge = d.headlineLarge.copy(fontFamily = RighteousFont, fontSize = 38.sp),
        headlineMedium = d.headlineMedium.copy(fontFamily = RighteousFont, fontSize = 34.sp),
        headlineSmall = d.headlineSmall.copy(fontFamily = RighteousFont, fontSize = 29.sp),
        titleLarge = d.titleLarge.copy(fontFamily = RighteousFont, fontSize = 27.sp),
        titleMedium = d.titleMedium.copy(fontFamily = RighteousFont, fontSize = 21.sp),
        titleSmall = d.titleSmall.copy(fontFamily = RighteousFont, fontSize = 19.sp),
        bodyLarge = d.bodyLarge.copy(fontFamily = Baloo2Font, fontSize = 21.sp),
        bodyMedium = d.bodyMedium.copy(fontFamily = Baloo2Font, fontSize = 18.5.sp),
        bodySmall = d.bodySmall.copy(fontFamily = Baloo2Font, fontSize = 16.sp),
        labelLarge = d.labelLarge.copy(fontFamily = OrbitronFont, fontSize = 18.5.sp),
        labelMedium = d.labelMedium.copy(fontFamily = OrbitronFont, fontSize = 16.5.sp),
        labelSmall = d.labelSmall.copy(fontFamily = OrbitronFont, fontSize = 15.sp),
    )
}

/**
 * Shared "frosted glass" card material for static surfaces — translucent tinted fill, soft drop
 * shadow, thin top-lit specular border. Rows/tiles/chips don't have meaningful content directly
 * behind them to blur through (they ARE the content, over a flat page background), so they get
 * this consistent glass-look MATERIAL instead of literal backdrop blur — Header/TabBar and the
 * detail hero panels use real Haze blur separately, since scrolling content/art actually passes
 * behind those.
 */
fun Modifier.glassCard(corner: androidx.compose.ui.unit.Dp = 14.dp, tint: Color = HioneTeal): Modifier = this
    .clip(RoundedCornerShape(corner))
    .drawBehind {
        val rr = CornerRadius(corner.toPx(), corner.toPx())
        drawRoundRect(Color(0x33000000), topLeft = Offset(0f, 1.6.dp.toPx()), size = size, cornerRadius = rr)
        drawRoundRect(
            Brush.verticalGradient(listOf(tint.copy(alpha = 0.14f), Mascot.color(0xFF0A2022).copy(alpha = 0.5f))),
            cornerRadius = rr
        )
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0x3AFFFFFF), Color(0x00FFFFFF)), endY = size.height * 0.55f),
            cornerRadius = rr, style = Stroke(1.dp.toPx())
        )
    }

/**
 * [glassCard] plus tactile press response: a springy scale-dip of the WHOLE tile (material and
 * content together — graphicsLayer sits before the glass draw), ripple clipped to the card, and
 * an optional long-press. One definition of "pressable tile" so grid cells and list rows all
 * respond the same way, instead of some rippling flat and others doing bespoke scale effects.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressableGlassCard(
    corner: androidx.compose.ui.unit.Dp = 14.dp,
    tint: Color = HioneTeal,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val ctx = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.935f else 1f,
        animationSpec = spring(dampingRatio = 0.60f, stiffness = 1200f),
        label = "cardPress"
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .glassCard(corner, tint)
        .combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onLongClick = onLongClick?.let {
                {
                    Haptics.heavy(ctx)
                    it()
                }
            },
            onClick = {
                Haptics.click(ctx)
                onClick()
            }
        )
}

// Order here is the visual tab-bar order (Home, Artists, Albums, Library) — Songs is no longer
// its own top-level tab (merged into Library, reachable via an "All Songs" row there), but the
// enum value stays for the existing `tab == Tab.SONGS -> SongList(...)` route; TabBar filters it
// out of what it actually renders rather than the enum controlling bar order/membership directly.
private enum class Tab(val label: String) { HOME("Home"), ARTISTS("Artists"), ALBUMS("Albums"), GENRES("Library"), SONGS("Songs") }

// Snapshot routes for the main content area's AnimatedContent. The exiting screen must keep
// rendering from data captured HERE, not from the live selection state — albumSel/artistSel/
// libSel are already nulled by the time that screen's exit animation plays, so reading them
// with !! inside the old content would crash mid-transition. `key` is what AnimatedContent
// compares (via contentKey) so a rescan swapping in an equal-looking group with a fresh track
// list updates in place instead of replaying the transition; `depth` drives push-vs-pop motion.
private sealed class ContentRoute(val key: String, val depth: Int) {
    class TabPage(val tab: Tab) : ContentRoute("tab:${tab.name}", if (tab == Tab.SONGS) 1 else 0)
    class Album(val group: AlbumGroup) : ContentRoute("album:${group.name}#${group.artist}", 1)
    class Artist(val group: ArtistGroup) : ContentRoute("artist:${group.name}", 1)
    class Lib(val name: String) : ContentRoute("lib:$name", 1)
    object Videos : ContentRoute("videos", 1)
}

// Bar position of a top-level tab route (-1 for everything else) — gives lateral tab hops a
// direction so content slides TOWARD the tapped tab rather than always from the same side.
private fun routeLane(r: ContentRoute): Int =
    (r as? ContentRoute.TabPage)?.let { VISIBLE_TABS.indexOf(it.tab) } ?: -1

// One motion grammar for the whole content area: detail screens PUSH in (rise + settle over the
// parent easing back) and POP out the way they came; same-level tab hops slide laterally toward
// the tapped tab. Springs kept stiff and fades short — both screens are live mid-transition on
// modest DAP silicon, and anything languid reads as lag rather than polish on a 3.5" panel.
private fun AnimatedContentTransitionScope<ContentRoute>.mainContentTransition(): ContentTransform {
    val from = initialState.depth
    val to = targetState.depth
    return when {
        to > from ->
            (slideInVertically(spring(dampingRatio = 0.9f, stiffness = 900f)) { it / 10 } +
                fadeIn(tween(190, easing = LinearOutSlowInEasing)))
                .togetherWith(fadeOut(tween(130)) + scaleOut(targetScale = 0.985f, animationSpec = tween(190)))
        to < from ->
            (fadeIn(tween(170, easing = LinearOutSlowInEasing)) + scaleIn(initialScale = 0.985f, animationSpec = tween(170)))
                .togetherWith(slideOutVertically(tween(190, easing = FastOutLinearInEasing)) { it / 12 } + fadeOut(tween(150)))
        else -> {
            val dir = if (routeLane(targetState) >= routeLane(initialState)) 1 else -1
            (slideInHorizontally(spring(dampingRatio = 1f, stiffness = 700f)) { dir * it / 9 } + fadeIn(tween(160)))
                .togetherWith(slideOutHorizontally(tween(160)) { -dir * it / 12 } + fadeOut(tween(130)))
        }
    }.using(SizeTransform(clip = false))
}

/** Observable "what's playing" state so any track row can show a live indicator without prop-drilling. */
object NowPlayingState {
    var currentId by mutableStateOf(-1L)
    var playing by mutableStateOf(false)
}


private val ALBUM_ART_URI = android.net.Uri.parse("content://media/external/audio/albumart")

/** MediaItem carrying full metadata + album art so the lockscreen/notification shows real info.
 *  Non-private (used by [[AlarmRingService]] too, to build the alarm's playback queue). */
fun mediaItemFor(t: Track): MediaItem {
    val meta = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(t.title)
        .setArtist(t.artist)
        .setAlbumTitle(t.album)
        .setArtworkUri(if (t.albumId > 0) ContentUris.withAppendedId(ALBUM_ART_URI, t.albumId) else null)
        .build()
    val sourceId = if (t.parentId != 0L) t.parentId else t.id
    val b = MediaItem.Builder()
        .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, sourceId))
        .setMediaId(t.id.toString())
        .setMediaMetadata(meta)
    // Virtual track cut from a whole-disc image: play only its window of the parent file (Media3
    // wraps this in a ClippingMediaSource — seeks to INDEX 01, ends at the next index).
    if (t.parentId != 0L) {
        b.setClippingConfiguration(
            androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(t.clipStartMs.coerceAtLeast(0L))
                .apply { if (t.clipEndMs > t.clipStartMs) setEndPositionMs(t.clipEndMs) }
                .build()
        )
    }
    return b.build()
}

object QueueManager {
    fun playNext(context: Context, player: ExoPlayer, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val items = tracks.map { mediaItemFor(it) }
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items, 0, 0L)
            player.prepare()
            player.play()
        } else {
            val nextIndex = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
            player.addMediaItems(nextIndex, items)
        }
        val msg = if (tracks.size == 1) "Playing next: ${tracks[0].title}" else "${tracks.size} tracks will play next"
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        Haptics.tick(context)
        UpdateManager.saveQueueSnapshot(context, player)
    }

    fun playLast(context: Context, player: ExoPlayer, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val items = tracks.map { mediaItemFor(it) }
        if (player.mediaItemCount == 0) {
            player.setMediaItems(items, 0, 0L)
            player.prepare()
            player.play()
        } else {
            player.addMediaItems(items)
        }
        val msg = if (tracks.size == 1) "Added to queue: ${tracks[0].title}" else "Added ${tracks.size} tracks to the queue"
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        Haptics.tick(context)
        UpdateManager.saveQueueSnapshot(context, player)
    }
}

private const val LIKED_KEY = " LIKED"

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var sustainedPerfListener: androidx.media3.common.Player.Listener? = null
    private var powerProfileListener: ((HionePowerGovernor.Profile) -> Unit)? = null
    // Dev workflow today, future in-app OTA installer tomorrow: whoever is about to trigger an
    companion object {
        var globalBackHandler: (() -> Unit)? = null
        /** Set when an intent asks for the alarms screen (Hione Music's alarm notifications and
         *  Hione Clock put [AlarmScheduler.EXTRA_OPEN_ALARMS] on it). The root composable watches
         *  this, opens Settings on the Alarms tab, then clears it. */
        val openAlarmsRequest = androidx.compose.runtime.mutableStateOf(false)
    }

    /** Honors the open_alarms extra once, then strips it so a recreate doesn't reopen alarms. */
    private fun consumeOpenAlarms(i: android.content.Intent?) {
        if (i?.getBooleanExtra(AlarmScheduler.EXTRA_OPEN_ALARMS, false) == true) {
            i.removeExtra(AlarmScheduler.EXTRA_OPEN_ALARMS)
            openAlarmsRequest.value = true
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenAlarms(intent)
    }

    private val gestureBackReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            runOnUiThread {
                val handler = globalBackHandler
                if (handler != null) {
                    handler()
                } else {
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    // actual package install broadcasts ACTION_UPDATE_STARTING first, so the overlay + full-queue
    // save happen BEFORE the process dies, not after — see UpdateHandler.kt's doc comment.
    private val updateStartingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            UpdateManager.beginGracefulUpdate(context, player, intent.getStringExtra(UpdateManager.EXTRA_TARGET_VERSION) ?: "")
        }
    }

    // Restores the real system screen-off timeout the INSTANT the display actually goes dark
    // (ScreenOffHelper shrinks it to force an early sleep at the end of the idle pipeline) — not
    // waiting for the app to be reopened. Without this, the shrunk timeout would keep applying
    // system-wide (every app, not just this one) until next launch. ACTION_SCREEN_OFF is a
    // protected broadcast — only deliverable to a dynamically-registered receiver, never manifest.
    // Handles BOTH screen transitions. The OFF edge is what stops every per-frame UI loop in the
    // app: IdleController.screenActive gates them, and it used to ignore the real display state
    // entirely (see IdleController.displayOn) — leaving the main thread at ~67% against a dark panel.
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_SCREEN_ON -> IdleController.setDisplayOn(true)
                else -> {
                    IdleController.setDisplayOn(false)
                    // The shared output-mix Visualizer is bound by the scrubber/visualisers via
                    // AudioCapture.ensure() from four call sites and was released by NONE of them,
                    // so it kept capturing FFT + waveform for a dark screen. Nothing reads those
                    // buffers while the panel is off; the next ensure() rebinds on wake.
                    runCatching { AudioCapture.release() }
                    ScreenOffHelper.restore(context)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        try {
            overridePendingTransition(R.anim.magic_lamp_expand, R.anim.magic_lamp_fade_out)
        } catch (_: Throwable) {}
        if (!isSupportedDevice()) {
            setContent { MaterialTheme(colorScheme = HioneColors, typography = HioneTypography) { UnsupportedDeviceScreen() } }
            return
        }
        // Remote entitlement layer on top of the local hardware gate (com.hione.player.entitlement).
        // Inert unless BuildConfig.HIONE_ENTITLEMENT_* is baked in AND the Settings toggle is on;
        // fail-open on every error; `blocked` only after a signed 3×/72h disallow chain.
        com.hione.player.entitlement.EntitlementManager.onAppStart(this)
        if (try { com.hione.player.entitlement.EntitlementManager.decision(this).blocked } catch (_: Throwable) { false }) {
            setContent { MaterialTheme(colorScheme = HioneColors, typography = HioneTypography) { com.hione.player.entitlement.EntitlementBlockedScreen(onUnblocked = { recreate() }) } }
            return
        }
        if (intent?.getBooleanExtra(UpdateManager.EXTRA_JUST_UPDATED, false) == true) {
            UpdateOverlay.mode.value = UpdateOverlayMode.RESUMING
        }
        consumeOpenAlarms(intent)
        player = PlayerHolder.ensure(this)
        PlayerHolder.ensureSession(this)                                  // branded lockscreen/notification control
        PlayerHolder.ensureControllerConnected(this)                      // keep service foreground & active for screen-off hardware keys
        try {
            com.hione.player.api.HioneApiServer.start(this)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to start HioneApiServer", e)
        }
        // BLE phone remote (remote/): only re-arms if the user left its Settings toggle on.
        try { com.hione.player.remote.HioneRemoteGattService.startIfEnabled(this) } catch (_: Throwable) {}
        val updateFilter = android.content.IntentFilter(UpdateManager.ACTION_UPDATE_STARTING)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateStartingReceiver, updateFilter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(updateStartingReceiver, updateFilter)
        }
        registerReceiver(screenOffReceiver, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        })
        // Seed from the platform: a broadcast missed while we were not registered must not leave
        // the per-frame loops believing the panel is lit.
        IdleController.syncDisplayState(this)
        val backFilter = android.content.IntentFilter().apply {
            addAction("com.hione.player.action.TRIGGER_BACK")
            addAction("com.hione.systemui.action.TRIGGER_BACK")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(gestureBackReceiver, backFilter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(gestureBackReceiver, backFilter)
        }
        AlarmScheduler.rescheduleAll(this)                                 // re-arm exact alarms every launch (idempotent; also covers "somehow got cleared")

        // "All files access" self-grant (platform-signed) — SD-card folder art + scans depend on it.
        HioneStorageAccess.ensure(this)
        // Automated SD card LOCAL rescans (never network — see HioneIngestGate for the ingress engine)
        schedulePeriodicLibraryScan(this)

        // Warm the projectM preset library off the GL thread. The shipped library is ~9.8k presets
        // (assets/presets_pack.zip); unpacking it the first time a visualizer opens would stall that
        // first frame for tens of seconds, so do it here, once, in the background.
        Thread {
            runCatching { ProjectMNative.ensurePresets(applicationContext) }
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()

        // Tell the platform to hold a stable, un-throttled CPU/GPU clock state (no governor
        // hunting/lag) exactly while we're actually busy — playing audio, which is also when the
        // projectM visualizer is doing its heaviest continuous GL work. This is a hint, not a
        // wake lock: it's automatically inert the instant this Window isn't visible (screen off,
        // backgrounded), so idle/screen-off keeps normal Android power behavior (deep sleep, no
        // CPU pegging) — see the GLSurfaceView lifecycle fix in ProjectMVisualizerView for the
        // other half of that. Toggled purely off player.isPlaying so "paused while browsing" also
        // falls back to normal scaling instead of needlessly holding a high clock state.
        // Driven by HionePowerGovernor now: PERF (vis / Now Playing visible while playing, or
        // charging) holds the sustained clock state; BALANCED/AUDIO_ONLY/IDLE release it.
        HionePowerGovernor.init(applicationContext)
        window.setSustainedPerformanceMode(HionePowerGovernor.sustainedPerfWanted)
        powerProfileListener = { _: HionePowerGovernor.Profile -> runOnUiThread { runCatching { window.setSustainedPerformanceMode(HionePowerGovernor.sustainedPerfWanted) } } }
        powerProfileListener?.let { HionePowerGovernor.addListener(it) }
        sustainedPerfListener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                HionePowerGovernor.onPlaybackState(applicationContext, isPlaying)
            }
        }.also { player.addListener(it) }
        LibraryDaemonService.start(this)
        // Permanently ensure Android framework recognizes device provisioning & user setup complete
        // (AOSP MediaSessionService drops all global hardware media buttons if user_setup_complete == 0)
        try {
            val cr = applicationContext.contentResolver
            android.provider.Settings.Secure.putInt(cr, "user_setup_complete", 1)
            android.provider.Settings.Global.putInt(cr, "device_provisioned", 1)
        } catch (_: Throwable) {}

        HioneDbg.install(this)
        try { HionePocketLockManager.init(this) } catch (t: Throwable) { android.util.Log.e("MainActivity", "HionePocketLockManager.init failed", t) }
        try { com.hione.player.volume.HioneVolumeManager.init(this) } catch (t: Throwable) { android.util.Log.e("MainActivity", "HioneVolumeManager.init failed", t) }
        try { com.hione.player.screentime.HioneSmartScreenTimeEngine.init(this) } catch (t: Throwable) { android.util.Log.e("MainActivity", "HioneSmartScreenTimeEngine.init failed", t) }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        setContent {
            MaterialTheme(colorScheme = HioneColors, typography = HioneTypography) {
                var granted by remember { mutableStateOf(hasAudioPermission()) }
                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result -> granted = result[audioPermission()] ?: hasAudioPermission() }
                LaunchedEffect(Unit) { launcher.launch(permissionsToRequest()) }
                // Collapse the whole semantics tree to ONE node when the only accessibility service
                // is our own navigation (which never reads content). Marking the content view
                // unimportant did NOT stop Compose's delegate: it still walked every node and
                // computed every window bound on every layout pass, 43% of the UI thread while
                // scrolling. A one-node tree makes that walk free. With a real screen reader
                // enabled the gate is off and the full tree is back. See HioneSemanticsGate.
                val navOnly = remember { HioneSemanticsGate.onlyHioneNavEnabled(this@MainActivity) }
                Surface(
                    color = HioneArtTheme.colors().ground,
                    modifier = Modifier.fillMaxSize().then(
                        if (navOnly) Modifier.clearAndSetSemantics {} else Modifier
                    )
                ) {
                    if (granted) {
                        var refresh by remember { mutableStateOf(0) }
                        var lastSeenGen by remember { mutableStateOf(ScanProgress.generation.get()) }
                        LaunchedEffect(Unit) {
                            // DEBOUNCED. A rescan bumps the generation once per scanner batch, and
                            // each bump used to re-key produceState and queue another full walk:
                            // FIVE back-to-back 7 to 20 second walks for one rescan (seen 2026-09-19).
                            // Now a change only counts once the generation has held still for a
                            // few seconds, i.e. the scan is actually done.
                            var pendingGen: Int? = null
                            var stableSince = 0L
                            while (true) {
                                val g = ScanProgress.generation.get()
                                if (g != lastSeenGen) {
                                    if (pendingGen != g) { pendingGen = g; stableSince = android.os.SystemClock.elapsedRealtime() }
                                    else if (android.os.SystemClock.elapsedRealtime() - stableSince >= 4000L) {
                                        lastSeenGen = g; pendingGen = null
                                        AlbumArtCache.clearMisses() // a scan may have surfaced art for previously-missed tracks
                                        refresh++
                                    }
                                }
                                delay(1500L)
                            }
                        }
                        // The binary cache load is NOT on the main thread any more. It was, inside
                        // remember{}, under a comment promising "0ms cold start", and it measured 7 to
                        // 10 SECONDS on this device because the launch-time background work starved
                        // the UI thread (see HioneBackground). The cache lands from IO in well under a
                        // second when nothing is fighting it, the screen draws a loading state until
                        // then, and the full MediaStore query follows on the low-priority pool.
                        var hasLoadedOnce by remember { mutableStateOf(FastLibraryStore.peek() != null) }
                        val tracks by produceState(
                            initialValue = FastLibraryStore.peek() ?: emptyList(), refresh
                        ) {
                            if (value.isEmpty()) {
                                val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    FastLibraryStore.loadSync(this@MainActivity)
                                }
                                if (!cached.isNullOrEmpty()) { value = cached; hasLoadedOnce = true }
                            }
                            // Skip the 45-second walk when MediaStore has not changed since the cache was
                            // written. A rescan (refresh > 0) or an empty cache always walks.
                            if (refresh == 0 && value.isNotEmpty() && FastLibraryStore.cacheIsCurrent(this@MainActivity)) {
                                android.util.Log.i("HionePlayer", "library: MediaStore generation unchanged, cache is current, skipping full query")
                                hasLoadedOnce = true
                                return@produceState
                            }
                            val result = kotlinx.coroutines.withContext(HioneBackground.dispatcher) { queryTracks() }
                            hasLoadedOnce = true
                            if (value.isEmpty() || result.size != value.size || (result.isNotEmpty() && value.isNotEmpty() && (result.first().id != value.first().id || result.last().id != value.last().id))) {
                                value = result
                                FastLibraryStore.saveAsync(this@MainActivity, result)
                            }
                            // The cache now reflects THIS generation of MediaStore.
                            FastLibraryStore.stampGeneration(this@MainActivity)
                        }
                        IdleWatcher()
                        val idleTier = IdleController.tier
                        LaunchedEffect(idleTier) { applyIdleBrightness(window, idleTier) }
                        Box(Modifier.fillMaxSize()) {
                            App(tracks, player, loading = !hasLoadedOnce, onScan = { rescan {} }) { list, i -> play(list, i) }
                            if (idleTier == IdleTier.AMBIENT || idleTier == IdleTier.OFF) AmbientOverlay(player, Modifier.fillMaxSize())
                            UpdateOverlayScreen(Modifier.fillMaxSize())
                            com.hione.player.volume.HioneCyberVolumeHudOverlay(
                                ctx = this@MainActivity,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 92.dp, end = 4.dp)
                            )
                            // One slider per output while playing to several, beside the HUD.
                            com.hione.player.volume.HioneOutputVolumesPanel(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 92.dp, end = 62.dp)
                            )
                        }
                    } else PermissionPrompt { launcher.launch(permissionsToRequest()) }
                }
            }
        }
    }

    // Player/session lifecycle is owned by PlayerHolder + PlaybackService (so playback and the
    // lockscreen control survive the Activity); don't release the player here. DO remove our own
    // listener though — PlayerHolder.player outlives this Activity, so leaving it attached would
    // leak this (and every future recreated) Activity/Window and keep firing setSustainedPerformanceMode
    // against a dead window.
    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        try {
            overridePendingTransition(R.anim.magic_lamp_fade_in, R.anim.magic_lamp_collapse)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        sustainedPerfListener?.let { player.removeListener(it) }
        powerProfileListener?.let { HionePowerGovernor.removeListener(it) }
        runCatching { unregisterReceiver(updateStartingReceiver) }
        runCatching { unregisterReceiver(screenOffReceiver) }
        runCatching { unregisterReceiver(gestureBackReceiver) }
        try { com.hione.player.screentime.HioneSmartScreenTimeEngine.stop() } catch (_: Throwable) {}
        kotlinx.coroutines.MainScope().launch { CpuPerformance.onBackground(this@MainActivity) }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        try { com.hione.player.screentime.HioneSmartScreenTimeEngine.stop() } catch (_: Throwable) {}
        lifecycleScope.launch { CpuPerformance.onBackground(this@MainActivity) }
        lifecycleScope.launch(Dispatchers.IO) { TrackTech.flushPending() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            VisualizerMemoryGuard.suppress()
        }
    }

    /**
     * Misnamed for history: this SHOWS the system bars and makes the app draw underneath them.
     *
     * The status bar owns the top 41px of a 720x1280 panel. Hione Music was laid out below it, so
     * that strip was dead space the app paid for and never used. Edge-to-edge gives the window the
     * whole panel and lets the stock clock/battery bar bleed over the app's own background, which
     * is what it is for on a screen this small. FLAG_FULLSCREEN has to be cleared explicitly: an
     * old theme set it, and while it is set the framework insets the app below the bar no matter
     * what the insets controller says.
     */
    private fun hideSystemBars() {
        try {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            // STATUS bar visible (clock/battery bleeding over the app is the point of edge-to-edge
            // here). NAVIGATION bar HIDDEN: this device's navigation IS the accessibility service's
            // own home pill, which follows the finger and shifts colour with the art. Showing the
            // stock one too put a second, dead, all-white pill on screen next to ours. Clearing
            // FLAG_FULLSCREEN for the status bar is what let it back in.
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            insetsController.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            // Android 10+ re-tints a transparent bar with a scrim of its own when it thinks the
            // content behind it is too busy. We draw our own scrim, so refuse it.
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            // Light art behind the bar would leave black glyphs on black. Force the light glyph set.
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        HioneSemanticsGate.apply(this)   // see HioneSemanticsGate: no semantics tree for a nav-only a11y service
        VisualizerMemoryGuard.release()
        IdleController.loadPrefs(this)
        IdleController.syncDisplayState(this)   // resuming means the panel is lit; re-arm the loops
        IdleController.poke(this)
        try { com.hione.player.screentime.HioneSmartScreenTimeEngine.start(this) } catch (_: Throwable) {}
        ScreenOffHelper.restore(this)
        lifecycleScope.launch {
            CpuPerformance.applyIfEnabled(this@MainActivity)
            CpuPerformance.restoreIfStranded(this@MainActivity)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Fires for ANY user input system-wide — touches, key presses, trackball, the physical
    // transport keys — without needing to wire a listener into every single composable/screen.
    // Exactly the "was the user just here" signal the idle dim/ambient timers need.
    override fun onUserInteraction() {
        super.onUserInteraction()
        IdleController.poke(this)
    }

    // Hardware transport keys & gesture chords (M500 side buttons / headset)
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        IdleController.poke(this)
        if (AlarmRingService.interceptMediaKey(this, keyCode)) return true   // ringing alarm owns the side buttons: play/pause = snooze, next/prev = dismiss
        // Unsupported-device / entitlement-blocked screens return from onCreate before `player`
        // exists — a side transport key there must not throw UninitializedPropertyAccessException.
        if (!::player.isInitialized) return super.onKeyDown(keyCode, event)
        if (com.hione.player.volume.HioneVolumeManager.handleKeyDown(keyCode, this)) {
            return true
        }
        if (HioneHardwareGestureEngine.onKeyDown(keyCode, event, this)) {
            return true
        }
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (event == null || event.repeatCount == 0) Haptics.tick(this)
                if (player.isPlaying) player.pause() else player.play()
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event == null || event.repeatCount == 0) Haptics.tick(this)
                player.play()
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event == null || event.repeatCount == 0) Haptics.tick(this)
                player.pause()
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event == null || event.repeatCount == 0) Haptics.tick(this)
                player.seekToNextMediaItem()
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event == null || event.repeatCount == 0) Haptics.tick(this)
                player.seekToPreviousMediaItem()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (HioneHardwareGestureEngine.onKeyUp(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isSupportedDevice(): Boolean {
        // DEVELOPER OVERRIDE. Hione Music is built for one device and the gate is deliberate: the
        // audio path, the DAC controls and half the hardware readouts assume an M500 and would
        // otherwise show nothing or lie. But running the UI on another phone is genuinely useful
        // for development, so shell can lift the gate:
        //
        //   adb shell settings put global hione_allow_any_device 1
        //
        // Settings.Global is used because the SHELL holds WRITE_SECURE_SETTINGS on any device
        // while the app does not, so this cannot be flipped by the app or by anything the user
        // taps. Off the M500 the hardware pages will honestly report that there is no CS43198 and
        // no vendor audio properties; the player, library and visualiser work normally.
        val overridden = try {
            android.provider.Settings.Global.getInt(contentResolver, "hione_allow_any_device", 0) == 1
        } catch (_: Throwable) { false }
        if (overridden) {
            android.util.Log.w("HionePlayer", "device gate lifted by hione_allow_any_device; " +
                "hardware-specific readouts will report unavailable on ${android.os.Build.MODEL}")
            return true
        }
        val realHardware = android.os.Build.MANUFACTURER.equals("HiBy", ignoreCase = true) &&
            android.os.Build.MODEL.contains("M500", ignoreCase = true)
        // Emulator/VM screen — a different field set than PlayerHolder's own VM check on purpose.
        val product = android.os.Build.PRODUCT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val looksVirtual = product.contains("sdk") || product.contains("vbox") ||
            model.contains("emulator") || model.contains("android sdk built for")
        return realHardware && !looksVirtual
    }

    private fun play(list: List<Track>, index: Int) {
        ensurePlaybackService()
        // Every caller here passes an EXPLICIT, deliberately-ordered list (an album in disc order,
        // a search result, a pre-shuffled list from the Shuffle button, etc.) — ExoPlayer's own
        // shuffleModeEnabled reordering the timeline on top of that is never correct, only ever a
        // stale leftover from a PREVIOUS session's shuffle toggle. Confirmed live: exactly this —
        // shuffle left on from an earlier NowPlaying/Tape session got silently re-applied by those
        // screens' own "reapply persisted shuffle" effect (now removed), so tapping a track inside
        // an album view could jump to a completely unrelated next track. Album-style playback is
        // meant to run front-to-back like a CD/vinyl side — force it off on every explicit play.
        player.shuffleModeEnabled = false
        PlayerPreferences.saveShuffle(this, false)
        InstantRandom.clear()
        player.setMediaItems(list.map { mediaItemFor(it) }, index, 0L)
        player.prepare(); player.play()
        PlayerPreferences.saveQueue(this, list.map { it.id }, index)
        list.getOrNull(index)?.let { PlayerPreferences.saveLastPlayback(this, it.id, 0L) }
    }

    private fun ensurePlaybackService() {
        try {
            PlayerHolder.ensureSession(this)
            // Foreground start + a live MediaController connection. Plain startService() left the
            // service un-promoted, so the session/notification died on screen-off/background and
            // hardware+BT media keys only worked with the app foregrounded and the screen on.
            PlayerHolder.ensureControllerConnected(this)
        } catch (_: Throwable) {}
    }

    /** Force a MediaStore rescan of every storage volume, then refresh the query. */
    /**
     * Full library rescan — now a real foreground service ([[LibraryScanService]]), not a plain
     * background Thread owned by this Activity. Confirmed live (user report, 2026-08-17): a bare
     * Thread with no foreground service or wake lock is exactly what Android's background-
     * execution limits target — it would stall/silently stop making progress once the screen went
     * to sleep or the app left the foreground, with zero error surfaced. This just triggers the
     * service and gets out of the way; the walk/tag-read/pipelining logic itself is unchanged,
     * only WHERE it runs changed. [[ScanProgress]] stays the shared state ScannerPill/
     * ScanProgressDialog already poll — the `onDone` callback here is no longer how the UI learns
     * to refresh (see App()'s ScanProgress.generation poll below); it's kept only so existing call
     * sites that want an immediate "trigger acknowledged" tick still get one.
     */
    private fun rescan(onDone: () -> Unit) {
        if (ScanProgress.active) { onDone(); return }
        ContextCompat.startForegroundService(this, android.content.Intent(this, LibraryScanService::class.java))
        onDone()
    }

    private fun audioPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    /** Read-audio (gates the library) + RECORD_AUDIO (the visualizer) + notifications + video. */
    private fun permissionsToRequest(): Array<String> {
        val p = mutableListOf(audioPermission(), Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.POST_NOTIFICATIONS)
            p.add(Manifest.permission.READ_MEDIA_VIDEO)
            p.add(Manifest.permission.READ_MEDIA_IMAGES) // folder cover art via MediaStore.Images
        }
        return p.toTypedArray()
    }

    /** Compiled once. This used to be `Regex(...)` INSIDE the per-row loop: 16.6k compiles a walk. */
    private val LEADING_TRACK_NUMBER_RE = Regex("^(\\d{1,3})[\\s.\\-_]")

    /**
     * SINGLE FLIGHT. A rescan bumps ScanProgress.generation more than once (scan start, scan end),
     * each bump re-keys the produceState, and produceState cancels the previous coroutine, but a
     * blocking walk inside withContext cannot be interrupted, so the old walk ran to completion
     * while the new one started: TWO full MediaStore walks at once (seen live: tids 14580 and
     * 14581 both reporting 16609 rows, and the walk went from 18s to 58s). Now the second caller
     * waits for the first and takes its result.
     */
    private val queryLock = Any()
    @Volatile private var queryInFlight: List<Track>? = null

    private fun queryTracks(): List<Track> = synchronized(queryLock) { queryTracksLocked() }

    private fun queryTracksLocked(): List<Track> {
        val tQuery0 = android.os.SystemClock.elapsedRealtime()
        val out = ArrayList<Track>()
        val hasBitrate = Build.VERSION.SDK_INT >= 30
        val proj = arrayListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DATE_ADDED,
        ).apply { if (hasBitrate) add(MediaStore.Audio.Media.BITRATE) }.toTypedArray()
        contentResolver.safeQuery(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iS = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iM = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iPath = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val iYear = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val iAlbumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val iTrackNo = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val iAlbumArtist = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val iDateAdded = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val iBr = if (hasBitrate) c.getColumnIndex(MediaStore.Audio.Media.BITRATE) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val rawTrackNo = if (iTrackNo >= 0 && !c.isNull(iTrackNo)) c.getInt(iTrackNo) else 0
                var parsedTrackNo = if (rawTrackNo >= 1000) rawTrackNo % 1000 else rawTrackNo
                val discNo = if (rawTrackNo >= 1000) rawTrackNo / 1000 else 0
                val path = if (iPath >= 0 && !c.isNull(iPath)) c.getString(iPath) ?: "" else ""

                // MediaStore rows can outlive the actual file — deletes/moves done outside the
                // scanner (adb, PC file transfer, the ingest pipeline replacing a release) leave a
                // ghost row behind until the next full media rescan notices. Art can still resolve
                // for a ghost row (thumbnail cache, or a shared album id with a surviving track),
                // which is exactly the "art but no media" bug this skips: art belongs to the media,
                // never the other way around — no readable file, no track, full stop. Only verified
                // via File I/O on primary storage though — see mediaStoreRowLikelyValid()'s doc
                // comment for why blindly applying this to SD-card paths was mass-dropping a real
                // library down to a fraction of its actual size.
                if (!mediaStoreRowLikelyValid(path)) continue

                // 1. Fallback to local persistent database
                if (parsedTrackNo <= 0) {
                    parsedTrackNo = PlayerPreferences.loadTrackNumber(this@MainActivity, id)
                }

                // 2. Infer from leading filename digits if missing (e.g., "01 - Track.flac", "04. Title.mp3")
                if (parsedTrackNo <= 0 && path.isNotBlank()) {
                    val fname = path.substringAfterLast('/').substringBeforeLast('.')
                    val match = LEADING_TRACK_NUMBER_RE.find(fname)
                    val inferred = match?.groupValues?.get(1)?.toIntOrNull()
                    if (inferred != null && inferred > 0) {
                        parsedTrackNo = inferred
                    }
                }

                // 3. Save into local persistent database
                if (parsedTrackNo > 0) {
                    PlayerPreferences.saveTrackNumber(this@MainActivity, id, parsedTrackNo)
                }

                val albumArtist = if (iAlbumArtist >= 0 && !c.isNull(iAlbumArtist)) c.getString(iAlbumArtist) ?: "" else ""
                out.add(Track(
                    id, c.getString(iT) ?: "Unknown", FastLibraryStore.intern(c.getString(iA) ?: "Unknown artist"),
                    FastLibraryStore.intern(c.getString(iAl) ?: ""), c.getLong(iD), c.getLong(iS),
                    if (iBr >= 0 && !c.isNull(iBr)) c.getInt(iBr) / 1000 else 0, FastLibraryStore.intern(c.getString(iM) ?: ""),
                    path,
                    if (iYear >= 0 && !c.isNull(iYear)) c.getInt(iYear) else 0,
                    if (iAlbumId >= 0 && !c.isNull(iAlbumId)) c.getLong(iAlbumId) else 0L,
                    parsedTrackNo,
                    FastLibraryStore.intern(albumArtist),
                    if (iDateAdded >= 0 && !c.isNull(iDateAdded)) c.getLong(iDateAdded) else 0L,
                    discNumber = discNo
                ))
            }
        }
        val tQuery1 = android.os.SystemClock.elapsedRealtime()
        PlayerPreferences.flushTrackNumbers(this) // one batched write for every saveTrackNumber() call made during this pass, not one per track
        val tFlush = android.os.SystemClock.elapsedRealtime()
        // Whole-CD image rips: flag + label, split into virtual tracks where a cue sheet allows.
        val result = DiscImage.apply(this, out)
        val tDisc = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("HionePlayer", "queryTracks: mediastore=${tQuery1 - tQuery0}ms (${out.size} rows) " +
            "flush=${tFlush - tQuery1}ms discimage=${tDisc - tFlush}ms")
        // PERF (cold start): stamp this sweep so LibraryDaemonService can skip its own identical
        // "initial background sync" query — the two used to run concurrently on every launch.
        FastLibraryStore.noteFullQuery(result.size)
        return result
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
/** Automated SD card library rescans: triggers periodic deep scans (default: weekly, opt-in to daily) */
/** LOCAL SD-card rescan schedule — MediaScanner over the card, no network (the ingress engine is a
 *  separate switch, see HioneIngestGate). */
fun schedulePeriodicLibraryScan(ctx: android.content.Context) {
    val prefs = ctx.getSharedPreferences("hione_auto_scan", android.content.Context.MODE_PRIVATE)
    val lastScanTime = prefs.getLong("last_auto_scan_ms", 0L)
    val now = System.currentTimeMillis()
    val ONE_DAY = 24 * 60 * 60 * 1000L
    val ONE_WEEK = 7 * ONE_DAY

    // Trigger if last scan was > 1 week ago (or first time) — and only in a profile that allows
    // background work (never while the screen is off in audio-only / idle).
    if (now - lastScanTime > ONE_WEEK && HionePowerGovernor.allowBackgroundWork) {
        android.util.Log.i("MainActivity", "Triggering automated deep library scan...")
        HioneSyncTransceiver.deepScanDirectory(ctx, HioneSyncTransceiver.getSdMusicPath(ctx)) { count ->
            prefs.edit().putLong("last_auto_scan_ms", System.currentTimeMillis()).apply()
            if (count > 0) {
                android.util.Log.i("MainActivity", "Auto-scan completed: $count tracks")
            }
        }
    }
}

@Composable
private fun App(tracks: List<Track>, player: ExoPlayer, loading: Boolean = false, onScan: () -> Unit, onPlay: (List<Track>, Int) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { LikeStore.init(ctx) }
    val savedTabName = remember { PlayerPreferences.loadTab(ctx) }
    var tab by remember { mutableStateOf(runCatching { Tab.valueOf(savedTabName) }.getOrDefault(Tab.HOME)) }
    var artistSel by remember { mutableStateOf<ArtistGroup?>(null) }
    var albumSel by remember { mutableStateOf<AlbumGroup?>(null) }
    var libSel by remember { mutableStateOf<String?>(null) }
    var showVideoLibrary by remember { mutableStateOf(false) }
    var videoSel by remember { mutableStateOf<VideoItem?>(null) }
    val videos by produceState(initialValue = emptyList<VideoItem>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { queryVideos(ctx) }
    }

    val homeListState = rememberLazyListState()
    val songsListState = rememberLazyListState()
    val artistsListState = rememberLazyListState()
    val albumsGridState = rememberLazyGridState()
    val libraryListState = rememberLazyListState()
    val artistDetailListState = rememberLazyListState()

    var currentTrack by remember { mutableStateOf<Track?>(null) }
    // App-wide dynamic color: re-extract from the CURRENT track's art on every change.
    LaunchedEffect(currentTrack?.id) { HioneArtTheme.update(ctx, currentTrack) }
    // Instant RANDOM mode — one tap, playback within a few hundred ms (see InstantRandom).
    val startRandom: () -> Unit = {
        Haptics.tick(ctx)
        val ok = InstantRandom.start(ctx) { t -> currentTrack = t }
        if (!ok) android.widget.Toast.makeText(ctx, "The library is empty. Run a scan first.", android.widget.Toast.LENGTH_SHORT).show()
    }
    // Views are sticky: relaunching drops you back into whatever you were looking at last
    // (tape deck / full now playing / the lists), as soon as the track restores.
    val initialTape = (ctx as? MainActivity)?.intent?.getBooleanExtra("open_tape_mode", false) == true
    var showFullNowPlaying by remember { mutableStateOf(false) }
    var showTape by remember { mutableStateOf(initialTape) }
    var showSettings by remember { mutableStateOf(false) }
    // "Open" on an alarm notification (or Hione Clock's alarms link): jump straight to Settings,
    // Alarms tab, over whatever full-screen view was up.
    val openAlarms = MainActivity.openAlarmsRequest.value
    LaunchedEffect(openAlarms) {
        if (openAlarms) {
            showTape = false
            showFullNowPlaying = false
            settingsCategoryRequest.value = SettingsCategory.ALARMS
            showSettings = true
            MainActivity.openAlarmsRequest.value = false
        }
    }
    // Tape mode is a full-bleed deck skin — the Android status bar (clock, icons, etc.) sitting on
    // top of it reads as a stray overlay rather than part of the "device". Go immersive for as
    // long as tape mode is up, restore the system bars the moment it closes.
    LaunchedEffect(showTape) {
        val window = (ctx as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (showTape) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(showFullNowPlaying, showTape) {
        com.hione.player.screentime.HioneSmartScreenTimeEngine.isNowPlayingOrTapeActive = showFullNowPlaying || showTape
        HionePowerGovernor.noteNowPlayingVisible(showFullNowPlaying || showTape)
    }
    val appScope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(false) }
    var historyTick by remember { mutableStateOf(0) }

    // Warm the art cache for the whole library the moment it's known: first few hundred decode
    // straight into memory, the rest materialize as lossless WebP on disk — art is instant
    // everywhere, never extracted-on-scroll.
    LaunchedEffect(tracks) { if (tracks.isNotEmpty()) AlbumArtCache.prewarm(ctx, tracks) }

    var sortIgnoreThe by remember { mutableStateOf(PlayerPreferences.loadSortIgnoreThe(ctx)) }
    var displayTheMode by remember { mutableStateOf(PlayerPreferences.loadDisplayTheMode(ctx)) }

    // Group once per library change or preference update — off the main thread. This used to run
    // synchronously inside `remember{}` during composition; grouping+sorting ~3000 tracks with the
    // full normalization pipeline (regex passes, Unicode NFD folding) was slow enough there to trip
    // Android's ANR watchdog and make the app fail to launch outright on a real library. Keep the
    // previous list showing while a recompute is in flight rather than flashing empty.
    // `loading` (passed in) only covers the FIRST gap — before queryTracks() itself has resolved
    // at all. There's a SECOND gap after that: tracks is non-empty but this grouping pass hasn't
    // finished yet (real time on a big library — canonicalArtistKey's regex/NFD pipeline per
    // track), during which artistGroups/albumGroups are still the initial emptyList(). That
    // second gap is what was still flashing "NO ARTISTS/ALBUMS FOUND" even after the first fix —
    // confirmed live, not theoretical. hasGroupedOnce tracks "grouping has produced a real result
    // for the CURRENT non-empty tracks at least once", separately from hasAlbumGroupedOnce since
    // they're two independent LaunchedEffects that can finish at different times.
    var hasGroupedOnce by remember { mutableStateOf(false) }
    var artistGroups by remember { mutableStateOf(emptyList<ArtistGroup>()) }
    LaunchedEffect(tracks, sortIgnoreThe, displayTheMode) {
        // Defense in depth on top of the Normalizer fixes in Model.kt (root cause of the
        // live-confirmed "Crystal Method missing from Artists" bug: one bad artist string could
        // throw uncaught out of this whole eager grouping pass, silently freezing artistGroups on
        // a stale result forever with no error surfaced anywhere). Even with those fixed, nothing
        // else in this chain should be able to take the whole library's grouping down with it —
        // catch, log, and keep the previous result rather than get permanently stuck either way.
        runCatching {
            kotlinx.coroutines.withContext(HioneBackground.dispatcher) {
                tracks.artists(ctx, ignoreThe = sortIgnoreThe, displayMode = displayTheMode)
            }
        }.onSuccess { artistGroups = it; hasGroupedOnce = true }
         .onFailure { android.util.Log.e("HionePlayer", "artist grouping failed, keeping previous result", it) }
    }
    var hasAlbumGroupedOnce by remember { mutableStateOf(false) }
    var albumGroups by remember { mutableStateOf(emptyList<AlbumGroup>()) }
    LaunchedEffect(tracks, sortIgnoreThe) {
        val result = runCatching {
            kotlinx.coroutines.withContext(HioneBackground.dispatcher) {
                tracks.albums(ctx, ignoreThe = sortIgnoreThe)
            }
        }.onFailure { android.util.Log.e("HionePlayer", "album grouping failed, keeping previous result", it) }
         .getOrNull() ?: return@LaunchedEffect
        albumGroups = result
        hasAlbumGroupedOnce = true
    }
    val recentlyPlayed = remember(tracks, historyTick) {
        val byId = tracks.associateBy { it.id }
        PlayerPreferences.loadHistory(ctx).mapNotNull { byId[it] }.take(30)
    }

    // Restore last played track & position on launch. Keyed on `tracks` because it needs a
    // non-empty library to work with — but a library rescan ALSO changes `tracks` (even one that
    // finds nothing new can return a differently-ordered list), which used to re-run this whole
    // block on every rescan and could yank live playback back to a stale saved track/position.
    // Split accordingly: the re-sync branch below is read-only (safe to re-run any number of
    // times), the actual restore-from-prefs branch is guarded to run exactly once per process via
    // PlayerHolder.sessionRestored — see its definition for the full story.
    LaunchedEffect(tracks) {
        if (tracks.isEmpty()) return@LaunchedEffect
        if (player.mediaItemCount > 0) {
            // Activity was recreated (rotation/config change) while a queue was live — reflect the
            // current item, DON'T rebuild the queue (that would replace the album with the whole
            // library and seek backwards). Read-only: never touches playback, safe every time.
            if (currentTrack == null) {
                val id = player.currentMediaItem?.mediaId?.toLongOrNull()
                currentTrack = tracks.find { it.id == id }
            }
            return@LaunchedEffect
        }
        if (PlayerHolder.sessionRestored) return@LaunchedEffect
        if (currentTrack == null) {
            PlayerHolder.markSessionRestored()
            val lastPos = PlayerPreferences.loadLastPositionMs(ctx)
            val lastTrackId = PlayerPreferences.loadLastTrackId(ctx)
            val queueIds = PlayerPreferences.loadQueueIds(ctx)
            val byId = if (queueIds.isNotEmpty()) tracks.associateBy { it.id } else emptyMap()
            val restoredQueue = queueIds.mapNotNull { byId[it] }

            if (restoredQueue.isNotEmpty()) {
                // Find exact index of the last track in the queue, falling back to saved index
                val queueIdx = PlayerPreferences.loadQueueIndex(ctx).coerceIn(0, restoredQueue.size - 1)
                val targetIdx = if (lastTrackId > 0) {
                    val matchingIdx = restoredQueue.indexOfFirst { it.id == lastTrackId }
                    if (matchingIdx >= 0) matchingIdx else queueIdx
                } else queueIdx

                val restoredTrack = restoredQueue[targetIdx]
                currentTrack = restoredTrack
                val safePos = if (lastPos in 0..(restoredTrack.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) lastPos else 0L
                player.setMediaItems(restoredQueue.map { mediaItemFor(it) }, targetIdx, safePos)
                player.prepare()
                // Random mode persists: the saved shuffle flag is only ever TRUE when the queue was
                // built by InstantRandom (explicit album/list plays save FALSE), so restoring it
                // can't re-shuffle an album — it just brings random mode back with its dice lit.
                if (PlayerPreferences.loadShuffle(ctx)) { player.shuffleModeEnabled = true; InstantRandom.markActive() }
                // Restore queue + position ready-to-play but PAUSED. Auto-calling play() here
                // meant every process restart (any app reinstall/update, a cached-process kill)
                // resumed audio unprompted — the "music starts randomly on update" glitch.
                // Pressing play continues exactly where it left off.
            } else {
                val exact = tracks.find { it.id == lastTrackId } ?: tracks.firstOrNull()
                if (exact != null) {
                    currentTrack = exact
                    val idx = tracks.indexOf(exact).coerceAtLeast(0)
                    val safePos = if (lastPos in 0..(exact.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) lastPos else 0L
                    player.setMediaItems(tracks.map { mediaItemFor(it) }, idx, safePos)
                    player.prepare()
                    // Restore ready-to-play but PAUSED — see note above; never auto-resume on
                    // a process restart, which is what an app update looks like from here.
                }
            }
            UpdateOverlay.mode.value = UpdateOverlayMode.NONE
        }
    }

    // Persist exact track & timestamp continuously while playing
    LaunchedEffect(currentTrack, isPlaying) {
        while (isPlaying && currentTrack != null) {
            PlayerPreferences.saveLastPlayback(ctx, currentTrack!!.id, player.currentPosition)
            PlayerPreferences.saveQueueIndex(ctx, player.currentMediaItemIndex)
            kotlinx.coroutines.delay(1000)
        }
    }

    // The listener below is registered ONCE (keyed on player) but must always see the CURRENT
    // library — capturing the `tracks` parameter directly froze the first (empty) list in the
    // closure, so transitions stopped resolving to Tracks and Now Playing never updated live.
    val tracksNow by rememberUpdatedState(tracks)
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) {
                isPlaying = p
                com.hione.player.screentime.HioneSmartScreenTimeEngine.isAudioPlaying = p
                PlayerPreferences.saveWasPlaying(ctx, p)
                if (currentTrack != null) {
                    PlayerPreferences.saveLastPlayback(ctx, currentTrack!!.id, player.currentPosition)
                    PlayerPreferences.saveQueueIndex(ctx, player.currentMediaItemIndex)
                }
            }
            override fun onEvents(pl: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) {
                    val id = pl.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                    val itemIdx = pl.currentMediaItemIndex
                    PlayerPreferences.saveQueueIndex(ctx, itemIdx)
                    if (currentTrack?.id != id) {
                        tracksNow.find { it.id == id }?.let {
                            currentTrack = it
                            PlayerPreferences.saveLastPlayback(ctx, it.id, pl.currentPosition)
                            PlayerPreferences.recordPlay(ctx, it.id, System.currentTimeMillis())   // history + play count
                            kotlinx.coroutines.MainScope().launch { }
                        }
                    }
                }
            }
        }
        isPlaying = player.isPlaying   // seed from ongoing playback (e.g. after Activity recreation)
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // Broadcast the current track + play state to the list rows' now-playing indicator.
    LaunchedEffect(currentTrack?.id, isPlaying) {
        NowPlayingState.currentId = currentTrack?.id ?: -1L
        NowPlayingState.playing = isPlaying
    }

    // Location: a single coarse fix, only once a track has actually been playing ~6s (skips/seeks
    // don't trigger it), so it costs almost nothing. Cancelled automatically if the track changes.
    LaunchedEffect(currentTrack?.id) {
        val t = currentTrack ?: return@LaunchedEffect
        kotlinx.coroutines.delay(6000)
        if (currentTrack?.id == t.id && isPlaying) LocationLogger.logForTrack(ctx, t.id, System.currentTimeMillis())
    }

    // Screen orientation is locked strictly to PORTRAIT mode across the entire app lifecycle.
    // Tape Mode renders rotated 90° CW in portrait mode so the user holds the DAP sideways while Android OS stays in portrait.

    fun startPlay(list: List<Track>, i: Int) {
        currentTrack = list[i]
        PlayerPreferences.saveLastPlayback(ctx, list[i].id, 0L)
        onPlay(list, i)
    }

    val openArtistByName: (String) -> Unit = { name ->
        // Match by the SAME canonical key the Artists tab groups by, not a raw exact-string
        // compare — a tap can arrive with a raw per-track artist string (e.g. from an album
        // header, a track's metadata line) that differs from whatever display name the group
        // ended up with (casing, "The" placement, a collab string that only canonicalizes down
        // to the real artist). An exact-equals match would miss the real group and fall through
        // to a synthetic one built from a raw-string filter that has the exact same problem.
        val nameKey = canonicalArtistKey(name, ctx)
        val found = artistGroups.find { canonicalArtistKey(it.name, ctx) == nameKey }
            ?: ArtistGroup(name, tracks.filter { canonicalArtistKey(normalizeArtistName(it.artist), ctx) == nameKey })
        artistSel = found
        albumSel = null
        libSel = null
        showFullNowPlaying = false
        showTape = false
    }

    // Mirrors openArtistByName above, keyed on canonicalAlbumKey instead — album name alone isn't
    // unique (confirmed live: two different albums can format to the identical display string,
    // see the Gorillaz LazyGrid-crash fix), so this needs BOTH artist and album, not just a title.
    val openAlbumByName: (String, String) -> Unit = { artist, album ->
        val albumKey = canonicalAlbumKey(artist, album, ctx)
        val found = albumGroups.find { canonicalAlbumKey(it.artist, it.name, ctx) == albumKey }
            ?: AlbumGroup(album, artist, tracks.filter { canonicalAlbumKey(it.albumArtist.ifBlank { it.artist }, it.album, ctx) == albumKey })
        albumSel = found
        artistSel = null
        libSel = null
        showFullNowPlaying = false
        showTape = false
    }

    var showScanDialog by remember { mutableStateOf(false) }
    var showHioneMonitorDialog by remember { mutableStateOf(false) }
    var showStorageModal by remember { mutableStateOf(false) }

    // Pop the same priority chain the `when` below already renders in (deepest overlay first),
    // one level at a time — shared by the system-back handler and the bottom-bar back glyph so
    // there's exactly one definition of "what does back mean right now".
    val canGoBack = showScanDialog || showHioneMonitorDialog || showSettings || showTape || showFullNowPlaying || videoSel != null || showVideoLibrary || albumSel != null || artistSel != null || libSel != null || tab == Tab.SONGS || tab != Tab.HOME
    val performBack: () -> Unit = {
        when {
            showScanDialog -> showScanDialog = false
            showHioneMonitorDialog -> showHioneMonitorDialog = false
            showSettings -> showSettings = false
            showTape -> showTape = false
            showFullNowPlaying -> showFullNowPlaying = false
            videoSel != null -> videoSel = null
            showVideoLibrary -> showVideoLibrary = false
            albumSel != null -> albumSel = null
            artistSel != null -> artistSel = null
            libSel != null -> libSel = null
            // All Songs isn't its own bar tab anymore — it's reached from Library, so back from it
            // returns to Library rather than falling through to app-exit.
            tab == Tab.SONGS -> tab = Tab.GENRES
            tab != Tab.HOME -> tab = Tab.HOME
            else -> {
                val act = ctx as? android.app.Activity
                act?.moveTaskToBack(true)
                try {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    }
                    ctx.startActivity(homeIntent)
                } catch (_: Throwable) {}
            }
        }
    }
    // System back — hardware key AND the Pixel-style edge-swipe gesture — smoothly pops nested screens
    // and falls back to system gesture navigation / minimize to launcher when at root.
    androidx.activity.compose.BackHandler(enabled = true, onBack = performBack)

    DisposableEffect(Unit) {
        MainActivity.globalBackHandler = performBack
        onDispose {
            MainActivity.globalBackHandler = null
        }
    }

    // Header + tab bar now float over the scrolling content as a real frosted-glass panel
    // (genuine Haze backdrop blur of whatever's currently scrolled underneath them) instead of
    // sitting in normal document flow above it — the authentic "Pixel glass app bar" look needs
    // content to actually pass behind the bar for the blur to show anything. Content gets top
    // padding equal to the bar's own measured height (it changes — the tab row only shows on the
    // top-level tabs, not in Artist/Album detail) so nothing starts out hidden underneath it.
    val hazeState = remember { HazeState() }
    val artGround = HioneArtTheme.colors().ground
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxSize()
            // (Edge-swipe back used to be handled here too. Removed: the HioneOS SystemUI gesture service
            // owns the screen edges system-wide; a second handler only fought it and ate the bottom/side
            // gesture zones. System back still lands via BackHandler below.)
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).hioneHazeSource(ctx, hazeState).padding(top = with(density) { headerHeightPx.toDp() })) {
                // Same priority chain the old hard-cut `when` rendered in, snapshotted into an
                // immutable route so the outgoing screen can finish its exit animation from its
                // own captured data (see ContentRoute's doc comment).
                val route: ContentRoute = when {
                    albumSel != null -> ContentRoute.Album(albumSel!!)
                    artistSel != null -> ContentRoute.Artist(artistSel!!)
                    libSel != null -> ContentRoute.Lib(libSel!!)
                    showVideoLibrary -> ContentRoute.Videos
                    else -> ContentRoute.TabPage(tab)
                }
                AnimatedContent(
                    targetState = route,
                    modifier = Modifier.fillMaxSize(),
                    contentKey = { it.key },
                    transitionSpec = { mainContentTransition() },
                    label = "mainContent"
                ) { r ->
                    when (r) {
                    is ContentRoute.Album -> DetailList(r.group.name, r.group.tracks, { albumSel = null }, ::startPlay, likeAlbum = r.group.name, onOpenArtist = openArtistByName)
                    is ContentRoute.Artist -> ArtistDetail(r.group, { artistSel = null }, { albumSel = it }, ::startPlay, listState = artistDetailListState)
                    is ContentRoute.Lib -> if (r.name == com.hione.player.wanted.WANTED_LIB_KEY) {
                        com.hione.player.wanted.WantedScreen(tracks, onBack = { libSel = null }, onPlay = ::startPlay)
                    } else {
                        val isLiked = r.name == LIKED_KEY
                        val libTracks = if (isLiked) LikeStore.resolveLiked(ctx, tracks)
                                        else { val ids = PlayerPreferences.loadPlaylists(ctx)[r.name]?.toSet() ?: emptySet(); tracks.filter { it.id in ids } }
                        DetailList(if (isLiked) "Liked Songs" else r.name, libTracks, { libSel = null }, ::startPlay, onOpenArtist = openArtistByName,
                            footer = if (isLiked) { { com.hione.player.wanted.WantedNotInLibrarySection(onOpenAll = { libSel = com.hione.player.wanted.WANTED_LIB_KEY }) } } else null)
                    }
                    ContentRoute.Videos -> VideoLibraryScreen(videos, onOpen = { videoSel = it }, onClose = { showVideoLibrary = false })
                    is ContentRoute.TabPage -> when (r.tab) {
                    Tab.HOME -> HomeScreen(
                        tracks, recentlyPlayed, { tab = Tab.ARTISTS }, ::startPlay, listState = homeListState,
                        albumGroups = albumGroups, artistGroups = artistGroups,
                        onOpenAlbum = { albumSel = it }, onOpenArtist = openArtistByName,
                        onRandom = startRandom
                    )
                    Tab.SONGS -> SongList(tracks, sortIgnoreThe = sortIgnoreThe, onPlay = ::startPlay, listState = songsListState)
                    Tab.ARTISTS -> ArtistList(
                        artists = artistGroups,
                        loading = loading || (tracks.isNotEmpty() && !hasGroupedOnce),
                        sortIgnoreThe = sortIgnoreThe,
                        displayTheMode = displayTheMode,
                        onUpdateSort = { ignore ->
                            sortIgnoreThe = ignore
                            PlayerPreferences.saveSortIgnoreThe(ctx, ignore)
                        },
                        onUpdateDisplay = { mode ->
                            displayTheMode = mode
                            PlayerPreferences.saveDisplayTheMode(ctx, mode)
                        },
                        onOpen = { artistSel = it },
                        onShuffle = { if (it.tracks.isNotEmpty()) startPlay(it.tracks.shuffled(), 0) },
                        listState = artistsListState
                    )
                    Tab.ALBUMS -> AlbumGrid(
                        albumGroups, loading = loading || (tracks.isNotEmpty() && !hasAlbumGroupedOnce),
                        onOpen = { albumSel = it },
                        onShuffle = { if (it.tracks.isNotEmpty()) startPlay(it.tracks.shuffled(), 0) },
                        onPlay = { if (it.tracks.isNotEmpty()) startPlay(sortAlbumTracks(it.tracks), 0) },
                        gridState = albumsGridState
                    )
                    Tab.GENRES -> LibraryScreen(
                        ctx, listState = libraryListState, trackCount = tracks.size, tracks = tracks,
                        artistCount = artistGroups.size, albumCount = albumGroups.size,
                        loading = loading, videoCount = videos.size,
                        onAllSongs = { tab = Tab.SONGS },
                        onVideos = { showVideoLibrary = true }
                    ) { libSel = it }
                    }
                    }
                }
            }
            if (currentTrack != null) NowPlayingBar(
                track = currentTrack!!,
                player = player,
                isPlaying = isPlaying,
                onBarClick = {
                    showTape = false
                    showFullNowPlaying = true
                },
                onToggle = { if (player.isPlaying) player.pause() else player.play() },
                onArtistClick = openArtistByName,
                onAlbumClick = openAlbumByName
            )
            // Hione Radio station sheet (taste/ForYouShelf.kt) — hosted here so it can open from
            // any screen's long-press menu / radio button, not just Home.
            com.hione.player.taste.StationHost()
        }
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .onSizeChanged { headerHeightPx = it.height }
                .hioneGlassPanel(ctx, hazeState, HazeMaterials.thick(artGround), artGround)
        ) {
            Header(
                count = tracks.size,
                onScan = {
                    // Track-count pill = the filesystem/storage display. The ingress engine has its
                    // own modal (HioneMonitorModal) reached from Settings → Ingress Staging Telemetry.
                    if (ScanProgress.active) {
                        showScanDialog = true
                    } else {
                        showStorageModal = true
                    }
                },
                onRandom = startRandom,
                onTape = {
                    showFullNowPlaying = false
                    if (currentTrack == null && tracks.isNotEmpty()) {
                        startPlay(tracks, 0)
                    }
                    showTape = true
                },
                onSettings = { showSettings = true },
                canGoBack = canGoBack,
                onBack = performBack
            )
            // The one-time Last.fm offer. Arms itself only after 90 seconds of actual playback, so
            // a fresh install plays music first and asks second. See ScrobbleSetupOffer.
            com.hione.player.scrobble.ScrobbleSetupOffer(player) { showSettings = true }
            if (artistSel == null && albumSel == null) TabBar(tab) {
                tab = it
                PlayerPreferences.saveTab(ctx, it.name)
                artistSel = null
                albumSel = null
            }
        }
        // Overlay screens animate in/out instead of hard-cutting — each keeps rendering through
        // its exit via AnimatedVisibility, so the content lambdas guard with ?.let rather than !!
        // (currentTrack can drop null mid-exit and the last frames must not crash).
        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn(tween(200)) + slideInVertically(spring(dampingRatio = 0.9f, stiffness = 900f)) { it / 14 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { it / 18 }
        ) {
            SettingsScreen(ctx = ctx, tracks = tracks, onClose = { showSettings = false })
        }
        // Rise-from-the-bar: full Now Playing grows up out of the bottom edge — where the mini
        // bar it expands from lives — rather than swapping in. Offset kept modest (it/4) because
        // the projectM GLSurfaceView inside punches a window hole that can trail the layout by a
        // frame while moving; a short rise reads as expansion without exposing that.
        AnimatedVisibility(
            visible = showFullNowPlaying && currentTrack != null && !showTape,
            enter = slideInVertically(spring(dampingRatio = 0.92f, stiffness = 600f)) { it / 4 } +
                fadeIn(tween(200)) + scaleIn(initialScale = 0.97f, animationSpec = tween(200)),
            exit = slideOutVertically(tween(190, easing = FastOutLinearInEasing)) { it / 5 } + fadeOut(tween(160))
        ) {
            currentTrack?.let { np ->
                NowPlayingScreen(track = np, player = player, onClose = { showFullNowPlaying = false },
                    onTape = { showTape = true }, tracks = tracks, onOpenArtist = openArtistByName, onOpenAlbum = openAlbumByName)
            }
        }
        // Tape is its own rotated full-bleed deck — a directional slide would fight its sideways
        // orientation, so it simply fades like a deck door closing over the app.
        AnimatedVisibility(
            visible = showTape && currentTrack != null,
            enter = fadeIn(tween(240)),
            exit = fadeOut(tween(200))
        ) {
            currentTrack?.let { TapeScreen(track = it, player = player, onExit = { showTape = false }) }
        }
        if (videoSel != null) {
            VideoPlayerScreen(video = videoSel!!, musicPlayer = player, onClose = { videoSel = null })
        }
        if (showHioneMonitorDialog) {
            HioneMonitorModal(
                onDismissRequest = { showHioneMonitorDialog = false }
            )
        }
        if (showStorageModal) {
            HioneStorageSettingsModal(onDismissRequest = { showStorageModal = false })
        }
        if (showScanDialog) {
            ScanProgressDialog(hazeState = hazeState, onDismissRequest = { showScanDialog = false })
        }
        // Persistent, unobtrusive version tag — now the bottom-RIGHT corner (was sharing the
        // left corner with the back glyph, which crowded a control right next to plain status
        // text). Hidden entirely in tape mode along with the back glyph below — tape mode is its
        // own full-bleed deck UI with its own exit control, this corner strip doesn't belong there.
        if (!showTape) {
            val infinitePulse = rememberInfiniteTransition(label = "verPulse")
            val verColor by infinitePulse.animateColor(
                initialValue = Color(0x9989ACA7),
                targetValue = HioneTealBright.copy(alpha = 0.85f),
                animationSpec = infiniteRepeatable(
                    animation = tween(2500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "verColor"
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = verColor,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 2.dp)
            )
        }
        // Cold-BOOT splash: once per device boot, NOT once per process. Android trims the
        // player as an "empty" cached process whenever no FGS is up (seen: am_kill ... empty),
        // so a per-process flag replayed the whole splash every time the app was re-opened.
        val splashCtx = androidx.compose.ui.platform.LocalContext.current
        var showSplash by remember { mutableStateOf(shouldShowBootSplash(splashCtx)) }
        if (showSplash) {
            HioneAnimatedBootSplash(onFinished = {
                markBootSplashShown(splashCtx)
                showSplash = false
            })
        }

        FnTouchGuard()
    }
}

private var hasShownColdBootSplash = false

/** Wall-clock moment this boot started, to the nearest 5 min - stable across process restarts,
 *  changes on every reboot. (boot_id under /proc is not readable from an app process.) */
private fun bootEpochBucket(): Long =
    (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 300_000L

private fun shouldShowBootSplash(ctx: android.content.Context): Boolean {
    if (hasShownColdBootSplash) return false
    val p = ctx.getSharedPreferences("hione_boot_splash", android.content.Context.MODE_PRIVATE)
    val last = p.getLong("boot_bucket", Long.MIN_VALUE)
    return kotlin.math.abs(last - bootEpochBucket()) > 1   // >5-10 min apart = a different boot
}

private fun markBootSplashShown(ctx: android.content.Context) {
    hasShownColdBootSplash = true
    ctx.getSharedPreferences("hione_boot_splash", android.content.Context.MODE_PRIVATE)
        .edit().putLong("boot_bucket", bootEpochBucket()).apply()
}

@Composable
private fun HioneAnimatedBootSplash(onFinished: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    var currentPose by remember { mutableStateOf(1) } // 1: Cheering Welcome, 2: Peace Sign Wink

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(700, easing = LinearOutSlowInEasing),
        label = "splashAlpha",
        finishedListener = { if (it == 0f) onFinished() }
    )

    // Breathing pulse scale for the background artwork
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scalePulse"
    )

    // Animated particle drift offset
    val particleY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particleDrift"
    )

    // Pulsing neon equalizer bars
    val eqBar1 by infiniteTransition.animateFloat(0.3f, 0.9f, infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "eq1")
    val eqBar2 by infiniteTransition.animateFloat(0.85f, 0.2f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "eq2")
    val eqBar3 by infiniteTransition.animateFloat(0.4f, 1.0f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "eq3")
    val eqBar4 by infiniteTransition.animateFloat(0.9f, 0.35f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "eq4")
    val eqBar5 by infiniteTransition.animateFloat(0.25f, 0.88f, infiniteRepeatable(tween(580), RepeatMode.Reverse), label = "eq5")

    LaunchedEffect(Unit) {
        delay(1100)
        currentPose = 2 // Keyframe transition to Peace Sign Wink pose!
        delay(1600)
        visible = false // Smooth fade out
    }

    if (alpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(alpha)
                .background(Mascot.color(0xFF041416))
        ) {
            // Fullscreen Bespoke Kawaii Hione Artwork with Crossfade Pose Animation
            Crossfade(
                targetState = currentPose,
                animationSpec = tween(600, easing = FastOutSlowInEasing),
                label = "poseCrossfade"
            ) { pose ->
                Image(
                    painter = mascotPainter(
                        id = if (pose == 1) R.drawable.hione_boot_splash else R.drawable.hione_boot_splash_pose2
                    ),
                    contentDescription = "${Mascot.name} Boot Splash Pose",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(scale)
                )
            }

            // Dark gradient overlay for text legibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Mascot.color(0x77041416),
                                Mascot.color(0x11041416),
                                Mascot.color(0x88041416),
                                Mascot.color(0xF5041416)
                            )
                        )
                    )
            )

            // Animated drifting sparkles, hearts & neon musical note icons
            Box(Modifier.fillMaxSize()) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = HioneTealBright.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 28.dp)
                        .offset(y = particleY.dp)
                        .size(30.dp)
                )
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = HionePink.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 36.dp)
                        .offset(y = (particleY * 1.3f).dp)
                        .size(28.dp)
                )
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = HioneGold.copy(alpha = 0.8f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 90.dp, end = 50.dp)
                        .offset(y = (particleY * 0.8f).dp)
                        .size(24.dp)
                )
            }

            // Bottom Kawaii Branding & Pulsing Equalizer Loading Bar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 36.dp)
            ) {
                // Neon Pulsing Title Card
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.horizontalGradient(listOf(Mascot.color(0xE60A2B2E), Mascot.color(0xE61C081A))))
                        .border(1.5.dp, Brush.horizontalGradient(listOf(HioneTealBright, HionePink)), RoundedCornerShape(20.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${Mascot.musicAppName.uppercase()} PLAYER",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = if (currentPose == 1) "${Mascot.fullNameJa} · STARTING" else "STARTING UP",
                            color = if (currentPose == 1) HioneTealBright else HionePink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Prominent Large Glanceable Version Number for DAP Startup
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = HioneTealBright,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont,
                    letterSpacing = 3.sp
                )

                Spacer(Modifier.height(14.dp))

                // Kawaii Animated Equalizer Wave
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.height(24.dp)
                ) {
                    listOf(eqBar1, eqBar2, eqBar3, eqBar4, eqBar5).forEachIndexed { index, heightFactor ->
                        Box(
                            modifier = Modifier
                                .width(5.dp)
                                .fillMaxHeight(heightFactor)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (index % 2 == 0) HioneTealBright else HionePink)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Wide "triple chevron" back glyph — three "<" arrowheads of different sizes, heavily overlapped
 * around a shared center so they read as ONE bold, wide arrow with depth/motion to it, not three
 * separate back-icons sitting in a row. The two outer chevrons are smaller and fixed teal, fanned
 * out vertically behind the center one; the center chevron is bigger and carries the live pulsing
 * color, drawn last so it sits on top and anchors the whole shape.
 */
@Composable private fun TripleChevronBackGlyph(centerColor: Color, outerColor: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun chevron(cx: Float, cy: Float, armW: Float, armH: Float, strokeW: Float, color: Color) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx + armW, cy - armH)
                lineTo(cx - armW, cy)
                lineTo(cx + armW, cy + armH)
            }
            drawPath(
                path, color,
                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        val strokeW = h * 0.22f
        // Outer pair — smaller, fixed teal, fanned above/below and trailing right of center to
        // give the whole glyph width and a sense of layered motion.
        chevron(w * 0.66f, h * 0.22f, w * 0.30f, h * 0.30f, strokeW * 0.75f, outerColor.copy(alpha = 0.85f))
        chevron(w * 0.66f, h * 0.78f, w * 0.30f, h * 0.30f, strokeW * 0.75f, outerColor.copy(alpha = 0.85f))
        // Center — larger, the live pulsing color, drawn on top so it reads as the "main" arrow.
        chevron(w * 0.56f, h * 0.5f, w * 0.42f, h * 0.46f, strokeW, centerColor)
    }
}

/**
 * In-app pocket lock workaround: watches the M500's Fn toggle (Settings.Global "fn_status") and,
 * while it's on, swallows touch events within this app.
 * NOTE: This is a temporary application-level workaround. The true fix is the system-level OS mod
 * to HiBy's AOSP framework (services.jar / PhoneWindowManager), which will natively intercept and
 * block BOTH touch events AND physical keys system-wide across all apps.
 */
@Composable private fun FnTouchGuard() {
    val ctx = LocalContext.current
    var fnLocked by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val cr = ctx.contentResolver
        fun read() = try { android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1 } catch (_: Throwable) { false }
        fnLocked = read()
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { fnLocked = read() }
        }
        try { cr.registerContentObserver(android.provider.Settings.Global.getUriFor("fn_status"), false, obs) } catch (_: Throwable) {}
        onDispose { try { cr.unregisterContentObserver(obs) } catch (_: Throwable) {} }
    }
    // HiBy's handler sometimes writes fn_status through a path the observer never hears about, so
    // unlocking looked laggy. While LOCKED we poll every 150ms (a Settings read is sub-ms cheap):
    // release is now effectively instant; a lazy 1s poll while unlocked catches silent locks too.
    LaunchedEffect(fnLocked) {
        val cr = ctx.contentResolver
        while (true) {
            kotlinx.coroutines.delay(if (fnLocked) 150 else 1000)
            val v = try { android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1 } catch (_: Throwable) { false }
            if (v != fnLocked) fnLocked = v
        }
    }
    // Ephemeral fullscreen glow on every lock/unlock TRANSITION — the lock instantly swallows
    // every touch with only a small top pill as explanation, which from the user's seat looks
    // exactly like the app hung. A loud, momentary, self-fading glow right when it happens (and
    // again when it releases) makes it read as "the device just did something" instead of
    // "the app died". Skips the very first composition so app launch itself doesn't flash.
    val flash = remember { Animatable(0f) }
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(fnLocked) {
        if (isFirstComposition) { isFirstComposition = false; return@LaunchedEffect }
        flash.snapTo(1f)
        flash.animateTo(0f, animationSpec = tween(1400, easing = FastOutSlowInEasing))
    }
    if (flash.value > 0f) {
        val glowColor = if (fnLocked) HionePink else HioneTealBright
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = flash.value }
                .drawBehind {
                    val w = size.width; val h = size.height
                    val edge = 110.dp.toPx()
                    drawRect(Brush.verticalGradient(listOf(glowColor.copy(alpha = 0.6f), Color.Transparent), startY = 0f, endY = edge))
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, glowColor.copy(alpha = 0.6f)), startY = h - edge, endY = h),
                        topLeft = Offset(0f, h - edge), size = Size(w, edge)
                    )
                    drawRect(Brush.horizontalGradient(listOf(glowColor.copy(alpha = 0.6f), Color.Transparent), startX = 0f, endX = edge))
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, glowColor.copy(alpha = 0.6f)), startX = w - edge, endX = w),
                        topLeft = Offset(w - edge, 0f), size = Size(edge, h)
                    )
                }
        ) {
            // Center caption rides along with the glow so the FIRST lock (before anyone's learned
            // the pill) is unambiguous — fades out with everything else, not a persistent nag.
            Row(
                Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(20.dp)).background(Mascot.color(0xCC061417))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (fnLocked) Icons.Default.Lock else Icons.Default.LockOpen, null, tint = glowColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (fnLocked) "Fn LOCK ON" else "Fn LOCK OFF",
                    color = glowColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
        }
    }
    if (fnLocked) {
        val cr = ctx.contentResolver
        // Skip the in-app touch-eating overlay ONLY when the digitizer is genuinely inhibited at
        // the platform level (InputManager reported a device disabled). This used to be gated on
        // the "root enabled" preference, which defaults to true and has nothing to do with whether
        // touch is actually blocked - so on any non-touch_and_key_lock mismatch the overlay went
        // missing while touch stayed live.
        val isHardwareTouchLock = HioneInputLock.touchHardwareLocked &&
            (android.provider.Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock") != "key_lock"
        if (!isHardwareTouchLock) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
                }
            ) {
                Row(
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Mascot.color(0xB3061417))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, null, tint = HioneTeal, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fn LOCK", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
            }
        }
    }
}

@Composable private fun ScannerPill(
    count: Int,
    onScan: () -> Unit
) {
    var deltaBadge by remember { mutableStateOf<Int?>(null) }

    // Poll the cross-thread ScanProgress instead of a fake fixed-duration spinner — the walk
    // phase alone can run from under a second (nothing new) to minutes (a freshly-dropped 2TB
    // card), so the UI has to reflect the REAL state, not a guessed timeout.
    var isScanning by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var visited by remember { mutableIntStateOf(0) }
    var newFound by remember { mutableIntStateOf(0) }
    var tagsScanned by remember { mutableIntStateOf(0) }
    var tagsTotal by remember { mutableIntStateOf(0) }
    var wasScanning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            isScanning = ScanProgress.active
            phase = ScanProgress.phase
            visited = ScanProgress.visited.get()
            newFound = ScanProgress.newFound.get()
            tagsScanned = ScanProgress.tagsScanned.get()
            tagsTotal = ScanProgress.tagsTotal
            // Show the "+N" result badge exactly once, on the falling edge of an actual scan —
            // driven by ScanProgress's own authoritative resultDelta, not by watching the raw
            // library `count` drift (that badge used to key off `count` directly, which could
            // change for reasons that have nothing to do with a scan — e.g. an external ingest
            // pipeline dropping files onto the card between our own scans — and a plain library-
            // reload afterward. That read as "stuck showing +3397 forever": the LaunchedEffect kept
            // restarting its 4s dismiss timer every time `count` so much as twitched, so it never
            // got a clean run to actually clear).
            if (wasScanning && !isScanning && ScanProgress.resultDelta != 0) {
                deltaBadge = ScanProgress.resultDelta
            }
            wasScanning = isScanning
            // Header (and this pill) stay mounted for the app's entire foreground lifetime — a
            // fixed 120ms poll ran continuously even on a totally idle screen, burning CPU/battery
            // for no reason. Only tighten to 120ms while a scan is actually active (or juuust
            // finished, so the falling-edge +N badge above still lands within one tick); back off
            // to 1.5s otherwise. The ~1.4s worst-case delay before noticing a fresh scan start is
            // imperceptible — scans are only ever started by the user's own tap on this pill.
            delay(if (isScanning) 120L else 1500L)
        }
    }
    LaunchedEffect(deltaBadge) {
        if (deltaBadge != null) { delay(4000); deltaBadge = null }
    }

    // PERF (scroll jank, 2026-09-17): these two infinite transitions used to be created
    // unconditionally — i.e. 60 animation frames a second for the entire life of the app — and
    // `spinAngle` was unwrapped with `by` and read in this composable's BODY
    // (`.rotate(spinAngle)` on the refresh icon). Since the pill lives in the always-visible
    // header that sits on top of every list screen, that meant ScannerPill recomposed 60 times a
    // second, forever, whether or not a scan was running, competing with list scrolling for the
    // main thread. Now the transition only EXISTS while a scan is actually running (same
    // conditional-composable-call pattern TechBadgeChip already uses for its shimmer), and both
    // values are read from the layer/draw phase so even during a scan nothing recomposes for them.
    val scanAnim: Pair<androidx.compose.runtime.State<Float>, androidx.compose.runtime.State<Float>>? = if (isScanning) {
        val rotationTransition = rememberInfiniteTransition(label = "spin")
        val spin = rotationTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "spinAngle"
        )
        val aura = rotationTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "auraAlpha"
        )
        spin to aura
    } else null

    // Same 3D-embossed-key language as HapticIconButton/DataChip: raised face lit from the top +
    // drop shadow at rest, springy scale + inverted sunk lighting while held, haptic tick on tap
    // — every other control in the app already gets this, the scan pill was the odd one out.
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(), label = "scanPillScale")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hapticCtx = androidx.compose.ui.platform.LocalContext.current

    val syncState by HioneSyncTransceiver.state.collectAsState()

    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val effectiveScale = 1.0f + (fontScale - 1.0f) * 0.20f
    val pillFontSize = (9.5f / fontScale * effectiveScale).sp

    fun abbrev(n: Int): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f).removeSuffix(".0M") + "M"
        n >= 10_000 -> "${n / 1000}k"
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000f).removeSuffix(".0k") + "k"
        else -> n.toString()
    }
    val statusText = when {
        syncState.isTransferring -> "${String.format(java.util.Locale.US, "%.1f", syncState.transferRateMBs)} MB/s"
        !isScanning -> abbrev(count)
        phase == "Reading tags…" && tagsTotal > 0 -> "${abbrev(tagsScanned)}/${abbrev(tagsTotal)}"
        else -> abbrev(visited)
    }

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(RoundedCornerShape(16.dp))
            .drawBehind {
                val rr = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                val topC = if (isScanning) Mascot.color(0xFF1C5A57) else Mascot.color(0xFF123634)
                val botC = if (isScanning) Mascot.color(0xFF4A1638) else Mascot.color(0xFF1C0D18)
                if (!pressed) {
                    drawRoundRect(Color(0x66000000), topLeft = Offset(0f, 2.2f), size = size, cornerRadius = rr)
                    drawRoundRect(Brush.verticalGradient(listOf(topC, botC)), cornerRadius = rr)
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0x73FFFFFF), Color(0x00FFFFFF)), endY = size.height * 0.6f),
                        cornerRadius = rr, style = Stroke(1.2f)
                    )
                } else {
                    drawRoundRect(Brush.verticalGradient(listOf(botC, topC)), cornerRadius = rr)
                    drawRoundRect(Color(0x73000000), topLeft = Offset(1f, 1f),
                        size = androidx.compose.ui.geometry.Size(size.width - 2f, size.height - 2f),
                        cornerRadius = rr, style = Stroke(2.2f))
                }
                drawRoundRect(
                    Brush.horizontalGradient(
                        // Draw-phase read of the aura animation (it was already read here, so
                        // this one never cost a recomposition) — .value because the transition is
                        // now conditional and handed around as a State rather than unwrapped.
                        if (isScanning) {
                            val aa = scanAnim?.second?.value ?: 0.35f
                            listOf(HioneTealBright.copy(alpha = aa), HionePink.copy(alpha = aa))
                        }
                        else listOf(HioneTeal.copy(alpha = 0.6f), HionePink.copy(alpha = 0.4f))
                    ),
                    cornerRadius = rr, style = Stroke(1.2.dp.toPx())
                )
            }
            .clickable(interactionSource = interaction, indication = null) {
                Haptics.click(hapticCtx)
                onScan()
            }
            .padding(horizontal = 7.dp, vertical = 3.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Scan library",
                tint = if (isScanning) HioneTealBright else HioneTeal,
                modifier = Modifier
                    .size(13.dp)
                    // graphicsLayer, not .rotate(): the angle is read in the LAYER phase, so the
                    // 60 Hz spin no longer recomposes the pill (see the note above).
                    .graphicsLayer { rotationZ = scanAnim?.first?.value ?: 0f }
            )
            Spacer(Modifier.width(4.dp))
            Text(
                statusText,
                color = Color.White,
                fontSize = pillFontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (deltaBadge != null && deltaBadge != 0) {
                val d = deltaBadge!!
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (d > 0) Mascot.color(0xFF0E4035) else Mascot.color(0xFF4A1024))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (d > 0) "+$d" else "$d",
                        color = if (d > 0) HioneTealBright else HionePink,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable private fun HioneMonitorDialog(
    hazeState: HazeState,
    tracks: List<Track>,
    onStartFullScan: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val syncState by HioneSyncTransceiver.state.collectAsState()
    var isScanningIngress by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(onBack = onDismissRequest)

    Box(
        Modifier.fillMaxSize()
            .background(Color(0x73000000))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.92f)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
                .clip(RoundedCornerShape(22.dp))
                .hioneGlassPanel(ctx, hazeState, HazeMaterials.thick(Ground), Ground)
                .background(Brush.verticalGradient(listOf(Mascot.color(0xE6072328), Mascot.color(0xF2031114))))
                .border(1.dp, HioneTealBright.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                .padding(18.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = mascotPainter(R.drawable.ic_hione_monitor_status),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${Mascot.name} Monitor",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (syncState.isTransferring) HionePink.copy(alpha = 0.3f) else HioneTeal.copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (syncState.isTransferring) "SYNCING" else "DAEMON ONLINE",
                        color = if (syncState.isTransferring) HionePink else HioneTealBright,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Storage and Library Stats
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(10.dp)
                ) {
                    Column {
                        Text("TRACKS", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("${tracks.size}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(10.dp)
                ) {
                    Column {
                        Text("ARTISTS", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("${tracks.map { it.artist }.distinct().size}", color = HioneTealBright, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(10.dp)
                ) {
                    Column {
                        Text("ALBUMS", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("${tracks.map { it.album }.distinct().size}", color = HionePink, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ============================================================
            // PC Daemon Reconciliation Telemetry (m500d)
            // ============================================================
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, if (syncState.daemon.online) HioneTealBright.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("PC SYNC", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Text(
                            if (syncState.daemon.online) "CONNECTED (${syncState.daemon.host})" else "OFFLINE",
                            color = if (syncState.daemon.online) HioneTealBright else Color.Gray,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    if (syncState.daemon.online) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Server Library", color = Muted, fontSize = 11.sp)
                            Text("${syncState.daemon.serverAudioCount} tracks", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Card Storage", color = Muted, fontSize = 11.sp)
                            Text("${syncState.daemon.cardAudioCount} tracks", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        if (syncState.daemon.stagingAudioCount > 0) {
                            Spacer(Modifier.height(2.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Staged", color = Muted, fontSize = 11.sp)
                                Text("${syncState.daemon.stagingAudioCount} files", color = HionePink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (syncState.daemon.isTransferring) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Sending: ${syncState.daemon.currentArtist} - ${syncState.daemon.currentAlbum}",
                                color = HionePink,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            "m500d daemon listening on port 8787",
                            color = Muted.copy(alpha = 0.85f),
                            fontSize = 10.5.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Transport & rsyncd Information
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Connection", color = Muted, fontSize = 12.sp)
                Text(syncState.transport.badge, color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Rsync target", color = Muted, fontSize = 12.sp)
                Text("rsync://${syncState.ipAddress}:${HioneSyncTransceiver.RSYNC_PORT}/music", color = Color.White, fontSize = 11.5.sp, fontFamily = AudiowideFont)
            }

            if (syncState.isTransferring) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Transfer rate", color = HionePink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${String.format("%.1f", syncState.transferRateMBs)} MB/s", color = HionePink, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action Buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val isStarting = !syncState.daemon.isTransferring
                        HioneSyncTransceiver.triggerDaemonSync(isStarting) { success, msg ->
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (syncState.daemon.isTransferring) HionePink.copy(alpha = 0.35f) else HioneTeal.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (syncState.daemon.isTransferring) "Stop sync" else "Start sync",
                        color = if (syncState.daemon.isTransferring) HionePink else HioneTealBright,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        HioneSyncTransceiver.runSpeedTest { res ->
                            android.widget.Toast.makeText(
                                ctx,
                                "${res.transportTier}\n${String.format("%.1f", res.speedMBs)} MB/s, ${res.latencyMs} ms latency",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.12f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "Speed test",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (syncState.speedTest != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(HioneTeal.copy(alpha = 0.15f))
                        .border(1.dp, HioneTealBright.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(syncState.speedTest?.transportTier ?: "", color = HioneTealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${String.format("%.1f", syncState.speedTest?.speedMBs ?: 0f)} MB/s (${syncState.speedTest?.latencyMs}ms)",
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // The SD-card scan controls (Quick / FORCE SCAN + unattended scans) live in the HioneOS
            // launcher's ingest observatory — this button hands off to that system screen.
            Button(
                onClick = {
                    val launch = ctx.packageManager.getLaunchIntentForPackage("com.hione.launcher")?.apply {
                        putExtra("open_ingest", true)
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    }
                    if (launch == null) {
                        android.widget.Toast.makeText(ctx, "The ${Mascot.osName} launcher isn't installed, so the sync screen can't open", android.widget.Toast.LENGTH_SHORT).show()
                    } else try { ctx.startActivity(launch) } catch (t: Throwable) {
                        android.widget.Toast.makeText(ctx, "Couldn't open the sync screen: ${t.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Open network sync (SD scan)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(6.dp))

            // BPM / rhythm game entry point. The game lives in the HioneOS launcher (it owns the
            // live output-mix beat detector); HioneBpmGameLink opens it on top of this task so
            // leaving the game comes straight back here.
            Button(
                onClick = { HioneBpmGameLink.open(ctx, HioneBpmGameLink.FROM_MONITOR) },
                modifier = Modifier.bpmBeatPulse(HionePink, RoundedCornerShape(10.dp)).fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = HionePink.copy(alpha = 0.16f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("BPM Game", color = HionePink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Verbose live scan readout as a real dismissable modal — this is the actual point of exposing
 * [ScanProgress] at all: no more guessing whether a big rescan is still working or has quietly
 * stalled. Floats over the content and can be closed without stopping the scan (it keeps running
 * in the background regardless; this dialog is just a window onto it). Previously this lived as
 * an inline panel under the header pill, which stretched the title bar taller while scanning.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable private fun ScanProgressDialog(hazeState: HazeState, onDismissRequest: () -> Unit) {
    var active by remember { mutableStateOf(ScanProgress.active) }
    var phase by remember { mutableStateOf(ScanProgress.phase) }
    var visited by remember { mutableIntStateOf(0) }
    var newFound by remember { mutableIntStateOf(0) }
    var tagsScanned by remember { mutableIntStateOf(0) }
    var tagsTotal by remember { mutableIntStateOf(0) }
    var currentFile by remember { mutableStateOf("") }
    var speed by remember { mutableFloatStateOf(0f) }
    var resultDelta by remember { mutableIntStateOf(0) }
    var resultTotal by remember { mutableIntStateOf(0) }
    var resultAlbums by remember { mutableIntStateOf(0) }
    var resultArtists by remember { mutableIntStateOf(0) }
    var formatSummary by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            active = ScanProgress.active
            phase = ScanProgress.phase
            visited = ScanProgress.visited.get()
            newFound = ScanProgress.newFound.get()
            tagsScanned = ScanProgress.tagsScanned.get()
            tagsTotal = ScanProgress.tagsTotal
            currentFile = ScanProgress.currentFile
            speed = ScanProgress.speedTracksPerSec
            resultDelta = ScanProgress.resultDelta
            resultTotal = ScanProgress.resultTotal
            resultAlbums = ScanProgress.resultAlbums
            resultArtists = ScanProgress.resultArtists
            formatSummary = ScanProgress.formatSummary
            delay(80L)
        }
    }
    // Once scan completes, keep result visible for 4s so user can read complete breakdown
    LaunchedEffect(active) { if (!active) { delay(4000L); onDismissRequest() } }
    androidx.activity.compose.BackHandler(onBack = onDismissRequest)
    Box(
        Modifier.fillMaxSize()
            .background(Color(0x77000000))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onDismissRequest() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp)
                    .hioneGlassPanel(
                        androidx.compose.ui.platform.LocalContext.current, hazeState,
                        HazeMaterials.thick(Color(0xFF0F1522)), Color(0xFF0F1522),
                        shape = RoundedCornerShape(22.dp)
                    )
                    .border(1.5.dp, Brush.horizontalGradient(listOf(HioneCyan.copy(alpha = 0.8f), HioneNeonPink.copy(alpha = 0.6f))), RoundedCornerShape(22.dp))
            ) {
                androidx.compose.foundation.Image(
                    painter = mascotPainter(HioneArt.djMegaphone),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    alpha = 0.22f,
                    modifier = Modifier.matchParentSize().clip(RoundedCornerShape(22.dp))
                )
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (active) Icons.Default.Refresh else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (active) HioneCyan else Color(0xFF00E676),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (active) "Scanning library" else "Scan finished",
                            color = Color.White,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            modifier = Modifier.weight(1f)
                        )
                        HapticIconButton(onClick = onDismissRequest) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (!active) {
                        Text(
                            if (resultDelta > 0) "Added $resultDelta new track${if (resultDelta == 1) "" else "s"}" else "The library is up to date",
                            color = HioneCyan,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "$resultTotal total tracks · $resultAlbums albums · $resultArtists artists",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (formatSummary.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$formatSummary",
                                color = Color(0xFF80D8FF),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        val isIndexing = tagsTotal > 0
                        val progressFraction = if (isIndexing) (tagsScanned.toFloat() / tagsTotal.toFloat()).coerceIn(0f, 1f) else 0f
                        val pct = (progressFraction * 100f).toInt()

                        Text(
                            if (isIndexing) "Reading tags ($pct%)" else phase,
                            color = HioneCyan,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                        Spacer(Modifier.height(8.dp))

                        if (isIndexing) {
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(3.5.dp)),
                                color = HioneCyan,
                                trackColor = Mascot.color(0xFF10323C)
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "$tagsScanned / $tagsTotal tracks indexed",
                                    color = Color.White.copy(alpha = 0.95f),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (speed > 0) {
                                    Text(
                                        "${String.format(java.util.Locale.US, "%.0f", speed)} trk/s",
                                        color = HioneNeonPink,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = AudiowideFont
                                    )
                                }
                            }
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(3.5.dp)),
                                color = HioneCyan,
                                trackColor = Mascot.color(0xFF10323C)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Checked $visited files · $newFound new",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (currentFile.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "$currentFile",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Scanning in the background. You can keep listening.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

fun expandNotificationShade(ctx: Context) {
    try {
        val intent = Intent().setClassName("com.hione.systemui", "com.hione.systemui.HioneShadeActivity").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        ctx.startActivity(intent)
    } catch (_: Throwable) {
        try {
            val sbservice = ctx.getSystemService("statusbar")
            val statusbarManager = Class.forName("android.app.StatusBarManager")
            val expand = statusbarManager.getMethod("expandNotificationsPanel")
            expand.invoke(sbservice)
        } catch (_: Throwable) {}
    }
}

@Composable private fun Header(
    count: Int,
    onScan: () -> Unit,
    onTape: () -> Unit,
    onSettings: () -> Unit = {},
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    onRandom: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val ac = HioneArtTheme.colors()
    // No background fill of its own anymore — this now sits inside the shared hazeChild glass
    // panel (see App()), which supplies the frosted tint. A second opaque fill here would just
    // hide the blur entirely.
    Box(
        Modifier.fillMaxWidth()
            .clipToBounds()
            // Pull the header down to open the Hione shade. Edge-safe: a pull that starts in the
            // window's top 24dp IS the system's own top-edge shade gesture (the a11y nav layer
            // handles it) — reacting here too double-fired the shade. Only pulls that begin below
            // that band, on the header body, are ours.
            .edgeSafePointerInput(Unit) { guard ->
                var totalY = 0f
                detectVerticalDragGesturesEdgeSafe(
                    guard,
                    onDragStart = { totalY = 0f },
                    onDragEnd = {
                        if (totalY > 15f) {
                            expandNotificationShade(ctx)
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount > 0) {
                            totalY += dragAmount
                            if (totalY > 20f) {
                                change.consume()
                                expandNotificationShade(ctx)
                                totalY = 0f
                            }
                        }
                    }
                )
            }
    ) {
        androidx.compose.foundation.Image(
            painter = mascotPainter(HioneArt.bgWide),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            alpha = 0.14f,
            modifier = Modifier.matchParentSize()
        )
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val effectiveScale = 1.0f + (fontScale - 1.0f) * 0.20f
        val brandFontSize = (15f / fontScale * effectiveScale).sp

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Show Back button ONLY when there is a sub-page to go back from
            if (canGoBack) {
                com.hione.player.ui.HioneBackButton(onClick = onBack)
                Spacer(Modifier.width(6.dp))
            }

            // Standalone Hione Music brand header — the shared mark (ui/HioneTopBar.kt), same glyph +
            // wordmark that signs Now Playing and every settings page.
            //
            // ROW BUDGET (360dp-wide panel): back 38 + gap, then Random and Tape keys at 38dp (the
            // back key's size), the BPM game key at 34dp with wider gaps, plus the scan pill. That leaves roughly 120-165dp for the brand,
            // so the brand takes the flexible weight instead of a Spacer: it is measured LAST, gets
            // whatever is left, and ellipsizes its wordmark if it ever has to. With a Spacer here,
            // an over-full row squeezed the right-most key (Tape) out of existence instead.
            com.hione.player.ui.HioneBrandMark(
                tint = ac.accent, fontSize = brandFontSize, glyphSize = 24.dp,
                modifier = Modifier.weight(1f)
            )

            // Instant RANDOM — always one tap away from anything in the library.
            HapticIconButton(
                onClick = onRandom,
                modifier = Modifier.size(38.dp).semantics { contentDescription = "Random: play anything" }
            ) {
                Icon(
                    Icons.Default.Casino, null,
                    tint = if (InstantRandom.active) ac.accent2 else ac.accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            ScannerPill(
                count = count,
                onScan = onScan
            )
            // BPM rhythm game: highlighted, and pulsing on the beat while music plays. 34dp plus
            // 4dp gaps keeps the same row budget as a 38dp key while leaving room for its halo.
            Spacer(Modifier.width(4.dp))
            com.hione.player.ui.BpmGameButton(
                from = HioneBpmGameLink.FROM_HEADER,
                accent = ac.accent,
                size = 34.dp,
                iconSize = 21.dp
            )
            Spacer(Modifier.width(4.dp))
            HapticIconButton(
                onClick = onTape,
                modifier = Modifier.size(38.dp).semantics { contentDescription = "Tape mode" }
            ) { TapeIcon(tint = ac.accent2, modifier = Modifier.size(22.dp)) }
        }
    }
}

// Slim tabs: Material3's default Tab is 48dp tall, which wasted a band of dead space between the
// tab labels and the content — cap the row at 36dp.
// The four tabs actually shown in the bar, in display order — Songs lives inside Library now.
private val VISIBLE_TABS = listOf(Tab.HOME, Tab.ARTISTS, Tab.ALBUMS, Tab.GENRES)
// Each tab gets its own fixed Hione-palette color (not just selected/unselected teal-or-muted) so
// the bar itself reads as colorful/branded rather than four identical grey words.
private fun tabColor(t: Tab): Color = when (t) {
    Tab.HOME -> HioneTealBright
    Tab.ARTISTS -> HionePink
    Tab.ALBUMS -> HioneGold
    Tab.GENRES -> Mascot.color(0xFFB9C2D0) // violet — same family used for the "ultra hi-res" tech badge tier
    Tab.SONGS -> HioneTeal
}

@Composable private fun TabBar(sel: Tab, onSel: (Tab) -> Unit) {
    val ctx = LocalContext.current
    // Transparent container — same reasoning as Header: this rides on the shared glass panel now.
    // Songs isn't ever the active tab from the bar's own perspective (it's not clickable here), but
    // App() can still land `tab` on it internally (Library's "All Songs" row) — fall back to
    // highlighting Library in that case rather than an out-of-range/no selection.
    val selIdx = VISIBLE_TABS.indexOf(sel).let { if (it >= 0) it else VISIBLE_TABS.indexOf(Tab.GENRES) }
    // Fixed (non-scrolling) row: all four tabs always visible and evenly spaced on the 360dp-wide
    // M500 screen — the scrollable row used to park "Library" against the right edge on Home.
    TabRow(
        selectedTabIndex = selIdx,
        modifier = Modifier.padding(horizontal = 8.dp),
        containerColor = Color.Transparent, contentColor = HioneTeal,
        indicator = { positions ->
            // Springy pill that GLIDES between tabs and retints toward each tab's own accent —
            // the default indicator hard-jumps and stays one color, which reads as a repaint
            // rather than the selection physically moving.
            val p = positions[selIdx]
            val left by animateDpAsState(p.left, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "tabIndLeft")
            val width by animateDpAsState(p.width, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "tabIndWidth")
            val accent by animateColorAsState(tabColor(VISIBLE_TABS[selIdx]), tween(300), label = "tabIndColor")
            Box(
                Modifier
                    .wrapContentSize(Alignment.BottomStart)
                    .offset(x = left)
                    .width(width)
                    .padding(horizontal = 14.dp)
                    .height(3.dp)
                    .background(
                        Brush.horizontalGradient(listOf(accent.copy(alpha = 0.25f), accent, accent.copy(alpha = 0.25f))),
                        RoundedCornerShape(1.5.dp)
                    )
            )
        },
        divider = {}
    ) {
        VISIBLE_TABS.forEach { t ->
            val base = tabColor(t)
            // Label color/weight ease over instead of snapping, matching the gliding indicator.
            val labelColor by animateColorAsState(if (t == sel) base else base.copy(alpha = 0.55f), tween(250), label = "tabLabel")
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val effectiveScale = 1.0f + (fontScale - 1.0f) * 0.20f
            val dampedTabFontSize = (14f / fontScale * effectiveScale).sp
            androidx.compose.material3.Tab(
                selected = t == sel,
                onClick = { if (t != sel) Haptics.tick(ctx); onSel(t) },
                modifier = Modifier.height(42.dp).hioneTactile(hapticTick = false, pressedScale = 0.90f),
                text = { Text(t.label, color = labelColor, fontWeight = if (t == sel) FontWeight.Bold else FontWeight.Normal, fontFamily = RighteousFont, fontSize = dampedTabFontSize, letterSpacing = 0.4.sp) })
        }
    }
}

// Top-level tab bar always visible for non-home too:
@Composable private fun AlwaysTabs(sel: Tab, onSel: (Tab) -> Unit) = TabBar(sel, onSel)

@Composable private fun HomeScreen(
    tracks: List<Track>,
    recentlyPlayed: List<Track>,
    goArtists: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    albumGroups: List<AlbumGroup> = emptyList(),
    artistGroups: List<ArtistGroup> = emptyList(),
    onOpenAlbum: (AlbumGroup) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onRandom: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val likedIds = LikeStore.liked.toList()
    // Self-healing resolution (LikeStore.resolveLiked) — a liked track's MediaStore _id can drift
    // across a rescan; a plain id-membership filter would silently drop it from every "liked"
    // shelf with no explanation. See its doc comment for the confirmed real-world failure mode.
    val likedTracks = remember(likedIds, tracks) { LikeStore.resolveLiked(ctx, tracks, heal = true) }

    // Was a synchronous `remember(tracks, likedIds) { ... }` — generateDailyHighlight does two
    // SharedPreferences reads PER TRACK (play count + last-played) across the whole library to
    // score everything, then sorts/picks the mix. On a few thousand tracks that's genuinely
    // expensive, and because it was keyed on `likedIds`, tapping ANY heart ANYWHERE re-ran the
    // whole thing right there on the UI thread — this was the "heart button lags the UI for 2-3
    // seconds" bug. Off the main thread now; keeps showing the previous mix (not a blank/empty
    // state) while a fresh one computes in the background.
    var highlight by remember { mutableStateOf(DailyHighlightEngine.HighlightResult(emptyList(), 0, 0, 0)) }
    LaunchedEffect(tracks, likedIds) {
        // Debounced: LaunchedEffect cancels+restarts this coroutine on every `likedIds` change, so
        // a burst of rapid heart-taps (liking a whole album quickly) used to spawn one full
        // library rescore PER tap, all running concurrently on Dispatchers.Default. The delay lets
        // a fast burst collapse into a single rescore of the FINAL liked-set once taps settle,
        // rather than doing (and discarding) N redundant full passes.
        delay(500)
        highlight = withContext(Dispatchers.Default) {
            DailyHighlightEngine.generateDailyHighlight(ctx, tracks, targetCount = 40)
        }
    }

    // "Newly Added" — artists/albums/tracks that landed in the library within Newness's own
    // 7-day window (same cutoff the badge dots use elsewhere), newest first. Tracks only show up
    // here if they're a single (no real album, or MediaStore's own albumId groups them with no
    // other track) — a track that's part of a real multi-track album is already covered by that
    // album showing up in the row above it, so listing it again as an individual track would just
    // be the same arrival duplicated twice.
    val nowSec = remember { System.currentTimeMillis() / 1000 }
    val albumTrackCounts = remember(tracks) { tracks.groupingBy { it.albumId }.eachCount() }
    val newArtists = remember(artistGroups, nowSec) {
        artistGroups.filter { Newness.isNew(it.dateAddedSec, nowSec) }
            .sortedByDescending { it.dateAddedSec }.take(12)
    }
    val newAlbums = remember(albumGroups, nowSec) {
        albumGroups.filter { Newness.isNew(it.dateAddedSec, nowSec) }
            .sortedByDescending { it.dateAddedSec }.take(12)
    }
    val newSingles = remember(tracks, nowSec, albumTrackCounts) {
        tracks.filter { t ->
            Newness.isNew(t.dateAddedSec, nowSec) &&
                (t.albumId <= 0L || t.album.isBlank() || (albumTrackCounts[t.albumId] ?: 1) <= 1)
        }.sortedByDescending { it.dateAddedSec }.take(12)
    }
    val hasNewSection = newArtists.isNotEmpty() || newAlbums.isNotEmpty() || newSingles.isNotEmpty()

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        // 1. Wallpaper of the day Banner + Smart Daily Highlight Action
        item {
            Box(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    .fillMaxWidth().height(120.dp)
                    .clip(RoundedCornerShape(18.dp))
            ) {
                androidx.compose.foundation.Image(
                    painter = mascotPainter(HioneArt.bannerCyberStage),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    // The banner's aspect ratio crops the SIDES off this source, and dead-center
                    // both axes clipped her head/hair off the top — she sits in the upper portion
                    // of the source scene (lots of crowd/lights fill the lower two-thirds), so a
                    // vertically-centered crop window landed mostly on empty crowd below her while
                    // still cutting her hairline above. Nudged left (off-axis face position) and
                    // well up (keep her whole head in frame).
                    alignment = androidx.compose.ui.BiasAlignment(-0.18f, -0.65f),
                    modifier = Modifier.matchParentSize()
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Ground.copy(alpha = 0.90f)))
                    )
                )
                Column(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("${Mascot.nameJa} · DAILY HIGHLIGHT", color = HioneTealBright, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    val subText = if (highlight.favCount > 0 || highlight.gemCount > 0) {
                        "${highlight.tracks.size} tracks · ${highlight.favCount} Favs · ${highlight.discCount} Fresh · ${highlight.gemCount} Gems"
                    } else {
                        "${highlight.tracks.size} tracks · picked for today"
                    }
                    Text(subText, color = Color.White.copy(alpha = 0.92f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Play Mix — moved up to the top-left corner (was bottom-right, sitting right on
                // top of her hand/the DAP prop in the art). Own spot now, out of the text's way.
                PlayMixButton(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    onClick = { if (highlight.tracks.isNotEmpty()) onPlay(highlight.tracks, 0) }
                )
            }
        }

        // 1.5. "What do you want to hear?" / Vibe Alchemist Prompt Bar & Randomizer
        item { RandomModePill(count = tracks.size, onRandom = onRandom) }
        item {
            HioneVibePromptCard(tracks = tracks, onPlay = onPlay)
        }

        // 2. Daily Highlight Tracks Carousel
        if (highlight.tracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Today's mix (${highlight.tracks.size})", color = HioneTealBright, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Play Next Mix Button
                        Text(
                            "+ Play Next",
                            color = HioneTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                PlayerHolder.player?.let { p -> QueueManager.playNext(ctx, p, highlight.tracks) }
                            }
                        )
                        Spacer(Modifier.width(12.dp))
                        // Add Mix to Queue Button
                        Text(
                            "+ Queue",
                            color = HionePink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                PlayerHolder.player?.let { p -> QueueManager.playLast(ctx, p, highlight.tracks) }
                            }
                        )
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(highlight.tracks.size, key = { highlight.tracks[it].id }, contentType = { "track" }) { i ->
                        val t = highlight.tracks[i]
                        Column(
                            Modifier.width(118.dp).padding(4.dp).clickable { onPlay(highlight.tracks, i) }
                        ) {
                            Box(Modifier.size(110.dp).clip(RoundedCornerShape(14.dp))) {
                                AlbumArtImage(trackId = t.id, modifier = Modifier.fillMaxSize(), trackPath = t.path)
                                // Bit-depth / sample rate quality badges overlaid directly on album art
                                // Must be a Row: TechBadgeRow emits sibling chips and a Box would
                                // stack them on top of each other at the same origin.
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TechBadgeRow(ctx, t, fontSize = 7.5.sp, spacing = 2.dp)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(t.title, color = Mascot.color(0xFFE8F4F2), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // 1b. Newly Added — artists/albums/singles that landed within the last 7 days. Whichever
        // sub-rows actually have anything are shown; the whole section is skipped if nothing in
        // the library is that fresh.
        if (hasNewSection) {
            item {
                Text("Newly added", color = HioneTealBright, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
            }
            if (newArtists.isNotEmpty()) {
                item {
                    Text("Artists", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(newArtists.size, key = { newArtists[it].name }) { i ->
                            val a = newArtists[i]
                            val reprTrack = a.coverTrack(ctx)
                            NewItemCard(
                                artOf = reprTrack?.id, artPath = reprTrack?.path,
                                title = a.name, subtitle = "${a.tracks.size} tracks",
                                prominence = Newness.prominence(a.dateAddedSec, nowSec, a.tracks.any { PlayerPreferences.loadPlayCount(ctx, it.id) > 0 }),
                                shape = RoundedCornerShape(50),
                                onClick = { onOpenArtist(a.name) }
                            )
                        }
                    }
                }
            }
            if (newAlbums.isNotEmpty()) {
                item {
                    Text("Albums", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(newAlbums.size, key = { "${newAlbums[it].name}#${newAlbums[it].artist}#${newAlbums[it].tracks.firstOrNull()?.id ?: it}" }) { i ->
                            val al = newAlbums[i]
                            val reprTrack = al.tracks.firstOrNull()
                            NewItemCard(
                                artOf = reprTrack?.id, artPath = reprTrack?.path,
                                title = al.name, subtitle = al.artist,
                                prominence = Newness.prominence(al.dateAddedSec, nowSec, al.tracks.any { PlayerPreferences.loadPlayCount(ctx, it.id) > 0 }),
                                shape = RoundedCornerShape(14.dp),
                                onClick = { onOpenAlbum(al) }
                            )
                        }
                    }
                }
            }
            if (newSingles.isNotEmpty()) {
                item {
                    Text("Singles", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(newSingles.size, key = { newSingles[it].id }) { i ->
                            val t = newSingles[i]
                            NewItemCard(
                                artOf = t.id, artPath = t.path,
                                title = t.title, subtitle = t.artist,
                                prominence = Newness.prominence(t.dateAddedSec, nowSec, PlayerPreferences.loadPlayCount(ctx, t.id) > 0),
                                shape = RoundedCornerShape(14.dp),
                                onClick = { onPlay(newSingles, i) }
                            )
                        }
                    }
                }
            }
        }

        // 2. Liked Songs Section (Dedicated Carousel + Play All)
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RainbowHeart(true) {}
                    Spacer(Modifier.width(8.dp))
                    Text("Liked Songs", color = HioneTealBright, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("(${likedTracks.size})", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                if (likedTracks.isNotEmpty()) {
                    Text(
                        "Play all",
                        color = HionePink,
modifier = Modifier.clickable { onPlay(likedTracks, 0) }
                    )
                }
            }

            if (likedTracks.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(likedTracks.size, key = { likedTracks[it].id }, contentType = { "track" }) { i ->
                        val t = likedTracks[i]
                        Column(
                            Modifier.width(120.dp).padding(4.dp).clickable { onPlay(likedTracks, i) }
                        ) {
                            Box(Modifier.size(112.dp).clip(RoundedCornerShape(14.dp))) {
                                AlbumArtImage(trackId = t.id, modifier = Modifier.fillMaxSize(), trackPath = t.path)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(t.title, color = Mascot.color(0xFFE8F4F2), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Mascot.color(0xFF092124))
                        .border(1.dp, HioneTeal.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Mascot.color(0xFF041416))
                    ) {
                        androidx.compose.foundation.Image(
                            painter = mascotPainter(HioneArt.chibiHearts),
                            contentDescription = "${Mascot.name}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("NO LIKED SONGS YET", color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Tap the heart on a song to add it here.",
                            color = Muted,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 2.5. "For You" — local taste engine shelf (taste/ForYouShelf.kt): heavy rotation,
        // forgotten favourites, good-right-now (time of day), discover-in-your-library, plus the
        // Hione Radio entry point and the taste-profile card. Every row is derived from real local
        // signals and stays locked ("Listen more to unlock") until there is enough of them.
        item { com.hione.player.taste.ForYouShelf(tracks = tracks, onPlay = onPlay, onOpenArtist = onOpenArtist) }

        // 3. Recently Played Section
        if (recentlyPlayed.isNotEmpty()) {
            item {
                SectionHeader("Recently played")
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(recentlyPlayed.size, key = { recentlyPlayed[it].id }) { i ->
                        val t = recentlyPlayed[i]
                        Column(Modifier.width(116.dp).padding(4.dp).clickable { onPlay(recentlyPlayed, i) }) {
                            AlbumArtImage(t.id, Modifier.size(108.dp).clip(RoundedCornerShape(14.dp)), trackPath = t.path)
                            Spacer(Modifier.height(6.dp))
                            Text(t.title, color = Mascot.color(0xFFE8F4F2), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // 4. Recently Added Songs Section
        item { SectionHeader("Recently added") }
        val recent = tracks.takeLast(30).reversed()
        itemsIndexed(recent) { i, t -> TrackRow(t) { onPlay(recent, i) } }
    }
}

/**
 * 4-bar level indicator for the current track row. The bars follow the REAL audio spectrum
 * (AudioCapture's shared Visualizer, 4 averaged bands) whenever that capture is active; when it
 * isn't (no Visualizer bound), they hold a steady "playing" glyph — never a synthesised sine bounce.
 */
@Composable private fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier) {
    val n = 4
    var levels by remember { mutableStateOf(FloatArray(n)) }
    var live by remember { mutableStateOf(false) }
    LaunchedEffect(playing) {
        if (!playing) { live = false; return@LaunchedEffect }
        while (true) {
            if (AudioCapture.active) {
                val fft = AudioCapture.fft
                val per = fft.size / n
                val next = FloatArray(n) { b ->
                    var s = 0f
                    for (k in 0 until per) s += fft[b * per + k]
                    (s / per).coerceIn(0f, 1f)
                }
                levels = next; live = true
            } else live = false
            delay(45)
        }
    }
    Canvas(modifier) {
        val gap = size.width / (n * 2f - 1)
        for (i in 0 until n) {
            val hf = when {
                playing && live -> 0.15f + 0.85f * levels[i]
                playing -> 0.6f   // steady glyph: playing, but no capture bound — no fake motion
                else -> 0.4f
            }
            val bh = size.height * hf.coerceIn(0.15f, 1f)
            drawRoundRect(
                HioneTealBright,
                topLeft = Offset(i * 2 * gap, size.height - bh),
                size = Size(gap, bh),
                cornerRadius = CornerRadius(gap / 2, gap / 2)
            )
        }
    }
}

@Composable private fun SectionHeader(text: String) = Text(
    text, color = HioneTealBright, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = RighteousFont, letterSpacing = 0.5.sp,
    modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 8.dp)
)

// helper because LazyListScope.itemsIndexed needs the foundation import via lambda
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    list: List<Track>, row: @Composable (Int, Track) -> Unit
) = items(list.size, key = { list[it].id }, contentType = { "track" }) { i -> row(i, list[i]) }

/**
 * PERF (scroll jank, 2026-09-17). Per-track bit-depth/sample-rate (TrackTech) and release-year
 * (TrackYear) probes are requested from inside list item bodies, so a fling used to launch one SD
 * card probe coroutine per row that flew past — thousands of them, from the main thread, mid-fling,
 * and then minutes of SD I/O afterwards for rows nobody is looking at any more. This tells both
 * caches "a list is moving": while it is, a request is only recorded, and the recording (capped to
 * the most recent screenful-and-then-some) is drained the instant scrolling stops.
 *
 * Deliberately its own tiny composable: `state.isScrollInProgress` is read HERE, so only this
 * zero-output function recomposes when a fling starts or stops — putting the read in the list
 * screen itself would re-run that whole (very large) composable twice per fling.
 */
@Composable private fun PauseProbesWhileScrolling(scrolling: Boolean) {
    DisposableEffect(scrolling) {
        if (scrolling) { TrackTech.beginScroll(); TrackYear.beginScroll() }
        onDispose { if (scrolling) { TrackTech.endScroll(); TrackYear.endScroll() } }
    }
}

@Composable private fun PauseProbesWhileScrolling(state: LazyListState) =
    PauseProbesWhileScrolling(state.isScrollInProgress)

@Composable private fun PauseProbesWhileScrolling(state: LazyGridState) =
    PauseProbesWhileScrolling(state.isScrollInProgress)

@Composable
private fun <T> AlphabetFastScroller(
    items: List<T>,
    getItemName: (T) -> String,
    onScrollTo: suspend (Int) -> Unit,
    firstVisibleIndex: () -> Int = { 0 },
    modifier: Modifier = Modifier
) {
    if (items.size < 6) return

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val alphabet = remember { listOf('#') + ('A'..'Z').toList() }

    val letterMap = remember(items) {
        val map = mutableMapOf<Char, Int>()
        items.forEachIndexed { index, item ->
            val name = getItemName(item).trim()
            val sortKey = formatArtistSortKey(name, true, ctx)
            val firstChar = sortKey.firstOrNull()?.uppercaseChar() ?: '#'
            val letter = if (firstChar in 'A'..'Z') firstChar else '#'
            if (!map.containsKey(letter)) {
                map[letter] = index
            }
        }
        map
    }

    var isDragging by remember { mutableStateOf(false) }
    var activeLetter by remember { mutableStateOf<Char?>('#') }
    var touchY by remember { mutableFloatStateOf(0f) }
    var containerHeight by remember { mutableIntStateOf(1) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Sync active letter with normal list scrolling when not dragging.
    // PERF (scroll jank, 2026-09-17): this used to be
    //     val visibleIdx by remember { derivedStateOf { firstVisibleIndex() } }
    //     LaunchedEffect(visibleIdx, isDragging) { ... }
    // i.e. the first-visible-item index was read in THIS composable's body. That index changes
    // continuously during a fling, so the whole rail — all 27 letter Texts plus the gesture
    // modifiers — recomposed several times a second on every scroll, and the LaunchedEffect was
    // cancelled and relaunched each time. The index is observed inside the effect now, so a
    // recomposition only happens when the ACTIVE LETTER actually changes, which is the only thing
    // the rail draws differently.
    LaunchedEffect(items, isDragging) {
        if (isDragging) return@LaunchedEffect
        snapshotFlow { firstVisibleIndex() }.collect { idx ->
            if (idx in items.indices) {
                val name = getItemName(items[idx]).trim()
                val sortKey = formatArtistSortKey(name, true, ctx)
                val firstChar = sortKey.firstOrNull()?.uppercaseChar() ?: '#'
                activeLetter = if (firstChar in 'A'..'Z') firstChar else '#'
            }
        }
    }

    val railAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1.0f else 0.45f,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "railAlpha"
    )

    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.4f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scrollerScale"
    )

    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(48.dp) // Wide touch target for leather cases!
            .padding(end = 4.dp, top = 8.dp, bottom = 8.dp)
            .onSizeChanged { containerHeight = it.height.coerceAtLeast(1) }
            // Edge-safe on the TOP/BOTTOM bands only: a vertical drag that starts in the bottom
            // 32dp is the system home swipe, not a jump to "Z". The rail deliberately keeps its
            // full 48dp width including the right-edge band — a vertical rail drag can't be a
            // (horizontal) back swipe, and leather cases need the fat target.
            .edgeSafePointerInput(items, edges = VERTICAL_GESTURE_EDGES) { guard ->
                detectVerticalDragGesturesEdgeSafe(
                    guard,
                    onDragStart = { offset ->
                        isDragging = true
                        touchY = offset.y
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onVerticalDrag = { change, _ ->
                        touchY = change.position.y
                    }
                )
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        // Ephemeral Letter Column (NO background rail lines or boxes!)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .alpha(railAlpha)
                .padding(end = 4.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            alphabet.forEach { char ->
                val hasItems = letterMap.containsKey(char)
                val isSelected = char == activeLetter
                Text(
                    text = char.toString(),
                    fontSize = if (isSelected) 9.5.sp else 8.sp,
                    fontWeight = if (isSelected || hasItems) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) HioneTealBright else if (hasItems) HioneTeal.copy(alpha = if (isDragging) 0.9f else 0.55f) else Color.White.copy(alpha = 0.12f)
                )
            }
        }

        // Active letter drag handling & throttled scroll execution
        LaunchedEffect(touchY, isDragging) {
            if (isDragging && containerHeight > 0) {
                val fraction = (touchY / containerHeight.toFloat()).coerceIn(0f, 1f)
                val targetIndex = (fraction * (alphabet.size - 1)).let { Math.round(it) }.coerceIn(0, alphabet.size - 1)
                val selectedChar = alphabet[targetIndex]

                if (selectedChar != activeLetter) {
                    activeLetter = selectedChar
                    Haptics.tick(ctx)

                    val scrollToIdx = letterMap[selectedChar] ?: run {
                        val availableLetters = alphabet.filter { letterMap.containsKey(it) }
                        if (availableLetters.isEmpty()) null
                        else {
                            val closest = availableLetters.minByOrNull { Math.abs(alphabet.indexOf(it) - targetIndex) }
                            closest?.let { letterMap[it] }
                        }
                    }

                    scrollToIdx?.let { target ->
                        scrollJob?.cancel()
                        scrollJob = scope.launch {
                            onScrollTo(target)
                        }
                    }
                }
            }
        }

        // Prominent Reticle Position Indicator Pill (Fluid liquid-glide animation!)
        val currentLetterChar = activeLetter ?: '#'
        val letterIdx = alphabet.indexOf(currentLetterChar).coerceAtLeast(0)
        val reticleFraction = letterIdx.toFloat() / (alphabet.size - 1).coerceAtLeast(1)
        val targetReticleYOffsetDp = with(density) { ((if (isDragging) touchY else reticleFraction * containerHeight) - (containerHeight / 2f)).toDp() }

        val animatedReticleYOffsetDp by animateDpAsState(
            targetValue = targetReticleYOffsetDp,
            animationSpec = if (isDragging) snap() else spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
            label = "reticleYOffset"
        )

        if (!isDragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-1).dp, y = animatedReticleYOffsetDp)
                    .size(width = 22.dp, height = 13.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Mascot.color(0xFF072428))
                    .border(1.2.dp, HioneTealBright, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentLetterChar.toString(),
                    color = HioneTealBright,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // Bulging Magnifier Bubble (Google Photos / iOS Scroller style)
        if (scale > 0.01f && activeLetter != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-52).dp, y = animatedReticleYOffsetDp)
                    .scale(scale)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Mascot.color(0xFF0F3E42), Mascot.color(0xFF041618))
                        )
                    )
                    .border(1.8.dp, HioneTealBright, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = activeLetter.toString(),
                    color = HioneTealBright,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }
        }
    }
}

@Composable
private fun HioneSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search library...",
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .height(40.dp)
            .glassCard(corner = 20.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, "Search", tint = HioneTeal, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Mascot.color(0xFFE8F4F2),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(HioneTealBright),
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text(placeholder, color = Muted.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                innerTextField()
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Default.Close,
                "Clear",
                tint = Muted,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

@Composable
fun HioneEmptyState(
    title: String,
    subtitle: String,
    imageRes: Int = HioneArt.chibiHearts,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Mascot.color(0xFF071B1E))
                .border(1.5.dp, Brush.horizontalGradient(listOf(HioneTeal, HionePink)), RoundedCornerShape(24.dp))
        ) {
            androidx.compose.foundation.Image(
                painter = mascotPainter(imageRes),
                contentDescription = "${Mascot.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            title,
            color = HioneTealBright,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            color = Muted,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable private fun SongList(
    tracks: List<Track>,
    sortIgnoreThe: Boolean = true,
    onPlay: (List<Track>, Int) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val ctx = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    // Was a synchronous remember{} — filter+sortedBy(formatArtistSortKey) over up to the WHOLE
    // library ran on the main thread on every single keystroke (an early keystroke like "b" can
    // still match thousands of tracks before the query narrows), no debounce. Confirmed live: typing
    // into this search box on a 6800-track library triggered a real ANR ("Input dispatching timed
    // out... waited 5005ms"), the app pegged at 118% CPU. Same fix shape as generateDailyHighlight's
    // fix earlier this session — debounce + off the main thread — applied here too.
    var sortedTracks by remember { mutableStateOf(tracks) }
    LaunchedEffect(tracks, sortIgnoreThe, searchQuery) {
        if (searchQuery.isNotBlank()) delay(250) // debounce fast typing; skip the delay when the query is being cleared back to blank
        sortedTracks = withContext(Dispatchers.Default) {
            val base = if (searchQuery.isBlank()) tracks
            else {
                val q = searchQuery.trim().lowercase()
                // Fold the query the same way the fields are folded, so "Suicide Boys" finds
                // "$uicide Boy$" and "$uicideboy$". The raw contains() below stays as well:
                // folding drops spacing and punctuation, so it must not be the only test or a
                // query the user typed with punctuation on purpose would get worse, not better.
                val qf = searchFold(searchQuery)
                tracks.filter {
                    it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q) ||
                    (qf.isNotEmpty() && (
                        searchFold(it.title).contains(qf) ||
                        searchFold(it.artist).contains(qf) ||
                        searchFold(it.album).contains(qf)))
                }
            }
            base.sortedBy { formatArtistSortKey(it.title, sortIgnoreThe, ctx) }
        }
    }
    val scope = rememberCoroutineScope()
    // Don't queue per-track SD-card probes for rows flying past mid-fling (see the function).
    PauseProbesWhileScrolling(listState)

    Column(Modifier.fillMaxSize()) {
        HioneSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search ${tracks.size} songs by title, artist, album..."
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (sortedTracks.isEmpty()) {
                HioneEmptyState(
                    title = "NO TRACKS FOUND",
                    subtitle = if (searchQuery.isNotBlank()) "No songs match \"$searchQuery\"" else "No songs in library",
                    imageRes = HioneArt.chibiHearts,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(sortedTracks.size, key = { sortedTracks[it].id }, contentType = { "track" }) { i ->
                        TrackRow(sortedTracks[i]) { onPlay(sortedTracks, i) }
                    }
                }
                if (searchQuery.isBlank()) {
                    AlphabetFastScroller(
                        items = sortedTracks,
                        getItemName = { it.title },
                        onScrollTo = { idx -> listState.scrollToItem(idx) },
                        firstVisibleIndex = { listState.firstVisibleItemIndex },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}

@Composable private fun ArtistList(
    artists: List<ArtistGroup>,
    loading: Boolean = false,
    sortIgnoreThe: Boolean = true,
    displayTheMode: String = "PREFIX",
    onUpdateSort: (Boolean) -> Unit = {},
    onUpdateDisplay: (String) -> Unit = {},
    onOpen: (ArtistGroup) -> Unit,
    onShuffle: (ArtistGroup) -> Unit = {},
    listState: LazyListState = rememberLazyListState()
) {
    var showSettingsModal by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredArtists = remember(artists, searchQuery) {
        if (searchQuery.isBlank()) artists
        else {
            val q = searchQuery.trim().lowercase()
            val qf = searchFold(searchQuery)
            artists.filter {
                it.name.lowercase().contains(q) ||
                    (qf.isNotEmpty() && searchFold(it.name).contains(qf))
            }
        }
    }
    val scope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }
    // Don't queue per-track SD-card probes for rows flying past mid-fling (see the function).
    PauseProbesWhileScrolling(listState)

    Column(Modifier.fillMaxSize()) {
        HioneSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search ${artists.size} artists..."
        )
        Box(Modifier.weight(1f).fillMaxWidth().hioneHazeSource(androidx.compose.ui.platform.LocalContext.current, hazeState)) {
            if (loading && filteredArtists.isEmpty()) {
                // Still waiting on the first queryTracks() to resolve — a real "0 artists" empty
                // state would be misleading here; this isn't a verdict on the library yet.
                androidx.compose.material3.CircularProgressIndicator(
                    color = HioneTealBright,
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                )
            } else if (filteredArtists.isEmpty()) {
                HioneEmptyState(
                    title = "NO ARTISTS FOUND",
                    subtitle = if (searchQuery.isNotBlank()) "No artists match \"$searchQuery\"" else "No artists in library",
                    imageRes = HioneArt.chibiHearts,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 36.dp)
                ) {
                    item(contentType = "header") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${filteredArtists.size} Artists",
                                color = HioneTealBright,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Row(
                                Modifier
                                    .glassCard(corner = 16.dp)
                                    .clickable { showSettingsModal = true }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Tune, "Sort options", tint = HioneTealBright, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (sortIgnoreThe) "Sorted under 'C' (Ignore 'The')" else "Sorted under 'T' (Include 'The')",
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Guaranteed-unique even in the rare case two distinct canonical-key groups
                    // format to the identical display name (Compose throws "Key ... was already
                    // used" on a real duplicate, which silently drops that row from view) —
                    // appending a representative track id as a tiebreaker costs nothing when names
                    // are already unique (the common case) and fixes it when they aren't.
                    items(filteredArtists.size, key = { "${filteredArtists[it].name}#${filteredArtists[it].tracks.firstOrNull()?.id ?: it}" }, contentType = { "artist" }) { i ->
                        val a = filteredArtists[i]
                        val rowCtx = LocalContext.current
                        // PERF (scroll jank, 2026-09-17), per artist row that scrolls into view:
                        //  - coverTrack() was called on every recomposition: a canonicalArtistKey
                        //    lookup plus a SharedPreferences read plus a key-string allocation,
                        //    for a value that can only change when the artist or its tracks do.
                        //    remember()ed on exactly that.
                        //  - computeQualityBreakdown() walked the artist's ENTIRE track list on
                        //    the MAIN THREAD (hundreds of tracks for a prolific artist), allocated
                        //    a format string + a counting map per call, registered a Compose
                        //    snapshot read for every one of those tracks, and launched one IO
                        //    probe coroutine per unprobed file from inside composition. The
                        //    remember() key made it survive recomposition but NOT scrolling out
                        //    of and back into view, so a fling paid all of that per row, twice
                        //    (down and back). qualityForGroup() is a map lookup that computes the
                        //    same breakdown once, off the main thread, and wakes the row when it
                        //    lands — see TrackTech's group-quality cache.
                        val reprTrack = remember(a.name, a.tracks) { a.coverTrack(rowCtx) }
                        val aQuality = TrackTech.qualityForGroup(rowCtx, a.tracks)
                        // One lookup per row instead of two (name colour + heart): each one
                        // canonicalises the artist name and then scans the liked-artists list.
                        val artistLiked = LikeStore.isArtistLiked(a.name, rowCtx)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)
                                .pressableGlassCard { onOpen(a) }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(48.dp)) {
                                // Real artist photo when one is known (see artistart/); album art / placeholder otherwise.
                                ArtistPhotoImage(artist = a, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(50))) {
                                    if (reprTrack != null) {
                                        AlbumArtImage(trackId = reprTrack.id, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(50)), trackPath = reprTrack.path)
                                    } else {
                                        CircleArt()
                                    }
                                }
                                // Circular Glanceable Quality Symbol Overlay (over bottom-end of avatar)
                                AudioQualityCrestOverlay(
                                    breakdown = aQuality,
                                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp),
                                    size = 18.dp
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, color = if (artistLiked) HioneTeal else Mascot.color(0xFFE8F4F2), fontSize = 16.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontFamily = Baloo2Font)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${a.tracks.size} tracks · ${a.albumCount} albums", color = Muted, fontSize = 12.5.sp, fontFamily = Baloo2Font)
                                    // Guarded on totalTracks like every other quality widget
                                    // (AudioQualityCrestOverlay / AudioQualitySpecLine already
                                    // were): the breakdown now arrives a beat after the row binds,
                                    // and the placeholder's "No audio" must never flash here.
                                    if (aQuality.totalTracks > 0) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("·", color = Muted.copy(alpha = 0.85f), fontSize = 12.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            aQuality.specTag,
                                            color = when (aQuality.highestTier) {
                                                4 -> Color(0xFFDFB8FF)
                                                3 -> Color(0xFFFFD166)
                                                2 -> HioneTealBright
                                                else -> Muted
                                            },
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            HapticIconButton(onClick = { onShuffle(a) }) {
                                Icon(Icons.Default.Shuffle, "Shuffle Artist", tint = HioneTealBright, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            ArtistRainbowHeart(artistLiked, size = 48.dp) { LikeStore.toggleArtist(rowCtx, a.name) }
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }

                if (searchQuery.isBlank()) {
                    AlphabetFastScroller(
                        items = filteredArtists,
                        getItemName = { it.name },
                        onScrollTo = { idx ->
                            scope.launch { listState.scrollToItem((idx + 1).coerceAtMost(filteredArtists.size)) }
                        },
                        firstVisibleIndex = { (listState.firstVisibleItemIndex - 1).coerceAtLeast(0) },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(bottom = 12.dp)
                    )
                }
            }
        }

        if (showSettingsModal) {
            ArtistSortSettingsModal(
                hazeState = hazeState,
                sortIgnoreThe = sortIgnoreThe,
                displayTheMode = displayTheMode,
                onUpdateSort = onUpdateSort,
                onUpdateDisplay = onUpdateDisplay,
                onDismiss = { showSettingsModal = false }
            )
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ArtistSortSettingsModal(
    hazeState: HazeState,
    sortIgnoreThe: Boolean,
    displayTheMode: String,
    onUpdateSort: (Boolean) -> Unit,
    onUpdateDisplay: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x59000000))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hioneGlassPanel(
                    androidx.compose.ui.platform.LocalContext.current, hazeState,
                    HazeMaterials.thick(Mascot.color(0xFF07191C)), Mascot.color(0xFF07191C),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
                .border(1.dp, HioneTeal.copy(alpha = 0.5f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    enabled = false
                ) {}
                .padding(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Artists starting with 'The\'",
                    color = HioneTealBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                HapticIconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White)
                }
            }

            Spacer(Modifier.height(14.dp))

            Text("SORTING", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(8.dp))

            // Option 1: Ignore "The" (Default)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sortIgnoreThe) Mascot.color(0xFF0E383B) else Mascot.color(0xFF041416))
                    .clickable { onUpdateSort(true) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = sortIgnoreThe, onClick = { onUpdateSort(true) })
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Sort under 'C' (Ignore 'The')", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("e.g., 'The Crystal Method' appears under 'C'", color = Muted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Option 2: Include "The"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (!sortIgnoreThe) Mascot.color(0xFF0E383B) else Mascot.color(0xFF041416))
                    .clickable { onUpdateSort(false) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = !sortIgnoreThe, onClick = { onUpdateSort(false) })
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Sort under 'T' (Include 'The')", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("e.g., 'The Crystal Method' appears under 'T'", color = Muted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("DISPLAY FORMAT", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(8.dp))

            listOf(
                "PREFIX" to ("'The Crystal Method'" to "Keep 'The' at the beginning"),
                "SUFFIX" to ("'Crystal Method, The'" to "Move 'The' to the end"),
                "STRIP" to ("'Crystal Method'" to "Hide 'The' prefix entirely")
            ).forEach { (modeKey, titleDesc) ->
                val selected = displayTheMode == modeKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Mascot.color(0xFF0E383B) else Mascot.color(0xFF041416))
                        .clickable { onUpdateDisplay(modeKey) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = { onUpdateDisplay(modeKey) })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(titleDesc.first, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(titleDesc.second, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Best-effort "show the whole title" text: shrinks one point at a time until it fits on one
 *  line instead of ellipsizing. A long messy tag (a real one: "Fear, and Loathing in Las Vegas")
 *  used to just get cut off — this reads the full name a little smaller rather than hiding it. */
@Composable private fun ShrinkToFitTitle(
    text: String,
    color: Color,
    maxFontSize: androidx.compose.ui.unit.TextUnit,
    minFontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight = FontWeight.Black,
    fontFamily: FontFamily? = null,
    modifier: Modifier = Modifier
) {
    var fontSize by remember(text) { mutableStateOf(maxFontSize) }
    Text(
        text,
        color = color,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize.value > minFontSize.value) {
                fontSize = (fontSize.value - 1f).sp
            }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalHazeMaterialsApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun ArtistDetail(
    a: ArtistGroup,
    back: () -> Unit,
    onAlbum: (AlbumGroup) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val ctx = LocalContext.current
    // Keyed on canonicalArtistKey(), not the raw display name — see coverTrack()'s doc comment in
    // Model.kt for why: `a.name` is display-mode-formatted and mutable (PREFIX/SUFFIX/STRIP "The").
    val artistKey = remember(a.name) { canonicalArtistKey(a.name, ctx) }
    var coverOverride by remember(artistKey) { mutableStateOf(PlayerPreferences.loadArtistCoverTrack(ctx, artistKey)) }
    val reprTrack = remember(artistKey, coverOverride, a.tracks) {
        coverOverride?.let { id -> a.tracks.firstOrNull { it.id == id } } ?: a.tracks.firstOrNull()
    }
    // Keyed on a.tracks (not just a.name) — a rescan that adds/removes this artist's tracks
    // produces a new ArtistGroup with the same name but a different track list; keying on name
    // alone froze "Albums (N)"/"▶ N plays" at whatever they were when the screen first opened.
    val albums = remember(a.tracks) { a.tracks.albums() }
    val totalPlays = remember(a.tracks) { a.tracks.sumOf { PlayerPreferences.loadPlayCount(ctx, it.id) } }
    val hazeState = remember { HazeState() }
    // Don't queue per-track SD-card probes for rows flying past mid-fling (see the function).
    PauseProbesWhileScrolling(listState)

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // 1. Fullscreen Hero Header Banner (Spotify Style)
        item {
            Box(
                Modifier.fillMaxWidth().height(210.dp)
            ) {
                // Background Cover Artwork — marked as the haze blur SOURCE so the text panel
                // below can show a real frosted-glass view of it instead of a flat dark scrim.
                // Real artist photo when one is known (see artistart/); the album art below otherwise.
                ArtistPhotoImage(artist = a, modifier = Modifier.fillMaxSize().hioneHazeSource(ctx, hazeState)) {
                    if (reprTrack != null) {
                        AlbumArtImage(
                            trackId = reprTrack.id,
                            modifier = Modifier.fillMaxSize().hioneHazeSource(ctx, hazeState),
                            trackPath = reprTrack.path
                        )
                    } else {
                        Box(Modifier.fillMaxSize().hioneHazeSource(ctx, hazeState).background(Mascot.color(0xFF0C2B2E)))
                    }
                }

                // Light top scrim only, for status-bar legibility — the text block below now
                // sits on its own real glass panel instead of needing a heavy full-height scrim.
                Box(
                    Modifier.fillMaxWidth().height(90.dp).align(Alignment.TopStart).background(
                        Brush.verticalGradient(listOf(Mascot.color(0x66041416), Color.Transparent))
                    )
                )
                // "ⓘ" photo credit — renders nothing unless a real artist photo is in use.
                ArtistPhotoCreditButton(a, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp))

                // Artist Header Text & Actions — real frosted glass over the blurred art behind it.
                Column(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .hioneGlassPanel(
                            androidx.compose.ui.platform.LocalContext.current, hazeState,
                            HazeMaterials.thick(Mascot.color(0xFF041416)), Mascot.color(0xFF041416),
                            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ARTIST", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        if (totalPlays > 0) {
                            Text("  ·  ▶ $totalPlays plays", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    ShrinkToFitTitle(
                        a.name,
                        color = Color.White,
                        maxFontSize = 24.sp,
                        minFontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                    Text(
                        "${albums.size} Albums  ·  ${a.tracks.size} Songs",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Glanceable Audio Fidelity Spec Line for Artist
                    // PERF (2026-09-17): off the main thread + cached (see TrackTech.qualityForGroup).
                    val artistQualityBreakdown = TrackTech.qualityForGroup(ctx, a.tracks)
                    Spacer(Modifier.height(6.dp))
                    AudioQualitySpecLine(artistQualityBreakdown)

                    Spacer(Modifier.height(10.dp))

                    // Action Row: Play All, Shuffle, Rainbow Heart
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Brush.horizontalGradient(listOf(HioneTeal, HionePink)))
                                .clickable { if (a.tracks.isNotEmpty()) onPlay(a.tracks, 0) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Mascot.color(0xFF121318), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play All", color = Mascot.color(0xFF121318), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Mascot.color(0xFF0F2B2E))
                                .border(1.dp, HioneTeal.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                                .clickable { if (a.tracks.isNotEmpty()) onPlay(a.tracks.shuffled(), 0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Shuffle, "Shuffle", tint = HioneTealBright, modifier = Modifier.size(18.dp))
                        }

                        Spacer(Modifier.width(10.dp))

                        // Hione Radio seeded from this artist (taste/StationEngine).
                        com.hione.player.taste.StationSeedButton(seed = { com.hione.player.taste.StationEngine.Seed.FromArtist(a.name, a.tracks) }, size = 36.dp)

                        Spacer(Modifier.width(10.dp))

                        ArtistRainbowHeart(LikeStore.isArtistLiked(a.name, ctx), size = 54.dp) { LikeStore.toggleArtist(ctx, a.name) }
                    }
                }
            }
        }

        // 2. Albums Section — wrapping grid (no left/right off-screen scroll)
        if (albums.isNotEmpty()) {
            item {
                SectionHeader("Albums (${albums.size})")
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    albums.forEachIndexed { i, al ->
                        val albumRepr = al.tracks.firstOrNull()
                        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                        // PERF (2026-09-17): this FlowRow composes EVERY album of the artist in a
                        // single LazyColumn item, so opening a prolific artist ran one full
                        // O(tracks) breakdown pass per album back-to-back on the main thread.
                        // Cached/backgrounded like the list rows.
                        val alQuality = TrackTech.qualityForGroup(ctx, al.tracks)
                        Column(
                            // 108dp (was 112) so THREE album tiles fit per row on the 360dp-wide
                            // M500 (3*108 + 2*4dp gaps = 332 <= 336dp usable) instead of two.
                            Modifier.width(108.dp).padding(vertical = 2.dp).combinedClickable(
                                onClick = { onAlbum(al) },
                                onLongClick = {
                                    if (albumRepr != null) {
                                        Haptics.heavy(ctx)
                                        PlayerPreferences.saveArtistCoverTrack(ctx, a.name, albumRepr.id)
                                        coverOverride = albumRepr.id
                                        android.widget.Toast.makeText(ctx, "Set as ${a.name}'s cover", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        ) {
                            Box(Modifier.size(108.dp).clip(RoundedCornerShape(14.dp))) {
                                if (albumRepr != null) {
                                    AlbumArtImage(trackId = albumRepr.id, modifier = Modifier.fillMaxSize(), trackPath = albumRepr.path, year = albumRepr.year)
                                } else {
                                    Box(Modifier.fillMaxSize().background(Mascot.color(0xFF123438)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Album, null, tint = HioneTeal.copy(alpha = .6f), modifier = Modifier.size(42.dp))
                                    }
                                }
                                if (albumRepr != null && coverOverride == albumRepr.id) {
                                    Icon(Icons.Default.PushPin, "Pinned as cover", tint = HionePink,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
                                }
                                // Glanceable Quality Crest Overlay
                                AudioQualityCrestOverlay(
                                    breakdown = alQuality,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                                    size = 22.dp
                                )
                            }
                            val yr = al.tracks.firstNotNullOfOrNull { TrackYear.yearFor(ctx, it)?.takeIf { y -> y > 0 } ?: it.year.takeIf { y -> y > 0 } } ?: 0
                            Text(al.name + if (yr > 0) "  ·  $yr" else "", color = Mascot.color(0xFFE8F4F2), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Guarded — see the artist row: the breakdown resolves asynchronously.
                            if (alQuality.totalTracks > 0) Text(
                                alQuality.specTag,
                                color = when (alQuality.highestTier) {
                                    4 -> Color(0xFFDFB8FF)
                                    3 -> Color(0xFFFFD166)
                                    2 -> HioneTealBright
                                    else -> Muted
                                },
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 3. Songs Section
        item { SectionHeader("All Songs (${a.tracks.size})") }
        itemsIndexed(a.tracks) { i, t ->
            TrackRow(t) { onPlay(a.tracks, i) }
        }
    }
}

@Composable private fun AlbumGrid(
    albums: List<AlbumGroup>,
    loading: Boolean = false,
    onOpen: (AlbumGroup) -> Unit,
    onShuffle: (AlbumGroup) -> Unit = {},
    onPlay: (AlbumGroup) -> Unit = {},   // long-press a tile: play the album in order, no detail hop
    gridState: LazyGridState = rememberLazyGridState()
) {
    val ctx = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val filteredAlbums = remember(albums, searchQuery) {
        if (searchQuery.isBlank()) albums
        else {
            val q = searchQuery.trim().lowercase()
            albums.filter {
                val qf = searchFold(q)
                it.name.lowercase().contains(q) || it.artist.lowercase().contains(q) ||
                    (qf.isNotEmpty() &&
                        (searchFold(it.name).contains(qf) || searchFold(it.artist).contains(qf)))
            }
        }
    }
    val scope = rememberCoroutineScope()
    // Don't queue per-track SD-card probes for tiles flying past mid-fling (see the function).
    PauseProbesWhileScrolling(gridState)

    Column(Modifier.fillMaxSize()) {
        HioneSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search ${albums.size} albums by title or artist..."
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading && filteredAlbums.isEmpty()) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = HioneTealBright,
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                )
            } else if (filteredAlbums.isEmpty()) {
                HioneEmptyState(
                    title = "NO ALBUMS FOUND",
                    subtitle = if (searchQuery.isNotBlank()) "No albums match \"$searchQuery\"" else "No albums in library",
                    imageRes = HioneArt.chibiHearts,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                    // name+artist alone isn't guaranteed unique — confirmed live crash: two
                    // distinct album groups both computed to "Humanz (Japanese Edition) [CD-02]" /
                    // "Gorillaz" (duplicate disc rip under a different folder, same shape as the
                    // earlier Crystal Method duplicate-untagged-folder finding), which crashed the
                    // whole grid with "Key ... was already used." Tiebreak with a representative
                    // track id, same fix already applied to the Artists list's key.
                    items(filteredAlbums.size, key = { "${filteredAlbums[it].name}#${filteredAlbums[it].artist}#${filteredAlbums[it].tracks.firstOrNull()?.id ?: it}" }, contentType = { "album" }) { idx ->
                        val al = filteredAlbums[idx]
                        val reprTrack = al.tracks.firstOrNull()
                        val albumPlays = remember(al.tracks) { al.tracks.sumOf { PlayerPreferences.loadPlayCount(ctx, it.id) } }
                        Column(
                            Modifier.padding(6.dp)
                                .pressableGlassCard(
                                    corner = 16.dp,
                                    onLongClick = { Haptics.tick(ctx); onPlay(al) },
                                    onClick = { onOpen(al) }
                                )
                                .padding(8.dp)
                        ) {
                            // PERF (2026-09-17): same change as the artist row — this walked the
                            // album's whole track list on the main thread on every tile bind (and
                            // launched a probe coroutine per unprobed track from composition).
                            // Now a cached lookup computed once on the background probe pool.
                            val alQuality = TrackTech.qualityForGroup(ctx, al.tracks)
                            // Album Art Box with Overlays
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Mascot.color(0xFF0F2B2E))
                            ) {
                                if (reprTrack != null) {
                                    AlbumArtImage(trackId = reprTrack.id, modifier = Modifier.fillMaxSize(), trackPath = reprTrack.path, year = reprTrack.year)
                                } else {
                                    Box(Modifier.fillMaxSize().background(Mascot.color(0xFF123438)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Album, null, tint = HioneTeal.copy(alpha = .6f), modifier = Modifier.size(46.dp))
                                    }
                                }

                                // Glanceable Quality Symbol Overlay (Bottom-Start Corner)
                                AudioQualityCrestOverlay(
                                    breakdown = alQuality,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(5.dp),
                                    size = 22.dp
                                )

                                // Quick Shuffle Button Overlay
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(5.dp)
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(15.dp))
                                        .background(Mascot.color(0xDD041416))
                                        .border(1.dp, HioneTeal.copy(alpha = 0.5f), RoundedCornerShape(15.dp))
                                        .clickable { onShuffle(al) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Shuffle, "Shuffle Album", tint = HioneTealBright, modifier = Modifier.size(15.dp))
                                }
                            }
                            Text(
                                al.name,
                                color = Mascot.color(0xFFE8F4F2),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            val yr = remember(al.tracks) {
                                al.tracks.firstNotNullOfOrNull { TrackYear.yearFor(ctx, it)?.takeIf { y -> y > 0 } ?: it.year.takeIf { y -> y > 0 } } ?: 0
                            }
                            val playsTag = if (albumPlays > 0) "  ·  ▶ $albumPlays" else ""
                            Text(al.artist + (if (yr > 0) "  ·  $yr" else "") + playsTag, color = if (yr > 0) Mascot.color(0xFFC0DDD9) else Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Pressing/edition tag when the album title carries one
                            releaseTag(al.name)?.let { tag ->
                                DataChip(tag, ReleaseTagColor)
                            }
                            if (al.hasDiscImage) DataChip(if (al.unsplitImageCount > 0) "FULL-CD RIP" else "CD RIP · CUE SPLIT", DiscImageColor)
                            // Guarded — see the artist row: the breakdown resolves asynchronously.
                            if (alQuality.totalTracks > 0) Text(
                                alQuality.specTag,
                                color = when {
                                    alQuality.isVinylRip -> Color(0xFFFFB300)
                                    alQuality.highestTier == 4 -> Color(0xFFDFB8FF)
                                    alQuality.highestTier == 3 -> Color(0xFFFFD166)
                                    alQuality.highestTier == 2 -> HioneTealBright
                                    else -> formatColor(al.tracks.first().mime)
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (searchQuery.isBlank()) {
                    AlphabetFastScroller(
                        items = filteredAlbums,
                        getItemName = { it.name },
                        onScrollTo = { idx -> gridState.scrollToItem(idx) },
                        firstVisibleIndex = { (gridState.firstVisibleItemIndex * 2).coerceIn(0, (filteredAlbums.size - 1).coerceAtLeast(0)) },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable private fun DetailList(
    title: String,
    tracks: List<Track>,
    back: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    likeAlbum: String? = null,
    onOpenArtist: ((String) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    footer: (@Composable () -> Unit)? = null
) {
    val ctx = LocalContext.current
    val sortedTracks = remember(tracks) { sortAlbumTracks(tracks) }
    val reprTrack = sortedTracks.firstOrNull()
    val albumArtist = reprTrack?.albumArtist?.ifBlank { reprTrack.artist } ?: reprTrack?.artist ?: "Various Artists"
    val year = remember(sortedTracks) {
        sortedTracks.firstNotNullOfOrNull { TrackYear.yearFor(ctx, it)?.takeIf { y -> y > 0 } ?: it.year.takeIf { y -> y > 0 } } ?: 0
    }
    val totalPlays = remember(sortedTracks) { sortedTracks.sumOf { PlayerPreferences.loadPlayCount(ctx, it.id) } }
    val hazeState = remember { HazeState() }
    // Don't queue per-track SD-card probes for rows flying past mid-fling (see the function).
    PauseProbesWhileScrolling(listState)

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        // Hero Album Banner Header (Spotify Style) — full-bleed blurred album art as the backdrop
        // (was a flat two-tone gradient, unrelated to what album you were actually looking at),
        // real glass panel behind the title block instead of sitting straight on the gradient.
        item {
            Box(Modifier.fillMaxWidth()) {
                if (reprTrack != null) {
                    AlbumArtImage(
                        trackId = reprTrack.id,
                        modifier = Modifier.matchParentSize().hioneHazeSource(ctx, hazeState),
                        trackPath = reprTrack.path
                    )
                } else {
                    Box(Modifier.matchParentSize().hioneHazeSource(ctx, hazeState).background(Mascot.color(0xFF123F44)))
                }
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Mascot.color(0x99123F44), Mascot.color(0xCC041416)))))

                // Like heart
                if (likeAlbum != null) {
                    Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
                        AlbumRainbowHeart(LikeStore.isAlbumLiked(albumArtist, likeAlbum, ctx), size = 54.dp) { LikeStore.toggleAlbum(ctx, albumArtist, likeAlbum) }
                    }
                }
                Column(
                    Modifier.fillMaxWidth()
                        .hioneGlassPanel(ctx, hazeState, HazeMaterials.regular(Mascot.color(0xFF041416)), Mascot.color(0xFF041416), fallbackAlpha = 0.82f)
                        .statusBarsPadding().padding(16.dp)
                ) {
                    // PERF (2026-09-17): off the main thread + cached (see TrackTech.qualityForGroup).
                    val albumQualityBreakdown = TrackTech.qualityForGroup(ctx, sortedTracks)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // Large Cover Artwork (120x120dp) with Glanceable Quality Crest Overlay
                        Box(Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)).background(Mascot.color(0xFF0C2B2E))) {
                            if (reprTrack != null) {
                                AlbumArtImage(trackId = reprTrack.id, modifier = Modifier.fillMaxSize(), trackPath = reprTrack.path, year = reprTrack.year)
                            } else {
                                Icon(Icons.Default.Album, null, tint = HioneTeal, modifier = Modifier.size(50.dp).align(Alignment.Center))
                            }
                            // Quality Emblem Overlay (Bottom-End Corner)
                            AudioQualityCrestOverlay(
                                breakdown = albumQualityBreakdown,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                                size = 26.dp
                            )
                        }

                        Spacer(Modifier.width(16.dp))

                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("ALBUM", color = HioneTealBright, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                releaseTag(title)?.let { tag ->
                                    Spacer(Modifier.width(6.dp))
                                    DataChip(tag, ReleaseTagColor)
                                }
                            }
                            ShrinkToFitTitle(title, color = Color.White, maxFontSize = 19.sp, minFontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                            
                            // Clickable Artist Link
                            Text(
                                albumArtist,
                                color = HionePink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onOpenArtist?.invoke(albumArtist) }
                            )

                            Spacer(Modifier.height(4.dp))
                            val yearText = if (year > 0) "$year  ·  " else ""
                            Text("$yearText${sortedTracks.size} tracks${if (totalPlays > 0) "  ·  ▶ $totalPlays plays" else ""}", color = Muted, fontSize = 12.sp)
                            val unsplitImages = sortedTracks.count { it.isDiscImage && it.parentId == 0L }
                            val virtualFromCue = sortedTracks.count { it.parentId != 0L }
                            if (unsplitImages > 0 || virtualFromCue > 0) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    when {
                                        unsplitImages > 0 && sortedTracks.size == unsplitImages && unsplitImages == 1 -> "This is a single-file CD rip. The whole disc plays as one track."
                                        unsplitImages > 0 -> "Single-file CD rip · $unsplitImages disc image${if (unsplitImages > 1) "s" else ""} play as whole-disc tracks"
                                        else -> "Single-file CD rip · $virtualFromCue tracks split from its cue sheet"
                                    },
                                    color = DiscImageColor, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp
                                )
                            }

                            // Glanceable Data Metric Text Spec Line
                            Spacer(Modifier.height(4.dp))
                            AudioQualitySpecLine(albumQualityBreakdown)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Play & Shuffle Action Row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Brush.horizontalGradient(listOf(HioneTeal, HionePink)))
                                .clickable { if (sortedTracks.isNotEmpty()) onPlay(sortedTracks, 0) }
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Mascot.color(0xFF121318), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play Album", color = Mascot.color(0xFF121318), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(Mascot.color(0xFF0F2B2E))
                                .border(1.dp, HioneTeal.copy(alpha = 0.5f), RoundedCornerShape(19.dp))
                                .clickable { if (sortedTracks.isNotEmpty()) onPlay(sortedTracks.shuffled(), 0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Shuffle, "Shuffle", tint = HioneTealBright, modifier = Modifier.size(18.dp))
                        }

                        Spacer(Modifier.width(10.dp))

                        // Hione Radio seeded from this album (taste/StationEngine).
                        com.hione.player.taste.StationSeedButton(
                            seed = {
                                com.hione.player.taste.StationEngine.Seed.FromAlbum(
                                    title, sortedTracks.firstOrNull()?.let { it.albumArtist.ifBlank { it.artist } } ?: "", sortedTracks
                                )
                            },
                            size = 38.dp
                        )

                        Spacer(Modifier.width(10.dp))

                        // Play Next Album
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(Mascot.color(0xFF0F2B2E))
                                .border(1.dp, HioneTeal.copy(alpha = 0.5f), RoundedCornerShape(19.dp))
                                .clickable {
                                    PlayerHolder.player?.let { p -> QueueManager.playNext(ctx, p, sortedTracks) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlaylistPlay, "Play Album Next", tint = HioneTealBright, modifier = Modifier.size(20.dp))
                        }

                        Spacer(Modifier.width(10.dp))

                        // Add Album to Queue
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(Mascot.color(0xFF1C081A))
                                .border(1.dp, HionePink.copy(alpha = 0.5f), RoundedCornerShape(19.dp))
                                .clickable {
                                    PlayerHolder.player?.let { p -> QueueManager.playLast(ctx, p, sortedTracks) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlaylistAdd, "Add Album to Queue", tint = HionePink, modifier = Modifier.size(20.dp))
                        }
                    }

                    // Booklet / physical-media art viewer entry — renders nothing unless real art
                    // (cover/back/inlay/disc/booklet scans or a PDF) exists on disk for this album.
                    com.hione.player.booklet.AlbumBookletButton(
                        tracks = sortedTracks, albumTitle = title, qualityTag = albumQualityBreakdown.summaryTag
                    )
                }
            }
        }

        // Track List
        items(sortedTracks.size, key = { sortedTracks[it].id }, contentType = { "track" }) { i ->
            TrackRow(sortedTracks[i], showTrackNumber = true) { onPlay(sortedTracks, i) }
        }
        if (footer != null) item(key = "detail_footer") { footer() }
    }
}


/** Tiny bar-chart glyph — 1/2/3 bars, taller each tier — riding inside the bit-depth badge as its
 *  "more than a pill" identity mark. Cheap Canvas draw, no bitmaps, safe to repeat down a long list. */
@Composable fun BitTierGlyph(tier: Int, color: Color, size: androidx.compose.ui.unit.Dp = 9.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val bars = tier.coerceIn(1, 3)
        val barW = this.size.width / (bars * 1.7f)
        val gap = barW * 0.55f
        val heights = floatArrayOf(0.42f, 0.72f, 1.0f)
        val totalW = bars * barW + (bars - 1) * gap
        var x = (this.size.width - totalW) / 2f
        for (i in 0 until bars) {
            val h = this.size.height * heights[i]
            drawRoundRect(
                color, topLeft = Offset(x, this.size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = CornerRadius(barW * 0.35f, barW * 0.35f)
            )
            x += barW + gap
        }
    }
}

/** Tiny concentric-ring glyph — 1..4 rings, one per sample-rate tier — the sample-rate badge's
 *  own identity mark, deliberately a different silhouette language than the bit-depth bars. */
@Composable fun RateTierGlyph(tier: Int, color: Color, size: androidx.compose.ui.unit.Dp = 9.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val rings = tier.coerceIn(1, 4)
        val cx = this.size.width / 2f; val cy = this.size.height / 2f
        val maxR = this.size.minDimension / 2f
        for (i in 0 until rings) {
            val r = maxR * (0.34f + 0.22f * i)
            drawCircle(color, radius = r, center = Offset(cx, cy), style = Stroke(1.1.dp.toPx()))
        }
    }
}

/**
 * Kawaii Hione Audio Quality Badge Chip:
 *  - Tier 1 (16-bit / 44.1k-48k): Frosted Hione Cyan-Mint Glass Capsule with subtle ambient glow and '✧' starlet
 *  - Tier 2 (24-bit / 88.2k-96k): Radiant Gold & Cyan Crystal Gem with specular reflection and '✦' star
 *  - Tier 3 (176.4k-192k+ / 32-bit / DSD): Iridescent Rainbow Chromatic Heart-Crest with holographic prism sheen and '♥' heart
 *  - Format (FLAC / WAV / ALAC): Kawaii Hione Ribbon Hex-Gem
 */
@Composable fun TechBadgeChip(
    text: String,
    tier: Int = 1,
    glyph: String = "",
    brush: Brush? = null,
    glowColor: Color = HioneTeal,
    shape: Shape = BadgeShapes.kawaiiCapsule,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.sp,
    isRainbow: Boolean = false
) {
    val density = LocalDensity.current
    val effectiveBrush = brush ?: Brush.linearGradient(listOf(glowColor, glowColor))
    // Top-tier badges get a living holographic shimmer sweeping across them — the "more kawaii and
    // colorful, ESPECIALLY for the top tier" ask. The infinite transition itself is only ever
    // created when isRainbow is true (conditional composable call, not a conditional read of an
    // always-running one) — most badges in a real library aren't top-tier, so this keeps every
    // other badge's animation cost at exactly zero, not just "unused".
    val shimmerPos = if (isRainbow) {
        val shimmerTransition = rememberInfiniteTransition(label = "badgeShimmer")
        val pos by shimmerTransition.animateFloat(
            initialValue = -0.3f, targetValue = 1.3f,
            animationSpec = infiniteRepeatable(animation = tween(1600, easing = LinearEasing)),
            label = "shimmerPos"
        )
        pos
    } else 0f

    Box(
        modifier = Modifier
            .drawBehind {
                val outline = shape.createOutline(size, layoutDirection, density)
                val path = Path().apply { addOutline(outline) }

                // 1. Soft depth drop shadow
                translate(top = 1.0f) { drawPath(path, Color(0x66000000)) }

                // 2. Crystal glass core background
                val glassFill = if (isRainbow) {
                    Brush.verticalGradient(listOf(Color(0xF2190B26), Color(0xF8090312)))
                } else if (tier >= 2) {
                    Brush.verticalGradient(listOf(Color(0xF220190A), Color(0xF80A0702)))
                } else {
                    Brush.verticalGradient(listOf(Mascot.color(0xF20B1D20), Mascot.color(0xF8040F11)))
                }
                drawPath(path, glassFill)

                // 3. Subtle ambient inner radial glow in tier color (controlled so text is never washed out)
                if (isRainbow) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Mascot.color(0x35FF3399), Mascot.color(0x25FFC56B), Color.Transparent),
                            radius = size.maxDimension * 0.75f
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.5f)
                    )
                } else {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(glowColor.copy(alpha = 0.12f), Color.Transparent),
                            radius = size.maxDimension * 0.60f
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.5f)
                    )
                }

                // 4. Layered glowing neon strokes — dynamically scaled by fontSize for razor-sharp precision
                val scaleFactor = (fontSize.value / 10.0f).coerceIn(0.7f, 1.3f)
                val haloW = (if (isRainbow) 4.0f else 3.0f) * scaleFactor
                val midW = (if (isRainbow) 2.2f else 1.6f) * scaleFactor
                val coreW = (if (isRainbow) 1.2.dp.toPx() else 0.95.dp.toPx()) * scaleFactor

                // Outer soft halo
                drawPath(path, effectiveBrush, alpha = if (isRainbow) 0.16f else 0.10f, style = Stroke(haloW, cap = StrokeCap.Round))
                // Mid glow
                drawPath(path, effectiveBrush, alpha = if (isRainbow) 0.38f else 0.28f, style = Stroke(midW, cap = StrokeCap.Round))
                // Crisp bright core rim
                drawPath(path, effectiveBrush, alpha = 0.92f, style = Stroke(coreW, cap = StrokeCap.Round))

                // 5. Specular curved glass sheen / glint
                clipPath(path) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0x40FFFFFF), Color(0x0CFFFFFF), Color.Transparent),
                            radius = size.minDimension * 0.65f
                        ),
                        radius = size.minDimension * 0.55f,
                        center = Offset(size.width * 0.5f, size.height * 0.12f)
                    )

                    // Prismatic rainbow shimmer streak for top tier
                    if (isRainbow) {
                        val sx = size.width * shimmerPos
                        drawLine(
                            Brush.linearGradient(
                                listOf(Color(0x00FFFFFF), Color(0x88FFFFFF), Color(0x00FFFFFF)),
                                start = Offset(sx - size.width * 0.18f, 0f),
                                end = Offset(sx + size.width * 0.18f, size.height)
                            ),
                            start = Offset(sx - size.width * 0.18f, 0f),
                            end = Offset(sx + size.width * 0.18f, size.height),
                            strokeWidth = 1.2.dp.toPx()
                        )
                    }

                    // 6. Cut-gem facet texture
                    val facetCount = when { isRainbow -> 6; tier >= 2 -> 4; else -> 3 }
                    val cx = size.width * 0.5f
                    val cy = size.height * 0.5f
                    val facetReach = size.maxDimension * 0.62f
                    for (i in 0 until facetCount) {
                        val a = (2.0 * Math.PI * i / facetCount) + (Math.PI / 6)
                        val fx = cx + (facetReach * kotlin.math.cos(a)).toFloat()
                        val fy = cy + (facetReach * kotlin.math.sin(a)).toFloat()
                        drawLine(Color.White.copy(alpha = if (isRainbow) 0.18f else 0.11f), Offset(cx, cy), Offset(fx, fy), 0.5.dp.toPx())
                    }
                    if (isRainbow) {
                        val sparkleAngles = listOf(0.4, 2.6, 4.3)
                        for (a in sparkleAngles) {
                            val px = cx + (facetReach * 0.72f * kotlin.math.cos(a)).toFloat()
                            val py = cy + (facetReach * 0.72f * kotlin.math.sin(a)).toFloat()
                            drawCircle(Color.White.copy(alpha = 0.75f), radius = 0.75.dp.toPx(), center = Offset(px, py))
                        }
                    }
                }
            }
            // Extra end padding so glyph corners clear the pointed tips of the diamond/pentagon.
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val effectiveScale = 1.0f + (fontScale - 1.0f) * 0.20f
        val dampedFontSize = (fontSize.value / fontScale * effectiveScale).sp
        Text(
            text,
            color = Color.White,
            fontSize = dampedFontSize,
            fontWeight = FontWeight.Black,
            fontFamily = OrbitronFont,
            letterSpacing = 0.3.sp,
            maxLines = 1,
            softWrap = false,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            // Orbitron carries tall ascent/descent metrics — with default font padding the glyphs
            // sat visibly low and off-center inside the gem. Trim the padding, center the line box.
            lineHeight = dampedFontSize,
            style = androidx.compose.ui.text.TextStyle(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                )
            )
        )
    }
}

/** Legacy / Overload wrapper for single color calls */
@Composable fun TechBadgeChip(
    text: String,
    color: Color,
    shape: Shape,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.5.sp
) {
    val tier = when {
        color == HionePink || color == Color(0xFFFF5C5C) || color == Mascot.color(0xFFB9C2D0) -> 3
        color == HioneGold || color == Color(0xFF4FC3F7) -> 2
        else -> 1
    }
    val isRainbow = tier >= 3
    val brush = if (isRainbow) HioneBadgePalette.RainbowBrush else if (tier == 2) HioneBadgePalette.HiResBrush else Brush.linearGradient(listOf(color, color))
    val glyph = if (tier >= 3) "♥" else if (tier == 2) "✦" else "✧"
    TechBadgeChip(
        text = text,
        tier = tier,
        glyph = glyph,
        brush = brush,
        glowColor = color,
        shape = shape,
        fontSize = fontSize,
        isRainbow = isRainbow
    )
}

/** Sample-rate + bit-depth chips together with full Kawaii Hione tiered styling. */
/** Dedicated Retro-Analog Gold & Obsidian Vinyl Rip Badge Chip. */
@Composable fun VinylBadgeChip(
    fontSize: androidx.compose.ui.unit.TextUnit = 9.5.sp,
    isCompact: Boolean = false
) {
    TechBadgeChip(
        text = if (isCompact) "VINYL" else "VINYL RIP",
        tier = 3,
        glyph = "⊚",
        brush = Brush.horizontalGradient(listOf(Color(0xFFFFD54F), Color(0xFFFF9800), Color(0xFFFF6D00))),
        glowColor = Color(0xFFFF9800),
        shape = BadgeShapes.kawaiiHexGem,
        fontSize = fontSize,
        isRainbow = false
    )
}

/** Sample-rate + bit-depth chips together with full Kawaii Hione tiered styling & Vinyl Rip detection. */
@Composable fun TechBadgeRow(
    ctx: android.content.Context,
    track: Track,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.5.sp,
    spacing: androidx.compose.ui.unit.Dp = 5.dp,
    includeFormat: Boolean = false
) {
    val bits = TrackTech.bitsFor(ctx, track)
    val sr = TrackTech.sampleRateFor(ctx, track)
    val fmt = track.mime.substringAfterLast('/').uppercase().ifBlank { null }
    val isVinyl = remember(track.path, track.album, track.title) { TrackTech.isVinyl(track) }
    var shown = false

    // Own Row so the chips always lay out side-by-side regardless of the parent (a Box parent
    // used to draw the bit-depth and sample-rate chips on top of each other).
    Row(verticalAlignment = Alignment.CenterVertically) {
    if (isVinyl) {
        VinylBadgeChip(fontSize = fontSize)
        shown = true
    }

    // External USB DAC engaged (HioneUsbDacOutput routed the player): "USB DAC" chip, bit-perfect tier.
    if (HioneUsbDacOutput.routed) {
        if (shown) Spacer(Modifier.width(spacing))
        TechBadgeChip(
            text = if (HioneUsbDacOutput.mixerMode == "BIT-PERFECT") "USB DAC ★" else "USB DAC",
            tier = 3,
            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(HioneTealBright, HionePink)),
            glowColor = HioneTealBright,
            shape = BadgeShapes.kawaiiHexGem,
            fontSize = fontSize,
            isRainbow = false
        )
        shown = true
    }

    if (includeFormat && fmt != null) {
        if (shown) Spacer(Modifier.width(spacing))
        val fmtTier = TrackTech.formatTier(track.mime)
        TechBadgeChip(
            text = fmt,
            tier = fmtTier,
            brush = TrackTech.formatBrush(track.mime),
            glowColor = formatColor(track.mime),
            shape = BadgeShapes.kawaiiHexGem,
            fontSize = fontSize,
            isRainbow = fmtTier >= 3
        )
        shown = true
    }

    if (bits != null && bits > 0) {
        if (shown) Spacer(Modifier.width(spacing))
        val tier = TrackTech.bitTier(bits)
        val isRainbow = tier >= 3
        TechBadgeChip(
            text = "$bits-BIT",
            tier = tier,
            glyph = TrackTech.glyphForBitTier(tier),
            brush = TrackTech.bitBrush(bits),
            glowColor = TrackTech.color(bits),
            shape = TrackTech.bitShape(bits),
            fontSize = fontSize,
            isRainbow = isRainbow
        )
        shown = true
    }

    if (sr != null && sr > 0) {
        if (shown) Spacer(Modifier.width(spacing))
        val tier = TrackTech.rateTier(sr)
        val isRainbow = tier >= 3
        TechBadgeChip(
            text = TrackTech.formatSampleRate(sr).uppercase(),
            tier = tier,
            glyph = TrackTech.glyphForRateTier(tier),
            brush = TrackTech.rateBrush(sr),
            glowColor = TrackTech.rateColor(sr),
            shape = TrackTech.rateShape(sr),
            fontSize = fontSize,
            isRainbow = isRainbow
        )
    }
    }
}

/** Compact "at a glance" quality meter for an artist/album list row — a speedometer-style arc,
 *  no text, colored teal→gold→pink by tier (same hotter-is-better language as every other quality
 *  signal in the app) so you can eyeball a whole artist/album's general recording quality without
 *  reading a single badge. Renders nothing while the sample hasn't resolved yet (no guessed score). */
@Composable fun QualityGauge(score: Float?, modifier: Modifier = Modifier.size(22.dp)) {
    if (score == null) return
    val color = when {
        score >= 0.72f -> HionePink
        score >= 0.38f -> HioneGold
        else -> HioneTeal
    }
    val animatedScore by animateFloatAsState(score, tween(500), label = "qualityGauge")
    Canvas(modifier) {
        val stroke = size.minDimension * 0.16f
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        // 270° speedometer sweep, gap at the bottom — background track, then the filled portion.
        drawArc(Mascot.color(0xFF1A2C2E), startAngle = 135f, sweepAngle = 270f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(color, startAngle = 135f, sweepAngle = 270f * animatedScore, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * Highly glanceable Audio Quality Crest / Emblem Symbol Overlay.
 * Designed to overlay directly on album covers and artist circular avatars.
 *
 * Tiers:
 *  - Vinyl Rip: ⊚ Warm Analog Amber & Gold Seal
 *  - 4 (Master Hi-Res / 192k+ / 32-bit / DSD): 💎 Diamond Violet/Holographic Seal
 *  - 3 (Studio Hi-Res / 96k / 24-bit): 👑 Crown Gold Seal
 *  - 2 (CD Bit-Perfect Lossless / 44.1k / 16-bit FLAC): ✧ Star Hione Mint Seal
 *  - 1 (Standard MP3 / AAC): ♪ Slate Ring
 */
@Composable
fun AudioQualityCrestOverlay(
    breakdown: TrackTech.QualityBreakdown,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    if (breakdown.totalTracks == 0) return

    val tier = breakdown.highestTier
    val (primaryColor, glowColor, symbol) = when {
        breakdown.isVinylRip -> Triple(Color(0xFFFFB300), Color(0x66FF8F00), "⊚")
        tier == 4 -> Triple(Color(0xFFDFB8FF), Color(0x669C27B0), "💎")
        tier == 3 -> Triple(Color(0xFFFFD166), Color(0x66FF9800), "👑")
        tier == 2 -> Triple(HioneTealBright, Mascot.color(0x6600B4D8), "✧")
        else -> Triple(Color(0xFFB0BEC5), Color(0x44546E7A), "♪")
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (breakdown.isVinylRip) Color(0xEE160E04) else Mascot.color(0xEE031215))
            .border(
                1.5.dp,
                when {
                    breakdown.isVinylRip -> Brush.radialGradient(listOf(Color(0xFFFFD54F), Color(0xFFFF6D00)))
                    tier == 4 -> HioneBadgePalette.RainbowBrush
                    else -> Brush.radialGradient(listOf(primaryColor, primaryColor.copy(alpha = 0.6f)))
                },
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(0.75f)
                .clip(CircleShape)
                .background(glowColor)
        )
        val fontSp = (size.value * 0.52f).sp
        Text(
            text = symbol,
            fontSize = fontSp,
            textAlign = TextAlign.Center,
            lineHeight = fontSp
        )
    }
}

/**
 * High-contrast, glanceable audio data metric line (e.g. "✦ 24-BIT · 96kHz FLAC · HI-RES").
 */
@Composable
fun AudioQualitySpecLine(
    breakdown: TrackTech.QualityBreakdown,
    modifier: Modifier = Modifier
) {
    if (breakdown.totalTracks == 0) return

    val tier = breakdown.highestTier
    val accentColor = when {
        breakdown.isVinylRip -> Color(0xFFFFB300)
        tier == 4 -> Color(0xFFDFB8FF)
        tier == 3 -> Color(0xFFFFD166)
        tier == 2 -> HioneTealBright
        else -> Color(0xFFB0BEC5)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AudioQualityCrestOverlay(breakdown = breakdown, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = breakdown.specTag,
            color = accentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (breakdown.isVinylRip) {
            Spacer(Modifier.width(6.dp))
            VinylBadgeChip(fontSize = 9.sp, isCompact = true)
        } else if (tier >= 2) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(accentColor.copy(alpha = 0.2f))
                    .border(0.8.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (tier) {
                        4 -> "MASTER"
                        3 -> "HI-RES"
                        else -> "LOSSLESS"
                    },
                    color = accentColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Compact relative fidelity strip for Album Cards in the grid and Artist rows in the list.
 */
@Composable fun MiniQualitySpectrumStrip(
    breakdown: TrackTech.QualityBreakdown,
    modifier: Modifier = Modifier
) {
    if (breakdown.totalTracks == 0) return
    Box(
        modifier = modifier
            .width(42.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Mascot.color(0xFF0F1E24))
    ) {
        Row(Modifier.fillMaxSize()) {
            if (breakdown.masterFraction > 0f) {
                Box(Modifier.weight(breakdown.masterFraction).fillMaxHeight().background(Mascot.color(0xFFB9C2D0)))
            }
            if (breakdown.studioHiResFraction > 0f) {
                Box(Modifier.weight(breakdown.studioHiResFraction).fillMaxHeight().background(Color(0xFFFFD166)))
            }
            if (breakdown.cdLosslessFraction > 0f) {
                Box(Modifier.weight(breakdown.cdLosslessFraction).fillMaxHeight().background(HioneTeal))
            }
            if (breakdown.lossyFraction > 0f) {
                Box(Modifier.weight(breakdown.lossyFraction).fillMaxHeight().background(Color(0xFF455A64)))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun TrackRow(t: Track, showTrackNumber: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    val playCount = PlayerPreferences.loadPlayCount(ctx, t.id)
    val displayYear = remember(t.id) { TrackYear.yearFor(ctx, t)?.takeIf { it > 0 } ?: t.year.takeIf { it > 0 } }
    // Build the styled metric lines once per track, not on every recomposition.
    val meta = remember(t, displayYear) { trackMetaSpan(t, displayYear) }
    val metrics = remember(t) { metricSpans(t) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .pressableGlassCard(
                tint = if (NowPlayingState.currentId == t.id) HioneTealBright else HioneTeal,
                onLongClick = { Haptics.tick(ctx); showSheet = true },   // long-press → full queue actions & metrics
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isCurrent = NowPlayingState.currentId == t.id

        if (showTrackNumber) {
            val numStr = if (t.trackNumber > 0) t.trackNumber.toString().padStart(2, '0') else "—"
            Text(
                numStr,
                color = if (isCurrent) HioneTealBright else Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont,
                modifier = Modifier.width(26.dp)
            )
            Spacer(Modifier.width(6.dp))
        }

        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            AlbumArtImage(trackId = t.id, modifier = Modifier.fillMaxSize(), trackPath = t.path)
            if (isCurrent) {
                Box(Modifier.matchParentSize().background(Mascot.color(0xAA121318)), contentAlignment = Alignment.Center) {
                    EqualizerBars(NowPlayingState.playing, Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                color = if (isCurrent) HioneTeal else Mascot.color(0xFFE8F4F2),
                fontSize = 19.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                fontFamily = Baloo2Font
            )
            Spacer(Modifier.height(1.dp))
            Text(meta, fontSize = 16.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Baloo2Font)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metrics, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = OrbitronFont, modifier = Modifier.weight(1f, fill = false))
                // Bit-depth + sample-rate chips, each with their own bespoke color/shape — resolves
                // async from the header cache, appears when known.
                Spacer(Modifier.width(6.dp))
                TechBadgeRow(ctx, t)
                if (playCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("▶ $playCount", color = HioneTealBright, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        RainbowHeart(LikeStore.isLikedEffective(ctx, t)) { LikeStore.toggleEffective(ctx, t) }
    }
    if (showSheet) TrackActionModalSheet(t) { showSheet = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TrackActionModalSheet(t: Track, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val playCount = PlayerPreferences.loadPlayCount(ctx, t.id) // see TrackRow — deliberately not remembered
    val lastPlayed = remember(t.id) { PlayerPreferences.loadLastPlayedAt(ctx, t.id) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArtImage(t.id, Modifier.size(74.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = HioneTealBright, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = Mascot.color(0xFFD4E8E5), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.album.isNotBlank()) Text(
                        t.album + if (t.year > 0) "  ·  ${t.year}" else "",
                        color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RainbowHeart(LikeStore.isLikedEffective(ctx, t)) { LikeStore.toggleEffective(ctx, t) }
            }

            Spacer(Modifier.height(14.dp))

            // Quick Queue Action Row (YouTube / Spotify Style)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Play Next Button
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Mascot.color(0xFF0F2B2E))
                        .border(1.2.dp, HioneTeal, RoundedCornerShape(14.dp))
                        .clickable {
                            PlayerHolder.player?.let { p -> QueueManager.playNext(ctx, p, listOf(t)) }
                            onDismiss()
                        }
                        .padding(vertical = 11.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlaylistPlay, "Play Next", tint = HioneTealBright, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play Next", color = HioneTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                // Add to Queue / Play Last Button
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Mascot.color(0xFF1C081A))
                        .border(1.2.dp, HionePink, RoundedCornerShape(14.dp))
                        .clickable {
                            PlayerHolder.player?.let { p -> QueueManager.playLast(ctx, p, listOf(t)) }
                            onDismiss()
                        }
                        .padding(vertical = 11.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PlaylistAdd, "Add to Queue", tint = HionePink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add to Queue", color = HionePink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Hione Radio seeded from this song (taste/StationEngine) — builds a self-refilling
            // queue of similar tracks; the sheet it opens shows the seed, discovery slider, why-this.
            Spacer(Modifier.height(10.dp))
            com.hione.player.taste.StationFromTrackButton(t) { onDismiss() }

            Spacer(Modifier.height(16.dp))
            if (t.trackNumber > 0) MetricRow("Track #", "#${t.trackNumber}", HioneTealBright)
            MetricRow("Format", t.mime.substringAfterLast('/').uppercase().ifBlank { "—" }, formatColor(t.mime))
            TrackTech.bitsFor(ctx, t)?.takeIf { it > 0 }?.let { MetricRow("Bit depth", "$it-bit", TrackTech.color(it)) }
            MetricRow("Bitrate", if (t.bitrateKbps > 0) "${t.bitrateKbps} kbps" else "—", bitrateColor(t.bitrateKbps))
            MetricRow("Duration", fmtDuration(t.durationMs))
            MetricRow("Size", if (t.sizeBytes > 0) "${"%.1f".format(t.sizeBytes / 1e6)} MB" else "—")
            if (t.year > 0) MetricRow("Year", "${t.year}", HioneGold)
            MetricRow("Play count", "$playCount", if (playCount > 0) HioneTeal else Muted)
            if (lastPlayed > 0) MetricRow("Last played", relTime(lastPlayed))
            if (t.path.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("PATH", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(t.path, color = Color(0xFFB8D4D0), fontSize = 11.sp)
            }
        }
    }
}

@Composable private fun MetricRow(label: String, value: String, valueColor: Color = Mascot.color(0xFFD4E8E5)) = Row(
    Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically
) {
    Text(label.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
    Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

private fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "—"
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun relTime(then: Long): String {
    val d = System.currentTimeMillis() - then
    if (d < 0) return "just now"
    val min = d / 60000
    val hr = min / 60
    val day = hr / 24
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        hr < 24 -> "${hr}h ago"
        day < 30 -> "${day}d ago"
        else -> "${day / 30}mo ago"
    }
}

/** Compact art+title+subtitle tile for Home's "Newly Added" rows, with a small pulsing "NEW"
 *  corner tag whose glow strength follows the item's own Newness prominence (louder for
 *  brand-new/unplayed, quieter once it's been played or is near the end of its 7-day window). */
@Composable private fun NewItemCard(
    artOf: Long?,
    artPath: String?,
    title: String,
    subtitle: String,
    prominence: Float,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit
) {
    Column(Modifier.width(104.dp).padding(4.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(96.dp).clip(shape)) {
            if (artOf != null) AlbumArtImage(trackId = artOf, modifier = Modifier.fillMaxSize(), trackPath = artPath ?: "")
            else Box(Modifier.fillMaxSize().background(Mascot.color(0xFF123438)))
            if (prominence > 0f) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(HionePink.copy(alpha = 0.35f + 0.5f * prominence))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text("NEW", color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(title, color = Mascot.color(0xFFE8F4F2), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "What are you feeling?" / Offline Vibe Alchemy Prompt Bar & Randomizer
 */
@Composable
private fun HioneVibePromptCard(
    tracks: List<Track>,
    onPlay: (List<Track>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var promptText by remember { mutableStateOf("") }
    var isSynthesizing by remember { mutableStateOf(false) }
    var vibeProgress by remember { mutableStateOf(0f) }
    var vibeStatus by remember { mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val executeVibe: (String) -> Unit = { query ->
        if (!isSynthesizing) {
            focusManager.clearFocus()
            isSynthesizing = true
            vibeProgress = 0f
            vibeStatus = "Building a mix…"

            scope.launch {
                val result = if (query.isBlank()) {
                    com.hione.player.vibe.HioneVibeAlchemyEngine.generateRandomVibe(ctx, tracks) { processed, total, status ->
                        vibeProgress = if (total > 0) processed.toFloat() / total.toFloat() else 0f
                        vibeStatus = status
                    }
                } else {
                    com.hione.player.vibe.HioneVibeAlchemyEngine.synthesizeFromPrompt(ctx, tracks, query) { processed, total, status ->
                        vibeProgress = if (total > 0) processed.toFloat() / total.toFloat() else 0f
                        vibeStatus = status
                    }
                }

                isSynthesizing = false
                if (result.playlist.isNotEmpty()) {
                    android.widget.Toast.makeText(ctx, "${result.vibeTitle} (${result.playlist.size} tracks)", android.widget.Toast.LENGTH_SHORT).show()
                    onPlay(result.playlist, 0)
                } else {
                    android.widget.Toast.makeText(ctx, "No matching tracks found in library", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF131828))
            .border(1.2.dp, Brush.horizontalGradient(listOf(HioneCyan.copy(alpha = 0.7f), HioneNeonPink.copy(alpha = 0.5f))), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = if (isSynthesizing) Color(0xFFFFD54F) else HioneCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isSynthesizing) "Building your mix…" else "What do you want to hear?",
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }

                // 🎲 Random Surprise Button
                Button(
                    onClick = { executeVibe("") },
                    enabled = !isSynthesizing,
                    colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x336FB0FF)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Random", color = Mascot.color(0xFFFF80AB), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            // Live Synthesizer Progress Bar (Smart OS Reporting)
            if (isSynthesizing) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = vibeStatus,
                            color = HioneCyan,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(vibeProgress * 100).toInt()}%",
                            color = Mascot.color(0xFFFF80AB),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { vibeProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = HioneCyan,
                        trackColor = Mascot.color(0x28FFC56B)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Input Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    enabled = !isSynthesizing,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { executeVibe(promptText) }),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(HioneCyan),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E2538))
                        .padding(horizontal = 10.dp),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) {
                            if (promptText.isEmpty()) {
                                Text(
                                    "e.g. Heavy metal riffs, late night drive, 80s anime...",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = { executeVibe(promptText) },
                    enabled = !isSynthesizing,
                    colors = ButtonDefaults.buttonColors(containerColor = HioneCyan),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    if (isSynthesizing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Mix", color = Color.Black, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Suggestion feeling pills
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val chips = listOf(
                    "Heavy metal" to "metal heavy thrash progressive guitar solo breakdown",
                    "Cyberpunk" to "cyberpunk edm fast electronic hype",
                    "Late-night lo-fi" to "night chill lofi ambient slow calm",
                    "Hi-res" to "audiophile dsd flac lossless acoustic",
                    "Anime" to "hione vocaloid anime cute jpop",
                    "Workout 140+" to "workout running gym rhythm pump 140 bpm",
                    "80s city pop" to "city pop 80s retro synthwave vintage",
                    "Rainy day" to "sad slow acoustic tears emotional rain",
                    "Focus" to "study focus coffee lofi jazz instrumental"
                )
                items(chips) { (label, query) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF222B42))
                            .clickable(enabled = !isSynthesizing) {
                                promptText = label.substringAfter(' ')
                                executeVibe(query)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(label, color = Color(0xFFB0BEC5), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Home banner's "Play Mix" button — embossed (raised face, drop shadow, top specular hairline,
 *  springy press-scale, haptic tick) like every other hardware-style control in the app, PLUS a
 *  "chasing lights" animated border: a Hione-palette sweep gradient continuously rotating around
 *  the pill outline, the classic marquee-border effect. Was a flat two-color gradient fill with
 *  no shadow/bevel at all — read as inert next to the rest of the app's raised-key language. */
@Composable private fun PlayMixButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(), label = "playMixScale")

    val rgbTransition = rememberInfiniteTransition(label = "playMixRgb")
    val colorPhase by rgbTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(4000, easing = LinearEasing)),
        label = "colorPhase"
    )

    val glowPulse by rgbTransition.animateFloat(
        initialValue = 0.55f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(animation = tween(2000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "glowPulse"
    )

    val currentRgb1 = remember(colorPhase) {
        val colors = listOf(HioneTeal, HionePink, HioneGold, HioneTealBright, HionePurple, HioneTeal)
        val pos = colorPhase * (colors.size - 1)
        val idx = pos.toInt().coerceIn(0, colors.size - 2)
        val fraction = pos - idx
        androidx.compose.ui.graphics.lerp(colors[idx], colors[idx + 1], fraction)
    }

    val currentRgb2 = remember(colorPhase) {
        val colors = listOf(HionePink, HioneGold, HioneTealBright, HionePurple, HioneTeal, HionePink)
        val pos = colorPhase * (colors.size - 1)
        val idx = pos.toInt().coerceIn(0, colors.size - 2)
        val fraction = pos - idx
        androidx.compose.ui.graphics.lerp(colors[idx], colors[idx + 1], fraction)
    }

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .drawBehind {
                val rr = CornerRadius(20.dp.toPx(), 20.dp.toPx())
                val borderThickness = 2.2.dp.toPx()

                // 1. Glowing RGB Aura Ring behind button
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(currentRgb1.copy(alpha = 0.45f * glowPulse), Color.Transparent),
                        center = center,
                        radius = size.width * 0.75f
                    ),
                    cornerRadius = rr
                )

                // 2. Smooth Static Pill RGB Border Ring (Color-cycling in place)
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(currentRgb1, currentRgb2),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    ),
                    cornerRadius = rr,
                    style = Stroke(borderThickness)
                )

                // 3. Raised key face inset within the RGB ring
                val pad = borderThickness
                val tl = Offset(pad, pad)
                val sz = androidx.compose.ui.geometry.Size(size.width - 2 * pad, size.height - 2 * pad)
                val faceRR = CornerRadius(20.dp.toPx() - pad, 20.dp.toPx() - pad)
                drawRoundRect(Color(0x59000000), topLeft = Offset(pad, pad + 2f), size = sz, cornerRadius = faceRR)
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(HioneTealBright, HionePink),
                        startY = pad,
                        endY = pad + sz.height
                    ),
                    topLeft = tl,
                    size = sz,
                    cornerRadius = faceRR
                )
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color(0x8CFFFFFF), Color(0x00FFFFFF)),
                        startY = pad,
                        endY = pad + sz.height * 0.6f
                    ),
                    topLeft = tl,
                    size = sz,
                    cornerRadius = faceRR,
                    style = Stroke(1.2f)
                )
            }
            .clickable(interactionSource = interaction, indication = null) {
                Haptics.click(ctx)
                onClick()
            }
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = Mascot.color(0xFF121318), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Play Mix", color = Mascot.color(0xFF121318), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * App settings — a real screen (not a modal), reached by tapping the header's Hione logo/wordmark.
 * Consolidates what used to be scattered one-off toggles (Artists sort order, auto-viz) into one
 * place. Every toggle here is backed by an existing PlayerPreferences key, so nothing about how
 * these settings are STORED changed — this is purely giving them a real front door.
 */
enum class SettingsCategory(val title: String, val icon: String) {
    GENERAL("General", "⚙️"),
    DISPLAY("Display", "🌙"),
    ALARMS("Alarms", "⏰"),
    ABOUT("About", "ℹ️")
}

/** A one-shot request to land Settings on a given tab (consumed by [SettingsScreen]). */
internal val settingsCategoryRequest = androidx.compose.runtime.mutableStateOf<SettingsCategory?>(null)

@Composable private fun SettingsScreen(ctx: android.content.Context, tracks: List<Track>, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    var selectedCategory by remember { mutableStateOf(settingsCategoryRequest.value ?: SettingsCategory.GENERAL) }
    val categoryRequest = settingsCategoryRequest.value
    LaunchedEffect(categoryRequest) {
        if (categoryRequest != null) {
            selectedCategory = categoryRequest
            settingsCategoryRequest.value = null
        }
    }
    var sortIgnoreThe by remember { mutableStateOf(PlayerPreferences.loadSortIgnoreThe(ctx)) }
    var autoViz by remember { mutableStateOf(PlayerPreferences.loadAutoViz(ctx)) }
    var idleDim by remember { mutableStateOf(IdleController.enabled) }
    var ambient by remember { mutableStateOf(IdleController.ambientEnabled) }
    var idleActiveSec by remember { mutableStateOf(IdleController.activeSec) }
    var idleDimSec by remember { mutableStateOf(IdleController.dimSec) }
    var idleAmbientSec by remember { mutableStateOf(IdleController.ambientSec) }

    Box(Modifier.fillMaxSize().background(Ground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                com.hione.player.ui.HioneBackButton(onClick = onClose)
                Spacer(Modifier.width(8.dp))
                Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            }

            // Tab Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Mascot.color(0xFF071B20))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SettingsCategory.values().forEach { cat ->
                    val isSel = cat == selectedCategory
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) HioneTealBright.copy(alpha = 0.25f) else Color.Transparent)
                            .border(1.dp, if (isSel) HioneTealBright else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable {
                                Haptics.tick(ctx)
                                selectedCategory = cat
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${cat.title}",
                            color = if (isSel) Color.White else Muted,
                            fontSize = 11.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                when (selectedCategory) {
                    SettingsCategory.GENERAL -> {
                        // DAC settings live in the Hardware app (the only DAC page on HioneOS).
                        // Hione Music keeps its own playback options on HardwareSettingsActivity.
                        listOf(
                            Triple("DAC settings", "Filter, gain, DRE, high power and USB DAC. Opens the Hardware app.", true),
                            Triple("Playback and listening profiles", "Headphone profiles, direct route, car audio, USB DAC output", false),
                        ).forEach { (title, sub, isDac) ->
                        item {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Brush.horizontalGradient(listOf(Mascot.color(0xFF0F3238), Mascot.color(0xFF071B20))))
                                    .border(1.dp, HioneTealBright.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                    .clickable {
                                        if (isDac) DacSettingsLink.open(ctx)
                                        else ctx.startActivity(android.content.Intent(ctx, HardwareSettingsActivity::class.java))
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (isDac) Icons.Default.Headphones else Icons.Default.Tune, null, tint = HioneTealBright, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(sub, color = HioneTealBright, fontSize = 11.5.sp)
                                }
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = HioneTealBright, modifier = Modifier.size(18.dp).rotate(180f))
                            }
                        }
                        }
                        item { SettingsSection("Library") }
                        item { StorageAccessCard(ctx) }
                        item { HioneSyncCard(ctx) }
                        item {
                            SettingsToggleRow(
                                title = "Sort artists ignoring \"The\"",
                                subtitle = "\"The Beatles\" files under B, not T",
                                checked = sortIgnoreThe
                            ) { sortIgnoreThe = it; PlayerPreferences.saveSortIgnoreThe(ctx, it) }
                        }
                        item { SettingsSection("Playback") }
                        item {
                            var hudOn by remember { mutableStateOf(HioneTrackHud.isEnabled(ctx)) }
                            SettingsToggleRow(
                                title = "Now-playing HUD over apps",
                                subtitle = "Shows a small card when the track changes, even outside the player",
                                checked = hudOn
                            ) { hudOn = it; HioneTrackHud.setEnabled(ctx, it) }
                        }
                        item { StatusLightSettings(ctx) }
                        item { SettingsSection("Power Governor") }
                        item { PowerGovernorCard(ctx) }
                        item {
                            SettingsToggleRow(
                                title = "Auto-start visualizer",
                                subtitle = "Now Playing opens straight into the ProjectM visualizer",
                                checked = autoViz
                            ) { autoViz = it; PlayerPreferences.saveAutoViz(ctx, it) }
                        }
                        // ---- Now Playing look (ui/NowPlayingLook.kt, visualizer/) ----
                        item { SettingsSection("Now Playing") }
                        item {
                            com.hione.player.ui.NowPlayingLook.load(ctx)
                            SettingsToggleRow(
                                title = "Album-art dynamic color",
                                subtitle = "Tint the Now Playing background, wavy bar and keys from the cover art (off = ${Mascot.name} teal)",
                                checked = com.hione.player.ui.NowPlayingLook.dynamicColor
                            ) { com.hione.player.ui.NowPlayingLook.setDynamicColor(ctx, it) }
                        }
                        item {
                            SettingsToggleRow(
                                title = "Wavy play bar",
                                subtitle = "Seek bar rides the live output waveform (off = plain embossed scrubber)",
                                checked = com.hione.player.ui.NowPlayingLook.wavyBar
                            ) { com.hione.player.ui.NowPlayingLook.setWavyBar(ctx, it) }
                        }
                        item {
                            // The "Hione Shaders" engine toggle is GONE (2026-09-17). There is one visualiser engine now,
                            // projectM, and the Hione look is delivered as our own .milk presets inside it rather than a
                            // second GLES2 renderer with its own preset list, its own settings and its own toggle.
                        }
                        item { SettingsSection("Phone Remote") }
                        item { com.hione.player.remote.HioneRemoteSettingsCard(ctx) }
                        item { SettingsSection("Wi-Fi Remote") }
                        item { com.hione.player.api.HioneApiSettingsCard(ctx) }
                        item { SettingsSection("Listening Stats") }
                        item { com.hione.player.stats.ListeningStatsSettingsCard(ctx) }
                        item { SettingsSection("Last.fm") }
                        item { com.hione.player.scrobble.ScrobbleSettingsCard(ctx) }
                        item { SettingsSection("Artist Photos") }
                        item { ArtistPhotoSettingsCard(ctx) }
                        item { SettingsSection("Disc Images") }
                        item { com.hione.player.discsplit.DiscSplitSettingsCard(ctx) }
                    }
                    SettingsCategory.DISPLAY -> {
                        item { SettingsSection("Idle screen") }
                        item {
                            SettingsToggleRow(
                                title = "Dim when idle",
                                subtitle = "Runs the stages below. Off keeps the screen at full brightness.",
                                checked = idleDim
                            ) { idleDim = it; IdleController.enabled = it; PlayerPreferences.saveIdleDimEnabled(ctx, it) }
                        }
                        item {
                            SettingsToggleRow(
                                title = "Ambient display (AOD)",
                                subtitle = "A simple clock and track screen before the display sleeps",
                                checked = ambient
                            ) { ambient = it; IdleController.ambientEnabled = it; PlayerPreferences.saveAmbientEnabled(ctx, it) }
                        }
                        item {
                            IdleTimingRow("Full brightness", "How long after the last touch before dimming", idleActiveSec, 10f..300f) {
                                idleActiveSec = it; IdleController.activeSec = it; PlayerPreferences.saveIdleActiveSec(ctx, it)
                            }
                        }
                        item {
                            IdleTimingRow("Dimmed", "How long the screen stays dimmed before ambient", idleDimSec, 10f..300f) {
                                idleDimSec = it; IdleController.dimSec = it; PlayerPreferences.saveIdleDimSec(ctx, it)
                            }
                        }
                        item {
                            IdleTimingRow("Ambient (AOD)", "How long the clock screen lasts before the display sleeps", idleAmbientSec, 10f..180f) {
                                idleAmbientSec = it; IdleController.ambientSec = it; PlayerPreferences.saveIdleAmbientSec(ctx, it)
                            }
                        }
                        item { ScreenOffPermissionCard(ctx) }
                    }
                    SettingsCategory.ALARMS -> {
                        item { SettingsSection("Alarms") }
                        item { AlarmsSettingsContent(ctx, tracks) }
                    }
                    SettingsCategory.ABOUT -> {
                        item { SettingsSection("About") }
                        item { AboutCard(ctx) }
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

/** Settings' About card — publisher mark, contact, and copyright. The logo opens
 *  falcontechnix.com in the device's own browser (a real ACTION_VIEW intent, not an in-app/AMP
 *  view), and the email opens a mail composer via mailto:. */
@Composable private fun AboutCard(ctx: android.content.Context) {
    val year = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Surface1, Mascot.color(0xFF0A2528))))
            .border(1.dp, HioneTealBright.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text("${Mascot.musicAppName} Player", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
        Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Muted, fontSize = 12.5.sp)

        Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.Image(
            painter = mascotPainter(HioneArt.falconTechnixLogo),
            contentDescription = "Falcon Technix (opens falcontechnix.com)",
            modifier = Modifier
                .height(56.dp)
                .clickable {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://falcontechnix.com"))
                        )
                    }
                }
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Justin@FalconTechnix.com",
            color = HioneTealBright,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:Justin@FalconTechnix.com"))
                    )
                }
            }
        )
        Spacer(Modifier.height(10.dp))
        Text("© $year Falcon Technix. All rights reserved.", color = Muted, fontSize = 11.5.sp)
    }
}

/** Settings' storage-access card — surfaces MANAGE_EXTERNAL_STORAGE status and a one-tap request.
 *  Without it, the scanner's own SD-card ghost-row check silently falls back to trusting MediaStore
 *  rather than verifying (see mediaStoreRowLikelyValid()'s doc comment in Model.kt for the concrete
 *  live-confirmed bug this exists to fix — a real library was undercounted by ~2000 tracks because
 *  java.io.File can't see removable storage on API 33+ without this permission). Android forces
 *  this through a dedicated Settings screen (no normal runtime dialog), so this card is that
 *  request's front door rather than something that could be silently auto-granted. */
@Composable private fun StorageAccessCard(ctx: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < 30) return // MANAGE_EXTERNAL_STORAGE doesn't exist before API 30 — scoped storage's SD-card restriction doesn't either
    var granted by remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = android.os.Environment.isExternalStorageManager()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    if (granted) return // nothing to show once it's granted — nothing to nag about

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFF6B6B).copy(alpha = 0.12f))
            .clickable {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, "Warning", tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Allow \"All files access\" so scans find everything on the SD card. Tap to open Settings.",
            color = Color(0xFFFF6B6B), fontSize = 11.5.sp, lineHeight = 15.sp
        )
    }
    Spacer(Modifier.height(8.dp))
}

/** One row of the idle pipeline's configurable stage durations — label, live value, a stepped
 *  (10s) slider. Reused for all three stages so they read as one consistent control set. */
@Composable private fun IdleTimingRow(title: String, subtitle: String, valueSec: Int, range: ClosedFloatingPointRange<Float>, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Mascot.color(0xFFE8F4F2), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Baloo2Font)
            Text("${valueSec}s", color = HioneTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Baloo2Font)
        }
        Text(subtitle, color = Muted, fontSize = 11.sp, fontFamily = Baloo2Font)
        androidx.compose.material3.Slider(
            value = valueSec.toFloat(),
            onValueChange = { onChange(((it / 10f).toInt() * 10).coerceAtLeast(10)) },
            valueRange = range,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = HioneTealBright, activeTrackColor = HioneTeal, inactiveTrackColor = Surface1)
        )
    }
}

/** The final idle stage (real display sleep) needs WRITE_SETTINGS — special app access, never
 *  silently granted (same family as StorageAccessCard above). Without it the pipeline just stops
 *  at Ambient forever; this banner is the only thing that explains why and offers the fix. */
@Composable private fun ScreenOffPermissionCard(ctx: android.content.Context) {
    var granted by remember { mutableStateOf(ScreenOffHelper.hasPermission(ctx)) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = ScreenOffHelper.hasPermission(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    if (granted) return

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFF6B6B).copy(alpha = 0.12f))
            .clickable { ScreenOffHelper.requestPermission(ctx) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, "Warning", tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Allow \"Modify system settings\" so the screen can sleep after ambient. Tap to open Settings.",
            color = Color(0xFFFF6B6B), fontSize = 11.5.sp, lineHeight = 15.sp
        )
    }
    Spacer(Modifier.height(8.dp))
}

/** Restart the app cleanly (used by settings that need a fresh process). */
private fun gracefulAppRestart(ctx: android.content.Context) {
    try {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (intent != null) {
            ctx.startActivity(intent)
        }
        (ctx as? android.app.Activity)?.finishAffinity()
        Runtime.getRuntime().exit(0)
    } catch (_: Throwable) {
        (ctx as? android.app.Activity)?.recreate()
    }
}

/**
 * Hardware & System section.
 *
 * There is no "grant root" switch any more: HioneOS ships no su binary at all, so the old master
 * toggle could only ever report "root request denied" while hiding the two features behind it.
 * This build is platform-signed and reaches the hardware through platform APIs instead. Each
 * control below is shown with its REAL availability, probed on this device, and says plainly when
 * the hardware cannot honour it.
 */
@Composable private fun RootFeaturesSection(ctx: android.content.Context) {
    val scope = rememberCoroutineScope()
    var cpuPerfOn by remember { mutableStateOf(PlayerPreferences.loadCpuPerfEnabled(ctx)) }
    var cpuSupported by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { cpuSupported = CpuPerformance.isSupported() }

    SettingsSection("Hardware & System")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Mascot.color(0xFF07272B))
            .border(1.dp, HioneTealBright.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text("Platform Hardware Access", color = HioneTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "${Mascot.osName} runs without su. ${Mascot.musicAppName} is platform-signed, so the DAC, input lock and power controls go straight through the Android platform APIs — there is nothing to grant here.",
            color = Muted,
            fontSize = 11.5.sp,
            lineHeight = 15.sp
        )
    }


    if (cpuSupported == true) {
        SettingsToggleRow(
            title = "Performance Governor",
            subtitle = "Pins every CPU core at peak clock while the player is open",
            checked = cpuPerfOn
        ) { cpuPerfOn = it; scope.launch { CpuPerformance.setEnabled(ctx, it) } }
    } else if (cpuSupported == false) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text("Performance governor (unavailable)", color = Color(0xFFFFD166), fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "This kernel does not let the player write cpufreq scaling_governor, so the clock pin cannot be applied. Sustained-performance mode (an Android hint) is still used while playing.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 15.sp
            )
        }
    }
}

/**
 * The front status light (HionePulse). The switch is the old Pulsar setting (pulsar_light_enabled).
 * Needs the system bridge's LED receiver; the beat row also needs the image's hione_led_beat.rc.
 */
@Composable private fun StatusLightSettings(ctx: android.content.Context) {
    var lightOn by remember { mutableStateOf(PlayerPreferences.loadPulsarEnabled(ctx)) }
    var lightBeatOn by remember { mutableStateOf(PlayerPreferences.loadPulsarBeatEnabled(ctx)) }
    val lightRelay = remember { HionePulse.relayAvailable(ctx) }
    val lightBeat = remember { HionePulse.beatSupported() }
    // One lazy-list item: a Column, or the rows would be drawn on top of each other.
    Column(Modifier.fillMaxWidth()) {
        if (lightRelay) {
            SettingsToggleRow(
                title = "Status light",
                subtitle = "Shows the playing format and charging. Off keeps it dark.",
                checked = lightOn
            ) { lightOn = it; HionePulse.setEnabled(ctx, it) }
            if (lightOn && lightBeat) {
                SettingsToggleRow(
                    title = "Pulse with the beat",
                    subtitle = "Pulses at the track's tempo",
                    checked = lightBeatOn
                ) { lightBeatOn = it; HionePulse.setBeatEnabled(ctx, it) }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text("Status light (unavailable)", color = Color(0xFFFFD166), fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Needs a newer ${Mascot.osName} system image.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable private fun HioneSyncCard(ctx: android.content.Context) {
    val syncState by HioneSyncTransceiver.state.collectAsState()
    var isManualScanning by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xFF07272B), Mascot.color(0xFF041417))))
            .border(1.dp, HioneTealBright.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎵", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text("Sync monitor", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (syncState.isTransferring) HionePink.copy(alpha = 0.3f) else HioneTeal.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    when {
                        syncState.isTransferring -> "SYNCING"
                        syncState.daemon.online -> "DAEMON ONLINE"
                        else -> "DAEMON OFFLINE"
                    },
                    color = if (syncState.isTransferring) HionePink else if (syncState.daemon.online) HioneTealBright else Muted,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Connection", color = Muted, fontSize = 12.sp)
            Text(syncState.transport.badge, color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Rsync target", color = Muted, fontSize = 12.sp)
            Text("rsync://${syncState.ipAddress}:${HioneSyncTransceiver.RSYNC_PORT}/music", color = Color.White, fontSize = 11.5.sp, fontFamily = AudiowideFont)
        }

        if (syncState.isTransferring) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Transfer speed", color = HionePink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("${String.format("%.1f", syncState.transferRateMBs)} MB/s", color = HionePink, fontSize = 13.sp, fontWeight = FontWeight.Black)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(HioneTeal.copy(alpha = 0.25f))
                .border(1.dp, HioneTealBright.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .clickable {
                    isManualScanning = true
                    HioneSyncTransceiver.scanAndIntegrateDirectory(ctx, HioneSyncTransceiver.getSdMusicPath(ctx)) { count ->
                        isManualScanning = false
                        android.widget.Toast.makeText(ctx, "Added $count audio files to the library", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (isManualScanning) "Scanning SD card…" else "Rescan SD staging and MUSIC", color = HioneTealBright, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun FnSwitchSettingsSection(ctx: android.content.Context) {
    val cr = ctx.contentResolver
    var fnMode by remember {
        mutableStateOf(android.provider.Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock")
    }
    var allowVolume by remember {
        mutableStateOf(android.provider.Settings.Global.getInt(cr, "m500_fn_allow_volume_wheel", 0) == 1)
    }
    var lockPower by remember {
        mutableStateOf(android.provider.Settings.Global.getInt(cr, "m500_fn_lock_power_button", 1) == 1)
    }

    // Whether the privileged input path this feature actually uses is available. Root is never
    // part of the answer: locking runs through InputManager.disableInputDevice, which needs the
    // signature permission DISABLE_INPUT_DEVICES that the Falcon platform key grants.
    val hasInputLock = ctx.checkSelfPermission("android.permission.DISABLE_INPUT_DEVICES") ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val isPocket = fnMode == "touch_and_key_lock" || fnMode == "Both"

    SettingsSection("Hardware Fn Switch")

    // Driver Status Card
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (hasInputLock) Mascot.color(0xFF07272B) else Color(0xFF2B2407))
            .border(1.dp, if (hasInputLock) HioneTealBright.copy(alpha = 0.3f) else Color(0xFFFFD166).copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (hasInputLock) "🟢" else "🟡", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (hasInputLock) "Input lock ready" else "Input lock permission missing",
                color = if (hasInputLock) HioneTealBright else Color(0xFFFFD166),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        // Honest description of the mechanism that actually runs. The old copy keyed this card on
        // whether a root shell existed — HioneOS has no su at all, so it always read as the
        // degraded state while the real (and working) mechanism is the platform input lock.
        Text(
            if (hasInputLock) "Locking runs root-free: InputManager.disableInputDevice (platform permission) plus the HiBy framework's button_lock setting."
            else "This build is missing DISABLE_INPUT_DEVICES, so only the HiBy framework's button_lock applies — the touchscreen will NOT be inhibited.",
            color = Muted,
            fontSize = 11.5.sp,
            lineHeight = 15.sp
        )
    }

    Spacer(Modifier.height(10.dp))

    SettingsToggleRow(
        title = "Pocket Lock (Touch & Key Lock)",
        subtitle = if (isPocket) "Locks the touchscreen and side buttons together" else "Off · normal key lock only",
        checked = isPocket
    ) { enabled ->
        val newMode = if (enabled) "touch_and_key_lock" else "key_lock"
        fnMode = newMode
        android.provider.Settings.Global.putString(cr, "fn_settings", newMode)
    }

    if (isPocket) {
        SettingsToggleRow(
            title = "Allow Volume Wheel during Lock",
            subtitle = "The volume wheel keeps working while touch and buttons are locked",
            checked = allowVolume
        ) { enabled ->
            allowVolume = enabled
            android.provider.Settings.Global.putInt(cr, "m500_fn_allow_volume_wheel", if (enabled) 1 else 0)
        }

        SettingsToggleRow(
            title = "Lock Power Button in Pocket",
            subtitle = "The screen won't wake if the power button gets bumped",
            checked = lockPower
        ) { enabled ->
            lockPower = enabled
            android.provider.Settings.Global.putInt(cr, "m500_fn_lock_power_button", if (enabled) 1 else 0)
        }
    }
}

@Composable private fun RootDisableConfirmModal(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xD9121318))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Surface1)
                .border(1.dp, HioneTealBright.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("DISABLE PRIVILEGED ACCESS?", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            Spacer(Modifier.height(10.dp))
            Text(
                "LED sync, CPU scaling and the kernel touch lock turn off and go back to normal mode.",
                color = Muted,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF6B6B), Color(0xFFFF8E53))))
                    .clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center
            ) {
                Text("DISABLE PRIVILEGED ACCESS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text("CANCEL", color = Color.White.copy(alpha = 0.8f), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun RootConsentModal(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xFF121318))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))

                // Hione 01 Cyberpunk Badge
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(HionePink.copy(alpha = 0.2f))
                        .border(1.dp, HionePink, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("01 · ROOT ACCESS", color = HionePink, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }

                Spacer(Modifier.height(16.dp))
                Text("ROOT (SU)", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                Spacer(Modifier.height(6.dp))
                Text("HiBy M500 ${Mascot.edition}", color = HioneTealBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                Spacer(Modifier.height(28.dp))

                // Feature Highlights Card
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface1)
                        .border(1.dp, HioneTealBright.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .padding(18.dp)
                ) {
                    ConsentFeatureRow("🏎️", "Performance governor", "Pins the Snapdragon 680 cores at full clock to avoid buffer underruns on DSD256.")
                    Spacer(Modifier.height(14.dp))
                    ConsentFeatureRow("🛡️", "Pocket lock", "Locks the touchscreen and buttons when the Fn switch is flipped.")
                }

                Spacer(Modifier.height(20.dp))

                // Notice
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Mascot.color(0xFF0F2B2E))
                        .padding(14.dp)
                ) {
                    Text(
                        "Playback works fine without this. Turning it on uses Magisk or KernelSU for direct hardware control.",
                        color = Muted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                // Confirm Button
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(25.dp))
                        .background(Brush.horizontalGradient(listOf(HioneTealBright, Mascot.color(0xFFFFC56B))))
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center
                ) {
                    Text("ENABLE PRIVILEGED ACCESS", color = Mascot.color(0xFF121318), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
                }

                Spacer(Modifier.height(10.dp))

                // Cancel Button
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text("CANCEL", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable private fun ConsentFeatureRow(icon: String, title: String, description: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 18.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(description, color = Muted, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
    }
}

private val DAY_LABELS = listOf(1 to "Su", 2 to "Mo", 3 to "Tu", 4 to "We", 5 to "Th", 6 to "Fr", 7 to "Sa")

private fun alarmSummary(a: Alarm): String {
    val time = when (a.mode) {
        AlarmTriggerMode.CLOCK_TIME -> String.format("%02d:%02d", a.hour, a.minute)
        AlarmTriggerMode.SUNRISE -> "Sunrise"
        AlarmTriggerMode.SUNSET -> "Sunset"
        AlarmTriggerMode.SUNRISE_OR_SUNSET -> "Sunrise/Sunset"
    }
    val days = if (a.repeatDays.isEmpty()) "One-time" else DAY_LABELS.filter { it.first in a.repeatDays }.joinToString(" ") { it.second }
    return "$time · $days"
}

/** Settings' Alarms card — list of configured alarms (music-based, fade-in, snooze, sunrise/sunset
 *  or clock-time trigger) plus an add/edit dialog. Scheduling itself lives in [[AlarmScheduler]];
 *  this is purely the editor UI over the JSON list in [[AlarmPreferences]]. Every write goes through
 *  AlarmPreferences' atomic update helpers (fresh read-modify-write against disk) rather than a
 *  locally `remember`ed snapshot, so an edit made here can never clobber a concurrent write from
 *  AlarmRingService (e.g. a one-shot alarm auto-disabling itself right after it fires). */
@Composable private fun AlarmsCard(ctx: android.content.Context, tracks: List<Track>) {
    var alarms by remember { mutableStateOf(AlarmPreferences.loadAlarms(ctx)) }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val activeSnoozeId by remember { mutableStateOf(AlarmPreferences.loadActiveSnooze(ctx)) }
    val exactAlarmsAllowed = remember {
        (ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
    }

    fun refresh() { alarms = AlarmPreferences.loadAlarms(ctx) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface1)
            .padding(16.dp)
    ) {
        if (!exactAlarmsAllowed) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFF6B6B).copy(alpha = 0.15f))
                    .clickable {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, "Warning", tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Exact alarms aren't allowed, so alarms may not go off on time. Tap to fix in Settings.", color = Color(0xFFFF6B6B), fontSize = 11.5.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
        }

        if (alarms.isEmpty()) {
            Text("No alarms yet. Alarms can play music, fade in, snooze, and follow sunrise or sunset.", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(12.dp))
        } else {
            alarms.sortedBy { it.hour * 60 + it.minute }.forEachIndexed { idx, a ->
                if (idx > 0) Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { editing = a; showEditor = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label.ifBlank { "Alarm" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (activeSnoozeId == a.id) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "SNOOZED", color = HioneGold, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(HioneGold.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(alarmSummary(a), color = Muted, fontSize = 11.5.sp)
                    }
                    Icon(
                        Icons.Default.Close, "Delete",
                        tint = Muted, modifier = Modifier.size(16.dp).clickable {
                            AlarmScheduler.cancel(ctx, a.id)
                            AlarmPreferences.removeAlarm(ctx, a.id)
                            refresh()
                        }
                    )
                    Spacer(Modifier.width(14.dp))
                    HeartToggle(checked = a.enabled, onCheckedChange = { on ->
                        AlarmPreferences.updateAlarm(ctx, a.id) { it.copy(enabled = on) }
                        refresh()
                        AlarmScheduler.rescheduleAll(ctx)
                    })
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(HioneTealBright.copy(alpha = 0.15f))
                .clickable { editing = null; showEditor = true }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text("+ Add alarm", color = HioneTealBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (showEditor) {
        AlarmEditorDialog(
            ctx = ctx,
            tracks = tracks,
            initial = editing,
            onDismissRequest = { showEditor = false },
            onSave = { a ->
                AlarmPreferences.upsertAlarm(ctx, a)
                refresh()
                AlarmScheduler.rescheduleAll(ctx)
                showEditor = false
            }
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun AlarmEditorDialog(ctx: android.content.Context, tracks: List<Track>, initial: Alarm?, onDismissRequest: () -> Unit, onSave: (Alarm) -> Unit) {
    var hour by remember { mutableStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }
    var mode by remember { mutableStateOf(initial?.mode ?: AlarmTriggerMode.CLOCK_TIME) }
    var repeatDays by remember { mutableStateOf(initial?.repeatDays ?: emptySet()) }
    var label by remember { mutableStateOf(initial?.label ?: "Alarm") }
    var fadeInSeconds by remember { mutableStateOf(initial?.fadeInSeconds ?: 30) }
    var snoozeMinutes by remember { mutableStateOf(initial?.snoozeMinutes ?: 9) }
    var source by remember { mutableStateOf(initial?.source ?: AlarmSource.SHUFFLE_ALL) }
    var sourceRef by remember { mutableStateOf(initial?.sourceRef) }
    var showSourcePicker by remember { mutableStateOf(false) }
    val artistNames = remember(tracks) { tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().sorted() }
    val albumNames = remember(tracks) { tracks.map { it.album }.filter { it.isNotBlank() }.distinct().sorted() }

    // A real platform Dialog rather than this file's usual in-app Box-overlay pattern: AlarmsCard
    // (and therefore this) is invoked from inside SettingsScreen's LazyColumn, which hands every
    // item() an unbounded max height — a Box(fillMaxSize()) there inherits that infinity straight
    // through to the Column().verticalScroll() below and Compose hard-crashes ("infinity maximum
    // height constraints"). A Dialog draws in its own window with real bounded constraints, so the
    // scrollable content inside measures correctly regardless of what's mounted it.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Surface1)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(if (initial == null) "New Alarm" else "Edit Alarm", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
            Spacer(Modifier.height(16.dp))

            Text("Trigger", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AlarmTriggerMode.CLOCK_TIME to "Time",
                    AlarmTriggerMode.SUNRISE to "Sunrise",
                    AlarmTriggerMode.SUNSET to "Sunset",
                    AlarmTriggerMode.SUNRISE_OR_SUNSET to "Both"
                ).forEach { (m, txt) -> AlarmChip(txt, mode == m) { mode = m } }
            }

            if (mode == AlarmTriggerMode.CLOCK_TIME) {
                Spacer(Modifier.height(16.dp))
                Text("Time (24h)", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                // Intentionally 24-hour only, no AM/PM anywhere in the alarm editor — hour steps
                // 0..23 directly and alarmSummary() formats with %02d:%02d, so there's no 12/24
                // ambiguity to get wrong at 10:00 vs 22:00.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeStepper(hour, 0, 23) { hour = it }
                    Text(":", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                    TimeStepper(minute, 0, 59) { minute = it }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Uses a rough, low-power location fix. If there isn't one yet, it quietly falls back.", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp)
            }

            Spacer(Modifier.height(16.dp))
            Text("Repeat", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DAY_LABELS.forEach { (d, txt) ->
                    AlarmChip(txt, d in repeatDays) {
                        repeatDays = if (d in repeatDays) repeatDays - d else repeatDays + d
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Plays", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlarmChip("Any Random Track", source == AlarmSource.SHUFFLE_ALL) { source = AlarmSource.SHUFFLE_ALL; sourceRef = null }
                AlarmChip("Liked Songs", source == AlarmSource.LIKED_SONGS) { source = AlarmSource.LIKED_SONGS; sourceRef = null }
                AlarmChip("Artist" + (sourceRef?.takeIf { source == AlarmSource.ARTIST }?.let { ": $it" } ?: ""), source == AlarmSource.ARTIST) {
                    source = AlarmSource.ARTIST; showSourcePicker = true
                }
                AlarmChip("Album" + (sourceRef?.takeIf { source == AlarmSource.ALBUM }?.let { ": $it" } ?: ""), source == AlarmSource.ALBUM) {
                    source = AlarmSource.ALBUM; showSourcePicker = true
                }
            }
            if (showSourcePicker && (source == AlarmSource.ARTIST || source == AlarmSource.ALBUM)) {
                Spacer(Modifier.height(8.dp))
                val options = if (source == AlarmSource.ARTIST) artistNames else albumNames
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp)
                ) {
                    if (options.isEmpty()) {
                        Text("Nothing in your library yet.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                    }
                    options.forEach { name ->
                        Text(
                            name,
                            color = if (sourceRef == name) HioneTealBright else Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = if (sourceRef == name) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sourceRef = name; showSourcePicker = false }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Label", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LastFmField(value = label, onValueChange = { label = it }, placeholder = "Alarm")

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Fade-in", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeStepper(fadeInSeconds, 0, 300, step = 5) { fadeInSeconds = it }
                        Text("s", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Column {
                    Text("Snooze", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeStepper(snoozeMinutes, 1, 30) { snoozeMinutes = it }
                        Text("m", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(HionePink)
                    .clickable {
                        AlarmLocation.refreshIfStale(ctx)
                        onSave(
                            Alarm(
                                id = initial?.id ?: AlarmPreferences.nextAlarmId(ctx),
                                enabled = initial?.enabled ?: true,
                                hour = hour, minute = minute, mode = mode, repeatDays = repeatDays,
                                label = label.ifBlank { "Alarm" }, fadeInSeconds = fadeInSeconds,
                                snoozeMinutes = snoozeMinutes, source = source,
                                sourceRef = if (source == AlarmSource.ARTIST || source == AlarmSource.ALBUM) sourceRef else null
                            )
                        )
                    }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("Save", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun AlarmChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.White else Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) HioneTealBright.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable private fun TimeStepper(value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("−", color = HioneTealBright, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
            onChange(if (value - step < min) max else value - step)
        }.padding(horizontal = 12.dp, vertical = 6.dp))
        Text(value.toString().padStart(2, '0'), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Text("+", color = HioneTealBright, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
            onChange(if (value + step > max) min else value + step)
        }.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** Settings' Last.fm card — username/password sign-in (auth.getMobileSession), connection status,
 *  disconnect. The password field never gets persisted anywhere — [[LastFm.login]] trades it for a
 *  session key in one round trip, and only that key is saved (encrypted, see [[LastFmPreferences]]).
 *  Once connected, [[LastFmScrobbler]] (attached to the shared player in PlayerHolder) drives
 *  now-playing pings and scrobbles automatically — nothing else to wire up from here. */
@Composable private fun LastFmCard(ctx: android.content.Context) {
    val lastFmRed = Color(0xFFD51007)
    var username by remember { mutableStateOf(LastFmPreferences.loadUsername(ctx) ?: "") }
    var password by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(LastFmPreferences.isConnected(ctx)) }
    var connectedAs by remember { mutableStateOf(LastFmPreferences.loadUsername(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Surface1, Color(0xFF2A0A10))))
            .border(1.dp, lastFmRed.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Podcasts, "Last.fm", tint = lastFmRed, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Last.fm Scrobbling", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !LastFm.isConfigured -> "Not set up on this build (no API key)."
                connected -> "Connected as ${connectedAs ?: username}. Every track scrobbles automatically."
                else -> "Sign in to scrobble every track you play to your Last.fm profile."
            },
            color = Muted, fontSize = 12.sp, lineHeight = 16.sp
        )

        if (LastFm.isConfigured) {
            Spacer(Modifier.height(14.dp))
            if (connected) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable {
                            LastFmPreferences.disconnect(ctx)
                            connected = false; connectedAs = null; username = ""; password = ""; error = null
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LinkOff, "Disconnect", tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Disconnect", color = Color(0xFFFF6B6B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                LastFmField(value = username, onValueChange = { username = it; error = null }, placeholder = "Username")
                Spacer(Modifier.height(8.dp))
                LastFmField(value = password, onValueChange = { password = it; error = null }, placeholder = "Password", isPassword = true)
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 11.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (loading) lastFmRed.copy(alpha = 0.35f) else lastFmRed)
                        .clickable(enabled = !loading) {
                            loading = true; error = null
                            scope.launch {
                                when (val r = LastFm.login(username, password)) {
                                    is LastFm.LoginResult.Success -> {
                                        LastFmPreferences.saveSession(ctx, r.username, r.sessionKey)
                                        connected = true; connectedAs = r.username; password = ""
                                    }
                                    is LastFm.LoginResult.Failure -> error = r.message
                                }
                                loading = false
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(if (loading) "Connecting…" else "Connect", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable private fun LastFmField(value: String, onValueChange: (String) -> Unit, placeholder: String, isPassword: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Mascot.color(0xFFE8F4F2), fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFD51007)),
            visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (isPassword) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text,
                autoCorrect = false
            ),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) Text(placeholder, color = Muted.copy(alpha = 0.85f), fontSize = 13.sp)
                innerTextField()
            }
        )
    }
}

@Composable private fun SettingsSection(title: String) = Text(
    title, color = HioneTealBright, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
)

@Composable private fun SettingsToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) = Row(
    Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .clickable { onCheckedChange(!checked) }
        .padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Column(Modifier.weight(1f)) {
        Text(title, color = Mascot.color(0xFFE8F4F2), fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
    Spacer(Modifier.width(8.dp))
    HeartToggle(checked = checked, onCheckedChange = null)
}

/** A toggle switch with a little beating heart for a thumb instead of the stock plain circle —
 *  slides between a hollow grey heart (off) and a filled Hione-pink one (on), same track-pill
 *  shape as a normal Switch so it still reads as "a toggle" at a glance. */
@Composable private fun HeartToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val ctx = LocalContext.current
    val thumbOffset by animateDpAsState(if (checked) 24.dp else 2.dp, spring(dampingRatio = 0.55f), label = "heartToggleOffset")
    val trackColor by animateColorAsState(if (checked) HioneTealBright.copy(alpha = 0.28f) else Mascot.color(0xFF123438), label = "heartToggleTrack")
    val heartColor by animateColorAsState(if (checked) HionePink else Muted, label = "heartToggleColor")
    val heartScale by animateFloatAsState(if (checked) 1.08f else 0.9f, spring(dampingRatio = 0.5f), label = "heartToggleScale")
    Box(
        Modifier
            .size(width = 54.dp, height = 30.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .border(1.dp, if (checked) HioneTealBright.copy(alpha = 0.55f) else Color(0x2AFFFFFF), RoundedCornerShape(50))
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        Haptics.tick(ctx)
                        onCheckedChange(!checked)
                    }
                } else Modifier
            )
    ) {
        Icon(
            if (checked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            null,
            tint = heartColor,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(24.dp)
                .scale(heartScale)
        )
    }
}

@Composable private fun CircleArt() = Box(Modifier.size(46.dp).clip(RoundedCornerShape(50)).background(Mascot.color(0xFF123438)),
    contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = HioneTeal.copy(alpha = .6f)) }

/**
 * Bespoke key silhouettes for the transport ControlAssembly — prev/next lean toward the play key
 * with an angled hex "wing" cut instead of a plain circle, and play itself is a bigger faceted
 * hero shield, so the cluster reads as one molded, artistically-designed assembly (each key still
 * its own distinct pressable silhouette) rather than identical circles sharing a trough.
 */
object TransportShapes {
    /** Leans right toward center — a shallow point on the play-facing side, square-ish on the far side. */
    val prevWing: Shape = GenericShape { size, _ ->
        val w = size.width; val h = size.height
        val cutNear = h * 0.30f; val cutFar = w * 0.10f
        moveTo(cutFar, 0f)
        lineTo(w - cutNear, 0f)
        lineTo(w, h / 2f)
        lineTo(w - cutNear, h)
        lineTo(cutFar, h)
        lineTo(0f, h * 0.85f)
        lineTo(0f, h * 0.15f)
        close()
    }
    /** Mirror of prevWing — leans left toward center. */
    val nextWing: Shape = GenericShape { size, _ ->
        val w = size.width; val h = size.height
        val cutNear = h * 0.30f; val cutFar = w * 0.10f
        moveTo(cutNear, 0f)
        lineTo(w - cutFar, 0f)
        lineTo(w, h * 0.15f)
        lineTo(w, h * 0.85f)
        lineTo(w - cutFar, h)
        lineTo(cutNear, h)
        lineTo(0f, h / 2f)
        close()
    }
    /** Play/pause hero — a faceted shield, bigger presence than the wings flanking it. */
    val hero: Shape = GenericShape { size, _ ->
        val w = size.width; val h = size.height
        val cutX = w * 0.15f; val cutY = h * 0.28f
        moveTo(cutX, 0f)
        lineTo(w - cutX, 0f)
        lineTo(w, cutY)
        lineTo(w, h - cutY)
        lineTo(w - cutX, h)
        lineTo(cutX, h)
        lineTo(0f, h - cutY)
        lineTo(0f, cutY)
        close()
    }
}

/**
 * Shared "hardware deck" housing for a transport-button cluster — one continuous engraved trough
 * (dark recessed gradient, inset shadow along the top edge, a thin highlight along the bottom) that
 * shuffle/prev/play/next sit inset into, like a real DAP's molded button assembly, instead of each
 * button floating separately on the bar's own background. The buttons themselves are untouched
 * HapticIconButtons — still fully distinct, individually raised/pressable keys — this is only the
 * shared shell that visually merges them into one assembly.
 */
@Composable fun ControlAssembly(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 26.dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .drawBehind {
                val rr = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
                drawRoundRect(Brush.verticalGradient(listOf(Mascot.color(0xFF030C10), Mascot.color(0xFF0B2226))), cornerRadius = rr)
                // inset shadow biting in from the top edge (recessed, not raised)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0x8A000000), Color.Transparent), endY = size.height * 0.55f),
                    cornerRadius = rr
                )
                // faint highlight along the bottom edge, like light catching the well's lower lip
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0x1FFFFFFF)), startY = size.height * 0.65f),
                    topLeft = Offset(0f, size.height * 0.65f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.35f),
                    cornerRadius = CornerRadius(rr.x * 0.5f, rr.y * 0.5f)
                )
            }
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(cornerRadius))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // IconButton's own built-in 48dp minimum-touch-target padding was quietly padding each
        // key out past its declared .size(), pushing the wing shapes apart from their neighbours
        // just enough that the "one molded assembly" look fell apart into separate floating keys
        // at the mini bar's smaller button sizes — only really visible once you compared it side
        // by side with the full Now Playing screen's bigger (closer-to-48dp) buttons, which didn't
        // show the gap. Zeroing it here lets keys sit exactly at their declared size, flush against
        // each other, so the interlocking silhouettes actually touch.
        CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
            content()
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun NowPlayingBar(
    track: Track,
    player: ExoPlayer,
    isPlaying: Boolean,
    onBarClick: () -> Unit,
    onToggle: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String, String) -> Unit = { _, _ -> }
) {
    val ctx = LocalContext.current
    val displayYear = remember(track.id) { TrackYear.yearFor(ctx, track)?.takeIf { it > 0 } ?: track.year.takeIf { it > 0 } }
    val albumArtist = remember(track.id) { track.albumArtist.ifBlank { track.artist } }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(track.durationMs.coerceAtLeast(1L)) }
    // Mirrors the same prefs-backed shuffle flag the full Now Playing screen uses — toggling from
    // either place stays in sync since both just read/write PlayerPreferences + player state.
    var shuffle by remember { mutableStateOf(runCatching { player.shuffleModeEnabled }.getOrDefault(PlayerPreferences.loadShuffle(ctx))) }
    LaunchedEffect(track.id) {
        while (true) {
            // This is the most pervasively-mounted poll in the app — alive on every screen the
            // instant a track is queued, not just NowPlaying/Tape. Pause the state write once the
            // real screen is ambient-covered or physically dark (same cutoff as those two).
            try {
                if (!IdleController.visuallyIdle) { pos = player.currentPosition; if (player.duration > 0) dur = player.duration }
            } catch (_: Throwable) {}   // never let a transient player error kill the update loop
            delay(500)
        }
    }
    // The raw fraction only updates every 500ms poll tick, which steps the line visibly — glide
    // between samples so it reads as continuous playback, not a ticking gauge.
    val targetProgress = (pos.toFloat() / dur.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    // PERF (scroll jank, 2026-09-17): kept as a State and NOT unwrapped with `by`. This animation
    // re-targets on every 500ms position poll, so it produces a new value on essentially every
    // frame for as long as anything is playing. It used to be read as a plain Float in this
    // function's body (`Modifier.fillMaxWidth(progress)` further down), which put a ~60Hz
    // invalidation on NowPlayingBar's own recomposition scope: the entire docked bar — artwork,
    // marquee AnnotatedString, chips, buttons — recomposed every frame, forever, on the same main
    // thread the library lists scroll on. The value is now read in the DRAW phase only (see the
    // progress line below): identical animation, zero recomposition.
    val progress = animateFloatAsState(targetProgress, tween(520, easing = LinearEasing), label = "barProgress")
    // Palette comes from the app-wide theme (extracted from the real art once it's loaded) and
    // glides between tracks — no more "stays teal because the art wasn't cached at first compose".
    LaunchedEffect(track.id) { HioneArtTheme.update(ctx, track) }
    val targetPalette = HioneArtTheme.palette
    val pc1 by animateColorAsState(targetPalette.color1, tween(600), label = "barC1")
    val pc2 by animateColorAsState(targetPalette.color2, tween(600), label = "barC2")
    val pc3 by animateColorAsState(targetPalette.color3, tween(600), label = "barC3")
    val palette = ArtPalette(pc1, pc2, pc3)

    // Two-row docked bar. Row 1 is the FULL width for text (thumb at the far left, then one long
    // "Title · Artist · Album · Year" marquee) — the old side-by-side layout left ~80dp for text
    // once art + heart + four transport keys took their share, so titles read as "-Produc". Row 2
    // holds the heart, quality badges and transport. 24dp of clear space stays under the bar so the
    // system's gesture pill never sits on the play key.
    Box(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                // Raised panel drop shadow + top specular hairline
                .drawBehind {
                    val rr = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                    drawRoundRect(Color(0x66000000), topLeft = Offset(0f, 3.dp.toPx()), size = size, cornerRadius = rr)
                }
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            palette.color1.copy(alpha = 0.65f),
                            Color(0x25FFFFFF),
                            Color.Transparent
                        )
                    ),
                    RoundedCornerShape(16.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    Haptics.tick(ctx)
                    onBarClick()
                }
                // Swipe up on the bar = open Now Playing. Edge-safe: the bar's lower edge sits
                // inside the window's bottom 32dp (24dp clear space under it), so a swipe that
                // begins there is the system HOME gesture — never our open. Also declines
                // downward drags at slop instead of consuming them.
                .edgeSafePointerInput(Unit) { guard ->
                    var totalY = 0f
                    detectVerticalDragGesturesEdgeSafe(
                        guard,
                        onDragStart = { totalY = 0f },
                        onDragEnd = {
                            if (totalY < -45f) {
                                Haptics.tick(ctx)
                                onBarClick()
                            }
                        },
                        accept = { overSlop -> overSlop < 0f },
                        onVerticalDrag = { change, dy ->
                            if (dy < 0) change.consume()
                            totalY += dy
                        }
                    )
                }
        ) {
            Box {
                // Dynamic Themed Inlay Background: Full album art inlay + dynamic palette color modding + vignette
                AlbumArtImage(
                    track.id,
                    Modifier.matchParentSize().blur(20.dp),
                    trackPath = track.path,
                    contentScale = ContentScale.Crop
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.horizontalGradient(
                            listOf(
                                palette.color2.copy(alpha = 0.88f),
                                palette.color1.copy(alpha = 0.20f),
                                Mascot.color(0xF2051114),
                                Mascot.color(0xFA02080A)
                            )
                        )
                    )
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x35000000),
                                Color(0x75000000)
                            )
                        )
                    )
                )
                Column(Modifier.fillMaxWidth()) {
                    // Live playback progress line. Was a nested Box sized with
                    // `Modifier.fillMaxWidth(progress)` — a COMPOSITION-phase read of a per-frame
                    // animation (see the note on `progress` above). Same pixels, drawn from the
                    // draw phase, so only this 3dp strip is invalidated per frame.
                    val progressBrush = remember(palette.color1) {
                        Brush.horizontalGradient(listOf(palette.color1, HioneTealBright))
                    }
                    Box(
                        Modifier.fillMaxWidth().height(3.dp).background(Mascot.color(0xFF07201F))
                            .drawBehind {
                                val w = size.width * progress.value.coerceIn(0f, 1f)
                                if (w > 0f) drawRect(progressBrush, size = Size(w, size.height))
                            }
                    )

                    // ROW 1 — thumb + one long marquee line with the full width of the bar.
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, palette.color1.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        ) {
                            AlbumArtImage(track.id, Modifier.fillMaxSize(), trackPath = track.path, contentScale = ContentScale.Crop)
                        }
                        Spacer(Modifier.width(9.dp))
                        val line = remember(track.id, track.title, track.artist, track.album, displayYear, palette.color1) {
                            androidx.compose.ui.text.buildAnnotatedString {
                                withStyle(androidx.compose.ui.text.SpanStyle(color = Mascot.color(0xFFEAF6F4), fontWeight = FontWeight.Bold)) { append(track.title) }
                                withStyle(androidx.compose.ui.text.SpanStyle(color = Mascot.color(0x88EAF6F4))) { append("   ·   ") }
                                withStyle(androidx.compose.ui.text.SpanStyle(color = palette.color1, fontWeight = FontWeight.SemiBold)) { append(track.artist) }
                                if (track.album.isNotBlank()) {
                                    withStyle(androidx.compose.ui.text.SpanStyle(color = Mascot.color(0x88EAF6F4))) { append("   ·   ") }
                                    withStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFFC9DEDB))) { append(track.album) }
                                    if (displayYear != null) withStyle(androidx.compose.ui.text.SpanStyle(color = HioneGold)) { append("  $displayYear") }
                                }
                            }
                        }
                        Text(
                            line,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .weight(1f)
                                .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1200, initialDelayMillis = 900, velocity = 28.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    Haptics.tick(ctx)
                                    onArtistClick(track.artist)
                                }
                        )
                    }

                    // ROW 2 — heart · quality badges · transport keys.
                    Row(
                        Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 3.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RainbowHeart(LikeStore.isLikedEffective(ctx, track), size = 30.dp) { LikeStore.toggleEffective(ctx, track) }
                        Spacer(Modifier.width(6.dp))
                        // BPM game, one tap from anywhere a track is playing. Takes its width from
                        // the badge strip (weighted, clipped), never from the transport keys.
                        com.hione.player.ui.BpmGameButton(
                            from = HioneBpmGameLink.FROM_PLAYER_BAR,
                            accent = palette.color1,
                            size = 28.dp,
                            iconSize = 17.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Row(
                            Modifier.weight(1f).height(20.dp).clipToBounds(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TechBadgeRow(ctx, track, fontSize = 8.5.sp, spacing = 3.dp)
                        }
                        Spacer(Modifier.width(4.dp))

                        // Transport Controls with Tightened, Isolated Touch Hitboxes
                        ControlAssembly {
                            HapticIconButton(
                                onClick = {
                                    shuffle = !shuffle
                                    player.shuffleModeEnabled = shuffle
                                    PlayerPreferences.saveShuffle(ctx, shuffle)
                                },
                                flat = true,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) HionePink else Muted, modifier = Modifier.size(14.dp))
                            }
                            HapticIconButton(
                                onClick = { player.seekToPreviousMediaItem() },
                                keyShape = TransportShapes.prevWing,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.SkipPrevious, "Prev", tint = Muted, modifier = Modifier.size(19.dp))
                            }
                            HapticIconButton(
                                onClick = onToggle,
                                face = palette.color1,
                                keyShape = TransportShapes.hero,
                                modifier = Modifier.size(width = 46.dp, height = 34.dp)
                            ) {
                                PlayPauseGlyph(isPlaying, tint = Mascot.color(0xFF00201D), size = 20.dp)
                            }
                            HapticIconButton(
                                onClick = { player.seekToNextMediaItem() },
                                keyShape = TransportShapes.nextWing,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.SkipNext, "Next", tint = Muted, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}



/** Live-updating number: smoothly counts to new value + flashes Hione-bright on change. */
@Composable private fun AnimatedNumber(
    value: Int,
    modifier: Modifier = Modifier,
    baseColor: Color = HioneTeal,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    suffix: String = ""
) {
    val animated by animateIntAsState(targetValue = value, animationSpec = tween(700, easing = FastOutSlowInEasing), label = "num")
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(value) { flash = true; kotlinx.coroutines.delay(450); flash = false }
    val color by animateColorAsState(if (flash) HioneTealBright else baseColor, tween(400), label = "numColor")
    val scale by animateFloatAsState(if (flash) 1.12f else 1f, spring(dampingRatio = 0.4f), label = "numScale")
    Text("$animated$suffix", color = color, fontSize = fontSize, fontWeight = fontWeight,
        modifier = modifier.scale(scale))
}

@Composable private fun LibraryScreen(
    ctx: android.content.Context,
    listState: LazyListState = rememberLazyListState(),
    trackCount: Int = 0,
    tracks: List<Track> = emptyList(),
    artistCount: Int = 0,
    albumCount: Int = 0,
    loading: Boolean = false,
    videoCount: Int = 0,
    onAllSongs: () -> Unit = {},
    onVideos: () -> Unit = {},
    onOpen: (String) -> Unit
) {
    // LikeStore.liked directly (not a one-shot remember{} of the prefs snapshot, which never
    // updated again after first composition) — this count needs to track live like/unlike taps.
    val liked = LikeStore.liked
    val playlists = remember { PlayerPreferences.loadPlaylists(ctx) }
    // Cheap aggregate pass over what's already in memory — no per-track disk/pref reads, so this
    // is safe to recompute whenever the track list itself changes (a scan finishing, etc).
    val stats = remember(tracks) {
        val totalMs = tracks.sumOf { it.durationMs }
        val totalBytes = tracks.sumOf { it.sizeBytes }
        val fmtCounts = tracks.groupingBy { it.mime.substringAfterLast('/').uppercase().ifBlank { "?" } }.eachCount()
        // Drop anything that would just display as a misleading "0%" (a handful of stray files
        // out of thousands rounds down to nothing and reads like a bug, not real data) — keep a
        // format only once it's at least 1% of the library.
        val fmtTotal = fmtCounts.values.sum().coerceAtLeast(1)
        val topFormats = fmtCounts.entries
            .filter { it.value * 100 / fmtTotal >= 1 }
            .sortedByDescending { it.value }
            .take(4)
        val avgBitrate = tracks.filter { it.bitrateKbps > 0 }.let { l -> if (l.isEmpty()) 0 else l.sumOf { it.bitrateKbps } / l.size }
        val (usedDevice, totalDevice) = deviceStorageStats(tracks)
        LibraryStats(totalMs, totalBytes, topFormats, avgBitrate, usedDevice, totalDevice)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Text("  Your Library", color = HioneTealBright, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 8.dp))
        }
        // Detailed library metrics — a real data dashboard, not just a track count. All derived
        // from the tracks list already resident in memory, so this costs nothing to recompute.
        item { LibraryStatsCard(trackCount, artistCount, albumCount, stats, loading, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        // Songs used to be its own top-level tab — now it lives here, first in the list, since
        // "browse everything" is the most-used entry point into a library screen.
        item { LibRow(Icons.Default.MusicNote, "All Songs", "$trackCount tracks", HioneTealBright, onAllSongs) }
        item { LibRow(Icons.Default.Favorite, "Liked Songs", "${liked.size} songs", HionePink) { onOpen(LIKED_KEY) } }
        item { com.hione.player.wanted.WantedLibRow { onOpen(com.hione.player.wanted.WANTED_LIB_KEY) } }
        if (videoCount > 0) item { LibRow(Icons.Default.Movie, "Videos", "$videoCount videos", HioneGold, onVideos) }
        items(playlists.keys.toList(), key = { it }) { name ->
            LibRow(Icons.Default.QueueMusic, name, "${playlists[name]?.size ?: 0} songs", HioneTeal) { onOpen(name) }
        }
        if (playlists.isEmpty()) item {
            Text("  No playlists yet. Add songs to a playlist from any track.", color = Muted, fontSize = 13.sp,
                modifier = Modifier.padding(16.dp, 8.dp))
        }
    }
}

private data class LibraryStats(
    val totalDurationMs: Long,
    val totalBytes: Long,
    val topFormats: List<Map.Entry<String, Int>>,
    val avgBitrateKbps: Int,
    val usedDeviceBytes: Long,
    val totalDeviceBytes: Long
)

/** Real device storage (total/used) for whichever volume(s) the library actually lives on — NOT
 *  just the library's own footprint. Sums StatFs across every distinct `/storage/<volume>` root
 *  seen in the track paths (primary emulated storage + any SD card), so a library split across
 *  both still reports accurate combined figures instead of only ever reflecting one volume. */
private fun deviceStorageStats(tracks: List<Track>): Pair<Long, Long> {
    val roots = tracks.mapNotNullTo(LinkedHashSet()) { STORAGE_ROOT_RE.find(it.path)?.groupValues?.get(1) }
    var total = 0L
    var used = 0L
    roots.forEach { root ->
        runCatching {
            val stat = android.os.StatFs(root)
            val t = stat.blockCountLong * stat.blockSizeLong
            val avail = stat.availableBlocksLong * stat.blockSizeLong
            total += t
            used += (t - avail)
        }
    }
    return used to total
}
private val STORAGE_ROOT_RE = Regex("^(/storage/[^/]+)")

/** Detailed library dashboard, redesigned per an explicit ask for real graphics instead of plain
 *  number+label pairs — a storage donut (device used/total, with the library's own footprint
 *  highlighted as a distinct inner arc), a format-mix stacked bar, and a quality gauge reusing the
 *  same speedometer-arc widget artist/album rows already use elsewhere — plus icon-tagged count
 *  tiles. Deliberately a fixed, compact non-scrolling Column (not LazyColumn) sized to read as one
 *  glanceable dashboard, not something you scroll through to see all the metrics. */
@Composable private fun LibraryStatsCard(trackCount: Int, artistCount: Int, albumCount: Int, stats: LibraryStats, loading: Boolean = false, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Surface1, Mascot.color(0xFF0A2528))))
            .border(1.dp, HioneTealBright.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text("LIBRARY STATS", color = HioneTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))

        if (loading) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = HioneTealBright, modifier = Modifier.size(28.dp))
            }
            return@Column
        }

        // Row 1: storage donut (real device used/total + library footprint) + format-mix bar.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StorageDonut(
                usedBytes = stats.usedDeviceBytes,
                totalBytes = stats.totalDeviceBytes,
                libraryBytes = stats.totalBytes,
                modifier = Modifier.size(92.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text("FORMAT MIX", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                FormatMixBar(stats.topFormats, Modifier.fillMaxWidth().height(12.dp))
                Spacer(Modifier.height(6.dp))
                Column {
                    stats.topFormats.take(3).forEach { (fmt, count) ->
                        val total = stats.topFormats.sumOf { it.value }.coerceAtLeast(1)
                        val pct = count * 100 / total
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(formatColor("audio/${fmt.lowercase()}")))
                            Spacer(Modifier.width(5.dp))
                            Text("$fmt $pct%", color = Muted, fontSize = 9.5.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Row 2: quality gauge (avg bitrate, reusing the same speedometer widget artist/album rows
        // use) + the count/listening tiles, each with its own small bespoke icon glyph.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 1500kbps is a reasonable "top of the scale" for a mixed FLAC/lossy library —
                // this is a relative-quality dial, not a claim of an absolute universal ceiling.
                QualityGauge(
                    score = if (stats.avgBitrateKbps > 0) (stats.avgBitrateKbps / 1500f).coerceIn(0f, 1f) else null,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(if (stats.avgBitrateKbps > 0) "${stats.avgBitrateKbps} kbps" else "—", color = Muted, fontSize = 9.sp)
            }
            LibraryMetricTile(Icons.Default.MusicNote, "$trackCount", "Tracks", HioneTealBright, Modifier.weight(1f))
            LibraryMetricTile(Icons.Default.Person, "$artistCount", "Artists", HionePink, Modifier.weight(1f))
            LibraryMetricTile(Icons.Default.Album, "$albumCount", "Albums", HioneGold, Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, null, tint = Mascot.color(0xFFB9C2D0), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(fmtDurationLong(stats.totalDurationMs), color = Mascot.color(0xFFB9C2D0), fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = OrbitronFont)
            Spacer(Modifier.width(5.dp))
            Text("total listening time", color = Muted, fontSize = 10.5.sp)
        }
    }
}

/** Compact icon+number+label tile — each library-count metric gets its own icon so the three read
 *  as visually distinct at a glance, not three identical number blocks. */
@Composable private fun LibraryMetricTile(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = OrbitronFont)
        Text(label, color = Muted, fontSize = 9.sp)
    }
}

/** Device storage ring — outer dim track = total capacity, bright arc = used, and a thinner
 *  highlighted inner arc = this library's own footprint within that used space, so it's visible
 *  at a glance how much of "used" is actually Hione Music's doing vs everything else on the device. */
@Composable private fun StorageDonut(usedBytes: Long, totalBytes: Long, libraryBytes: Long, modifier: Modifier = Modifier) {
    val usedFrac = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val libFrac = if (totalBytes > 0) (libraryBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, usedFrac) else 0f
    val animUsed by animateFloatAsState(usedFrac, tween(600), label = "storageUsed")
    val animLib by animateFloatAsState(libFrac, tween(600), label = "storageLib")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.14f
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(Mascot.color(0xFF12292C), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Color(0xFF4FC3F7), -90f, animUsed * 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (animLib > 0.002f) {
                val libInset = stroke * 1.1f
                drawArc(
                    HionePink, -90f, animLib * 360f, false,
                    Offset(libInset, libInset),
                    androidx.compose.ui.geometry.Size(size.width - libInset * 2, size.height - libInset * 2),
                    style = Stroke(stroke * 0.4f, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(usedBytes / 1e9).let { "%.0f".format(it) }}", color = Color(0xFF4FC3F7), fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = OrbitronFont)
            Text("/${(totalBytes / 1e9).let { "%.0f".format(it) }} GB", color = Muted, fontSize = 8.5.sp)
        }
    }
}

/** Proportional stacked bar — one segment per format, width = share of the library, colored to
 *  match formatColor() so it lines up with the format badges used everywhere else in the app. */
@Composable private fun FormatMixBar(topFormats: List<Map.Entry<String, Int>>, modifier: Modifier = Modifier) {
    val total = topFormats.sumOf { it.value }.coerceAtLeast(1)
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.06f))) {
        topFormats.forEach { (fmt, count) ->
            val frac = (count.toFloat() / total).coerceAtLeast(0.01f)
            Box(
                Modifier
                    .weight(frac)
                    .fillMaxHeight()
                    .background(formatColor("audio/${fmt.lowercase()}"))
            )
        }
    }
}

/** "3d 4h 12m" / "6h 20m" / "42m" — total listening time, human scale (fmtDuration elsewhere is
 *  per-track mm:ss, not the right shape for a library-wide total). */
private fun fmtDurationLong(ms: Long): String {
    val totalMin = ms / 60_000
    val days = totalMin / (60 * 24)
    val hours = (totalMin % (60 * 24)) / 60
    val mins = totalMin % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins}m"
    }
}

@Composable private fun LibRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tint: Color, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Mascot.color(0xFF123438)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint)
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Text(title, color = Mascot.color(0xFFE8F4F2), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(sub, color = Muted, fontSize = 12.sp)
    }
    Icon(Icons.Default.ChevronRight, null, tint = Muted)
}

@Composable private fun Centered(msg: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(msg, color = Muted, fontSize = 15.sp)
}

@Composable private fun PermissionPrompt(onGrant: () -> Unit) = Column(
    Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Default.MusicNote, null, tint = HioneTeal, modifier = Modifier.size(64.dp))
    Spacer(Modifier.height(16.dp))
    Text("${Mascot.musicAppName} needs access to your audio", color = Mascot.color(0xFFE8F4F2), fontSize = 16.sp)
    Spacer(Modifier.height(20.dp))
    Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = HioneTeal)) { Text("Grant access", color = Mascot.color(0xFF00201D)) }
}

/** Shown instead of the whole app on anything that isn't the HiBy M500 — see isSupportedDevice(). */
@Composable private fun UnsupportedDeviceScreen() = Box(Modifier.fillMaxSize().background(Ground), contentAlignment = Alignment.Center) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.MusicNote, null, tint = HioneTeal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("${Mascot.musicAppName} is built for the HiBy M500", color = Mascot.color(0xFFE8F4F2), fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "It relies on this device's own hardware (DAC/amp controls, side transport keys, on-device library) and won't run anywhere else.",
            color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center
        )
    }
}



/** Home-screen "RANDOM" pill: one tap → a random track from the whole library starts instantly
 *  (InstantRandom), queue fills behind it. Lit in the art accent while random mode is active. */
@Composable private fun RandomModePill(count: Int, onRandom: () -> Unit) {
    val ac = HioneArtTheme.colors()
    val active = InstantRandom.active
    val glow by animateColorAsState(if (active) ac.accent2 else ac.accent, tween(400), label = "randomPillGlow")
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(glow.copy(alpha = 0.22f), ac.surface.copy(alpha = 0.85f))))
            .border(1.dp, Brush.horizontalGradient(listOf(glow.copy(alpha = 0.9f), glow.copy(alpha = 0.25f))), RoundedCornerShape(16.dp))
            .clickable { onRandom() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Casino, null, tint = glow, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (active) "RANDOM MODE ON" else "RANDOM",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 1.sp
            )
            Text(
                if (active) "Rolling through the whole library · tap to re-roll" else "Play anything, instantly · $count tracks",
                color = Muted, fontSize = 11.sp, fontFamily = Baloo2Font, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.PlayArrow, null, tint = glow, modifier = Modifier.size(22.dp))
    }
}


/** Settings → "Power Governor": manual override, live profile readout, per-profile toggles. */
@Composable private fun PowerGovernorCard(ctx: android.content.Context) {
    LaunchedEffect(Unit) { HionePowerGovernor.init(ctx) }
    val profile = HionePowerGovernor.profile
    val mode = HionePowerGovernor.mode
    val ac = HioneArtTheme.colors()
    val profileColor = when (profile) {
        HionePowerGovernor.Profile.PERF -> HionePink
        HionePowerGovernor.Profile.BALANCED -> ac.accent
        HionePowerGovernor.Profile.AUDIO_ONLY -> HioneGold
        HionePowerGovernor.Profile.IDLE -> Muted
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, profileColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ACTIVE PROFILE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.weight(1f))
            Text(profile.key.uppercase().replace('_', ' '), color = profileColor, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = OrbitronFont)
        }
        Text(HionePowerGovernor.describe(), color = Color(0xFFC9DEDB), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
        // OS-wide summary: the mode below is Settings.Global hione_power_mode (launcher POWER MODE tile
        // cycles the same key), so this line is the single source of truth for "what is the device doing".
        var battery by remember { mutableStateOf(HionePowerGovernor.batterySummary(ctx)) }
        LaunchedEffect(profile, mode) { while (true) { battery = HionePowerGovernor.batterySummary(ctx); kotlinx.coroutines.delay(10_000) } }
        Text("Mode ${mode.key.uppercase()} (Settings.Global hione_power_mode) · $battery", color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 3.dp))
        Text(
            when (profile) {
                HionePowerGovernor.Profile.PERF -> "Sustained clocks + render hints: visualizer and Now Playing at full speed."
                HionePowerGovernor.Profile.BALANCED -> "Normal clocks, normal background work."
                HionePowerGovernor.Profile.AUDIO_ONLY -> "Screen off. Everything but the DAC path is held back. Battery saver ${if (HionePowerGovernor.saverInAudioOnly) "ON" else "off"}."
                HionePowerGovernor.Profile.IDLE -> "Nothing playing. Background work is idle and battery saver runs while the screen is off."
            },
            color = Muted, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text("MODE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                HionePowerGovernor.Mode.AUTO to "Auto",
                HionePowerGovernor.Mode.PERF to "Perf",
                HionePowerGovernor.Mode.SAVE to "Save"
            ).forEach { (m, label) ->
                val sel = m == mode
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) profileColor.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (sel) profileColor else Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .clickable { Haptics.tick(ctx); HionePowerGovernor.setMode(ctx, m) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(label, color = if (sel) Color.White else Muted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(4.dp))
        SettingsToggleRow(
            title = "Battery saver in Audio-Only",
            subtitle = "With the screen off and music playing, battery saver turns on and goes back when you leave. The DAC path is never touched.",
            checked = HionePowerGovernor.saverInAudioOnly
        ) { HionePowerGovernor.setSaverInAudioOnly(ctx, it) }
        SettingsToggleRow(
            title = "Cap visualizer at 30 FPS",
            subtitle = "Always (auto-applies at ≥ 42 °C regardless)",
            checked = HionePowerGovernor.userVisFpsCap30
        ) { HionePowerGovernor.setUserVisFpsCap30(ctx, it) }
    }
}
