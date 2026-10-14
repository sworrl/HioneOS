package com.hione.systemui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * HioneOS navigation layer. AOSP SystemUI still runs and still provides the status bar, the
 * nav bar inset and the edge back gesture; this accessibility service adds the Hione parts:
 *
 *  - Back: NOT here any more. AOSP's EdgeBackGestureHandler is the only back handler. It works
 *    in every app, respects gesture-exclusion rects and immersive mode, and does predictive
 *    back. Hione used to run its own edge strips as well, so every edge swipe had two handlers
 *    fighting over it, and an app that excluded its edges (drawers, carousels) still lost them
 *    to our overlay. The debug back broadcast still works (performGlobalAction).
 *  - Home pill (full-width zone, as tall as the real nav bar inset): swipe up / fling = HOME;
 *    swipe up then pause = Hione Recents; horizontal swipe = quick switch (right = previous
 *    app, left = forward again). Anything that does not turn into one of those is replayed
 *    into the app underneath, so bottom tabs and buttons in that band still work.
 *  - Top strip (as tall as the real status bar, transparent): a downward drag opens the Hione
 *    shade with the drag offset; anything else is replayed.
 *  - Replay-on-lift: the a11y MotionEventInjector cancels injected input while a real finger is
 *    still moving, so live pass-through is impossible. The strip keeps the touch, and on release
 *    re-injects it with dispatchGesture while the strip is NOT_TOUCHABLE.
 *  - Power: framework assistant path owns long-press (see frameworkOwnsPowerLongPress).
 */
class HioneNotificationShadeService : AccessibilityService() {

    companion object {
        private const val TAG = "HioneNav"
        /** Draws its own status row on home, the lockscreen and AOD. */
        private const val LAUNCHER_PKG = "com.hione.launcher"
        /**
         * Retro modes (Riot mode, HionePod). While one is in front, no modern system UI shows: no
         * track toast, volume or brightness HUD, DAC badge, heads-up notifications, top pull strip
         * or home pill. The app draws its own era's volume and status, and has its own exit.
         */
        val RETRO_PKGS = setOf("com.hione.riot", "com.hione.wheel")
        const val ACTION_TRIGGER_BACK = "com.hione.systemui.action.TRIGGER_BACK"
        const val ACTION_DEBUG_QUICK_SWITCH = "com.hione.systemui.action.DEBUG_QUICK_SWITCH"
        const val ACTION_DEBUG_RECENTS = "com.hione.systemui.action.DEBUG_RECENTS"
        const val ACTION_DEBUG_HOME = "com.hione.systemui.action.DEBUG_HOME"
        const val ACTION_DEBUG_BACK = "com.hione.systemui.action.DEBUG_BACK"
        /**
         * Opens the Hione shade window from another HioneOS app (the launcher's top bar). The sender
         * must hold android.permission.STATUS_BAR, which only platform-signed apps get.
         */
        const val ACTION_OPEN_SHADE = "com.hione.systemui.action.OPEN_SHADE_WINDOW"

        // Gesture geometry (dp)
        const val POWER_HOLD_MS = 450L
        /**
         * Fallback height of the top strip when the framework's status_bar_height can't be read.
         * The strip used to be 40dp so the pull was easy to catch in apps that draw to the top
         * edge, but at 40dp it sat over app toolbars (every tap there was delayed to lift and
         * replayed) and over a status bar that is now visible again. It is now exactly the
         * status bar, which is where a pull starts on every other Android phone.
         */
        const val TOP_STRIP_FALLBACK_DP = 24
        /** Fallback height of the pill zone when navigation_bar_height can't be read. */
        const val PILL_ZONE_FALLBACK_DP = 24
        const val SHADE_PULL_DP = 24f
        const val PILL_W_DP = 104f
        const val PILL_H_DP = 4f
        const val HOME_DP = 24f
        /** Fallback only. The pill uses ViewConfiguration.scaledMinimumFlingVelocity, same as AOSP. */
        const val HOME_FLING_PX_S = 900f
        /**
         * AOSP `motion_pause_detector_min_displacement`. Overview opens once the swipe has come
         * this far AND the motion pauses. Was 48dp plus a 150ms stillness timer, which is a much
         * higher bar than a Pixel and is what made the app switcher feel cumbersome.
         */
        const val RECENTS_DP = 24f
        // AOSP MotionPauseDetector speeds, in dp per MILLISECOND (Launcher3 res/values/dimens.xml:
        // motion_pause_detector_speed_{very_fast,fast,somewhat_fast,slow}).
        const val PAUSE_SPEED_VERY_FAST_DP_MS = 3.0f
        const val PAUSE_SPEED_FAST_DP_MS = 1.0f
        const val PAUSE_SPEED_SOMEWHAT_FAST_DP_MS = 0.9f
        const val PAUSE_SPEED_SLOW_DP_MS = 0.15f
        /** MotionPauseDetector.RAPID_DECELERATION_FACTOR. */
        const val PAUSE_RAPID_DECELERATION_FACTOR = 0.6f
        /** MotionPauseDetector.FORCE_PAUSE_TIMEOUT: no motion at all for this long counts as a pause. */
        const val PAUSE_FORCE_TIMEOUT_MS = 300L
        /** AOSP quick switch commits just past the touch slop, not at 32dp. */
        const val QUICK_SWITCH_DP = 16f
        const val QUICK_SWITCH_MAX_DY_DP = 16f
        const val QUICK_SWITCH_SESSION_MS = 2500L
        const val REPLAY_MAX_MS = 600L
    }

    private lateinit var windowManager: WindowManager
    /** OS-wide idle dim + tap-to-awaken ladder (see HioneIdleDim.kt). Lives here because this
     *  service is the only thing on the device that sees input from every app. */
    private var idleDim: HioneIdleDimController? = null
    /** The shade, as a persistent window rather than an Activity. See HioneShadeWindow. */
    private var shadeWindow: HioneShadeWindow? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workThread = HandlerThread("hione-nav-work").apply { start() }
    private val workHandler = Handler(workThread.looper)
    private var overlaysAdded = false

    private var topStripView: ShadePullView? = null
    private var pillView: HomePillView? = null
    private var topParams: WindowManager.LayoutParams? = null
    private var pillParams: WindowManager.LayoutParams? = null

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    // ------------------------------------------------------------------ broadcasts

    /** Now-Playing HUD (track-change pop-over drawn in our overlay layer). */
    private var trackHud: HioneTrackHud? = null
    /** Universal volume HUD overlay for 3rd-party apps (Spotify, etc.). */
    private var volumeHud: HioneVolumeHud? = null
    /** Brightness HUD: warm horizontal bar under the status bar (see HioneBrightnessHud). */
    private var brightnessHud: HioneBrightnessHud? = null
    /** DAC badge in the middle of the status bar (see HioneDacBadge). */
    private var dacBadge: HioneDacBadge? = null

    /** Album accent bled ≈25% into the nav chrome (pill glow, back capsule), animated 400ms. */
    @Volatile private var navTeal = HioneAccent.TEAL
    @Volatile private var navTealBright = HioneAccent.TEAL_BRIGHT
    private var accentAnim: ValueAnimator? = null
    private var accentJob: kotlinx.coroutines.Job? = null
    private val accentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private fun startAccentObserver() {
        HioneAccent.observe(this)
        accentJob?.cancel()
        accentJob = accentScope.launch {
            HioneAccent.accent.collect { a ->
                val toTeal = HioneAccent.tealTinted(a, 0.25f); val toBright = HioneAccent.tealBrightTinted(a, 0.25f)
                val fromTeal = navTeal; val fromBright = navTealBright
                accentAnim?.cancel()
                accentAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = HionePowerProfile.ms(400).toLong()
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        navTeal = HioneAccent.mix(fromTeal, toTeal, f); navTealBright = HioneAccent.mix(fromBright, toBright, f)
                        pillView?.invalidate()
                    }
                    start()
                }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                HioneTrackHud.ACTION_TRACK_CHANGED, HioneTrackHud.ACTION_DEBUG -> {
                    val a = intent.getIntExtra("accent", 0); val a2 = intent.getIntExtra("accent2", 0)
                    if (a != 0) HioneAccent.push(a, a2)
                    if (!retroInFront) trackHud?.show(HioneTrackHud.Payload.from(intent))
                }
                "android.media.VOLUME_CHANGED_ACTION",
                "android.media.MASTER_VOLUME_CHANGED_ACTION",
                "android.media.RINGER_MODE_CHANGED" -> {
                    // One HUD at a time: the centre-capsule volume style sits where this bar does.
                    brightnessHud?.hide()
                    if (!retroInFront) volumeHud?.onVolumeChanged()
                }
                HioneBrightnessHud.ACTION_SHOW -> {
                    volumeHud?.hide()
                    if (!retroInFront) brightnessHud?.show()
                }
                ACTION_TRIGGER_BACK, ACTION_DEBUG_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                ACTION_DEBUG_HOME -> triggerHome()
                ACTION_DEBUG_RECENTS -> openRecents()
                ACTION_DEBUG_QUICK_SWITCH -> {
                    val dir = if (intent.getStringExtra("dir") == "next") -1 else 1
                    quickSwitch(dir)
                }
            }
        }
    }

    /** Separate from [receiver]: this one is permission-guarded. */
    private val openShadeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_OPEN_SHADE) return
            Log.i(TAG, "open shade: broadcast")
            mainHandler.post { openShadeActivity(-1) }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        idleDim = HioneIdleDimController(this, windowManager)
        trackHud = HioneTrackHud(this, windowManager) { dragPx -> openShadeActivity(dragPx) }
        volumeHud = HioneVolumeHud(this, windowManager)
        brightnessHud = HioneBrightnessHud(this, windowManager) { shadeWindow?.isOpen == true }
        HionePowerProfile.observe(this)
        HioneHaptics.ensureDefaults(this)
        startAccentObserver()
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_TRIGGER_BACK); addAction(ACTION_DEBUG_BACK)
                addAction(ACTION_DEBUG_HOME); addAction(ACTION_DEBUG_RECENTS)
                addAction(ACTION_DEBUG_QUICK_SWITCH)
                addAction(HioneTrackHud.ACTION_TRACK_CHANGED); addAction(HioneTrackHud.ACTION_DEBUG)
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.MASTER_VOLUME_CHANGED_ACTION")
                addAction("android.media.RINGER_MODE_CHANGED")
                addAction(HioneBrightnessHud.ACTION_SHOW)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) { Log.w(TAG, "receiver register failed: $t") }
        try {
            val f = IntentFilter(ACTION_OPEN_SHADE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(openShadeReceiver, f, "android.permission.STATUS_BAR", mainHandler, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(openShadeReceiver, f, "android.permission.STATUS_BAR", mainHandler)
            }
        } catch (t: Throwable) { Log.w(TAG, "open-shade receiver register failed: $t") }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Before addOverlays(): accessibility overlays stack in add order, so starting the idle
        // ladder first puts its 1px touch sentinel UNDERNEATH the nav strips rather than stealing
        // the top-left pixel of the shade pull strip.
        idleDim?.start()
        // The DAC badge goes in before the shade window, so the open shade covers it.
        if (dacBadge == null) {
            dacBadge = HioneDacBadge(this, windowManager, systemDimenPx("status_bar_height", TOP_STRIP_FALLBACK_DP))
        }
        dacBadge?.attach()
        // Attach BEFORE the nav overlays so the shade sits underneath the top strip and the home
        // pill in z-order: the strip must keep receiving the pull that opens it.
        if (shadeWindow == null) {
            shadeWindow = HioneShadeWindow(
                ctx = this,
                windowManager = windowManager,
                onOpenSettings = {
                    runCatching {
                        packageManager.getLaunchIntentForPackage("com.hione.settings")
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let { startActivity(it) }
                    }
                },
                onOpenPower = { openPowerMenuActivity() }
            )
        }
        shadeWindow?.attach()
        addOverlays()
        HioneNotificationStore.ensureEnabled(this)
        HioneRotationKeeper.start(this)
        suppressStockShade()
        mainHandler.post(checkOwnBar)
        Log.i(TAG, "HioneNav connected; overlays=$overlaysAdded canGestures=" +
            (serviceInfo?.capabilities?.and(android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0))
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Throwable) {}
        try { unregisterReceiver(openShadeReceiver) } catch (_: Throwable) {}
        idleDim?.destroy(); idleDim = null
        shadeWindow?.detach(); shadeWindow = null
        trackHud?.destroy(); trackHud = null
        volumeHud?.destroy(); volumeHud = null
        brightnessHud?.destroy(); brightnessHud = null
        dacBadge?.detach(); dacBadge = null
        accentJob?.cancel(); accentAnim?.cancel()
        removeOverlays()
        try { workThread.quitSafely() } catch (_: Throwable) {}
        super.onDestroy()
    }

    /**
     * The AOSP-stock SystemUI we ship still draws its own status bar and pull-down shade, which
     * competed with the Hione shade ("both stock and our swipe-from-top"). Block the stock shade
     * expansion and its home/recents via StatusBarManager so only the Hione top strip opens a
     * shade. Our shade is a separate overlay, unaffected by DISABLE_EXPAND.
     */
    /**
     * True while a Hione screen that draws its own status row (home, lockscreen, AOD) is in front.
     * Those screens hide the stock bar, but Android still reveals it for about 3 seconds on every
     * wake, and re-hiding does not cancel that reveal. Blanking the stock icons while they are in
     * front means the reveal shows nothing, so there is never a second clock.
     */
    private var ownBarInFront = false
    /** A retro mode (RETRO_PKGS) is in front. */
    @Volatile private var retroInFront = false

    private val checkOwnBar = Runnable {
        val top = HioneTaskStack.topTask(this)?.second
        val pkg = top?.packageName
        val cls = top?.className
        dacBadge?.setForeground(pkg, cls)
        idleDim?.setForeground(pkg, cls)
        val own = pkg == LAUNCHER_PKG
        val retro = pkg in RETRO_PKGS
        if (own != ownBarInFront || retro != retroInFront) {
            ownBarInFront = own
            if (retro != retroInFront) {
                retroInFront = retro
                applyRetroMode()
            }
            suppressStockShade()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.removeCallbacks(checkOwnBar)
        mainHandler.post(checkOwnBar)
    }

    /** Hide or bring back the Hione overlays for a retro mode. */
    private fun applyRetroMode() {
        val vis = if (retroInFront) View.GONE else View.VISIBLE
        topStripView?.visibility = vis
        pillView?.visibility = vis
        if (retroInFront) {
            trackHud?.hide(); volumeHud?.hideImmediately(); brightnessHud?.hideImmediately()
        }
        dacBadge?.setRetro(retroInFront)
        Log.i(TAG, "retro mode ${if (retroInFront) "on: modern overlays hidden" else "off"}")
    }

    private fun suppressStockShade() {
        runCatching {
            // The application context's StatusBarManager, never this service's: each Context gets
            // its own manager with its own disable token, so a re-created service used to leave
            // the previous instance's flags (stock icons hidden) stuck in StatusBarManagerService
            // and third-party apps showed an empty status bar. One app-wide token replaces itself.
            val sb = applicationContext.getSystemService(Context.STATUS_BAR_SERVICE)
            // What stays disabled, and why:
            //   EXPAND  the stock shade panel. The Hione top strip opens the Hione shade instead.
            //   HOME, RECENT  the stock home handle and recents. The Hione pill owns those gestures.
            //           (The handle itself is also made transparent by the HioneSystemUIOverlay
            //           RRO, since DISABLE_HOME alone did not always stop it drawing.)
            // What is deliberately NOT disabled any more:
            //   CLOCK, SYSTEM_INFO, NOTIFICATION_ICONS  blanking them left third-party apps with
            //           an empty black status bar (the launcher hides the bars itself, so it was
            //           only ever other apps that saw it).
            //   BACK    AOSP's edge back gesture is now the only back handler on the device.
            //   NOTIFICATION_ALERTS  heads-up popups and sounds still come from stock SystemUI.
            // StatusBarManager.disable() replaces the whole set on every call, so the flags that
            // were set before are cleared simply by not passing them.
            val DISABLE_EXPAND = 0x00010000
            val DISABLE_HOME = 0x00200000
            val DISABLE_RECENT = 0x01000000
            // While a Hione screen with its own status row is in front, the stock icons go too.
            val DISABLE_NOTIFICATION_ICONS = 0x00020000
            val DISABLE_SYSTEM_INFO = 0x00100000
            val DISABLE_CLOCK = 0x00800000
            val DISABLE_NOTIFICATION_ALERTS = 0x00040000
            val icons = if (ownBarInFront || retroInFront) DISABLE_NOTIFICATION_ICONS or DISABLE_SYSTEM_INFO or DISABLE_CLOCK else 0
            // Retro modes also drop heads-up popups and notification sounds.
            val alerts = if (retroInFront) DISABLE_NOTIFICATION_ALERTS else 0
            val flags = DISABLE_EXPAND or DISABLE_HOME or DISABLE_RECENT or icons or alerts
            sb.javaClass.getMethod("disable", Int::class.javaPrimitiveType).invoke(sb, flags)
            Log.i(TAG, "stock shade blocked, stock home/recents disabled, stock icons ${if (ownBarInFront) "hidden (Hione screen in front)" else "shown"}")
        }.onFailure { Log.w(TAG, "suppressStockShade failed: $it") }
    }

    override fun onInterrupt() {}

    private fun overlayParams(w: Int, h: Int, gravity: Int, noLimits: Boolean = true) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (noLimits) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0),
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }

    /**
     * A framework dimen in px, or [fallbackDp] when the resource is missing or reads as zero.
     * The strips size themselves from these so they cover exactly the real bars, no more.
     */
    private fun systemDimenPx(name: String, fallbackDp: Int): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        val px = if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
        return if (px > 0) px else dp(fallbackDp.toFloat()).toInt()
    }

    /**
     * Adds the navigation overlays (top pull strip, home pill). There must be exactly ONE of each
     * on screen, most of all the home pill, so any previous set is torn down first
     * (onServiceConnected can fire again after a rebind on the SAME instance, which would
     * otherwise stack a second pill).
     */
    private fun addOverlays() {
        if (overlaysAdded || topStripView != null || pillView != null) {
            Log.i(TAG, "addOverlays: tearing down previous overlay set first")
            removeOverlays()
        }
        val topPx = systemDimenPx("status_bar_height", TOP_STRIP_FALLBACK_DP)
        // navigation_bar_height is the inset apps see (41px here); navigation_bar_frame_height is
        // the taller window AOSP draws in. The inset is the band apps already keep clear.
        val pillZoneH = systemDimenPx("navigation_bar_height", PILL_ZONE_FALLBACK_DP)
            .coerceIn(dp(16f).toInt(), dp(48f).toInt())
        Log.i(TAG, "overlays: top strip ${topPx}px, pill zone ${pillZoneH}px")

        topStripView = ShadePullView(this).also { v ->
            topParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, topPx, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            // Spy first (see stripIsSpy). If the window manager refuses it, a plain overlay.
            stripIsSpy = makeSpy(topParams!!) &&
                runCatching { windowManager.addView(v, topParams) }.onFailure { Log.w(TAG, "top strip add as spy: $it") }.isSuccess
            if (!stripIsSpy) {
                topParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, topPx, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                runCatching { windowManager.addView(v, topParams) }.onFailure { Log.w(TAG, "top strip add: $it") }
            }
            Log.i(TAG, "top strip is ${if (stripIsSpy) "an input spy" else "a plain overlay"}")
        }
        pillView = HomePillView(this).also { v ->
            // Full width so the home swipe is catchable across the whole bottom edge, not only the
            // centre (3rd-party apps like Spotify own the centre-bottom). Touches that are not a
            // gesture are replayed, so app bottom tabs in this band still get their taps. The pill
            // is still DRAWN centred (onDraw uses width/2).
            pillParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, pillZoneH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            runCatching { windowManager.addView(v, pillParams) }.onFailure { Log.w(TAG, "pill add: $it") }
        }
        overlaysAdded = true
    }

    private fun removeOverlays() {
        listOf(topStripView, pillView).forEach { v ->
            v?.let { runCatching { windowManager.removeView(it) } }
        }
        topStripView = null; pillView = null; topParams = null; pillParams = null
        overlaysAdded = false
    }

    // ------------------------------------------------------------------ actions

    private fun launchOwnActivity(cls: Class<*>, requestCode: Int, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent(this, cls).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
            extras?.invoke(this)
        }
        try {
            // PendingIntent.send() keeps the launch on OUR background-activity-launch grant.
            PendingIntent.getActivity(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).send()
        } catch (_: Throwable) {
            try { startActivity(intent) } catch (t: Throwable) { Log.w(TAG, "launch ${cls.simpleName}: $t") }
        }
    }

    /**
     * Opens the shade. Named for history: it is no longer an Activity.
     *
     * HioneShadeActivity cost 524 to 861ms on a warm launch, paid on EVERY pull, with the first
     * frames of the drag landing inside activity creation. [shadeWindow] is composed once when this
     * service connects and only shown, which is how a Pixel's shade works. The Activity is still in
     * the manifest so the quick-settings tile and any external launcher intent keep working.
     */
    private fun openShadeActivity(dragOffsetPx: Int = -1) {
        if (retroInFront) return
        // If stock SystemUI's panel got out anyway (an app called expandNotificationsPanel before
        // DISABLE_EXPAND landed), fold it so only the Hione shade is on screen.
        runCatching {
            val sb = getSystemService(Context.STATUS_BAR_SERVICE)
            sb.javaClass.getMethod("collapsePanels").invoke(sb)
        }
        val w = shadeWindow
        if (w != null) { w.open(dragOffsetPx); return }
        launchOwnActivity(HioneShadeActivity::class.java, 0) {
            if (dragOffsetPx >= 0) putExtra(HioneShadeActivity.EXTRA_DRAG_OFFSET_PX, dragOffsetPx)
        }
    }

    private var lastPowerMenuOpenMs = 0L
    private fun openPowerMenuActivity() {
        // Debounce: the framework assist path (if it ever fires) and our hold timer may both
        // land within the same hold — one launch per second is plenty.
        val now = SystemClock.uptimeMillis()
        if (now - lastPowerMenuOpenMs < 1000L) return
        lastPowerMenuOpenMs = now
        launchOwnActivity(HionePowerMenuActivity::class.java, 1)
    }

    fun openRecents() = launchOwnActivity(HioneRecentsActivity::class.java, 2)

    fun triggerHome() {
        // Pixel: leaving an app for home flashes the handle; going home from home does nothing.
        workHandler.post {
            val fromApp = runCatching { !HioneTaskStack.isHomeOnTop(this) }.getOrDefault(false)
            if (fromApp) mainHandler.post { pillView?.flashGlow() }
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // Quick-switch session: the MRU list is captured on the first swipe and walked for 2.5 s.
    private var qsList: List<HioneTaskStack.Entry> = emptyList()
    private var qsIndex = -1          // index of the task we are standing on (-1 = home/unknown)
    private var qsFromHome = false
    private var qsTime = 0L

    /** dir = +1 → previous app (swipe right); dir = -1 → forward again (swipe left). */
    fun quickSwitch(dir: Int) {
        workHandler.post {
            val now = SystemClock.elapsedRealtime()
            if (now - qsTime > QUICK_SWITCH_SESSION_MS || qsList.isEmpty()) {
                qsList = HioneTaskStack.recents(this, 8)
                val top = HioneTaskStack.topTask(this)
                qsFromHome = HioneTaskStack.isHomeOnTop(this)
                qsIndex = qsList.indexOfFirst { it.taskId == top?.first }
            }
            val target = qsIndex + dir
            val ok = when {
                target in qsList.indices -> HioneTaskStack.switchTo(this, qsList[target].taskId).also { if (it) qsIndex = target }
                target < 0 && qsFromHome && qsIndex >= 0 -> { mainHandler.post { triggerHome() }; qsIndex = -1; true }
                else -> false
            }
            qsTime = now
            Log.i(TAG, "quickSwitch dir=$dir target=$target ok=$ok list=${qsList.map { it.pkg }}")
            if (!ok) mainHandler.post { pillView?.rejectBounce() }
        }
    }

    // ------------------------------------------------------------------ replay-on-lift

    data class TouchPt(val x: Float, val y: Float, val t: Long)

    /**
     * True while a replayed touch is being injected. The injected stroke is a REAL screen touch,
     * so once the strip goes touchable again it can land back on us and replay itself — the
     * strips ignore new ACTION_DOWNs while this is set (observed: one tap replayed 3x).
     */
    @Volatile private var replayInFlight = false

    private fun setTouchable(view: View?, params: WindowManager.LayoutParams?, touchable: Boolean) {
        if (view == null || params == null) return
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val next = if (touchable) params.flags and f.inv() else params.flags or f
        if (next == params.flags) return
        params.flags = next
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * Re-inject a touch that a strip swallowed but that was NOT a gesture (tap, vertical
     * scroll, long press) so the app underneath still receives it. Real timing is kept up to
     * REPLAY_MAX_MS; longer drags are time-compressed.
     */
    private fun replayTouch(points: List<TouchPt>, view: View?, params: WindowManager.LayoutParams?) {
        if (points.isEmpty()) return
        val first = points.first(); val last = points.last()
        val path = Path().apply { moveTo(first.x, first.y) }
        var travelled = 0f
        var px = first.x; var py = first.y
        for (p in points.drop(1)) {
            val d = abs(p.x - px) + abs(p.y - py)
            if (d < 1f) continue
            path.lineTo(p.x, p.y); travelled += d; px = p.x; py = p.y
        }
        val realDur = (last.t - first.t).coerceAtLeast(1L)
        val dur = if (travelled < 8f) realDur.coerceIn(20L, REPLAY_MAX_MS) else realDur.coerceIn(1L, REPLAY_MAX_MS)
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()
        } catch (t: Throwable) { Log.w(TAG, "replay build: $t"); return }
        Log.i(TAG, "replay: pts=${points.size} travelled=${travelled.toInt()} dur=$dur")
        replayInFlight = true
        setTouchable(view, params, false)
        val restore = Runnable { setTouchable(view, params, true); replayInFlight = false }
        val ok = try {
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { mainHandler.post(restore) }
                override fun onCancelled(g: GestureDescription?) { mainHandler.post(restore) }
            }, mainHandler)
        } catch (t: Throwable) { Log.w(TAG, "replay dispatch: $t"); false }
        if (!ok) restore.run() else mainHandler.postDelayed(restore, dur + 400L)   // safety net
    }

    // ------------------------------------------------------------------ keys / events

    private var powerDownTimestamp = 0L

    /**
     * True when the framework itself routes long-press-power to HionePowerMenuActivity
     * (Settings.Global power_button_long_press = 5 = LONG_PRESS_POWER_ASSISTANT with
     * Settings.Secure assistant = our component — seeded by the ROM rc). In that mode this
     * accessibility key filter must stand down, otherwise one hold fires the modal twice.
     */
    private fun frameworkOwnsPowerLongPress(): Boolean = try {
        android.provider.Settings.Global.getInt(contentResolver, "power_button_long_press", 0) == 5 &&
            (android.provider.Settings.Secure.getString(contentResolver, "assistant") ?: "")
                .startsWith("com.hione.systemui/")
    } catch (_: Throwable) { false }

    /** Hold timer for the power key: fires the modal at [POWER_HOLD_MS] unless an UP cancels it. */
    private val powerHoldRunnable = Runnable {
        powerDownTimestamp = 0L
        // Fire only while the display is still interactive: from the Hione lockscreen a power
        // DOWN turns the panel off immediately, and a modal over a dark panel is worse than none.
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return@Runnable
        Log.i(TAG, "power hold ${POWER_HOLD_MS}ms → power menu")
        openPowerMenuActivity()
    }

    /**
     * Power long-press → HionePowerMenuActivity, owned HERE regardless of the framework path.
     * Settings power_button_long_press=5 + assistant=our component is still kept: it makes the
     * framework mark the key handled (no sleep on release) and play the long-press haptic. But
     * on this HiBy SystemUI the assist hand-off (StatusBar.startAssist → AssistManager) dies
     * silently — the user gets the vibration and no modal (measured 2026-08-25: powerLongPress
     * behavior=5 logged, no activity start ever follows). So the a11y key filter times the hold
     * itself. Timer-based on DOWN (not "second DOWN > 450ms"): key repeats are synthesized inside
     * InputDispatcher AFTER the a11y input filter, so this service only ever sees DOWN and UP.
     * Double-fire is harmless: the activity is singleInstance and [openPowerMenuActivity] has a
     * debounce.
     */
    /**
     * Fn pocket lock, key half (root-free). The Fn switch shares the gpio-keys input device with
     * the transport buttons, so that device can't be disabled without losing the unlock event;
     * instead swallow the keys here while fn_status=1. Covers every app while the screen is on
     * (Hione Music additionally ignores media buttons itself, which also covers screen-off).
     */
    private fun fnLockSwallows(keyCode: Int): Boolean {
        val cr = contentResolver
        val locked = runCatching { android.provider.Settings.Global.getInt(cr, "fn_status", 0) == 1 }.getOrDefault(false)
        if (!locked) return false
        val mode = runCatching { android.provider.Settings.Global.getString(cr, "fn_settings") }.getOrNull() ?: "touch_and_key_lock"
        if (mode == "touch_lock") return false                       // keys deliberately live
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, android.view.KeyEvent.KEYCODE_HEADSETHOOK -> true
            android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN ->
                runCatching { android.provider.Settings.Global.getInt(cr, "m500_fn_allow_volume_wheel", 0) != 1 }.getOrDefault(true)
            else -> false
        }
    }

    /**
     * Rotary volume knob ("ring-keys" KEY_VOLUMEUP/KEY_VOLUMEDOWN), owned HERE for every output.
     *
     * Root cause of "knob works on the 4.4mm jack but not on the 3.5mm jack / speaker while the
     * on-screen sliders always work" (verified in the decompiled vendor services.jar, 2026-08-29):
     * every GUI slider ends in AudioService.setStreamVolume(), but a key press ends in
     * AudioService.adjustStreamVolume() — and HiBy's AudioService has an ADJUST-ONLY, RAISE-ONLY,
     * PER-JACK gate at the top of adjustStreamVolume(): when the current index is already >=
     * getLockMaxVolume() the step is silently swallowed (it only re-broadcasts the old index).
     * getLockMaxVolume() comes from getPluggedInState(): balanced = 40 (35), 3.5mm h2w = 50 (40),
     * USB/SPDIF = 80, speaker/nothing = 100 (101) — the bracketed values apply while
     * Settings.Global "volum_tips_ce_flag" != "yes"; the gate is armed by
     * Settings.Global "vendor.audio.hw.volume_lock" == "yes" OR "volum_tips_ce_flag" != "yes"
     * (HiBy's out-of-box defaults). Key presses also pass through MediaSessionService, which
     * re-targets the stream (ring/notification/voice-call "recently active" logic) and, with the
     * screen off and nothing playing, drops the key outright ("Nothing is playing on the music
     * stream. Skipping volume event"). None of that applies to setStreamVolume(), which HiBy's
     * own setStreamVolumeIndex() then applies to the ACTIVE device as policy index 100 + HAL
     * master volume (CS43198 "Plat Left/Right Playback Volume") — the same thing a slider does.
     *
     * So: step STREAM_MUSIC with setStreamVolume(current ± 1) — the exact slider path — and
     * consume the key. The launcher / player HUDs are driven by VOLUME_CHANGED_ACTION, so they
     * still pop. External (BT/USB) remotes keep the framework path (they need key repeat);
     * injected keys (`input keyevent 24/25`) take this path so adb can verify it.
     *
     * Screen off: PhoneWindowManager runs BEFORE the a11y input filter and, while music is
     * active, has already dispatched this key through MediaSessionService — stepping again here
     * would double it, so only take over when that framework path would have dropped the key.
     */
    private fun handleVolumeKnob(event: android.view.KeyEvent): Boolean {
        val dev = event.device
        if (dev != null && dev.isExternal) return false
        val am = getSystemService(android.media.AudioManager::class.java) ?: return false
        val interactive = runCatching {
            getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
        }.getOrDefault(true)
        if (!interactive && am.isMusicActive) return false
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return true   // the UP of a pair we own
        val stream = android.media.AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        val cur = am.getStreamVolume(stream)
        val step = if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
        val target = (cur + step).coerceIn(0, max)
        // FLAG_SHOW_UI only when the user opted back into HiBy's fullscreen dialog (it is what
        // that dialog keys on); our own HUD listens to VOLUME_CHANGED_ACTION instead.
        val showHiby = runCatching {
            android.provider.Settings.Global.getInt(contentResolver, "hiby_volume_dialog_enable", 0) == 1
        }.getOrDefault(false)
        val flags = if (showHiby) android.media.AudioManager.FLAG_SHOW_UI else 0
        val ok = runCatching { am.setStreamVolume(stream, target, flags); true }
            .onFailure { Log.w(TAG, "knob: setStreamVolume($target/$max) failed", it) }
            .getOrDefault(false)
        if (ok) {
            if (target != cur) HioneHaptics.tick(this)   // one detent per knob step, none at the end stops
            volumeHud?.onVolumeChanged(step)
        }
        return ok
    }

    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null) return false
        if (fnLockSwallows(event.keyCode)) return true
        // Any key (incl. the volume knob) is an interaction: restore full brightness immediately.
        if (event.action == android.view.KeyEvent.ACTION_DOWN) idleDim?.poke()
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Retro modes take the volume keys themselves and draw their own era's volume bar.
            if (retroInFront) return super.onKeyEvent(event)
            if (handleVolumeKnob(event)) return true
        }
        // Brightness keys (a keyboard or remote that has them): step and show the brightness bar.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BRIGHTNESS_UP ||
            event.keyCode == android.view.KeyEvent.KEYCODE_BRIGHTNESS_DOWN) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                volumeHud?.hide()
                brightnessHud?.step(if (event.keyCode == android.view.KeyEvent.KEYCODE_BRIGHTNESS_UP) 1 else -1)
            }
            return true
        }
        if (event.keyCode == android.view.KeyEvent.KEYCODE_POWER) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 || powerDownTimestamp == 0L) {
                    powerDownTimestamp = System.currentTimeMillis()
                    mainHandler.removeCallbacks(powerHoldRunnable)
                    mainHandler.postDelayed(powerHoldRunnable, POWER_HOLD_MS)
                }
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                powerDownTimestamp = 0L
                mainHandler.removeCallbacks(powerHoldRunnable)
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Idle ladder: a THIRD interaction source behind the 1px touch sentinel and onKeyEvent.
        // ONLY unambiguously user-initiated event types are listed. Deliberately excluded:
        // TYPE_WINDOW_CONTENT_CHANGED (a ticking clock fires it every second), TYPE_VIEW_SCROLLED
        // (programmatic scrolls and animations fire it) and TYPE_VIEW_TEXT_CHANGED (a field
        // updated in code fires it) — any of those would reset the idle timer with nobody
        // touching the device and the screen would never dim at all.
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> idleDim?.poke()
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Hand the ladder the new foreground app so it can stand down for the apps that run
            // their own brightness lifecycle (Hione Music, the HioneOS lockscreen/AOD).
            idleDim?.setForeground(event.packageName?.toString(), event.className?.toString())
            // Read the real top task a moment later: this event also fires for dialogs, toasts
            // and the keyboard, which are not what owns the screen.
            mainHandler.removeCallbacks(checkOwnBar)
            mainHandler.postDelayed(checkOwnBar, 150)
            volumeHud?.setForeground(event.packageName?.toString(), event.className?.toString())
            val cls = event.className?.toString() ?: ""
            val pkg = event.packageName?.toString() ?: ""
            if (cls.contains("GlobalActions", ignoreCase = true) ||
                cls.contains("PowerDialog", ignoreCase = true) ||
                cls.contains("ShutdownActivity", ignoreCase = true) ||
                (pkg.contains("android") && cls.contains("GlobalActionsDialog", ignoreCase = true))) {
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Throwable) {}
                openPowerMenuActivity()
                return
            }
            // Stock SystemUI's brightness dialog (ACTION_SHOW_BRIGHTNESS_DIALOG): close it and show
            // the Hione brightness bar instead, so there is one brightness UI and it is ours.
            if (cls.contains("BrightnessDialog", ignoreCase = true)) {
                runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
                volumeHud?.hide()
                if (!retroInFront) brightnessHud?.show()
                return
            }
            if (cls.contains("NotificationShade", ignoreCase = true) ||
                cls.contains("QuickSettings", ignoreCase = true) ||
                cls.contains("StatusBarWindow", ignoreCase = true)) {
                openShadeActivity()
            }
        }
    }

    // ================================================================== TOP SHADE PULL

    /**
     * The top strip is an input SPY window when the platform lets us (INPUT_FEATURE_SPY needs
     * MONITOR_INPUT, a signature permission we hold as a platform app).
     *
     * Why: Android 14's DisplayPolicy watches every touch on the screen. When it sees a swipe down
     * from the top edge over an app that shows its status bar, it calls transferTouch() and hands
     * the rest of the finger to the stock StatusBar window, so the stock shade can follow it
     * (DisplayPolicy.requestTransientBars). A normal overlay then only gets ACTION_CANCEL, and with
     * stock expansion disabled the pull did nothing at all in apps like Google Play. The launcher
     * hides the bars, takes the transient-bars path instead and never lost the touch, which is why
     * the pull only ever worked on home.
     *
     * A spy window receives the whole gesture alongside whatever window is touched, and a transfer
     * does not take it away. Once the drag is clearly a pull it calls pilferPointers(), which
     * cancels the touch for the status bar and the app, and the shade follows the finger from there.
     * Taps are not taken at all, so the app under the strip gets them directly, with no replay.
     */
    private var stripIsSpy = false

    private fun makeSpy(lp: WindowManager.LayoutParams): Boolean = runCatching {
        val spy = WindowManager.LayoutParams::class.java.getField("INPUT_FEATURE_SPY").getInt(null)
        val f = WindowManager.LayoutParams::class.java.getField("inputFeatures")
        f.setInt(lp, f.getInt(lp) or spy)
        true
    }.onFailure { Log.w(TAG, "spy flag: $it") }.getOrDefault(false)

    /** Cancel the touch for every other window; this spy keeps the stream. */
    private fun pilfer(view: View): Boolean = runCatching {
        val vri = View::class.java.getMethod("getViewRootImpl").invoke(view) ?: return@runCatching false
        val token = vri.javaClass.getMethod("getInputToken").invoke(vri) as? android.os.IBinder ?: return@runCatching false
        val im = getSystemService(Context.INPUT_SERVICE)
        im.javaClass.getMethod("pilferPointers", android.os.IBinder::class.java).invoke(im, token)
        true
    }.onFailure { Log.w(TAG, "pilferPointers: $it") }.getOrDefault(false)

    inner class ShadePullView(context: Context) : View(context) {
        private var down = false
        private var opened = false
        private var startX = 0f; private var startY = 0f
        private var lastY = 0f
        private var velocity: VelocityTracker? = null
        private val points = ArrayList<TouchPt>(64)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val now = SystemClock.uptimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (replayInFlight) return false          // our own injected touch
                    down = true; opened = false
                    startX = event.rawX; startY = event.rawY; lastY = startY
                    velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    points.clear(); points += TouchPt(startX, startY, now)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!down) return false
                    velocity?.addMovement(event)
                    if (points.size < 400) points += TouchPt(event.rawX, event.rawY, now)
                    lastY = event.rawY
                    val dy = event.rawY - startY
                    if (!opened) {
                        val dx = abs(event.rawX - startX)
                        if (dy >= dp(SHADE_PULL_DP) && dx < dy) {
                            opened = true
                            val took = if (stripIsSpy) pilfer(this) else true
                            Log.i(TAG, "top strip pull -> shade (dy=${dy.toInt()}, spy=$stripIsSpy, pilfered=$took)")
                            HioneHaptics.tick(this)
                            HioneShadeDrag.begin(dy)                  // the shade follows this finger 1:1
                            openShadeActivity(dy.toInt())
                        }
                    } else {
                        HioneShadeDrag.move(dy)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!down) return false
                    down = false
                    points += TouchPt(event.rawX, event.rawY, now)
                    velocity?.addMovement(event); velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    velocity?.recycle(); velocity = null
                    if (opened) HioneShadeDrag.release(vy)
                    // A spy never took the touch, so the app already had it. Only a plain overlay
                    // has to replay what it swallowed.
                    else if (!stripIsSpy) replayTouch(ArrayList(points), topStripView, topParams)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // Something else took the finger (the back-gesture spy, or the status bar
                    // transfer on a plain overlay). A pull that got going still opens the shade.
                    val pullingDown = down && (lastY - startY) >= dp(8f)
                    down = false
                    velocity?.recycle(); velocity = null
                    if (opened) HioneShadeDrag.release(if (pullingDown) 2000f else 0f)
                    else if (pullingDown && !retroInFront) openShadeActivity(-1)
                    Log.i(TAG, "top strip: touch cancelled (opened=$opened, dy=${(lastY - startY).toInt()})")
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    // ================================================================== HOME PILL

    inner class HomePillView(context: Context) : View(context) {
        private var down = false
        private var fired = false
        private var startX = 0f; private var startY = 0f
        private var curX = 0f; private var curY = 0f
        private var velocity: VelocityTracker? = null
        private var stretch = 0f       // 0..1 upward drag progress
        private var shiftX = 0f        // horizontal follow
        private var armed = false      // recents hold armed (pill turns teal)
        // ---- AOSP MotionPauseDetector port. A Pixel opens Overview the moment the swipe-up
        // DECELERATES past 24dp, not when the finger has been held perfectly still for a fixed
        // time. The old 48dp + 150ms-still rule is what "cumbersome" meant.
        private var pausePrevSpeed = 0f       // px/ms
        private var pauseIsPaused = false
        private var pauseEverPaused = false
        private var pauseDisabled = false     // a very fast flick is a fling home, never Overview
        private var pauseLastTime = 0L
        private var pauseLastY = 0f
        private var pauseSlowCount = 0
        private var pop = 1f
        private var flash = 0f         // 90ms glow flash when leaving an app for home
        private var flashAnim: ValueAnimator? = null
        private var claimed = false    // first light tick once the drag is clearly upward
        private var holdScheduled = false  // holdCheck poll is running; see ACTION_MOVE
        private var relaxAnim: ValueAnimator? = null
        private var popAnim: ValueAnimator? = null
        /** The whole touch, for replaying it into the app when it was not a gesture. */
        private val points = ArrayList<TouchPt>(64)

        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xD9FFFFFF.toInt() }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x38FFC56B }
        private val rect = RectF()

        /**
         * FORCE_PAUSE_TIMEOUT arm: a finger held truly still can stop producing MOVE events, so
         * AOSP treats "no motion for 300ms" as a pause outright. Re-posted on every MOVE, which is
         * correct here (unlike the old stillness poll) because the timeout means "no events at
         * all", and a jittering still finger is already caught by the slow-speed path below.
         */
        private val forcePause = Runnable {
            if (down && !fired && !pauseDisabled) onMotionPaused()
        }

        /** MotionPauseDetector.checkMotionPaused, constants and all. Speeds are px/ms. */
        private fun checkMotionPaused(speed: Float, prevSpeed: Float) {
            val slow = dp(PAUSE_SPEED_SLOW_DP_MS)
            val somewhatFast = dp(PAUSE_SPEED_SOMEWHAT_FAST_DP_MS)
            val fast = dp(PAUSE_SPEED_FAST_DP_MS)
            val paused: Boolean
            if (pauseIsPaused) {
                // Stay paused until the finger clearly moves again.
                paused = speed < fast
            } else if (speed < slow) {
                // AOSP wants two slow samples in a row before the first pause sticks.
                pauseSlowCount++
                paused = pauseEverPaused || pauseSlowCount >= 2
            } else {
                pauseSlowCount = 0
                // Be aggressive about the FIRST pause so it feels responsive: a rapid deceleration
                // counts even if the finger has not actually come to a stop yet.
                paused = !pauseEverPaused &&
                    speed < prevSpeed * PAUSE_RAPID_DECELERATION_FACTOR && speed < somewhatFast
            }
            if (paused && !pauseIsPaused) { pauseIsPaused = true; pauseEverPaused = true; onMotionPaused() }
            else if (!paused) pauseIsPaused = false
        }

        /** The pause fired. Open Overview if the swipe has come far enough. */
        private fun onMotionPaused() {
            if (fired) return
            val dyUp = startY - curY
            if (dyUp < dp(RECENTS_DP)) return
            Log.i(TAG, "pill motion pause -> recents (dyUp=${dyUp.toInt()})")
            fired = true; armed = true
            HioneHaptics.pop(this@HomePillView)                      // strong: pause → recents
            animatePop()
            openRecents()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val pw = dp(PILL_W_DP) * (1f + 0.18f * stretch)
            val ph = dp(PILL_H_DP)
            // The zone is only as tall as the nav inset (24dp here), so the lift is capped to
            // keep the pill and its glow inside the window instead of clipping off the top.
            val lift = min(dp(12f), (h - dp(8f) - ph - dp(7f)).coerceAtLeast(0f)) * stretch
            val cx = w / 2f + shiftX
            val bottom = h - dp(8f) - lift
            canvas.save()
            canvas.scale(pop, pop, cx, bottom - ph / 2f)
            val glowA = ((if (armed) 0x90 else (0x30 + (0x50 * stretch).toInt())) + (0x60 * flash).toInt()).coerceAtMost(0xF0)
            val gb = navTealBright and 0x00FFFFFF
            glowPaint.shader = android.graphics.LinearGradient(cx - pw / 2f - dp(10f), 0f, cx + pw / 2f + dp(10f), 0f,
                intArrayOf(gb, (glowA shl 24) or gb, gb), null, android.graphics.Shader.TileMode.CLAMP)
            rect.set(cx - pw / 2f - dp(10f), bottom - ph - dp(6f), cx + pw / 2f + dp(10f), bottom + dp(6f))
            canvas.drawRoundRect(rect, ph + dp(6f), ph + dp(6f), glowPaint)
            rect.set(cx - pw / 2f, bottom - ph, cx + pw / 2f, bottom)
            pillPaint.color = if (armed) navTealBright else 0xB3FFFFFF.toInt()
            canvas.drawRoundRect(rect, ph / 2f, ph / 2f, pillPaint)
            canvas.restore()
        }

        private fun animateRelax() {
            relaxAnim?.cancel()
            val s0 = stretch; val x0 = shiftX
            relaxAnim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = HioneMotion.ms(220).toLong(); interpolator = HioneMotion.overshoot(1.2f)
                addUpdateListener { val f = it.animatedValue as Float; stretch = s0 * f; shiftX = x0 * f; invalidate() }
                start()
            }
        }

        private fun animatePop() {
            popAnim?.cancel()
            popAnim = ValueAnimator.ofFloat(1f, 1.25f, 1f).apply {
                duration = HioneMotion.ms(160).toLong(); interpolator = HioneMotion.overshoot(1.4f)
                addUpdateListener { pop = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        /** Quick 90ms glow flash — played when a HOME gesture leaves a foreground app. */
        fun flashGlow() {
            if (HioneMotion.quiet) return
            flashAnim?.cancel()
            flashAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 90
                addUpdateListener { flash = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun rejectBounce() {
            HioneHaptics.reject(this)
            relaxAnim?.cancel()
            relaxAnim = ValueAnimator.ofFloat(0f, 1f, -1f, 0f).apply {
                duration = 260
                addUpdateListener { shiftX = dp(6f) * (it.animatedValue as Float); invalidate() }
                start()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (replayInFlight) return false          // our own injected touch
                    relaxAnim?.cancel()
                    down = true; fired = false; armed = false; claimed = false; holdScheduled = false
                    startX = event.rawX; startY = event.rawY; curX = startX; curY = startY
                    points.clear(); points += TouchPt(startX, startY, SystemClock.uptimeMillis())
                    pausePrevSpeed = 0f; pauseIsPaused = false; pauseEverPaused = false
                    pauseDisabled = false; pauseSlowCount = 0
                    pauseLastTime = event.eventTime; pauseLastY = event.rawY
                    mainHandler.removeCallbacks(forcePause)
                    velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!down) return false
                    velocity?.addMovement(event)
                    curX = event.rawX; curY = event.rawY
                    if (points.size < 400) points += TouchPt(curX, curY, SystemClock.uptimeMillis())
                    val dyUp = (startY - curY).coerceAtLeast(0f)
                    val dx = curX - startX
                    val max = dp(RECENTS_DP)
                    stretch = if (dyUp <= max) dyUp / max else 1f + (dyUp - max) / max * 0.12f
                    shiftX = (dx * 0.5f).coerceIn(-dp(24f), dp(24f))
                    if (!claimed && (dyUp >= dp(8f) || abs(dx) >= dp(8f))) { claimed = true; HioneHaptics.tick(this) }   // light: claimed
                    // Overview is decided by the AOSP motion-pause rule, not by a stillness timer.
                    if (!fired) {
                        val dt = (event.eventTime - pauseLastTime).coerceAtLeast(1L)
                        val speed = abs(event.rawY - pauseLastY) / dt      // px/ms
                        pauseLastTime = event.eventTime; pauseLastY = event.rawY
                        // A genuinely fast flick is a fling home. AOSP stops looking for a pause
                        // at all once the gesture has been that fast.
                        if (speed > dp(PAUSE_SPEED_VERY_FAST_DP_MS)) pauseDisabled = true
                        if (!pauseDisabled) {
                            checkMotionPaused(speed, pausePrevSpeed)
                            mainHandler.removeCallbacks(forcePause)
                            mainHandler.postDelayed(forcePause, PAUSE_FORCE_TIMEOUT_MS)
                        }
                        pausePrevSpeed = speed
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!down) return false
                    down = false
                    holdScheduled = false
                    mainHandler.removeCallbacks(forcePause)
                    velocity?.addMovement(event)
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    velocity?.recycle(); velocity = null
                    val dyUp = startY - curY
                    val dx = curX - startX
                    val dy = abs(curY - startY)
                    Log.i(TAG, "pill up: dyUp=${dyUp.toInt()} dx=${dx.toInt()} vy=${vy.toInt()} fired=$fired")
                    if (event.actionMasked == MotionEvent.ACTION_UP && !fired) {
                        // AOSP uses the platform's own minimum fling velocity here rather than a
                        // magic number, so a flick that registers as a fling anywhere else in the
                        // system registers as one on the pill too.
                        val flingPxS = android.view.ViewConfiguration.get(context)
                            .scaledMinimumFlingVelocity.toFloat().coerceAtLeast(1f)
                        if (dyUp >= dp(HOME_DP) || (vy < -flingPxS && dyUp >= dp(12f))) {
                            fired = true
                            HioneHaptics.confirm(this)
                            animatePop()
                            triggerHome()
                        } else if (abs(dx) >= dp(QUICK_SWITCH_DP) && dy < dp(QUICK_SWITCH_MAX_DY_DP)) {
                            fired = true
                            HioneHaptics.confirm(this)
                            animatePop()
                            quickSwitch(if (dx > 0) 1 else -1)
                        }
                    }
                    // Not a gesture (a tap on an app's bottom tab, a short scroll, a long press):
                    // hand it to the app underneath.
                    if (event.actionMasked == MotionEvent.ACTION_UP && !fired) {
                        points += TouchPt(event.rawX, event.rawY, SystemClock.uptimeMillis())
                        replayTouch(ArrayList(points), pillView, pillParams)
                    }
                    armed = false
                    animateRelax()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
