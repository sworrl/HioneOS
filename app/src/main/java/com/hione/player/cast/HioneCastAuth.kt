package com.hione.player.cast

import android.content.Context
import android.util.Log
import com.hione.player.api.HioneApiSecurity
import com.hione.player.api.HioneSecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who may receive the cast stream. See [HioneCastProtocol.TYPE_AUTH] for the handshake.
 *
 * A TV pairs once with the 6-digit code shown on the M500 while casting is on, and gets its own
 * random 256-bit token. After that it proves itself with an HMAC over a fresh challenge on every
 * connect. Tokens live in [HioneSecureStore]. Forgetting a TV deletes its token.
 */
object HioneCastAuth {
    private const val TAG = "HioneCastAuth"
    private const val KEY_SERVER_ID = "cast_server_id"
    private const val KEY_TVS = "cast_tvs"
    private const val MAX_WRONG = 5
    private const val LOCK_MS = 60_000L

    data class PairedTv(val id: String, val label: String, val pairedAtMs: Long, val lastSeenMs: Long)

    data class PairState(val code: String = "", val lockedUntilMs: Long = 0L, val generation: Int = 0)

    private val _state = MutableStateFlow(PairState())
    val state: StateFlow<PairState> = _state
    private var wrong = 0

    @Synchronized
    fun newCode() {
        wrong = 0
        _state.value = _state.value.copy(code = HioneApiSecurity.sixDigits())
    }

    @Synchronized
    fun clearCode() { _state.value = _state.value.copy(code = "") }

    fun serverId(ctx: Context): String? {
        val sp = HioneSecureStore.get(ctx) ?: return null
        sp.getString(KEY_SERVER_ID, null)?.let { return it }
        val id = HioneApiSecurity.randomHex(8)
        sp.edit().putString(KEY_SERVER_ID, id).commit()
        return id
    }

    fun challenge(): String = HioneApiSecurity.randomHex(32)

    sealed class Verdict {
        data class Ok(val tvLabel: String, val reply: JSONObject) : Verdict()
        data class Refused(val reply: JSONObject, val countsAsFailure: Boolean) : Verdict()
    }

    @Synchronized
    fun verify(ctx: Context, msg: JSONObject, challenge: String): Verdict {
        val tvs = load(ctx)
        val id = msg.optString("id")
        val mac = msg.optString("mac")
        if (id.isNotEmpty() && mac.isNotEmpty()) {
            val tv = (0 until tvs.length()).mapNotNull { tvs.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
                ?: return Verdict.Refused(err("unknown-tv"), true)
            val want = HioneApiSecurity.hex(
                HioneApiSecurity.hmac(tv.optString("token").toByteArray(Charsets.UTF_8), challenge.toByteArray(Charsets.UTF_8))
            )
            if (!HioneApiSecurity.constantTimeEquals(want, mac.lowercase())) return Verdict.Refused(err("unknown-tv"), true)
            tv.put("lastSeen", System.currentTimeMillis())
            save(ctx, tvs)
            return Verdict.Ok(tv.optString("label"), JSONObject().put("ok", true))
        }

        val code = msg.optString("code")
        if (code.isEmpty()) return Verdict.Refused(err("no-code"), true)
        val now = System.currentTimeMillis()
        val s = _state.value
        if (s.lockedUntilMs > now) return Verdict.Refused(err("locked"), false)
        if (s.code.isEmpty()) return Verdict.Refused(err("no-code"), true)
        if (!HioneApiSecurity.constantTimeEquals(s.code, code.trim())) {
            wrong++
            if (wrong >= MAX_WRONG) {
                wrong = 0
                _state.value = s.copy(code = "", lockedUntilMs = now + LOCK_MS)
                Log.w(TAG, "TV pairing locked after $MAX_WRONG wrong codes")
                return Verdict.Refused(err("locked"), true)
            }
            return Verdict.Refused(err("bad-code"), true)
        }
        val tvId = HioneApiSecurity.randomHex(8)
        val token = HioneApiSecurity.randomHex(32)
        val label = msg.optString("name").take(40).ifBlank { "TV" }
        tvs.put(JSONObject().put("id", tvId).put("label", label).put("token", token).put("pairedAt", now).put("lastSeen", now))
        if (!save(ctx, tvs)) return Verdict.Refused(err("unavailable"), false)
        // Single use: the next TV needs a new code.
        wrong = 0
        _state.value = s.copy(code = "", generation = s.generation + 1)
        Log.i(TAG, "paired TV '$label'")
        return Verdict.Ok(label, JSONObject().put("ok", true).put("id", tvId).put("token", token))
    }

    fun list(ctx: Context): List<PairedTv> {
        val arr = load(ctx)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PairedTv(o.optString("id"), o.optString("label"), o.optLong("pairedAt"), o.optLong("lastSeen"))
        }
    }

    @Synchronized
    fun forget(ctx: Context, id: String) {
        val arr = load(ctx)
        val out = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { out.put(it) }
        save(ctx, out)
        _state.value = _state.value.copy(generation = _state.value.generation + 1)
    }

    private fun load(ctx: Context): JSONArray {
        val raw = HioneSecureStore.get(ctx)?.getString(KEY_TVS, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun save(ctx: Context, arr: JSONArray): Boolean =
        HioneSecureStore.get(ctx)?.edit()?.putString(KEY_TVS, arr.toString())?.commit() ?: false

    private fun err(e: String) = JSONObject().put("ok", false).put("error", e)
}
