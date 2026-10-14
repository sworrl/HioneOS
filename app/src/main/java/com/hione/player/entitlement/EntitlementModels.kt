package com.hione.player.entitlement

import org.json.JSONObject

/**
 * Remote entitlement ("kill-switch") data model.
 *
 * Design invariants (see EntitlementDecision for the full table):
 *  - Only a SIGNED, nonce-matched, unexpired, clock-sane server response ever produces a
 *    [VerdictRecord]. Everything else is a [CheckOutcome] that is displayed but NOT recorded as a
 *    verdict, so it can never contribute to a lockout.
 *  - A DISALLOW verdict on its own never blocks anything; the decision layer requires a run of
 *    consecutive signed disallows spanning a grace window.
 */
enum class Verdict { ALLOW, DISALLOW;
    companion object {
        fun parse(s: String?): Verdict? = when (s?.trim()?.lowercase()) {
            "allow", "allowed", "ok" -> ALLOW
            "deny", "denied", "disallow", "disallowed", "revoke", "revoked" -> DISALLOW
            else -> null
        }
    }
}

/** One fully verified server verdict. Persisted (JSON) in the history ring. */
data class VerdictRecord(
    /** Local wall-clock ms when the record was stored. */
    val receivedAt: Long,
    /** Server-declared timestamp (ms since epoch). */
    val serverTs: Long,
    /** Server-declared expiry of this verdict (ms since epoch). After this it is inert. */
    val expiresAt: Long,
    val verdict: Verdict,
    /** Free-text note from the server (e.g. why a device was revoked, who to contact). */
    val note: String,
    /** Always true for anything in the history — kept explicit so the UI can never lie about it. */
    val signed: Boolean = true,
    /** Signature algorithm the server reported, informational. */
    val alg: String = "HMAC-SHA256"
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt

    fun toJson(): JSONObject = JSONObject()
        .put("receivedAt", receivedAt)
        .put("serverTs", serverTs)
        .put("expiresAt", expiresAt)
        .put("verdict", verdict.name)
        .put("note", note)
        .put("signed", signed)
        .put("alg", alg)

    companion object {
        fun fromJson(o: JSONObject): VerdictRecord? = try {
            VerdictRecord(
                receivedAt = o.getLong("receivedAt"),
                serverTs = o.getLong("serverTs"),
                expiresAt = o.getLong("expiresAt"),
                verdict = Verdict.valueOf(o.getString("verdict")),
                note = o.optString("note", ""),
                signed = o.optBoolean("signed", false),
                alg = o.optString("alg", "HMAC-SHA256")
            ).takeIf { it.signed } // unsigned rows can never exist, but never trust storage blindly
        } catch (_: Throwable) { null }
    }
}

/** Result of a single network check. Only [Verified] is ever written to the verdict history. */
sealed class CheckOutcome(val label: String, val detail: String) {
    /** Feature can't run: BuildConfig URL or key is empty. */
    object NotConfigured : CheckOutcome("Not configured", "No endpoint / signing key baked into this build")
    /** User toggle is off. */
    object Disabled : CheckOutcome("Disabled", "Remote entitlement is switched off in Settings")
    class NetworkError(msg: String) : CheckOutcome("Unreachable", msg)
    class BadResponse(msg: String) : CheckOutcome("Bad response", msg)
    class Unsigned(msg: String) : CheckOutcome("Unsigned", msg)
    object NonceMismatch : CheckOutcome("Nonce mismatch", "Response did not echo the nonce we sent (replay?)")
    class ClockSkew(msg: String) : CheckOutcome("Clock skew", msg)
    class Expired(msg: String) : CheckOutcome("Expired", msg)
    class Verified(val record: VerdictRecord) : CheckOutcome(
        if (record.verdict == Verdict.ALLOW) "Allowed (signed)" else "Disallowed (signed)",
        record.note.ifBlank { "Signed verdict accepted" }
    )

    /** True for every outcome that, by design, cannot count against the device. */
    val failOpen: Boolean get() = this !is Verified
}

/** The final local answer. `blocked == true` is the ONLY thing that ever shows the blocked screen. */
data class Decision(
    val blocked: Boolean,
    /** Human-readable reason, always populated (surfaced verbatim in the License card). */
    val reason: String,
    /** The server note attached to the enforcing disallow chain, if any. */
    val note: String = ""
) {
    companion object {
        fun allowed(reason: String, note: String = "") = Decision(false, reason, note)
    }
}

/** Snapshot for the UI. Every field reflects real stored state — nothing is synthesized. */
data class EntitlementStatus(
    val configured: Boolean,
    val endpointHost: String,
    val enabled: Boolean,
    val deviceId: String,
    val idSource: String,
    val lastCheckAt: Long,
    val lastOutcomeLabel: String,
    val lastOutcomeDetail: String,
    val lastVerdict: VerdictRecord?,
    val history: List<VerdictRecord>,
    val consecutiveDisallows: Int,
    val disallowSpanMs: Long,
    val nextCheckAt: Long,
    val ownerOverride: Boolean,
    val decision: Decision,
    val checking: Boolean
)
