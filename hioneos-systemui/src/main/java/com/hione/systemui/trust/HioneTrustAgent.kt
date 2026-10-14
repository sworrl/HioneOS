package com.hione.systemui.trust

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.trust.TrustAgentService
import android.util.Log

/**
 * HioneOS trust agent: the part that makes "PIN after a restart, not at every unlock" work.
 *
 * Android needs the credential after every restart to unlock credential encrypted storage. That
 * stays. After that first unlock this agent grants trust, so the keyguard opens with a swipe on
 * the Hione lockscreen until the next restart.
 *
 * The grant is "temporary and renewable" (Android 14 active unlock style): when the screen turns
 * off, the system drops it to "trustable", and this agent renews it when the screen turns on.
 * Renewing only works from "trustable", which the system only reaches after a real credential
 * unlock, so the agent cannot open a device the user has not unlocked since restart.
 *
 * Limits the system enforces, which this does not fight:
 *  - The credential is needed again 24 hours after it was last entered (trustable hard timeout).
 *  - It is also needed when no renewal happened for 8 hours, for example the screen stayed off
 *    overnight (trustable idle timeout).
 *  - Trust is never allowed while strong auth is required: after a restart, after a lockdown,
 *    after too many wrong tries, or after 72 hours without the credential.
 *
 * With Settings.Secure hione_lock_trust_until_restart = 0 the agent only holds trust for the
 * unlock that was just made with the credential, and drops it when the screen turns off, so the
 * credential is asked every time.
 *
 * Enabled through LockPatternUtils.setEnabledTrustAgents by HioneSettings when a screen lock is
 * set. The system only binds it while a credential exists and trust is allowed.
 */
class HioneTrustAgent : TrustAgentService() {
    companion object {
        private const val TAG = "HioneTrustAgent"
        const val KEY_UNTIL_RESTART = "hione_lock_trust_until_restart"
        private const val MESSAGE = "Unlocked since restart"
    }

    private val main = Handler(Looper.getMainLooper())

    private fun untilRestart(): Boolean =
        runCatching { Settings.Secure.getInt(contentResolver, KEY_UNTIL_RESTART, 1) == 1 }.getOrDefault(true)

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> if (untilRestart()) grant("screen on")
                Intent.ACTION_SCREEN_OFF -> if (!untilRestart()) revoke("screen off")
            }
        }
    }

    private val setting = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            // Turning it on takes effect at once (the user is unlocked, they are in Settings).
            // Turning it off takes effect at the next screen off.
            if (untilRestart()) grant("setting on")
        }
    }

    override fun onCreate() {
        super.onCreate()
        setManagingTrust(true)
        runCatching {
            registerReceiver(screen, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        }
        runCatching {
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(KEY_UNTIL_RESTART), false, setting)
        }
        // Bound after a credential unlock (the system does not run agents before one). The grant
        // counts at once if the keyguard is already gone, or on the next reported unlock.
        if (untilRestart()) grant("bound")
        Log.i(TAG, "created, untilRestart=${untilRestart()}")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        runCatching { contentResolver.unregisterContentObserver(setting) }
        super.onDestroy()
    }

    override fun onUnlockAttempt(successful: Boolean) {
        // A credential unlock just happened (stock bouncer or the HioneOS pad). Grant for it in
        // both modes. In "ask every time" mode the grant ends at the next screen off.
        if (successful) grant("unlock")
    }

    override fun onDeviceUnlocked() {
        if (untilRestart()) grant("device unlocked")
    }

    override fun onUserMayRequestUnlock() {
        if (untilRestart()) grant("may request unlock")
    }

    override fun onUserRequestedUnlock(dismissKeyguard: Boolean) {
        if (untilRestart()) grant("requested unlock")
    }

    override fun onTrustTimeout() {
        if (untilRestart()) grant("timeout")
    }

    private fun grant(why: String) {
        try {
            grantTrust(MESSAGE, 0L, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE)
            Log.i(TAG, "grant ($why)")
        } catch (t: Throwable) {
            Log.w(TAG, "grant failed ($why)", t)
        }
    }

    private fun revoke(why: String) {
        try {
            revokeTrust()
            Log.i(TAG, "revoke ($why)")
        } catch (t: Throwable) {
            Log.w(TAG, "revoke failed ($why)", t)
        }
    }
}
