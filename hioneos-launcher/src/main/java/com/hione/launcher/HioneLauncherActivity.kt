package com.hione.launcher

import com.hione.launcher.R
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.hione.launcher.ui.hionePressScale
import com.hione.launcher.ui.hioneAppIconClickable
import com.hione.launcher.ui.homeVerticalSwipe
import com.hione.launcher.ui.HioneDimens
import com.hione.launcher.ui.QuiltBadge
import com.hione.launcher.ui.HioneQuiltPrefs
import com.hione.launcher.ui.HioneQuiltConfig
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import com.hione.launcher.ui.hioneBeatPulse
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import com.hione.launcher.ui.gatedFloat
import com.hione.launcher.ui.gatedColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class InstalledApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val iconResId: Int?,
    val systemIcon: Drawable?,
    val category: String = "All",
    val isSystemApp: Boolean = false
)

data class RecentTaskItem(
    val taskId: Int,
    val label: String,
    val packageName: String,
    val iconRes: Int?,
    val systemIcon: Drawable?,
    val baseIntent: Intent?
)

data class HioneWallpaperTheme(
    val id: String,
    val name: String,
    val resId: Int
)

fun resolveAppDisplayLabel(pkg: String, activityName: String = "", rawLabel: String = ""): String {
    val p = pkg.lowercase()
    val a = activityName.lowercase()
    val r = rawLabel.lowercase()
    return when {
        p == "com.android.settings" || (p.contains("settings") && !p.contains("hione")) -> "Legacy Settings"
        p == "com.caf.fmradio" -> "FM Radio"
        p.contains("radio") && !p.contains("hione") -> "Legacy FM Radio"
        p == "com.hiby.music" || p.contains("hibymusic") -> "Legacy HiBy Music"
        p.contains("hibytape") || p == "com.hiby.tape" -> "Legacy Tape"
        p == "com.hione.player" && (a.contains("settings") || r.contains("settings")) -> "${Mascot.name} Settings"
        p == "com.hione.player" && (a.contains("fmradio") || a.contains("radio") || r.contains("radio")) -> "FM Radio"
        p == "com.hione.player" -> "${Mascot.musicAppName}"
        else -> rawLabel.ifBlank { "App" }
    }
}

/** The pearl's ring once Hione Music's icon has gone gold (see HioneSecrets.GOLDEN_ICON). */
private val GOLD_RING_STOPS = listOf(Color(0xFFFFF3B0), Color(0xFFFFC930), Color(0xFF8A5A00), Color(0xFFFFD54F), Color(0xFFFFF3B0))

fun resolveCustomAppIcon(pkg: String, label: String = "", activityName: String = ""): Int? {
    if (pkg == "com.android.settings") {
        return R.drawable.ic_settings_hione
    }
    val p = (pkg + " " + label + " " + activityName).lowercase()
    return when {
        p.contains("magisk") || p.contains("topjohnwu") -> R.drawable.ic_magisk_sakura
        p.contains("spotify") -> R.drawable.ic_spotify_pink_hione
        p.contains("setting") -> R.drawable.ic_hione_settings_bespoke
        p.contains("last.fm") || p.contains("lastfm") || pkg.contains("last.fm") -> R.drawable.ic_lastfm_hione
        p.contains("rioriot") || p.contains("rio riot") || p.contains("riot") || pkg.contains("riot") -> R.drawable.ic_rio_riot
        p.contains("fmradio") || p.contains("com.caf.fmradio") || (p.contains("fm") && p.contains("radio")) || (p.contains("radio") && !p.contains("last")) -> R.drawable.ic_hione_fm_boombox
        p.contains("hione.player") || p.contains("hione player") || p.contains("hione music") || (pkg == "com.hione.player") -> R.drawable.ic_hione_music_brand
        p.contains("hiby.music") || p.contains("hibymusic") -> R.drawable.ic_hiby_music_hione
        p.contains("roon") -> R.drawable.ic_hiby_roon_ready_hione
        p.contains("hibyblue") -> R.drawable.ic_hibyblue_hione
        p.contains("hibycast") -> R.drawable.ic_hibycast_hione
        p.contains("hibysupport") -> R.drawable.ic_hibysupport_hione
        p.contains("hibyusb") -> R.drawable.ic_hibyusb_hione
        p.contains("gallery") || p.contains("photos") -> R.drawable.ic_gallery_hione
        p.contains("documentsui") || p.contains("file") -> R.drawable.ic_file_hione
        p.contains("camera") || p.contains("opencamera") -> R.drawable.ic_camera_hione
        p.contains("clock") || p.contains("deskclock") -> R.drawable.ic_clock_hione
        p.contains("calculator") || p.contains("calc") -> R.drawable.ic_calculator_hione
        p.contains("firefox") || p.contains("mozilla") -> R.drawable.ic_firefox_hione
        p.contains("chrome") -> R.drawable.ic_chrome_hione
        p.contains("browser") -> R.drawable.ic_browser_hione
        else -> null
    }
}

class HioneLauncherActivity : ComponentActivity() {

    companion object {
        var requestedRecentsOpen by mutableStateOf(false)
        /** Set by an `open_ingest` launch extra (Hione Music's Ingress button) → opens the ingest observatory. */
        var requestedIngestOpen by mutableStateOf(false)

        /** Set by an `open_bpm` launch extra (Hione Music's BPM game button) → opens the BPM observatory. */
        var requestedBpmOpen by mutableStateOf(false)
        /** Set when the HOME intent re-arrives while we're already on top (gesture-pill swipe up): dismiss overlays. */
        var requestedHome by mutableStateOf(false)
        /** Set by an `open_battery` launch extra (Hione Music Settings → Battery & Power Core). */
        var requestedBatteryOpen by mutableStateOf(false)
    }

    private fun hideSystemBars() {
        try {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        } catch (_: Throwable) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        HioneCompositing.attach(this)   // load saved KDE-compositing per-event choices
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        hideSystemBars()

        if (intent?.getBooleanExtra("open_recents", false) == true) {
            requestedRecentsOpen = true
        }
        if (intent?.getBooleanExtra("open_ingest", false) == true) {
            requestedIngestOpen = true
        }
        if (intent?.getBooleanExtra("open_bpm", false) == true) {
            requestedBpmOpen = true
        }
        if (intent?.getBooleanExtra("open_battery", false) == true) {
            requestedBatteryOpen = true
        }

        // Asynchronously initialize background system services, ADB & network daemons without blocking UI
        Thread {
            try {
                android.provider.Settings.Secure.putInt(contentResolver, "user_setup_complete", 1)
                android.provider.Settings.Global.putInt(contentResolver, "device_provisioned", 1)
                // Dev builds only: a release build (ro.hione.release=1) leaves adb to the user.
                if (!HioneSystemTuning.isReleaseBuild()) {
                    android.provider.Settings.Global.putInt(contentResolver, android.provider.Settings.Global.ADB_ENABLED, 1)
                    android.provider.Settings.Global.putInt(contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
                    android.provider.Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 1)
                }
            } catch (_: Throwable) {}

            // The high-perf Wi-Fi lock moved into HioneSystemTuning. It used to be acquired here
            // into a local val that went straight out of scope, and WifiLock releases on finalize,
            // so the lock was dropped by the next GC and dumpsys showed none held.

            // Startup tuning, via platform APIs rather than a root shell. This was a 19-command
            // string handed to RootShell.execFast, and since HioneOS has no root it failed on every
            // single launch and applied none of it. adb_wifi_enabled was in there, which is how the
            // ingest relay reaches this device, so the sync could never have worked. See
            // HioneSystemTuning for what carried over and what cannot (the setprop lines and the
            // adbd restart are not ours to make, and are dropped rather than faked).
            HioneSystemTuning.apply(applicationContext)

            // Initialize Real-Time Network Telemetry & Auto-Rejoin Daemon (Protected)
            try {
                com.hione.launcher.network.HioneNetworkService.startMonitoring(applicationContext)
            } catch (t: Throwable) {
                android.util.Log.e("HioneLauncher", "Network service start failed", t)
            }

            // Initialize Real-Time GPS & Open-Meteo Weather Engine (Protected)
            try {
                com.hione.launcher.weather.HioneWeatherService.start(applicationContext)
            } catch (t: Throwable) {
                android.util.Log.e("HioneLauncher", "Weather service start failed", t)
            }

            // OS-wide gesture navigation now lives in com.hione.systemui (HioneNotificationShadeService,
            // an accessibility service auto-enabled at first boot above) — no in-launcher overlay.

            // Initialize OS-Level Real-Time BPM Engine & Pulsar Light Link
            try {
                // Ensure the HioneSystemUI accessibility service (the OS nav + notification shade)
                // is enabled — on a fresh /data the ROM boot-grant doesn't reliably stick, which
                // leaves the stock SystemUI status bar/shade showing through instead of ours.
                runCatching {
                    val comp = "com.hione.systemui/com.hione.systemui.HioneNotificationShadeService"
                    val cur = android.provider.Settings.Secure.getString(
                        contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                    if (!cur.split(":").contains(comp)) {
                        val updated = if (cur.isBlank()) comp else "$cur:$comp"
                        android.provider.Settings.Secure.putString(
                            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                        android.provider.Settings.Secure.putInt(
                            contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    }
                }
                com.hione.launcher.bpm.HioneBpmEngine.startListening(applicationContext)
            } catch (t: Throwable) {
                android.util.Log.e("HioneLauncher", "BPM engine start failed", t)
            }

            // Play HioneOS Welcome Jingle (once per boot, gate-controlled by user toggle)
            try {
                com.hione.launcher.audio.HioneBootWelcomeService.maybePlay(applicationContext)
            } catch (_: Throwable) {}

            // Initialize OS-Level Audio Track Library Telemetry Engine
            try {
                com.hione.launcher.track.HioneLibraryEngine.init(applicationContext)
            } catch (t: Throwable) {
                android.util.Log.e("HioneLauncher", "Library engine start failed", t)
            }



            // Start System-Level USB Audio & Host Controller Service
            try {
                com.hione.launcher.usb.HioneUsbAudioHostService.start(applicationContext)
            } catch (_: Throwable) {}

            // Install the OS-level Hione lockscreen power-button/screen state machine.
            try {
                com.hione.launcher.lockscreen.HioneLockscreenManager.install(applicationContext)
            } catch (_: Throwable) {}
        }.start()

        setContent {
            // One semantics node for the whole launcher when the only accessibility service is our
            // own navigation. Compose rebuilt and re-walked the full tree on every layout pass for
            // it, for content it never reads. Full tree comes back with a real screen reader on.
            val navOnly = androidx.compose.runtime.remember { com.hione.launcher.ui.HioneSemanticsGate.onlyHioneNavEnabled(this) }
            androidx.compose.foundation.layout.Box(
                if (navOnly) androidx.compose.ui.Modifier.clearAndSetSemantics {} else androidx.compose.ui.Modifier
            ) {
                HioneLauncherScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        com.hione.launcher.ui.HioneLaunchSource.onHomeReturn()
        com.hione.launcher.ui.HioneAmbient.touch()
        pokeAmbientBrightnessService()
    }

    // A14 stamps while-in-use camera eligibility per service-start: a
    // BOOT_COMPLETED start leaves the ambient-light service unable to open the
    // camera forever. A start from the TOP app (us, the home screen) IS
    // eligible, so poke it on resume; its onStartCommand retries the
    // camera-type foreground upgrade. Throttled - resume fires constantly.
    private var lastAmbientPokeMs = 0L
    private fun pokeAmbientBrightnessService() {
        val now = System.currentTimeMillis()
        if (now - lastAmbientPokeMs < 60_000) return
        lastAmbientPokeMs = now
        try {
            startForegroundService(android.content.Intent().setClassName(
                "com.m500.hardware", "com.m500.hardware.AmbientBrightnessService"))
        } catch (_: Throwable) { /* app absent or start refused - fine */ }
    }
    // Any touch (re)arms the ambient-animation attention window (see ui/HioneAmbient.kt).
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.actionMasked == android.view.MotionEvent.ACTION_DOWN) com.hione.launcher.ui.HioneAmbient.touch()
        return super.dispatchTouchEvent(ev)
    }
    // Power: pollers/timers park while another app is in front (Compose's frame clock already
    // pauses animations at ON_STOP); see HionePowerProfile.awaitVisible().
    override fun onStart() {
        super.onStart()
        com.hione.launcher.ui.HionePowerProfile.setLauncherVisible(true)
    }
    override fun onStop() {
        com.hione.launcher.ui.HionePowerProfile.setLauncherVisible(false)
        super.onStop()
    }

    // Delegating to the dispatcher IS the recommended replacement for super.onBackPressed();
    // lint's MissingSuperCall doesn't recognize the delegation as equivalent.
    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        onBackPressedDispatcher.onBackPressed()
    }

    // When Hione Music is backgrounded, the LAUNCHER is the foreground window, so the hardware
    // transport keys land here first. The framework's fall-through to the media session proved
    // unreliable (the physical Play/Pause/Next/Prev did nothing unless the music app was focused),
    // so forward media keys explicitly to the active session via AudioManager and consume them —
    // same fix as the lockscreen. Screen-off (no foreground activity) is still the framework's job.
    private fun isTransportKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_HEADSETHOOK -> true
        else -> false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTransportKey(event.keyCode)) {
            runCatching {
                (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).dispatchMediaKeyEvent(event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        hideSystemBars()
        if (intent.getBooleanExtra("open_recents", false)) {
            requestedRecentsOpen = true
        }
        if (intent.getBooleanExtra("open_ingest", false)) {
            requestedIngestOpen = true
        }
        if (intent.getBooleanExtra("open_bpm", false)) {
            requestedBpmOpen = true
        }
        if (intent.getBooleanExtra("open_battery", false)) {
            requestedBatteryOpen = true
        }
        // Pixel behaviour: the home gesture while the app drawer / recents are open closes them.
        if (intent.hasCategory(Intent.CATEGORY_HOME) && !intent.getBooleanExtra("open_recents", false)) {
            requestedHome = true
        }
    }
}

/** Default quilt patch order (persisted user order is reconciled against this list). */
val HioneQuiltBadgeIds = listOf("clock", "wxtile", "network", "nowplaying", "bpmgame", "dac", "ingest", "library", "brain", "thermal", "volume", "battery", "control")

@Composable
fun HioneLauncherScreen() {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs: SharedPreferences = remember {
        ctx.getSharedPreferences("hione_launcher_prefs", Context.MODE_PRIVATE)
    }

    var isAllAppsOpen by remember { mutableStateOf(false) }
    var isRecentsOpen by remember { mutableStateOf(false) }
    var isWallpaperPickerOpen by remember { mutableStateOf(false) }
    var isMonitorModalOpen by remember { mutableStateOf(false) }
    var isBrainModalOpen by remember { mutableStateOf(false) }
    var isCyberQuickSettingsOpen by remember { mutableStateOf(false) }
    var isWeatherObservatoryOpen by remember { mutableStateOf(false) }
    var isGpsModalOpen by remember { mutableStateOf(false) }
    var isBatteryObservatoryOpen by remember { mutableStateOf(false) }
    var isNetworkObservatoryOpen by remember { mutableStateOf(false) }
    var isFsIngestModalOpen by remember { mutableStateOf(false) }
    var isDesktopContextMenuOpen by remember { mutableStateOf(false) }
    // TOP BAR + QUILT (user correction 2026-08-25): a thin standard status bar on top, and the
    // badge quilt directly under it. Quilt order/geometry are user-editable and persisted.
    var topBarTheme by remember { mutableStateOf(HioneTopBarTheme.load(ctx)) }
    var quiltConfig by remember { mutableStateOf(HioneQuiltPrefs.loadConfig(ctx)) }
    var quiltOrder by remember { mutableStateOf(HioneQuiltPrefs.loadOrder(ctx, HioneQuiltBadgeIds)) }
    var isQuiltOptionsOpen by remember { mutableStateOf(false) }
    // HioneDeck (ui/HioneDeck.kt): the card above can live in a pull-up sheet over the dock
    // instead, so the whole wallpaper shows. Layout comes from Settings.Global hione_deck_mode.
    val hioneDeck = com.hione.launcher.ui.rememberHioneDeckState()
    val deckConfig by com.hione.launcher.ui.rememberHioneDeckConfig()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var chibiReaction by remember { mutableStateOf("🎵") }
    var allApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var recentTasks by remember { mutableStateOf<List<RecentTaskItem>>(emptyList()) }

    LaunchedEffect(HioneLauncherActivity.requestedRecentsOpen) {
        if (HioneLauncherActivity.requestedRecentsOpen) {
            recentTasks = loadRecentTasks(ctx, allApps)
            isRecentsOpen = true
            HioneLauncherActivity.requestedRecentsOpen = false
        }
    }

    LaunchedEffect(HioneLauncherActivity.requestedIngestOpen) {
        if (HioneLauncherActivity.requestedIngestOpen) {
            isFsIngestModalOpen = true
            HioneLauncherActivity.requestedIngestOpen = false
        }
    }

    LaunchedEffect(HioneLauncherActivity.requestedBatteryOpen) {
        if (HioneLauncherActivity.requestedBatteryOpen) {
            isBatteryObservatoryOpen = true
            HioneLauncherActivity.requestedBatteryOpen = false
        }
    }

    LaunchedEffect(HioneLauncherActivity.requestedHome) {
        if (HioneLauncherActivity.requestedHome) {
            isAllAppsOpen = false
            isRecentsOpen = false
            HioneLauncherActivity.requestedHome = false
        }
    }

    // Intercept Back Press unconditionally so system status bar is NEVER un-hidden or exposed by Android OS
    BackHandler(enabled = true) {
        if (isDesktopContextMenuOpen) isDesktopContextMenuOpen = false
        else if (isFsIngestModalOpen) isFsIngestModalOpen = false
        else if (isWeatherObservatoryOpen) isWeatherObservatoryOpen = false
        else if (isGpsModalOpen) isGpsModalOpen = false
        else if (isBatteryObservatoryOpen) isBatteryObservatoryOpen = false
        else if (isNetworkObservatoryOpen) isNetworkObservatoryOpen = false
        else if (isCyberQuickSettingsOpen) isCyberQuickSettingsOpen = false
        else if (isBrainModalOpen) isBrainModalOpen = false
        else if (isMonitorModalOpen) isMonitorModalOpen = false
        else if (isAllAppsOpen) isAllAppsOpen = false
        else if (isRecentsOpen) isRecentsOpen = false
        else if (isWallpaperPickerOpen) isWallpaperPickerOpen = false
        else {
            // Bare launcher root: put the pulled-up card away, else absorb the back gesture.
            if (hioneDeck.shown && deckConfig.mode.hidesCard) hioneDeck.close()
        }
    }

    // HioneOS theming engine: restore the persisted theme once, and expose the
    // built-in themes (Default/Halloween/Beach/Cyber Stage/Cozy Cafe) at the TOP
    // of the wallpaper picker so the theme engine is visible + selectable. Each
    // theme resolves its wallpaper drawable by name at runtime (falls back to the
    // default beach art when a theme's dedicated art isn't wired in yet).
    remember { com.hione.launcher.theme.HioneThemeRegistry.init(ctx); true }
    val wallpapers = remember {
        com.hione.launcher.theme.HioneThemeRegistry.builtIns.map { t ->
            val res = com.hione.launcher.theme.HioneThemeRegistry.resolveDrawableRes(ctx, t.wallpaperDrawableName)
            HioneWallpaperTheme(
                "theme_${t.id}",
                (if (t.hasRealArt) "✦ " else "◇ ") + t.displayName,
                if (res != 0) res else R.drawable.hione_wallpaper
            )
        } + listOf(
            HioneWallpaperTheme("classic", "Concert Stage (Default)", R.drawable.hione_wallpaper),
            HioneWallpaperTheme("w0", "Cyan Glow Neon", R.drawable.wall_paper_0),
            HioneWallpaperTheme("w1", "Electronic Diva", R.drawable.wall_paper_1),
            HioneWallpaperTheme("w2", "Matrix Circuit", R.drawable.wall_paper_2),
            HioneWallpaperTheme("w3", "Tokyo Night Shibuya", R.drawable.wall_paper_3),
            HioneWallpaperTheme("w4", "Chibi Meadow", R.drawable.wall_paper_4),
            HioneWallpaperTheme("w5", "Pastel Sky", R.drawable.wall_paper_5),
            HioneWallpaperTheme("w6", "Neon Wave", R.drawable.wall_paper_6),
            HioneWallpaperTheme("w7", "Cyber Space Hologram", R.drawable.wall_paper_7),
            HioneWallpaperTheme("w8", "Classic Retro Stage", R.drawable.wall_paper_8),
            HioneWallpaperTheme("w9", "Chibi Heart Jam", R.drawable.wall_paper_9)
        )
    }

    var currentWallpaperId by remember {
        mutableStateOf(prefs.getString("current_wallpaper_id", "classic") ?: "classic")
    }

    val currentWallpaperRes = remember(currentWallpaperId) {
        wallpapers.firstOrNull { it.id == currentWallpaperId }?.resId ?: R.drawable.hione_wallpaper
    }

    // Holiday and season art (holiday/HolidayState): replaces the wallpaper only while the
    // choice is the default one and no gallery picture is set.
    remember { com.hione.launcher.holiday.HolidayState.start(ctx); true }
    val seasonalWallpaper = com.hione.launcher.holiday.HolidayState.homeBitmap.value
        ?.takeIf { currentWallpaperId in com.hione.launcher.holiday.HolidayState.DEFAULT_WALLPAPER_IDS }
    // Focus points (holiday/WallpaperFocus): frame her face into the gap under the widget card.
    val seasonalFocus = com.hione.launcher.holiday.HolidayState.homeFocus.value
    val wallpaperFocusTable = com.hione.launcher.holiday.HolidayState.focusTable.value
    val wallpaperPackId = Mascot.pack.id
    // The pack's replacement of the picked drawable, only when the pack has a focus point for it.
    // Everything else keeps the plain mascotPainter center crop.
    val focusedPackWallpaper = remember(wallpaperPackId, currentWallpaperRes, wallpaperFocusTable) {
        if (wallpaperPackId == "hione" || wallpaperFocusTable.isEmpty()) null
        else runCatching {
            val name = ctx.resources.getResourceEntryName(currentWallpaperRes)
            val focus = com.hione.launcher.holiday.WallpaperFocus.lookup(wallpaperFocusTable, "drawable:$name")
            val bmp = if (focus != null) Mascot.artFor(ctx, currentWallpaperRes) else null
            if (focus != null && bmp != null) bmp.asImageBitmap() to focus else null
        }.getOrNull()
    }

    // The face target follows the card: under it (top), in the open screen (pull-up), above it (pinned).
    val deckWallTarget = com.hione.launcher.ui.HioneDeck.wallpaperTarget(deckConfig.mode)

    var isOnboardingOpen by remember {
        val prefsCompleted = prefs.getBoolean("hione_onboarding_completed", false)
        val globalCompleted = runCatching {
            android.provider.Settings.Global.getInt(ctx.contentResolver, "hione_onboarding_completed", 0) == 1
        }.getOrDefault(false)
        mutableStateOf(!prefsCompleted && !globalCompleted)
    }

    var isProfileModalOpen by remember { mutableStateOf(false) }
    var isArtworkPeekActive by remember { mutableStateOf(false) }

    var isBpmObservatoryOpen by remember { mutableStateOf(false) }

    LaunchedEffect(HioneLauncherActivity.requestedBpmOpen) {
        if (HioneLauncherActivity.requestedBpmOpen) {
            isBpmObservatoryOpen = true
            HioneLauncherActivity.requestedBpmOpen = false
        }
    }
    var isThermalObservatoryOpen by remember { mutableStateOf(false) }

    var customWallpaperUri by remember {
        mutableStateOf(prefs.getString("custom_wallpaper_uri", null))
    }

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) {}
            customWallpaperUri = uri.toString()
            prefs.edit().putString("custom_wallpaper_uri", uri.toString()).apply()
        }
    }

    val customWallpaperBitmap = remember(customWallpaperUri) {
        if (customWallpaperUri != null) {
            try {
                val uri = Uri.parse(customWallpaperUri)
                val stream = ctx.contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(stream)
                stream?.close()
                bmp?.asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        } else null
    }

    // Seeded from the sticky battery broadcast synchronously: the badge's first frame is the real
    // level (the poll below is visibility-gated and used to leave a fake 100% up until it ran).
    val initialBatteryRead = remember {
        try {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scl = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val st = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            // -1 = unreadable. It was 0, and every consumer (home badge, shade chip, fullscreen
            // charging modal) printed a confident, critical-looking "0%" with an empty gauge.
            (if (lvl >= 0 && scl > 0) (lvl * 100) / scl else -1) to
                (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
        } catch (_: Throwable) { -1 to false }
    }
    var batteryPct by remember { mutableIntStateOf(initialBatteryRead.first) }
    var isCharging by remember { mutableStateOf(initialBatteryRead.second) }
    var isWifiConnected by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            com.hione.launcher.ui.HionePowerProfile.awaitVisible()
            try {
                val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val bIntent = ctx.registerReceiver(null, ifilter)
                val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val bStatus = bIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                isCharging = bStatus == BatteryManager.BATTERY_STATUS_CHARGING || bStatus == BatteryManager.BATTERY_STATUS_FULL
                // Unreadable broadcast keeps the last real value instead of snapping to 100.
                if (level >= 0 && scale > 0) batteryPct = (level * 100) / scale

                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val net = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(net)
                isWifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } catch (_: Throwable) {}
            delay(com.hione.launcher.ui.HionePowerProfile.refreshMs(2500L))
        }
    }

    // App Context Dialog State (Long-press on any app)
    var contextMenuApp by remember { mutableStateOf<InstalledApp?>(null) }

    // Desktop Pinned Favorites Persistence (Hione Music lives on the Diva Dock).
    // Default desktop starts EMPTY — no pre-pinned apps (avoids phantom slots for
    // apps that aren't installed). The bottom Diva Dock keeps its icons; the center
    // Hione Music hero is fixed/permanent. Users can pin apps themselves later.
    val defaultDesktopFavs = emptySet<String>()
    var pinnedPackages by remember {
        mutableStateOf(
            prefs.getStringSet("hione_desktop_pinned", defaultDesktopFavs)?.toSet() ?: defaultDesktopFavs
        )
    }
    val onTogglePinDesktop = { pkg: String ->
        val updated = pinnedPackages.toMutableSet()
        if (updated.contains(pkg)) {
            updated.remove(pkg)
        } else {
            updated.add(pkg)
        }
        prefs.edit().putStringSet("hione_desktop_pinned", updated).apply()
        pinnedPackages = updated
    }

    // Interactive Pixel Gesture Nav Pill Dragging State

    // System-Wide USB Host Connected Modal State
    var showUsbHostModal by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        com.hione.launcher.usb.HioneUsbAudioHostService.usbConnectedEvents.collect {
            showUsbHostModal = it
        }
    }

    // Horizontal Pager: Page 0 = Main Desktop, Page 1 = Hardware & DAC Telemetry Widgets (Only created when needed)
    val hasExtraPage = remember(pinnedPackages.size) {
        pinnedPackages.size > 12 || prefs.getBoolean("enable_widget_page", false)
    }
    val pagerState = rememberPagerState(pageCount = { if (hasExtraPage) 2 else 1 })

    // Live Clock & Date
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.US)
        while (true) {
            com.hione.launcher.ui.HionePowerProfile.awaitVisible()
            val now = Date()
            currentTime = timeFormat.format(now)
            currentDate = dateFormat.format(now)
            delay(1000L)
        }
    }

    // Query and map all installed apps to official Hione icons (Comprehensive Discovery)
    val loadAllApps = {
        val pm = ctx.packageManager
        val seenPackages = mutableSetOf<String>()
        val list = mutableListOf<InstalledApp>()
        val essentialPackages = setOf(
            "net.sourceforge.opencamera",
            "com.android.camera2",
            "com.android.camera",
            "com.android.gallery3d",
            "com.google.android.apps.photos",
            "com.android.documentsui",
            "com.android.settings",
            "com.hione.player",
            "com.spotify.music",
            "org.mozilla.firefox",
            "com.android.chrome",
            "com.plexapp.android",
            "com.topjohnwu.magisk",
            "org.fossify.clock",
            "org.fossify.calendar",
            "org.fossify.math"
        )

        val blacklistedPackages = setOf(
            "com.android.settings",
            "com.android.gallery3d",
            "com.android.musicfx",
            "com.hiby.update",
            "com.android.fallback",
            "com.hione.systemui"
        )

        // 1. Query standard launcher apps
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherInfos = pm.queryIntentActivities(mainIntent, 0)
        val realFmInstalled = isRealHioneFmInstalled(ctx)
        for (info in launcherInfos) {
            val pkg = info.activityInfo.packageName
            val activityName = info.activityInfo.name
            if (pkg == ctx.packageName && activityName == HioneLauncherActivity::class.java.name) continue
            // The in-app FM tab can't tune (SELinux); hide it whenever the real com.caf.fmradio is present.
            if (realFmInstalled && pkg == "com.hione.player" && activityName.contains("radio", ignoreCase = true)) continue
            if (blacklistedPackages.contains(pkg)) continue
            val rawLabel = info.loadLabel(pm).toString()
            val label = resolveAppDisplayLabel(pkg, activityName, rawLabel)
            val systemIcon = try { info.loadIcon(pm) } catch (_: Throwable) { null }
            val category = resolveAppCategory(pkg, label)
            val isSys = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && !essentialPackages.contains(pkg)
            } catch (_: Throwable) { false }

            seenPackages.add(pkg)
            list.add(
                InstalledApp(
                    label = label,
                    packageName = pkg,
                    activityName = activityName,
                    iconResId = resolveCustomAppIcon(pkg, label, activityName),
                    systemIcon = systemIcon,
                    category = category,
                    isSystemApp = isSys
                )
            )
        }

        // 2. Query Leanback launcher apps (TV/DAP interfaces)
        val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        val leanbackInfos = pm.queryIntentActivities(leanbackIntent, 0)
        for (info in leanbackInfos) {
            val pkg = info.activityInfo.packageName
            if (seenPackages.contains(pkg)) continue
            if (blacklistedPackages.contains(pkg)) continue
            val activityName = info.activityInfo.name
            val rawLabel = info.loadLabel(pm).toString()
            val label = resolveAppDisplayLabel(pkg, activityName, rawLabel)
            val systemIcon = try { info.loadIcon(pm) } catch (_: Throwable) { null }
            val category = resolveAppCategory(pkg, label)
            val isSys = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && !essentialPackages.contains(pkg)
            } catch (_: Throwable) { false }

            seenPackages.add(pkg)
            list.add(
                InstalledApp(
                    label = label,
                    packageName = pkg,
                    activityName = activityName,
                    iconResId = resolveCustomAppIcon(pkg, label, activityName),
                    systemIcon = systemIcon,
                    category = category,
                    isSystemApp = isSys
                )
            )
        }

        // 3. Scan all installed packages with launch intents
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (appInfo in installedApps) {
            val pkg = appInfo.packageName
            if (seenPackages.contains(pkg)) continue
            if (pkg == ctx.packageName) continue
            if (blacklistedPackages.contains(pkg)) continue
            val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: continue
            val rawLabel = pm.getApplicationLabel(appInfo).toString()
            val activityName = launchIntent.component?.className ?: ""
            val label = resolveAppDisplayLabel(pkg, activityName, rawLabel)
            val systemIcon = try { appInfo.loadIcon(pm) } catch (_: Throwable) { null }
            val category = resolveAppCategory(pkg, label)
            val isSys = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && !essentialPackages.contains(pkg)

            seenPackages.add(pkg)
            list.add(
                InstalledApp(
                    label = label,
                    packageName = pkg,
                    activityName = activityName,
                    iconResId = resolveCustomAppIcon(pkg, label, activityName),
                    systemIcon = systemIcon,
                    category = category,
                    isSystemApp = isSys
                )
            )
        }

        // 4. Ensure Hione Suite is explicitly registered
        val hioneSuite = listOf(
            InstalledApp(
                label = "${Mascot.musicAppName}",
                packageName = "com.hione.player",
                activityName = "com.hione.player.MainActivity",
                iconResId = R.drawable.ic_hione_music_brand,
                systemIcon = null,
                category = "Media",
                isSystemApp = false
            ),
            InstalledApp(
                label = "${Mascot.name} Settings",
                packageName = "com.hione.settings",
                activityName = "com.hione.settings.HioneSettingsActivity",
                iconResId = R.drawable.ic_settings_hione,
                systemIcon = null,
                category = "System",
                isSystemApp = false
            )
        )
        for (app in hioneSuite) {
            if (list.none { it.packageName == app.packageName }) {
                list.add(app)
            }
        }

        // Disambiguate identical labels (e.g. Camera Go + Snapdragon Camera both say "Camera").
        val dupes = list.groupBy { it.label.lowercase() }.filterValues { it.size > 1 }.keys
        val deduped = list.map { app ->
            if (app.label.lowercase() !in dupes) app else app.copy(label = app.label + " " + when {
                app.packageName.contains("cameralite") -> "Go"
                app.packageName.contains("snap", ignoreCase = true) -> "SD"
                app.packageName.startsWith("com.google") -> "G"
                else -> app.packageName.substringAfterLast('.').take(4).replaceFirstChar { it.uppercase() }
            })
        }
        allApps = deduped.sortedBy { it.label.lowercase() }
    }

    // Keyed on the mascot pack: app names in the drawer and dock come from the active pack.
    LaunchedEffect(Mascot.id) {
        withContext(Dispatchers.IO) {
            try {
                loadAllApps()
            } catch (_: Throwable) {}
        }
    }

    LaunchedEffect(isAllAppsOpen) {
        if (isAllAppsOpen) {
            withContext(Dispatchers.IO) {
                try {
                    loadAllApps()
                } catch (_: Throwable) {}
            }
        }
    }

    // Dynamic CPU & Battery Thermal Telemetry Poller. 0 = not read yet / sensor unreadable; every
    // consumer renders that as "—" instead of a presumed room-temperature figure.
    var cpuTempC by remember { mutableStateOf(0f) }
    var batteryTempC by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            com.hione.launcher.ui.HionePowerProfile.awaitVisible()
            withContext(Dispatchers.IO) {
                var cTemp = 0f
                val thermalPaths = listOf(
                    "/sys/class/thermal/thermal_zone0/temp",
                    "/sys/class/thermal/thermal_zone1/temp",
                    "/sys/devices/virtual/thermal/thermal_zone0/temp"
                )
                for (p in thermalPaths) {
                    try {
                        val file = java.io.File(p)
                        if (file.exists() && file.canRead()) {
                            val raw = file.readText().trim().toFloatOrNull() ?: 0f
                            val deg = if (raw > 1000f) raw / 1000f else raw
                            if (deg in 15f..115f) {
                                cTemp = deg
                                break
                            }
                        }
                    } catch (_: Throwable) {}
                }

                var bTemp = 0f
                try {
                    val bIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val rawB = bIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                    if (rawB > 0) {
                        bTemp = rawB / 10f
                    }
                } catch (_: Throwable) {}

                // No cross-derivation: an unreadable sensor stays 0 and is shown as "—".
                cpuTempC = cTemp
                batteryTempC = bTemp
            }
            delay(com.hione.launcher.ui.HionePowerProfile.refreshMs(4000L))
        }
    }

    // Now Playing Telemetry from PlayerHolder & HioneBpmEngine
    val snapshot = remember { mutableStateOf(PlayerHolder.snapshot()) }
    var isSystemMusicActive by remember { mutableStateOf(false) }
    val audioManager = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var isHardwareAudioActive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            com.hione.launcher.ui.HionePowerProfile.awaitVisible()
            snapshot.value = PlayerHolder.snapshot()
            val npBpmPlaying = com.hione.launcher.bpm.HioneBpmEngine.state.value.isPlaying
            val globalPlaying = try { android.provider.Settings.Global.getInt(ctx.contentResolver, "hione_is_playing", 0) == 1 } catch (_: Throwable) { false }
            val playerHolderPlaying = snapshot.value?.isPlaying == true
            val amActive = audioManager?.isMusicActive == true
            isSystemMusicActive = playerHolderPlaying || npBpmPlaying || globalPlaying || amActive
            isHardwareAudioActive = amActive || playerHolderPlaying || (npBpmPlaying && globalPlaying)
            delay(com.hione.launcher.ui.HionePowerProfile.refreshMs(500L))
        }
    }
    val hasActiveAudioOutput = isSystemMusicActive && isHardwareAudioActive
    val isAudioPlaying = hasActiveAudioOutput
    // Album-art accent (Hione Music → Settings.Global hione_np_accent*) blended subtly into the OS chrome.
    val npAccent = com.hione.launcher.ui.rememberNpAccent()
    // Pixel-style drawer sheet physics (finger-follow + spring settle); see ui/HioneDrawerSheet.kt.
    val drawerSheet = com.hione.launcher.ui.rememberDrawerSheetState()
    // Open drawer covers home → ambient home animators freeze (HioneAmbient).
    androidx.compose.runtime.LaunchedEffect(drawerSheet) {
        var covering = false
        androidx.compose.runtime.snapshotFlow { drawerSheet.progress.value > 0.02f }.collect { c ->
            if (c && !covering) { covering = true; com.hione.launcher.ui.HioneAmbient.pushCovered() }
            else if (!c && covering) { covering = false; com.hione.launcher.ui.HioneAmbient.popCovered() }
        }
    }
    val drawerHeightPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    drawerSheet.heightPx = drawerHeightPx
    LaunchedEffect(isAllAppsOpen) { if (isAllAppsOpen) drawerSheet.open() else if (!drawerSheet.dragging) drawerSheet.close() }

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        // High-Resolution Official Desktop Wallpaper (Custom Gallery or Hione Artwork)
        if (customWallpaperBitmap != null) {
            Image(
                bitmap = customWallpaperBitmap,
                contentDescription = "Wallpaper",
                modifier = Modifier.fillMaxSize().graphicsLayer(),
                contentScale = ContentScale.Crop
            )
        } else if (seasonalWallpaper != null) {
            com.hione.launcher.holiday.FocusedWallpaper(
                bitmap = seasonalWallpaper,
                focus = seasonalFocus,
                target = deckWallTarget,
                contentDescription = "Wallpaper",
                modifier = Modifier.fillMaxSize().graphicsLayer()
            )
        } else if (focusedPackWallpaper != null) {
            com.hione.launcher.holiday.FocusedWallpaper(
                bitmap = focusedPackWallpaper.first,
                focus = focusedPackWallpaper.second,
                target = deckWallTarget,
                contentDescription = "Wallpaper",
                modifier = Modifier.fillMaxSize().graphicsLayer()
            )
        } else {
            Image(
                painter = mascotPainter(id = currentWallpaperRes),
                contentDescription = "Wallpaper",
                modifier = Modifier.fillMaxSize().graphicsLayer(),
                contentScale = ContentScale.Crop
            )
        }

        // Cyber Vignette Overlay (own layer: static)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0x660C0D10),
                            Color(0x22000000),
                            Mascot.color(0xCC0C0D10)
                        )
                    )
                )
        )

        // Now-playing accent tint over the wallpaper (~8 %, animated, transparent when idle).
        Box(Modifier.fillMaxSize().background(npAccent.scrim))

        // BPM-game secrets that live on the wallpaper: Negi Snow and Concert Mode. Draws nothing
        // (and runs no frame loop) unless one of them is earned and on.
        HioneSecretHomeFx(isPlaying = isAudioPlaying, accent = npAccent.full)

        var rootDragY by remember { mutableFloatStateOf(0f) }
        // The card (hearts clock, chips, weather tile, controls row). One definition, placed by the
        // HioneDeck layout: at the top, pinned above the dock, or in the pull-up sheet.
        val hioneDeckCard: @Composable () -> Unit = {
            HioneLauncherQuiltSection(
                currentTime = currentTime,
                currentDate = currentDate,
                isAudioPlaying = isAudioPlaying,
                npAccent = npAccent,
                cpuTempC = cpuTempC,
                batteryTempC = batteryTempC,
                batteryPct = batteryPct,
                isCharging = isCharging,
                topBarTheme = topBarTheme,
                quiltConfig = quiltConfig,
                quiltOrder = quiltOrder,
                onQuiltOrderChange = { quiltOrder = it; HioneQuiltPrefs.saveOrder(ctx, it) },
                onOpenWeatherObservatory = { isWeatherObservatoryOpen = true },
                onOpenGps = { isGpsModalOpen = true },
                onOpenNetworkObservatory = { isNetworkObservatoryOpen = true },
                onOpenBpmObservatory = { isBpmObservatoryOpen = true },
                onOpenFsIngest = { isFsIngestModalOpen = true },
                onOpenBrain = { isBrainModalOpen = true },
                onOpenThermal = { isThermalObservatoryOpen = true },
                onOpenBattery = { isBatteryObservatoryOpen = true },
                onOpenQuickSettings = { isCyberQuickSettingsOpen = true }
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // ============================================================
            // TOP BAR — thin, standard-layout Android status bar (clock left, glyphs right).
            // NOT a card. The 24dp strip above it belongs to the system gesture layer (shade pull).
            // ============================================================
            val statusBarState = com.hione.launcher.ui.rememberHioneStatusBarState(
                clock = currentTime, isPlaying = isAudioPlaying, thermalC = cpuTempC
            )
            Box(Modifier.fillMaxWidth().graphicsLayer()) {
                val trayApps = com.hione.launcher.ui.rememberNotificationTray()
                com.hione.launcher.ui.HioneStatusBar(
                    state = statusBarState,
                    onClockClick = { launchClockApp(ctx) },
                    notificationApps = trayApps,
                    onOpenShade = { expandNotificationShade(ctx) }
                )
            }
            if (isArtworkPeekActive) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Mascot.color(0x44000000))
                        .border(1.dp, HioneCyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .clickable { isArtworkPeekActive = false }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("ARTWORK PEEK ACTIVE · TAP TO EXPAND WIDGETS", color = HioneCyan, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                }
            } else if (deckConfig.mode == com.hione.launcher.ui.HioneDeckMode.TOP) {
                hioneDeckCard()
            }

            // ============================================================
            // PAGINATED DESKTOP WORKSPACE
            // ============================================================
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .graphicsLayer()
                    .homeVerticalSwipe(
                        onSwipeUp = { isAllAppsOpen = true },
                        onSwipeDown = { expandNotificationShade(ctx) },
                        onDragUp = { dy -> drawerSheet.dragBy(dy) },
                        onDragUpEnd = { vy -> if (drawerSheet.release(vy)) isAllAppsOpen = true }
                    )
            ) { page ->
                when (page) {
                    0 -> MainDesktopPage(
                        apps = allApps,
                        pinnedPackages = pinnedPackages,
                        snapshot = snapshot.value,
                        chibiReaction = chibiReaction,
                        onChibiTap = {
                            val reactions = listOf("🎵", "💙", "⚡", "✨", "🎤", "🎧", "🌸", "⭐")
                            chibiReaction = reactions.random()
                        },
                        onLaunchApp = { launchApp(ctx, it) },
                        onAppLongClick = { contextMenuApp = it },
                        onOpenWallpapers = { isDesktopContextMenuOpen = true },
                        onSwipeUp = { isAllAppsOpen = true },
                        onSwipeDown = { expandNotificationShade(ctx) }
                    )
                    1 -> HardwareWidgetsPage(
                        onOpenAudioSettings = {
                            val intent = ctx.packageManager.getLaunchIntentForPackage("com.hione.settings")?.apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            if (intent != null) ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
                        },

                        onOpenFnSettings = {
                            val intent = ctx.packageManager.getLaunchIntentForPackage("com.hione.settings")?.apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            if (intent != null) ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
                        },
                        onOpenMonitor = { }
                    )
                }
            }



            com.hione.launcher.ui.HioneDeckDockSlot(hioneDeck, deckConfig.mode, hioneDeckCard)
            HioneLauncherDivaDock(
                hasActiveAudioOutput = hasActiveAudioOutput,
                isAudioPlaying = isAudioPlaying,
                npAccent = npAccent,
                onOpenAllApps = { isAllAppsOpen = true },
                onOpenHardware = { isBrainModalOpen = true },
                onOpenWeather = { isWeatherObservatoryOpen = true }
            )
            // Clearance for the OS-wide gesture pill strip (24dp) + breathing room
            Spacer(Modifier.height(28.dp))
        }

        // The pull-up sheet (pull-up layouts only). Over the workspace, never over the dock.
        com.hione.launcher.ui.HioneDeckOverlay(
            state = hioneDeck,
            config = deckConfig,
            drawerOpen = { drawerSheet.progress.value > 0.02f },
            onOpenDrawer = { isAllAppsOpen = true },
            content = hioneDeckCard
        )

        HioneLauncherShadeAndWallpaperModals(
            isCyberQuickSettingsOpen = isCyberQuickSettingsOpen,
            onCloseQuickSettings = { isCyberQuickSettingsOpen = false },
            batteryPct = batteryPct,
            isCharging = isCharging,
            isWifiConnected = isWifiConnected,
            currentTime = currentTime,
            currentDate = currentDate,
            onOpenProfile = {
                isCyberQuickSettingsOpen = false
                isProfileModalOpen = true
            },
            showUsbHostModal = showUsbHostModal,
            onDismissUsbHostModal = { showUsbHostModal = false },
            isWallpaperPickerOpen = isWallpaperPickerOpen,
            onCloseWallpaperPicker = { isWallpaperPickerOpen = false },
            onPickFromGallery = { galleryPicker.launch("image/*") },
            customWallpaperUri = customWallpaperUri,
            onCustomWallpaperUriChange = { customWallpaperUri = it },
            onWallpaperIdChange = { currentWallpaperId = it },
            prefs = prefs,
            wallpapers = wallpapers,
            currentWallpaperRes = currentWallpaperRes
        )



        HioneLauncherDrawerAndRecents(
            drawerSheet = drawerSheet,
            drawerHeightPx = drawerHeightPx,
            allApps = allApps,
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            selectedCategory = selectedCategory,
            onCategoryChange = { selectedCategory = it },
            onCloseAllApps = { isAllAppsOpen = false },
            onOpenQuickSettings = {
                isAllAppsOpen = false
                isCyberQuickSettingsOpen = true
            },
            contextMenuApp = contextMenuApp,
            onContextMenuAppChange = { contextMenuApp = it },
            isRecentsOpen = isRecentsOpen,
            onCloseRecents = { isRecentsOpen = false },
            recentTasks = recentTasks,
            onRecentTasksChange = { recentTasks = it },
            pinnedPackages = pinnedPackages,
            onTogglePinDesktop = onTogglePinDesktop
        )

        HioneLauncherObservatoryModals(
            quiltConfig = quiltConfig,
            onQuiltConfigChange = { quiltConfig = it; HioneQuiltPrefs.saveConfig(ctx, it) },
            topBarTheme = topBarTheme,
            onCycleQuiltBackground = {
                val next = topBarTheme.next(ctx)
                topBarTheme = next
                HioneTopBarTheme.save(ctx, next)
            },
            onResetQuiltOrder = {
                HioneQuiltPrefs.resetOrder(ctx)
                quiltOrder = HioneQuiltPrefs.loadOrder(ctx, HioneQuiltBadgeIds)
            },
            isQuiltOptionsOpen = isQuiltOptionsOpen,
            onOpenQuiltOptions = { isQuiltOptionsOpen = true },
            onCloseQuiltOptions = { isQuiltOptionsOpen = false },
            isDesktopContextMenuOpen = isDesktopContextMenuOpen,
            onCloseDesktopContextMenu = { isDesktopContextMenuOpen = false },
            onOpenWallpaperPicker = { isWallpaperPickerOpen = true },
            coroutineScope = coroutineScope,
            pagerState = pagerState,
            isRecentsOpen = isRecentsOpen,
            onCloseRecents = { isRecentsOpen = false },
            recentTasks = recentTasks,
            onRecentTasksChange = { recentTasks = it },
            isWeatherObservatoryOpen = isWeatherObservatoryOpen,
            onCloseWeatherObservatory = { isWeatherObservatoryOpen = false },
            isGpsModalOpen = isGpsModalOpen,
            onCloseGps = { isGpsModalOpen = false },
            isBatteryObservatoryOpen = isBatteryObservatoryOpen,
            onCloseBatteryObservatory = { isBatteryObservatoryOpen = false },
            isNetworkObservatoryOpen = isNetworkObservatoryOpen,
            onCloseNetworkObservatory = { isNetworkObservatoryOpen = false },
            isBrainModalOpen = isBrainModalOpen,
            onCloseBrain = { isBrainModalOpen = false },
            isBpmObservatoryOpen = isBpmObservatoryOpen,
            onCloseBpmObservatory = { isBpmObservatoryOpen = false },
            isFsIngestModalOpen = isFsIngestModalOpen,
            onCloseFsIngest = { isFsIngestModalOpen = false },
            isThermalObservatoryOpen = isThermalObservatoryOpen,
            onCloseThermal = { isThermalObservatoryOpen = false },
            cpuTempC = cpuTempC,
            batteryTempC = batteryTempC,
            batteryPct = batteryPct,
            isCharging = isCharging,
            isOnboardingOpen = isOnboardingOpen,
            onFinishOnboarding = {
                prefs.edit().putBoolean("hione_onboarding_completed", true).apply()
                runCatching {
                    android.provider.Settings.Global.putInt(ctx.contentResolver, "hione_onboarding_completed", 1)
                }
                isOnboardingOpen = false
            },
            isProfileModalOpen = isProfileModalOpen,
            onCloseProfile = { isProfileModalOpen = false },
            onOpenProfile = { isProfileModalOpen = true },
            onToggleArtworkPeek = { isArtworkPeekActive = !isArtworkPeekActive },
            prefs = prefs
        )
    }
}

@Composable
fun MainDesktopPage(
    apps: List<InstalledApp>,
    pinnedPackages: Set<String>,
    snapshot: PlayerHolder.PlayerSnapshot?,
    chibiReaction: String,
    onChibiTap: () -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onAppLongClick: (InstalledApp) -> Unit,
    onOpenWallpapers: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit
) {
    val ctx = LocalContext.current
    val desktopApps = apps.filter { pinnedPackages.contains(it.packageName) }

    Box(
        Modifier
            .fillMaxSize()
            .homeVerticalSwipe(onSwipeUp = onSwipeUp, onSwipeDown = onSwipeDown)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onOpenWallpapers() })
            }
            .padding(horizontal = 16.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(8.dp))

            // Desktop Favorites Grid (Non-scrolling so gestures pass seamlessly)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                userScrollEnabled = false,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(desktopApps) { app ->
                    DesktopAppIconItem(
                        app = app,
                        onClick = { onLaunchApp(app) },
                        onLongClick = { onAppLongClick(app) }
                    )
                }
            }
        }
    }
}

@Composable
fun HardwareWidgetsPage(
    onOpenAudioSettings: () -> Unit,
    onOpenFnSettings: () -> Unit,
    onOpenMonitor: () -> Unit
) {
    val hwCtx = LocalContext.current
    // Every value on these cards is READ at composition from a real source; anything that cannot
    // be read renders "—" / "unavailable". Nothing here is a literal pretending to be measured.
    val hwFilter = remember { runCatching { CirrusLogicManager.getDigitalFilterOrNull(hwCtx) }.getOrNull() }
    val hwGain = remember { runCatching { CirrusLogicManager.getGainModeOrNull(hwCtx) }.getOrNull() }
    val fnLockLabel = remember {
        try {
            val raw = android.provider.Settings.Global.getString(hwCtx.contentResolver, "button_lock")
            when (raw?.trim()) { null, "" -> "—"; "0" -> "OFF"; else -> "ON ($raw)" }
        } catch (_: Throwable) { "—" }
    }
    val launcherVersion = remember {
        runCatching { "v" + hwCtx.packageManager.getPackageInfo(hwCtx.packageName, 0).versionName }.getOrDefault("v—")
    }
    val kernelLabel = remember {
        val rel = runCatching { System.getProperty("os.version") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "—"
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "—"
        "Linux $rel · $abi"
    }
    val signingLabel = remember {
        runCatching {
            val pm = hwCtx.packageManager
            if (pm.checkSignatures(hwCtx.packageName, "android") == android.content.pm.PackageManager.SIGNATURE_MATCH)
                "Platform-signed · Native Compose" else "NOT platform-signed · Native Compose"
        }.getOrDefault("Signature check failed")
    }
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = mascotPainter(id = R.drawable.hione_bg_page1),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(Mascot.color(0xCC0C0D10))
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Hione Cyber Audio Controller Card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberGlassCard)
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                    .clickable { onOpenAudioSettings() }
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("AUDIO", color = HioneCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Icon(Icons.Default.Tune, contentDescription = null, tint = HioneCyan, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("DAC Chipset", color = HioneTextSecondary, fontSize = 11.sp)
                        Text("Cirrus Logic CS43198", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Filter & Gain", color = HioneTextSecondary, fontSize = 11.sp)
                        // Read from the DAC sysfs / vendor globals; "—" when neither is readable.
                        Text(
                            "${hwFilter?.label ?: "—"} / ${hwGain?.label ?: "—"}",
                            color = HioneCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 3. Physical Fn Switch & Pocket Guard Card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberGlassCard)
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                    .clickable { onOpenFnSettings() }
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("FN KEY AND POCKET LOCK", color = HioneCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Icon(Icons.Default.Lock, contentDescription = null, tint = HioneCyan, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Button lock", color = HioneTextSecondary, fontSize = 11.sp)
                        // Settings.Global button_lock is the framework's real lock flag; absent = "—".
                        Text(fnLockLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Pocket lock", color = HioneTextSecondary, fontSize = 11.sp)
                        Text("Set in Fn settings", color = HioneCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 4. HioneOS System Platform Card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberGlassCard)
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                    .clickable { onOpenAudioSettings() }
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SYSTEM", color = HioneCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        // Real launcher versionName from PackageManager.
                        Text(launcherVersion, color = HioneCyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Kernel", color = HioneTextSecondary, fontSize = 11.sp)
                        // uname release from the running kernel + primary ABI — never a literal.
                        Text(kernelLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Signing", color = HioneTextSecondary, fontSize = 11.sp)
                        // Verified against the "android" package's certificate at runtime.
                        Text(signingLabel, color = HioneCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Pixel-Style Recents Multi-Tasking Carousel Overview.
 */
@Composable
fun CyberRecentsOverview(
    tasks: List<RecentTaskItem>,
    onSelectTask: (RecentTaskItem) -> Unit,
    onDismissTask: (RecentTaskItem) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xF2030D14))
            .systemBarsPadding()
            .pointerInput(Unit) {
                detectTapGestures { onClose() }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with Close & Title
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(HioneCyan)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "RECENT APPS",
                        color = HioneCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 1.sp
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (tasks.isEmpty()) {
                Box(
                    Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("✨", fontSize = 28.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("No recent apps", color = HioneTextSecondary, fontSize = 12.sp, fontFamily = AudiowideFont)
                    }
                }
            } else {
                // Horizontal Carousel of High-Tech Task Cards (Swipe up to dismiss)
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(tasks, key = { "${it.taskId}_${it.packageName}" }) { task ->
                        var dismissOffset by remember { mutableFloatStateOf(0f) }
                        val animatedDismissOffset by animateFloatAsState(
                            targetValue = dismissOffset,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "CardDismiss"
                        )

                        Box(
                            Modifier
                                .width(230.dp)
                                .fillMaxHeight(0.88f)
                                .offset(y = animatedDismissOffset.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Mascot.color(0xFF09202C),
                                            Mascot.color(0xFF05121A)
                                        )
                                    )
                                )
                                .border(
                                    BorderStroke(
                                        1.2.dp,
                                        Brush.verticalGradient(
                                            listOf(
                                                HioneCyan.copy(alpha = 0.8f),
                                                CyberGlassBorder.copy(alpha = 0.3f),
                                                HioneNeonPink.copy(alpha = 0.5f)
                                            )
                                        )
                                    ),
                                    RoundedCornerShape(24.dp)
                                )
                                .pointerInput(task.taskId) {
                                    var startY = 0f
                                    detectDragGestures(
                                        onDragStart = { startY = 0f },
                                        onDragEnd = {
                                            if (dismissOffset < -100f) {
                                                onDismissTask(task)
                                            } else {
                                                dismissOffset = 0f
                                            }
                                        },
                                        onDragCancel = { dismissOffset = 0f },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            startY += dragAmount.y
                                            dismissOffset = startY.coerceAtMost(0f)
                                        }
                                    )
                                }
                                .clickable { onSelectTask(task) }
                                .padding(14.dp)
                        ) {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Task Header
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (task.iconRes != null) {
                                            Image(
                                                painter = mascotPainter(id = task.iconRes),
                                                contentDescription = null,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        } else if (task.systemIcon != null) {
                                            Image(
                                                bitmap = task.systemIcon.toBitmap(64, 64).asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = task.label,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = AudiowideFont,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = task.packageName,
                                                color = HioneTextSecondary,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { onDismissTask(task) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray, modifier = Modifier.size(14.dp))
                                    }
                                }

                                // Center App Interactive Preview Mockup
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(vertical = 10.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Mascot.color(0xFF030D12))
                                        .border(0.8.dp, CyberGlassBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (task.iconRes != null) {
                                            Image(
                                                painter = mascotPainter(id = task.iconRes),
                                                contentDescription = null,
                                                modifier = Modifier.size(54.dp)
                                            )
                                        } else if (task.systemIcon != null) {
                                            Image(
                                                bitmap = task.systemIcon.toBitmap(96, 96).asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.size(54.dp)
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "RUNNING",
                                            color = com.hione.launcher.ui.HioneIdentity.Leek,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            fontFamily = AudiowideFont,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }

                                // Bottom Hint
                                Text(
                                    "Tap to switch, swipe up to close",
                                    color = HioneTextSecondary,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Bottom "Clear All" Action Button
            if (tasks.isNotEmpty()) {
                Button(
                    onClick = onClearAll,
                    colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x336FB0FF)),
                    border = BorderStroke(1.dp, HioneNeonPink),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text("CLEAR ALL", color = HioneNeonPink, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                }
            }
        }
    }
}
@Composable
fun CyberAppContextDialog(
    app: InstalledApp,
    isPinnedOnDesktop: Boolean,
    onDismiss: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
    onTogglePinDesktop: () -> Unit
) {
    // Appear: scale 0.85 → 1 with a slight overshoot (~150 ms) + fade, like a Pixel popup.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 1100f)) }
    AlertDialog(
        modifier = Modifier.graphicsLayer {
            val sc = 0.85f + 0.15f * appear.value
            scaleX = sc; scaleY = sc
            alpha = appear.value.coerceIn(0f, 1f)
        },
        onDismissRequest = onDismiss,
        containerColor = Mascot.color(0xF504141E),
        tonalElevation = 12.dp,
        shape = CutCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.systemIcon?.let { d ->
                    val bm = remember(app.packageName) {
                        try { d.toBitmap(80, 80).asImageBitmap() } catch (_: Throwable) { null }
                    }
                    if (bm != null) {
                        Image(bitmap = bm, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                }
                Column {
                    Text(app.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    Text(app.packageName, color = HioneTextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pin / Unpin Desktop
                Button(
                    onClick = {
                        onTogglePinDesktop()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x33FFC56B)),
                    border = BorderStroke(1.dp, HioneCyan.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isPinnedOnDesktop) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd, contentDescription = null, tint = HioneCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isPinnedOnDesktop) "Remove from Desktop" else "Pin to Desktop", color = Color.White, fontSize = 11.5.sp, fontFamily = AudiowideFont)
                    }
                }

                // App Info (Settings)
                Button(
                    onClick = {
                        onAppInfo()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x22FFFFFF)),
                    border = BorderStroke(1.dp, CyberGlassBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Mascot.color(0xFFFFC56B), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("App Details", color = Color.White, fontSize = 11.5.sp, fontFamily = AudiowideFont)
                    }
                }

                // Uninstall (if not system app)
                Button(
                    onClick = {
                        onUninstall()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF1744)),
                    border = BorderStroke(1.dp, Color(0xFFFF5252)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Uninstall App", color = Color(0xFFFF5252), fontSize = 11.5.sp, fontFamily = AudiowideFont)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = HioneCyan, fontFamily = AudiowideFont)
            }
        }
    )
}

/**
 * Pixel / AOSP-style Themed 3D Cyber Desktop Context Menu Modal.
 * Prompts user for Wallpaper & style, Widgets, or Home settings.
 */
@Composable
fun CyberDesktopContextMenuModal(
    onWallpaperAndStyle: () -> Unit,
    onWidgets: () -> Unit,
    onHomeSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x9902080D))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(260.dp)
                .clip(CutCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        )
                    )
                )
                .clickable(enabled = false) {}
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(1.dp)
                    .clip(CutCornerShape(15.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Mascot.color(0xFA09222E),
                                Mascot.color(0xFF04121A),
                                Mascot.color(0xFF02090D)
                            )
                        )
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(
                                    HioneCyan.copy(alpha = 0.9f),
                                    CyberGlassBorder.copy(alpha = 0.4f),
                                    HioneNeonPink.copy(alpha = 0.7f)
                                )
                            )
                        ),
                        CutCornerShape(15.dp)
                    )
                    .padding(14.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "DESKTOP ACTIONS",
                        color = HioneCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 1.sp
                    )

                    // 1. Wallpaper & Style
                    Button(
                        onClick = {
                            onDismiss()
                            onWallpaperAndStyle()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x22FFC56B)),
                        border = BorderStroke(1.dp, HioneCyan.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Palette, contentDescription = null, tint = HioneCyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Wallpaper & style", color = Color.White, fontSize = 11.5.sp, fontFamily = AudiowideFont)
                        }
                    }

                    // 2. Widgets
                    Button(
                        onClick = {
                            onDismiss()
                            onWidgets()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x227C4DFF)),
                        border = BorderStroke(1.dp, Mascot.color(0xFFB9C2D0).copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Widgets, contentDescription = null, tint = Mascot.color(0xFFB9C2D0), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Widgets", color = Color.White, fontSize = 11.5.sp, fontFamily = AudiowideFont)
                        }
                    }

                    // 3. Home Settings
                    Button(
                        onClick = {
                            onDismiss()
                            onHomeSettings()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x226FB0FF)),
                        border = BorderStroke(1.dp, HioneNeonPink.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SettingsSuggest, contentDescription = null, tint = HioneNeonPink, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Home settings", color = Color.White, fontSize = 11.5.sp, fontFamily = AudiowideFont)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CyberAllAppsDrawer(
    apps: List<InstalledApp>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onAppLongClick: (InstalledApp) -> Unit = {},
    onOpenQuickSettings: () -> Unit = {},
    onClose: () -> Unit,
    sheet: com.hione.launcher.ui.DrawerSheetState? = null,
    revealProgress: () -> Float = { 1f }
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val categories = remember { listOf("All", "Audio", "Tools", "Media", "System") }
    val filteredApps = remember(apps, selectedCategory, searchQuery) {
        apps.filter {
            val matchesCategory = when (selectedCategory) {
                "All" -> true
                "System" -> it.isSystemApp
                else -> !it.isSystemApp && it.category.equals(selectedCategory, ignoreCase = true)
            }
            matchesCategory && (searchQuery.isEmpty() || it.label.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CyberDarkBg)
    ) {
        // Waifu Streetwear Tokyo App Drawer Artwork Backdrop (06_waifu_streetwear_tokyo.jpg)
        Image(
            painter = mascotPainter(id = R.drawable.hione_drawer_bg_tokyo),
            contentDescription = "App drawer background",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Luminous Frosted Cyber Gradient Overlay (High Visibility)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0x660C0D10),
                            Mascot.color(0x330C0D10),
                            Mascot.color(0x880C0D10)
                        )
                    )
                )
        )
        // Pixel-style swipe-down-to-dismiss: when the app grid is scrolled to the top
        // and the user keeps dragging down, the leftover (unconsumed) scroll accumulates
        // here and dismisses the drawer past a threshold. Works alongside the back arrow.
        var pullDownAccum by remember { mutableFloatStateOf(0f) }
        val dismissThresholdPx = with(LocalDensity.current) { 90.dp.toPx() }
        val upFlingDismissPxPerSec = with(LocalDensity.current) { 900.dp.toPx() }
        val latestClose = rememberUpdatedState(onClose)
        val swipeDownDismiss = remember(dismissThresholdPx, sheet) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (sheet != null) {
                        // While the sheet sits pulled-down, an upward drag restores it before the grid scrolls.
                        if (source == NestedScrollSource.Drag && available.y < 0f && sheet.progress.value < 0.999f) {
                            sheet.pullDownBy(available.y)
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }
                    // Any upward scroll (list moving up) resets the pull accumulator.
                    if (available.y < 0f) pullDownAccum = 0f
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.Drag && available.y > 0f) {
                        if (sheet != null) {
                            // Grid already at its top: the leftover pull moves the whole sheet with the finger.
                            sheet.pullDownBy(available.y)
                            return Offset(0f, available.y)
                        }
                        pullDownAccum += available.y
                        if (pullDownAccum > dismissThresholdPx) {
                            pullDownAccum = 0f
                            onClose()
                        }
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (sheet != null && (sheet.dragging || sheet.progress.value < 0.999f)) {
                        if (sheet.releasePull(available.y)) latestClose.value()
                        return available
                    }
                    return androidx.compose.ui.unit.Velocity.Zero
                }
                // Swipe UP past the end of the grid also dismisses: a fling the grid couldn't consume
                // (it is already at its bottom) arrives here with its upward velocity intact. The
                // velocity gate keeps a short list (which never consumes) from closing on a slow nudge.
                override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (available.y < -upFlingDismissPxPerSec) {
                        latestClose.value()
                        return available
                    }
                    return androidx.compose.ui.unit.Velocity.Zero
                }
            }
        }
        val lowPowerDrawer by com.hione.launcher.ui.rememberLowPower()
        val parallaxPx = if (lowPowerDrawer) 0f else with(LocalDensity.current) { 12.dp.toPx() }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                // Content lags the sheet by up to 12dp while revealing (Pixel drawer parallax).
                .graphicsLayer { translationY = (1f - revealProgress().coerceIn(0f, 1f)) * parallaxPx }
                .nestedScroll(swipeDownDismiss)
        ) {
            // Drag Down Dismiss Pull-Bar (Tapping or pulling down triggers close or QS)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clickable { onOpenQuickSettings() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(52.dp)
                        .height(4.5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(HioneCyan, HioneNeonPink)
                            )
                        )
                )
            }
            // Search Bar with Back Button & Single-Line IME Search Action
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberGlassCard)
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(16.dp))
                    .height(HioneDimens.touchMin + 4.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(HioneDimens.touchMin)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = HioneCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Compact single-line search (BasicTextField: Material TextField wants 56dp and clips
                // inside a 44dp status-bar-height row).
                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = HioneDimens.textM, color = Color.White),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(HioneCyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                Text("Search apps…", color = HioneTextSecondary, fontSize = HioneDimens.textS, maxLines = 1)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Category Filter Chips
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { cat ->
                    val isSelected = selectedCategory.equals(cat, ignoreCase = true)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) HioneCyan else CyberGlassCard)
                            .border(1.dp, if (isSelected) HioneCyan else CyberGlassBorder, RoundedCornerShape(12.dp))
                            .clickable { onCategoryChange(cat) }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = cat,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // App Grid (4 Columns)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredApps) { app ->
                    DesktopAppIconItem(
                        app = app,
                        onClick = { onLaunchApp(app) },
                        onLongClick = { onAppLongClick(app) }
                    )
                }
                if (filteredApps.isEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        com.hione.launcher.ui.HioneEmptyState(
                            face = com.hione.launcher.ui.HioneIdentity.CHIBI_SEARCH,
                            text = if (searchQuery.isNotEmpty()) "No app matches \"$searchQuery\"" else "Nothing here yet",
                            hint = if (searchQuery.isNotEmpty()) "Try a shorter name" else null
                        )
                    }
                }
            }

            // (Removed redundant bottom navigation pill — it duplicated the system
            // 2-tone gesture pill. Swipe-down-to-dismiss is handled by the nestedScroll
            // connection above, and the header back arrow still closes the drawer.)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopAppIconItem(
    app: InstalledApp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val iconBitmap = remember(app.packageName, app.activityName) {
        app.systemIcon?.let { d ->
            try {
                d.toBitmap(width = 160, height = 160, config = android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .hioneAppIconClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                launchPackage = app.packageName
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The BPM game's drawer entry is a game, not a utility: highlighted, and pulsing on the
        // beat while music plays (the drawer covers home, hence insideCover).
        val isBpmGame = app.packageName == ctx.packageName &&
            app.activityName == com.hione.launcher.bpm.HioneBpmGameActivity::class.java.name
        Box(
            Modifier
                .size(HioneDimens.appIconArt)
                .then(if (isBpmGame) Modifier.hioneBeatPulse(Mascot.color(0xFFFF4FA3), corner = 16.dp, insideCover = true) else Modifier)
                .clip(RoundedCornerShape(16.dp))
                .background(Mascot.color(0x1AFFC56B))
                .border(0.8.dp, CyberGlassBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            val golden = rememberUnlockEnabled(com.hione.launcher.bpm.HioneSecrets.GOLDEN_ICON)
            val shownRes = displayIconRes(app.iconResId, golden)
            val customPainter = remember(shownRes) {
                if (shownRes != null && shownRes != 0) {
                    try {
                        ctx.resources.getDrawable(shownRes, ctx.theme)?.toBitmap(160, 160)?.asImageBitmap()
                    } catch (_: Throwable) { null }
                } else null
            }

            if (customPainter != null) {
                Image(
                    bitmap = customPainter,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (iconBitmap != null) {
                // Color-shift 3rd party app icons to Hione cyberpunk palette
                val cyberFilter = remember {
                    val matrix = android.graphics.ColorMatrix().apply {
                        setSaturation(0.70f)
                    }
                    val cm = androidx.compose.ui.graphics.ColorMatrix(matrix.array)
                    androidx.compose.ui.graphics.ColorFilter.colorMatrix(cm)
                }
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.label,
                    colorFilter = cyberFilter,
                    modifier = Modifier.fillMaxSize().padding(3.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = app.label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun DockIconItem(
    iconRes: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .hioneAppIconClickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 2.dp)
    ) {
        Box(
            Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = mascotPainter(id = iconRes),
                contentDescription = label,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = HioneTextSecondary,
            fontSize = HioneDimens.textXs,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * The REAL Hione FM tuner is the platform-signed com.caf.fmradio build (versionCode >= 1000): only
 * that package name runs in the SELinux vendor_fm_app domain and can touch /dev/radio0. The FM tab
 * inside com.hione.player cannot tune and only shows a fake tuner, so it is a last-resort fallback.
 */
/**
 * True when the system FM tuner package (com.caf.fmradio) is installed AND enabled — whichever
 * build it is (HiBy's stock FM2 or the Hione FM system build). It is the only package SELinux
 * lets touch /dev/radio0, so the launcher always prefers it over Hione Music's in-app radio tab.
 * No versionCode gate: an adb-installed Hione FM update can't load its JNI from /data, so the
 * active copy may legitimately be the stock v14.
 */
fun isRealHioneFmInstalled(ctx: Context): Boolean = try {
    val pm = ctx.packageManager
    val ai = pm.getApplicationInfo("com.caf.fmradio", 0)
    val state = pm.getApplicationEnabledSetting("com.caf.fmradio")
    val enabled = ai.enabled && state != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
        state != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
        state != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    enabled && pm.getLaunchIntentForPackage("com.caf.fmradio") != null
} catch (_: Throwable) { false }

fun launchHioneFm(ctx: Context) {
    val opts = HioneCompositing.optionsForLaunch(ctx, HioneTransitionEvent.APP_OPEN, com.hione.launcher.ui.HioneLaunchSource.take())
    if (isRealHioneFmInstalled(ctx)) {
        // Resolve the tuner's LAUNCHER activity dynamically: stock FM2 = .FMRadio, Hione FM = .HioneFMRadioActivity.
        try {
            val li = ctx.packageManager.getLaunchIntentForPackage("com.caf.fmradio")?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (li != null) { ctx.startActivity(li, opts); return }
        } catch (_: Throwable) {}
        for (cls in listOf("com.caf.fmradio.FMRadio", "com.caf.fmradio.HioneFMRadioActivity")) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName("com.caf.fmradio", cls)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }, opts)
                return
            } catch (_: Throwable) {}
        }
    }
    // Package absent: the in-app radio tab (no tuner access — it hands off / shows unavailable).
    try {
        ctx.startActivity(Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName("com.hione.player", "com.hione.player.radio.HioneFMRadioActivity")
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }, opts)
        return
    } catch (_: Throwable) {}
    try { ctx.startActivity(Intent("com.caf.fmradio.FMRADIO_ACTIVITY").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts) } catch (_: Throwable) {}
}

// Standard-launcher behavior: tapping the homescreen clock opens the clock app.
// Try Hione Clock (com.hione.tools), then Google Clock, then AOSP Deskclock, then the generic
// SHOW_ALARMS intent, else a Toast.
fun launchClockApp(ctx: Context) {
    val pm = ctx.packageManager
    try {
        val hione = Intent(Intent.ACTION_MAIN).apply {
            component = android.content.ComponentName("com.hione.tools", "com.hione.tools.clock.ClockActivity")
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (hione.resolveActivity(pm) != null) {
            ctx.startActivity(hione, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.APP_OPEN))
            return
        }
    } catch (_: Throwable) {}
    for (pkg in listOf("com.google.android.deskclock", "com.android.deskclock")) {
        val intent = pm.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent != null) {
            try {
                ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.APP_OPEN))
                return
            } catch (_: Throwable) {}
        }
    }
    try {
        val alarmIntent = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (alarmIntent.resolveActivity(pm) != null) {
            ctx.startActivity(alarmIntent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.APP_OPEN))
            return
        }
    } catch (_: Throwable) {}
    android.widget.Toast.makeText(ctx, "No clock app found", android.widget.Toast.LENGTH_SHORT).show()
}

fun launchApp(ctx: Context, app: InstalledApp) {
    try {
        // The BPM game's drawer entry: open it on top of the launcher (no new task), so closing
        // the game drops the player back on the drawer/home they opened it from.
        if (app.packageName == ctx.packageName && app.activityName == com.hione.launcher.bpm.HioneBpmGameActivity::class.java.name) {
            com.hione.launcher.bpm.HioneBpmGameActivity.open(ctx, "drawer")
            return
        }
        if (app.packageName == "com.android.settings") {
            val legacyIntent = ctx.packageManager.getLaunchIntentForPackage("com.android.settings")
                ?: Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    setPackage("com.android.settings")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
            ctx.startActivity(legacyIntent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
            return
        }
        if (app.activityName.contains("Settings", ignoreCase = true) || app.label.contains("${Mascot.name} Settings", ignoreCase = true) || app.packageName == "com.hione.settings") {
            val intent = ctx.packageManager.getLaunchIntentForPackage("com.hione.settings")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (intent != null) {
                ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
                return
            }
        }
        if (app.packageName == "com.hione.player" || app.label.contains("${Mascot.musicAppName}", ignoreCase = true)) {
            val intent = Intent().apply {
                setClassName("com.hione.player", "com.hione.player.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (intent != null) {
                ctx.startActivity(intent, HioneCompositing.optionsForLaunch(ctx, HioneTransitionEvent.APP_OPEN, com.hione.launcher.ui.HioneLaunchSource.take()))
                return
            }
        }
        val intent = if (app.activityName.isNotEmpty()) {
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(app.packageName, app.activityName)
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        } else {
            ctx.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        }
        if (intent != null) {
            val opts = HioneCompositing.optionsForLaunch(ctx, HioneTransitionEvent.APP_OPEN, com.hione.launcher.ui.HioneLaunchSource.take())
            ctx.startActivity(intent, opts)
        }
    } catch (_: Throwable) {}
}

fun resolveMatchingApp(allApps: List<InstalledApp>, pkg: String, cls: String): InstalledApp? {
    if (pkg == "com.hione.player") {
        if (cls.contains("FMRadio") || cls.contains("radio")) {
            return allApps.firstOrNull { it.activityName.contains("FMRadio") || it.label.contains("FM") }
        }
        if (cls.contains("Settings")) {
            return allApps.firstOrNull { it.activityName.contains("Settings") || it.label.contains("Settings") }
        }
        if (cls.contains("MainActivity") || cls.contains("player")) {
            return allApps.firstOrNull { it.activityName.contains("MainActivity") || it.label.contains("${Mascot.musicAppName}") || it.label.contains("Music") }
        }
    }
    return allApps.firstOrNull { it.packageName == pkg && (it.activityName.isEmpty() || it.activityName == cls || cls.endsWith(it.activityName)) }
        ?: allApps.firstOrNull { it.packageName == pkg }
}

fun loadRecentTasks(ctx: Context, allApps: List<InstalledApp>): List<RecentTaskItem> {
    val pm = ctx.packageManager
    val results = ArrayList<RecentTaskItem>()
    val seenPackages = HashSet<String>()

    // Tier 1: Query global running activities and tasks via dumpsys activity activities (catches Spotify, Chrome, etc.)
    try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys activity activities | grep -E 'Run #[0-9]+:'"))
        val lines = process.inputStream.bufferedReader().readLines()
        for (line in lines) {
            val match = Regex("u0\\s+([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)\\s+t([0-9]+)").find(line)
            if (match != null) {
                val pkg = match.groupValues[1]
                val cls = match.groupValues[2]
                val taskId = match.groupValues[3].toIntOrNull() ?: -1
                if (pkg == ctx.packageName && cls.contains("Launcher")) continue
                val seenKey = if (pkg == ctx.packageName) "$pkg/$cls" else pkg
                if (seenPackages.contains(seenKey)) continue
                seenPackages.add(seenKey)

                val fullCls = if (cls.startsWith(".")) pkg + cls else cls
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(pkg, fullCls)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }

                val matchingApp = resolveMatchingApp(allApps, pkg, fullCls)
                val label = matchingApp?.label ?: try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Throwable) { pkg }

                val iconRes = matchingApp?.iconResId ?: resolveCustomAppIcon(pkg, label, fullCls)
                val systemIcon = matchingApp?.systemIcon ?: try {
                    pm.getApplicationIcon(pkg)
                } catch (_: Throwable) { null }

                results.add(
                    RecentTaskItem(
                        taskId = taskId,
                        label = label,
                        packageName = pkg,
                        iconRes = iconRes,
                        systemIcon = systemIcon,
                        baseIntent = intent
                    )
                )
            }
        }
    } catch (_: Throwable) {}

    // Tier 2: Query ActivityManager getRecentTasks via reflection / system API
    if (results.isEmpty()) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                @Suppress("DEPRECATION")
                val recentList = am.getRecentTasks(30, ActivityManager.RECENT_WITH_EXCLUDED or ActivityManager.RECENT_IGNORE_UNAVAILABLE)
                for (r in recentList) {
                    val baseIntent = r.baseIntent ?: continue
                    val cmp = baseIntent.component ?: continue
                    val pkg = cmp.packageName
                    val cls = cmp.className
                    if (pkg == ctx.packageName && cls.contains("Launcher")) continue
                    val seenKey = if (pkg == ctx.packageName) "$pkg/$cls" else pkg
                    if (seenPackages.contains(seenKey)) continue
                    seenPackages.add(seenKey)

                    val matchingApp = resolveMatchingApp(allApps, pkg, cls)
                    val label = matchingApp?.label ?: try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Throwable) { pkg }

                    val iconRes = matchingApp?.iconResId ?: resolveCustomAppIcon(pkg, label, cls)
                    val systemIcon = matchingApp?.systemIcon ?: try {
                        pm.getApplicationIcon(pkg)
                    } catch (_: Throwable) { null }

                    results.add(
                        RecentTaskItem(
                            taskId = r.id,
                            label = label,
                            packageName = pkg,
                            iconRes = iconRes,
                            systemIcon = systemIcon,
                            baseIntent = baseIntent
                        )
                    )
                }
            }
        } catch (_: Throwable) {}
    }

    // Tier 3: Query appTasks fallback
    if (results.isEmpty()) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val appTasks = try { am?.appTasks } catch (_: Throwable) { null } ?: emptyList()
        for (task in appTasks) {
            val info = task.taskInfo ?: continue
            val baseIntent = info.baseIntent ?: continue
            val cmp = baseIntent.component ?: continue
            val pkg = cmp.packageName
            val cls = cmp.className
            if (pkg == ctx.packageName && cmp.className.contains("Launcher")) continue
            val seenKey = if (pkg == ctx.packageName) "$pkg/$cls" else pkg
            if (seenPackages.contains(seenKey)) continue
            seenPackages.add(seenKey)

            val matchingApp = resolveMatchingApp(allApps, pkg, cls)
            val label = matchingApp?.label ?: try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Throwable) { pkg }

            val iconRes = matchingApp?.iconResId ?: resolveCustomAppIcon(pkg, label, cls)
            val systemIcon = matchingApp?.systemIcon ?: try {
                pm.getApplicationIcon(pkg)
            } catch (_: Throwable) { null }

            results.add(
                RecentTaskItem(
                    taskId = info.id,
                    label = label,
                    packageName = pkg,
                    iconRes = iconRes,
                    systemIcon = systemIcon,
                    baseIntent = baseIntent
                )
            )
        }
    }
    return results
}

fun bringTaskToFront(ctx: Context, task: RecentTaskItem) {
    // 1. Root am task to-front
    if (task.taskId > 0) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am task to-front ${task.taskId}"))
            return
        } catch (_: Throwable) {}
    }
    // 2. ActivityManager moveTaskToFront
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    if (am != null && task.taskId > 0) {
        try {
            am.moveTaskToFront(task.taskId, ActivityManager.MOVE_TASK_WITH_HOME)
            return
        } catch (_: Throwable) {}
    }
    // 3. Fallback launch via Intent
    if (task.baseIntent != null) {
        task.baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { ctx.startActivity(task.baseIntent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.APP_OPEN)) } catch (_: Throwable) {}
    } else {
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(task.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(launchIntent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.APP_OPEN)) } catch (_: Throwable) {}
        }
    }
}

/**
 * Actually removes a task and stops its app. Returns true only if the task removal was accepted.
 *
 * This used to be `su -c am force-stop` (there is no su on HioneOS — the fork threw and was
 * swallowed) plus `am.appTasks…finishAndRemoveTask()`, and appTasks only ever contains THIS app's
 * own tasks, so a third-party card was never removed by either half. The caller then deleted the
 * card from the list anyway, so the UI showed a dismissal that had not happened.
 */
fun dismissTask(ctx: Context, task: RecentTaskItem): Boolean {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    var removed = false
    try {
        am?.appTasks?.firstOrNull { it.taskInfo?.taskId == task.taskId }?.let {
            it.finishAndRemoveTask()
            removed = true
        }
    } catch (_: Throwable) {}
    if (!removed) {
        // Platform-signed path (HioneOS is privileged, not rooted): ActivityTaskManager.removeTask.
        removed = runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val svc = atmClass.getMethod("getService").invoke(null)
            svc.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(svc, task.taskId)
            true
        }.getOrDefault(false)
    }
    // forceStopPackage is a signature-permission @SystemApi — reachable for a platform-signed
    // launcher, unlike the `su` fork it replaces.
    runCatching {
        ActivityManager::class.java.getMethod("forceStopPackage", String::class.java).invoke(am, task.packageName)
    }
    return removed
}

fun clearAllTasks(ctx: Context, tasks: List<RecentTaskItem>) {
    for (t in tasks) {
        dismissTask(ctx, t)
    }
}

fun expandNotificationShade(ctx: Context) {
    // The Hione shade is a window owned by HioneSystemUI's navigation service: ask it to show.
    // Needs android.permission.STATUS_BAR (we hold it, platform-signed). If that service is not
    // running (accessibility service off) there is no receiver, so fall back to the shade activity.
    val a11yOn = runCatching {
        (android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains("com.hione.systemui")
    }.getOrDefault(false)
    if (a11yOn) {
        runCatching {
            ctx.sendBroadcast(Intent("com.hione.systemui.action.OPEN_SHADE_WINDOW").setPackage("com.hione.systemui"))
            return
        }
    }
    try {
        val intent = Intent().setClassName("com.hione.systemui", "com.hione.systemui.HioneShadeActivity").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.MODAL))
    } catch (_: Throwable) {
        try {
            val sbservice = ctx.getSystemService("statusbar")
            val statusbarManager = Class.forName("android.app.StatusBarManager")
            val expand = statusbarManager.getMethod("expandNotificationsPanel")
            expand.invoke(sbservice)
        } catch (_: Throwable) {}
    }
}

fun resolveHioneThemedIcon(packageName: String): Int? {
    return when {
        packageName.contains("music", ignoreCase = true) -> R.drawable.hione_cover
        packageName.contains("fmradio", ignoreCase = true) || packageName.contains("radio", ignoreCase = true) -> R.drawable.ic_fm_hione
        packageName.contains("gallery", ignoreCase = true) || packageName.contains("photo", ignoreCase = true) -> R.drawable.ic_gallery_hione
        packageName.contains("file", ignoreCase = true) || packageName.contains("document", ignoreCase = true) -> R.drawable.ic_file_hione
        packageName.contains("setting", ignoreCase = true) -> R.drawable.ic_settings_hione
        packageName.contains("camera", ignoreCase = true) -> R.drawable.ic_camera_hione
        packageName.contains("browser", ignoreCase = true) || packageName.contains("chrome", ignoreCase = true) -> R.drawable.ic_browser_hione
        packageName.contains("calculator", ignoreCase = true) -> R.drawable.ic_calculator_hione
        packageName.contains("clock", ignoreCase = true) || packageName.contains("alarm", ignoreCase = true) -> R.drawable.ic_clock_hione
        packageName.contains("record", ignoreCase = true) -> R.drawable.ic_fm_hione
        packageName.contains("search", ignoreCase = true) -> R.drawable.ic_search_head_hione
        else -> null
    }
}

fun resolveAppCategory(packageName: String, label: String): String {
    val text = "$packageName $label".lowercase()
    return when {
        text.contains("music") || text.contains("audio") || text.contains("radio") || text.contains("sound") -> "Audio"
        text.contains("setting") || text.contains("config") -> "Settings"
        text.contains("gallery") || text.contains("camera") || text.contains("video") || text.contains("media") -> "Media"
        text.contains("file") || text.contains("clock") || text.contains("calc") || text.contains("record") -> "Tools"
        else -> "All"
    }
}

// ================================================================
// BESPOKE DOMAIN-SPECIFIC CYBER SHAPES
// ================================================================

val WifiRadarShape = GenericShape { size, _ ->
    val chamfer = 8f
    moveTo(0f, chamfer)
    lineTo(size.width * 0.25f, 0f)
    lineTo(size.width - chamfer, 0f)
    lineTo(size.width, chamfer)
    lineTo(size.width, size.height - chamfer)
    lineTo(size.width * 0.75f, size.height)
    lineTo(chamfer, size.height)
    lineTo(0f, size.height - chamfer)
    close()
}

val BatteryCellShape = GenericShape { size, _ ->
    val terminalW = 4f
    val terminalTop = size.height * 0.25f
    val terminalBottom = size.height * 0.75f
    val chamfer = 6f
    moveTo(chamfer, 0f)
    lineTo(size.width - terminalW - chamfer, 0f)
    lineTo(size.width - terminalW, chamfer)
    lineTo(size.width - terminalW, terminalTop)
    lineTo(size.width, terminalTop)
    lineTo(size.width, terminalBottom)
    lineTo(size.width - terminalW, terminalBottom)
    lineTo(size.width - terminalW, size.height - chamfer)
    lineTo(size.width - terminalW - chamfer, size.height)
    lineTo(chamfer, size.height)
    lineTo(0f, size.height - chamfer)
    lineTo(0f, chamfer)
    close()
}

val DacChipShape = GenericShape { size, _ ->
    val c = 6f
    val notch = 3f
    moveTo(c, 0f)
    lineTo(size.width * 0.38f, 0f)
    lineTo(size.width * 0.44f, notch)
    lineTo(size.width * 0.56f, notch)
    lineTo(size.width * 0.62f, 0f)
    lineTo(size.width - c, 0f)
    lineTo(size.width, c)
    lineTo(size.width, size.height - c)
    lineTo(size.width - c, size.height)
    lineTo(c, size.height)
    lineTo(0f, size.height - c)
    lineTo(0f, c)
    close()
}

val BrainShieldShape = GenericShape { size, _ ->
    val c = 8f
    moveTo(c, 0f)
    lineTo(size.width - c, 0f)
    lineTo(size.width, size.height * 0.42f)
    lineTo(size.width * 0.82f, size.height)
    lineTo(size.width * 0.18f, size.height)
    lineTo(0f, size.height * 0.42f)
    close()
}

val IngressStreamShape = GenericShape { size, _ ->
    val c = 10f
    moveTo(c, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width - (c * 0.6f), size.height * 0.5f)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    lineTo(c * 0.6f, size.height * 0.5f)
    close()
}

val AtmosphereHorizonShape = GenericShape { size, _ ->
    val c = 8f
    moveTo(0f, c)
    quadraticTo(size.width * 0.5f, -4f, size.width, c)
    lineTo(size.width, size.height - c)
    lineTo(size.width - c, size.height)
    lineTo(c, size.height)
    lineTo(0f, size.height - c)
    close()
}

val SatelliteDiamondShape = GenericShape { size, _ ->
    val c = 8f
    moveTo(c, 0f)
    lineTo(size.width - c, 0f)
    lineTo(size.width, c)
    lineTo(c, size.height)
    lineTo(0f, size.height - c)
    lineTo(0f, c)
    close()
}

val SakuraBlossomShape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val rx = size.width / 2f
    val ry = size.height / 2f
    val steps = 60
    for (i in 0..steps) {
        val theta = (i.toFloat() / steps) * 2f * Math.PI.toFloat()
        val rNorm = 0.84f + 0.16f * kotlin.math.cos(5f * theta) - 0.05f * kotlin.math.abs(kotlin.math.sin(5f * theta))
        val x = cx + rx * rNorm * kotlin.math.cos(theta - Math.PI.toFloat() / 2f)
        val y = cy + ry * rNorm * kotlin.math.sin(theta - Math.PI.toFloat() / 2f)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * Artsy Chamfered Cyber Weather & GPS Telemetry Badge.
 * Features a dynamic live weather art background tailored to the current weather condition
 * (Rain, Thunderstorm, Snow, Cloud, Sunny Day, Starry Night) and high-density atmospheric metrics.
 */
@Composable
fun HioneCyberWeatherGpsBadge(
    weather: com.hione.launcher.weather.HioneWeatherService.WeatherCondition,
    gps: com.hione.launcher.weather.HioneWeatherService.GpsTelemetry,
    onWeatherClick: () -> Unit,
    onGpsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lowPowerGate by com.hione.launcher.ui.rememberAmbientGate()
    val infiniteTransition = rememberInfiniteTransition(label = "TelemetryGlow")
    val pulseGlow by infiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TelemetryGlow"
    )
    val weatherAnimPhase by infiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WeatherAnim"
    )

    // Classify weather condition for dynamic theme and art
    val code = weather.code
    val isNight = !weather.isDay
    val isRain = code in listOf(51, 53, 55, 61, 63, 65, 80, 81, 82) || weather.summary.contains("Rain", ignoreCase = true) || weather.summary.contains("Drizzle", ignoreCase = true)
    val isThunder = code in listOf(95, 96, 99) || weather.summary.contains("Thunder", ignoreCase = true) || weather.summary.contains("Storm", ignoreCase = true)
    val isSnow = code in listOf(71, 73, 75, 77, 85, 86) || weather.summary.contains("Snow", ignoreCase = true)
    val isCloudy = code in listOf(2, 3, 45, 48) || weather.summary.contains("Cloud", ignoreCase = true) || weather.summary.contains("Overcast", ignoreCase = true)

    val badgeGradient = when {
        isThunder -> listOf(Color(0xF020083B), Color(0xFA0B0218), Mascot.color(0xF02A042A))
        isRain -> listOf(Mascot.color(0xF003202E), Mascot.color(0xFA02121A), Mascot.color(0xF0011C24))
        isSnow -> listOf(Color(0xF006243A), Color(0xFA051726), Mascot.color(0xF00C2E42))
        isNight -> listOf(Color(0xF0051120), Color(0xFA020912), Color(0xF00A1324))
        isCloudy -> listOf(Mascot.color(0xF00C202B), Mascot.color(0xFA07151D), Mascot.color(0xF012222D))
        else -> listOf(Color(0xF01A2412), Color(0xFA0A1810), Mascot.color(0xF0071C26)) // Sunny/Clear Day
    }

    val rimColor = when {
        isThunder -> Color(0xFFE040FB)
        isRain -> Mascot.color(0xFFFFC56B)
        isSnow -> Color(0xFF80D8FF)
        isNight -> Color(0xFF7C4DFF)
        isCloudy -> Color(0xFF40C4FF)
        else -> com.hione.launcher.ui.HioneIdentity.Gold // Solar Gold
    }

    val badgeShape = remember {
        CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp, topEnd = 4.dp, bottomStart = 4.dp)
    }

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(badgeShape)
            .background(Brush.horizontalGradient(badgeGradient))
            .border(
                BorderStroke(
                    0.9.dp,
                    Brush.linearGradient(
                        listOf(
                            rimColor.copy(alpha = pulseGlow),
                            CyberGlassBorder.copy(alpha = 0.35f),
                            HioneNeonPink.copy(alpha = pulseGlow * 0.75f)
                        )
                    )
                ),
                badgeShape
            )
    ) {
        // ==========================================
        // DYNAMIC PROCEDURAL WEATHER ART BACKGROUND CANVAS
        // ==========================================
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            when {
                isThunder -> {
                    // Electric purple thunder glow + occasional flashing lightning bolt
                    val flash = (kotlin.math.sin(weatherAnimPhase * 12.56f) > 0.88f)
                    if (flash) {
                        drawRect(Color(0x33E040FB))
                        drawLine(
                            color = Color.White.copy(alpha = 0.85f),
                            start = Offset(w * 0.45f, 0f),
                            end = Offset(w * 0.52f, h * 0.55f),
                            strokeWidth = 1.5f
                        )
                        drawLine(
                            color = Mascot.color(0xFFFFC56B),
                            start = Offset(w * 0.52f, h * 0.55f),
                            end = Offset(w * 0.48f, h),
                            strokeWidth = 1.2f
                        )
                    }
                }
                isRain -> {
                    // Slanted falling neon cyan rain streaks
                    repeat(14) { idx ->
                        val rx = (idx * 27f + weatherAnimPhase * 40f) % w
                        val ry = (idx * 13f + weatherAnimPhase * h * 2.2f) % h
                        drawLine(
                            color = Mascot.color(0x66FFC56B),
                            start = Offset(rx, ry),
                            end = Offset(rx - 4f, ry + 7f),
                            strokeWidth = 1.1f
                        )
                    }
                }
                isSnow -> {
                    // Drifting soft snowflakes
                    repeat(12) { idx ->
                        val sx = (idx * 31f + kotlin.math.sin(weatherAnimPhase * 6.28f + idx) * 8f) % w
                        val sy = (idx * 11f + weatherAnimPhase * h * 1.2f) % h
                        drawCircle(
                            color = Color.White.copy(alpha = 0.55f),
                            radius = if (idx % 2 == 0) 1.5f else 1.0f,
                            center = Offset(sx, sy)
                        )
                    }
                }
                isNight -> {
                    // Night Starfield Twinkles
                    val stars = listOf(
                        Offset(w * 0.15f, h * 0.25f),
                        Offset(w * 0.45f, h * 0.75f),
                        Offset(w * 0.78f, h * 0.30f),
                        Offset(w * 0.90f, h * 0.68f)
                    )
                    stars.forEachIndexed { i, pt ->
                        val twAlpha = (kotlin.math.sin(weatherAnimPhase * 6.28f + i) * 0.4f + 0.6f).toFloat()
                        drawCircle(
                            color = Color(0xFFE1BEE7).copy(alpha = twAlpha),
                            radius = 1.2f,
                            center = pt
                        )
                    }
                }
                isCloudy -> {
                    // Overcast Fog Bands
                    drawCircle(
                        color = Color(0x18FFFFFF),
                        radius = h * 0.8f,
                        center = Offset(w * 0.3f, h * 0.9f)
                    )
                    drawCircle(
                        color = Color(0x14FFFFFF),
                        radius = h * 0.65f,
                        center = Offset(w * 0.7f, h * 0.85f)
                    )
                }
                else -> {
                    // Sunny Solar Corona Flare (Top-Right)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(0x33FFD600), Color(0x11FF6D00), Color.Transparent),
                            center = Offset(w * 0.88f, h * 0.2f),
                            radius = h * 1.1f
                        ),
                        radius = h * 1.1f,
                        center = Offset(w * 0.88f, h * 0.2f)
                    )
                }
            }
        }

        // ==========================================
        // HIGH-DENSITY ATMOSPHERIC METRICS CONTENT
        // ==========================================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 7.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // lastUpdatedTime == 0 -> nothing fetched yet; every field is the model's neutral
            // default and is rendered as an em dash, never as a reading.
            val wxReal = weather.lastUpdatedTime > 0L
            // Row 1: Weather Icon + Live Temp + High/Low Range + Condition Summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onWeatherClick() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (wxReal) weather.icon.ifEmpty { if (isNight) "🌙" else "☀️" } else "🌐",
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (wxReal) "${weather.tempF.toInt()}°F" else "—°F",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont
                    )
                }

                // High / Low Micro Capsule
                if (wxReal && weather.highTempF != 0f && weather.lowTempF != 0f) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x66000000))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("▲${weather.highTempF.toInt()}°", color = Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(3.dp))
                        Text("▼${weather.lowTempF.toInt()}°", color = Mascot.color(0xFFFFC56B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Summary
                Text(
                    if (wxReal && weather.summary.isNotEmpty()) weather.summary else if (wxReal) "—" else "No weather data",
                    color = rimColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(3.dp))

            // Row 2: Atmospheric Telemetry (Humidity, Wind, Precip, GPS Location)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGpsClick() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left Metrics: Humidity & Wind
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (wxReal) "${weather.humidityPct}% RH" else "RH —", color = HioneCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(5.dp))
                    Text(if (wxReal) "${weather.windSpeedMph.toInt()}mph" else "Wind —", color = HioneTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (wxReal && weather.precipitationProbPct > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text("${weather.precipitationProbPct}% rain", color = Mascot.color(0xFFFF80AB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Right: Active City / Tactical Coordinate Lock
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val locText = when {
                        gps.city.isNotEmpty() -> gps.city
                        gps.fuzzyLocation.isNotEmpty() -> gps.fuzzyLocation.split(",").firstOrNull() ?: "Locating..."
                        gps.isLocked -> "Have a fix"
                        else -> "Locating..."
                    }
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (gps.isLocked) Color(0xFF00FF7F) else com.hione.launcher.ui.HioneIdentity.Gold)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = locText,
                        color = if (gps.isLocked) Color(0xFF00FF7F) else Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Oki Hione Connected Dual-RF Network Capsule (Wi-Fi & Cellular/LTE Dual Pod).
 * Interlocks live high-granularity Wi-Fi and Cellular/LTE radio status into
 * a single unified, connected dual-pod cyber badge with individual status pods
 * joined seamlessly by an illuminated tech-seam.
 */
@Composable
fun ConnectedRfNetworkCapsule(
    wifi: com.hione.launcher.network.HioneNetworkService.WifiGranularState,
    cell: com.hione.launcher.network.HioneNetworkService.CellularGranularState,
    // false until HioneNetworkService has polled the radios once — before that neither "WIFI" nor
    // "OFF" is a fact, so the pod shows "—".
    radioStateKnown: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val capsuleShape = remember {
        CutCornerShape(
            topStart = 6.dp,
            bottomStart = 6.dp,
            topEnd = 6.dp,
            bottomEnd = 6.dp
        )
    }

    val wifiColor = when {
        !wifi.isConnected -> Color.White.copy(alpha = 0.5f)
        wifi.rssiDbm >= -65 -> HioneCyan
        wifi.rssiDbm >= -80 -> com.hione.launcher.ui.HioneIdentity.Gold
        else -> Color(0xFFFF5252)
    }
    // "No data" is only a PROBLEM when cellular is meant to carry data — i.e. Wi-Fi isn't already
    // providing it. On Wi-Fi the cell radio idling without a data PDN is normal, so show the signal
    // bars neutrally by level instead of an alarming red warning.
    val cellNoData = cell.hasSignal && !cell.dataConnected && !wifi.isConnected
    val cellColor = when {
        !cell.isConnected -> Color.White.copy(alpha = 0.5f)
        cellNoData -> Color(0xFFFF5252)                    // genuine no-data (no Wi-Fi fallback)
        cell.signalLevel5 >= 3 -> com.hione.launcher.ui.HioneIdentity.Leek
        cell.signalLevel5 == 2 -> com.hione.launcher.ui.HioneIdentity.Gold
        cell.signalLevel5 == 1 -> Color(0xFFFF9800)
        else -> Color.White.copy(alpha = 0.6f)
    }

    Box(
        modifier = modifier
            .clip(capsuleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.55f)
                    )
                )
            )
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .padding(1.dp)
                .clip(capsuleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Mascot.color(0xEE08202A),
                            Mascot.color(0xFF041218),
                            Color(0xEE061C14)
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(
                                wifiColor.copy(alpha = 0.9f),
                                CyberGlassBorder.copy(alpha = 0.35f),
                                cellColor.copy(alpha = 0.9f)
                            )
                        )
                    ),
                    capsuleShape
                )
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // ==================== POD 1: WI-FI ====================
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                ) {
                    if (wifi.isConnected) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = "Wi-Fi Connected",
                            tint = wifiColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (wifi.rssiDbm > -100) "WIFI ${wifi.rssiDbm}d" else "WIFI",
                            color = wifiColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont
                        )
                    } else {
                        Icon(
                            Icons.Default.WifiOff,
                            contentDescription = "Wi-Fi Off",
                            tint = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (!radioStateKnown) "—" else if (wifi.isEnabled) "WIFI" else "OFF",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont
                        )
                    }
                }

                // ==================== CENTRAL SEAM ====================
                Box(
                    Modifier
                        .padding(horizontal = 5.dp)
                        .width(1.dp)
                        .height(16.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    wifiColor.copy(alpha = 0.8f),
                                    cellColor.copy(alpha = 0.8f)
                                )
                            )
                        )
                )

                // ==================== POD 2: CELLULAR / LTE ====================
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.height(12.dp)
                    ) {
                        repeat(4) { i ->
                            val active = (i + 1) <= cell.signalLevel5
                            Box(
                                Modifier
                                    .width(2.2.dp)
                                    .height(((i + 1) * 2.8).dp)
                                    .clip(RoundedCornerShape(0.5.dp))
                                    .background(if (active) cellColor else Color.White.copy(alpha = 0.2f))
                            )
                        }
                    }
                    Text(
                        // The radio technology comes from TelephonyManager (cell.networkType);
                        // it was a hardcoded "LTE" literal that lied on 5G/3G/no-service. dBm is
                        // only printed when actually measured (real RSSI is negative; 0 = unknown).
                        text = when {
                            !radioStateKnown -> "—"
                            !cell.isConnected -> "NO SIM"
                            cellNoData -> "NO DATA"
                            else -> {
                                val tech = cell.networkType.ifEmpty { "CELL" }
                                if (cell.signalDbm < 0) "$tech ${cell.signalDbm}d" else tech
                            }
                        },
                        color = cellColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AudiowideFont
                    )
                }
            }
        }
    }
}

/**
 * Oki Hione Unified Joined Weather & Geodesic GPS Cyber Capsule.
 * Seamlessly bonds the real-time atmospheric observatory pod and the
 * high-precision GPS geodesic fix pod into a single interlocking cyber capsule
 * with live fuzzy location (County, City, State), altitude, and radar link.
 */
@Composable
fun UnifiedWeatherGpsCapsule(
    weather: com.hione.launcher.weather.HioneWeatherService.WeatherCondition,
    gps: com.hione.launcher.weather.HioneWeatherService.GpsTelemetry,
    onWeatherClick: () -> Unit,
    onGpsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lowPowerGate by com.hione.launcher.ui.rememberAmbientGate()
    val infiniteTransition = rememberInfiniteTransition(label = "UnifiedCapsuleShimmer")
    val pulseGlow by infiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "UnifiedPulse"
    )

    val capsuleShape = remember {
        CutCornerShape(
            topStart = 8.dp,
            bottomStart = 8.dp,
            topEnd = 8.dp,
            bottomEnd = 8.dp
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .clip(capsuleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.55f)
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(1.dp)
                .clip(capsuleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Mascot.color(0xEE08202A),
                            Mascot.color(0xFF041218),
                            Color(0xEE061C14)
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF00B0FF).copy(alpha = pulseGlow),
                                CyberGlassBorder.copy(alpha = 0.4f),
                                if (gps.isLocked) com.hione.launcher.ui.HioneIdentity.Leek.copy(alpha = pulseGlow) else Color(0xFFFFB300).copy(alpha = pulseGlow)
                            )
                        )
                    ),
                    capsuleShape
                )
                .padding(horizontal = 7.dp, vertical = 3.5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LEFT SECTION: Atmospheric Weather Pod (Click -> Weather Observatory Modal)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onWeatherClick() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val wxReal = weather.lastUpdatedTime > 0L
                    Text(
                        if (wxReal) weather.icon.ifEmpty { "🌐" } else "🌐",
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (wxReal) "${weather.tempF.toInt()}°F" else "—°F",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = AudiowideFont
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                if (wxReal) "(${weather.summary.ifEmpty { "—" }})" else "(no weather data)",
                                color = Color(0xFF00B0FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val statusLine = when {
                            !wxReal -> "No weather yet"
                            weather.nextPrecipLabel.isNotEmpty() ->
                                "${weather.humidityPct}% RH, ${weather.windSpeedMph.toInt()}mph · ${weather.nextPrecipLabel}"
                            else ->
                                "${weather.humidityPct}% RH, ${weather.windSpeedMph.toInt()}mph ${weather.windDirectionCompass}, ${weather.precipitationProbPct}% rain"
                        }
                        Text(
                            statusLine,
                            color = if (weather.nextPrecipLabel.isNotEmpty()) com.hione.launcher.ui.HioneIdentity.Gold else HioneTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Central Tech-Seam Link Divider
                Box(
                    Modifier
                        .padding(horizontal = 5.dp)
                        .width(1.dp)
                        .height(24.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF00B0FF).copy(alpha = 0.8f),
                                    com.hione.launcher.ui.HioneIdentity.Leek.copy(alpha = 0.8f)
                                )
                            )
                        )
                )

                // RIGHT SECTION: Linked Geodesic GPS & Fuzzy Location Pod (Click -> Tactical GPS Map Modal)
                Row(
                    modifier = Modifier
                        .weight(1.18f)
                        .clickable { onGpsClick() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (gps.isLocked) com.hione.launcher.ui.HioneIdentity.Leek else Color(0xFFFFB300))
                    )
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (gps.isLocked) "GPS FIX" else "GPS",
                                color = if (gps.isLocked) com.hione.launcher.ui.HioneIdentity.Leek else Color(0xFFFFB300),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = AudiowideFont
                            )
                            if (gps.isLocked && gps.altitudeM != 0.0) {
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "Alt ${gps.altitudeM.toInt()}m",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (gps.accuracyM > 0f) {
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "±${gps.accuracyM.toInt()}m",
                                    color = HioneCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        // Fuzzy Location display: County, City, State
                        Text(
                            text = if (gps.fuzzyLocation.isNotEmpty()) gps.fuzzyLocation else if (gps.city.isNotEmpty()) gps.city else if (gps.isLocked) "Have a fix, no place name yet" else "No location fix yet",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Oki Hione Dynamic Cyber Thermal Telemetry Badge.
 * Dynamically displays CPU core temp and Battery cell temp with thermocline coloring.
 */
@Composable
fun HioneThermalBadge(
    cpuTempC: Float,
    batteryTempC: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val maxTemp = maxOf(cpuTempC, batteryTempC)
    val hasReading = maxTemp > 0f
    val tempColor = when {
        !hasReading -> HioneTextSecondary                            // No sensor read: neutral, not "cool"
        maxTemp >= 55f -> com.hione.launcher.ui.HioneIdentity.Coral // Hot / Throttle Warning: Red
        maxTemp >= 45f -> Color(0xFFFF9100) // Warm: Amber Orange
        maxTemp >= 38f -> com.hione.launcher.ui.HioneIdentity.Gold // Nominal Warm: Gold
        maxTemp >= 30f -> Mascot.color(0xFFFFC56B) // Cool Nominal: Cyan
        else -> com.hione.launcher.ui.HioneIdentity.Leek           // Low Ambient: Mint Green
    }

    val displayTemp = when {
        cpuTempC > 0f -> "${cpuTempC.toInt()}°C"
        batteryTempC > 0f -> "${batteryTempC.toInt()}°C"
        else -> "—°C"
    }

    CyberBespokeBadge(
        onClick = onClick,
        accentColor = tempColor,
        gradient = listOf(tempColor.copy(alpha = 0.28f), Mascot.color(0xFF030D14)),
        shape = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp, topEnd = 4.dp, bottomStart = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "🔥",
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 2.dp)
            )
            Text(
                text = displayTemp,
                color = tempColor,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont,
                maxLines = 1
            )
        }
    }
}

/**
 * Oki Hione Real-Time BPM Engine Telemetry Badge.
 */
@Composable
fun HioneBpmEngineBadge(
    bpm: Float,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 0 = no real tempo; rendered as "—", never a presumed 128.
    val liveBpm = if (bpm in 40f..260f) bpm.toInt() else 0
    val bpmColor = when {
        !isPlaying || liveBpm == 0 -> Color(0xFF8BA6A9)
        liveBpm >= 150 -> com.hione.launcher.ui.HioneIdentity.Coral // Hardcore / Fast: Red
        liveBpm >= 126 -> Mascot.color(0xFF6FB0FF) // Diva / Vocaloid Dance: Pink
        liveBpm >= 100 -> Mascot.color(0xFFFFC56B) // Pop / Groove: Cyan
        else -> Color(0xFF00FF7F)           // Lo-Fi / Chill: Mint Green
    }

    CyberBespokeBadge(
        onClick = onClick,
        accentColor = bpmColor,
        gradient = listOf(bpmColor.copy(alpha = if (isPlaying) 0.35f else 0.15f), Mascot.color(0xFF030D14)),
        shape = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp, topEnd = 4.dp, bottomStart = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isPlaying) "⚡" else "♩",
                fontSize = 11.sp,
                color = bpmColor,
                modifier = Modifier.padding(end = 2.dp)
            )
            Text(
                text = if (isPlaying) (if (liveBpm > 0) "$liveBpm" else "—") else "BPM",
                color = if (isPlaying) Color.White else bpmColor,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont,
                maxLines = 1
            )
        }
    }
}

/**
 * Oki Hione Intricate Quantum Power Core Battery Badge.
 * Features 3D beveled housing, 3 discrete energy core segments, electron sparks, and thermocline colors.
 */
@Composable
fun HioneQuantumBatteryBadge(
    batteryPct: Int,
    isCharging: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // batteryPct < 0 = the sticky ACTION_BATTERY_CHANGED read failed. Show "—%", no filled bars and
    // a neutral tint instead of a red "0%" with an alarming low-battery pulse.
    val batteryKnown = batteryPct >= 0
    val batteryColor = when {
        !batteryKnown -> HioneTextSecondary
        isCharging -> com.hione.launcher.ui.HioneIdentity.Leek
        batteryPct > 50 -> Mascot.color(0xFFFFC56B)
        batteryPct > 20 -> com.hione.launcher.ui.HioneIdentity.Gold
        else -> com.hione.launcher.ui.HioneIdentity.Coral
    }

    val lowPowerGate by com.hione.launcher.ui.rememberAmbientGate()

    val infiniteTransition = rememberInfiniteTransition(label = "ChargeSparkle")
    val sparkAlpha by infiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "spark"
    )

    Box(
        modifier = modifier
            .height(24.dp)
            .clip(CutCornerShape(topStart = 5.dp, bottomEnd = 5.dp, topEnd = 3.dp, bottomStart = 3.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        batteryColor.copy(alpha = if (isCharging) 0.35f else 0.20f),
                        Mascot.color(0xFF030D14)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    if (batteryKnown && batteryPct <= 20 && !isCharging) batteryColor.copy(alpha = sparkAlpha)
                    else batteryColor.copy(alpha = 0.85f)
                ),
                CutCornerShape(topStart = 5.dp, bottomEnd = 5.dp, topEnd = 3.dp, bottomStart = 3.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // PKCELL or NEGI BATTERY (BPM-game secrets)
            val pkcell = rememberUnlockEnabled(com.hione.launcher.bpm.HioneSecrets.DANKPODS_PKCELL)
            val negi = rememberUnlockEnabled(com.hione.launcher.bpm.HioneSecrets.NEGI_BATTERY)
            if (pkcell) {
                HionePkcellBatteryGlyph(
                    pct = batteryPct, charging = isCharging,
                    low = batteryKnown && batteryPct <= 20 && !isCharging
                )
            } else if (negi) {
                HioneNegiBatteryGlyph(
                    pct = batteryPct, charging = isCharging,
                    low = batteryKnown && batteryPct <= 20 && !isCharging
                )
            } else
            // Micro 4-bar level indicator
            Row(
                modifier = Modifier
                    .width(18.dp)
                    .height(9.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Mascot.color(0xFF02090D))
                    .border(0.6.dp, batteryColor.copy(alpha = 0.6f), RoundedCornerShape(1.5.dp))
                    .padding(1.dp),
                horizontalArrangement = Arrangement.spacedBy(0.8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filledBars = when {
                    !batteryKnown -> 0   // unknown level: empty cell, not "flat battery"
                    batteryPct >= 75 -> 4
                    batteryPct >= 50 -> 3
                    batteryPct >= 25 -> 2
                    batteryPct >= 8 -> 1
                    else -> 0
                }
                repeat(4) { idx ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(0.5.dp))
                            .background(if (idx < filledBars) batteryColor else Color(0x18FFFFFF))
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            if (isCharging) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = "charging",
                    tint = Mascot.color(0xFF00FFCC),
                    modifier = Modifier.size(13.dp).padding(end = 1.dp)
                )
            }

            Text(
                text = if (batteryKnown) "$batteryPct%" else "—%",
                color = if (batteryKnown) Color.White else HioneTextSecondary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont,
                maxLines = 1
            )
        }
    }
}

/**
 * Oki Hione Bespoke Chamfered Cyber Badge.
 * High-tech domain-inspired geometry, custom shape, holographic rim lighting,
 * and individual color theming.
 */
@Composable
fun CyberBespokeBadge(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    accentColor: Color,
    shape: Shape = CutCornerShape(5.dp),
    gradient: List<Color> = listOf(Mascot.color(0xEE0A222C), Mascot.color(0xFF041218)),
    borderAlphaBase: Float = 0.95f,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(24.dp)
            .hionePressScale(
                pressedScale = 0.90f,
                glowColor = accentColor,
                interactionSource = interactionSource
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.55f)
                    )
                )
            )
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onClick() } else Modifier
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(0.8.dp)
                .clip(shape)
                .background(Brush.verticalGradient(gradient))
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                accentColor.copy(alpha = borderAlphaBase),
                                CyberGlassBorder.copy(alpha = 0.35f),
                                accentColor.copy(alpha = (borderAlphaBase * 0.75f).coerceIn(0f, 1f))
                            )
                        )
                    ),
                    shape
                )
                .padding(horizontal = 7.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                content()
            }
        }
    }
}

@Composable
fun HioneNowPlayingBadge(
    bpm: Float,
    isPlaying: Boolean,
    beatIntervalMs: Long,
    onClick: () -> Unit
) {
    // 0 = no real tempo; rendered as "—", never a presumed 128.
    val liveBpm = if (bpm in 40f..260f) bpm.toInt() else 0
    val tierColor = when {
        !isPlaying || liveBpm == 0 -> Color(0xFF8BA6A9)
        liveBpm >= 150 -> com.hione.launcher.ui.HioneIdentity.Coral
        liveBpm >= 126 -> HioneNeonPink
        liveBpm >= 100 -> HioneCyan
        else -> Color(0xFF00FF7F)
    }
    val bandColors = listOf(Color(0xFF00FF7F), HioneCyan, Mascot.color(0xFFFFD08A), HioneNeonPink, com.hione.launcher.ui.HioneIdentity.Coral)
    val nBars = bandColors.size
    val interval = beatIntervalMs.coerceIn(250L, 1500L).toInt()

    val lowPowerGate by com.hione.launcher.ui.rememberAmbientGate()

    val eq = rememberInfiniteTransition(label = "eqBadge")
    val beatPhase by eq.gatedFloat(lowPowerGate, 
        0f, 1f, infiniteRepeatable(tween(interval, easing = LinearEasing), RepeatMode.Restart), label = "beat"
    )
    val beatEnv = if (isPlaying) (1f - beatPhase) * (1f - beatPhase) else 0f
    val osc = (0 until nBars).map { i ->
        eq.gatedFloat(lowPowerGate, 
            0f, 1f,
            infiniteRepeatable(tween(200 + i * 85, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "osc$i"
        )
    }

    CyberBespokeBadge(
        onClick = onClick,
        accentColor = tierColor,
        gradient = listOf(tierColor.copy(alpha = if (isPlaying) 0.32f else 0.14f), Mascot.color(0xFF120410)),
        shape = CutCornerShape(5.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            modifier = Modifier.height(13.dp)
        ) {
            for (i in 0 until nBars) {
                val bassBias = 1f - i.toFloat() / nBars
                val base = if (isPlaying) 0.22f + osc[i].value * (0.30f + 0.25f * (1f - bassBias)) else 0.14f
                val h = (base + beatEnv * (0.20f + 0.60f * bassBias)).coerceIn(0.10f, 1f)
                Box(
                    Modifier.width(2.5.dp).height((13 * h).dp)
                        .clip(RoundedCornerShape(0.6.dp))
                        .background(if (isPlaying) bandColors[i] else bandColors[i].copy(alpha = 0.4f))
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (isPlaying) (if (liveBpm > 0) "$liveBpm" else "—") else "BPM",
            color = if (isPlaying) Color.White else tierColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = AudiowideFont,
            maxLines = 1
        )
    }
}

/**
 * Ultra-Modern 3D Embossed Cyber Glass Capsule Pill.
 * Features animated shimmering holographic plasma edges and specular lighting.
 */
@Composable
fun Cyber3dEmbossedPill(
    onClick: (() -> Unit)? = null,
    borderColor: Color = CyberGlassBorder,
    glowGradient: List<Color> = listOf(Mascot.color(0xEE0E242C), Mascot.color(0xFF041015)),
    content: @Composable RowScope.() -> Unit
) {
    val lowPowerGate by com.hione.launcher.ui.rememberAmbientGate()
    val infiniteTransition = rememberInfiniteTransition(label = "PillShimmer")
    val shimmerAlpha by infiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ShimmerAlpha"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.18f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.55f)
                    )
                )
            )
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            )
    ) {
        Box(
            modifier = Modifier
                .padding(1.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(glowGradient))
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                borderColor.copy(alpha = shimmerAlpha),
                                Mascot.color(0x22FFC56B),
                                borderColor.copy(alpha = shimmerAlpha * 0.8f)
                            )
                        )
                    ),
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                content()
            }
        }
    }
}

/**
 * Adorable Kawaii Rolling Bounce Number Transition for Clock Digits.
 * Implements smooth spring-damped vertical roll with playful scale pop overshoot.
 */
@Composable
fun KawaiiAnimatedDigitPair(
    digits: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 32.sp,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        digits.forEachIndexed { idx, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    (slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    ) { height -> -height } + fadeIn(tween(180)) + scaleIn(
                        initialScale = 0.65f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                    )) togetherWith (slideOutVertically(
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) { height -> height } + fadeOut(tween(160)) + scaleOut(
                        targetScale = 1.18f,
                        animationSpec = tween(220)
                    ))
                },
                label = "KawaiiDigit_${idx}_$char"
            ) { targetChar ->
                Text(
                    text = targetChar.toString(),
                    color = color,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

/**
 * Computes ultra-smooth 24-bit Truecolor spectrum shifting using 360-degree HSV space.
 */
fun cyber24BitColorShift(phaseDeg: Float, saturation: Float = 0.88f, brightness: Float = 1.0f, alpha: Float = 1.0f): Color {
    val normHue = (phaseDeg % 360f + 360f) % 360f
    val hsv = floatArrayOf(normHue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    val argb = android.graphics.Color.HSVToColor((alpha.coerceIn(0f, 1f) * 255).toInt(), hsv)
    return Color(argb)
}

@Composable
fun KawaiiHeartColon(
    color: Color,
    scale: Float,
    size: androidx.compose.ui.unit.Dp = 9.5.dp,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .padding(horizontal = 3.dp)
            .scale(scale)
    ) {
        CyberHeartGlyph(color = color, size = size)
        CyberHeartGlyph(color = color, size = size)
    }
}

@Composable
fun CyberHeartGlyph(
    color: Color,
    size: androidx.compose.ui.unit.Dp = 9.5.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.88f)
            cubicTo(w * 0.08f, h * 0.58f, 0f, h * 0.38f, 0f, h * 0.24f)
            cubicTo(0f, h * 0.08f, w * 0.18f, 0f, w * 0.36f, 0f)
            cubicTo(w * 0.44f, 0f, w * 0.5f, h * 0.08f, w * 0.5f, h * 0.16f)
            cubicTo(w * 0.5f, h * 0.08f, w * 0.56f, 0f, w * 0.64f, 0f)
            cubicTo(w * 0.82f, 0f, w, h * 0.08f, w, h * 0.24f)
            cubicTo(w, h * 0.38f, w * 0.92f, h * 0.58f, w * 0.5f, h * 0.88f)
            close()
        }
        drawPath(path = path, color = color)
    }
}

/**
 * Oki Hione Ephemeral Cyber Plasma Glow Clock.
 * Features 24-bit continuous Truecolor spectrum shifting across digits and colon,
 * kawaii rolling-bounce number transitions, animated floating cyber stardust particles,
 * sweeping holographic laser scanlines, 5-layer volumetric bloom, chromatic aberration fringe,
 * 1-second cadence heartbeat colon, and 6-channel reactive phosphor spectrum bars.
 */
@Composable
fun CyberPlasmaGlowClock(
    time: String,
    date: String
) {
    val lowPowerGate by com.hione.launcher.ui.rememberAmbientGate()
    val infiniteTransition = rememberInfiniteTransition(label = "ClockPlasmaPulse")

    // Continuous 24-Bit Truecolor Spectrum Shift Engine (Rotates 360 degrees smoothly)
    val colorShiftPhase by infiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "24BitColorShift"
    )

    // Real system wall-clock second tick (0 or 1)
    val secondTick by produceState(initialValue = (System.currentTimeMillis() / 1000) % 2) {
        while (true) {
            com.hione.launcher.ui.HionePowerProfile.awaitVisible()
            val sec = (System.currentTimeMillis() / 1000)
            value = sec % 2
            val msToNextSec = 1000L - (System.currentTimeMillis() % 1000L)
            delay(msToNextSec.coerceIn(50L, 1000L))
        }
    }

    // Snappy tactile heartbeat scale bounce on every single second tick
    val heartbeatScale = remember { Animatable(1.0f) }
    val npAccentClock = com.hione.launcher.ui.rememberNpAccent()
    val lowPowerClock by com.hione.launcher.ui.rememberLowPower()
    val bpmClock by com.hione.launcher.bpm.HioneBpmEngine.state.collectAsState()
    val beatSynced = bpmClock.isPlaying && bpmClock.beatIntervalMs in 250L..1500L && !lowPowerClock
    // Idle: heartbeat on the second tick. Music playing: the hearts pulse ON THE BEAT.
    // Low-power profile (audio_only / idle): no pulse at all.
    LaunchedEffect(secondTick, beatSynced, lowPowerClock) {
        if (beatSynced || lowPowerClock) return@LaunchedEffect
        heartbeatScale.snapTo(1.32f)
        heartbeatScale.animateTo(1.0f, tween(durationMillis = 380, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(beatSynced, bpmClock.beatIntervalMs, bpmClock.lastPulseEpochMs) {
        if (!beatSynced) return@LaunchedEffect
        val interval = bpmClock.beatIntervalMs
        val anchor = if (bpmClock.lastPulseEpochMs > 0L) bpmClock.lastPulseEpochMs else System.currentTimeMillis()
        while (true) {
            com.hione.launcher.ui.HionePowerProfile.awaitVisible()
            val now = System.currentTimeMillis()
            val toNext = interval - ((now - anchor) % interval)
            delay(toNext.coerceIn(10L, interval))
            heartbeatScale.snapTo(1.30f)
            heartbeatScale.animateTo(1.0f, tween((interval * 0.6f).toInt().coerceIn(120, 380), easing = FastOutSlowInEasing))
        }
    }

    // 24-Bit Smooth Gradient Colors for Numbers and Integrated Colon Shift:
    val hoursShiftColor = cyber24BitColorShift(colorShiftPhase, saturation = 0.85f, brightness = 1.0f)
    val hoursCoreColor = cyber24BitColorShift(colorShiftPhase, saturation = 0.12f, brightness = 1.0f)

    // Integrated Colon: Seamlessly flowing with dynamic harmonic offset on second tick
    val colonHarmonicOffset = if (secondTick == 0L) 25f else 165f
    val colonHue = colorShiftPhase + colonHarmonicOffset
    val colonColor = com.hione.launcher.ui.HioneNowPlayingAccent.blend(
        cyber24BitColorShift(colonHue, saturation = 0.95f, brightness = 1.0f),
        npAccentClock.full, if (npAccentClock.active) 0.45f else 0f
    )
    val colonHaloColor = cyber24BitColorShift(colonHue + 20f, saturation = 0.85f, brightness = 0.95f)

    // Minutes digit: Phase offset for continuous flowing gradient across the face
    val minutesShiftColor = cyber24BitColorShift(colorShiftPhase + 50f, saturation = 0.85f, brightness = 1.0f)
    val minutesCoreColor = cyber24BitColorShift(colorShiftPhase + 50f, saturation = 0.12f, brightness = 1.0f)

    // Breathing plasma halo alpha
    val glowAlpha by infiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = 0.40f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PlasmaAlpha"
    )

    // Chromatic aberration fringe phase
    val shimmerPhase by infiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = -2.8f,
        targetValue = 2.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ChromaticPhase"
    )

    // Particle drift animation phase (0..1 continuous)
    val particlePhase by infiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ParticlePhase"
    )

    // Sweeping holographic scanline Y position
    val scanlineY by infiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ScanlineY"
    )

    // 6-Band animated micro equalizer
    val bar1 by infiniteTransition.gatedFloat(lowPowerGate, initialValue = 0.25f, targetValue = 0.95f, animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "b1")
    val bar2 by infiniteTransition.gatedFloat(lowPowerGate, initialValue = 0.85f, targetValue = 0.20f, animationSpec = infiniteRepeatable(tween(580), RepeatMode.Reverse), label = "b2")
    val bar3 by infiniteTransition.gatedFloat(lowPowerGate, initialValue = 0.35f, targetValue = 1.0f, animationSpec = infiniteRepeatable(tween(340), RepeatMode.Reverse), label = "b3")
    val bar4 by infiniteTransition.gatedFloat(lowPowerGate, initialValue = 0.90f, targetValue = 0.45f, animationSpec = infiniteRepeatable(tween(510), RepeatMode.Reverse), label = "b4")
    val bar5 by infiniteTransition.gatedFloat(lowPowerGate, initialValue = 0.20f, targetValue = 0.80f, animationSpec = infiniteRepeatable(tween(390), RepeatMode.Reverse), label = "b5")
    val bar6 by infiniteTransition.gatedFloat(lowPowerGate, initialValue = 0.70f, targetValue = 0.30f, animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse), label = "b6")

    val timeParts = remember(time) {
        if (time.contains(":")) {
            val idx = time.indexOf(":")
            Pair(time.substring(0, idx), time.substring(idx + 1))
        } else {
            Pair(time, "")
        }
    }

    Column {
        Box(contentAlignment = Alignment.CenterStart) {
            // Layer 1: Ambient Floating Cyber Particles & Stardust Canvas
            val particles = remember {
                listOf(
                    Triple(0.12f, 0.25f, Mascot.color(0xFFFFC56B)),
                    Triple(0.28f, 0.65f, Mascot.color(0xFFFF007F)),
                    Triple(0.45f, 0.15f, Mascot.color(0xFF00FFCC)),
                    Triple(0.58f, 0.80f, Color(0xFFFFFFFF)),
                    Triple(0.72f, 0.35f, Mascot.color(0xFFFFC56B)),
                    Triple(0.85f, 0.70f, Mascot.color(0xFFFF007F)),
                    Triple(0.92f, 0.20f, com.hione.launcher.ui.HioneIdentity.Leek),
                    Triple(0.38f, 0.90f, Mascot.color(0xFFFFC56B)),
                    Triple(0.65f, 0.45f, com.hione.launcher.ui.HioneIdentity.Gold)
                )
            }
            Canvas(
                modifier = Modifier
                    .size(width = 135.dp, height = 40.dp)
            ) {
                particles.forEachIndexed { i, p ->
                    val baseX = p.first * size.width
                    val baseY = p.second * size.height
                    val driftY = (baseY - (particlePhase * size.height) + (i * 12f)) % size.height
                    val wobbleX = baseX + (kotlin.math.sin((particlePhase * 6.28f) + i) * 6f).toFloat()
                    val pAlpha = (kotlin.math.sin((particlePhase * 3.14f) + (i * 0.7f)).toFloat().coerceIn(0.15f, 0.9f)) * glowAlpha
                    val radius = if (i % 2 == 0) 2.2f else 1.5f

                    drawCircle(
                        color = p.third.copy(alpha = pAlpha),
                        radius = radius,
                        center = Offset(wobbleX, driftY)
                    )
                }

                // Sweeping Holographic Cyber Laser Scanline
                val scanY = scanlineY * size.height
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            hoursShiftColor.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.75f),
                            minutesShiftColor.copy(alpha = 0.45f),
                            Color.Transparent
                        )
                    ),
                    start = Offset(0f, scanY),
                    end = Offset(size.width, scanY),
                    strokeWidth = 1.2f
                )
            }

            // Layer 2: Radiant Volumetric 24-Bit Plasma Halo
            Box(
                Modifier
                    .offset(x = 1.dp, y = 2.dp)
                    .size(width = 135.dp, height = 40.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                hoursShiftColor.copy(alpha = glowAlpha * 0.65f),
                                minutesShiftColor.copy(alpha = glowAlpha * 0.30f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Layer 3: Chromatic Aberration Fringe (24-Bit Spectrum Offset 1)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = (-1.2).dp + (shimmerPhase * 0.25f).dp, y = (-0.7).dp)
            ) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = cyber24BitColorShift(colorShiftPhase + 180f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.80f),
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonHaloColor.copy(alpha = 0.9f),
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = cyber24BitColorShift(colorShiftPhase + 230f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.80f),
                    fontSize = 36.sp
                )
            }

            // Layer 4: Chromatic Aberration Fringe (24-Bit Spectrum Offset 2)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = (1.2).dp - (shimmerPhase * 0.25f).dp, y = (0.9).dp)
            ) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = cyber24BitColorShift(colorShiftPhase + 290f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.70f),
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonHaloColor.copy(alpha = 0.85f),
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = cyber24BitColorShift(colorShiftPhase + 340f, saturation = 0.9f, brightness = 0.95f, alpha = glowAlpha * 0.70f),
                    fontSize = 36.sp
                )
            }

            // Layer 5: Intense 24-Bit Neon Under-Glow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = 0.6.dp, y = 0.6.dp)
            ) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = hoursShiftColor.copy(alpha = 0.95f),
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonColor,
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = minutesShiftColor.copy(alpha = 0.95f),
                    fontSize = 36.sp
                )
            }

            // Layer 6: Brilliant 24-Bit Holographic Luminous Core with Heartbeat Colon
            Row(verticalAlignment = Alignment.CenterVertically) {
                KawaiiAnimatedDigitPair(
                    digits = timeParts.first,
                    color = hoursCoreColor,
                    fontSize = 36.sp
                )
                KawaiiHeartColon(
                    color = colonColor,
                    scale = heartbeatScale.value
                )
                KawaiiAnimatedDigitPair(
                    digits = timeParts.second,
                    color = minutesCoreColor,
                    fontSize = 36.sp
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        // Date & Holographic Spectrum Wave Sub-header with Multi-Layer Volumetric Drop-Glow
        Box(contentAlignment = Alignment.CenterStart) {
            // Layer 1: Radiant Neon Drop-Glow Shadow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = 0.8.dp, y = 0.8.dp)
            ) {
                val dateParts = date.split("/")
                if (dateParts.size == 3) {
                    Text(
                        text = dateParts[0],
                        color = HioneCyan.copy(alpha = glowAlpha * 0.7f),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = " / ",
                        color = HioneNeonPink.copy(alpha = glowAlpha * 0.75f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = dateParts[1],
                        color = Mascot.color(0xFF56F0E0).copy(alpha = glowAlpha * 0.7f),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = " / ",
                        color = HioneNeonPink.copy(alpha = glowAlpha * 0.75f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = dateParts[2],
                        color = Mascot.color(0xFFB9C2D0).copy(alpha = glowAlpha * 0.7f),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Layer 2: Vivid Luminous Core Surface
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dateParts = date.split("/")
                if (dateParts.size == 3) {
                    Text(
                        text = dateParts[0],
                        color = Mascot.color(0xFF80FFFF),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = " / ",
                        color = Mascot.color(0xFFFF69B4),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = dateParts[1],
                        color = Mascot.color(0xFF70FFF0),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = " / ",
                        color = Mascot.color(0xFFFF69B4),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = dateParts[2],
                        color = Color(0xFFD1B3FF),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                } else {
                    Text(
                        text = date,
                        color = HioneCyan,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(Modifier.width(8.dp))

                // 6-Channel Mini Audio Spectrum Phosphor Bars
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                    modifier = Modifier.height(11.dp)
                ) {
                    Box(Modifier.width(2.dp).height((11 * bar1).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(HioneCyan, Mascot.color(0xFF00FFCC)))))
                    Box(Modifier.width(2.dp).height((11 * bar2).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(HioneNeonPink, Mascot.color(0xFF6FB0FF)))))
                    Box(Modifier.width(2.dp).height((11 * bar3).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(Color(0xFF00FF7F), com.hione.launcher.ui.HioneIdentity.Leek))))
                    Box(Modifier.width(2.dp).height((11 * bar4).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(HioneCyan, Color(0xFF00B0FF)))))
                    Box(Modifier.width(2.dp).height((11 * bar5).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(HioneNeonPink, Mascot.color(0xFFFF007F)))))
                    Box(Modifier.width(2.dp).height((11 * bar6).dp).clip(RoundedCornerShape(0.5.dp)).background(Brush.verticalGradient(listOf(Mascot.color(0xFF00FFCC), HioneCyan))))
                }
            }
        }
    }
}

/**
 * Oki Hione Full Cyber Notification Shade & Quick Settings Modal.
 * Features 3D embossed quick hardware tiles, Now Playing telemetry,
 * Ingress sync stream card, and Data-Only SIM Shield indicator.
 */
@Composable
fun CyberNotificationShadeModal(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAudioSettings: () -> Unit,
    batteryPct: Int,
    isCharging: Boolean,
    isWifiConnected: Boolean,
    currentTime: String,
    currentDate: String,
    onOpenProfile: () -> Unit = {}
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Mascot.color(0xE60C0D10))
            .clickable { onClose() }
    ) {
        // High-Tech Cyber Glass Container (Safe screen inset with smooth corners)
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 8.dp, end = 8.dp, bottom = 6.dp)
                .clickable(enabled = false) {}
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Mascot.color(0xFF071922),
                            Mascot.color(0xFF041017),
                            Mascot.color(0xFA020A0E)
                        )
                    )
                )
                .border(
                    BorderStroke(
                        1.2.dp,
                        Brush.linearGradient(
                            listOf(
                                HioneCyan.copy(alpha = 0.85f),
                                CyberGlassBorder.copy(alpha = 0.4f),
                                HioneNeonPink.copy(alpha = 0.65f)
                            )
                        )
                    ),
                    RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Grabber handle — swipe UP or tap to dismiss the shade. This is the fix for the
                // "soundboard can't be dismissed at all" trap: the scrim tap was swallowed by the
                // container's disabled-clickable, there was no drag-to-dismiss, and the ✕ CLOSE
                // button sat scrolled off the bottom. A fixed grabber at the top gives a reliable,
                // discoverable close that never scrolls away.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -4f) onClose()
                            }
                        },
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .padding(bottom = 8.dp)
                            .width(46.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(HioneCyan.copy(alpha = 0.7f))
                            .clickable { onClose() }
                    )
                }

                // Top Control Header: Clock, Battery Pill & Settings
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CyberPlasmaGlowClock(time = currentTime, date = currentDate)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Battery Chip
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Mascot.color(0xFF061820))
                                .border(0.8.dp, HioneCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                    contentDescription = null,
                                    tint = if (isCharging) com.hione.launcher.ui.HioneIdentity.Leek else HioneCyan,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    // -1 = level unreadable; "—%" rather than a fabricated 0 %.
                                    if (batteryPct >= 0) "$batteryPct%" else "—%",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = AudiowideFont
                                )
                            }
                        }

                        // Open Hione Cyber Settings Gear Button
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Mascot.color(0x33FFC56B))
                                .border(1.dp, HioneCyan, CircleShape)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = HioneCyan, modifier = Modifier.size(16.dp))
                        }

                        // User Profile Button
                        IconButton(
                            onClick = onOpenProfile,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Mascot.color(0x336FB0FF))
                                .border(1.dp, HioneNeonPink, CircleShape)
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Profile", tint = HioneNeonPink, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                CyberShadeConnectivitySection(isWifiConnected = isWifiConnected)

                Spacer(Modifier.height(10.dp))

                CyberShadeSliderSection()

                Spacer(Modifier.height(10.dp))

                CyberShadeSoundboardSection(isWifiConnected = isWifiConnected, onClose = onClose)

                Spacer(Modifier.height(10.dp))

                CyberShadeFooterSection(onClose = onClose)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CyberQuickTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val tileShape = remember { RoundedCornerShape(com.hione.launcher.ui.HioneDimens.cornerM) }

    // Outer 3D Embossed Container with Specular Top Bevel
    Box(
        modifier = modifier
            .clip(tileShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (isActive) 0.25f else 0.12f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.6f)
                    )
                )
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        // Inner Elevated Holographic Card Surface
        Box(
            modifier = Modifier
                .fillMaxSize()
                .heightIn(min = 76.dp)
                .padding(0.9.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        if (isActive) listOf(accentColor.copy(alpha = 0.35f), Mascot.color(0xFF071922), Mascot.color(0xFF030D12))
                        else listOf(Mascot.color(0xEE0B222C), Mascot.color(0xFF05131A), Mascot.color(0xFF02090D))
                    )
                )
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            if (isActive) listOf(
                                accentColor.copy(alpha = 0.95f),
                                Mascot.color(0xFFB9C2D0).copy(alpha = 0.6f),
                                accentColor.copy(alpha = 0.8f)
                            )
                            else listOf(
                                CyberGlassBorder.copy(alpha = 0.7f),
                                Color.Transparent,
                                CyberGlassBorder.copy(alpha = 0.4f)
                            )
                        )
                    ),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 3D Glowing Icon Halo
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (isActive) accentColor.copy(alpha = 0.22f) else Color(0x18FFFFFF))
                        .border(
                            0.8.dp,
                            if (isActive) accentColor.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.2f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = title,
                        tint = if (isActive) accentColor else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 11.5.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = AudiowideFont,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        color = if (isActive) accentColor else HioneTextSecondary,
                        fontSize = HioneDimens.textXs,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}



/**
 * Dual Internet / Bluetooth connectivity pills of the notification shade.
 *
 * Extracted out of [CyberNotificationShadeModal] because that composable compiled to
 * ~18k dex instructions — past ART's 16384-instruction JIT ceiling — so it was refused
 * by the JIT and re-interpreted on every recomposition, pinning the main thread.
 * Behaviour, ordering and modifiers are identical to the inlined version; the network
 * telemetry collection and the Bluetooth-sink lookup simply live here now, next to
 * their only consumer.
 */
@Composable
private fun CyberShadeConnectivitySection(isWifiConnected: Boolean) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager }
    // Real radio telemetry (HioneNetworkService polls TelephonyManager/WifiManager). Before the
    // first poll every field is blank/false, so the subtitles below read "Offline"/"—", never a
    // fabricated "5GHz Wi-Fi + LTE".
    val netState by com.hione.launcher.network.HioneNetworkService.state.collectAsState()

    // REAL Bluetooth audio state. The card used to claim "LDAC Hi-Res 990k" unconditionally — even
    // with the radio off and nothing paired, and the active codec is not readable unprivileged.
    // AudioManager's output-device list is the real source: it reports an A2DP/LE/SCO sink only when
    // one is actually connected, and carries its product name.
    val btAudioLabel = remember(am) {
        runCatching {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            when {
                adapter == null -> "No Bluetooth radio"
                !adapter.isEnabled -> "Off"
                else -> {
                    val sink = am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                        ?.firstOrNull {
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
                        }
                    val name = sink?.productName?.toString()?.trim().orEmpty()
                    when {
                        sink == null -> "On · no audio device"
                        name.isNotEmpty() -> name
                        else -> "On · audio device connected"
                    }
                }
            }
        }.getOrDefault("—")
    }

    // Pixel-Style Large Dual Network / Bluetooth Connectivity Pills
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Internet Card (Wi-Fi + 4G LTE)
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isWifiConnected) HioneCyan.copy(alpha = 0.2f) else Mascot.color(0xFF081820))
                .border(1.dp, if (isWifiConnected) HioneCyan else Mascot.color(0xFF1E3A45), RoundedCornerShape(16.dp))
                .clickable {
                    try { ctx.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS)) } catch (_: Throwable) {}
                }
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isWifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = if (isWifiConnected) HioneCyan else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Internet", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    // Derived from the real Wi-Fi/cellular telemetry — SSID + the band the
                    // radio actually reports, or the real mobile radio technology. The old
                    // text asserted "5GHz Wi-Fi + LTE" / "LTE Connected" regardless.
                    val internetSubtitle = run {
                        val w = netState.wifi
                        val c = netState.cellular
                        when {
                            w.isConnected -> listOf(
                                w.ssid.ifBlank { "Wi-Fi" },
                                w.bandLabel
                            ).filter { it.isNotBlank() }.joinToString(" · ")
                            c.dataConnected -> c.networkType.ifBlank { "Mobile data" }
                            c.hasSignal -> "No mobile data"
                            netState.lastUpdated == 0L -> "—"
                            else -> "Offline"
                        }
                    }
                    Text(internetSubtitle, color = HioneCyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Bluetooth Card (LDAC Hi-Res)
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0A1828))
                .border(1.dp, Color(0xFF2979FF).copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .clickable {
                    try {
                        val intent = Intent().setClassName("com.hione.settings", "com.hione.settings.HioneSettingsActivity").apply {
                            putExtra("extra_section", "bluetooth")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
                    } catch (_: Throwable) {
                        try { ctx.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS)) } catch (_: Throwable) {}
                    }
                }
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    // Dimmed when the radio is off / absent, so the glyph cannot imply a
                    // live link the label denies.
                    tint = if (btAudioLabel == "Off" || btAudioLabel == "No Bluetooth radio" || btAudioLabel == "—")
                        Color.Gray else Color(0xFF2979FF),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Bluetooth", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    Text(btAudioLabel, color = Color(0xFF82B1FF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Brightness + master-volume slider rows of the notification shade.
 *
 * Extracted out of [CyberNotificationShadeModal]: the parent exceeded ART's 16384-dex-
 * instruction JIT limit and therefore ran interpreted on every recomposition. The
 * slider state is only ever read and written here, so it moved down with the UI.
 */
@Composable
private fun CyberShadeSliderSection() {
    val ctx = LocalContext.current
    // Brightness Controller
    val cr = ctx.contentResolver
    var brightness by remember {
        mutableStateOf(
            try {
                android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Throwable) { 128 }
        )
    }

    // Volume Controller
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager }
    var streamVol by remember {
        mutableStateOf(am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 8)
    }
    val maxVol = remember { am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15 }

    // Interactive Haptic Brightness Slider (Pixel-Style AOSP Bar)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Mascot.color(0xFF081C24))
            .border(1.dp, HioneCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.BrightnessMedium, contentDescription = null, tint = HioneCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Slider(
                value = brightness.toFloat(),
                onValueChange = { newB ->
                    brightness = newB.toInt()
                    try {
                        android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, brightness)
                    } catch (_: Throwable) {}
                },
                valueRange = 10f..255f,
                colors = SliderDefaults.colors(
                    thumbColor = HioneCyan,
                    activeTrackColor = HioneCyan,
                    inactiveTrackColor = Mascot.color(0xFF0D2C35)
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // Master Audio Output & Volume Slider
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A1420))
            .border(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Mascot.color(0xFFB9C2D0), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Slider(
                value = streamVol.toFloat(),
                onValueChange = { newV ->
                    streamVol = newV.toInt()
                    try {
                        am?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, streamVol, 0)
                    } catch (_: Throwable) {}
                },
                valueRange = 0f..maxVol.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Mascot.color(0xFFB9C2D0),
                    activeTrackColor = Color(0xFF7C4DFF),
                    inactiveTrackColor = Color(0xFF140D26)
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text("${((streamVol.toFloat() / maxVol.toFloat()) * 100).toInt()}%", color = Mascot.color(0xFFB9C2D0), fontSize = 11.sp, fontFamily = AudiowideFont)
        }
    }
}

/**
 * "DAC" quick-tile grid of the notification shade.
 *
 * Extracted out of [CyberNotificationShadeModal]: that function compiled to ~18k dex
 * instructions, over ART's 16384 JIT ceiling, so it was never JIT-compiled and every
 * recomposition was interpreted on the main thread. The DAC/pulsar tile state is
 * used nowhere else, so it is remembered here.
 */
@Composable
private fun CyberShadeSoundboardSection(
    isWifiConnected: Boolean,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Hardware Audio States
    var gainMode by remember { mutableStateOf(CirrusLogicManager.getGainMode(ctx)) }
    var filterMode by remember { mutableStateOf(CirrusLogicManager.getDigitalFilter(ctx)) }
    var dreEnabled by remember { mutableStateOf(CirrusLogicManager.isDreEnabled(ctx)) }
    // Provenance of each tile's value, not a bare "known" boolean. These used to be set to `true`
    // the instant the user tapped, which claimed the DAC had been verified when all that had
    // happened was a write attempt (and on HioneOS the sysfs write needs su and normally fails).
    // null = nothing reports it; USER_REQUEST = only our own saved request; SYSFS/VENDOR_SETTING =
    // a real read. Tapping sets USER_REQUEST, and the real source is re-read after the apply.
    var gainSource by remember { mutableStateOf(CirrusLogicManager.readGainMode(ctx)?.source) }
    var filterSource by remember { mutableStateOf(CirrusLogicManager.readDigitalFilter(ctx)?.source) }
    var dreSource by remember { mutableStateOf(CirrusLogicManager.readDre(ctx)?.source) }
    val gainKnown = gainSource != null
    val filterKnown = filterSource != null
    val dreKnown = dreSource != null
    fun sourceNote(src: CirrusLogicManager.Source?): String = when (src) {
        CirrusLogicManager.Source.SYSFS -> ""
        CirrusLogicManager.Source.VENDOR_SETTING -> "  (HAL setting)"
        CirrusLogicManager.Source.USER_REQUEST -> "  (requested, unverified)"
        null -> ""
    }


    // 8 Cyber Quick Hardware Tiles (2x4 Grid). The tiles are quick toggles; "All settings"
    // opens the Hardware app's DAC page, the only full DAC page on HioneOS.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "DAC",
            color = HioneCyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = AudiowideFont,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "All settings",
            color = HioneCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable { if (DacSettingsLink.open(ctx)) onClose() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }

    Spacer(Modifier.height(6.dp))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Row 1: CS43198 Gain + Filter Mode
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CyberQuickTile(
                modifier = Modifier.weight(1f),
                title = "GAIN",
                subtitle = if (!gainKnown) "Can't read gain"
                    else (if (gainMode == CirrusLogicManager.GainMode.HIGH) "HIGH" else "LOW") + sourceNote(gainSource),
                icon = Icons.Default.VolumeUp,
                accentColor = if (gainKnown && gainMode == CirrusLogicManager.GainMode.HIGH) HioneNeonPink else HioneCyan,
                isActive = gainKnown && gainMode == CirrusLogicManager.GainMode.HIGH,
                onClick = {
                    val next = if (gainMode == CirrusLogicManager.GainMode.LOW) CirrusLogicManager.GainMode.HIGH else CirrusLogicManager.GainMode.LOW
                    gainMode = next
                    // A tap is a REQUEST, never a verified hardware state.
                    gainSource = CirrusLogicManager.Source.USER_REQUEST
                    scope.launch(Dispatchers.IO) {
                        CirrusLogicManager.setGainMode(ctx, next)
                        // Re-read after the apply and report whatever really answers now.
                        val back = CirrusLogicManager.readGainMode(ctx)
                        withContext(Dispatchers.Main) {
                            gainSource = back?.source
                            if (back != null) gainMode = back.value
                        }
                    }
                }
            )

            CyberQuickTile(
                modifier = Modifier.weight(1f),
                title = "FILTER",
                subtitle = if (filterKnown) filterMode.label + sourceNote(filterSource) else "Can't read filter",
                icon = Icons.Default.Tune,
                accentColor = HioneCyan,
                // Only a real read lights the tile; a value we merely requested must not.
                isActive = filterSource == CirrusLogicManager.Source.SYSFS ||
                    filterSource == CirrusLogicManager.Source.VENDOR_SETTING,
                onClick = {
                    val all = CirrusLogicManager.DigitalFilter.values()
                    val next = all[(filterMode.ordinal + 1) % all.size]
                    filterMode = next
                    filterSource = CirrusLogicManager.Source.USER_REQUEST
                    scope.launch(Dispatchers.IO) {
                        CirrusLogicManager.setDigitalFilter(ctx, next)
                        val back = CirrusLogicManager.readDigitalFilter(ctx)
                        withContext(Dispatchers.Main) {
                            filterSource = back?.source
                            if (back != null) filterMode = back.value
                        }
                    }
                }
            )
        }

        // Row 2: Direct ALSA Bypass + DRE
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(modifier = Modifier.weight(1f))

            CyberQuickTile(
                modifier = Modifier.weight(1f),
                title = "DRE",
                subtitle = if (!dreKnown) "Can't read DRE"
                    else (if (dreEnabled) "ON" else "OFF") + sourceNote(dreSource),
                icon = Icons.Default.Headphones,
                accentColor = com.hione.launcher.ui.HioneIdentity.Leek,
                isActive = dreKnown && dreEnabled,
                onClick = {
                    val next = !dreEnabled
                    dreEnabled = next
                    dreSource = CirrusLogicManager.Source.USER_REQUEST
                    scope.launch(Dispatchers.IO) {
                        CirrusLogicManager.setDreEnabled(ctx, next)
                        val back = CirrusLogicManager.readDre(ctx)
                        withContext(Dispatchers.Main) {
                            dreSource = back?.source
                            if (back != null) dreEnabled = back.value
                        }
                    }
                }
            )
        }

        // Row 3: Wireless ADB + System Tools
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val adbIp = remember(isWifiConnected) {
                try {
                    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val ipInt = wm?.connectionInfo?.ipAddress ?: 0
                    if (ipInt != 0) {
                        String.format(
                            Locale.US,
                            "%d.%d.%d.%d",
                            ipInt and 0xff,
                            ipInt shr 8 and 0xff,
                            ipInt shr 16 and 0xff,
                            ipInt shr 24 and 0xff
                        )
                    } else ""
                } catch (_: Throwable) { "" }
            }
            var isAdbEnabled by remember {
                mutableStateOf(
                    try {
                        val p = Runtime.getRuntime().exec("getprop service.adb.tcp.port")
                        p.inputStream.bufferedReader().readText().trim() == "5555"
                    } catch (_: Throwable) { false }
                )
            }

            CyberQuickTile(
                modifier = Modifier.weight(1f),
                title = "WIRELESS ADB",
                subtitle = if (isAdbEnabled) "$adbIp:5555" else "PORT 5555",
                icon = Icons.Default.DeveloperMode,
                accentColor = if (isAdbEnabled) com.hione.launcher.ui.HioneIdentity.Leek else HioneNeonPink,
                isActive = isAdbEnabled,
                onClick = {
                    val next = !isAdbEnabled
                    isAdbEnabled = next
                    scope.launch(Dispatchers.IO) {
                        // persist.adb.tcp.port, NOT service.adb.tcp.port — the service.
                        // prop flips adbd TCP-ONLY and kills USB adb (bit us hard once);
                        // persist. adds TCP alongside USB and survives reboots.
                        val cmd = if (next) {
                            "setprop persist.adb.tcp.port 5555 && stop adbd && start adbd"
                        } else {
                            "setprop persist.adb.tcp.port -1 && stop adbd && start adbd"
                        }
                        try {
                            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
                        } catch (_: Throwable) {}
                    }
                    android.widget.Toast.makeText(
                        ctx,
                        if (next) "Wireless ADB on at $adbIp:5555" else "Wireless ADB off",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            )

            CyberQuickTile(
                modifier = Modifier.weight(1f),
                title = "DEVELOPER",
                subtitle = "SYSTEM AND TOOLS",
                icon = Icons.Default.Build,
                accentColor = HioneCyan,
                isActive = true,
                onClick = {
                    onClose()
                    val intent = ctx.packageManager.getLaunchIntentForPackage("com.hione.settings")?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Pause-on-unplug — mirrors the toggle in Hione Music's Sound Settings page
            // (quick settings + sound page only; deliberately NOT in the volume modal).
            // State lives in Settings.Global "hione_pause_on_unplug"; the broadcast lets
            // the player apply it live to the running ExoPlayer.
            var pauseOnUnplug by remember {
                mutableStateOf(
                    android.provider.Settings.Global.getInt(ctx.contentResolver, "hione_pause_on_unplug", 1) == 1
                )
            }
            CyberQuickTile(
                modifier = Modifier.weight(1f),
                title = "PAUSE ON UNPLUG",
                subtitle = if (pauseOnUnplug) "STOCK BEHAVIOR" else "KEEP PLAYING",
                icon = Icons.Default.HeadsetOff,
                accentColor = if (pauseOnUnplug) com.hione.launcher.ui.HioneIdentity.Leek else HioneNeonPink,
                isActive = pauseOnUnplug,
                onClick = {
                    val next = !pauseOnUnplug
                    pauseOnUnplug = next
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            android.provider.Settings.Global.putInt(
                                ctx.contentResolver, "hione_pause_on_unplug", if (next) 1 else 0
                            )
                        }.getOrNull() ?: RootShell.execFast(
                            "settings put global hione_pause_on_unplug ${if (next) 1 else 0}"
                        )
                    }
                    ctx.sendBroadcast(
                        Intent("com.hione.player.SET_PAUSE_ON_UNPLUG")
                            .setPackage("com.hione.player")
                            .putExtra("enabled", next)
                    )
                }
            )
            // Ingress engine (network rsync ingest) — OFF by default; while off only local
            // SD-card scan updates run. State = Settings.Global "hione_ingest_enabled";
            // the FS & Ingestion modal shows the same switch and reflects this live.
            var ingestOn by remember {
                mutableStateOf(com.hione.launcher.ingest.HioneIngestEngine.isEngineEnabled(ctx))
            }
            CyberQuickTile(
                modifier = Modifier.weight(1f),
                title = "NETWORK SYNC",
                subtitle = if (ingestOn) "RSYNC ON" else "SD CARD ONLY",
                icon = Icons.Default.Sync,
                accentColor = if (ingestOn) com.hione.launcher.ui.HioneIdentity.Leek else HioneNeonPink,
                isActive = ingestOn,
                onClick = {
                    val next = !ingestOn
                    ingestOn = next
                    com.hione.launcher.ingest.HioneIngestEngine.setEngineEnabled(ctx, next)
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // System-wide track-change popup (SystemUI draws it; Hione Music stops broadcasting
            // when 0). State = Settings.Global "hione_track_hud_enabled", default 1.
            var trackHudOn by remember {
                mutableStateOf(
                    try { android.provider.Settings.Global.getInt(ctx.contentResolver, "hione_track_hud_enabled", 1) == 1 } catch (_: Throwable) { true }
                )
            }
            CyberQuickTile(
                modifier = Modifier.weight(1f),
                title = "NOW PLAYING HUD",
                subtitle = if (trackHudOn) "TRACK POPUP ON" else "TRACK POPUP OFF",
                icon = Icons.Default.MusicNote,
                accentColor = if (trackHudOn) com.hione.launcher.ui.HioneIdentity.Leek else HioneNeonPink,
                isActive = trackHudOn,
                onClick = {
                    val next = !trackHudOn
                    trackHudOn = next
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            android.provider.Settings.Global.putInt(ctx.contentResolver, "hione_track_hud_enabled", if (next) 1 else 0)
                        }.getOrNull() ?: RootShell.execFast("settings put global hione_track_hud_enabled ${if (next) 1 else 0}")
                    }
                }
            )
            // POWER MODE: Settings.Global hione_power_mode auto → perf → save (Hione Music's
            // governor honours it); subtitle shows the live profile while in AUTO.
            val powerMode by com.hione.launcher.ui.rememberPowerMode()
            val liveProfile by com.hione.launcher.ui.rememberPowerProfile()
            CyberQuickTile(
                modifier = Modifier.weight(1f),
                title = "POWER MODE",
                subtitle = com.hione.launcher.ui.HionePowerProfile.modeGlyph(powerMode, liveProfile) + " " +
                    com.hione.launcher.ui.HionePowerProfile.modeLabel(powerMode, liveProfile),
                icon = when (powerMode) { "perf" -> Icons.Default.Bolt; "save" -> Icons.Default.DarkMode; else -> Icons.Default.AutoAwesome },
                accentColor = when (powerMode) {
                    "perf" -> com.hione.launcher.ui.HioneIdentity.PinkNeon
                    "save" -> com.hione.launcher.ui.HioneIdentity.Lavender
                    else -> com.hione.launcher.ui.HioneIdentity.Teal
                },
                isActive = powerMode != "auto",
                onClick = {
                    com.hione.launcher.ui.HionePowerProfile.cycleMode(ctx)
                    com.hione.launcher.haptics.HioneHaptics.confirm(ctx)
                },
                onLongClick = { com.hione.launcher.ui.HionePowerProfile.openGovernor(ctx) }
            )
        }
    }
}

/**
 * SIM-shield banner, weather card and power/close footer of the notification shade.
 *
 * Extracted out of [CyberNotificationShadeModal] to get the parent back under ART's
 * 16384-dex-instruction JIT limit; over that limit the method is refused by the JIT
 * and interpreted on every recomposition.
 */
@Composable
private fun CyberShadeFooterSection(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()

    // Telemetry & Weather
    val weatherState by com.hione.launcher.weather.HioneWeatherService.state.collectAsState()
    val netState by com.hione.launcher.network.HioneNetworkService.state.collectAsState()

    // Cellular status — REAL, from HioneNetworkService.
    // WAS: an unconditional green "live" banner reading
    //   "🛡️ DATA-ONLY SIM SHIELD: GOOGLE FI & IMS NAGS SUPPRESSED"
    // which asserted (a) that the SIM was a Google Fi data-only SIM and (b) that a suppression had
    // been applied. Neither was ever checked, it rendered with no SIM in the tray at all, and the
    // only thing that could apply such a change is a RootShell write that silently no-ops on this
    // (rootless) device. NOW: the carrier and SIM state we can actually read, and nothing at all
    // until the telemetry poll has run or when no SIM is present.
    val cell = netState.cellular
    if (netState.lastUpdated > 0L && cell.isConnected) {
        val liveTint = if (cell.dataConnected) com.hione.launcher.ui.HioneIdentity.Leek else Color(0xFFFFB300)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(liveTint.copy(alpha = 0.18f))
                .border(1.dp, liveTint, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(liveTint))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${cell.carrierName.ifBlank { "SIM" }} · ${cell.simStateLabel.ifBlank { "—" }}",
                    color = liveTint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // Weather & Conditions Card
    val w = weatherState.weather
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Mascot.color(0x33FFC56B))
            .border(1.dp, HioneCyan, RoundedCornerShape(16.dp))
            .padding(10.dp)
    ) {
        // WAS: rendered w.tempF / w.summary / w.feelsLikeF / humidity / wind / precip with NO
        // freshness gate, so before the first successful fetch the shade printed the model's
        // neutral defaults as readings: "0°F ·  (Feels 0°F)" and "Humidity: 0% · Wind: 0mph ·
        // Precip: 0%". A WeatherCondition is only real once lastUpdatedTime > 0.
        val wxReal = w.lastUpdatedTime > 0L
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (wxReal) w.icon.ifBlank { "🌐" } else "🌐", fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (wxReal)
                        "${w.tempF.toInt()}°F · ${w.summary.ifBlank { "—" }} (Feels ${w.feelsLikeF.toInt()}°F)"
                    else "No weather data yet",
                    color = Color.White,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AudiowideFont
                )
                Text(
                    text = if (wxReal)
                        "Humidity ${w.humidityPct}% · Wind ${w.windSpeedMph.toInt()}mph ${w.windDirectionCompass} · Rain ${w.precipitationProbPct}%"
                    else "Humidity — · Wind — · Rain —",
                    color = HioneTextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // Footer Action Bar: Power Menu (Reboot / Power Off / Dismiss)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")).waitFor()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x33FFC56B)),
            border = BorderStroke(1.dp, HioneCyan),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).height(42.dp)
        ) {
            Text("RESTART", color = HioneCyan, fontSize = 14.sp, fontFamily = AudiowideFont)
        }

        Button(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p")).waitFor()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Mascot.color(0x336FB0FF)),
            border = BorderStroke(1.dp, HioneNeonPink),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).height(42.dp)
        ) {
            Text("POWER OFF", color = HioneNeonPink, fontSize = 14.sp, fontFamily = AudiowideFont)
        }

        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0x22FFFFFF)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).height(42.dp)
        ) {
            Text("CLOSE", color = Color.White, fontSize = 14.sp, fontFamily = AudiowideFont)
        }
    }

    Spacer(Modifier.height(8.dp))
}


/**
 * THE QUILT — top-bar badge patchwork, plus the severe-weather banner under it.
 *
 * Extracted out of [HioneLauncherScreen]: that composable compiled to ~16.2k dex
 * instructions, right at ART's 16384-instruction JIT ceiling, past which a method is
 * never JIT-compiled and is re-interpreted on every recomposition (the home screen's
 * main-thread stalls). Content, order and modifiers are unchanged; the quilt's own
 * telemetry collectors simply live here now, next to their only consumer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HioneLauncherQuiltSection(
    currentTime: String,
    currentDate: String,
    isAudioPlaying: Boolean,
    npAccent: com.hione.launcher.ui.NpAccent,
    cpuTempC: Float,
    batteryTempC: Float,
    batteryPct: Int,
    isCharging: Boolean,
    topBarTheme: HioneTopBarTheme,
    quiltConfig: com.hione.launcher.ui.HioneQuiltConfig,
    quiltOrder: List<String>,
    onQuiltOrderChange: (List<String>) -> Unit,
    onOpenWeatherObservatory: () -> Unit,
    onOpenGps: () -> Unit,
    onOpenNetworkObservatory: () -> Unit,
    onOpenBpmObservatory: () -> Unit,
    onOpenFsIngest: () -> Unit,
    onOpenBrain: () -> Unit,
    onOpenThermal: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenQuickSettings: () -> Unit
) {
    val ctx = LocalContext.current
    // ============================================================
    // THE QUILT — the badge patchwork directly under the top bar (both always visible).
    // Long-press-drag a badge to rearrange; size / density / rows / backdrop live in the
    // home long-press menu → "Quilt & badges". The hearts clock stays here as a patch.
    // ============================================================
    val networkState by com.hione.launcher.network.HioneNetworkService.state.collectAsState()
    val weatherState by com.hione.launcher.weather.HioneWeatherService.state.collectAsState()
    val npBpm by com.hione.launcher.bpm.HioneBpmEngine.state.collectAsState()
    val quiltBadges = listOf(
        QuiltBadge("clock", "Hearts clock") {
            // Concert Mode (a BPM-game secret) is a double-tap on this clock. Until it is earned
            // the clock keeps a plain single tap, so nobody pays the double-tap delay for a
            // feature they do not have and the gesture cannot be stumbled on early.
            val concertEarned = rememberUnlockEnabled(com.hione.launcher.bpm.HioneSecrets.CONCERT_MODE)
            Box(
                if (concertEarned) Modifier.combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { launchClockApp(ctx) },
                    onDoubleClick = {
                        val on = !HioneConcertMode.active.value
                        HioneConcertMode.active.value = on
                        com.hione.launcher.haptics.HioneHaptics.confirm(ctx)
                        android.widget.Toast.makeText(
                            ctx,
                            if (!on) "Concert lights off" else if (isAudioPlaying) "Concert lights on" else "Concert lights on. Start some music.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                ) else Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { launchClockApp(ctx) }
            ) { CyberPlasmaGlowClock(time = currentTime, date = currentDate) }
        },
        // Full-width Hione weather TILE (Open-Meteo + optional Windy, real AQI, 6h strip,
        // 3-day row). Self-contained: owns its engine + detail sheet, no launcher state.
        QuiltBadge("wxtile", "Weather tile") {
            com.hione.launcher.widget.HioneWeatherTile(onOpenObservatory = onOpenWeatherObservatory)
        },
        QuiltBadge("gps", "GPS") {
            HioneCyberWeatherGpsBadge(
                weather = weatherState.weather,
                gps = weatherState.gps,
                onWeatherClick = onOpenWeatherObservatory,
                onGpsClick = onOpenGps,
                modifier = Modifier.width(176.dp)
            )
        },
        QuiltBadge("network", "Wi-Fi & LTE") {
            ConnectedRfNetworkCapsule(
                wifi = networkState.wifi,
                cell = networkState.cellular,
                radioStateKnown = networkState.lastUpdated != 0L,
                onClick = onOpenNetworkObservatory
            )
        },
        QuiltBadge("nowplaying", "Now playing / BPM") {
            Box(
                Modifier.then(
                    if (npAccent.active) Modifier.border(1.2.dp, npAccent.full.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    else Modifier
                )
            ) {
                HioneNowPlayingBadge(
                    bpm = npBpm.bpm,
                    isPlaying = isAudioPlaying || npBpm.isPlaying,
                    beatIntervalMs = npBpm.beatIntervalMs,
                    onClick = onOpenBpmObservatory
                )
            }
        },
        // The BPM game's own patch: highlighted and pulsing on the beat while music plays. On home
        // it opens the in-launcher game, so closing it lands right back here.
        QuiltBadge("bpmgame", "BPM game") {
            com.hione.launcher.ui.HioneBpmGameEntryPill(onClick = onOpenBpmObservatory)
        },
        QuiltBadge("dac", "CS43198 DAC") {
            CyberBespokeBadge(
                // The DAC page is the Hardware app's (the only one on HioneOS).
                onClick = { DacSettingsLink.open(ctx) },
                accentColor = Color(0xFF7C4DFF),
                gradient = listOf(Color(0x447C4DFF), Color(0xFF0A0418)),
                shape = DacChipShape
            ) {
                Box(Modifier.size(5.5.dp).clip(CircleShape).background(Color(0xFF7C4DFF)))
                Spacer(Modifier.width(3.5.dp))
                Text("CS43198", color = Mascot.color(0xFFB9C2D0), fontSize = 11.5.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, maxLines = 1)
            }
        },
        QuiltBadge("ingest", "Network sync") {
            com.hione.launcher.ingest.HioneIngestionBadge(onClick = onOpenFsIngest)
        },
        QuiltBadge("library", "Library") {
            com.hione.launcher.track.HioneLibraryTrackBadge(onClick = onOpenFsIngest)
        },
        QuiltBadge("brain", "Brain") {
            CyberBespokeBadge(
                onClick = onOpenBrain,
                accentColor = Color(0xFF00FF7F),
                gradient = listOf(Color(0x3300FF7F), Color(0xFF04150E)),
                shape = BrainShieldShape
            ) {
                Box(Modifier.size(5.5.dp).clip(CircleShape).background(Color(0xFF00FF7F)))
                Spacer(Modifier.width(3.5.dp))
                Text("BRAIN", color = Color(0xFF00FF7F), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, maxLines = 1)
            }
        },
        QuiltBadge("thermal", "Thermal") {
            HioneThermalBadge(cpuTempC = cpuTempC, batteryTempC = batteryTempC, onClick = onOpenThermal)
        },
        QuiltBadge("volume", "Volume") {
            com.hione.launcher.volume.CyberVolumeBadge(onClick = { com.hione.launcher.volume.HioneVolumeManager.triggerHud(ctx) })
        },
        QuiltBadge("battery", "Battery") {
            HioneQuantumBatteryBadge(batteryPct = batteryPct, isCharging = isCharging, onClick = onOpenBattery)
        },
        QuiltBadge("control", "${Mascot.osName} control") {
            Box(Modifier.width(96.dp)) {
                CyberBespokeBadge(
                    onClick = onOpenQuickSettings,
                    accentColor = HioneCyan,
                    gradient = listOf(Mascot.color(0x33FFC56B), Mascot.color(0xFF041015)),
                    shape = IngressStreamShape
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "${Mascot.osName} control", tint = HioneCyan, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("CONTROL", color = HioneCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, maxLines = 1)
                }
            }
        }
    )
    Box(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 3.dp).graphicsLayer()) {
        HioneTopBarBackground(topBarTheme, Modifier.matchParentSize())
        com.hione.launcher.ui.HioneBadgeQuilt(
            badges = quiltBadges,
            order = quiltOrder,
            onOrderChange = onQuiltOrderChange,
            config = quiltConfig
        )
    }
    // Active severe weather warning banner (if any)
    if (weatherState.weather.severeWarning != null) {
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clip(CutCornerShape(8.dp))
                .background(Brush.horizontalGradient(listOf(com.hione.launcher.ui.HioneIdentity.Coral, Color(0xFFB71C1C))))
                .border(1.dp, Color(0xFFFF5252), CutCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = weatherState.weather.severeWarning ?: "",
                color = Color.White,
                fontSize = HioneDimens.textXs,
                fontWeight = FontWeight.Black,
                fontFamily = AudiowideFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * BOTTOM 3D EMBOSSED DIVA DOCK TRAY (anchor bar + protruding Hione pearl).
 *
 * Extracted out of [HioneLauncherScreen]: the parent sat at ~16.2k dex instructions,
 * at ART's 16384-instruction JIT limit, so it ran interpreted on every recomposition.
 * This is the densest part of the home tree (deeply nested inline Box/Row/Column with
 * gradient brushes), so it moves out whole — identical composables, order and modifiers.
 */
@Composable
private fun HioneLauncherDivaDock(
    hasActiveAudioOutput: Boolean,
    isAudioPlaying: Boolean,
    npAccent: com.hione.launcher.ui.NpAccent,
    onOpenAllApps: () -> Unit,
    onOpenHardware: () -> Unit,
    onOpenWeather: () -> Unit
) {
    val ctx = LocalContext.current
    // ============================================================
    // BOTTOM 3D EMBOSSED DIVA DOCK TRAY
    // Features spinning rainbow sweep gradient ring, BPM aura pulse,
    // bottom-aligned bold centerpiece text, and horizontal swipe quick app switching
    // ============================================================
    val bpmState by com.hione.launcher.bpm.HioneBpmEngine.state.collectAsState()
    val isBpmAudioPlaying = hasActiveAudioOutput && (isAudioPlaying || bpmState.isPlaying)
    val liveBpm = if (bpmState.bpm in 40f..260f) bpmState.bpm else 128f
    val beatIntervalMs = (60_000f / liveBpm).toInt().coerceIn(240, 1500)

    // Ambient-gated: the dock aura only animates while someone is looking (HioneAmbient).
    val lowPowerGate by com.hione.launcher.ui.rememberAmbientGate()
    androidx.compose.runtime.LaunchedEffect(isBpmAudioPlaying) { com.hione.launcher.ui.HioneAmbient.setPlaying(isBpmAudioPlaying) }

    val dockInfiniteTransition = rememberInfiniteTransition(label = "DivaDockAura")
    val rainbowRotation by dockInfiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isBpmAudioPlaying) 6000 else 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "DockRainbowRotate"
    )

    // Subtle BPM background aura glow (ONLY animated when active audio output = true)
    val bpmAuraAlpha by dockInfiniteTransition.gatedFloat(lowPowerGate, 
        initialValue = if (isBpmAudioPlaying) 0.35f else 0.18f,
        targetValue = if (isBpmAudioPlaying) 0.72f else 0.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isBpmAudioPlaying) (beatIntervalMs / 2) else 3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "DockBpmAura"
    )

    val rainbowStops = remember {
        listOf(
            Mascot.color(0xFFFFC56B), // Cyan
            Color(0xFF00FF88), // Mint
            com.hione.launcher.ui.HioneIdentity.Gold, // Gold
            Mascot.color(0xFF6FB0FF), // Pink
            Mascot.color(0xFFB9C2D0), // Purple
            Mascot.color(0xFFFFC56B)  // Cyan
        )
    }

    // Anchor bar: ONE evenly-spaced row of 5 equal cells (Hardware · FM · Hione Music · Settings ·
    // Weather) — Hione-suite destinations ONLY, no third-party apps. 48dp icons, the Hione anchor one step larger (56dp), 11sp labels on every cell,
    // nothing absolutely positioned, so nothing can overlap or wrap at 360dp.
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 14.dp)
            .pointerInput(Unit) {
                var totalDragX = 0f
                var totalDragY = 0f
                detectDragGestures(
                    onDragStart = { totalDragX = 0f; totalDragY = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount.x
                        totalDragY += dragAmount.y
                    },
                    onDragEnd = {
                        // Swipe UP on the dock = app drawer. App switching lives on the system pill.
                        val thresholdPx = 40 * ctx.resources.displayMetrics.density
                        if (kotlin.math.abs(totalDragY) > kotlin.math.abs(totalDragX) && totalDragY < -thresholdPx) {
                            onOpenAllApps()
                        }
                    }
                )
            }
    ) {
        // Shorter glass tray; the pearl above is drawn OUTSIDE it (no clip on the outer Box).
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
            .clip(RoundedCornerShape(HioneDimens.cornerL))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xEE0D2630), Mascot.color(0xFF06141B))))
            .border(
                BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            CyberGlassBorder.copy(alpha = 0.9f),
                            CyberGlassBorder.copy(alpha = 0.25f),
                            CyberGlassBorder.copy(alpha = 0.65f)
                        )
                    )
                ),
                RoundedCornerShape(HioneDimens.cornerL)
            )
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 5.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            // Anchor bar is Hione-suite ONLY (no third-party apps). This cell used to
            // launch Chrome/Firefox; it now opens the in-launcher Hardware (Anatomical/DAC)
            // observatory — the device's real hardware readout, no external dependency.
            DockIconItem(
                iconRes = R.drawable.ic_hione_monitor_status,
                label = "Hardware",
                modifier = Modifier.weight(1f),
                onClick = onOpenHardware
            )
            DockIconItem(
                iconRes = R.drawable.ic_fm_hione,
                label = "FM Radio",
                modifier = Modifier.weight(1f),
                onClick = { launchHioneFm(ctx) }
            )
            // Reserved slot for the Hione Pearl (drawn above the tray, see below): keeps the
            // even 5-cell spacing and holds the label at the same baseline as the others.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Spacer(Modifier.size(48.dp))
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${Mascot.musicAppName}",
                    color = if (isBpmAudioPlaying) HioneNeonPink else HioneCyan,
                    fontSize = HioneDimens.textXs,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
            DockIconItem(
                iconRes = R.drawable.ic_settings_hione,
                label = "Settings",
                modifier = Modifier.weight(1f),
                onClick = {
                    val intent = ctx.packageManager.getLaunchIntentForPackage("com.hione.settings")?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
                }
            )
            // Was Files (launched DocumentsUI); now the in-launcher Weather observatory.
            DockIconItem(
                iconRes = R.drawable.ic_weather_hione,
                label = "Weather",
                modifier = Modifier.weight(1f),
                onClick = onOpenWeather
            )
        }
        }
        // MIKU PEARL: the Hione Music anchor, larger than the dock icons and protruding above the
        // bar's top edge (drawn after the tray, so it is above it in z-order). Magic-lamp launch.
            val hioneMusicInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .hionePressScale(
                        pressedScale = 0.86f,
                        glowColor = Mascot.color(0xFFFF007F),
                        interactionSource = hioneMusicInteraction
                    )
                    .clickable(
                        interactionSource = hioneMusicInteraction,
                        indication = null
                    ) {
                        try {
                            val intent = Intent().apply {
                                setClassName("com.hione.player", "com.hione.player.MainActivity")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            }
                            val options = android.app.ActivityOptions.makeCustomAnimation(
                                ctx,
                                R.anim.magic_lamp_expand,
                                R.anim.magic_lamp_fade_out
                            )
                            ctx.startActivity(intent, options.toBundle())
                        } catch (_: Throwable) {}
                    }
            ) {
                Box(
                    Modifier
                        .size(62.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    if (isBpmAudioPlaying) Color(bpmState.dominantColor).copy(alpha = bpmAuraAlpha) else npAccent.full.copy(alpha = 0.20f),
                                    Mascot.color(0xFFB9C2D0).copy(alpha = if (isBpmAudioPlaying) 0.25f else 0.08f),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // GOLDEN MIKU MUSIC (a BPM-game secret): gold art and a gold ring on the pearl.
                    val golden = rememberUnlockEnabled(com.hione.launcher.bpm.HioneSecrets.GOLDEN_ICON)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = rainbowRotation }
                            .border(BorderStroke(2.5.dp, Brush.sweepGradient(colors = if (golden) GOLD_RING_STOPS else rainbowStops)), CircleShape)
                    )
                    Image(
                        painter = mascotPainter(id = hioneMusicIconRes(golden)),
                        contentDescription = "${Mascot.musicAppName}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.5.dp)
                            .clip(CircleShape)
                    )
                }
            }
    }
}

/**
 * Quick-settings shade, USB-host modal and the wallpaper / theme picker dialog.
 *
 * Extracted out of [HioneLauncherScreen] to pull that composable back from ART's
 * 16384-dex-instruction JIT ceiling (it measured ~16.2k and therefore ran interpreted
 * on every recomposition). Same composables, same visibility conditions.
 */
@Composable
private fun HioneLauncherShadeAndWallpaperModals(
    isCyberQuickSettingsOpen: Boolean,
    onCloseQuickSettings: () -> Unit,
    batteryPct: Int,
    isCharging: Boolean,
    isWifiConnected: Boolean,
    currentTime: String,
    currentDate: String,
    onOpenProfile: () -> Unit = {},
    showUsbHostModal: Boolean,
    onDismissUsbHostModal: () -> Unit,
    isWallpaperPickerOpen: Boolean,
    onCloseWallpaperPicker: () -> Unit,
    onPickFromGallery: () -> Unit,
    customWallpaperUri: String?,
    onCustomWallpaperUriChange: (String?) -> Unit,
    onWallpaperIdChange: (String) -> Unit,
    prefs: SharedPreferences,
    wallpapers: List<HioneWallpaperTheme>,
    currentWallpaperRes: Int
) {
    val ctx = LocalContext.current
    // Cosmetics earned in the BPM game. A locked card stays VISIBLE (hiding it means nobody
    // learns the reward exists) but is labelled and refuses selection. Ids are HioneUnlocks'.
    // Keyed on the unlock store's version: this composable stays alive for the whole launcher
    // session, so an unkeyed remember kept a wallpaper earned mid-session showing as locked until
    // the launcher was restarted — an earned reward that looked like it did nothing.
    val unlockVersion by com.hione.launcher.bpm.HioneUnlocks.version.collectAsState()
    val lockedCosmetics = remember(unlockVersion) {
        val u = com.hione.launcher.bpm.HioneUnlocks.unlockedIds(ctx)
        mapOf(
            "w6" to com.hione.launcher.bpm.HioneUnlocks.OS_WALLPAPER_NEON_WAVE,
            "w7" to com.hione.launcher.bpm.HioneUnlocks.OS_WALLPAPER_CYBER_HOLOGRAM,
            "theme_cozy_cafe" to com.hione.launcher.bpm.HioneUnlocks.OS_THEME_COZY_CAFE,
            "theme_cyber_stage" to com.hione.launcher.bpm.HioneUnlocks.OS_THEME_CYBER_STAGE
        ).filterValues { it !in u }.keys
    }
    // ============================================================
    // CYBER NOTIFICATION SHADE & QUICK SETTINGS MODAL
    // ============================================================
    AnimatedVisibility(
        visible = isCyberQuickSettingsOpen,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        CyberNotificationShadeModal(
            onClose = onCloseQuickSettings,
            onOpenSettings = {
                onCloseQuickSettings()
                val intent = ctx.packageManager.getLaunchIntentForPackage("com.hione.settings")?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
            },
            onOpenAudioSettings = {
                onCloseQuickSettings()
                val intent = ctx.packageManager.getLaunchIntentForPackage("com.hione.settings")?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
            },
            batteryPct = batteryPct,
            isCharging = isCharging,
            isWifiConnected = isWifiConnected,
            currentTime = currentTime,
            currentDate = currentDate,
            onOpenProfile = onOpenProfile
        )
    }

    // ============================================================
    // SYSTEM-WIDE USB HOST CONTROLLER MODAL
    // ============================================================
    if (showUsbHostModal) {
        com.hione.launcher.usb.HioneUsbHostModal(
            onDismissRequest = onDismissUsbHostModal
        )
    }

    // ============================================================
    // WALLPAPER & THEME PICKER MODAL (LONG-PRESS DESKTOP)
    // ============================================================
    if (isWallpaperPickerOpen) {
        AlertDialog(
            onDismissRequest = onCloseWallpaperPicker,
            containerColor = Mascot.color(0xF50A1E26),
            title = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wallpaper", color = HioneCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    IconButton(onClick = onCloseWallpaperPicker) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                    }
                }
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    // 1. Primary Action: Direct System Gallery Picker
                    Button(
                        onClick = {
                            onCloseWallpaperPicker()
                            onPickFromGallery()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HioneCyan.copy(alpha = 0.25f)),
                        border = BorderStroke(1.5.dp, HioneCyan),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = HioneCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Choose from gallery or files", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    }

                    if (customWallpaperUri != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                onCustomWallpaperUriChange(null)
                                prefs.edit().remove("custom_wallpaper_uri").apply()
                                onCloseWallpaperPicker()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HioneNeonPink.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, HioneNeonPink),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Reset to the default wallpaper", color = HioneNeonPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("Or pick a built-in wallpaper:", color = HioneTextSecondary, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))

                    LazyRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(wallpapers) { wp ->
                            val isSelected = (customWallpaperUri == null && currentWallpaperRes == wp.resId)
                            val isLocked = wp.id in lockedCosmetics
                            Column(
                                Modifier
                                    .width(90.dp)
                                    .clickable(enabled = !isLocked) {
                                        onCustomWallpaperUriChange(null)
                                        prefs.edit().remove("custom_wallpaper_uri").apply()
                                        onWallpaperIdChange(wp.id)
                                        prefs.edit().putString("current_wallpaper_id", wp.id).apply()
                                        // Persist to the theme engine when a built-in theme is chosen.
                                        if (wp.id.startsWith("theme_")) {
                                            com.hione.launcher.theme.HioneThemeRegistry.selectTheme(ctx, wp.id.removePrefix("theme_"))
                                        }
                                        onCloseWallpaperPicker()
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier
                                        .size(width = 90.dp, height = 140.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) HioneCyan else Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                ) {
                                    Image(
                                        painter = mascotPainter(id = wp.resId),
                                        contentDescription = wp.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        alpha = if (isLocked) 0.3f else 1f
                                    )
                                    if (isLocked) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("\uD83D\uDD12", fontSize = 26.sp)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (isLocked) "Locked \u00B7 BPM game" else wp.name,
                                    color = if (isLocked) Color.White.copy(alpha = 0.55f) else if (isSelected) HioneCyan else Color.White,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

/**
 * All-apps drawer sheet, recents overview and the long-press app context dialog.
 *
 * Extracted out of [HioneLauncherScreen]: the parent measured ~16.2k dex instructions,
 * at ART's 16384-instruction JIT limit, so it was refused by the JIT and interpreted on
 * every recomposition. Behaviour, ordering and the drawer's finger-follow physics are
 * unchanged — only the lexical home of the block moved.
 */
@Composable
private fun HioneLauncherDrawerAndRecents(
    drawerSheet: com.hione.launcher.ui.DrawerSheetState,
    drawerHeightPx: Float,
    allApps: List<InstalledApp>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    onCloseAllApps: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    contextMenuApp: InstalledApp?,
    onContextMenuAppChange: (InstalledApp?) -> Unit,
    isRecentsOpen: Boolean,
    onCloseRecents: () -> Unit,
    recentTasks: List<RecentTaskItem>,
    onRecentTasksChange: (List<RecentTaskItem>) -> Unit,
    pinnedPackages: Set<String>,
    onTogglePinDesktop: (String) -> Unit
) {
    val ctx = LocalContext.current
    // ============================================================
    // ALL-APPS CYBER DRAWER SHEET (SWIPE-UP GESTURE)
    // ============================================================
    // Pixel-style drawer SHEET: translated by (1 - progress) * height so it follows the finger
    // from the home swipe, settles with a spring, scrim fades with progress, grid parallax.
    run {
        // Scrim + sheet translation are driven from the Animatable inside draw/layer lambdas
        // (frame-rate work only, zero recomposition of this screen); the sheet stays composed
        // even when closed (translated fully off-screen) so opening never pays a first-frame
        // composition hitch.
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = 0.6f * drawerSheet.progress.value.coerceIn(0f, 1f)
        }.background(Color.Black))
        Box(Modifier.fillMaxSize().graphicsLayer {
            val pr = drawerSheet.progress.value
            translationY = (1f - pr) * drawerHeightPx
            alpha = if (pr <= 0.001f) 0f else 1f
        }) {
            CyberAllAppsDrawer(
                apps = allApps,
                searchQuery = searchQuery,
                onSearchChange = onSearchChange,
                selectedCategory = selectedCategory,
                onCategoryChange = onCategoryChange,
                onLaunchApp = {
                    onCloseAllApps()
                    launchApp(ctx, it)
                },
                onAppLongClick = { onContextMenuAppChange(it) },
                onOpenQuickSettings = onOpenQuickSettings,
                onClose = onCloseAllApps,
                sheet = drawerSheet,
                revealProgress = { drawerSheet.progress.value }
            )
        }
    }

    // ============================================================
    // PIXEL-STYLE RECENTS MULTI-TASKING OVERVIEW (SWIPE & HOLD)
    // ============================================================
    AnimatedVisibility(
        visible = isRecentsOpen,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        CyberRecentsOverview(
            tasks = recentTasks,
            onSelectTask = { task ->
                onCloseRecents()
                bringTaskToFront(ctx, task)
            },
            onDismissTask = { task ->
                dismissTask(ctx, task)
                onRecentTasksChange(recentTasks.filter { it.taskId != task.taskId })
            },
            onClearAll = {
                clearAllTasks(ctx, recentTasks)
                onRecentTasksChange(emptyList())
                onCloseRecents()
            },
            onClose = onCloseRecents
        )
    }

    // Context Menu Dialog (Long-press App QoL)
    if (contextMenuApp != null) {
        val app = contextMenuApp!!
        CyberAppContextDialog(
            app = app,
            isPinnedOnDesktop = pinnedPackages.contains(app.packageName),
            onDismiss = { onContextMenuAppChange(null) },
            onAppInfo = {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${app.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.MODAL))
                } catch (_: Throwable) {}
            },
            onUninstall = {
                try {
                    val intent = Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.parse("package:${app.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.MODAL))
                } catch (_: Throwable) {}
            },
            onTogglePinDesktop = {
                onTogglePinDesktop(app.packageName)
            }
        )
    }
}

/**
 * Quilt-options / home long-press dialogs, the observatory modal-host stack, the
 * floating volume HUD and the first-boot onboarding wizard.
 *
 * Extracted out of [HioneLauncherScreen], which measured ~16.2k dex instructions — ART
 * refuses to JIT-compile any method over 16384, so the home screen was re-interpreted
 * on every recomposition. Declared as a BoxScope extension so the volume HUD keeps its
 * Modifier.align(Alignment.TopEnd) against the same root Box as before.
 */
@Composable
private fun BoxScope.HioneLauncherObservatoryModals(
    quiltConfig: com.hione.launcher.ui.HioneQuiltConfig,
    onQuiltConfigChange: (com.hione.launcher.ui.HioneQuiltConfig) -> Unit,
    topBarTheme: HioneTopBarTheme,
    onCycleQuiltBackground: () -> Unit,
    onResetQuiltOrder: () -> Unit,
    isQuiltOptionsOpen: Boolean,
    onOpenQuiltOptions: () -> Unit,
    onCloseQuiltOptions: () -> Unit,
    isDesktopContextMenuOpen: Boolean,
    onCloseDesktopContextMenu: () -> Unit,
    onOpenWallpaperPicker: () -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    pagerState: androidx.compose.foundation.pager.PagerState,
    isRecentsOpen: Boolean,
    onCloseRecents: () -> Unit,
    recentTasks: List<RecentTaskItem>,
    onRecentTasksChange: (List<RecentTaskItem>) -> Unit,
    isWeatherObservatoryOpen: Boolean,
    onCloseWeatherObservatory: () -> Unit,
    isGpsModalOpen: Boolean,
    onCloseGps: () -> Unit,
    isBatteryObservatoryOpen: Boolean,
    onCloseBatteryObservatory: () -> Unit,
    isNetworkObservatoryOpen: Boolean,
    onCloseNetworkObservatory: () -> Unit,
    isBrainModalOpen: Boolean,
    onCloseBrain: () -> Unit,
    isBpmObservatoryOpen: Boolean,
    onCloseBpmObservatory: () -> Unit,
    isFsIngestModalOpen: Boolean,
    onCloseFsIngest: () -> Unit,
    isThermalObservatoryOpen: Boolean,
    onCloseThermal: () -> Unit,
    cpuTempC: Float,
    batteryTempC: Float,
    batteryPct: Int,
    isCharging: Boolean,
    isOnboardingOpen: Boolean,
    onFinishOnboarding: () -> Unit,
    isProfileModalOpen: Boolean = false,
    onCloseProfile: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onToggleArtworkPeek: () -> Unit = {},
    prefs: SharedPreferences
) {
    val ctx = LocalContext.current
    // ============================================================
    // VERBOSE MIKU MONITOR, BRAIN & OBSERVATORY MODALS
    // ============================================================
    if (isQuiltOptionsOpen) {
        com.hione.launcher.ui.HioneQuiltOptionsDialog(
            config = quiltConfig,
            onConfigChange = onQuiltConfigChange,
            backgroundName = topBarTheme.displayName,
            onCycleBackground = onCycleQuiltBackground,
            onResetOrder = onResetQuiltOrder,
            onDismiss = onCloseQuiltOptions
        )
    }
    if (isDesktopContextMenuOpen) {
        com.hione.launcher.menu.HioneHomescreenLongpressMenu(
            onWallpaperAndStyle = onOpenWallpaperPicker,
            onQuiltOptions = onOpenQuiltOptions,
            onWidgets = {
                coroutineScope.launch {
                    if (pagerState.pageCount > 1) {
                        pagerState.animateScrollToPage(1)
                    } else {
                        val intent = ctx.packageManager.getLaunchIntentForPackage("com.hione.settings")?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent != null) ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
                    }
                }
            },
            onHomeSettings = {
                // HioneSettings > Home screen (card layout and pop up). Older HioneSettings builds
                // without that page get their main screen, as before.
                val page = Intent("com.hione.settings.action.HOME_LAYOUT").setPackage("com.hione.settings")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val intent = page.takeIf { it.resolveActivity(ctx.packageManager) != null }
                    ?: ctx.packageManager.getLaunchIntentForPackage("com.hione.settings")?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                if (intent != null) ctx.startActivity(intent, HioneCompositing.optionsFor(ctx, HioneTransitionEvent.SETTINGS))
            },
            onOpenProfile = onOpenProfile,
            onToggleArtworkPeek = onToggleArtworkPeek,
            onDismiss = onCloseDesktopContextMenu
        )
    }

    com.hione.launcher.ui.HioneModalHost(visible = isRecentsOpen, onDismiss = onCloseRecents) {
        com.hione.launcher.recents.HioneRecentsOverviewCarousel(
            tasks = recentTasks,
            onLaunchTask = { task -> bringTaskToFront(ctx, task) },
            // These used to update the launcher's own list ONLY: the card vanished and the
            // count went to zero while the app was never stopped and the task never removed —
            // a completed action the user watched happen that had not happened. (The sibling
            // CyberRecentsOverview host above always did call these.)
            onDismissTask = { task ->
                dismissTask(ctx, task)
                onRecentTasksChange(recentTasks.filter { it.taskId != task.taskId })
            },
            onClearAll = {
                clearAllTasks(ctx, recentTasks)
                onRecentTasksChange(emptyList())
            },
            onDismissRequest = onCloseRecents
        )
    }

    com.hione.launcher.ui.HioneModalHost(visible = isWeatherObservatoryOpen, onDismiss = onCloseWeatherObservatory) {
        com.hione.launcher.weather.HioneWeatherObservatoryModal(
            onDismissRequest = onCloseWeatherObservatory
        )
    }
    // Live GPS (5 s HIGH_ACCURACY) only while the tactical map is on screen - see
    // HioneWeatherService.acquireLiveGps (background-forever GPS was the big idle drain).
    androidx.compose.runtime.DisposableEffect(isGpsModalOpen) {
        if (isGpsModalOpen) com.hione.launcher.weather.HioneWeatherService.acquireLiveGps(ctx)
        onDispose { if (isGpsModalOpen) com.hione.launcher.weather.HioneWeatherService.releaseLiveGps(ctx) }
    }
    com.hione.launcher.ui.HioneModalHost(visible = isGpsModalOpen, onDismiss = onCloseGps) {
        com.hione.launcher.gps.HioneGpsTacticalMapModal(
            onDismissRequest = onCloseGps
        )
    }
    com.hione.launcher.ui.HioneModalHost(visible = isBatteryObservatoryOpen, onDismiss = onCloseBatteryObservatory) {
        com.hione.launcher.battery.HioneBatteryObservatoryModal(
            onDismissRequest = onCloseBatteryObservatory
        )
    }
    com.hione.launcher.ui.HioneModalHost(visible = isNetworkObservatoryOpen, onDismiss = onCloseNetworkObservatory) {
        com.hione.launcher.network.HioneNetworkObservatoryModal(
            onDismissRequest = onCloseNetworkObservatory
        )
    }
    com.hione.launcher.ui.HioneModalHost(visible = isBrainModalOpen, onDismiss = onCloseBrain) {
        com.hione.launcher.observatory.HioneAnatomicalObservatoryModal(
            onDismissRequest = onCloseBrain
        )
    }
    com.hione.launcher.ui.HioneModalHost(visible = isBpmObservatoryOpen, onDismiss = onCloseBpmObservatory) {
        val bpmState by com.hione.launcher.bpm.HioneBpmEngine.state.collectAsState()
        com.hione.launcher.bpm.HioneBpmObservatoryModal(
            onClose = onCloseBpmObservatory,
            bpmState = bpmState
        )
    }
    com.hione.launcher.ui.HioneModalHost(visible = isFsIngestModalOpen, onDismiss = onCloseFsIngest) {
        com.hione.launcher.ingest.HioneFsIngestObservatoryModal(
            onClose = onCloseFsIngest
        )
    }
    com.hione.launcher.ui.HioneModalHost(visible = isThermalObservatoryOpen, onDismiss = onCloseThermal) {
        com.hione.launcher.thermal.HioneThermalObservatoryModal(
            onClose = onCloseThermal,
            cpuTempC = cpuTempC,
            batteryTempC = batteryTempC
        )
    }
    // The "QUANTUM CHARGING CORE" screen is only truthful while the device is actually charging.
    // It was mounted on the SAME flag as the battery observatory with no isCharging check, so
    // tapping the battery badge on battery power put a full-screen CHARGING panel over it.
    com.hione.launcher.ui.HioneModalHost(visible = isBatteryObservatoryOpen && isCharging, onDismiss = onCloseBatteryObservatory) {
        com.hione.launcher.battery.HioneFullscreenChargingModal(
            onDismiss = onCloseBatteryObservatory,
            batteryPct = batteryPct,
            isCharging = isCharging
        )
    }

    // Floating In-Theme Cyber Volume HUD Overlay (Top-Right Aligned near Physical Roller)
    com.hione.launcher.volume.HioneCyberVolumeHudOverlay(
        ctx = ctx,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 92.dp, end = 4.dp)
    )

    val lowPowerGate by com.hione.launcher.ui.rememberAmbientGate()

    val infinitePulse = rememberInfiniteTransition(label = "verPulse")
    val verColor by infinitePulse.gatedColor(lowPowerGate, 
        initialValue = Color(0x9989ACA7),
        targetValue = HioneCyan.copy(alpha = 0.85f),
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "verColor"
    )
    // ============================================================
    // MIKUOS BESPOKE 4-STEP FAST-BOOT ONBOARDING WIZARD MODAL
    // ============================================================
    if (isOnboardingOpen) {
        com.hione.launcher.onboarding.HioneOnboardingWizardModal(
            prefs = prefs,
            onFinish = onFinishOnboarding
        )
    }

    if (isProfileModalOpen) {
        com.hione.launcher.profile.HioneProfileModal(
            onDismiss = onCloseProfile
        )
    }
}
