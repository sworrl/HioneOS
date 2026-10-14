package com.hione.launcher

import android.content.Context
import java.net.NetworkInterface

/**
 * Central, PII-free configuration holder for the media ingestion server (rsync sync target)
 * and network/VPN telemetry display. Mirror of the app-module copy (com.hione.player).
 *
 * No personal host, IP, port, subnet or SSID is ever hardcoded in source. Every value is
 * resolved at runtime in priority order:
 *
 *   1. SharedPreferences  — what the user entered at runtime (persisted via the set* helpers).
 *   2. BuildConfig field  — seeded from the gitignored `local.properties` at build time
 *                           (e.g. `hione.sync.host=...`); empty when the file omits the key.
 *   3. Neutral default    — an empty string, which triggers runtime auto-discovery. The sole
 *                           non-empty default is the well-known rsync protocol port (8730),
 *                           which is a protocol constant, not PII.
 *
 * When the sync host is unset, [discoveryTargets] falls back to universal probes (global
 * broadcast + loopback for the USB adb-forward path) plus directed broadcast addresses derived
 * live from the device's own network interfaces — never a baked-in personal IP.
 *
 * Any example addresses in comments use the RFC 5737 TEST-NET-1 block (192.0.2.0/24) or RFC 1918
 * ranges purely as documentation and are never used as real defaults.
 */
object HioneIngestConfig {

    /** rsync daemon port. A well-known protocol constant (not PII); overridable via config. */
    const val RSYNC_PORT_DEFAULT = 8730

    private const val PREFS = "hione_ingest_prefs"

    // Preference keys (runtime, user-editable)
    const val KEY_SYNC_HOST = "hione_sync_host"
    const val KEY_SYNC_PORT = "hione_sync_port"
    const val KEY_LAN_HINTS = "hione_lan_hints"
    const val KEY_HOME_SSIDS = "hione_home_ssids"
    const val KEY_VPN_ENDPOINT = "hione_vpn_endpoint"
    const val KEY_VPN_ASSIGNED_IP = "hione_vpn_assigned_ip"
    const val KEY_VPN_ROUTES = "hione_vpn_routes"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Prefs value → BuildConfig fallback → empty. Blank prefs entries are ignored. */
    private fun resolve(ctx: Context, key: String, buildConfigValue: String): String {
        val pref = prefs(ctx).getString(key, null)?.trim()
        if (!pref.isNullOrEmpty()) return pref
        return buildConfigValue.trim()
    }

    private fun csv(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    // ── Ingestion server ───────────────────────────────────────────────────────────────────

    /** Sync/ingest server host. Empty string => trigger runtime auto-discovery (never a personal IP). */
    fun syncHost(ctx: Context): String = resolve(ctx, KEY_SYNC_HOST, BuildConfig.HIONE_SYNC_HOST)

    fun setSyncHost(ctx: Context, host: String) {
        prefs(ctx).edit().putString(KEY_SYNC_HOST, host.trim()).apply()
    }

    /** rsync port: prefs → BuildConfig → protocol default (8730). */
    fun rsyncPort(ctx: Context): Int {
        val raw = resolve(ctx, KEY_SYNC_PORT, BuildConfig.HIONE_SYNC_PORT)
        return raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: RSYNC_PORT_DEFAULT
    }

    fun setRsyncPort(ctx: Context, port: Int) {
        prefs(ctx).edit().putString(KEY_SYNC_PORT, port.toString()).apply()
    }

    /** Optional user-supplied LAN hints (comma-separated IPs / subnets) for discovery + home detection. */
    fun lanHints(ctx: Context): List<String> = csv(resolve(ctx, KEY_LAN_HINTS, BuildConfig.HIONE_LAN_HINTS))

    fun setLanHints(ctx: Context, hints: String) {
        prefs(ctx).edit().putString(KEY_LAN_HINTS, hints.trim()).apply()
    }

    /**
     * Probe targets for daemon auto-discovery. All PII-free:
     *  - global broadcast (255.255.255.255) + loopback (127.0.0.1, the USB adb-forward path),
     *  - the explicitly configured sync host, if any,
     *  - directed broadcast addresses read live from the device's network interfaces,
     *  - any optional user-provided LAN hints.
     */
    fun discoveryTargets(ctx: Context): List<String> {
        val targets = LinkedHashSet<String>()
        targets.add("255.255.255.255")
        targets.add("127.0.0.1")
        syncHost(ctx).takeIf { it.isNotBlank() }?.let { targets.add(it) }
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (nif.isLoopback || !nif.isUp) continue
                for (ia in nif.interfaceAddresses) {
                    ia.broadcast?.hostAddress?.let { targets.add(it) }
                }
            }
        } catch (_: Throwable) {}
        lanHints(ctx).forEach { targets.add(it) }
        return targets.toList()
    }

    // ── Network / VPN telemetry (display only) ───────────────────────────────────────────────

    /** Configured home-network SSIDs (comma-separated). Empty => nothing treated as "home". */
    fun homeSsids(ctx: Context): List<String> = csv(resolve(ctx, KEY_HOME_SSIDS, BuildConfig.HIONE_HOME_SSIDS))

    fun setHomeSsids(ctx: Context, ssids: String) {
        prefs(ctx).edit().putString(KEY_HOME_SSIDS, ssids.trim()).apply()
    }

    /**
     * Whether the current Wi-Fi is a configured "home" network. Matches configured SSIDs exactly
     * and configured LAN hints as address prefixes (a hint like "192.0.2." or "192.0.2.0/24"
     * matches by its leading dotted octets). With nothing configured this returns false — a safe,
     * neutral default that never keys off a personal SSID or subnet.
     */
    fun isHomeNetwork(ctx: Context, connected: Boolean, ssid: String, ipAddress: String): Boolean {
        if (!connected) return false
        if (homeSsids(ctx).any { it.equals(ssid, ignoreCase = true) }) return true
        return lanHints(ctx).any { hint ->
            val prefix = hint.substringBefore('/').trim().let { if (it.endsWith(".")) it else "$it." }
            prefix.count { c -> c == '.' } >= 2 && ipAddress.startsWith(prefix)
        }
    }

    fun vpnEndpoint(ctx: Context): String = resolve(ctx, KEY_VPN_ENDPOINT, BuildConfig.HIONE_VPN_ENDPOINT)
    fun setVpnEndpoint(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_VPN_ENDPOINT, v.trim()).apply() }

    fun vpnAssignedIp(ctx: Context): String = resolve(ctx, KEY_VPN_ASSIGNED_IP, BuildConfig.HIONE_VPN_ASSIGNED_IP)
    fun setVpnAssignedIp(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_VPN_ASSIGNED_IP, v.trim()).apply() }

    fun vpnRoutes(ctx: Context): String = resolve(ctx, KEY_VPN_ROUTES, BuildConfig.HIONE_VPN_ROUTES)
    fun setVpnRoutes(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_VPN_ROUTES, v.trim()).apply() }
}
