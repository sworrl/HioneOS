package com.hione.systemui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Keeps the user's rotation choice (auto-rotate on or off, and the orientation a locked screen is
 * held in) across a reboot.
 *
 * Root cause of "rotation lock does not persist": the ROM's init rc runs
 * `settings put system accelerometer_rotation 0` on every sys.boot_completed=1, not only on first
 * boot, so whatever the user picked was overwritten each boot. That line belongs in the ROM
 * build. Until a build without it is flashed, this restores the choice after boot.
 *
 * The choice is saved in Settings.Global hione_rotation_choice as "auto" or "locked:<rotation>".
 * It is written by the quick settings tile and by an observer that follows changes made anywhere
 * else (stock Settings). Changes in the first [BOOT_WINDOW_MS] after boot are not recorded, since
 * those are the boot script, not the user.
 */
object HioneRotationKeeper {
    private const val TAG = "HioneRotation"
    private const val KEY = "hione_rotation_choice"
    private const val BOOT_WINDOW_MS = 120_000L
    private val RESTORE_AT_MS = longArrayOf(4_000L, 15_000L, 40_000L, 90_000L)

    private var observer: ContentObserver? = null
    private val main = Handler(Looper.getMainLooper())

    fun record(ctx: Context, auto: Boolean, rotation: Int) {
        val v = if (auto) "auto" else "locked:$rotation"
        runCatching { Settings.Global.putString(ctx.contentResolver, KEY, v) }
            .onFailure { Log.w(TAG, "record: $it") }
    }

    private fun inBootWindow() = SystemClock.elapsedRealtime() < BOOT_WINDOW_MS

    /** Call once the navigation service is up. Restores after boot, then follows changes. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (inBootWindow()) {
            RESTORE_AT_MS.forEach { at ->
                val delay = (at - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                main.postDelayed({ restore(app) }, delay)
            }
        }
        if (observer == null) {
            val o = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    if (inBootWindow()) return
                    val cr = app.contentResolver
                    val auto = runCatching { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }.getOrDefault(false)
                    val rot = runCatching { Settings.System.getInt(cr, Settings.System.USER_ROTATION, 0) }.getOrDefault(0)
                    record(app, auto, rot)
                }
            }
            runCatching {
                app.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, o)
                app.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.USER_ROTATION), false, o)
                observer = o
            }.onFailure { Log.w(TAG, "observer: $it") }
        }
    }

    private fun restore(ctx: Context) {
        val cr = ctx.contentResolver
        val v = runCatching { Settings.Global.getString(cr, KEY) }.getOrNull() ?: return
        val auto = v == "auto"
        val rot = if (auto) null else v.substringAfter("locked:", "").toIntOrNull()
        runCatching {
            if (rot != null && Settings.System.getInt(cr, Settings.System.USER_ROTATION, 0) != rot) {
                Settings.System.putInt(cr, Settings.System.USER_ROTATION, rot)
            }
            val want = if (auto) 1 else 0
            if (Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) != want) {
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, want)
                Log.i(TAG, "restored rotation choice after boot: $v")
            }
        }.onFailure { Log.w(TAG, "restore: $it") }
    }
}
