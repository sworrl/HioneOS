package com.hione.player

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log

/**
 * "All files access" (MANAGE_EXTERNAL_STORAGE) is what lets java.io.File see the SD card on
 * API 30+: without it the library walker can't list /storage/<card>, folder cover art
 * (cover.jpg next to the FLACs) is invisible, and ghost-row checks can't run. Android only offers a
 * Settings screen for it — no runtime dialog — so on a stock phone the user has to go flip it.
 *
 * This is HioneOS: the player is platform-signed, which makes it eligible for
 * MANAGE_APP_OPS_MODES and lets it set its own app-op. Confirmed live 2026-08-25 that the op was
 * still "default" (= rejected) on the device — the whole "missing album art" report traced back to
 * this one switch. Self-grant on every launch; harmless when already granted.
 */
object HioneStorageAccess {
    private const val TAG = "HioneStorageAccess"
    private const val OP = "android:manage_external_storage"

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** Try to grant ourselves the op. Returns true when access is (now) held. Cheap when granted. */
    fun ensure(ctx: Context): Boolean {
        if (hasAllFilesAccess()) return true
        if (Build.VERSION.SDK_INT < 30) return true
        val app = ctx.applicationContext
        val uid = app.applicationInfo.uid
        val pkg = app.packageName
        // 1. Platform-signed path: AppOpsManager.setMode(String op, int uid, String pkg, int mode)
        //    (hidden API — platform-signed apps are exempt from the hidden-API restriction).
        runCatching {
            val aom = app.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val m = android.app.AppOpsManager::class.java.getMethod(
                "setMode", String::class.java, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType
            )
            m.invoke(aom, OP, uid, pkg, android.app.AppOpsManager.MODE_ALLOWED)
        }.onFailure { Log.w(TAG, "AppOpsManager.setMode failed: ${it.javaClass.simpleName}: ${it.message}") }
        if (hasAllFilesAccess()) { Log.i(TAG, "All-files access self-granted via AppOps"); return true }
        // No su fallback: AppOpsManager.setMode above IS the privileged path on this
        // platform-signed build. If it did not take, report the failure honestly so the caller can
        // send the user to the "All files access" Settings screen instead of pretending.
        Log.w(TAG, "All-files access NOT held after AppOps self-grant (pkg=$pkg uid=$uid) - user must grant it in Settings")
        return false
    }
}
