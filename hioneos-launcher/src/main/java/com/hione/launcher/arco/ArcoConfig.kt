package com.hione.launcher.arco

import com.hione.launcher.BuildConfig

/**
 * Non-secret protocol constants for the arcobocconotto integration, plus the
 * only place BuildConfig-sourced local-dev fallbacks are read from.
 *
 * IMPORTANT — no PII/secrets live in source here. [BuildConfig.ARCO_HMAC_KEY_ID],
 * [BuildConfig.ARCO_HMAC_SECRET], [BuildConfig.ARCO_LAN_URL] and
 * [BuildConfig.ARCO_PUBLIC_URL] are compiled in from an *optional, gitignored*
 * `hioneos-launcher/arco.properties` file (see arco.properties.example) by
 * build.gradle.kts — they are empty strings on any build where that file
 * doesn't exist, which is the expected state for anyone other than the
 * original owner's local dev machine. The runtime source of truth is always
 * [ArcoSecureStore] (EncryptedSharedPreferences), populated interactively via
 * mDNS discovery + SAS pairing or manual entry in HioneArcoSettingsActivity.
 */
object ArcoConfig {
    /** mDNS/DNS-SD service type advertised by the daemon (PAIRING_SPEC.md §1). */
    const val MDNS_SERVICE_TYPE = "_arcobocconotto._tcp"
    const val DEFAULT_PORT = 8000
    const val WS_PATH = "/ws"

    const val PAIR_INIT_PATH = "/api/pair/init"
    const val PAIR_VERIFY_PATH = "/api/pair/verify"

    /** WEBSOCKET_PROTOCOL.md §5 reconnection guidelines. */
    const val WS_HEARTBEAT_INTERVAL_MS = 25_000L
    const val WS_READ_TIMEOUT_MS = 60_000L
    const val WS_RECONNECT_INITIAL_DELAY_MS = 1_000L
    const val WS_RECONNECT_MAX_DELAY_MS = 30_000L
    const val WS_RECONNECT_MULTIPLIER = 1.5
    const val WS_RECONNECT_JITTER = 0.2

    /** Static fallback zone enum from API_REFERENCE.md's /api/notify docs — NOT
     * the hardware rig map (that's dynamic; see ArcoModels.kt's ArcoZone note).
     * Used only until the server exposes a real zones endpoint, or if that
     * endpoint call fails at runtime. */
    val NOTIFY_ZONE_FALLBACK = listOf("all", "fans", "ram", "mouse", "keyboard", "mobo", "gpu", "peripherals", "desk")

    val NOTIFY_STYLES = listOf("pulse", "double_strobe", "triple_flash", "solid", "comet", "rainbow")

    /** Optional local-dev direct-key fallback, see class doc. Empty when unset. */
    fun buildConfigHmacKeyId(): String? = BuildConfig.ARCO_HMAC_KEY_ID.ifBlank { null }
    fun buildConfigHmacSecret(): String? = BuildConfig.ARCO_HMAC_SECRET.ifBlank { null }
    fun buildConfigLanUrl(): String? = BuildConfig.ARCO_LAN_URL.ifBlank { null }
    fun buildConfigPublicUrl(): String? = BuildConfig.ARCO_PUBLIC_URL.ifBlank { null }

    fun wsUrlFor(baseUrl: String, token: String?): String {
        val scheme = baseUrl.replace("http://", "ws://").replace("https://", "wss://").trimEnd('/')
        return if (token.isNullOrBlank()) "$scheme$WS_PATH" else "$scheme$WS_PATH?token=$token"
    }
}
