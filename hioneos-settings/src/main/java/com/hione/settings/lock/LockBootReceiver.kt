package com.hione.settings.lock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

/**
 * After a restart with a screen lock set, puts the HioneOS unlock pad over the stock keyguard so
 * the first unlock (the one that unlocks storage) is entered on the Hione pad.
 *
 * Direct boot aware: LOCKED_BOOT_COMPLETED arrives before the user's storage is unlocked, which is
 * exactly when the pad is needed. The Hione lockscreen in the launcher cannot run yet at that point.
 */
class LockBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        try {
            val um = context.getSystemService(UserManager::class.java)
            val km = context.getSystemService(KeyguardManager::class.java)
            val type = HioneLock.credentialType(context)
            if (um?.isUserUnlocked == false && km?.isDeviceSecure == true &&
                (type == HioneLock.TYPE_PIN || type == HioneLock.TYPE_PASSWORD)
            ) {
                HioneUnlockActivity.start(context)
            }
        } catch (t: Throwable) {
            Log.w("HioneUnlock", "boot pad failed", t)
        }
    }
}
