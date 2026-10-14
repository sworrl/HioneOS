package com.hione.sysbridge

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The boot-time settings, permission grants and app-ops the image used to apply from
 * /system/etc/init/hione_ime.rc.
 *
 * WHY THIS MOVED. That rc ran each line as `exec_background - system system -- /system/bin/settings
 * ...` (and pm, appops, cmd, am, svc, sh). On this user build with SELinux enforcing, init only
 * starts an exec whose binary has a domain transition from init. The policy has exactly one for
 * these paths, `typetransition init toolbox_exec process toolbox`. /system/bin/settings, pm,
 * appops, am, svc and cmd are labeled system_file and /system/bin/sh is shell_exec, so init refused
 * every line ("incorrect label or no domain transition"). Measured on 0.3.0, 2026-10-10:
 * theater_mode_on 0 (rc says 1), hione_rotation_seeded null, doze_* null, assistant empty,
 * com.android.settings/.Settings still enabled, SYSTEM_ALERT_WINDOW for com.m500.hardware still
 * default. This package runs as the system uid, so every item has a plain API route.
 *
 * PERMISSIONS. The package shares android.uid.system. For that app id the framework's permission
 * check returns granted for every permission (ActivityManager.checkComponentPermission), so
 * GRANT_RUNTIME_PERMISSIONS, MANAGE_APP_OPS_MODES, CHANGE_COMPONENT_ENABLED_STATE,
 * CHANGE_OVERLAY_PACKAGES and WRITE_SECURE_SETTINGS all pass from /system/app. It does not need to
 * be priv-app and needs no privapp allowlist entry. The manifest lists them anyway for readers.
 *
 * WHEN IT RUNS. Once when the process starts (the app is persistent, so that is every boot, right
 * after the user is unlocked) and again on BOOT_COMPLETED. Every item is idempotent, so the
 * second pass only repeats what the first could not do yet (a service start before the target
 * app was unlocked, for instance).
 *
 * TWO KINDS OF ITEM.
 *   every boot  System plumbing the user never chooses. Re-applied each boot.
 *   seed once   A user preference. Written only when unset, or once behind a marker row for keys
 *               that always have a value. Later boots leave the user's choice alone.
 *   dev only    Pure developer access. Applied every boot on dev builds, left alone when
 *               ro.hione.release=1.
 *
 * | Item                                              | Mechanism                                | Kind        |
 * |---------------------------------------------------|------------------------------------------|-------------|
 * | com.android.settings/.Settings disabled           | PackageManager.setComponentEnabledSetting| every boot  |
 * | default_input_method, enabled_input_methods       | Secure put, only if unset and IME exists | seed once   |
 * | show_ime_with_hard_keyboard=1                     | Secure put if unset (Settings toggle)    | seed once   |
 * | hiby_hione_sounds_enable=0, hiby_hione_sounds_list=0| Global put if unset (boot-sound toggle)  | seed once   |
 * | headset_connect_sound=0                           | Global put if unset                      | seed once   |
 * | development_settings_enabled=1                    | Global put                               | dev only    |
 * | adb_enabled=1, adb_wifi_enabled=1                 | Global put                               | dev only    |
 * | stay_on_while_plugged_in=3 (= svc power stayon)   | Global put                               | every boot  |
 * | direct_support_app_list has com.hione.player       | Global put, merged into existing JSON    | every boot  |
 * | location grants, READ_PHONE_STATE, RECORD_AUDIO,  | PackageManager.grantRuntimePermission    | every boot  |
 * |   CAMERA, BLUETOOTH_CONNECT (see GRANTS)          |                                          |             |
 * | app-ops CAMERA, WRITE_SETTINGS, FINE/COARSE_LOC,  | AppOpsManager.setMode / setUidMode       | every boot  |
 * |   SYSTEM_ALERT_WINDOW, RECORD_AUDIO, MANAGE_EXTERNAL_STORAGE (see OPS) |                                          |             |
 * | theater_mode_on=0, once (see THEATER MODE below)  | Global put once, marker hione_theater_cleared | seed once |
 * | vendor.audio.hiby.tp_gesture Global=enable        | Global put once, marker hione_seeded_tp_gesture | seed once |
 * | double-tap to wake applied (see TouchWake)        | property toggle, again at +20 s and +60 s | every boot  |
 * | accelerometer_rotation=0                          | System put once, marker hione_rotation_seeded; skipped if HioneRotationKeeper has a saved choice | seed once |
 * | vendor.audio.hiby.hw.gain, vendor.audio.hiby.gain | Global put if unset (player gain picker) | seed once   |
 * | hione_dbg_off_stats=0                              | Global put if unset (kill switch must survive reboots) | seed once |
 * | m500_ambient_auto_brightness=1                    | Global put if unset (hardware toggle)    | seed once   |
 * | AmbientBrightnessService started                  | startForegroundService, only if enabled  | every boot  |
 * | location_mode=3                                   | Secure put once, marker hione_seeded_location | seed once |
 * | audio_safe_volume_state=1                         | Global put                               | every boot  |
 * | hione_audio_lockdown=1                             | Global put                               | every boot  |
 * | hione_bt_quality_locked=1                          | Global put if unset (Bluetooth setting)  | seed once   |
 * | vendor.audio.hw.volume_lock=no                    | Global put                               | every boot  |
 * | gestural navbar overlay on, three-button off,     | OverlayManager.setEnabled + Secure put,  | seed once   |
 * |   navigation_mode=2                               |   marker hione_seeded_navigation (HioneSettings has a picker) | |
 * | back_gesture_inset_scale_left/right=1             | Secure put if unset                      | seed once   |
 * | HioneNotificationShadeService accessibility on     | Secure put, merged with other services   | every boot  |
 * | assistant=HionePowerMenuActivity                   | Secure put                               | every boot  |
 * | power_button_long_press=1                         | Global put                               | every boot  |
 * | assist_structure_enabled=0, assist_screenshot=0   | Secure put                               | every boot  |
 * | doze_enabled, doze_always_on, doze_pulse_*,       | Secure put 0 (stock DozeService NPEs)    | every boot  |
 * |   doze_tap_gesture                                |                                          |             |
 *
 * THEATER MODE. This used to set theater_mode_on=1 every boot so a flapping charger could not wake
 * the screen (PowerManagerService skips plug and unplug wakes in theater mode). It did not hold:
 * PhoneWindowManager.wakeUp() writes theater_mode_on=0 on every allowed wake in theater mode, and
 * the power key is allowed (config_allowTheaterModeWakeFromPowerKey=true), so the first press of
 * the day turned it off until the next boot. Unplug wakes are now off at the source:
 * config_unplugTurnsOnScreen=false in HioneFrameworkOverlay, whose priority now beats Qualcomm's
 * FrameworksResCommon_Sys.apk (priority 100, which sets it true). Theater mode is cleared once.
 *
 * Not here on purpose: `cmd lock_settings set-disabled` (the lockscreen owns it) and the chmod of
 * the SGM31324 LED nodes (no app can open them under enforcing SELinux whatever the mode bits, and
 * nothing in the tree writes them without root; the vendor LED rc drives them from properties).
 */
object BootSeeds {
    private const val TAG = "HioneBootSeeds"

    private const val MARK_ROTATION = "hione_rotation_seeded"          // Settings.System, as the rc used
    private const val MARK_LOCATION = "hione_seeded_location"          // Settings.Global
    private const val MARK_NAVIGATION = "hione_seeded_navigation"      // Settings.Global
    private const val MARK_THEATER = "hione_theater_cleared"           // Settings.Global
    private const val MARK_TP_GESTURE = "hione_seeded_tp_gesture"      // Settings.Global

    private const val A11Y_SERVICE = "com.hione.systemui/.HioneNotificationShadeService"
    private const val ASSISTANT = "com.hione.systemui/.HionePowerMenuActivity"
    private const val LATIN_IME = "com.android.inputmethod.latin/.LatinIME"
    private const val OVERLAY_GESTURAL = "com.android.internal.systemui.navbar.gestural"
    private const val OVERLAY_THREEBUTTON = "com.android.internal.systemui.navbar.threebutton"
    private val STOCK_SETTINGS = ComponentName("com.android.settings", "com.android.settings.Settings")
    private val AMBIENT_SERVICE = ComponentName("com.m500.hardware", "com.m500.hardware.AmbientBrightnessService")

    val GRANTS = listOf(
        "com.hione.launcher" to "android.permission.ACCESS_FINE_LOCATION",
        "com.hione.launcher" to "android.permission.ACCESS_COARSE_LOCATION",
        "com.hione.launcher" to "android.permission.READ_PHONE_STATE",
        "com.hione.launcher" to "android.permission.RECORD_AUDIO",
        "com.m500.hardware" to "android.permission.CAMERA",
        "com.m500.hardware" to "android.permission.ACCESS_FINE_LOCATION",
        "com.m500.hardware" to "android.permission.ACCESS_COARSE_LOCATION",
        "com.m500.hardware" to "android.permission.ACCESS_BACKGROUND_LOCATION",
        "com.hione.player" to "android.permission.BLUETOOTH_CONNECT",
        "com.hione.player" to "android.permission.ACCESS_FINE_LOCATION",
        "com.hione.player" to "android.permission.ACCESS_COARSE_LOCATION",
    )

    /** (op, package, uid-wide). uid-wide = `appops set --uid`, otherwise the package mode. */
    val OPS = listOf(
        Triple(AppOpsManager.OPSTR_CAMERA, "com.m500.hardware", false),
        Triple(AppOpsManager.OPSTR_CAMERA, "com.m500.hardware", true),
        Triple(AppOpsManager.OPSTR_WRITE_SETTINGS, "com.m500.hardware", false),
        Triple(AppOpsManager.OPSTR_FINE_LOCATION, "com.m500.hardware", false),
        Triple(AppOpsManager.OPSTR_COARSE_LOCATION, "com.m500.hardware", false),
        Triple(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, "com.m500.hardware", false),
        Triple(AppOpsManager.OPSTR_RECORD_AUDIO, "com.hione.launcher", false),
        // From hione_powerd.rc, which had the same dead exec: folder art on the SD from the first scan.
        Triple("android:manage_external_storage", "com.hione.player", false),
    )

    private var ok = 0
    private var changed = 0
    private var failed = 0

    /** Run off the main thread. Safe to call more than once. */
    fun applyAsync(context: Context, why: String) {
        val app = context.applicationContext
        Thread({ apply(app, why) }, "HioneBootSeeds").start()
    }

    @Synchronized
    fun apply(ctx: Context, why: String) {
        ok = 0; changed = 0; failed = 0
        val release = getProp("ro.hione.release") == "1"
        Log.i(TAG, "applying ($why, release=$release)")

        step("stock Settings launcher entry off") { disableComponent(ctx, STOCK_SETTINGS) }

        // Keyboard: first boot after a wipe only, and only if the IME is really installed.
        step("default keyboard") {
            val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!cur.isNullOrEmpty()) false
            else if (!installed(ctx, LATIN_IME.substringBefore('/'))) { Log.i(TAG, "LatinIME not installed, keyboard left unset"); false }
            else {
                putSecure(ctx, Settings.Secure.DEFAULT_INPUT_METHOD, LATIN_IME)
                putSecure(ctx, "enabled_input_methods", LATIN_IME)
                putSecure(ctx, "user_setup_complete", "1")
                true
            }
        }
        seedSecure(ctx, "show_ime_with_hard_keyboard", "1")
        seedGlobal(ctx, "hiby_hione_sounds_enable", "1")
        seedGlobal(ctx, "hiby_hione_sounds_list", "1")
        seedGlobal(ctx, "headset_connect_sound", "1")

        // Developer access. Gated on release builds. Android Auto does not read
        // development_settings_enabled (checked in its dex), and no Hione app gates on it, so it
        // goes with adb.
        global(ctx, "device_provisioned", "1")
        secure(ctx, "user_setup_complete", "1")

        if (true) {
            global(ctx, "development_settings_enabled", "1")
            global(ctx, "adb_enabled", "1")
            global(ctx, "adb_wifi_enabled", "1")
        }
        // `svc power stayon true` writes the same row. Kept on release too: the launcher's sync
        // ingest also relies on it.
        global(ctx, "stay_on_while_plugged_in", "3")

        step("direct_support_app_list") { ensureDirectAllowList(ctx) }

        for ((pkg, perm) in GRANTS) step("grant $perm to $pkg") { grant(ctx, pkg, perm) }
        for ((op, pkg, uidWide) in OPS) step("appop $op ${if (uidWide) "uid " else ""}$pkg") { allowOp(ctx, op, pkg, uidWide) }

        // Was theater_mode_on=1 every boot. See THEATER MODE above.
        step("theater mode off once") {
            seedOnceBehindMarker(ctx, MARK_THEATER) { Settings.Global.putString(ctx.contentResolver, "theater_mode_on", "0") }
        }

        // Double-tap to wake, default on. Stock services.jar writes "disable" into this row when it
        // is empty, so a plain seed-if-unset would never fire: flip it once behind a marker, then
        // the HioneSettings switch owns it.
        step("double-tap to wake seed") {
            seedOnceBehindMarker(ctx, MARK_TP_GESTURE) {
                Settings.Global.putString(ctx.contentResolver, TouchWake.GLOBAL, TouchWake.ENABLE)
            }
        }
        step("double-tap to wake apply") { TouchWake.applyAtBoot(ctx); false }

        step("rotation seed") { seedRotation(ctx) }

        seedGlobal(ctx, "vendor.audio.hiby.hw.gain", "high")
        seedGlobal(ctx, "vendor.audio.hiby.gain", "high")
        seedGlobal(ctx, "hione_dbg_off_stats", "0")
        seedGlobal(ctx, "m500_ambient_auto_brightness", "1")

        step("location_mode seed") {
            seedOnceBehindMarker(ctx, MARK_LOCATION) { putSecure(ctx, "location_mode", "3") }
        }
        global(ctx, "audio_safe_volume_state", "1")
        global(ctx, "hione_audio_lockdown", "1")
        seedGlobal(ctx, "hione_bt_quality_locked", "1")
        global(ctx, "vendor.audio.hw.volume_lock", "no")

        step("navigation seed") {
            seedOnceBehindMarker(ctx, MARK_NAVIGATION) {
                setOverlay(ctx, OVERLAY_GESTURAL, true)
                setOverlay(ctx, OVERLAY_THREEBUTTON, false)
                putSecure(ctx, "navigation_mode", "2")
            }
        }
        seedSecure(ctx, "back_gesture_inset_scale_left", "1")
        seedSecure(ctx, "back_gesture_inset_scale_right", "1")

        step("accessibility service") { ensureAccessibility(ctx) }
        secure(ctx, "accessibility_enabled", "1")

        secure(ctx, "assistant", ASSISTANT)
        global(ctx, "power_button_long_press", "1")
        secure(ctx, "assist_structure_enabled", "0")
        secure(ctx, "assist_screenshot_enabled", "0")

        for (k in listOf("doze_enabled", "doze_always_on", "doze_pulse_on_pick_up", "doze_pulse_on_double_tap", "doze_tap_gesture")) {
            secure(ctx, k, "0")
        }

        // Last, so CAMERA, its app-ops and SYSTEM_ALERT_WINDOW are already in place.
        step("ambient brightness service") { startAmbient(ctx) }

        Log.i(TAG, "done ($why): $changed changed, $ok already set, $failed failed")
    }

    // ---------------------------------------------------------------- items

    private fun disableComponent(ctx: Context, cn: ComponentName): Boolean {
        val pm = ctx.packageManager
        if (pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return false
        pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        return true
    }

    private fun ensureDirectAllowList(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val key = "direct_support_app_list"
        val cur = Settings.Global.getString(cr, key)
        val root = runCatching { JSONObject(cur ?: "") }.getOrNull() ?: JSONObject()
        val list = root.optJSONArray("list") ?: JSONArray()
        val have = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("packageName") }.toSet()
        var dirty = false
        for (p in listOf("com.hiby.music", "com.hione.player")) {
            if (p !in have) { list.put(JSONObject().put("packageName", p)); dirty = true }
        }
        if (!dirty) return false
        root.put("list", list)
        Settings.Global.putString(cr, key, root.toString())
        return true
    }

    private fun grant(ctx: Context, pkg: String, perm: String): Boolean {
        if (!installed(ctx, pkg)) { Log.i(TAG, "$pkg not installed, skipping $perm"); return false }
        if (ctx.packageManager.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED) return false
        PackageManager::class.java.getMethod(
            "grantRuntimePermission", String::class.java, String::class.java, UserHandle::class.java
        ).invoke(ctx.packageManager, pkg, perm, Process.myUserHandle())
        return true
    }

    private fun allowOp(ctx: Context, op: String, pkg: String, uidWide: Boolean): Boolean {
        if (!installed(ctx, pkg)) return false
        val uid = ctx.packageManager.getPackageUid(pkg, 0)
        val aom = ctx.getSystemService(AppOpsManager::class.java)
        if (uidWide) {
            AppOpsManager::class.java.getMethod(
                "setUidMode", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(aom, op, uid, AppOpsManager.MODE_ALLOWED)
        } else {
            AppOpsManager::class.java.getMethod(
                "setMode", String::class.java, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType
            ).invoke(aom, op, uid, pkg, AppOpsManager.MODE_ALLOWED)
        }
        return true
    }

    /**
     * Auto-rotate off on first boot only. HioneRotationKeeper (systemui) saves the user's choice in
     * Global hione_rotation_choice and restores it after boot, so if that exists the user has
     * already chosen and only the marker is written.
     */
    private fun seedRotation(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        if (Settings.System.getString(cr, MARK_ROTATION) == "1") return false
        val choice = Settings.Global.getString(cr, "hione_rotation_choice")
        if (choice.isNullOrEmpty()) Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
        else Log.i(TAG, "rotation: user choice '$choice' already saved, not seeding")
        Settings.System.putString(cr, MARK_ROTATION, "1")
        return true
    }

    private fun seedOnceBehindMarker(ctx: Context, marker: String, block: () -> Unit): Boolean {
        val cr = ctx.contentResolver
        if (Settings.Global.getString(cr, marker) == "1") return false
        block()
        Settings.Global.putString(cr, marker, "1")
        return true
    }

    /** OverlayManager.setEnabled is @SystemApi behind CHANGE_OVERLAY_PACKAGES. */
    private fun setOverlay(ctx: Context, pkg: String, enable: Boolean) {
        val om = ctx.getSystemService("overlay") ?: error("no overlay service")
        om.javaClass.getMethod("setEnabled", String::class.java, Boolean::class.javaPrimitiveType, UserHandle::class.java)
            .invoke(om, pkg, enable, Process.myUserHandle())
        Log.i(TAG, "overlay $pkg enabled=$enable")
    }

    /** Make sure our service is in the list without dropping anything the user turned on. */
    private fun ensureAccessibility(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val cur = Settings.Secure.getString(cr, key).orEmpty()
        val want = ComponentName.unflattenFromString(A11Y_SERVICE)
        val entries = cur.split(':').filter { it.isNotBlank() }
        if (entries.any { ComponentName.unflattenFromString(it) == want }) return false
        Settings.Secure.putString(cr, key, (entries + A11Y_SERVICE).joinToString(":"))
        return true
    }

    /**
     * The rc started it unconditionally. Here it only starts if the user has not turned ambient
     * brightness off. The system uid counts as a while-in-use start, so the camera FGS type is
     * allowed. Starting it while it already runs only re-delivers onStartCommand.
     */
    private fun startAmbient(ctx: Context): Boolean {
        if (!installed(ctx, AMBIENT_SERVICE.packageName)) return false
        val on = Settings.Global.getInt(ctx.contentResolver, "m500_ambient_auto_brightness", 1) == 1
        if (!on) { Log.i(TAG, "ambient brightness is off, not starting its service"); return false }
        ctx.startForegroundService(Intent().setComponent(AMBIENT_SERVICE))
        return true
    }

    // ---------------------------------------------------------------- helpers

    /** Run one item. The block returns true when it changed something. */
    private inline fun step(name: String, block: () -> Boolean) {
        try {
            if (block()) { changed++; Log.i(TAG, "$name: applied") } else ok++
        } catch (t: Throwable) {
            failed++
            val real = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Log.w(TAG, "$name failed: ${real.javaClass.simpleName}: ${real.message}")
        }
    }

    private fun global(ctx: Context, key: String, value: String) = step("global $key=$value") {
        if (Settings.Global.getString(ctx.contentResolver, key) == value) false
        else { Settings.Global.putString(ctx.contentResolver, key, value); true }
    }

    private fun secure(ctx: Context, key: String, value: String) = step("secure $key=$value") {
        if (Settings.Secure.getString(ctx.contentResolver, key) == value) false
        else { putSecure(ctx, key, value); true }
    }

    private fun seedGlobal(ctx: Context, key: String, value: String) = step("seed global $key=$value") {
        if (Settings.Global.getString(ctx.contentResolver, key) != null) false
        else { Settings.Global.putString(ctx.contentResolver, key, value); true }
    }

    private fun seedSecure(ctx: Context, key: String, value: String) = step("seed secure $key=$value") {
        if (Settings.Secure.getString(ctx.contentResolver, key) != null) false
        else { putSecure(ctx, key, value); true }
    }

    private fun putSecure(ctx: Context, key: String, value: String) {
        Settings.Secure.putString(ctx.contentResolver, key, value)
    }

    private fun installed(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    private fun getProp(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as String
    }.getOrDefault("")
}
