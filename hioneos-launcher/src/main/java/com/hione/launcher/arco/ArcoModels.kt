package com.hione.launcher.arco

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire models for the arcobocconotto RGB fleet controller REST + WebSocket API.
 * Mirrors docs/android/API_REFERENCE.md, PAIRING_SPEC.md and WEBSOCKET_PROTOCOL.md
 * in the arcobocconotto repo (not vendored here — those docs are the source of
 * truth; keep this file in sync if the server's schema changes).
 */

// ---------------------------------------------------------------------------
// Pairing (PAIRING_SPEC.md)
// ---------------------------------------------------------------------------

@Serializable
data class ArcoPairInitRequest(
    val client_id: String,
    val device_name: String,
    val platform: String,
    val app_version: String
)

@Serializable
data class ArcoPairInitResponse(
    val session_id: String,
    val code: String,
    val display_code: String,
    val expires_in: Int,
    val server_name: String
)

@Serializable
data class ArcoPairVerifyRequest(
    val session_id: String,
    val code: String
)

@Serializable
data class ArcoPairVerifyResponse(
    val status: String,
    val device_id: String,
    val device_name: String,
    val api_key_id: String,
    val auth_token: String,
    val server_version: String,
    val ws_endpoint: String
)

@Serializable
data class ArcoApiError(
    val error: String? = null
)

// ---------------------------------------------------------------------------
// Lighting & effects (API_REFERENCE.md §2)
// ---------------------------------------------------------------------------

@Serializable
data class ArcoStatusResponse(
    val current_effect: String = ""
)

@Serializable
data class ArcoTheme(
    val id: String,
    val name: String,
    val category: String = "",
    val description: String = "",
    val palette_hex: List<String> = emptyList()
)

@Serializable
data class ArcoEffectsResponse(
    val effects: List<String> = emptyList(),
    val themes: List<ArcoTheme> = emptyList()
)

@Serializable
data class ArcoEffectStartResponse(
    val started: String? = null,
    val args: List<String>? = null
)

@Serializable
data class ArcoStopResponse(
    val stopped: Boolean = false
)

@Serializable
data class ArcoNotifyRequest(
    val color: String,
    val style: String = "triple_flash",
    val zones: List<String>? = null,
    val duration_ms: Int = 1800
)

@Serializable
data class ArcoNotifyResponse(
    val notified: Boolean = false,
    val color: String = "",
    val style: String = "",
    val zones: List<String>? = null,
    val duration_ms: Int = 0
)

@Serializable
data class ArcoAudioPushRequest(
    val level: Float,
    val beat: Float,
    val bands: List<Float>
)

@Serializable
data class ArcoAudioPushResponse(
    val ok: Boolean = false
)

@Serializable
data class ArcoTrackMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val bpm: Double = 0.0,
    val energy: Double = 0.0,
    val duration_ms: Long = 0,
    val album_art_url: String = "",
    val palette: List<String> = emptyList(),
    val style_hint: String = "",
    val is_playing: Boolean = false,
    val source: String = "android_app",
    val last_update: String? = null
)

@Serializable
data class ArcoTrackPushResponse(
    val ok: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val genre: String = "",
    val style_hint: String = "",
    val palette: List<String> = emptyList()
)

@Serializable
data class ArcoPalettePushRequest(
    val palette: List<String>,
    val apply_mode: String = "ambient" // "visualizer" | "ambient"
)

@Serializable
data class ArcoPalettePushResponse(
    val ok: Boolean = false,
    val palette: List<String> = emptyList()
)

// ---------------------------------------------------------------------------
// Device management (API_REFERENCE.md §3)
// ---------------------------------------------------------------------------

@Serializable
data class ArcoDeviceInfo(
    val id: String,
    val name: String,
    val platform: String = "",
    val app_version: String = "",
    val key_id: String = "",
    val paired_at: String = "",
    val last_seen: String = "",
    val last_ip: String = ""
)

@Serializable
data class ArcoDevicesResponse(
    val devices: List<ArcoDeviceInfo> = emptyList()
)

@Serializable
data class ArcoRenameDeviceRequest(
    val name: String
)

// ---------------------------------------------------------------------------
// Zones — TODO(server): arcobocconotto has no live zone/LED-map endpoint yet
// (checked pkg/server/server.go route table — only /api/notify's static
// `zones` enum exists: all/fans/ram/mouse/keyboard/mobo/gpu/peripherals/desk).
// The rig's real hardware zones/LED counts are DYNAMIC (the user keeps adding
// zones), so this client deliberately does NOT hardcode the docs' 17-zone/
// 150-LED table anywhere. ArcoClient.fetchZones() speculatively tries a
// GET /api/zones (and /api/devices/hardware) endpoint first — once the server
// adds one, drop the response shape in below and it wires up automatically —
// and falls back to the small, documented, stable notify-zone enum otherwise.
// ---------------------------------------------------------------------------

@Serializable
data class ArcoZone(
    val id: String,
    val name: String,
    @SerialName("led_count") val ledCount: Int? = null,
    val group: String? = null
)

@Serializable
data class ArcoZonesResponse(
    val zones: List<ArcoZone> = emptyList()
)

// ---------------------------------------------------------------------------
// WebSocket envelope (WEBSOCKET_PROTOCOL.md)
// ---------------------------------------------------------------------------

@Serializable
data class ArcoWsMessage(
    val type: String,
    val id: String? = null,
    val reply_to: String? = null,
    val payload: JsonElement? = null
)

@Serializable
data class ArcoAudioSpectrum(
    val level: Float = 0f,
    val beat: Float = 0f,
    val bands: List<Float> = emptyList()
)

@Serializable
data class ArcoNotificationEvent(
    val color: String = "",
    val style: String = "",
    val zones: List<String>? = null,
    val duration_ms: Int = 1800
)

@Serializable
data class ArcoWelcomePayload(
    val server_name: String = "",
    val server_version: String = "",
    val current_effect: String = "",
    val device_id: String = "",
    val device_name: String = "",
    val timestamp: Long = 0
)

@Serializable
data class ArcoStatusChangePayload(
    val current_effect: String = ""
)

// ---------------------------------------------------------------------------
// Client-facing connection state
// ---------------------------------------------------------------------------

enum class ArcoConnectionState {
    UNPAIRED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class ArcoDiscoveredServer(
    val name: String,
    val host: String,
    val port: Int
) {
    val url: String get() = "http://$host:$port"
}
