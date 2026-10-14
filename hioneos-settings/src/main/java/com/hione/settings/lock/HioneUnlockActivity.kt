package com.hione.settings.lock

import com.hione.settings.Mascot
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The HioneOS unlock screen: the PIN or password pad shown over the stock keyguard whenever the
 * device actually needs the credential (after a restart, or when the trust agent is not holding
 * the device open).
 *
 * How unlocking works, in order:
 *  1. The typed credential goes to LockSettingsService (LockPatternUtils.checkCredential). That is
 *     the system verify path: after a restart it unlocks credential encrypted storage.
 *  2. Like the stock keyguard after a correct entry, it tells LockSettingsService the user is
 *     present (clears "strong auth required") and reports the successful attempt to TrustManager.
 *     The HioneOS trust agent (HioneSystemUI) answers with a trust grant, which makes the keyguard
 *     dismissible.
 *  3. Once the keyguard reports it is no longer locked, this asks it to dismiss. No stock bouncer.
 *
 * Direct boot aware, so it also works before the first unlock after a restart, when only
 * device protected storage exists. It reads nothing from credential encrypted storage.
 *
 * Launched by the Hione lockscreen (launcher) on swipe up, by [LockBootReceiver] after a restart,
 * and by the Hione shade when a hidden notification is tapped.
 */
class HioneUnlockActivity : ComponentActivity() {
    companion object {
        private const val TAG = "HioneUnlock"

        fun start(ctx: Context) {
            try {
                ctx.startActivity(Intent(ctx, HioneUnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (t: Throwable) {
                Log.w(TAG, "start failed", t)
            }
        }
    }

    private val km by lazy { getSystemService(KeyguardManager::class.java) }

    private var type by mutableIntStateOf(HioneLock.TYPE_PIN)
    private var error by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var lockedUntil by mutableLongStateOf(0L)
    private var resetKey by mutableIntStateOf(0)
    private var shakeKey by mutableIntStateOf(0)
    /** Scramble PIN layout (Screen lock setting). New digit places each time the pad shows. */
    private var scramble by mutableStateOf(false)
    private var layoutKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        // No screenshots or recents thumbnail of the pad.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        runCatching {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            }
        }
        setContent {
            val now by produceState(SystemClock.elapsedRealtime(), lockedUntil) {
                while (SystemClock.elapsedRealtime() < lockedUntil) { value = SystemClock.elapsedRealtime(); delay(250) }
                value = SystemClock.elapsedRealtime()
            }
            val wait = ((lockedUntil - now + 999) / 1000).coerceAtLeast(0)
            val noun = if (type == HioneLock.TYPE_PASSWORD) "password" else "PIN"
            val afterRestart = remember { !isUserUnlocked() }
            HioneLockBackdrop {
                CredentialEntry(
                    type = type,
                    title = "Enter your $noun",
                    subtitle = if (afterRestart) "${Mascot.osName} asks for it after every restart" else null,
                    error = if (wait > 0) "Too many tries. Try again in ${wait}s" else error,
                    busy = busy,
                    locked = wait > 0,
                    resetKey = resetKey,
                    shakeKey = shakeKey,
                    submitLabel = "Unlock",
                    onSubmit = { submit(it) },
                    onCancel = { finish() },
                    header = { LockClock() },
                    scramble = scramble,
                    layoutKey = layoutKey
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        error = null
        resetKey++
    }

    override fun onResume() {
        super.onResume()
        if (busy) return
        scramble = HioneLock.scramblePin(this)
        layoutKey++
        val t = HioneLock.credentialType(this)
        when {
            // Nothing to unlock: the keyguard is gone already.
            !km.isKeyguardLocked -> finish()
            // Trusted (or no credential): the keyguard opens without a credential.
            !km.isDeviceLocked -> dismissAndFinish()
            // A pattern was set by the stock flow. The stock bouncer handles it.
            t == HioneLock.TYPE_PATTERN || t == HioneLock.TYPE_NONE -> dismissAndFinish()
            else -> type = t
        }
    }

    private fun isUserUnlocked(): Boolean =
        runCatching { getSystemService(UserManager::class.java).isUserUnlocked }.getOrDefault(true)

    private fun submit(value: String) {
        if (busy) return
        busy = true
        error = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { HioneLock.check(this@HioneUnlockActivity, type, value) }
            when (result) {
                is HioneLock.Check.Ok -> {
                    withContext(Dispatchers.IO) {
                        HioneLock.userPresent(this@HioneUnlockActivity)
                        HioneLock.reportAttempt(this@HioneUnlockActivity, true)
                    }
                    waitForTrustThenDismiss()
                }
                is HioneLock.Check.Wrong -> {
                    withContext(Dispatchers.IO) { HioneLock.reportAttempt(this@HioneUnlockActivity, false) }
                    busy = false
                    error = if (type == HioneLock.TYPE_PASSWORD) "Wrong password" else "Wrong PIN"
                    shakeKey++
                    resetKey++
                }
                is HioneLock.Check.Throttled -> {
                    busy = false
                    lockedUntil = SystemClock.elapsedRealtime() + result.ms
                    shakeKey++
                    resetKey++
                }
                is HioneLock.Check.Failed -> {
                    busy = false
                    error = "Could not check it: ${result.why}"
                    resetKey++
                }
            }
        }
    }

    /**
     * The credential was right and storage is unlocked. The keyguard opens once the system
     * counts the trust agent's grant for this unlock. A grant that arrives after the first report
     * counts on the next report, so report again until the keyguard says it is no longer locked.
     * Right after a restart the agent can take a few seconds to start.
     */
    private suspend fun waitForTrustThenDismiss() {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var n = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(250)
            if (!km.isKeyguardLocked) { finish(); return }
            if (!km.isDeviceLocked) break
            if (++n % 2 == 0) withContext(Dispatchers.IO) { HioneLock.reportTrust(this@HioneUnlockActivity, true) }
        }
        if (km.isDeviceLocked) Log.w(TAG, "trust not granted in time, the stock keyguard takes over")
        else {
            // One more report so the keyguard's own trust state matches what TrustManager says.
            withContext(Dispatchers.IO) { HioneLock.reportTrust(this@HioneUnlockActivity, true) }
            delay(150)
        }
        dismissAndFinish()
    }

    private fun dismissAndFinish() {
        try {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { finish() }
                override fun onDismissCancelled() { busy = false; resetKey++ }
                override fun onDismissError() { finish() }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "requestDismissKeyguard failed", t)
            finish()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}
