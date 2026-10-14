package com.hione.launcher

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * The startup tuning the launcher applies to make the device reachable and usable, done with
 * platform APIs instead of a root shell.
 *
 * WHY THIS EXISTS. `HioneLauncherActivity.onCreate` used to hand a 19-command string to
 * `RootShell.execFast`. HioneOS has no root and never will, so every launch produced
 * `Cannot run program "su": error=2, No such file or directory`, RootShell backed off for 120
 * seconds, and NOT ONE of those nineteen settings was ever applied.
 *
 * Every one of those commands has a first-class API behind it, and a platform-signed app holds the
 * permissions they need: WRITE_SECURE_SETTINGS for the `settings put` lines,
 * MANAGE_APP_OPS_MODES for `appops set`, GRANT_RUNTIME_PERMISSIONS for `pm grant`. This is the
 * standing rule in practice: a `su` failure here was a code bug, not a reason to install Magisk.
 *
 * WHAT IS DELIBERATELY NOT CARRIED OVER. The original string also ran `setprop` on four
 * `persist.*` / `service.adb.*` properties and then `stop adbd; start adbd`. Those are not
 * available to us and no amount of signing changes it: the property contexts are owned by the
 * `adbd` domain and `stop`/`start` are init verbs for the shell. They are dropped rather than
 * attempted-and-swallowed, because a call that cannot work should not look like one that might.
 * `Settings.Global.ADB_WIFI_ENABLED` is the supported route to the same outcome on Android 11 and
 * later, and it is set here.
 */
object HioneSystemTuning {

    private const val TAG = "HioneSystemTuning"

    /**
     * Reflection wraps whatever the target threw in an InvocationTargetException, whose own
     * message is null. Logging that verbatim produces "InvocationTargetException: null", which
     * says nothing; the useful part is always the cause.
     */
    private fun why(t: Throwable): String {
        val real = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        return "${real.javaClass.simpleName}: ${real.message ?: "no detail"}"
    }

    /**
     * The high-performance Wi-Fi lock, held for the life of the process.
     *
     * This used to be a local `val` in HioneLauncherActivity.onCreate: created, acquired, and then
     * immediately out of scope. WifiLock releases itself on finalize, so the lock the launcher
     * thought it was holding was dropped by the next GC and the radio went back to aggressive
     * power save. Measured consequence on 2026-10-08: `dumpsys wifi` listed no held locks at all,
     * and wireless adb would complete a TCP handshake and then stall with no data flowing, which
     * is what a sleeping radio looks like from the other end.
     *
     * A strong reference on an object that outlives the activity is the whole fix. Kept here
     * rather than in the activity because the activity is destroyed and recreated.
     */
    @Volatile private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** Acquire the high-perf Wi-Fi lock once, and keep a reference so it is not finalized away. */
    private fun holdWifiLock(ctx: Context) {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val lock = wm?.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HioneIngressLock"
            ) ?: return
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
            Log.i(TAG, "high-perf Wi-Fi lock acquired and retained (held=${lock.isHeld})")
        }.onFailure { Log.w(TAG, "Wi-Fi lock failed: ${why(it)}") }
    }

    private val GLOBAL_INTS = listOf(
        // adb. The ingest relay reaches the device this way, so these are not a developer
        // convenience here, they are load-bearing for sync.
        "adb_enabled" to 1,
        "development_settings_enabled" to 1,
        // adb_wifi_enabled is deliberately NOT set, in either direction.
        //
        // An earlier version of this file forced it to 0 and claimed, in a comment and a commit
        // message, that 1 blocked legacy port 5555. That was wrong. Re-tested properly on
        // 2026-10-08: wireless adb works with the flag at 0 AND at 1. The thing that had actually
        // been broken was the Wi-Fi lock below never being held, so the radio slept and every
        // command timed out; the flag changed in the same step and got the credit.
        //
        // With no measurement justifying either value, the honest thing is to leave the user's
        // setting alone. Android 11 wireless debugging is a real feature and this is not the place
        // to have an opinion about it.
        // Never drop Wi-Fi when the screen goes off, or a sync dies halfway through a library.
        "wifi_sleep_policy" to 2,
        // Stay awake on any charger while ingesting.
        "stay_on_while_plugged_in" to 3,
    )

    /**
     * App ops to force to ALLOW, as (op name, packages).
     *
     * MANAGE_EXTERNAL_STORAGE is here because the permission being granted is only half of it:
     * the op has to be `allow` as well, and on this device it sat at `default` with a recent
     * reject. The visible consequence was the ingest observatory reporting "No external TF/MicroSD
     * card mounted" while a 16,000-track card was mounted and playing, because /storage is
     * `drwx--x---` (traverse, not list) and the card is `root:media_rw`, so without the op the raw
     * path is simply closed to us.
     */
    private val APP_OPS = listOf(
        "android:system_alert_window" to listOf("com.hione.launcher", "com.hione.player", "com.hione.systemui"),
        "android:manage_external_storage" to listOf("com.hione.launcher", "com.hione.player"),
    )

    /** SYSTEM_ALERT_WINDOW for our own overlays: the nav pill, the shade, the volume modal. */
    private val OVERLAY_PACKAGES = listOf(
        "com.hione.launcher",
        "com.hione.player",
        "com.hione.systemui",
    )

    private val RUNTIME_GRANTS = listOf(
        "com.hione.launcher" to android.Manifest.permission.RECORD_AUDIO,
        "com.hione.launcher" to "android.permission.MEDIA_CONTENT_CONTROL",
    )

    /**
     * Apply everything, reporting per-item rather than as one opaque success.
     *
     * Each item is attempted independently: one SecurityException must not take the rest down,
     * because partial tuning that says which part failed is far more useful than an all-or-nothing
     * shell string that silently did nothing for months.
     */
    fun apply(ctx: Context) {
        holdWifiLock(ctx)
        val cr = ctx.contentResolver
        var ok = 0
        var failed = 0

        // A release build (ro.hione.release=1) leaves adb and developer options to the user.
        val release = isReleaseBuild()
        for ((key, value) in GLOBAL_INTS) {
            if (release && key in DEV_ONLY_GLOBALS) continue
            val current = runCatching { Settings.Global.getInt(cr, key, Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
            if (current == value) { ok++; continue }
            runCatching { Settings.Global.putInt(cr, key, value) }
                .onSuccess { ok++; Log.i(TAG, "global $key: $current -> $value") }
                .onFailure { failed++; Log.w(TAG, "global $key failed: ${it.javaClass.simpleName}: ${it.message}") }
        }

        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        for ((opName, packages) in APP_OPS) {
            for (pkg in packages) {
                val uid = runCatching { ctx.packageManager.getPackageUid(pkg, 0) }.getOrNull()
                if (uid == null) { Log.d(TAG, "appops: $pkg not installed, skipping"); continue }
                runCatching {
                    // setMode(String, int, String, int) is @SystemApi behind MANAGE_APP_OPS_MODES.
                    // The String overload is used so ops can be named directly; the int constants
                    // for several of these are hidden and differ across releases.
                    val m = AppOpsManager::class.java.getMethod(
                        "setMode", String::class.java, Int::class.javaPrimitiveType,
                        String::class.java, Int::class.javaPrimitiveType
                    )
                    m.invoke(appOps, opName, uid, pkg, AppOpsManager.MODE_ALLOWED)
                }.onSuccess { ok++; Log.i(TAG, "appop $opName allowed for $pkg") }
                 .onFailure { failed++; Log.w(TAG, "appop $opName for $pkg failed: ${why(it)}") }
            }
        }

        for ((pkg, perm) in RUNTIME_GRANTS) {
            val already = runCatching {
                ctx.packageManager.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (already) { ok++; continue }
            runCatching {
                // grantRuntimePermission is @SystemApi behind GRANT_RUNTIME_PERMISSIONS.
                val m = PackageManager::class.java.getMethod(
                    "grantRuntimePermission", String::class.java, String::class.java,
                    android.os.UserHandle::class.java
                )
                m.invoke(ctx.packageManager, pkg, perm, android.os.Process.myUserHandle())
            }.onSuccess { ok++; Log.i(TAG, "granted $perm to $pkg") }
             .onFailure { failed++; Log.w(TAG, "grant $perm to $pkg failed: ${why(it)}") }
        }

        // The original string also pinned the default IME to
        // com.android.inputmethod.latin/.LatinIME. That is carried over, but only when the IME is
        // actually present: HioneOS ships HeliBoard, so on a current build that component may not
        // exist, and writing a default_input_method that resolves to nothing leaves the device
        // with no keyboard at all. Checked first, skipped loudly if absent.
        runCatching {
            val ime = "com.android.inputmethod.latin/.LatinIME"
            val pkg = ime.substringBefore('/')
            val installed = ctx.packageManager.getInstalledPackages(0).any { it.packageName == pkg }
            if (!installed) {
                Log.i(TAG, "default IME $pkg not installed, leaving the keyboard setting alone")
            } else if (Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD).let { it.isNullOrEmpty() || it == "null" }) {
                // Seed only. Running this on every launcher start switched the user's chosen
                // keyboard back to LatinIME each time.
                Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, ime)
                Settings.Secure.putString(cr, "enabled_input_methods", ime)
                Log.i(TAG, "default IME seeded to $ime")
            }
        }.onFailure { Log.w(TAG, "IME setting failed: ${it.javaClass.simpleName}: ${it.message}") }

        Log.i(TAG, "startup tuning: $ok applied or already set, $failed failed")
    }

    private val DEV_ONLY_GLOBALS = setOf("adb_enabled", "development_settings_enabled")

    /** ro.hione.release=1 is set by build_hioneos_super.sh HIONEOS_RELEASE=1. */
    fun isReleaseBuild(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "ro.hione.release") as String
    }.getOrDefault("") == "1"
}
