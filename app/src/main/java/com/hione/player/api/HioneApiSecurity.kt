package com.hione.player.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Access control for the Wi-Fi remote API.
 *
 * - The API is OFF unless the user turns it on in Settings. The old `api_enabled` key defaulted
 *   to true, so it is dropped on first run of this version and the new key defaults to false.
 * - One random 256-bit token per device, kept in [HioneSecureStore] (Keystore-backed). Every
 *   route except the page shell and the pairing call needs `Authorization: Bearer <token>`.
 * - `<img>` and `<audio>` can't send headers, so the server hands out signed URLs for artwork
 *   and audio: `?exp=<unix s>&sig=HMAC-SHA256(k, path + "\n" + exp)`, where k is derived from the
 *   token. They name one track id, expire, and stop working when the token is reset.
 * - A browser gets the token by pairing: Settings opens a 5-minute window with a single-use
 *   6-digit code (also in a QR code). Five wrong codes close the window and lock pairing for a
 *   minute.
 * - Failed auth from one address 10 times in a minute blocks that address for 5 minutes.
 * - All secret comparisons are constant-time.
 */
object HioneApiSecurity {
    private const val TAG = "HioneApiSecurity"
    private const val PREFS_NAME = "hione_api_security_prefs"

    // Plain prefs (not secret).
    private const val KEY_ENABLED = "remote_api_on"            // new key, default false
    private const val KEY_MIGRATED = "migrated_v2"
    private const val KEY_API_PORT = "api_port"
    private const val KEY_USE_HTTPS = "api_use_https"
    // Keys from the old version, removed by the migration.
    private val LEGACY_KEYS = listOf("api_enabled", "api_secret_key", "api_require_auth")

    // Encrypted prefs.
    private const val KEY_TOKEN = "api_token"

    const val DEFAULT_PORT = 8765
    const val PAIR_WINDOW_MS = 5 * 60_000L
    private const val PAIR_MAX_WRONG = 5
    private const val PAIR_LOCK_MS = 60_000L

    private const val FAIL_WINDOW_MS = 60_000L
    private const val FAIL_MAX = 10
    private const val FAIL_BLOCK_MS = 5 * 60_000L

    private val rng = SecureRandom()

    // ------------------------------------------------------------------ settings

    private fun prefs(context: Context): SharedPreferences {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_MIGRATED, false)) {
            // In-place update from a build where the API was on by default with a plaintext
            // secret in this file: drop both. The user turns it on again if they want it.
            p.edit().apply { LEGACY_KEYS.forEach { remove(it) } }.putBoolean(KEY_MIGRATED, true).apply()
            Log.i(TAG, "migrated remote API prefs: off by default, legacy secret removed")
        }
        return p
    }

    fun isApiEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setApiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getApiPort(context: Context): Int =
        prefs(context).getInt(KEY_API_PORT, DEFAULT_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_PORT

    fun isHttpsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_USE_HTTPS, false)

    // ------------------------------------------------------------------ token

    /** The device token, created on first use. Null only when the Keystore is unusable. */
    @Synchronized
    fun getOrCreateToken(context: Context): String? {
        val sp = HioneSecureStore.get(context) ?: return null
        sp.getString(KEY_TOKEN, null)?.takeIf { it.length >= 43 }?.let { return it }
        val t = randomToken()
        if (!sp.edit().putString(KEY_TOKEN, t).commit()) return null
        signingKey = null
        return t
    }

    /** New token: every paired browser and every outstanding signed URL stops working. */
    @Synchronized
    fun resetToken(context: Context) {
        val sp = HioneSecureStore.get(context) ?: return
        sp.edit().putString(KEY_TOKEN, randomToken()).commit()
        signingKey = null
        Log.i(TAG, "remote API token reset")
    }

    private fun randomToken(): String {
        val b = ByteArray(32)
        rng.nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    @Volatile private var signingKey: ByteArray? = null

    private fun urlKey(context: Context): ByteArray? {
        signingKey?.let { return it }
        val token = getOrCreateToken(context) ?: return null
        val k = hmac(token.toByteArray(Charsets.UTF_8), "hione-url-sign-v1".toByteArray(Charsets.UTF_8))
        signingKey = k
        return k
    }

    fun checkBearer(context: Context, headers: Map<String, String>): Boolean {
        val h = headers["authorization"] ?: return false
        if (!h.startsWith("Bearer ", ignoreCase = true)) return false
        val presented = h.substring(7).trim()
        val token = getOrCreateToken(context) ?: return false
        return constantTimeEquals(token, presented)
    }

    // ------------------------------------------------------------------ signed URLs

    private val SIGNABLE = Regex("^/api/v1/(artwork|audio/stream)/\\d+$")

    /** `path?exp=..&sig=..`, or null if [path] is not a signable media path. */
    fun signUrl(context: Context, path: String, ttlMs: Long): String? {
        if (!SIGNABLE.matches(path)) return null
        val key = urlKey(context) ?: return null
        val exp = (System.currentTimeMillis() + ttlMs) / 1000
        val sig = hex(hmac(key, "$path\n$exp".toByteArray(Charsets.UTF_8)))
        return "$path?exp=$exp&sig=$sig"
    }

    fun checkSignedUrl(context: Context, method: String, path: String, query: Map<String, String>): Boolean {
        if (method != "GET" && method != "HEAD") return false
        if (!SIGNABLE.matches(path)) return false
        val exp = query["exp"]?.toLongOrNull() ?: return false
        val sig = query["sig"] ?: return false
        if (exp * 1000 < System.currentTimeMillis()) return false
        val key = urlKey(context) ?: return false
        val want = hex(hmac(key, "$path\n$exp".toByteArray(Charsets.UTF_8)))
        return constantTimeEquals(want, sig.lowercase())
    }

    // ------------------------------------------------------------------ failed-auth limiter

    private class Fails(var windowStart: Long, var count: Int, var blockedUntil: Long)
    private val fails = LinkedHashMap<String, Fails>()

    @Synchronized
    fun isBlocked(ip: String): Boolean {
        val f = fails[ip] ?: return false
        return f.blockedUntil > System.currentTimeMillis()
    }

    @Synchronized
    fun recordFailure(ip: String) {
        val now = System.currentTimeMillis()
        val f = fails.getOrPut(ip) { Fails(now, 0, 0L) }
        if (now - f.windowStart > FAIL_WINDOW_MS) { f.windowStart = now; f.count = 0 }
        f.count++
        if (f.count >= FAIL_MAX) {
            f.blockedUntil = now + FAIL_BLOCK_MS
            f.count = 0
            Log.w(TAG, "blocking $ip for ${FAIL_BLOCK_MS / 1000}s after repeated failed auth")
        }
        // Bound the table so a flood of source addresses can't grow it forever.
        while (fails.size > 256) fails.remove(fails.keys.first())
    }

    // ------------------------------------------------------------------ pairing

    data class PairState(
        val code: String = "",
        val expiresAtMs: Long = 0L,
        val lockedUntilMs: Long = 0L,
        val pairedCount: Int = 0,
    ) {
        fun open(now: Long = System.currentTimeMillis()) = code.isNotEmpty() && expiresAtMs > now
    }

    private val _pair = MutableStateFlow(PairState())
    val pairState: StateFlow<PairState> = _pair
    private var wrongCodes = 0

    @Synchronized
    fun openPairing() {
        val now = System.currentTimeMillis()
        if (_pair.value.lockedUntilMs > now) return
        wrongCodes = 0
        _pair.value = _pair.value.copy(code = sixDigits(), expiresAtMs = now + PAIR_WINDOW_MS)
    }

    @Synchronized
    fun closePairing() {
        _pair.value = _pair.value.copy(code = "", expiresAtMs = 0L)
    }

    sealed class PairResult {
        data class Ok(val token: String) : PairResult()
        object BadCode : PairResult()
        object Closed : PairResult()
        object Locked : PairResult()
        object Unavailable : PairResult()
    }

    @Synchronized
    fun tryPair(context: Context, code: String): PairResult {
        val now = System.currentTimeMillis()
        val s = _pair.value
        if (s.lockedUntilMs > now) return PairResult.Locked
        if (!s.open(now)) return PairResult.Closed
        if (!constantTimeEquals(s.code, code.trim())) {
            wrongCodes++
            if (wrongCodes >= PAIR_MAX_WRONG) {
                wrongCodes = 0
                _pair.value = s.copy(code = "", expiresAtMs = 0L, lockedUntilMs = now + PAIR_LOCK_MS)
                Log.w(TAG, "pairing locked after $PAIR_MAX_WRONG wrong codes")
                return PairResult.Locked
            }
            return PairResult.BadCode
        }
        val token = getOrCreateToken(context) ?: return PairResult.Unavailable
        // Single use: the window closes as soon as one browser pairs.
        _pair.value = s.copy(code = "", expiresAtMs = 0L, pairedCount = s.pairedCount + 1)
        return PairResult.Ok(token)
    }

    fun sixDigits(): String = (rng.nextInt(1_000_000)).toString().padStart(6, '0')

    // ------------------------------------------------------------------ helpers

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(out)
    }

    fun randomHex(bytes: Int): String = ByteArray(bytes).also { rng.nextBytes(it) }.let { hex(it) }
}
