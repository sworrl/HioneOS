package com.hione.player.entitlement

import android.content.Context

/**
 * The ONLY place that can answer "blocked". Pure function over stored state; never touches the
 * network; never throws (any exception → ALLOWED).
 *
 * FAIL-OPEN DECISION TABLE
 * ┌──────────────────────────────────────────────────────────────┬──────────────────────────────┐
 * │ Condition                                                    │ Result                       │
 * ├──────────────────────────────────────────────────────────────┼──────────────────────────────┤
 * │ BuildConfig URL or HMAC secret empty (not configured)        │ ALLOWED  "not configured"    │
 * │ Settings toggle "Remote entitlement" OFF (default)           │ ALLOWED  "disabled"          │
 * │ Owner override code accepted on this device                  │ ALLOWED  "owner override"    │
 * │ Never checked / no verdict history                           │ ALLOWED  "no verdicts"       │
 * │ Network error / timeout / DNS / TLS failure                  │ ALLOWED  (not recorded)      │
 * │ HTTP non-2xx, malformed JSON, missing fields                 │ ALLOWED  (not recorded)      │
 * │ Missing / wrong signature                                    │ ALLOWED  (not recorded)      │
 * │ Nonce in response ≠ nonce we sent                            │ ALLOWED  (not recorded)      │
 * │ |server ts − local clock| > 12h (clock skew)                  │ ALLOWED  (not recorded)      │
 * │ Response already expired (expiresAt ≤ now)                   │ ALLOWED  (not recorded)      │
 * │ Latest signed verdict = ALLOW                                │ ALLOWED  "signed allow"      │
 * │ Signed DISALLOW but < 3 consecutive                          │ ALLOWED  "grace: n/3"        │
 * │ ≥3 consecutive signed DISALLOW but span < 72h                │ ALLOWED  "grace: span"       │
 * │ ≥3 consecutive signed DISALLOW, span ≥ 72h, newest expired   │ ALLOWED  "verdict expired"   │
 * │ ≥3 consecutive signed DISALLOW, span ≥ 72h, newest unexpired │ BLOCKED  (blocked screen)    │
 * │ Any exception anywhere in this evaluation                    │ ALLOWED  "evaluation error"  │
 * └──────────────────────────────────────────────────────────────┴──────────────────────────────┘
 * Owner-exempt devices are handled server-side (Worker always answers ALLOW for them), so they
 * can never accumulate a disallow chain in the first place.
 *
 * BLOCKED never deletes data, never stops background playback services already running, and
 * always offers the owner-override path + contact info on screen.
 */
object EntitlementDecision {

    fun evaluate(c: Context, now: Long = System.currentTimeMillis()): Decision = try {
        evaluateUnsafe(c, now)
    } catch (t: Throwable) {
        Decision.allowed("evaluation error (${t.javaClass.simpleName}), allowed")
    }

    private fun evaluateUnsafe(c: Context, now: Long): Decision {
        if (!EntitlementConfig.isConfigured) return Decision.allowed("not configured in this build")
        if (!EntitlementStore.isEnabled(c)) return Decision.allowed("remote entitlement disabled")
        if (EntitlementStore.isOwnerOverride(c)) return Decision.allowed("owner override active")

        val history = EntitlementStore.history(c)
        if (history.isEmpty()) return Decision.allowed("no signed verdicts yet")

        val newest = history.last()
        if (newest.verdict == Verdict.ALLOW) return Decision.allowed("latest signed verdict: allow")

        val chain = trailingDisallows(history)
        val n = chain.size
        if (n < EntitlementConfig.GRACE_MIN_DISALLOWS) {
            return Decision.allowed("grace: $n/${EntitlementConfig.GRACE_MIN_DISALLOWS} signed disallows", newest.note)
        }
        val span = chain.last().receivedAt - chain.first().receivedAt
        if (span < EntitlementConfig.GRACE_MIN_SPAN_MS) {
            val h = span / 3_600_000
            return Decision.allowed("grace: disallows span ${h}h of ${EntitlementConfig.GRACE_MIN_SPAN_MS / 3_600_000}h", newest.note)
        }
        if (newest.isExpired(now)) return Decision.allowed("newest disallow verdict expired, re-check needed", newest.note)

        return Decision(
            blocked = true,
            reason = "$n consecutive signed disallows over ${span / 3_600_000}h",
            note = newest.note
        )
    }

    /** Newest run of DISALLOW records (oldest → newest), stopping at the most recent ALLOW. */
    fun trailingDisallows(history: List<VerdictRecord>): List<VerdictRecord> {
        val out = ArrayList<VerdictRecord>()
        for (r in history.asReversed()) {
            if (r.verdict != Verdict.DISALLOW || !r.signed) break
            out.add(r)
        }
        return out.asReversed()
    }
}
