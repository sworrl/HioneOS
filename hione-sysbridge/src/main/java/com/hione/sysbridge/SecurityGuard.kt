package com.hione.sysbridge

import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * Lock-state hardening that needs the system UID: reboot after a long time locked, and no new
 * USB data connections while locked. Both do nothing unless a real screen lock credential is set
 * (KeyguardManager.isDeviceSecure), because without one there is no lock to protect and no
 * encryption key a reboot would put away.
 *
 * Settings (Settings.Global, written by HioneSettings or by hand with `settings put global`):
 *   hione_sec_auto_reboot_hours  0 = off, 1..168 = reboot after this many hours locked.
 *   hione_sec_usb_lock           0 = off, 1 = while locked, a newly connected USB cable gets no
 *                               data functions (charging only, adb follows its own setting).
 * Unset means the build default: off on dev builds, 18 hours and on with ro.hione.release=1.
 *
 * Read back for the UI (Settings.Global, written here):
 *   hione_sec_locked             1 while this class considers the device locked.
 *   hione_sec_reboot_at          wall-clock ms of the pending auto reboot, 0 when none.
 *   hione_sec_usb_blocked        1 while a USB connection is held at charging only.
 *
 * Lock state. The device counts as locked from screen-off, from the keyguard showing, or from a
 * LOCK_STATE locked=true broadcast, and as unlocked on ACTION_USER_PRESENT or LOCK_STATE
 * locked=false. Until a lockscreen has sent LOCK_STATE at least once since boot, a screen-on with
 * no keyguard showing also counts as unlocked, so a build whose lockscreen does not report yet
 * never reboots in someone's hand.
 *
 * Broadcasts accepted (sender needs com.hione.permission.SYSTEM_BRIDGE):
 *   com.hione.sysbridge.LOCK_STATE  extra "locked" (boolean). The HioneOS lockscreen sends true
 *                                  when it shows and false after a successful unlock.
 *   com.hione.sysbridge.LOCKDOWN    the power-menu lockdown: require the PIN for the next unlock
 *                                  (no trust agent or biometric), mark locked, screen off.
 */
object SecurityGuard {
    private const val TAG = "HioneSecurity"
    const val PERMISSION = "com.hione.permission.SYSTEM_BRIDGE"
    const val ACTION_LOCK_STATE = "com.hione.sysbridge.LOCK_STATE"
    const val ACTION_LOCKDOWN = "com.hione.sysbridge.LOCKDOWN"

    const val KEY_REBOOT_HOURS = "hione_sec_auto_reboot_hours"
    const val KEY_USB_LOCK = "hione_sec_usb_lock"
    const val KEY_LOCKED = "hione_sec_locked"
    const val KEY_REBOOT_AT = "hione_sec_reboot_at"
    const val KEY_USB_BLOCKED = "hione_sec_usb_blocked"

    private const val RELEASE_REBOOT_HOURS = 18
    private const val MAX_REBOOT_HOURS = 168

    // UsbManager function bits. Data functions are the ones a locked device refuses.
    private const val FN_MTP = 1L shl 2
    private const val FN_MIDI = 1L shl 3
    private const val FN_PTP = 1L shl 4
    private const val FN_RNDIS = 1L shl 5
    private const val FN_UVC = 1L shl 7
    private const val FN_NCM = 1L shl 10
    private val DATA_EXTRAS = mapOf(
        "mtp" to FN_MTP, "ptp" to FN_PTP, "midi" to FN_MIDI,
        "rndis" to FN_RNDIS, "ncm" to FN_NCM, "uvc" to FN_UVC
    )
    /** LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN */
    private const val STRONG_AUTH_LOCKDOWN = 0x20
    private const val USER_ALL = -1

    private var started = false
    private lateinit var app: Context
    private lateinit var main: Handler

    /** elapsedRealtime when the device became locked, 0 while unlocked. */
    private var lockedSince = 0L
    /** A lockscreen has reported through LOCK_STATE since boot. */
    private var lockscreenReports = false
    private var usbConnected = false
    /** Data functions taken off the current connection, restored on unlock. */
    private var blockedFunctions = 0L
    private var lastBlockAt = 0L
    private var blockRounds = 0
    private var rebootArmed = false

    private val rebootAlarm = AlarmManager.OnAlarmListener { onRebootAlarm() }

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        main = Handler(Looper.getMainLooper())

        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = schedule()
        }
        for (k in listOf(KEY_REBOOT_HOURS, KEY_USB_LOCK)) {
            app.contentResolver.registerContentObserver(Settings.Global.getUriFor(k), false, observer)
        }

        // System broadcasts: no export flag needed, the system is the only sender.
        val sys = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onSystemEvent(i.action)
        }, sys, null, main)

        val usbSticky = app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onUsbState(i)
        }, IntentFilter(ACTION_USB_STATE), null, main)
        usbConnected = usbSticky?.getBooleanExtra("connected", false) ?: false

        // HioneOS broadcasts, signature permission only.
        val own = IntentFilter().apply {
            addAction(ACTION_LOCK_STATE)
            addAction(ACTION_LOCKDOWN)
        }
        val ownReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_LOCK_STATE -> {
                        lockscreenReports = true
                        if (i.getBooleanExtra("locked", true)) markLocked("lockscreen") else markUnlocked("lockscreen")
                    }
                    ACTION_LOCKDOWN -> lockdown()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(ownReceiver, own, PERMISSION, main, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(ownReceiver, own, PERMISSION, main)
        }

        // Starting with the screen off (process restart in a pocket) counts as locked.
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive || keyguardShowing()) markLocked("start") else publish()
        Log.i(TAG, "started: release=${isRelease()} rebootHours=${rebootHours()} usbLock=${usbLockOn()} " +
            "secure=${deviceSecure()} usb=$usbConnected")
    }

    private fun onSystemEvent(action: String?) {
        when (action) {
            Intent.ACTION_SCREEN_OFF -> markLocked("screen off")
            Intent.ACTION_USER_PRESENT -> markUnlocked("user present")
            Intent.ACTION_SCREEN_ON -> {
                // Only a fallback for builds whose lockscreen does not report: a screen with no
                // keyguard on it is a device someone is using.
                if (!lockscreenReports && !keyguardShowing()) markUnlocked("screen on, no keyguard")
            }
        }
    }

    private fun markLocked(why: String) {
        if (lockedSince == 0L) {
            lockedSince = SystemClock.elapsedRealtime()
            Log.i(TAG, "locked ($why)")
        }
        schedule()
    }

    private fun markUnlocked(why: String) {
        if (lockedSince != 0L) Log.i(TAG, "unlocked ($why)")
        lockedSince = 0L
        restoreUsb()
        schedule()
    }

    /** Re-arms or clears the reboot alarm from the current state and settings. */
    private fun schedule() {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (rebootArmed) { am.cancel(rebootAlarm); rebootArmed = false }
        var rebootAtWall = 0L
        val hours = rebootHours()
        if (lockedSince != 0L && hours > 0 && deviceSecure() && userUnlockedSinceBoot()) {
            val at = lockedSince + hours * 3_600_000L
            // Exact and wake-from-idle: alarms from the system UID are not deferred by doze.
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, "hione-auto-reboot", rebootAlarm, main)
            rebootArmed = true
            rebootAtWall = System.currentTimeMillis() + (at - SystemClock.elapsedRealtime())
        }
        if (lockedSince == 0L || !usbLockOn()) restoreUsb()
        publish(rebootAtWall)
    }

    private fun onRebootAlarm() {
        rebootArmed = false
        val hours = rebootHours()
        val lockedFor = if (lockedSince == 0L) 0L else SystemClock.elapsedRealtime() - lockedSince
        if (hours <= 0 || lockedSince == 0L || !deviceSecure() || lockedFor < hours * 3_600_000L - 60_000L) {
            Log.i(TAG, "auto reboot alarm ignored (hours=$hours lockedFor=${lockedFor / 1000}s)")
            schedule()
            return
        }
        Log.w(TAG, "auto reboot: locked for ${lockedFor / 60_000} min, rebooting to put the user keys away")
        runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).reboot("hione_auto_reboot")
        }.onFailure { Log.e(TAG, "auto reboot failed", it) }
    }

    private fun onUsbState(i: Intent) {
        val connected = i.getBooleanExtra("connected", false)
        val wasConnected = usbConnected
        usbConnected = connected
        if (!connected) {
            // Cutting the functions makes the gadget drop and re-enumerate, which reads as a
            // disconnect. Only a real unplug (well after the cut) clears what is owed back.
            if (blockedFunctions != 0L && SystemClock.elapsedRealtime() - lastBlockAt > 5_000L) {
                Log.i(TAG, "usb disconnected, block cleared")
                blockedFunctions = 0L
                publish()
            }
            return
        }
        if (lockedSince == 0L || !usbLockOn() || !deviceSecure()) return
        // Leave alone: USB DAC mode (the user asked for it), and Android Auto (accessory), which
        // has to work in a locked car dock.
        if (i.getBooleanExtra("accessory", false)) return
        if (Settings.Global.getString(app.contentResolver, "work_mode") == "dacin") return
        var data = 0L
        for ((extra, bit) in DATA_EXTRAS) if (i.getBooleanExtra(extra, false)) data = data or bit
        if (data == 0L) return
        // A connection that existed before the lock keeps working, the same as GrapheneOS. Only a
        // cable connected while locked, or one already cut that got data back, goes to charging.
        if (wasConnected && blockedFunctions == 0L) return
        // If something keeps putting the data functions back (HiBy's framework re-applies its
        // own USB default), stop after a few rounds rather than bounce the port forever.
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt > 30_000L) blockRounds = 0
        if (++blockRounds > 5) {
            Log.e(TAG, "usb lock: data functions keep coming back, giving up on this connection")
            return
        }
        blockedFunctions = blockedFunctions or data
        lastBlockAt = now
        setUsbFunctions(0L)
        Log.w(TAG, "usb connected while locked: data functions 0x${data.toString(16)} refused")
        publish()
    }

    private fun restoreUsb() {
        if (blockedFunctions == 0L) return
        val f = blockedFunctions
        blockedFunctions = 0L
        if (usbConnected) {
            setUsbFunctions(f)
            Log.i(TAG, "unlocked: usb functions 0x${f.toString(16)} restored")
        }
        publish()
    }

    private fun lockdown() {
        val secure = deviceSecure()
        if (secure) {
            runCatching {
                val cls = Class.forName("com.android.internal.widget.LockPatternUtils")
                val lpu = cls.getConstructor(Context::class.java).newInstance(app)
                cls.getMethod("requireStrongAuth", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(lpu, STRONG_AUTH_LOCKDOWN, USER_ALL)
            }.onFailure { Log.e(TAG, "lockdown: requireStrongAuth failed", it.cause ?: it) }
        }
        markLocked("lockdown")
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType).invoke(pm, SystemClock.uptimeMillis())
        }.onFailure { Log.w(TAG, "lockdown: screen off failed", it.cause ?: it) }
        Log.w(TAG, "lockdown (credential set: $secure)")
    }

    private fun setUsbFunctions(functions: Long) {
        val um = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val r = runCatching {
            UsbManager::class.java.getMethod("setCurrentFunctions", Long::class.javaPrimitiveType).invoke(um, functions)
        }
        if (r.isFailure) Log.e(TAG, "setCurrentFunctions($functions) failed", r.exceptionOrNull()?.cause ?: r.exceptionOrNull())
    }

    private fun publish(rebootAtWall: Long = -1L) {
        val cr = app.contentResolver
        runCatching {
            Settings.Global.putInt(cr, KEY_LOCKED, if (lockedSince != 0L) 1 else 0)
            Settings.Global.putInt(cr, KEY_USB_BLOCKED, if (blockedFunctions != 0L) 1 else 0)
            if (rebootAtWall >= 0L) Settings.Global.putLong(cr, KEY_REBOOT_AT, rebootAtWall)
        }
    }

    private fun rebootHours(): Int {
        val v = Settings.Global.getString(app.contentResolver, KEY_REBOOT_HOURS)?.trim()?.toIntOrNull()
            ?: return if (isRelease()) RELEASE_REBOOT_HOURS else 0
        return v.coerceIn(0, MAX_REBOOT_HOURS)
    }

    private fun usbLockOn(): Boolean {
        val v = Settings.Global.getString(app.contentResolver, KEY_USB_LOCK)?.trim()
            ?: return isRelease()
        return v == "1"
    }

    private fun deviceSecure(): Boolean = runCatching {
        (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
    }.getOrDefault(false)

    private fun keyguardShowing(): Boolean = runCatching {
        (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    }.getOrDefault(false)

    /** A reboot before the first unlock gains nothing: the keys are already put away. */
    private fun userUnlockedSinceBoot(): Boolean = runCatching {
        (app.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
    }.getOrDefault(true)

    private fun isRelease(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "ro.hione.release") as String
    }.getOrDefault("") == "1"

    private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
}
