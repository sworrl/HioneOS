package com.hione.riot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The whole skin is one activity: a device body drawn in Compose, the emulated LCD inside it,
 * and a [RiotShell] that owns every screen. It is a HOME activity, so it can be the launcher,
 * and it is also what com.hione.riot.action.ENTER opens.
 */
class RiotActivity : ComponentActivity() {

    companion object {
        const val TICK_GAP_MS = 45L
        // The M500's motor needs about 20 ms to be felt: 10-12 ms pulses run but feel like nothing.
        const val TICK_PULSE_MS = 22L
        const val PRESS_PULSE_MS = 35L
        const val HOLD_PULSE_MS = 60L
    }

    private lateinit var shell: RiotShell
    private var ticker: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
        volumeControlStream = AudioManager.STREAM_MUSIC
        RiotAssets.load(this)
        shell = RiotShell(this, unlockedOrForced(intent))
        setContent { RiotDevice(shell) { shell.powerButton() } }
        // Gesture back arrives here rather than as a key. As HOME there is nowhere to go back
        // to, so it is the Riot's own Back key.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { shell.keyDown(Key.BACK); shell.keyUp(Key.BACK) }
        })
    }

    /** No status bar, no navigation bar: the whole screen is the player. */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shell.setUnlocked(unlockedOrForced(intent))
        // Home pressed while already home: back to the home screen, like any launcher.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) shell.goHome()
    }

    private fun unlockedOrForced(i: Intent?): Boolean =
        i?.getBooleanExtra("force", false) == true || RiotSystem.isUnlocked(this)

    override fun onStart() {
        super.onStart()
        shell.start()
        ticker = lifecycleScope.launch {
            while (isActive) { shell.tick(); delay(RiotShell.TICK_MS) }
        }
    }

    override fun onStop() {
        ticker?.cancel(); ticker = null
        shell.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra("force", false) != true) shell.setUnlocked(RiotSystem.isUnlocked(this))
    }

    // ---- hardware keys -----------------------------------------------------------------------

    /**
     * The M500's volume ring ("ring-keys") sends VOLUME_UP/DOWN: those are the Riot's Volume
     * keys. The side keys ("gpio-keys-hiby") send media keys: those are the four-way pad, so
     * Forward and Backward tune the radio in radio mode, as they did on the Riot. Back is the
     * Riot's Back key. A D-pad, if one is attached, is the Scroll wheel and Select.
     */
    /**
     * While Riot mode is in front, HioneSystemUI passes the volume keys through instead of showing
     * its own HUD. Take them here, before anything else can, and show the Riot's own volume bar.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Key.VOL_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> Key.VOL_DOWN
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) shell.keyDown(k)
        return true
    }

    private fun map(code: Int): Key? = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> Key.VOL_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> Key.VOL_DOWN
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK -> Key.PLAY
        KeyEvent.KEYCODE_MEDIA_STOP -> Key.STOP
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Key.FWD
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> Key.REW
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Key.SELECT
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL -> Key.BACK
        KeyEvent.KEYCODE_MENU -> Key.MENU
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { shell.wheel(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { shell.wheel(1); return true }
        }
        val k = map(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0 || k == Key.VOL_UP || k == Key.VOL_DOWN) shell.keyDown(k)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val k = map(keyCode) ?: return super.onKeyUp(keyCode, event)
        shell.keyUp(k)
        return true
    }

    // ---- services for the shell ------------------------------------------------------------------

    // ---- haptics ---------------------------------------------------------------------------------

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
    }
    private var lastTick = 0L

    /** Follows Settings > Sound > Touch feedback, like any system haptic. */
    private fun hapticsOn(): Boolean = runCatching {
        Settings.System.getInt(contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }.getOrDefault(true)

    private fun buzz(effect: VibrationEffect) {
        val v = vibrator ?: return
        if (!v.hasVibrator() || !hapticsOn()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            else @Suppress("DEPRECATION") v.vibrate(effect)
        }
    }

    /**
     * One detent of the thumb wheel: a crisp, short tick. At most one every [TICK_GAP_MS], so a
     * fast spin clicks instead of turning into a buzz.
     */
    fun tickFeedback() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < TICK_GAP_MS) return
        lastTick = now
        buzz(VibrationEffect.createOneShot(TICK_PULSE_MS, 255))
    }

    /** A button press: a firmer pulse than the wheel. [strong] for the hold that leaves Riot mode. */
    fun pressFeedback(strong: Boolean = false) {
        buzz(VibrationEffect.createOneShot(if (strong) HOLD_PULSE_MS else PRESS_PULSE_MS, 255))
    }

    /**
     * The M500's haptics HAL has no prebaked effects (EFFECT_TICK falls back to a soft 50 ms
     * buzz) but does play composition primitives, which are short and crisp. Null when the
     * primitive is not supported, so the caller falls back to a one-shot pulse.
     */
    private fun primitive(id: Int, scale: Float): VibrationEffect? {
        if (Build.VERSION.SDK_INT < 30) return null
        val v = vibrator ?: return null
        if (!v.areAllPrimitivesSupported(id)) return null
        return VibrationEffect.startComposition().addPrimitive(id, scale).compose()
    }

    fun exitRiot() = RiotSystem.exitToHione(this)

    /**
     * The silver button. As the launcher, hand HOME back to Hione. Opened from somewhere else,
     * just close, which lands back wherever Riot mode was started from.
     */
    fun leave() {
        if (RiotSystem.isDefaultHome(this)) RiotSystem.exitToHione(this) else finishAndRemoveTask()
    }

    /** Power Saver: the LCD goes dark and so does the real screen, until a key wakes it. */
    fun setPowerSaving(on: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    fun requestAudioPermission() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(p), 39)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 39 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) shell.loadLibrary()
    }
}
