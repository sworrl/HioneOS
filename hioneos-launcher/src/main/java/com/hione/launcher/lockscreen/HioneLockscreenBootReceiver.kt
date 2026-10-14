package com.hione.launcher.lockscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Installs the OS-level Hione lockscreen at boot. Fires on BOOT_COMPLETED (registered in the
 * launcher manifest with RECEIVE_BOOT_COMPLETED) so the SCREEN_ON/SCREEN_OFF state machine is
 * armed even if the user never opens the launcher UI after a reboot.
 */
class HioneLockscreenBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                HioneLockscreenManager.install(context.applicationContext)
            } catch (t: Throwable) {
                Log.e("HioneLockscreen", "Boot install failed", t)
            }
        }
    }
}
