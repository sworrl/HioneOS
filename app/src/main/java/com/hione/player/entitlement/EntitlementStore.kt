package com.hione.player.entitlement

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray

/**
 * SharedPreferences-backed state for the entitlement feature + the stable device identity.
 *
 * Everything here is plain (unencrypted) on purpose: the security property does not come from
 * hiding local state — it comes from the fact that only SIGNED server verdicts are ever written
 * to the history, and a lockout needs a chain of them. Tampering with this file can only ever
 * make the app MORE permissive (clearing history == fail-open), never lock the owner out.
 */
object EntitlementStore {
    private const val PREFS = "hione_entitlement"
    private const val K_ENABLED = "enabled"                 // user toggle, default OFF
    private const val K_HISTORY = "history_json"            // JSONArray of VerdictRecord
    private const val K_LAST_CHECK_AT = "last_check_at"
    private const val K_LAST_LABEL = "last_outcome_label"
    private const val K_LAST_DETAIL = "last_outcome_detail"
    private const val K_NEXT_CHECK_AT = "next_check_at"
    private const val K_OWNER_OVERRIDE = "owner_override"
    private const val K_DEVICE_ID = "device_id"
    private const val K_ID_SOURCE = "device_id_source"

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- user toggle ------------------------------------------------------------------------
    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(K_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean(K_ENABLED, on).apply() }

    // ---- owner override ---------------------------------------------------------------------
    fun isOwnerOverride(c: Context): Boolean = prefs(c).getBoolean(K_OWNER_OVERRIDE, false)
    fun setOwnerOverride(c: Context, on: Boolean) { prefs(c).edit().putBoolean(K_OWNER_OVERRIDE, on).apply() }

    // ---- last check bookkeeping (any outcome, incl. fail-open ones) -------------------------
    fun lastCheckAt(c: Context): Long = prefs(c).getLong(K_LAST_CHECK_AT, 0L)
    fun nextCheckAt(c: Context): Long = prefs(c).getLong(K_NEXT_CHECK_AT, 0L)
    fun lastOutcome(c: Context): Pair<String, String> =
        (prefs(c).getString(K_LAST_LABEL, "") ?: "") to (prefs(c).getString(K_LAST_DETAIL, "") ?: "")

    fun recordOutcome(c: Context, outcome: CheckOutcome, now: Long = System.currentTimeMillis()) {
        val e = prefs(c).edit()
            .putLong(K_LAST_CHECK_AT, now)
            .putLong(K_NEXT_CHECK_AT, now + EntitlementConfig.CHECK_INTERVAL_MS)
            .putString(K_LAST_LABEL, outcome.label)
            .putString(K_LAST_DETAIL, outcome.detail)
        if (outcome is CheckOutcome.Verified) {
            val list = history(c).toMutableList()
            list.add(outcome.record)
            while (list.size > EntitlementConfig.HISTORY_CAP) list.removeAt(0)
            e.putString(K_HISTORY, JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } }.toString())
        }
        e.apply()
    }

    /** Oldest → newest. Unparseable / unsigned rows are dropped silently (fail-open). */
    fun history(c: Context): List<VerdictRecord> = try {
        val arr = JSONArray(prefs(c).getString(K_HISTORY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { VerdictRecord.fromJson(it) } }
    } catch (_: Throwable) { emptyList() }

    fun clearHistory(c: Context) {
        prefs(c).edit().remove(K_HISTORY).remove(K_LAST_LABEL).remove(K_LAST_DETAIL).apply()
    }

    // ---- device identity --------------------------------------------------------------------
    /**
     * Stable device id = SHA-256("hione-m500\n<serial-or-android_id>\n<brand gate values>").
     * The brand-gate values are the same Build fields the local hardware gates key on
     * (BRAND/DEVICE from PlayerHolder.brandDeviceGateOk, MANUFACTURER/MODEL from
     * MainActivity.isSupportedDevice) plus HARDWARE, so an id is bound to a real M500 identity.
     *
     * Computed once and pinned in prefs: Build.getSerial() needs READ_PHONE_STATE and may throw on
     * one boot and not another; pinning stops the id from drifting (which would otherwise make
     * the device "unknown" → default-allow, harmless but confusing on the dashboard).
     */
    fun deviceId(c: Context): String {
        prefs(c).getString(K_DEVICE_ID, null)?.takeIf { it.length == 64 }?.let { return it }
        val (raw, source) = hardwareSerialOrAndroidId(c)
        val material = listOf(
            "hione-m500", raw,
            Build.BRAND, Build.DEVICE, Build.MANUFACTURER, Build.MODEL, Build.HARDWARE
        ).joinToString("\n")
        val id = EntitlementConfig.sha256Hex(material)
        prefs(c).edit().putString(K_DEVICE_ID, id).putString(K_ID_SOURCE, source).apply()
        return id
    }

    fun deviceIdSource(c: Context): String = prefs(c).getString(K_ID_SOURCE, "") ?: ""

    @Suppress("DEPRECATION")
    private fun hardwareSerialOrAndroidId(c: Context): Pair<String, String> {
        try {
            val s = Build.getSerial()
            if (s.isNotBlank() && !s.equals(Build.UNKNOWN, ignoreCase = true)) return s to "serial"
        } catch (_: Throwable) { /* SecurityException without READ_PHONE_STATE — expected */ }
        try {
            val s = Build.SERIAL
            if (!s.isNullOrBlank() && !s.equals(Build.UNKNOWN, ignoreCase = true)) return s to "serial"
        } catch (_: Throwable) {}
        val aid = EntitlementConfig.androidId(c)
        return if (aid.isNotBlank()) aid to "android_id" else Build.FINGERPRINT to "fingerprint"
    }
}
