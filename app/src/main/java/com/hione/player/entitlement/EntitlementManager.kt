package com.hione.player.entitlement

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide coordinator: 24h check cadence (plain coroutine — WorkManager is not a dependency
 * of this module and adding one is out of scope), "check now", owner override, and a Compose
 * observable [revision] the License card uses to refresh.
 *
 * [onAppStart] is the ONE hook MainActivity calls. It is safe to call repeatedly (idempotent
 * loop) and does nothing at all unless the feature is configured AND enabled.
 */
object EntitlementManager {
    private const val TAG = "HioneEntitlement"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var loop: Job? = null
    @Volatile private var lastManualAt = 0L

    /** Bumped after every state change so Compose readers recompose. */
    var revision by mutableStateOf(0L); private set
    var checking by mutableStateOf(false); private set

    private fun bump() { revision++ }

    /** MainActivity hook. Never throws. */
    fun onAppStart(context: Context) {
        try {
            val app = context.applicationContext
            if (!EntitlementConfig.isConfigured || !EntitlementStore.isEnabled(app)) return
            ensureLoop(app)
        } catch (t: Throwable) {
            Log.w(TAG, "onAppStart ignored: ${t.message}")
        }
    }

    /** The decision — see EntitlementDecision for the table. Never throws. */
    fun decision(context: Context): Decision = EntitlementDecision.evaluate(context.applicationContext)

    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        EntitlementStore.setEnabled(app, on)
        if (on && EntitlementConfig.isConfigured) ensureLoop(app) else stopLoop()
        bump()
    }

    /** Settings "Check now". Returns the outcome (also persisted + reflected in [status]). */
    suspend fun checkNow(context: Context, manual: Boolean = true): CheckOutcome {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (manual) {
            if (now - lastManualAt < EntitlementConfig.MIN_MANUAL_INTERVAL_MS) {
                return CheckOutcome.NetworkError("please wait a few seconds between manual checks")
            }
            lastManualAt = now
        }
        return runCheck(app)
    }

    /** Owner code entry. Accepting it flips the persistent override flag (ALLOWED forever after). */
    fun tryOwnerOverride(context: Context, code: String): Boolean {
        val app = context.applicationContext
        val ok = try { EntitlementConfig.ownerOverrideMatches(EntitlementStore.deviceId(app), code) } catch (_: Throwable) { false }
        if (ok) { EntitlementStore.setOwnerOverride(app, true); bump() }
        return ok
    }

    fun clearOwnerOverride(context: Context) { EntitlementStore.setOwnerOverride(context.applicationContext, false); bump() }

    fun clearHistory(context: Context) { EntitlementStore.clearHistory(context.applicationContext); bump() }

    fun status(context: Context): EntitlementStatus {
        val app = context.applicationContext
        val history = EntitlementStore.history(app)
        val chain = EntitlementDecision.trailingDisallows(history)
        val (label, detail) = EntitlementStore.lastOutcome(app)
        val configured = EntitlementConfig.isConfigured
        return EntitlementStatus(
            configured = configured,
            endpointHost = if (configured) EntitlementConfig.endpointHost else "",
            enabled = EntitlementStore.isEnabled(app),
            deviceId = try { EntitlementStore.deviceId(app) } catch (_: Throwable) { "" },
            idSource = EntitlementStore.deviceIdSource(app),
            lastCheckAt = EntitlementStore.lastCheckAt(app),
            lastOutcomeLabel = label,
            lastOutcomeDetail = detail,
            lastVerdict = history.lastOrNull(),
            history = history,
            consecutiveDisallows = chain.size,
            disallowSpanMs = if (chain.size >= 2) chain.last().receivedAt - chain.first().receivedAt else 0L,
            nextCheckAt = EntitlementStore.nextCheckAt(app),
            ownerOverride = EntitlementStore.isOwnerOverride(app),
            decision = decision(app),
            checking = checking
        )
    }

    // ---- internals --------------------------------------------------------------------------

    private suspend fun runCheck(app: Context): CheckOutcome = lock.withLock {
        checking = true; bump()
        val outcome = try { EntitlementClient.check(app) } catch (t: Throwable) {
            CheckOutcome.NetworkError("${t.javaClass.simpleName}: ${t.message}")
        }
        try { EntitlementStore.recordOutcome(app, outcome) } catch (t: Throwable) { Log.w(TAG, "record failed: $t") }
        Log.i(TAG, "check → ${outcome.label}: ${outcome.detail}")
        checking = false; bump()
        outcome
    }

    @Synchronized
    private fun ensureLoop(app: Context) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            // Small start-up delay so the check never competes with first-frame / library work.
            delay(15_000)
            while (true) {
                try {
                    if (!EntitlementConfig.isConfigured || !EntitlementStore.isEnabled(app)) break
                    val now = System.currentTimeMillis()
                    val due = EntitlementStore.nextCheckAt(app)
                    if (now >= due) runCheck(app)
                    val wait = (EntitlementStore.nextCheckAt(app) - System.currentTimeMillis())
                        .coerceIn(60_000L, EntitlementConfig.CHECK_INTERVAL_MS)
                    delay(wait)
                } catch (t: Throwable) {
                    Log.w(TAG, "loop iteration failed: $t")
                    delay(60L * 60 * 1000)
                }
            }
        }
    }

    @Synchronized
    private fun stopLoop() { loop?.cancel(); loop = null }
}
