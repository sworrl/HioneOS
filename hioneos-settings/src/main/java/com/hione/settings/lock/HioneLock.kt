package com.hione.settings.lock

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.hione.settings.sys.Hidden
import java.lang.reflect.InvocationTargetException

/**
 * Screen lock credential calls for HioneOS.
 *
 * Everything goes through the platform LockPatternUtils, the same class stock Settings and the
 * stock keyguard use. LockSettingsService does the real work: it stores the credential, binds it
 * to the file encryption keys, and on a correct check after a restart it unlocks credential
 * encrypted storage. HioneSettings is platform-signed, so ACCESS_KEYGUARD_SECURE_STORAGE (the
 * permission behind these calls) is granted and hidden API rules do not apply.
 *
 * Calls that check or set a credential are slow (key stretching). Run them off the main thread.
 */
object HioneLock {
    private const val TAG = "HioneLock"

    const val TYPE_NONE = -1
    const val TYPE_PATTERN = 1
    const val TYPE_PIN = 3
    const val TYPE_PASSWORD = 4

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 16

    /** Settings.Secure. 1 (default): after the first unlock, keep the device trusted until restart. */
    const val KEY_UNTIL_RESTART = "hione_lock_trust_until_restart"
    /** Settings.Secure. 1: the Hione shade shows notification content while locked. Default 0. */
    const val KEY_SHOW_CONTENT = "hione_lock_show_notif_content"
    /** Settings.Secure. 1: the unlock PIN pad puts the digits in new places each time it shows. Default 0. */
    const val KEY_SCRAMBLE_PIN = "hione_lock_scramble_pin"

    /** The HioneOS trust agent. It lives in HioneSystemUI, which needs no privapp allowlist entry. */
    val TRUST_AGENT = ComponentName("com.hione.systemui", "com.hione.systemui.trust.HioneTrustAgent")

    fun userId(): Int = android.os.Process.myUid() / 100_000

    @Volatile private var cached: Any? = null
    private fun lpu(ctx: Context): Any? = cached ?: try {
        Class.forName("com.android.internal.widget.LockPatternUtils")
            .getConstructor(Context::class.java)
            .newInstance(ctx.applicationContext ?: ctx)
            .also { cached = it }
    } catch (t: Throwable) {
        Log.w(TAG, "LockPatternUtils unavailable", t)
        null
    }

    fun credentialType(ctx: Context): Int =
        (Hidden.call(lpu(ctx), "getCredentialTypeForUser", userId()) as? Int) ?: TYPE_NONE

    fun isSecure(ctx: Context): Boolean =
        (Hidden.call(lpu(ctx), "isSecure", userId()) as? Boolean) ?: false

    fun typeName(type: Int): String = when (type) {
        TYPE_PIN -> "PIN"
        TYPE_PASSWORD -> "Password"
        TYPE_PATTERN -> "Pattern"
        TYPE_NONE -> "None"
        else -> "Unknown"
    }

    /** A LockscreenCredential for [type] holding [value]. Zeroize it when done. */
    private fun credential(type: Int, value: CharSequence?): Any {
        val cls = Class.forName("com.android.internal.widget.LockscreenCredential")
        return when (type) {
            TYPE_PIN -> cls.getMethod("createPin", CharSequence::class.java).invoke(null, value)
            TYPE_PASSWORD -> cls.getMethod("createPassword", CharSequence::class.java).invoke(null, value)
            else -> cls.getMethod("createNone").invoke(null)
        }!!
    }

    private fun zeroize(c: Any?) {
        if (c != null) runCatching { c.javaClass.getMethod("zeroize").invoke(c) }
    }

    sealed class Check {
        object Ok : Check()
        object Wrong : Check()
        class Throttled(val ms: Long) : Check()
        class Failed(val why: String) : Check()
    }

    /**
     * Checks [value] against the stored credential through LockSettingsService. A correct check
     * also unlocks credential encrypted storage after a restart and clears the "strong auth
     * required" state, exactly as the stock keyguard does. Off the main thread only.
     */
    fun check(ctx: Context, type: Int, value: CharSequence): Check {
        val lpu = lpu(ctx) ?: return Check.Failed("Lock service not available")
        var cred: Any? = null
        return try {
            cred = credential(type, value)
            val m = lpu.javaClass.methods.first { it.name == "checkCredential" && it.parameterTypes.size == 3 }
            if (m.invoke(lpu, cred, userId(), null) as Boolean) Check.Ok else Check.Wrong
        } catch (e: InvocationTargetException) {
            val c = e.cause
            if (c != null && c.javaClass.simpleName == "RequestThrottledException") {
                val ms = runCatching { (c.javaClass.getMethod("getTimeoutMs").invoke(c) as Number).toLong() }.getOrDefault(30_000L)
                Check.Throttled(ms)
            } else {
                Log.w(TAG, "checkCredential failed", c ?: e)
                Check.Failed(c?.message ?: "Check failed")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "checkCredential failed", t)
            Check.Failed(t.message ?: "Check failed")
        } finally {
            zeroize(cred)
        }
    }

    /**
     * Sets a new credential. [oldType]/[old] are the current one (TYPE_NONE when there is none).
     * Off the main thread only.
     */
    fun setCredential(ctx: Context, newType: Int, new: CharSequence?, oldType: Int, old: CharSequence?): Result<Unit> {
        val lpu = lpu(ctx) ?: return Result.failure(IllegalStateException("Lock service not available"))
        var n: Any? = null
        var o: Any? = null
        return try {
            n = credential(newType, new)
            o = credential(oldType, old)
            val m = lpu.javaClass.methods.first { it.name == "setLockCredential" && it.parameterTypes.size == 3 }
            val ok = m.invoke(lpu, n, o, userId()) as Boolean
            if (!ok) return Result.failure(IllegalStateException("The current lock did not match"))
            if (newType == TYPE_NONE) afterCredentialRemoved(ctx) else afterCredentialSet(ctx)
            Result.success(Unit)
        } catch (e: InvocationTargetException) {
            Log.w(TAG, "setLockCredential failed", e.cause ?: e)
            Result.failure(e.cause ?: e)
        } catch (t: Throwable) {
            Log.w(TAG, "setLockCredential failed", t)
            Result.failure(t)
        } finally {
            zeroize(n); zeroize(o)
        }
    }

    /**
     * A credential exists now: the stock keyguard must be enabled (a disabled keyguard with a
     * credential makes no sense, and LockPatternUtils ignores the flag anyway), the HioneOS trust
     * agent must be on, and notification content starts hidden unless the user already chose.
     */
    private fun afterCredentialSet(ctx: Context) {
        setLockScreenDisabled(ctx, false)
        enableTrustAgent(ctx)
        val cr = ctx.contentResolver
        runCatching {
            if (Settings.Secure.getString(cr, KEY_UNTIL_RESTART) == null) Settings.Secure.putInt(cr, KEY_UNTIL_RESTART, 1)
            if (Settings.Secure.getString(cr, KEY_SHOW_CONTENT) == null) {
                Settings.Secure.putInt(cr, KEY_SHOW_CONTENT, 0)
                Settings.Secure.putInt(cr, "lock_screen_allow_private_notifications", 0)
            }
        }
    }

    /** Back to how HioneOS ran without a credential: stock keyguard off, Hione lockscreen only. */
    private fun afterCredentialRemoved(ctx: Context) {
        setLockScreenDisabled(ctx, true)
    }

    fun setLockScreenDisabled(ctx: Context, disabled: Boolean) {
        Hidden.call(lpu(ctx), "setLockScreenDisabled", disabled, userId())
    }

    fun isLockScreenDisabledFlag(ctx: Context): Boolean? =
        Hidden.call(lpu(ctx), "isLockScreenDisabled", userId()) as? Boolean

    /** Adds the HioneOS trust agent to the enabled list, keeping any other enabled agent. */
    fun enableTrustAgent(ctx: Context) {
        val lpu = lpu(ctx) ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            val current = (Hidden.call(lpu, "getEnabledTrustAgents", userId()) as? List<ComponentName>).orEmpty()
            if (TRUST_AGENT in current) return
            val m = lpu.javaClass.methods.first { it.name == "setEnabledTrustAgents" && it.parameterTypes.size == 2 }
            m.invoke(lpu, current + TRUST_AGENT, userId())
        } catch (t: Throwable) {
            Log.w(TAG, "enableTrustAgent failed", (t as? InvocationTargetException)?.cause ?: t)
        }
    }

    fun trustAgentEnabled(ctx: Context): Boolean? {
        @Suppress("UNCHECKED_CAST")
        val l = Hidden.call(lpu(ctx), "getEnabledTrustAgents", userId()) as? List<ComponentName> ?: return null
        return TRUST_AGENT in l
    }

    /**
     * Tells DevicePolicyManager and TrustManager about an unlock attempt, like the keyguard does.
     * A successful report is what lets the trust agent's grant count for this unlock.
     */
    fun reportAttempt(ctx: Context, success: Boolean) {
        val lpu = lpu(ctx)
        val dpmOk = Hidden.tryCall(lpu, if (success) "reportSuccessfulPasswordAttempt" else "reportFailedPasswordAttempt", userId()).isSuccess
        // The DPM half needs BIND_DEVICE_ADMIN. If it was refused, the TrustManager half did not run either.
        if (!dpmOk) reportTrust(ctx, success)
    }

    /**
     * What the stock keyguard does once a credential was accepted: LockSettingsService clears
     * the "strong auth required" flags (after restart, lockdown, timeout). Until that happens the
     * system does not allow trust, so the keyguard could not be dismissed by the trust grant.
     * Only call this after [check] returned [Check.Ok].
     */
    fun userPresent(ctx: Context) {
        Hidden.call(lpu(ctx), "userPresent", userId())
    }

    /** TrustManager.reportUnlockAttempt only. Needs ACCESS_KEYGUARD_SECURE_STORAGE. */
    fun reportTrust(ctx: Context, success: Boolean) {
        try {
            val tm = ctx.getSystemService("trust") ?: return
            tm.javaClass.getMethod("reportUnlockAttempt", java.lang.Boolean.TYPE, Integer.TYPE).invoke(tm, success, userId())
        } catch (t: Throwable) {
            Log.w(TAG, "reportUnlockAttempt failed", (t as? InvocationTargetException)?.cause ?: t)
        }
    }

    fun untilRestart(ctx: Context): Boolean =
        runCatching { Settings.Secure.getInt(ctx.contentResolver, KEY_UNTIL_RESTART, 1) == 1 }.getOrDefault(true)

    fun setUntilRestart(ctx: Context, on: Boolean): Boolean =
        runCatching { Settings.Secure.putInt(ctx.contentResolver, KEY_UNTIL_RESTART, if (on) 1 else 0) }.getOrDefault(false)

    fun showContent(ctx: Context): Boolean =
        runCatching { Settings.Secure.getInt(ctx.contentResolver, KEY_SHOW_CONTENT, 0) == 1 }.getOrDefault(false)

    fun setShowContent(ctx: Context, on: Boolean): Boolean = runCatching {
        val cr = ctx.contentResolver
        Settings.Secure.putInt(cr, KEY_SHOW_CONTENT, if (on) 1 else 0) &&
            Settings.Secure.putInt(cr, "lock_screen_allow_private_notifications", if (on) 1 else 0)
    }.getOrDefault(false)

    fun scramblePin(ctx: Context): Boolean =
        runCatching { Settings.Secure.getInt(ctx.contentResolver, KEY_SCRAMBLE_PIN, 0) == 1 }.getOrDefault(false)

    fun setScramblePin(ctx: Context, on: Boolean): Boolean =
        runCatching { Settings.Secure.putInt(ctx.contentResolver, KEY_SCRAMBLE_PIN, if (on) 1 else 0) }.getOrDefault(false)

    /** Checks a candidate new credential. Returns an error line, or null when it is fine. */
    fun validate(type: Int, value: String): String? = when {
        value.length < MIN_LENGTH -> if (type == TYPE_PIN) "Use at least $MIN_LENGTH digits" else "Use at least $MIN_LENGTH characters"
        value.length > MAX_LENGTH -> if (type == TYPE_PIN) "Use $MAX_LENGTH digits or fewer" else "Use $MAX_LENGTH characters or fewer"
        type == TYPE_PIN && !value.all { it in '0'..'9' } -> "A PIN is digits only"
        type == TYPE_PASSWORD && value.all { it in '0'..'9' } -> "Use at least one letter, or set a PIN instead"
        type == TYPE_PASSWORD && value.any { it.code < 0x20 || it.code > 0x7e } -> "Use letters, digits and symbols from the keyboard"
        else -> null
    }
}
