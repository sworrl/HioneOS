package com.hione.player.entitlement

import android.content.Context
import android.provider.Settings
import com.hione.player.BuildConfig
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Build-time configuration + crypto primitives for the remote entitlement check.
 *
 * Everything comes from `local.properties` (gitignored) via BuildConfig:
 *   hione.entitlement.url     = https://hionesan.falcontechnix.com   (Worker base URL)
 *   hione.entitlement.hmac    = <hex or plain shared secret, same value as the Worker's
 *                               ENTITLEMENT_HMAC_SECRET>
 *   hione.entitlement.contact = Justin@FalconTechnix.com             (shown on the blocked screen)
 *
 * Empty URL or empty secret => [isConfigured] is false => the feature is completely inert:
 * no network calls, no verdicts, [EntitlementDecision] always answers ALLOWED.
 */
object EntitlementConfig {
    const val API_VERSION = "v1"
    /** 24h between automatic checks. */
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    /** Minimum gap between checks the user can force from Settings (anti-hammer, not security). */
    const val MIN_MANUAL_INTERVAL_MS = 10_000L
    /** A signed verdict whose server timestamp differs from local time by more than this is
     *  treated as clock skew and is NOT recorded (fail-open). */
    const val MAX_CLOCK_SKEW_MS = 12L * 60 * 60 * 1000
    /** Grace rules: this many consecutive signed DISALLOWs ... */
    const val GRACE_MIN_DISALLOWS = 3
    /** ... spanning at least this long (first to last) before anything is enforced. */
    const val GRACE_MIN_SPAN_MS = 72L * 60 * 60 * 1000
    /** Verdict history ring size. */
    const val HISTORY_CAP = 40
    const val HTTP_TIMEOUT_MS = 8_000

    val url: String get() = BuildConfig.HIONE_ENTITLEMENT_URL.trim().trimEnd('/')
    val secret: String get() = BuildConfig.HIONE_ENTITLEMENT_HMAC.trim()
    val contact: String get() = BuildConfig.HIONE_ENTITLEMENT_CONTACT.trim().ifBlank { "Falcon Technix" }

    /** True only when BOTH the endpoint and the signing secret are baked in. */
    val isConfigured: Boolean
        get() = url.startsWith("http", ignoreCase = true) && secret.length >= 16

    val endpointHost: String
        get() = try { URI(url).host ?: url } catch (_: Throwable) { url }

    private fun secretBytes(): ByteArray {
        val s = secret
        // Accept a hex-encoded secret (as produced by `openssl rand -hex 32`) or raw text.
        val isHex = s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        return if (isHex && s.length >= 32) hexToBytes(s) else s.toByteArray(Charsets.UTF_8)
    }

    fun hmacHex(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes(), "HmacSHA256"))
        return bytesToHex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Canonical string the server signs. MUST match `canonical()` in the Worker byte for byte.
     * `note` is last so a note containing newlines can't shift any other field.
     */
    fun canonical(deviceId: String, verdict: String, nonce: String, ts: Long, expiresAt: Long, note: String): String =
        listOf(API_VERSION, deviceId, verdict, nonce, ts.toString(), expiresAt.toString(), note).joinToString("\n")

    /** Constant-time hex comparison (case-insensitive). */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.lowercase().toByteArray(); val y = b.lowercase().toByteArray()
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    /**
     * Owner override code: the human who holds the HMAC secret can always unlock their own
     * device. Derived, never stored: HMAC(secret, "owner-override\n<deviceId>") → first 12 hex,
     * shown as XXXX-XXXX-XXXX. Compute it on a PC with the same secret (see Worker README).
     */
    fun ownerOverrideCode(deviceId: String): String? {
        if (!isConfigured) return null
        return try {
            val h = hmacHex("owner-override\n$deviceId").uppercase().take(12)
            "${h.substring(0, 4)}-${h.substring(4, 8)}-${h.substring(8, 12)}"
        } catch (_: Throwable) { null }
    }

    fun ownerOverrideMatches(deviceId: String, entered: String): Boolean {
        val expected = ownerOverrideCode(deviceId) ?: return false
        val norm = entered.trim().uppercase().replace("-", "").replace(" ", "")
        return constantTimeEquals(expected.replace("-", ""), norm)
    }

    fun sha256Hex(s: String): String =
        bytesToHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    fun bytesToHex(b: ByteArray): String = buildString(b.size * 2) {
        for (x in b) { val v = x.toInt() and 0xff; append(HEX[v ushr 4]); append(HEX[v and 0x0f]) }
    }

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte() }

    private val HEX = "0123456789abcdef".toCharArray()

    fun randomNonce(): String {
        val b = ByteArray(16); java.security.SecureRandom().nextBytes(b); return bytesToHex(b)
    }

    /** Best-effort ANDROID_ID, "" on failure. */
    fun androidId(context: Context): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    } catch (_: Throwable) { "" }
}
