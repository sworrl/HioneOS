package com.hione.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Opens the one DAC settings page on HioneOS: the Hardware app (com.m500.hardware).
 * The same helper exists in Hione Music, the launcher and SystemUI.
 *
 * Tries the action inside the Hardware package first, then the activity by name (works with a
 * Hardware build that predates the action), then says so if neither is there.
 */
object DacSettingsLink {
    const val ACTION = "com.hione.action.DAC_SETTINGS"
    const val PACKAGE = "com.m500.hardware"
    private const val ACTIVITY = "com.m500.hardware.MainActivity"

    fun open(ctx: Context): Boolean {
        val tries = listOf(
            Intent(ACTION).setPackage(PACKAGE),
            Intent(ACTION).setClassName(PACKAGE, ACTIVITY),
        )
        for (i in tries) {
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Toast.makeText(ctx, "The Hardware app is not installed, so DAC settings are not available.", Toast.LENGTH_LONG).show()
        return false
    }

    /** Blackjack Monitor in the Hardware app: which DAC driver runs and what it is doing. */
    const val MONITOR_ACTION = "com.hione.action.BLACKJACK_MONITOR"

    fun openBlackjackMonitor(ctx: Context): Boolean {
        val i = Intent(MONITOR_ACTION).setPackage(PACKAGE)
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "This Hardware app has no Blackjack Monitor yet. It comes with the next update.", Toast.LENGTH_LONG).show()
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * True when the DAC at 0x30 is bound to Blackjack's driver. Reads the sysfs `driver` symlink:
     * apps may read sysfs links but not stat sysfs directories, so File("/sys/module/x").exists()
     * is always false here.
     */
    fun blackjackLoaded(): Boolean = runCatching {
        android.system.Os.readlink("/sys/bus/i2c/devices/0-0030/driver").substringAfterLast('/').startsWith("blackjack")
    }.getOrDefault(false)
}
