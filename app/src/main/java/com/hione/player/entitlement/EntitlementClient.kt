package com.hione.player.entitlement

import android.content.Context
import com.hione.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * One round-trip to the entitlement Worker: POST {deviceId, appVersion, nonce, ts} to
 * `<url>/v1/check`, then verify the reply before it is allowed to mean anything.
 *
 * Dependency-free (HttpURLConnection + org.json, same as LastFm.kt). Every failure mode maps to
 * a fail-open [CheckOutcome]; only a reply that passes ALL of the following becomes
 * [CheckOutcome.Verified] and is recorded:
 *   1. HTTP 2xx with a JSON object body
 *   2. `deviceId` echoes ours
 *   3. `nonce` echoes the one we generated for this request
 *   4. `sig` == HMAC-SHA256(secret, canonical(...)) (constant-time compare)
 *   5. |ts − local clock| ≤ MAX_CLOCK_SKEW_MS
 *   6. expiresAt > ts and expiresAt > now
 *   7. `verdict` parses to ALLOW / DISALLOW
 */
object EntitlementClient {

    suspend fun check(context: Context): CheckOutcome = withContext(Dispatchers.IO) {
        try { checkBlocking(context) } catch (t: Throwable) {
            CheckOutcome.NetworkError("${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }
    }

    private fun checkBlocking(context: Context): CheckOutcome {
        if (!EntitlementConfig.isConfigured) return CheckOutcome.NotConfigured
        if (!EntitlementStore.isEnabled(context)) return CheckOutcome.Disabled

        val deviceId = EntitlementStore.deviceId(context)
        val nonce = EntitlementConfig.randomNonce()
        val sentTs = System.currentTimeMillis()
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("nonce", nonce)
            .put("ts", sentTs)
            .toString()

        val conn = (URL("${EntitlementConfig.url}/${EntitlementConfig.API_VERSION}/check").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = EntitlementConfig.HTTP_TIMEOUT_MS
            readTimeout = EntitlementConfig.HTTP_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "HioneMusic/${BuildConfig.VERSION_NAME} (HiBy M500)")
        }
        val text: String
        val code: Int
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
        if (code !in 200..299) return CheckOutcome.BadResponse("HTTP $code")
        return verify(text, deviceId, nonce, System.currentTimeMillis())
    }

    /** Pure verification step — separated so it is trivially unit-testable with canned JSON. */
    fun verify(text: String, deviceId: String, nonce: String, now: Long): CheckOutcome {
        val json = try { JSONObject(text) } catch (_: Throwable) {
            return CheckOutcome.BadResponse("body is not a JSON object")
        }
        val rDevice = json.optString("deviceId", "")
        val rVerdict = json.optString("verdict", "")
        val rNonce = json.optString("nonce", "")
        val rTs = json.optLong("ts", -1L)
        val rExp = json.optLong("expiresAt", -1L)
        val rNote = json.optString("note", "")
        val rSig = json.optString("sig", "")
        val rAlg = json.optString("alg", "HMAC-SHA256")

        if (rDevice.isEmpty() || rVerdict.isEmpty() || rTs < 0 || rExp < 0) {
            return CheckOutcome.BadResponse("missing deviceId/verdict/ts/expiresAt")
        }
        if (rDevice != deviceId) return CheckOutcome.BadResponse("deviceId mismatch")
        if (rNonce != nonce) return CheckOutcome.NonceMismatch
        if (rSig.isBlank()) return CheckOutcome.Unsigned("no `sig` field")
        if (!rAlg.equals("HMAC-SHA256", ignoreCase = true)) return CheckOutcome.Unsigned("unsupported alg $rAlg")

        val expected = try {
            EntitlementConfig.hmacHex(EntitlementConfig.canonical(rDevice, rVerdict, rNonce, rTs, rExp, rNote))
        } catch (t: Throwable) {
            return CheckOutcome.Unsigned("local HMAC failed: ${t.javaClass.simpleName}")
        }
        if (!EntitlementConfig.constantTimeEquals(expected, rSig)) return CheckOutcome.Unsigned("signature does not verify")

        val skew = abs(rTs - now)
        if (skew > EntitlementConfig.MAX_CLOCK_SKEW_MS) {
            return CheckOutcome.ClockSkew("server/device clocks differ by ${skew / 60_000} min")
        }
        if (rExp <= rTs) return CheckOutcome.BadResponse("expiresAt not after ts")
        if (rExp <= now) return CheckOutcome.Expired("verdict already expired on arrival")

        val verdict = Verdict.parse(rVerdict) ?: return CheckOutcome.BadResponse("unknown verdict '$rVerdict'")
        return CheckOutcome.Verified(
            VerdictRecord(
                receivedAt = now, serverTs = rTs, expiresAt = rExp,
                verdict = verdict, note = rNote, signed = true, alg = "HMAC-SHA256"
            )
        )
    }
}
