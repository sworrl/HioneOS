package com.hione.player.remote

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Persistence for the phone-remote feature: the master enable (default OFF — the GATT server is
 * never advertised unless the user turned it on), the PWA URL baked into the Settings QR code,
 * and the list of paired phones (token hashes only; the raw token lives on the phone).
 */
object HioneRemotePreferences {
    private const val PREFS = "hione_remote_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PWA_URL = "pwa_url"
    private const val KEY_ADVERTISE_NAME = "advertise_name"
    private const val KEY_ORIGINAL_BT_NAME = "original_bt_name"
    private const val KEY_PAIRED = "paired_json"

    /**
     * Where the companion PWA is hosted. Placeholder until the tools/remote-pwa folder is deployed
     * (see its README: any HTTPS static host works — GitHub Pages, Cloudflare Pages…). Editable in
     * the Settings card; whatever is set here is what the QR code encodes.
     */
    const val DEFAULT_PWA_URL = "https://hione-remote.pages.dev/"

    data class PairedPhone(
        val id: String,
        val label: String,
        val tokenHash: String,
        val pairedAtMs: Long,
        val lastSeenMs: Long
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getPwaUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_PWA_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PWA_URL

    fun setPwaUrl(ctx: Context, url: String) {
        val clean = url.trim()
        prefs(ctx).edit().apply {
            if (clean.isEmpty()) remove(KEY_PWA_URL) else putString(KEY_PWA_URL, clean)
        }.apply()
    }

    /** Rename the adapter to "Hione M500" while the remote runs (restored when it stops). */
    fun advertiseBrandedName(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ADVERTISE_NAME, true)
    fun setAdvertiseBrandedName(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ADVERTISE_NAME, on).apply()
    }

    /** The adapter name before we renamed it — persisted so a crash can't strand the rename. */
    fun getOriginalBtName(ctx: Context): String? = prefs(ctx).getString(KEY_ORIGINAL_BT_NAME, null)
    fun setOriginalBtName(ctx: Context, name: String?) {
        prefs(ctx).edit().apply {
            if (name == null) remove(KEY_ORIGINAL_BT_NAME) else putString(KEY_ORIGINAL_BT_NAME, name)
        }.apply()
    }

    // ---------------------------------------------------------------- paired phones

    @Synchronized
    fun listPaired(ctx: Context): List<PairedPhone> {
        val raw = prefs(ctx).getString(KEY_PAIRED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                PairedPhone(
                    id = o.optString("id"),
                    label = o.optString("label", "Phone"),
                    tokenHash = o.optString("hash"),
                    pairedAtMs = o.optLong("paired", 0L),
                    lastSeenMs = o.optLong("seen", 0L)
                )
            }.filter { it.id.isNotEmpty() && it.tokenHash.isNotEmpty() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<PairedPhone>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("label", p.label)
                put("hash", p.tokenHash)
                put("paired", p.pairedAtMs)
                put("seen", p.lastSeenMs)
            })
        }
        prefs(ctx).edit().putString(KEY_PAIRED, arr.toString()).apply()
    }

    /**
     * Registers a new phone and returns the RAW token to hand back over the pair characteristic.
     * Only the SHA-256 of it is stored here.
     */
    @Synchronized
    fun addPaired(ctx: Context, label: String): Pair<PairedPhone, String> {
        val token = randomHex(16)
        val now = System.currentTimeMillis()
        val phone = PairedPhone(
            id = randomHex(4),
            label = label.take(40).ifBlank { "Phone" },
            tokenHash = sha256Hex(token),
            pairedAtMs = now,
            lastSeenMs = now
        )
        save(ctx, listPaired(ctx) + phone)
        return phone to token
    }

    @Synchronized
    fun findByToken(ctx: Context, token: String): PairedPhone? {
        val hash = sha256Hex(token)
        return listPaired(ctx).firstOrNull { constantTimeEquals(it.tokenHash, hash) }
    }

    @Synchronized
    fun touch(ctx: Context, id: String) {
        val now = System.currentTimeMillis()
        save(ctx, listPaired(ctx).map { if (it.id == id) it.copy(lastSeenMs = now) else it })
    }

    @Synchronized
    fun forget(ctx: Context, id: String) {
        save(ctx, listPaired(ctx).filterNot { it.id == id })
    }

    @Synchronized
    fun forgetAll(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PAIRED).apply()
    }

    // ---------------------------------------------------------------- crypto helpers

    private val rng = SecureRandom()

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 6-digit pairing code, zero-padded, from SecureRandom (never from the clock). */
    fun randomPairingCode(): String = "%06d".format(rng.nextInt(1_000_000))

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
}
