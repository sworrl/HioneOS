package com.hione.systemui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the Hione shade may show about notifications right now.
 *
 * "Locked" means the device needs the PIN or password to open (KeyguardManager.isDeviceLocked).
 * Without a screen lock, or while the HioneOS trust agent holds the device open, that is false and
 * the shade shows everything, as before. While locked:
 *  - Settings.Secure lock_screen_show_notifications = 0 hides notifications.
 *  - Otherwise each notification shows only its app, unless the user turned on
 *    "Show notification content when locked" (Settings.Secure hione_lock_show_notif_content = 1,
 *    written by HioneSettings > Security > Screen lock). Default is hidden content.
 *  - In Lockdown (power menu) nothing shows until the PIN is entered.
 */
object HioneLockPrivacy {
    private const val TAG = "HioneLockPrivacy"
    const val KEY_SHOW_CONTENT = "hione_lock_show_notif_content"
    const val HIDDEN_TEXT = "Unlock to see this notification"

    enum class Mode { FULL, REDACTED, HIDDEN }

    private val _mode = MutableStateFlow(Mode.FULL)
    val mode: StateFlow<Mode> = _mode

    private fun km(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java)

    private fun lockedMode(ctx: Context): Mode {
        val cr = ctx.contentResolver
        return when {
            // Power menu Lockdown hides every notification until the PIN is entered, like stock.
            HioneLockdown.active(ctx) -> Mode.HIDDEN
            runCatching { Settings.Secure.getInt(cr, "lock_screen_show_notifications", 1) == 0 }.getOrDefault(false) -> Mode.HIDDEN
            runCatching { Settings.Secure.getInt(cr, KEY_SHOW_CONTENT, 0) == 1 }.getOrDefault(false) -> Mode.FULL
            else -> Mode.REDACTED
        }
    }

    fun refresh(ctx: Context) {
        val locked = runCatching { km(ctx)?.isDeviceLocked == true }.getOrDefault(false)
        _mode.value = if (locked) lockedMode(ctx) else Mode.FULL
    }

    /**
     * Screen off with a screen lock set: assume locked until the next refresh says otherwise,
     * so a shade kept composed between pulls never opens with content from before the lock.
     */
    fun onScreenOff(ctx: Context) {
        if (runCatching { km(ctx)?.isDeviceSecure == true }.getOrDefault(false)) _mode.value = lockedMode(ctx)
    }

    /** Keeps [mode] current while registered. Returns the receiver to unregister. */
    fun watch(ctx: Context): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff(ctx)
                    else -> refresh(ctx)
                }
            }
        }
        runCatching {
            ctx.registerReceiver(r, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        }
        refresh(ctx)
        return r
    }

    fun redact(n: HioneNotif): HioneNotif = n.copy(
        title = n.appLabel,
        text = HIDDEN_TEXT,
        bigText = null,
        subText = null,
        contentIntent = null,
        actions = emptyList(),
        messages = emptyList(),
        conversationTitle = null,
        largeIcon = null,
        progress = null,
        progressMax = null,
        progressIndeterminate = false
    )

    /** Opens the HioneOS unlock pad (HioneSettings). */
    fun requestUnlock(ctx: Context) {
        try {
            ctx.startActivity(Intent().setClassName("com.hione.settings", "com.hione.settings.lock.HioneUnlockActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.w(TAG, "unlock pad not available", t)
        }
    }
}
