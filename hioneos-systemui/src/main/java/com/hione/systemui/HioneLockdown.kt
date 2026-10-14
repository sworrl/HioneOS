package com.hione.systemui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Power menu Lockdown, the same calls stock GlobalActions makes.
 *
 * LockPatternUtils.requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN, USER_ALL) makes
 * the next unlock need the PIN or password: the trust agent cannot keep the device open and the
 * lock screen hides notifications until the credential is entered. A correct PIN on the HioneOS
 * pad clears it (LockPatternUtils.userPresent). Then the keyguard locks now (IWindowManager.lockNow).
 *
 * Also sends com.hione.sysbridge.LOCKDOWN so SecurityGuard marks the device locked. Image copies of
 * sysbridge without SecurityGuard ignore it, so nothing here depends on it.
 *
 * Needs ACCESS_KEYGUARD_SECURE_STORAGE (granted by the platform signature).
 */
object HioneLockdown {
    private const val TAG = "HioneLockdown"
    /** LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN */
    private const val STRONG_AUTH_LOCKDOWN = 0x20
    private const val USER_ALL = -1
    private const val ACTION_SYSBRIDGE_LOCKDOWN = "com.hione.sysbridge.LOCKDOWN"

    private fun userId(): Int = android.os.Process.myUid() / 100_000

    private fun lpu(ctx: Context): Any? = runCatching {
        Class.forName("com.android.internal.widget.LockPatternUtils")
            .getConstructor(Context::class.java)
            .newInstance(ctx.applicationContext ?: ctx)
    }.onFailure { Log.w(TAG, "LockPatternUtils unavailable", it) }.getOrNull()

    /** A PIN, password or pattern is set. Lockdown means nothing without one. */
    fun available(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }.getOrDefault(false)

    /** Lockdown is on: set from the power menu and the PIN not entered since. */
    fun active(ctx: Context): Boolean {
        val l = lpu(ctx) ?: return false
        val flags = runCatching {
            l.javaClass.getMethod("getStrongAuthForUser", Int::class.javaPrimitiveType).invoke(l, userId()) as Int
        }.getOrDefault(0)
        return flags and STRONG_AUTH_LOCKDOWN != 0
    }

    /** Requires the credential for the next unlock and locks now. The caller turns the screen off. Off the main thread. */
    fun engage(ctx: Context): Boolean {
        if (!available(ctx)) return false
        val l = lpu(ctx) ?: return false
        val ok = runCatching {
            l.javaClass.getMethod("requireStrongAuth", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(l, STRONG_AUTH_LOCKDOWN, USER_ALL)
            true
        }.getOrElse { Log.e(TAG, "requireStrongAuth failed", (it as? java.lang.reflect.InvocationTargetException)?.cause ?: it); false }
        runCatching {
            ctx.sendBroadcast(Intent(ACTION_SYSBRIDGE_LOCKDOWN).setPackage("com.hione.sysbridge"))
        }.onFailure { Log.w(TAG, "sysbridge LOCKDOWN not sent", it) }
        lockNow()
        Log.w(TAG, "lockdown: strong auth required=$ok, active=${active(ctx)}")
        return ok
    }

    /** IWindowManager.lockNow(null): shows the keyguard now instead of after the lock timeout. */
    fun lockNow() {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
            wm.javaClass.getMethod("lockNow", Bundle::class.java).invoke(wm, null)
        }.onFailure { Log.w(TAG, "lockNow failed", (it as? java.lang.reflect.InvocationTargetException)?.cause ?: it) }
    }

    /** PowerManager.goToSleep (hidden, DEVICE_POWER). */
    fun screenOff(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(PowerManager::class.java)
            pm.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType).invoke(pm, SystemClock.uptimeMillis())
        }.onFailure { Log.w(TAG, "screen off failed", (it as? java.lang.reflect.InvocationTargetException)?.cause ?: it) }
    }
}
