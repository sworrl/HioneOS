package com.hione.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Opens the one DAC settings page on HioneOS: the Hardware app (com.m500.hardware).
 * Hione Music has no DAC page of its own: its DAC buttons open this. Listening profiles still apply DAC settings themselves through HibyDacBridge.
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
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
}
