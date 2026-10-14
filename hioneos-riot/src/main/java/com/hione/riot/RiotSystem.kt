package com.hione.riot

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/** Small reads and writes against the rest of the OS. No state lives here. */
object RiotSystem {
    private const val TAG = "RiotSystem"

    const val UNLOCK_ID = "secret.os.riot"
    const val LAUNCHER_PKG = "com.hione.launcher"
    const val FM_PKG = "com.caf.fmradio"

    // ---- unlock -------------------------------------------------------------------------------

    /**
     * Earned when `secret.os.riot` is a key in Settings.Global `hione_unlocks`. The BPM game in the
     * launcher is the only writer. Presence alone counts, the same rule pickers use: once earned it
     * stays earned, whatever the Secrets list switch says. Anything unreadable counts as locked.
     */
    fun isUnlocked(ctx: Context): Boolean = runCatching {
        val raw = Settings.Global.getString(ctx.contentResolver, "hione_unlocks")
        !raw.isNullOrBlank() && JSONObject(raw).has(UNLOCK_ID)
    }.getOrDefault(false)

    // ---- battery and "disk" -------------------------------------------------------------------

    /**
     * [charging]: on external power and still filling (the firmware's CHARGE gauge).
     * [plugged]: on external power at all. [usb]: connected to a computer (the Riot's USB plug icon).
     */
    data class Battery(val pct: Int, val charging: Boolean, val plugged: Boolean = false, val usb: Boolean = false)

    fun battery(ctx: Context): Battery {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        return Battery(pct, charging, plugged, usbHost(ctx))
    }

    /** Plugged into a computer (a USB host), whatever the transfer mode. A wall charger is not. */
    private fun usbHost(ctx: Context): Boolean = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE")) ?: return false
        i.getBooleanExtra("connected", false)
    }.getOrDefault(false)

    /** Shared storage, standing in for the Riot's hard drive: (used bytes, total bytes). */
    fun disk(): Pair<Long, Long> = runCatching {
        val s = StatFs(Environment.getExternalStorageDirectory().path)
        val total = s.blockCountLong * s.blockSizeLong
        val free = s.availableBlocksLong * s.blockSizeLong
        (total - free) to total
    }.getOrDefault(0L to 0L)

    /** True while plugged into a computer in file transfer (MTP or PTP) mode. */
    fun usbTransfer(ctx: Context): Boolean = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE")) ?: return false
        i.getBooleanExtra("connected", false) && (i.getBooleanExtra("mtp", false) || i.getBooleanExtra("ptp", false))
    }.getOrDefault(false)

    fun global(ctx: Context, key: String): String? =
        runCatching { Settings.Global.getString(ctx.contentResolver, key) }.getOrNull()

    // ---- FM ------------------------------------------------------------------------------------
    // The radio goes through the FM app's media session, see RiotFm.

    fun openFmApp(ctx: Context): Boolean = launch(ctx, FM_PKG)

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    // ---- home ----------------------------------------------------------------------------------

    fun isDefaultHome(ctx: Context): Boolean {
        val r = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == ctx.packageName
    }

    /**
     * Leave Riot mode for good: hand HOME back to the Hione launcher, then open it.
     *
     * Three layers, because which one works depends on the build. The role call is a system API,
     * reached by reflection, and needs MANAGE_ROLE_HOLDERS (a platform signature permission this
     * APK asks for). Clearing our own preferred activities needs no permission at all and covers
     * a pre-role "Always" choice. If HOME still resolves to us after both, the Home app picker
     * opens so the user can pick, rather than landing back here.
     */
    fun exitToHione(activity: Activity) {
        val ctx = activity.applicationContext
        if (isDefaultHome(ctx)) {
            if (Build.VERSION.SDK_INT >= 29) giveHomeRoleTo(ctx, LAUNCHER_PKG)
            runCatching { ctx.packageManager.clearPackagePreferredActivities(ctx.packageName) }
                .onFailure { Log.w(TAG, "clearPackagePreferredActivities: $it") }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setPackage(LAUNCHER_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { activity.startActivity(home); true }.getOrDefault(false) ||
            launch(ctx, LAUNCHER_PKG)
        if (!started || isDefaultHome(ctx)) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        activity.finishAndRemoveTask()
    }

    private fun giveHomeRoleTo(ctx: Context, pkg: String) {
        runCatching {
            val rm = ctx.getSystemService(RoleManager::class.java) ?: return
            val m = RoleManager::class.java.getMethod(
                "addRoleHolderAsUser", String::class.java, String::class.java, Int::class.javaPrimitiveType,
                android.os.UserHandle::class.java, java.util.concurrent.Executor::class.java,
                java.util.function.Consumer::class.java)
            m.invoke(rm, RoleManager.ROLE_HOME, pkg, 0, Process.myUserHandle(), ctx.mainExecutor,
                java.util.function.Consumer<Boolean> { ok -> Log.i(TAG, "home role to $pkg: $ok") })
        }.onFailure { Log.w(TAG, "home role handover not available: $it") }
    }

    @Suppress("unused")
    fun component(ctx: Context) = ComponentName(ctx, RiotActivity::class.java)
}
