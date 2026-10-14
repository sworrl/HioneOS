package com.hione.wheel

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HionePod: the era picker and the four skins, in one activity. Also a HOME candidate, so it can
 * stand in as the whole shell; "Exit" hands HOME back to com.hione.launcher.
 *
 * Intents:
 *  - MAIN/LAUNCHER, MAIN/HOME: the last era used (if still unlocked), else the picker.
 *  - com.hione.wheel.action.ENTER: extra "era" = 2001 | 2004 | 2005 | 2007 opens that era;
 *    extra "force" = true skips the unlock check (debug). Extra "open" = "radio" opens the tuner.
 */
class WheelActivity : ComponentActivity() {

    private lateinit var prefs: WheelPrefs
    private lateinit var link: PlayerLink
    private lateinit var art: ArtCache
    private lateinit var clicker: Clicker
    private lateinit var radio: RadioLink

    private val frame = mutableIntStateOf(0)
    private val era = mutableStateOf<Era?>(null)
    private val unlocked = mutableStateOf<Set<Era>>(emptySet())
    private var forced = false
    private val skinState = mutableStateOf<Skin?>(null)
    private var skin: Skin?
        get() = skinState.value
        set(v) { skinState.value = v }
    private var library: Library = Library.EMPTY
    private var libraryLoaded = false
    private var hasPermission = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasPermission = ok
        skin?.hasPermission = ok
        if (ok) loadLibrary()
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) Power.pct = level * 100 / scale
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            Power.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            frame.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.load(this)
        prefs = WheelPrefs(this)
        link = PlayerLink(this) {
            ((skin?.top as? MenuScreen)?.rows as? StaticRows)?.refresh()
            frame.intValue++
        }
        art = ArtCache(this) { frame.intValue++ }
        clicker = Clicker(this).apply { haptics = prefs.haptics }
        radio = RadioLink(this, prefs) {
            ((skin?.top as? MenuScreen)?.rows as? StaticRows)?.refresh()
            frame.intValue++
        }
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        WindowCompat.setDecorFitsSystemWindows(window, false)
        immersive()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val s = skin
                if (era.value != null && s != null) {
                    if (s.back()) frame.intValue++ else showPicker()
                } else if (!isDefaultHome()) {
                    finish()
                }
            }
        })

        link.connect()
        checkPermission()
        refreshUnlocks()
        route(intent)

        setContent {
            val e = era.value
            val s = skinState.value
            if (e != null && s != null && s.era == e) {
                SkinShell(s, frame, onEras = ::showPicker, onExit = ::exitToHioneHome)
            } else {
                EraPicker(unlocked.value, forced, onPick = { enter(it) }, onExit = ::exitToHioneHome)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshUnlocks()
        if (intent.action == ACTION_ENTER) { route(intent); return }
        // HOME pressed while we are home: back to the top menu, like pressing MENU a few times.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            skin?.toRoot()
            frame.intValue++
        }
    }

    override fun onResume() {
        super.onResume()
        immersive()
        refreshUnlocks()
        ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        if (!link.connected) link.connect()
        skin?.wake()
        // An era that was switched off (or never earned) while we were away closes itself.
        val e = era.value
        if (e != null && !forced && e !in unlocked.value) showPicker()
    }

    override fun onPause() {
        super.onPause()
        clicker.sleep()
        try { unregisterReceiver(battery) } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        link.release()
        radio.release()
        clicker.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    /** No status bar, no nav bar, in every era and on the picker. A swipe shows them briefly. */
    private fun immersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Hardware volume keys. HioneSystemUI passes them through while HionePod is in front, so they
     * are handled here: the music volume moves one wheel step with no system UI (flags 0), the
     * clicker ticks, and the era's own volume bar shows. Both down and up are consumed.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val dir = if (k == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                val s = skin
                if (s != null && era.value != null) s.volumeKey(dir)
                else link.adjustVolume(dir * maxOf(1, link.maxVolume / 30))
                frame.intValue++
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- routing -----------------------------------------------------------------------------

    private fun route(i: Intent?) {
        if (i == null) return
        if (truthy(i, "force")) forced = true
        val wanted = Era.byId(i.getStringExtra("era") ?: i.getIntExtra("era", 0).takeIf { it > 0 }?.toString())
        when {
            wanted != null && (forced || wanted in unlocked.value) -> {
                enter(wanted)
                // extra "open" = "radio" goes straight to the tuner.
                if (i.getStringExtra("open") == "radio") skin?.let { it.toRoot(); it.openRadio(); frame.intValue++ }
            }
            i.action == ACTION_ENTER -> showPicker()
            era.value == null -> {
                val last = Era.byId(prefs.lastEra)
                if (last != null && (forced || last in unlocked.value)) enter(last) else showPicker()
            }
        }
    }

    private fun truthy(i: Intent, key: String): Boolean {
        val extras = i.extras ?: return false
        val v = extras.get(key)
        return v == true || (v is String && v.equals("true", true)) || v == 1
    }

    private fun enter(e: Era) {
        if (!forced && e !in unlocked.value) return
        prefs.lastEra = e.id
        clicker.era = e
        clicker.route = prefs.clickerRoute(e)
        skin = Skin(e, this, prefs, link, art, clicker, radio, library, libraryLoaded, hasPermission) { frame.intValue++ }
        era.value = e
        frame.intValue++
    }

    private fun showPicker() {
        era.value = null
        skin = null
        frame.intValue++
    }

    private fun refreshUnlocks() {
        unlocked.value = Unlocks.unlocked(this)
    }

    // ---- library -----------------------------------------------------------------------------

    private fun audioPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun checkPermission() {
        hasPermission = ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) loadLibrary() else permLauncher.launch(audioPermission())
    }

    private fun loadLibrary() {
        lifecycleScope.launch {
            val lib = withContext(Dispatchers.IO) { Library.load(applicationContext) }
            library = lib
            libraryLoaded = true
            skin?.let { it.lib = lib; it.libLoaded = true; it.hasPermission = true }
            frame.intValue++
        }
    }

    // ---- HOME --------------------------------------------------------------------------------

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val r = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == packageName
    }

    /**
     * Give HOME back to the Hione launcher and go there. Clears our own preferred-home entry; on
     * Android 10+ the HOME role is also handed back when the platform lets us (we are platform
     * signed, so MANAGE_ROLE_HOLDERS is normally granted).
     */
    private fun exitToHioneHome() {
        if (isDefaultHome()) {
            try {
                @Suppress("DEPRECATION")
                packageManager.clearPackagePreferredActivities(packageName)
            } catch (t: Throwable) { Log.w(TAG, "clearPackagePreferredActivities failed", t) }
            giveHomeRoleTo(LAUNCHER_PKG)
        }
        val toLauncher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(LAUNCHER_PKG)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(toLauncher)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) { }
        }
        showPicker()
        finish()
    }

    private fun giveHomeRoleTo(pkg: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val rm = getSystemService(android.app.role.RoleManager::class.java) ?: return
            val m = rm.javaClass.getMethod(
                "addRoleHolderAsUser", String::class.java, String::class.java, Int::class.javaPrimitiveType,
                android.os.UserHandle::class.java, java.util.concurrent.Executor::class.java,
                java.util.function.Consumer::class.java
            )
            m.invoke(rm, android.app.role.RoleManager.ROLE_HOME, pkg, 0, Process.myUserHandle(), mainExecutor,
                java.util.function.Consumer<Boolean> { ok -> Log.i(TAG, "HOME role to $pkg: $ok") })
        } catch (t: Throwable) {
            Log.i(TAG, "HOME role hand-back not available: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        const val ACTION_ENTER = "com.hione.wheel.action.ENTER"
        const val LAUNCHER_PKG = "com.hione.launcher"
        private const val TAG = "HionePod"
    }
}
