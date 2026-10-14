package com.hione.launcher.arco

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow
import kotlin.random.Random

/**
 * Singleton client for the arcobocconotto RGB fleet controller — mDNS
 * discovery, SAS pairing, REST (`/api/…`) and a coroutine WebSocket (`/ws`)
 * with live state exposed as [StateFlow]/[SharedFlow]. Adapted from
 * docs/android/android_client_reference.kt in the arcobocconotto repo,
 * updated to this project's OkHttp 4.12 + kotlinx.serialization stack and to
 * an object-singleton shape matching this module's other services
 * (HioneNetworkService, HioneBpmEngine).
 *
 * Call [init] once (idempotent) before using — HioneArcoBadge/HioneArcoModal/
 * HioneArcoSettingsActivity all do this defensively so any of them can be the
 * first to touch the client.
 */
object ArcoClient {
    private const val TAG = "ArcoClient"

    private lateinit var appContext: Context
    private lateinit var secureStore: ArcoSecureStore
    lateinit var discovery: ArcoDiscovery
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(ArcoConfig.WS_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var initialized = false
    private var webSocket: WebSocket? = null
    private var wsGeneration = 0
    private var reconnectAttempt = 0
    private var heartbeatJob: Job? = null

    // --- Public state ---------------------------------------------------

    private val _connectionState = MutableStateFlow(ArcoConnectionState.UNPAIRED)
    val connectionState: StateFlow<ArcoConnectionState> = _connectionState.asStateFlow()

    // "" = the rig's effect has never been READ (no welcome/status frame yet, direct-key-only mode,
    // disconnected, or reconnect backoff). It defaulted to "off", which the UI rendered as a live
    // reading: "Active effect: off", "Fleet is blacked out", and an unchecked Power switch — a
    // claim about hardware state we had never once heard from.
    private val _activeEffect = MutableStateFlow("")
    val activeEffect: StateFlow<String> = _activeEffect.asStateFlow()

    private val _effects = MutableStateFlow<List<String>>(emptyList())
    val effects: StateFlow<List<String>> = _effects.asStateFlow()

    private val _themes = MutableStateFlow<List<ArcoTheme>>(emptyList())
    val themes: StateFlow<List<ArcoTheme>> = _themes.asStateFlow()

    /** Dynamic zone/device list — see the TODO(server) note on [ArcoZone]. */
    private val _zones = MutableStateFlow<List<ArcoZone>>(emptyList())
    val zones: StateFlow<List<ArcoZone>> = _zones.asStateFlow()

    /**
     * PROVENANCE of [zones]: true only when a zones endpoint actually answered. False means the list
     * is the hardcoded NOTIFY_ZONE_FALLBACK enum. The zones pane used to badge the fallback list as
     * "LIVE" and state "nothing here is hardcoded" — the exact opposite of the truth for the current
     * server build, which exposes no zones endpoint at all.
     */
    private val _zonesAreLive = MutableStateFlow(false)
    val zonesAreLive: StateFlow<Boolean> = _zonesAreLive.asStateFlow()

    private val _devices = MutableStateFlow<List<ArcoDeviceInfo>>(emptyList())
    val devices: StateFlow<List<ArcoDeviceInfo>> = _devices.asStateFlow()

    private val _trackMetadata = MutableStateFlow<ArcoTrackMetadata?>(null)
    val trackMetadata: StateFlow<ArcoTrackMetadata?> = _trackMetadata.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _audioSpectrum = MutableSharedFlow<ArcoAudioSpectrum>(extraBufferCapacity = 1)
    val audioSpectrum: SharedFlow<ArcoAudioSpectrum> = _audioSpectrum.asSharedFlow()

    private val _notificationEvents = MutableSharedFlow<ArcoNotificationEvent>(extraBufferCapacity = 10)
    val notificationEvents: SharedFlow<ArcoNotificationEvent> = _notificationEvents.asSharedFlow()

    /** Toggle for [ArcoMusicVisualizerBridge]; persisted so it survives restarts. */
    private val _musicVisualizerBridgeEnabled = MutableStateFlow(false)
    val musicVisualizerBridgeEnabled: StateFlow<Boolean> = _musicVisualizerBridgeEnabled.asStateFlow()

    val isPaired: Boolean get() = ::secureStore.isInitialized && secureStore.isPaired
    val isDirectKeyConfigured: Boolean get() = ::secureStore.isInitialized && secureStore.isDirectKeyConfigured
    val serverUrl: String? get() = if (::secureStore.isInitialized) secureStore.serverUrl else null
    val deviceName: String? get() = if (::secureStore.isInitialized) secureStore.deviceName else null

    // --- Lifecycle --------------------------------------------------------

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        secureStore = ArcoSecureStore(appContext)
        discovery = ArcoDiscovery(appContext)
        _musicVisualizerBridgeEnabled.value = secureStore.musicVisualizerBridgeEnabled
        initialized = true

        if (secureStore.isPaired) {
            _connectionState.value = ArcoConnectionState.DISCONNECTED
            connectWebSocket()
            scope.launch { refreshEffectsAndZones() }
        } else {
            _connectionState.value = ArcoConnectionState.UNPAIRED
        }
    }

    suspend fun refreshEffectsAndZones() {
        getEffects()
        fetchZones()
    }

    // -----------------------------------------------------------------------
    // 1. Pairing (PAIRING_SPEC.md)
    // -----------------------------------------------------------------------

    suspend fun initiatePairing(serverUrl: String, deviceName: String): Result<ArcoPairInitResponse> {
        val req = ArcoPairInitRequest(
            client_id = UUID.randomUUID().toString(),
            device_name = deviceName,
            platform = "Android ${android.os.Build.VERSION.RELEASE}",
            app_version = "1.0.0"
        )
        return httpRequestAbsolute("POST", serverUrl.trimEnd('/') + ArcoConfig.PAIR_INIT_PATH, json.encodeToString(req), authenticate = false)
            .mapCatching { json.decodeFromString<ArcoPairInitResponse>(it) }
    }

    suspend fun verifyPairing(serverUrl: String, sessionId: String, code: String): Result<ArcoPairVerifyResponse> {
        val req = ArcoPairVerifyRequest(session_id = sessionId, code = code)
        val result = httpRequestAbsolute("POST", serverUrl.trimEnd('/') + ArcoConfig.PAIR_VERIFY_PATH, json.encodeToString(req), authenticate = false)
            .mapCatching { json.decodeFromString<ArcoPairVerifyResponse>(it) }

        result.onSuccess { verified ->
            secureStore.serverUrl = serverUrl.trimEnd('/')
            secureStore.authToken = verified.auth_token
            secureStore.deviceId = verified.device_id
            secureStore.deviceName = verified.device_name
            secureStore.apiKeyId = verified.api_key_id
            secureStore.serverVersion = verified.server_version
            secureStore.wsEndpoint = verified.ws_endpoint
            _connectionState.value = ArcoConnectionState.DISCONNECTED
            connectWebSocket()
            scope.launch { refreshEffectsAndZones() }
        }
        return result
    }

    /** Clears local pairing state. Server-side revocation is a separate,
     * explicit action via [revokeDevice] (PAIRING_SPEC.md §4). */
    fun unpair() {
        disconnect()
        if (::secureStore.isInitialized) secureStore.clearPairing()
        _connectionState.value = ArcoConnectionState.UNPAIRED
        _activeEffect.value = "off"
        _trackMetadata.value = null
    }

    /** Lets the settings UI save/point at a manually entered LAN IP or the
     * public HTTPS URL without going through discovery. Does not itself pair —
     * call [initiatePairing] against the returned/saved URL next. */
    fun setManualServerUrl(url: String) {
        if (::secureStore.isInitialized) secureStore.serverUrl = url.trimEnd('/')
    }

    /** Direct-key path (HMAC pre-shared secret): lets REST calls succeed
     * without interactive pairing when a key id/secret are present (either
     * saved into the encrypted store from the settings UI, or sourced from
     * the optional gitignored arco.properties via [ArcoConfig]). This does
     * NOT open the WebSocket — /ws only documents Bearer-token auth. */
    fun setDirectKey(serverUrl: String, keyId: String, secret: String) {
        if (!::secureStore.isInitialized) return
        secureStore.serverUrl = serverUrl.trimEnd('/')
        secureStore.hmacKeyId = keyId
        secureStore.hmacSecret = secret
        _connectionState.value = ArcoConnectionState.DISCONNECTED
        scope.launch { refreshEffectsAndZones() }
    }

    // -----------------------------------------------------------------------
    // 2. REST — lighting & effects (API_REFERENCE.md §2)
    // -----------------------------------------------------------------------

    suspend fun getStatus(): Result<ArcoStatusResponse> =
        httpRequest("GET", "/api/status").mapCatching { json.decodeFromString<ArcoStatusResponse>(it) }
            .onSuccess { _activeEffect.value = it.current_effect }

    suspend fun getEffects(): Result<ArcoEffectsResponse> =
        httpRequest("GET", "/api/effects").mapCatching { json.decodeFromString<ArcoEffectsResponse>(it) }
            .onSuccess { _effects.value = it.effects; _themes.value = it.themes }

    suspend fun startEffect(name: String, args: List<String>? = null): Result<ArcoEffectStartResponse> {
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val query = if (!args.isNullOrEmpty()) {
            "?" + args.joinToString("&") { "args=" + URLEncoder.encode(it, "UTF-8") }
        } else ""
        return httpRequest("POST", "/api/effect/$encodedName$query")
            .mapCatching { json.decodeFromString<ArcoEffectStartResponse>(it) }
            .onSuccess { it.started?.let { name2 -> _activeEffect.value = name2 } }
    }

    /** Halts active lighting and blacks out the fleet (also used as "power off"). */
    suspend fun stop(): Result<ArcoStopResponse> =
        httpRequest("POST", "/api/stop").mapCatching { json.decodeFromString<ArcoStopResponse>(it) }
            .onSuccess { if (it.stopped) _activeEffect.value = "off" }

    suspend fun notify(color: String, style: String = "triple_flash", zones: List<String>? = null, durationMs: Int = 1800): Result<ArcoNotifyResponse> =
        httpRequest("POST", "/api/notify", json.encodeToString(ArcoNotifyRequest(color, style, zones, durationMs)))
            .mapCatching { json.decodeFromString<ArcoNotifyResponse>(it) }

    /** Pushes 8 band levels + beat data to drive arco's music visualizer themes. NOTE: the bands are
     * SYNTHESIZED from tempo/beat phase by ArcoMusicVisualizerBridge — HioneOS broadcasts no real
     * per-band spectral data — so this is not FFT output despite the shape of the payload.
     * See [ArcoMusicVisualizerBridge] for the BPM-engine-driven caller. */
    suspend fun pushAudio(level: Float, beat: Float, bands: List<Float>): Result<ArcoAudioPushResponse> =
        httpRequest("POST", "/api/audio", json.encodeToString(ArcoAudioPushRequest(level, beat, bands)))
            .mapCatching { json.decodeFromString<ArcoAudioPushResponse>(it) }

    suspend fun pushTrackMetadata(meta: ArcoTrackMetadata): Result<ArcoTrackPushResponse> =
        httpRequest("POST", "/api/music/track", json.encodeToString(meta)).mapCatching { json.decodeFromString<ArcoTrackPushResponse>(it) }

    suspend fun getTrackMetadata(): Result<ArcoTrackMetadata> =
        httpRequest("GET", "/api/music/track").mapCatching { json.decodeFromString<ArcoTrackMetadata>(it) }
            .onSuccess { _trackMetadata.value = it }

    suspend fun pushAlbumPalette(palette: List<String>, applyMode: String = "ambient"): Result<ArcoPalettePushResponse> =
        httpRequest("POST", "/api/music/palette", json.encodeToString(ArcoPalettePushRequest(palette, applyMode)))
            .mapCatching { json.decodeFromString<ArcoPalettePushResponse>(it) }

    /**
     * TODO(server): arcobocconotto has no brightness/dimming endpoint yet —
     * checked pkg/server/server.go's route table and pkg/server/ws.go's
     * message-type switch in the arcobocconotto repo; neither exposes one.
     * This speculatively calls POST /api/brightness so the settings slider
     * wires up automatically the moment the server adds support; until then
     * it resolves to a clean [Result.failure] the UI can surface as
     * "brightness control isn't supported by this server build yet" rather
     * than silently doing nothing.
     */
    suspend fun setBrightness(value: Float): Result<Unit> =
        httpRequest("POST", "/api/brightness", json.encodeToString(mapOf("value" to value.coerceIn(0f, 1f))))
            .mapCatching { }

    // -----------------------------------------------------------------------
    // 3. Device management (API_REFERENCE.md §3)
    // -----------------------------------------------------------------------

    suspend fun listDevices(): Result<List<ArcoDeviceInfo>> =
        httpRequest("GET", "/api/devices").mapCatching { json.decodeFromString<ArcoDevicesResponse>(it).devices }
            .onSuccess { _devices.value = it }

    suspend fun revokeDevice(id: String): Result<Unit> =
        httpRequest("DELETE", "/api/devices/${URLEncoder.encode(id, "UTF-8")}").mapCatching { }

    suspend fun renameDevice(id: String, name: String): Result<Unit> =
        httpRequest("POST", "/api/devices/${URLEncoder.encode(id, "UTF-8")}/rename", json.encodeToString(ArcoRenameDeviceRequest(name)))
            .mapCatching { }

    // -----------------------------------------------------------------------
    // Zones — dynamic, see the TODO(server) note on ArcoZone in ArcoModels.kt.
    // Never hardcode the rig's LED map here; always resolve it at call time.
    // -----------------------------------------------------------------------

    suspend fun fetchZones(): Result<List<ArcoZone>> = withContext(Dispatchers.IO) {
        for (path in ZONE_ENDPOINT_CANDIDATES) {
            val raw = httpRequest("GET", path).getOrNull() ?: continue
            val parsed = runCatching { parseZonesLenient(raw) }.getOrNull()
            if (!parsed.isNullOrEmpty()) {
                _zones.value = parsed
                _zonesAreLive.value = true
                return@withContext Result.success(parsed)
            }
        }
        // Fallback: the small, documented, stable /api/notify zone enum —
        // NOT the hardware LED table, which is dynamic and lives server-side.
        val fallback = ArcoConfig.NOTIFY_ZONE_FALLBACK.map {
            ArcoZone(id = it, name = it.replaceFirstChar { c -> c.uppercase() }, ledCount = null, group = "notify")
        }
        _zones.value = fallback
        _zonesAreLive.value = false
        Result.success(fallback)
    }

    private fun parseZonesLenient(raw: String): List<ArcoZone> {
        val root = json.decodeFromString<JsonElement>(raw)
        val arr: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["zones"] as? JsonArray) ?: (root["devices"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        return arr.mapNotNull { el ->
            when (el) {
                is JsonObject -> {
                    val id = (el["id"] as? JsonPrimitive)?.contentOrNull() ?: (el["name"] as? JsonPrimitive)?.contentOrNull() ?: return@mapNotNull null
                    val name = (el["name"] as? JsonPrimitive)?.contentOrNull() ?: id
                    val led = (el["led_count"] as? JsonPrimitive)?.contentOrNull()?.toIntOrNull()
                        ?: (el["ledCount"] as? JsonPrimitive)?.contentOrNull()?.toIntOrNull()
                        ?: (el["count"] as? JsonPrimitive)?.contentOrNull()?.toIntOrNull()
                    val group = (el["group"] as? JsonPrimitive)?.contentOrNull()
                    ArcoZone(id = id, name = name, ledCount = led, group = group)
                }
                is JsonPrimitive -> el.contentOrNull()?.let { ArcoZone(id = it, name = it) }
                else -> null
            }
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (this.toString() == "null") null else this.content

    // -----------------------------------------------------------------------
    // 4. WebSocket (WEBSOCKET_PROTOCOL.md)
    // -----------------------------------------------------------------------

    @Synchronized
    fun connectWebSocket() {
        if (!::secureStore.isInitialized) return
        val token = secureStore.authToken
        val base = secureStore.serverUrl
        if (token.isNullOrBlank() || base.isNullOrBlank()) {
            _connectionState.value = ArcoConnectionState.UNPAIRED
            return
        }
        closeSocketOnly()
        _connectionState.value = ArcoConnectionState.CONNECTING
        val myGeneration = ++wsGeneration
        val wsUrl = ArcoConfig.wsUrlFor(base, token)
        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (myGeneration != wsGeneration) return
                _connectionState.value = ArcoConnectionState.CONNECTED
                _lastError.value = null
                reconnectAttempt = 0
                startHeartbeat(myGeneration)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (myGeneration != wsGeneration) return
                handleIncomingWs(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (myGeneration != wsGeneration) return
                _connectionState.value = ArcoConnectionState.DISCONNECTED
                scheduleReconnect(myGeneration)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (myGeneration != wsGeneration) return
                Log.w(TAG, "WebSocket failure", t)
                _lastError.value = t.message ?: "WebSocket connection failed"
                _connectionState.value = ArcoConnectionState.ERROR
                scheduleReconnect(myGeneration)
            }
        })
    }

    private fun handleIncomingWs(text: String) {
        val msg = runCatching { json.decodeFromString<ArcoWsMessage>(text) }.getOrNull() ?: return
        val payload = msg.payload
        try {
            when (msg.type) {
                "welcome" -> payload?.let {
                    val p = json.decodeFromJsonElement<ArcoWelcomePayload>(it)
                    _activeEffect.value = p.current_effect
                    if (p.server_version.isNotBlank()) secureStore.serverVersion = p.server_version
                }
                "status_change" -> payload?.let {
                    _activeEffect.value = json.decodeFromJsonElement<ArcoStatusChangePayload>(it).current_effect
                }
                "audio_spectrum" -> payload?.let {
                    _audioSpectrum.tryEmit(json.decodeFromJsonElement<ArcoAudioSpectrum>(it))
                }
                "notification_flash" -> payload?.let {
                    _notificationEvents.tryEmit(json.decodeFromJsonElement<ArcoNotificationEvent>(it))
                }
                "track_changed" -> payload?.let {
                    _trackMetadata.value = json.decodeFromJsonElement<ArcoTrackMetadata>(it)
                }
                "pong", "ack" -> { /* no-op: heartbeat/ack acknowledgement */ }
                "error" -> Log.w(TAG, "Server WS error: $payload")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to decode WS payload for type=${msg.type}", t)
        }
    }

    fun setEffect(name: String, args: List<String> = emptyList()) {
        val payload = buildJsonObject {
            put("name", name)
            putJsonArray("args") { args.forEach { add(it) } }
        }
        sendWs("set_effect", payload)
    }

    fun setColor(hexColor: String) = sendWs("set_color", buildJsonObject { put("color", hexColor) })

    fun blackout() = sendWs("blackout", null)

    fun sendNotification(color: String, style: String = "triple_flash", zones: List<String>? = null, durationMs: Int = 1800) {
        val payload = buildJsonObject {
            put("color", color)
            put("style", style)
            putJsonArray("zones") { (zones ?: listOf("all")).forEach { add(it) } }
            put("duration_ms", durationMs)
        }
        sendWs("notify", payload)
    }

    fun pushTrackMetadataWs(meta: ArcoTrackMetadata) = sendWs("set_track_metadata", json.encodeToJsonElement(meta))

    fun pushAlbumPaletteWs(palette: List<String>, applyMode: String = "visualizer") {
        val payload = buildJsonObject {
            putJsonArray("palette") { palette.forEach { add(it) } }
            put("apply_mode", applyMode)
        }
        sendWs("set_album_palette", payload)
    }

    private fun sendWs(type: String, payload: JsonElement?) {
        val ws = webSocket ?: return
        val msg = ArcoWsMessage(type = type, id = "req_${UUID.randomUUID()}", payload = payload)
        ws.send(json.encodeToString(msg))
    }

    private fun startHeartbeat(generation: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (generation == wsGeneration && _connectionState.value == ArcoConnectionState.CONNECTED) {
                delay(ArcoConfig.WS_HEARTBEAT_INTERVAL_MS)
                if (generation != wsGeneration) break
                sendWs("ping", null)
            }
        }
    }

    /** Exponential backoff with jitter, per WEBSOCKET_PROTOCOL.md §5. */
    private fun scheduleReconnect(generation: Int) {
        if (!secureStore.isPaired) return
        val attempt = reconnectAttempt++
        val base = (ArcoConfig.WS_RECONNECT_INITIAL_DELAY_MS * ArcoConfig.WS_RECONNECT_MULTIPLIER.pow(attempt))
            .toLong()
            .coerceAtMost(ArcoConfig.WS_RECONNECT_MAX_DELAY_MS)
        val jitterRange = (base * ArcoConfig.WS_RECONNECT_JITTER).toLong()
        val delayMs = (base + Random.nextLong(-jitterRange, jitterRange + 1)).coerceAtLeast(200L)
        scope.launch {
            delay(delayMs)
            if (generation == wsGeneration && secureStore.isPaired && _connectionState.value != ArcoConnectionState.CONNECTED) {
                connectWebSocket()
            }
        }
    }

    private fun closeSocketOnly() {
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
    }

    /** User-initiated disconnect; does not clear pairing (use [unpair] for that). */
    fun disconnect() {
        wsGeneration++ // invalidate any in-flight listener callbacks/reconnect loops
        closeSocketOnly()
        _connectionState.value = if (isPaired) ArcoConnectionState.DISCONNECTED else ArcoConnectionState.UNPAIRED
    }

    // -----------------------------------------------------------------------
    // Music visualizer bridge toggle (wiring lives in ArcoMusicVisualizerBridge)
    // -----------------------------------------------------------------------

    fun setMusicVisualizerBridgeEnabled(enabled: Boolean) {
        _musicVisualizerBridgeEnabled.value = enabled
        if (::secureStore.isInitialized) secureStore.musicVisualizerBridgeEnabled = enabled
    }

    // -----------------------------------------------------------------------
    // Low-level HTTP + auth (Bearer or optional HMAC-SHA512 direct-key path)
    // -----------------------------------------------------------------------

    private suspend fun httpRequest(method: String, path: String, jsonBody: String? = null): Result<String> {
        val base = if (::secureStore.isInitialized) secureStore.serverUrl else null
        if (base.isNullOrBlank()) return Result.failure(IllegalStateException("No arcobocconotto server set. Pair or set an endpoint first."))
        return httpRequestAbsolute(method, base.trimEnd('/') + path, jsonBody, authenticate = true, signedPath = path)
    }

    private suspend fun httpRequestAbsolute(
        method: String,
        absoluteUrl: String,
        jsonBody: String? = null,
        authenticate: Boolean,
        signedPath: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val bodyBytes = jsonBody?.toByteArray(Charsets.UTF_8)
            val builder = Request.Builder().url(absoluteUrl)
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> if (bodyBytes != null) builder.delete(bodyBytes.toRequestBody(mediaType)) else builder.delete()
                else -> builder.method(method, (bodyBytes ?: ByteArray(0)).toRequestBody(mediaType))
            }
            if (authenticate && ::secureStore.isInitialized) {
                applyAuthHeaders(builder, method, signedPath ?: absoluteUrl, bodyBytes ?: ByteArray(0))
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val serverMsg = runCatching { json.decodeFromString<ArcoApiError>(text).error }.getOrNull()
                    Result.failure(IOException(serverMsg ?: "HTTP ${resp.code}"))
                } else {
                    Result.success(text)
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Bearer token (paired mobile auth) takes priority; falls back to the
     * pre-shared HMAC-SHA512 direct-auth path (API_REFERENCE.md "Direct /
     * Scripting Auth") when configured and no token is paired yet. */
    private fun applyAuthHeaders(builder: Request.Builder, method: String, path: String, bodyBytes: ByteArray) {
        val token = secureStore.authToken
        if (!token.isNullOrBlank()) {
            builder.addHeader("Authorization", "Bearer $token")
            return
        }
        val keyId = secureStore.hmacKeyId
        val secret = secureStore.hmacSecret
        if (!keyId.isNullOrBlank() && !secret.isNullOrBlank()) {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val bodyHash = sha512Hex(bodyBytes)
            val message = "$ts\n$method\n$path\n$bodyHash"
            val signature = hmacSha512Hex(secret, message)
            builder.addHeader("X-Api-Key-Id", keyId)
            builder.addHeader("X-Timestamp", ts)
            builder.addHeader("X-Signature", signature)
        }
    }

    private fun sha512Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hmacSha512Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA512"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private val ZONE_ENDPOINT_CANDIDATES = listOf("/api/zones", "/api/devices/hardware")
}
