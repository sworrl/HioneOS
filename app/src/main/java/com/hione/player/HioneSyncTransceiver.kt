package com.hione.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.*
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Enhanced Hione Multi-Transport Media Ingress & Rsync Sync Monitor.
 * Connects directly to m500d daemon on port 8787 for live ingestion telemetry.
 */
object HioneSyncTransceiver {
    private const val TAG = "HioneSyncTransceiver"

    /** Mirror of Settings.Global hione_ingest_enabled, kept current by [HioneIngestGate]. */
    @Volatile var ingestEnabledFlag: Boolean = false

    const val RSYNC_PORT = 8730
    const val M500D_PORT = 8787
    const val M500D_BEACON_PORT = 8788

    const val PATH_SD_MUSIC = "/sdcard/MUSIC"
    const val PATH_SD_MOVIES = "/sdcard/Movies"
    const val PATH_SD_STAGING = "/sdcard/.m500staging"

    fun getSdMusicPath(context: Context): String {
        val dirs = context.getExternalFilesDirs(null)
        for (d in dirs) {
            if (d != null && Environment.isExternalStorageRemovable(d)) {
                val root = d.absolutePath.substringBefore("/Android")
                val musicDir = File(root, "MUSIC")
                if (musicDir.exists() || musicDir.mkdirs()) return musicDir.absolutePath
                val musicDir2 = File(root, "Music")
                if (musicDir2.exists() || musicDir2.mkdirs()) return musicDir2.absolutePath
                return root
            }
        }
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
    }

    /** Transport as reported by ConnectivityManager. Badges carry NO speed claims — the only
     *  throughput figures shown anywhere are measured (transferRateMBs / SpeedTestResult). */
    enum class TransportType(val label: String, val badge: String) {
        USB_HIGH_SPEED("USB / Ethernet Link", "USB / Ethernet"),
        WIFI_DIRECT("Wi-Fi Link", "Wi-Fi"),
        DAEMON_ONLY("Daemon reachable (transport not reported)", "Daemon link"),
        DISCONNECTED("No connection", "Offline")
    }

    data class SpeedTestResult(
        val speedMBs: Float = 0f,
        val latencyMs: Long = 0L,
        val transportTier: String = "Unknown",
        val timestamp: Long = 0L
    )

    data class WorkerInfo(
        val id: Int,
        val album: String,
        val status: String
    )

    data class DaemonStatus(
        val online: Boolean = false,
        val host: String = "127.0.0.1",
        val pingLatencyMs: Long = 0L,
        val serverAudioCount: Int = 0,
        val serverAudioBytes: Long = 0L,
        val cardAudioCount: Int = 0,
        val cardAudioBytes: Long = 0L,
        val stagingAudioCount: Int = 0,
        val stagingAudioBytes: Long = 0L,
        val isTransferring: Boolean = false,
        val currentArtist: String = "",
        val currentAlbum: String = "",
        val currentFile: String = "",
        val lastEvent: String = "",
        val stage: String = "",
        val albumsDone: Int = 0,
        val albumsTotal: Int = 0,
        val filesDone: Int = 0,
        val filesTotal: Int = 0,
        val bytesTotalPlan: Long = 0L,
        val transferRateBps: Double = 0.0,
        val cacheBytes: Long = 0L,
        val cacheAlbums: Int = 0,
        val fetchActive: Int = 0,
        val pushActive: Int = 0,
        val fetchRateBps: Double = 0.0,
        val pushRateBps: Double = 0.0,
        val serverLoad: String = "",
        val serverReachable: Boolean = false,
        val failures: List<String> = emptyList(),
        val workers: List<WorkerInfo> = emptyList(),
        val sdCardFreeBytes: Long = 0L,
        val sdCardTotalBytes: Long = 0L,
        val internalFreeBytes: Long = 0L,
        val internalTotalBytes: Long = 0L,
        val sdCardPath: String = ""
    ) {
        val souffleLoad: String get() = serverLoad
    }

    data class SyncState(
        val transport: TransportType = TransportType.DISCONNECTED,
        val ipAddress: String = "127.0.0.1",
        val isRsyncActive: Boolean = false,
        val isTransferring: Boolean = false,
        val transferRateMBs: Float = 0f,
        val activeModule: String = "music",
        val lastSyncTimestamp: Long = 0L,
        val lastSyncedTracksCount: Int = 0,
        val daemon: DaemonStatus = DaemonStatus(),
        val speedTest: SpeedTestResult? = null,
        val activeHost: String = "127.0.0.1",
        val eventLogs: List<String> = emptyList(),
        val throughputHistory: List<Float> = emptyList(),
        /** Last beacon acknowledgement text; empty until one is actually received. */
        val beaconAck: String = ""
    )

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    private val logBuffer = mutableListOf<String>()
    private val throughputBuffer = mutableListOf<Float>().apply { repeat(30) { add(0f) } }

    fun log(msg: String) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())
        val entry = "[$timestamp] $msg"
        synchronized(logBuffer) {
            logBuffer.add(0, entry)
            while (logBuffer.size > 200) logBuffer.removeAt(logBuffer.size - 1)
            _state.value = _state.value.copy(eventLogs = logBuffer.toList())
        }
    }

    private val scope = CoroutineScope(HioneBrain.NetworkDispatcher + SupervisorJob())
    private var monitorJob: Job? = null
    private val wakeSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    // Thread-safe candidate list dynamically populated with loopback and local subnet gateway
    private val candidateHosts = CopyOnWriteArraySet(listOf("127.0.0.1"))
    @Volatile var activeHost = "127.0.0.1"

    fun wake() {
        wakeSignal.trySend(Unit)
    }

    /** Real used/free/total for the two physical volumes, resolved at runtime (no hardcoded sizes/UUIDs). */
    data class VolumeStats(
        val sdFree: Long = 0L,
        val sdTotal: Long = 0L,
        val sdPath: String = "",
        val internalFree: Long = 0L,
        val internalTotal: Long = 0L
    )

    /**
     * Enumerates the device's real storage volumes and reads live capacity via StatFs.
     * Internal = the /data system+app volume. SD = the first removable/non-primary volume
     * (resolved through getExternalFilesDirs + StorageManager so the per-card UUID never has to be
     * hardcoded). The music library / ingestion target is the SD volume (sdPath).
     */
    fun readVolumeStats(ctx: Context): VolumeStats {
        // Internal volume (system / apps) — the /data mount.
        var internalFree = 0L
        var internalTotal = 0L
        try {
            val s = StatFs(Environment.getDataDirectory().absolutePath)
            internalFree = s.availableBytes
            internalTotal = s.totalBytes
        } catch (_: Throwable) {}

        // Removable SD volume — resolve the mount root (UUID varies per card).
        var sdPath = ""
        try {
            for (d in ctx.getExternalFilesDirs(null)) {
                if (d != null && Environment.isExternalStorageRemovable(d)) {
                    sdPath = d.absolutePath.substringBefore("/Android")
                    break
                }
            }
        } catch (_: Throwable) {}
        if (sdPath.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Fallback: first removable, non-primary StorageVolume.
            try {
                val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
                val vol = sm?.storageVolumes?.firstOrNull { it.isRemovable && !it.isPrimary }
                    ?: sm?.storageVolumes?.firstOrNull { !it.isPrimary }
                vol?.directory?.absolutePath?.let { sdPath = it }
            } catch (_: Throwable) {}
        }

        var sdFree = 0L
        var sdTotal = 0L
        if (sdPath.isNotEmpty()) {
            try {
                val s = StatFs(sdPath)
                sdFree = s.availableBytes
                sdTotal = s.totalBytes
            } catch (_: Throwable) {}
        }
        return VolumeStats(sdFree, sdTotal, sdPath, internalFree, internalTotal)
    }

    private var networkCallbackRegistered = false

    /** Halts the network side of the ingress engine (host discovery + daemon polling). The local
     *  library mirror in LibraryDaemonService is untouched — this is what "ingest engine OFF"
     *  means: nothing talks to the network, local SD scans keep working. */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _state.value = _state.value.copy(
            transport = TransportType.DISCONNECTED,
            daemon = _state.value.daemon.copy(online = false, isTransferring = false),
            isTransferring = false
        )
        log("Network sync off, stopped watching the network (SD card only)")
    }

    fun startMonitoring(ctx: Context) {
        if (monitorJob != null) return
        if (!HioneIngestGate.isEnabled(ctx)) { log("Network sync is off, not watching the network"); return }
        
        if (!networkCallbackRegistered) {
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    val request = android.net.NetworkRequest.Builder().build()
                    cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: android.net.Network) { wake() }
                        override fun onLost(network: android.net.Network) { wake() }
                        override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: NetworkCapabilities) { wake() }
                    })
                    networkCallbackRegistered = true
                }
            } catch (_: Throwable) {}
        }

        monitorJob = scope.launch {
            var lastDiscoveryTime = 0L
            var lastStatFsTime = 0L
            var cachedVols = VolumeStats()

            while (isActive) {
                var nextDelayMs = 2500L
                if (!HionePowerGovernor.allowBackgroundWork) { delay(60_000L); continue }   // starved profile: no discovery, 1-min heartbeat
                try {
                    val now = SystemClock.elapsedRealtime()

                    // Refresh StatFs only once every 30s to eliminate flash wear / CPU IO
                    if (now - lastStatFsTime > 30000L || cachedVols.sdTotal == 0L) {
                        lastStatFsTime = now
                        cachedVols = readVolumeStats(ctx)
                    }

                    // Query active host directly (loopback / local IP)
                    var daemon = queryDaemonStatus(activeHost, cachedVols)

                    // If active host is offline, probe candidate hosts
                    if (!daemon.online) {
                        if (now - lastDiscoveryTime > 15000L) {
                            lastDiscoveryTime = now
                            discoverDaemonHosts(ctx)
                        }
                        val probeDeferreds = candidateHosts.map { cand ->
                            async(Dispatchers.IO) {
                                cand to queryDaemonStatus(cand, cachedVols)
                            }
                        }
                        val probeResults = probeDeferreds.awaitAll()
                        val livePair = probeResults.firstOrNull { it.second.online }
                        if (livePair != null) {
                            activeHost = livePair.first
                            daemon = livePair.second
                        }
                    }

                    val rateMBs = (daemon.transferRateBps / (1024.0 * 1024.0)).toFloat()
                    val isTransfer = daemon.isTransferring || rateMBs > 0.01f || (daemon.stage.isNotEmpty() && daemon.stage != "idle")

                    synchronized(throughputBuffer) {
                        if (throughputBuffer.isNotEmpty()) throughputBuffer.removeAt(0)
                        throughputBuffer.add(rateMBs)
                    }

                    if (daemon.online && (_state.value.daemon.stage != daemon.stage || _state.value.daemon.currentAlbum != daemon.currentAlbum)) {
                        if (daemon.currentArtist.isNotEmpty() || daemon.currentAlbum.isNotEmpty()) {
                            log("STAGE: ${daemon.stage.uppercase()} · CACHING: ${daemon.currentArtist} / ${daemon.currentAlbum}")
                        }
                    }

                    // The daemon being reachable says nothing about the physical link — always detect it.
                    val transport = detectTransport(ctx)
                    val ip = if (daemon.online) activeHost else getDeviceIpAddress(ctx)

                    _state.value = _state.value.copy(
                        transport = transport,
                        ipAddress = ip,
                        isRsyncActive = daemon.online,
                        isTransferring = isTransfer,
                        transferRateMBs = if (isTransfer) rateMBs else 0f,
                        activeModule = if (isTransfer) "music" else "idle",
                        lastSyncTimestamp = if (isTransfer) System.currentTimeMillis() else _state.value.lastSyncTimestamp,
                        daemon = daemon,
                        activeHost = activeHost,
                        throughputHistory = synchronized(throughputBuffer) { throughputBuffer.toList() },
                        eventLogs = synchronized(logBuffer) { logBuffer.toList() }
                    )

                    // Adaptive polling rate: fast when active, slow when idle, deepest sleep when complete
                    val isLibraryFullAndComplete = daemon.online && !isTransfer && daemon.filesTotal > 0 && daemon.filesDone >= daemon.filesTotal
                    
                    nextDelayMs = when {
                        isTransfer -> 750L
                        isLibraryFullAndComplete -> 30000L // Deep sleep if fully caught up
                        daemon.online -> 3000L
                        else -> 12000L // Offline slow poll
                    }

                } catch (e: Throwable) {
                    Log.w(TAG, "Sync monitoring tick: ${e.message}")
                    nextDelayMs = 4000L
                }
                
                try {
                    withTimeoutOrNull(nextDelayMs) {
                        wakeSignal.receive()
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Sends a UDP discovery probe to discover m500d across local network interfaces.
     */
    private fun discoverDaemonHosts(ctx: Context) {
        try {
            val socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 400
            }
            val probeData = "{\"ping\":\"m500_hione\"}".toByteArray()

            // PII-free probe set: universal broadcast/loopback + the device's own directed
            // broadcast addresses (runtime auto-discovery) + any user-configured host/LAN hints.
            // No personal IP is ever baked into source — see HioneIngestConfig.discoveryTargets.
            val targets = mutableListOf<InetAddress>()
            HioneIngestConfig.discoveryTargets(ctx).forEach { hostStr ->
                try { targets.add(InetAddress.getByName(hostStr)) } catch (_: Throwable) {}
            }

            for (target in targets) {
                try {
                    val packet = DatagramPacket(probeData, probeData.size, target, M500D_BEACON_PORT)
                    socket.send(packet)
                } catch (_: Throwable) {}
            }

            val recvBuf = ByteArray(1024)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            try {
                socket.receive(recvPacket)
                val respStr = String(recvPacket.data, 0, recvPacket.length)
                val json = JSONObject(respStr)
                if (json.optString("daemon") == "m500d" || respStr.contains("HIONE_CYBERDECK_BROADCAST_BEACON")) {
                    val fromIp = recvPacket.address.hostAddress ?: ""
                    if (fromIp.isNotEmpty() && fromIp != "0.0.0.0") {
                        candidateHosts.add(fromIp)
                        log("BEACON ACK: $fromIp:$M500D_BEACON_PORT")
                    }
                    val ipsArr = json.optJSONArray("ips")
                    if (ipsArr != null) {
                        for (i in 0 until ipsArr.length()) {
                            val candidate = ipsArr.optString(i)
                            if (candidate.isNotEmpty()) candidateHosts.add(candidate)
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
            } finally {
                socket.close()
            }
        } catch (_: Throwable) {}
    }

    fun runSpeedTest(onProgress: (SpeedTestResult) -> Unit) {
        scope.launch {
            try {
                val host = activeHost
                val start = SystemClock.elapsedRealtime()
                val url = URL("http://$host:$M500D_PORT/api/speedtest?size_kb=2048")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 8000
                }
                var bytesRead = 0L
                val buf = ByteArray(16384)
                conn.inputStream.use { input ->
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        bytesRead += r
                    }
                }
                val durationMs = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
                val speedMBs = (bytesRead.toFloat() / (1024f * 1024f)) / (durationMs.toFloat() / 1000f)

                // Label = the REAL detected transport; the speed is the measurement itself. No
                // inferring "USB" / "5GHz" / "2.4GHz" from a throughput number.
                val tier = "${_state.value.transport.badge} · measured"
                val result = SpeedTestResult(speedMBs, durationMs, tier, System.currentTimeMillis())
                _state.value = _state.value.copy(speedTest = result)
                withContext(Dispatchers.Main) { onProgress(result) }
            } catch (t: Throwable) {
                val failed = SpeedTestResult(0f, 0L, "Failed: ${t.message}", System.currentTimeMillis())
                _state.value = _state.value.copy(speedTest = failed)
                withContext(Dispatchers.Main) { onProgress(failed) }
            }
        }
    }

    private fun queryDaemonStatus(host: String, vol: VolumeStats): DaemonStatus {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val url = URL("http://$host:$M500D_PORT/api/status")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 3000
                requestMethod = "GET"
            }
            val latency = SystemClock.elapsedRealtime() - t0
            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val json = JSONObject(reader.readText())
                reader.close()

                val sourcesObj = json.optJSONObject("sources")
                val cardObj = sourcesObj?.optJSONObject("card")?.optJSONObject("value") ?: json.optJSONObject("card")
                val libObj = sourcesObj?.optJSONObject("library")?.optJSONObject("value") ?: json.optJSONObject("library")
                val xferObj = sourcesObj?.optJSONObject("transfer")?.optJSONObject("value") ?: json.optJSONObject("transfer")
                val liveObj = sourcesObj?.optJSONObject("live")?.optJSONObject("value") ?: json.optJSONObject("live")
                val pipeObj = sourcesObj?.optJSONObject("pipeline")?.optJSONObject("value") ?: json.optJSONObject("pipeline")
                val incObj = sourcesObj?.optJSONObject("incoming")?.optJSONObject("value") ?: json.optJSONObject("incoming")
                val rate = json.optDouble("rate_bytes_sec", liveObj?.optDouble("push_rate_bps", 0.0) ?: 0.0)

                val fails = mutableListOf<String>()
                val failsArr = xferObj?.optJSONArray("failures")
                if (failsArr != null) {
                    for (i in 0 until failsArr.length()) {
                        fails.add(failsArr.optString(i))
                    }
                }

                val workerList = mutableListOf<WorkerInfo>()
                val workersArr = xferObj?.optJSONArray("workers")
                if (workersArr != null) {
                    for (i in 0 until workersArr.length()) {
                        val w = workersArr.optJSONObject(i)
                        if (w != null) {
                            workerList.add(
                                WorkerInfo(
                                    id = w.optInt("id", i + 1),
                                    album = w.optString("album", ""),
                                    status = w.optString("status", "idle")
                                )
                            )
                        }
                    }
                }

                DaemonStatus(
                    online = true,
                    host = host,
                    pingLatencyMs = latency,
                    serverAudioCount = libObj?.optInt("audio_count", 0) ?: 0,
                    serverAudioBytes = libObj?.optLong("audio_bytes", 0L) ?: 0L,
                    cardAudioCount = cardObj?.optInt("audio_count", 0) ?: 0,
                    cardAudioBytes = cardObj?.optLong("audio_bytes", 0L) ?: 0L,
                    stagingAudioCount = cardObj?.optInt("staging_count", 0) ?: 0,
                    stagingAudioBytes = cardObj?.optLong("staging_bytes", 0L) ?: 0L,
                    isTransferring = xferObj?.optBoolean("running", false) ?: false,
                    currentArtist = xferObj?.optString("current_artist", "") ?: "",
                    currentAlbum = xferObj?.optString("current_album", "") ?: "",
                    currentFile = xferObj?.optString("current_file", "") ?: "",
                    lastEvent = xferObj?.optString("last_event", "") ?: "",
                    stage = xferObj?.optString("stage", "") ?: "",
                    albumsDone = xferObj?.optInt("albums_done", 0) ?: 0,
                    albumsTotal = xferObj?.optInt("album_total", 0) ?: 0,
                    filesDone = xferObj?.optInt("files_done", 0) ?: 0,
                    filesTotal = xferObj?.optInt("files_total", 0) ?: 0,
                    bytesTotalPlan = xferObj?.optLong("bytes_total_plan", 0L) ?: 0L,
                    transferRateBps = rate,
                    cacheBytes = liveObj?.optLong("cache_bytes", 0L) ?: 0L,
                    cacheAlbums = liveObj?.optInt("cache_albums", 0) ?: 0,
                    fetchActive = liveObj?.optInt("fetch_active", 0) ?: 0,
                    pushActive = liveObj?.optInt("push_active", 0) ?: 0,
                    fetchRateBps = liveObj?.optDouble("fetch_rate_bps", 0.0) ?: 0.0,
                    pushRateBps = liveObj?.optDouble("push_rate_bps", 0.0) ?: 0.0,
                    serverLoad = pipeObj?.optString("server_load", pipeObj?.optString("souffle_load", "")) ?: "",
                    serverReachable = incObj?.optBoolean("reachable", true) ?: true,
                    failures = fails,
                    workers = workerList,
                    sdCardFreeBytes = vol.sdFree,
                    sdCardTotalBytes = vol.sdTotal,
                    internalFreeBytes = vol.internalFree,
                    internalTotalBytes = vol.internalTotal,
                    sdCardPath = vol.sdPath
                )
            } else {
                DaemonStatus(online = false, host = host, pingLatencyMs = latency, sdCardFreeBytes = vol.sdFree, sdCardTotalBytes = vol.sdTotal, internalFreeBytes = vol.internalFree, internalTotalBytes = vol.internalTotal, sdCardPath = vol.sdPath)
            }
        } catch (_: Throwable) {
            DaemonStatus(online = false, host = host, pingLatencyMs = 0L, sdCardFreeBytes = vol.sdFree, sdCardTotalBytes = vol.sdTotal, internalFreeBytes = vol.internalFree, internalTotalBytes = vol.internalTotal, sdCardPath = vol.sdPath)
        }
    }

    fun triggerDaemonSync(start: Boolean, onResult: (Boolean, String) -> Unit) {
        if (!ingestEnabledFlag) { onResult(false, "Network sync is off (SD card only)"); return }
        scope.launch {
            try {
                val host = activeHost
                val url = URL("http://$host:$M500D_PORT/api/sync")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val payload = JSONObject().put("action", if (start) "start" else "stop").toString()
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val code = conn.responseCode
                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        onResult(true, if (start) "Sync started on the host daemon" else "Sync stopped")
                    } else {
                        onResult(false, "Daemon returned HTTP $code")
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection error: ${t.localizedMessage}")
                }
            }
        }
    }

    fun scanAndIntegrateDirectory(ctx: Context, path: String, onDone: (Int) -> Unit) {
        HioneBrain.launchScanner {
            val root = File(path)
            if (!root.exists() || !root.isDirectory) {
                withContext(Dispatchers.Main) { onDone(0) }
                return@launchScanner
            }

            val mediaFiles = mutableListOf<String>()
            val supportedExts = setOf("flac", "dsf", "dff", "mp3", "wav", "m4a", "ogg", "opus", "ape", "wv")

            root.walkTopDown().maxDepth(6).forEach { file ->
                if (file.isFile && file.extension.lowercase() in supportedExts) {
                    mediaFiles.add(file.absolutePath)
                }
                HioneBrain.cooperativeYield()
            }

            if (mediaFiles.isNotEmpty()) {
                val pathsArray = mediaFiles.toTypedArray()
                android.media.MediaScannerConnection.scanFile(ctx, pathsArray, null) { _, _ -> }
            }

            withContext(Dispatchers.Main) {
                onDone(mediaFiles.size)
            }
        }
    }

    /** Force deep rescan: rescans entire SD library comprehensively (no depth limit, all subdirs) */
    fun deepScanDirectory(ctx: Context, path: String, onDone: (Int) -> Unit) {
        HioneBrain.launchScanner {

            val root = File(path)
            if (!root.exists() || !root.isDirectory) {
                withContext(Dispatchers.Main) { onDone(0) }
                return@launchScanner
            }

            val mediaFiles = mutableListOf<String>()
            val supportedExts = setOf("flac", "dsf", "dff", "mp3", "wav", "m4a", "ogg", "opus", "ape", "wv")

            // Deep scan: recursive, no depth limit
            root.walkTopDown().forEach { file ->
                if (file.isFile && file.extension.lowercase() in supportedExts) {
                    mediaFiles.add(file.absolutePath)
                }
                HioneBrain.cooperativeYield()
            }

            if (mediaFiles.isNotEmpty()) {
                val pathsArray = mediaFiles.toTypedArray()
                android.media.MediaScannerConnection.scanFile(ctx, pathsArray, null) { _, _ -> }
                android.util.Log.i("HioneSyncTransceiver", "Deep scan: ${mediaFiles.size} files queued")
            }

            withContext(Dispatchers.Main) {
                onDone(mediaFiles.size)
            }
        }
    }

    private fun detectTransport(ctx: Context): TransportType {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return TransportType.DISCONNECTED
        val net = cm.activeNetwork ?: return TransportType.DISCONNECTED
        val caps = cm.getNetworkCapabilities(net)

        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true -> TransportType.USB_HIGH_SPEED
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> TransportType.WIFI_DIRECT
            _state.value.daemon.online -> TransportType.DAEMON_ONLY
            else -> TransportType.DISCONNECTED
        }
    }

    private fun getDeviceIpAddress(ctx: Context): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Throwable) {}
        return "127.0.0.1"
    }
}
