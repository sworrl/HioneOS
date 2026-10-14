package com.hione.tools.clock

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Everything Clock knows about Hione Music (com.hione.player), using only what that app already
 * exposes. Hione Music has its own alarm system (library playlists, sunrise/sunset triggers,
 * bit-perfect playback) whose storage and receivers are private to it, so Clock does not try
 * to read or edit those alarms. Instead:
 *
 *  - [nextHioneAlarm] reads the system's next alarm clock and reports it when it belongs to Hione
 *    Music. AlarmManager.getNextAlarmClock is device wide, so this needs no hook in Hione Music,
 *    but it only ever sees the single soonest alarm across all apps.
 *  - [openHioneAlarms] opens Hione Music for editing those alarms.
 *  - Wake to music: a Clock alarm can start Hione Music playback instead of a tone, either a
 *    shuffle of the library (Hione Music's exported PLAY_RANDOM broadcast) or a resume of what
 *    played last (a media PLAY key, which Android routes to the last active media session).
 */
object HioneMusicBridge {
    const val PKG = "com.hione.player"
    private const val ACTION_PLAY_RANDOM = "com.hione.player.action.PLAY_RANDOM"

    fun isInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    /** Trigger time of Hione Music's next alarm, when it is the device's soonest alarm. */
    fun nextHioneAlarm(ctx: Context): Long? {
        val info = runCatching { ctx.getSystemService(AlarmManager::class.java).nextAlarmClock }.getOrNull() ?: return null
        val creator = runCatching { info.showIntent?.creatorPackage }.getOrNull()
        return if (creator == PKG) info.triggerTime else null
    }

    fun openHioneAlarms(ctx: Context): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        // "open_alarms" is the extra Hione Music's own alarm notifications put on this intent.
        launch.putExtra("open_alarms", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(launch); true }.getOrDefault(false)
    }

    /** Ask Hione Music to start a random library track. */
    fun startShuffle(ctx: Context): Boolean {
        if (!isInstalled(ctx)) return false
        val i = Intent(ACTION_PLAY_RANDOM).setPackage(PKG)
        // Hione Music starts its playback service from that receiver. A receiver woken by
        // another app's broadcast does not normally get to start a foreground service from the
        // background; the sender can grant it a short allowance when it holds
        // START_FOREGROUND_SERVICES_FROM_BACKGROUND (granted to us by the platform key). The API
        // for that is hidden, hence reflection; on any failure it falls back to a plain send,
        // and the ring service's silence check covers the case where nothing plays.
        val opts = allowlistOptions()
        val sent = opts != null && runCatching {
            Context::class.java.getMethod("sendBroadcast", Intent::class.java, String::class.java, Bundle::class.java)
                .invoke(ctx, i, null, opts)
            true
        }.getOrElse { Log.w(TAG, "allowlisted broadcast failed: $it"); false }
        if (!sent) runCatching { ctx.sendBroadcast(i) }.onFailure { return false }
        return true
    }

    private fun allowlistOptions(): Bundle? = runCatching {
        val cls = Class.forName("android.app.BroadcastOptions")
        val opts = cls.getMethod("makeBasic").invoke(null)
        val ok = runCatching {
            // (duration, TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED = 0, REASON_OTHER = 1, reason)
            cls.getMethod("setTemporaryAppAllowlist", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                .invoke(opts, 30_000L, 0, 1, "hione clock wake to music")
            true
        }.getOrElse {
            runCatching {
                cls.getMethod("setTemporaryAppWhitelistDuration", Long::class.javaPrimitiveType).invoke(opts, 30_000L); true
            }.getOrDefault(false)
        }
        if (!ok) null else cls.getMethod("toBundle").invoke(opts) as Bundle
    }.getOrNull()

    /** Resume whatever played last, through the normal media-button routing. */
    fun resume(ctx: Context) = mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)

    fun pause(ctx: Context) = mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)

    private fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val t = SystemClock.uptimeMillis()
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        }
    }

    private const val TAG = "HioneClock"
}
