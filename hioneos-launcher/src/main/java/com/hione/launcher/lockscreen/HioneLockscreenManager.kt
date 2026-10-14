package com.hione.launcher.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.hione.launcher.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * OS-level manager for the bespoke Oki Hione Custom Lockscreen — ported from the music app
 * into the HioneOS launcher so the lockscreen lives on the **system** surface, not inside the
 * player.
 *
 * Handles the 2-phase power button logic (behaviour kept verbatim from the app source):
 * 1. Press Power while **Unlocked** -> Locks device & wakes display to the Lockscreen.
 * 2. Press Power while **Locked**   -> Powers off display immediately (we do nothing / let it sleep).
 * 3. Press Power while **Asleep**   -> SCREEN_ON while locked re-shows the Lockscreen.
 *
 * Also handles natural inactivity timeout: when the screen times out from an unlocked state, the
 * device locks and enters sleep directly (no wake-to-lockscreen).
 *
 * Root/privilege coupling: the stock-keyguard-disable path is gated behind [RootShell.isAvailable]
 * exactly like the source. Without privilege the Hione lockscreen simply layers as an overlay via
 * the SCREEN_ON/SCREEN_OFF receiver, leaving the stock keyguard untouched.
 */
object HioneLockscreenManager {
    private const val TAG = "HioneLockscreen"

    /** Music app package the double-press "music"/"radio" targets launch into. */
    private const val MUSIC_APP_PKG = "com.hione.player"

    private var isInstalled = false

    /**
     * Whether the device is currently in "locked" state.
     * Marked @Volatile for safe cross-thread visibility from BroadcastReceiver + Activity threads.
     */
    @Volatile
    var isLocked: Boolean = false
        private set

    /**
     * Tracks whether the most recent SCREEN_OFF was caused by an explicit power button press
     * (detected via KeyEvent interception in the foreground Activity) vs. a natural inactivity
     * timeout. Reset to false on every SCREEN_ON.
     */
    @Volatile
    var isPowerButtonScreenOff: Boolean = false

    fun setLocked(locked: Boolean) {
        isLocked = locked
        Log.d(TAG, "isLocked=$isLocked")
    }

    fun markPowerButtonPress() {
        isPowerButtonScreenOff = true
        Log.d(TAG, "Power button press flagged for next SCREEN_OFF")
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val fnStatus = try {
                Settings.Global.getInt(context.contentResolver, "fn_status", 0)
            } catch (_: Throwable) { 0 }

            if (fnStatus != 0) return

            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (!isLocked) {
                        if (isPowerButtonScreenOff) {
                            // Phase 1: User pressed power button while UNLOCKED.
                            // Lock device & wake display to show Lockscreen immediately.
                            Log.d(TAG, "Power button while unlocked -> Lock & wake to Lockscreen")
                            isLocked = true
                            isPowerButtonScreenOff = false

                            try {
                                val lockIntent = Intent(context, HioneLockscreenActivity::class.java).apply {
                                    putExtra("is_screen_off_transition", false)
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    )
                                }
                                // FLAG_ACTIVITY_NEW_TASK makes Android use the default TASK-OPEN
                                // animation (slide in from the RIGHT), which overrides the theme's
                                // curtain-drop window animation. Force the curtain (drop from TOP)
                                // explicitly via ActivityOptions so it reads as a system shade.
                                val opts = android.app.ActivityOptions.makeCustomAnimation(
                                    context, com.hione.launcher.R.anim.lockscreen_curtain_down, 0
                                ).toBundle()
                                context.startActivity(lockIntent, opts)
                            } catch (t: Throwable) {
                                Log.e(TAG, "Failed to launch HioneLockscreenActivity on lock", t)
                            }

                            // Wake the display to show the lockscreen. ACQUIRE_CAUSES_WAKEUP is
                            // the rootless wake path — root is optional on this OS, so no wake
                            // may depend on injected keyevents.
                            try {
                                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                @Suppress("DEPRECATION")
                                val wl = pm?.newWakeLock(
                                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                                    "hione:lock_wake"
                                )
                                wl?.acquire(1000L)
                            } catch (_: Throwable) {}
                        } else {
                            // Inactivity timeout / screen off while unlocked. Mark locked state;
                            // the lockscreen activity will be launched cleanly on ACTION_SCREEN_ON
                            // when the display actually lights up. Launching an activity during
                            // an in-flight sleep causes Android's InputDispatcher to wait 5s for
                            // a nonexistent window and drop all hardware/media key events.
                            Log.d(TAG, "Screen off -> Lock armed (will present on SCREEN_ON)")
                            isLocked = true
                            maybeEngageAod(context)
                        }
                    } else {
                        // Phase 2: User pressed power button while ALREADY LOCKED.
                        // Display powers off naturally — unless AOD is engaged, in which case
                        // the AOD face takes the panel instead of darkness.
                        Log.d(TAG, "SCREEN_OFF while locked -> Display power off confirmed")
                        isPowerButtonScreenOff = false
                        maybeEngageAod(context)
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    isPowerButtonScreenOff = false
                    // Wakeup from asleep state — show lockscreen
                    if (isLocked) {
                        try {
                            val wakeIntent = Intent(context, HioneLockscreenActivity::class.java).apply {
                                putExtra("is_screen_off_transition", false)
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                            }
                            val opts = android.app.ActivityOptions.makeCustomAnimation(
                                context, com.hione.launcher.R.anim.lockscreen_curtain_down, 0
                            ).toBundle()
                            context.startActivity(wakeIntent, opts)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to launch HioneLockscreenActivity on SCREEN_ON", t)
                        }
                    }
                }
            }
        }
    }

    /**
     * Engage the Always-On Display when the user's AOD mode says so: launch the AOD face and
     * wake the panel (it renders black at minimum brightness). No-op when AOD is off or the
     * "while charging" condition isn't met — the display then sleeps normally.
     */
    private fun maybeEngageAod(context: Context) {
        if (!HioneLockscreenPrefs.aodShouldEngage(context)) return
        try {
            val aod = Intent(context, HioneAodActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            context.startActivity(aod)
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            // ACQUIRE_CAUSES_WAKEUP is the rootless wake path; if it fails the display simply
            // sleeps dark, which is the correct AOD-off fallback.
            @Suppress("DEPRECATION")
            pm?.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "hione:aod_wake"
            )?.acquire(1500L)
            Log.d(TAG, "AOD engaged")
        } catch (t: Throwable) {
            Log.e(TAG, "AOD engage failed", t)
        }
    }

    /**
     * Optional double-power-press gesture handler. Targets are read live from
     * [HioneLockscreenPrefs]. Music/radio launch the music app via its package launch intent
     * (no app classes imported); camera goes through the standard MediaStore intent.
     */
    @Suppress("unused")
    private fun handleDoublePressPowerGesture(context: Context) {
        val action = HioneLockscreenPrefs.getPowerDoublePressAction(context)

        try {
            when (action) {
                "camera" -> {
                    val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    if (cameraIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(cameraIntent)
                    } else {
                        val fallback = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(fallback)
                    }
                }
                // Decoupled from the app: launch the music app by package rather than importing
                // com.hione.player.MainActivity / .radio.HioneFMRadioActivity.
                "music", "radio" -> {
                    val launch = context.packageManager.getLaunchIntentForPackage(MUSIC_APP_PKG)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (launch != null) context.startActivity(launch)
                }
                else -> {}
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to execute double press power action $action", t)
        }
    }

    fun install(context: Context) {
        if (isInstalled) return
        isInstalled = true
        val appContext = context.applicationContext

        // Disable stock AOSP lockscreen, blacklist stock status bar icons (keeps shade functional), configure system.
        // IMPORTANT: disabling the stock keyguard requires WRITE_SECURE_SETTINGS (platform signature)
        // or root. On a non-privileged build these writes throw SecurityException and, worse, would
        // fight the Hione overlay lockscreen if they half-succeeded. So gate the entire keyguard-disable
        // path behind an actual privilege check — when not privileged, the Hione lockscreen simply layers
        // as an overlay via the SCREEN_ON/SCREEN_OFF receiver below (no stock-keyguard disable at all).
        // Platform-signed builds hold WRITE_SECURE_SETTINGS and can disable the stock keyguard
        // WITHOUT root — the previous root-only gate meant this unrooted (platform-signed) OS just
        // left the stock keyguard up, so the user saw stock lockscreen instead of ours.
        val canSecure = runCatching {
            appContext.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        // With a PIN or password set (HioneSettings > Security > Screen lock) the stock keyguard
        // must stay enabled: it is what holds the device locked, and the system ignores the
        // disable flag for a secure user anyway. HioneSettings turns the flag back on when the
        // screen lock is removed.
        val secure = runCatching {
            (appContext.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
        }.getOrDefault(false)
        if (RootShell.isAvailable() || canSecure) {
            try {
                if (!secure) {
                Settings.Secure.putInt(appContext.contentResolver, "lockscreen.disabled", 1)
                Settings.System.putInt(appContext.contentResolver, "lockscreen.disabled", 1)
                // Authoritative A14 disable (needs WRITE_SECURE_SETTINGS) — the Settings key alone
                // isn't always honored; LockPatternUtils.setLockScreenDisabled is.
                runCatching {
                    val lpu = Class.forName("com.android.internal.widget.LockPatternUtils")
                        .getConstructor(android.content.Context::class.java).newInstance(appContext)
                    lpu.javaClass.getMethod("setLockScreenDisabled", java.lang.Boolean.TYPE, Integer.TYPE)
                        .invoke(lpu, true, 0)
                }
                }
                Settings.System.putString(appContext.contentResolver, Settings.System.TIME_12_24, "24")
                Settings.Secure.putInt(appContext.contentResolver, "camera_double_tap_power_gesture_disabled", 0)
                Settings.Secure.putInt(appContext.contentResolver, "camera_gesture_disabled", 0)
                // Blacklist individual stock status bar icons but keep the shade pulldown functional
                Settings.Secure.putString(
                    appContext.contentResolver,
                    "icon_blacklist",
                    "clock,battery,volume,wifi,cell,mobile,bluetooth,zen,location,cast,hotspot,alarm_clock,managed_profile,vpn,nfc,rotate,headset,data_saver,mute,speakerphone,tty,ims_indicator,volte,hd,phone_signal,data_connection,network_speed,hiby_gain,hiby_sample"
                )
                // Use RootShell instead of Runtime.exec to avoid Process resource leaks
                CoroutineScope(Dispatchers.IO).launch {
                    if (!secure) RootShell.execFast("cmd lock_settings set-disabled true")
                    RootShell.execFast("settings put secure camera_double_tap_power_gesture_disabled 0")
                    // No statusbar disable flags here: HioneSystemUI owns them (HioneNotificationShadeService).
                }
            } catch (_: Throwable) {}
        } else {
            Log.i(TAG, "Not privileged (no root / WRITE_SECURE_SETTINGS) — layering Hione lockscreen as overlay only; leaving stock keyguard untouched")
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            appContext.registerReceiver(screenReceiver, filter)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register screenReceiver", t)
        }
    }
}
