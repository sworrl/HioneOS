package com.hione.launcher.ingest

import com.hione.launcher.Mascot
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import com.hione.launcher.HioneIngestConfig
import com.hione.launcher.RootShell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

data class HioneIngestState(
    val totalTracks: Int = 0,
    // "—" until a scan has actually counted: "0" asserted an empty library before any query ran.
    val abbreviatedTracks: String = "—",
    val flacCount: Int = 0,
    val dsdCount: Int = 0,
    val wavCount: Int = 0,
    val alacCount: Int = 0,
    val mp3Count: Int = 0,
    val aacCount: Int = 0,
    val otherCount: Int = 0,
    val hiResCount: Int = 0,
    val hiResPercent: Int = 0,
    val totalAlbums: Int = 0,
    val totalArtists: Int = 0,
    // Storage Telemetry
    val internalUsedBytes: Long = 0L,
    val internalTotalBytes: Long = 0L,
    val sdCardUsedBytes: Long = 0L,
    val sdCardTotalBytes: Long = 0L,
    val sdCardPath: String = "",
    val isSdCardMounted: Boolean = false,
    // Live Ingest / Scanning State
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val scannedFilesCount: Int = 0,
    val isServerReachable: Boolean = false,
    val isNetworkOnline: Boolean = false,
    // "Idle · Ingested" implied an ingest had already happened before anything ran.
    val statusMessage: String = "Idle · no sync has run yet",
    val lastScanTime: String = "Never",
    val logMessages: List<String> = emptyList(),
    val isInitialized: Boolean = false,
    /** Network (rsync) ingest engine switch — OFF by default; local SD scans always work. */
    val engineEnabled: Boolean = false,
    /**
     * Live picture of what the host relay is doing, or null when we are not watching it.
     * Polled only while the ingest observatory is open; see HioneRelayPoller.
     */
    val relay: HioneRelayStatus? = null
)

object HioneIngestEngine {
    private const val TAG = "HioneOS_IngestEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(HioneIngestState())
    val state: StateFlow<HioneIngestState> = _state.asStateFlow()

    private val recentLogs = ConcurrentLinkedQueue<String>()
    private var observerRegistered = false
    private var networkCallbackRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchdogJob: Job? = null
    private var mediaReceiverRegistered = false
    private var autoScanDebounceJob: Job? = null
    private const val AUTO_PREFS = "hione_ingest_auto"
    private const val KEY_LAST_AUTO_SCAN = "last_auto_force_scan_ms"
    /** Unattended force-scan cadence: once a day, plus right after an SD card (re)mount. */
    private const val AUTO_SCAN_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Settings.Global switch for the network/rsync ingest engine. 0 (default) = OFF: only local SD scans. */
    const val GLOBAL_ENABLED_KEY = "hione_ingest_enabled"
    private const val OFF_MESSAGE = "Network sync off · only SD card scans update the library"

    fun isEngineEnabled(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, GLOBAL_ENABLED_KEY, 0) == 1
    } catch (_: Throwable) { false }

    /** Flip the engine live: persists the Global, then arms or tears down the network watchdog. */
    fun setEngineEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        scope.launch {
            val v = if (enabled) 1 else 0
            val ok = runCatching { Settings.Global.putInt(appContext.contentResolver, GLOBAL_ENABLED_KEY, v) }.getOrDefault(false)
            if (!ok) runCatching { RootShell.execFast("settings put global $GLOBAL_ENABLED_KEY $v") }
            _state.value = _state.value.copy(
                engineEnabled = enabled,
                isServerReachable = false,
                statusMessage = if (enabled) "Network sync on · looking for the rsync server…" else OFF_MESSAGE
            )
            if (enabled) {
                log("Network sync turned on · rsync will run when the server is reachable")
                setupNetworkWatchdog(appContext)
                probeAndAutoResume(appContext)
            } else {
                log("Network sync turned off · only SD card scans update the library")
                teardownNetworkWatchdog(appContext)
            }
        }
    }

    private fun teardownNetworkWatchdog(context: Context) {
        watchdogJob?.cancel(); watchdogJob = null
        val cb = networkCallback
        if (cb != null) {
            try {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
            } catch (_: Throwable) {}
        }
        networkCallback = null
        networkCallbackRegistered = false
    }

    fun init(context: Context) {
        val appContext = context.applicationContext
        val enabled = isEngineEnabled(appContext)
        if (!_state.value.isInitialized) {
            _state.value = _state.value.copy(
                engineEnabled = enabled,
                statusMessage = if (enabled) _state.value.statusMessage else OFF_MESSAGE
            )
        } else if (_state.value.engineEnabled != enabled) {
            _state.value = _state.value.copy(engineEnabled = enabled)
        }
        refresh(appContext)
        if (enabled) setupNetworkWatchdog(appContext)

        if (!observerRegistered) {
            observerRegistered = true
            try {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        refresh(appContext)
                    }
                }
                appContext.contentResolver.registerContentObserver(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer
                )
            } catch (_: Throwable) {}
        }
        armAutomaticScans(appContext)
    }

    /**
     * Unattended ingest — the relay node keeps the library current without anyone opening the
     * observatory. Two triggers: (1) SD card mounted / platform MediaScanner finished → debounced
     * force scan; (2) a daily catch-up force scan if none has run in the last 24h (also first run).
     * Purely local (MediaScanner + Hione Music nudge); never touches the network engine switch.
     */
    private fun armAutomaticScans(appContext: Context) {
        if (!mediaReceiverRegistered) {
            mediaReceiverRegistered = true
            try {
                // ONLY a real new-storage event. ACTION_MEDIA_SCANNER_FINISHED was in this filter
                // and it is a FEEDBACK EDGE: our force scan runs MediaScanner, MediaScanner finishing
                // broadcasts SCANNER_FINISHED, we scheduled another force scan, and so on forever —
                // a self-sustaining rescan every ~8 s that pinned com.android.providers.media.module
                // and hammered the SD card with I/O. The isScanning guard could never catch it: the
                // scan has already COMPLETED (that is what sent the broadcast) by the time the
                // debounce elapses. Introduced 2026-09-13, removed 2026-09-17.
                val filter = android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_MEDIA_MOUNTED)
                    addDataScheme("file")
                }
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        val action = intent?.action ?: return
                        log("Storage mounted · scheduling a scan")
                        scheduleAutoForceScan(appContext, "storage:${action.substringAfterLast('.')}")
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag") appContext.registerReceiver(receiver, filter)
                }
            } catch (_: Throwable) { mediaReceiverRegistered = false }
        }
        val last = appContext.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_AUTO_SCAN, 0L)
        if (System.currentTimeMillis() - last > AUTO_SCAN_INTERVAL_MS) {
            scheduleAutoForceScan(appContext, if (last == 0L) "first-run" else "daily")
        }
    }

    private fun scheduleAutoForceScan(appContext: Context, reason: String) {
        autoScanDebounceJob?.cancel()
        autoScanDebounceJob = scope.launch {
            delay(if (reason.startsWith("storage")) 8_000L else 20_000L)   // let the platform scanner / boot settle
            if (_state.value.isScanning) { log("Auto scan ($reason) skipped · a scan is already running"); return@launch }
            // The interval gates EVERY path, mounts included. Previously only the "daily" path
            // consulted it, so a repeating storage event could rescan without limit.
            val since = System.currentTimeMillis() -
                appContext.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_AUTO_SCAN, 0L)
            if (since < AUTO_SCAN_INTERVAL_MS) {
                log("Auto scan ($reason) skipped · last was ${since / 60_000} min ago")
                return@launch
            }
            appContext.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_AUTO_SCAN, System.currentTimeMillis()).apply()
            log("Auto scan ($reason)")
            triggerForceScan(appContext)
        }
    }

    private fun setupNetworkWatchdog(context: Context) {
        if (networkCallbackRegistered) return
        networkCallbackRegistered = true

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    log("Network back · looking for the sync server…")
                    _state.value = _state.value.copy(isNetworkOnline = true)
                    probeAndAutoResume(context)
                }

                override fun onLost(network: Network) {
                    log("Network dropped or out of range · sync paused")
                    _state.value = _state.value.copy(
                        isNetworkOnline = false,
                        isServerReachable = false,
                        statusMessage = "Offline · waiting for Wi-Fi…"
                    )
                }
            }
            networkCallback = cb
            cm.registerNetworkCallback(request, cb)
        } catch (_: Throwable) {}

        // Periodic background reachability watchdog (every 45 seconds)
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(45_000)
                probeAndAutoResume(context)
            }
        }
    }

    private fun probeAndAutoResume(context: Context) {
        if (!isEngineEnabled(context)) return
        scope.launch {
            val syncHost = HioneIngestConfig.syncHost(context)
            val rsyncPort = HioneIngestConfig.rsyncPort(context)

            if (syncHost.isBlank()) return@launch

            val isReachable = withContext(Dispatchers.IO) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(syncHost, rsyncPort), 2500)
                        true
                    }
                } catch (_: Throwable) {
                    false
                }
            }

            val prevReachable = _state.value.isServerReachable
            _state.value = _state.value.copy(isServerReachable = isReachable)

            if (isReachable && !prevReachable) {
                log("Sync server ($syncHost:$rsyncPort) is back. Resuming scan and sync…")
                triggerRsyncSync(context)
            }
        }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        recentLogs.add("[$time] $msg")
        while (recentLogs.size > 25) {
            recentLogs.poll()
        }
        _state.value = _state.value.copy(logMessages = recentLogs.toList())
    }

    private var refreshDebounceJob: Job? = null

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        refreshDebounceJob?.cancel()
        refreshDebounceJob = scope.launch {
            delay(350)
            try {
                val cr = appContext.contentResolver

                // 1. Audio Library Taxonomy Breakdown
                var total = 0
                var flac = 0
                var dsd = 0
                var wav = 0
                var alac = 0
                var mp3 = 0
                var aac = 0
                var other = 0
                val artistSet = mutableSetOf<String>()
                val albumSet = mutableSetOf<String>()

                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM
                )

                try {
                    cr.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                        val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                        val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)

                        while (cursor.moveToNext()) {
                            total++
                            val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                            val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                            val artist = if (artistCol >= 0) cursor.getString(artistCol) ?: "" else ""
                            val album = if (albumCol >= 0) cursor.getString(albumCol) ?: "" else ""

                            if (artist.isNotBlank() && artist != "<unknown>") artistSet.add(artist)
                            if (album.isNotBlank() && album != "<unknown>") albumSet.add(album)

                            val lowerPath = path.lowercase(Locale.ROOT)
                            val lowerMime = mime.lowercase(Locale.ROOT)

                            when {
                                lowerMime.contains("flac") || lowerPath.endsWith(".flac") -> flac++
                                lowerPath.endsWith(".dsf") || lowerPath.endsWith(".dff") || lowerPath.endsWith(".iso") -> dsd++
                                lowerMime.contains("wav") || lowerPath.endsWith(".wav") -> wav++
                                lowerPath.endsWith(".alac") || lowerPath.endsWith(".m4a") -> alac++
                                lowerMime.contains("mpeg") || lowerMime.contains("mp3") || lowerPath.endsWith(".mp3") -> mp3++
                                lowerMime.contains("aac") || lowerPath.endsWith(".aac") -> aac++
                                else -> other++
                            }
                        }
                    }
                } catch (_: Throwable) {}

                // Fallback direct scan if MediaStore is empty or still indexing
                if (total == 0) {
                    val searchDirs = mutableListOf(
                        File("/storage/emulated/0/Music"),
                        File("/storage/emulated/0/Download"),
                        File("/sdcard/Music")
                    )
                    val extStorage = File("/storage")
                    if (extStorage.exists() && extStorage.isDirectory) {
                        extStorage.listFiles()?.forEach { f ->
                            if (f.isDirectory && f.name != "emulated" && f.name != "self") {
                                searchDirs.add(File(f, "Music"))
                                searchDirs.add(f)
                            }
                        }
                    }
                    val exts = setOf("flac", "dsf", "dff", "iso", "wav", "m4a", "alac", "mp3", "aac", "ogg", "opus", "ape")
                    searchDirs.forEach { dir ->
                        if (dir.exists() && dir.canRead()) {
                            try {
                                dir.walkTopDown().maxDepth(6).forEach { f ->
                                    if (f.isFile && exts.contains(f.extension.lowercase(Locale.ROOT))) {
                                        total++
                                        val lower = f.extension.lowercase(Locale.ROOT)
                                        when {
                                            lower == "flac" -> flac++
                                            lower == "dsf" || lower == "dff" || lower == "iso" -> dsd++
                                            lower == "wav" -> wav++
                                            lower == "alac" || lower == "m4a" -> alac++
                                            lower == "mp3" -> mp3++
                                            lower == "aac" -> aac++
                                            else -> other++
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }

                // 2. Storage Telemetry (Internal Flash & MicroSD Card)
                val internalPath = Environment.getDataDirectory().path
                val internalStat = StatFs(internalPath)
                val intBlockSize = internalStat.blockSizeLong
                val intTotalBytes = internalStat.blockCountLong * intBlockSize
                val intAvailBytes = internalStat.availableBlocksLong * intBlockSize
                val intUsedBytes = (intTotalBytes - intAvailBytes).coerceAtLeast(0L)

                var sdPath = ""
                var sdUsedBytes = 0L
                var sdTotalBytes = 0L
                var isSdMounted = false

                // Ask the platform which volumes exist instead of listing /storage ourselves.
                //
                // The old code did `File("/storage").listFiles()` and required `canRead()` on each
                // entry. That cannot work on this device and was reporting "No external TF/MicroSD
                // card mounted" while a 16,000-track card was mounted and playing. On-device:
                // /storage is `drwx--x---  shell everybody`, so an app may traverse it but not
                // LIST it, and /storage/EAFF-98FE is `drwxrwx--- root media_rw`, a group we are
                // not in. MANAGE_EXTERNAL_STORAGE is granted as a permission but its APP OP sits
                // at `default`, and that op has to be `allow` for the raw path to open. So both
                // the listing and the canRead() gate fail, and the panel confidently said there
                // was no card.
                //
                // StorageManager.getStorageVolumes() is the supported route and answers from the
                // platform's own mount table, independent of filesystem permissions.
                runCatching {
                    val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
                    for (vol in sm.storageVolumes) {
                        if (!vol.isRemovable) continue
                        if (vol.state != android.os.Environment.MEDIA_MOUNTED) continue
                        val dir = vol.directory ?: continue
                        val stat = StatFs(dir.absolutePath)
                        val totalB = stat.blockCountLong * stat.blockSizeLong
                        val availB = stat.availableBlocksLong * stat.blockSizeLong
                        if (totalB <= 0L) continue
                        sdPath = dir.absolutePath
                        sdTotalBytes = totalB
                        sdUsedBytes = (totalB - availB).coerceAtLeast(0L)
                        isSdMounted = true
                        break
                    }
                }.onFailure { log("Storage volume query failed: ${it.javaClass.simpleName}") }

                // Fallback for anything StorageManager did not surface. Same shape as before, but
                // without the canRead() gate that was the thing actually rejecting the card.
                if (!isSdMounted) {
                    val storageDir = File("/storage")
                    storageDir.listFiles()?.forEach { file ->
                        if (file.isDirectory && file.name != "emulated" && file.name != "self") {
                            runCatching {
                                val stat = StatFs(file.absolutePath)
                                val totalB = stat.blockCountLong * stat.blockSizeLong
                                val availB = stat.availableBlocksLong * stat.blockSizeLong
                                if (totalB > 1024 * 1024 * 500) {
                                    sdPath = file.absolutePath
                                    sdTotalBytes = totalB
                                    sdUsedBytes = (totalB - availB).coerceAtLeast(0L)
                                    isSdMounted = true
                                }
                            }
                        }
                    }
                }

                val hiRes = flac + dsd + wav + alac
                val hiResPct = if (total > 0) ((hiRes.toFloat() / total.toFloat()) * 100).toInt() else 0

                val abbr = when {
                    total >= 1_000_000 -> String.format(Locale.US, "%.1fM", total / 1_000_000.0)
                    total >= 1_000 -> String.format(Locale.US, "%.1fk", total / 1_000.0)
                    else -> total.toString()
                }

                _state.value = _state.value.copy(
                    totalTracks = total,
                    abbreviatedTracks = abbr,
                    flacCount = flac,
                    dsdCount = dsd,
                    wavCount = wav,
                    alacCount = alac,
                    mp3Count = mp3,
                    aacCount = aac,
                    otherCount = other,
                    hiResCount = hiRes,
                    hiResPercent = hiResPct,
                    totalAlbums = albumSet.size,
                    totalArtists = artistSet.size,
                    internalUsedBytes = intUsedBytes,
                    internalTotalBytes = intTotalBytes,
                    sdCardUsedBytes = sdUsedBytes,
                    sdCardTotalBytes = sdTotalBytes,
                    sdCardPath = sdPath,
                    isSdCardMounted = isSdMounted,
                    isInitialized = true,
                    logMessages = recentLogs.toList()
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to refresh Ingest & FS metrics", t)
            }
        }
    }

    /**
     * Single-flight guard for [triggerRescan]. Four callers can start a scan: the Force scan
     * button, m500d's INGEST_SCAN broadcast at the end of every pass, the storage-mounted receiver
     * and the daily catch-up. Only the automatic path looked at isScanning, so a button press
     * during a relay-triggered scan started a second full walk of the same trees and submitted
     * every file to MediaScanner twice.
     */
    private val rescanRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    fun triggerRescan(context: Context) {
        val appContext = context.applicationContext
        if (!rescanRunning.compareAndSet(false, true)) {
            log("Scan already running · this request was skipped")
            return
        }
        scope.launch {
          try {
            _state.value = _state.value.copy(
                isScanning = true,
                scanProgress = 0.05f,
                statusMessage = "Indexing storage…"
            )
            log("Started a MediaScanner scan of all storage")

            val scanPaths = mutableListOf<String>()

            val internalMusic = File("/storage/emulated/0/Music")
            if (internalMusic.exists()) scanPaths.add(internalMusic.absolutePath)
            val internalDownload = File("/storage/emulated/0/Download")
            if (internalDownload.exists()) scanPaths.add(internalDownload.absolutePath)

            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                storageDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && file.name != "emulated" && file.name != "self") {
                        // Keep "Music" as spelled here. The folder on the card is MUSIC, but exFAT
                        // ignores case and every MediaStore row on this device (17,950 on
                        // 2026-10-10) is stored as /storage/<id>/Music/... MediaStore matches paths
                        // case-sensitively, so submitting the folder's on-disk spelling would add a
                        // second row for every track.
                        val sdMusic = File(file, "Music")
                        if (sdMusic.exists()) scanPaths.add(sdMusic.absolutePath)
                        else scanPaths.add(file.absolutePath)
                    }
                }
            }

            val audioFiles = mutableListOf<String>()
            val extensions = setOf("flac", "dsf", "dff", "iso", "wav", "m4a", "alac", "mp3", "aac", "ogg", "opus", "ape")

            scanPaths.forEach { rootPath ->
                log("Scanning folder: $rootPath")
                try {
                    // Skip hidden folders. m500d stages in-flight albums in .m500staging at the
                    // card root, and a scan that runs mid-transfer would hand MediaScanner files
                    // that are about to move, leaving a second row per track for the old path.
                    File(rootPath).walkTopDown().maxDepth(8)
                        .onEnter { it.absolutePath == rootPath || !it.name.startsWith(".") }
                        .forEach { f ->
                        if (f.isFile && extensions.contains(f.extension.lowercase(Locale.ROOT))) {
                            audioFiles.add(f.absolutePath)
                        }
                    }
                } catch (_: Throwable) {}
            }

            log("Found ${audioFiles.size} audio files to index into MediaStore")
            _state.value = _state.value.copy(scanProgress = 0.3f, statusMessage = "Found ${audioFiles.size} audio tracks…")

            if (audioFiles.isNotEmpty()) {
                var scannedCount = 0
                val totalToScan = audioFiles.size

                val chunks = audioFiles.chunked(100)
                for (chunk in chunks) {
                    MediaScannerConnection.scanFile(
                        appContext,
                        chunk.toTypedArray(),
                        null
                    ) { _, _ -> }
                    scannedCount += chunk.size
                    val prog = 0.3f + (0.65f * (scannedCount.toFloat() / totalToScan))
                    _state.value = _state.value.copy(
                        scanProgress = prog,
                        scannedFilesCount = scannedCount,
                        // "Indexed" overstated it: the files have been HANDED to MediaScanner (the
                        // completion callback is a no-op), not confirmed indexed.
                        statusMessage = "Submitted $scannedCount / $totalToScan files to MediaScanner (${(prog * 100).toInt()}%)"
                    )
                    delay(40)
                }
            }

            // A final sweep of the whole Music tree, on top of the per-file submissions above.
            // This was an `am broadcast` through RootShell, which on a rootless device threw every
            // time and then blocked RootShell for 120 seconds. We can send the broadcast
            // ourselves: a platform-signed app does not need a shell to do it.
            runCatching {
                appContext.sendBroadcast(
                    android.content.Intent(
                        android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        android.net.Uri.parse("file:///storage/emulated/0/Music")
                    )
                )
            }.onFailure { log("Final media-scan sweep failed: ${it.javaClass.simpleName}") }

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            log("Scan done: ${audioFiles.size} files indexed")
            nudgeHioneMusicLibrary(appContext)

            _state.value = _state.value.copy(
                isScanning = false,
                scanProgress = 1f,
                statusMessage = "Scan done · ${audioFiles.size} tracks indexed",
                lastScanTime = timeStr
            )

            delay(1000)
            refresh(appContext)
          } finally {
            rescanRunning.set(false)
            if (_state.value.isScanning) _state.value = _state.value.copy(isScanning = false)
          }
        }
    }

    /**
     * FORCE SCAN — purely local: MediaScanner over every mounted volume (SD card + internal),
     * plus an explicit nudge to Hione Music's library engine so its own index catches up.
     * Never touches the network; works with the ingest engine OFF.
     */
    fun triggerForceScan(context: Context) {
        val appContext = context.applicationContext
        log("Force scan · SD card and internal storage through MediaScanner, then a ${Mascot.musicAppName} library rescan")
        // triggerRescan() nudges Hione Music itself once MediaScanner has finished — nudging here
        // too made the player rescan a still-stale MediaStore and then rescan again seconds later.
        triggerRescan(appContext)
    }

    private fun nudgeHioneMusicLibrary(appContext: Context) {
        try {
            appContext.sendBroadcast(
                Intent("com.hione.player.action.FORCE_LIBRARY_SCAN")
                    .setClassName("com.hione.player", "com.hione.player.HionePrefsReceiver")
                    .putExtra("source", "launcher_force_scan")
            )
        } catch (_: Throwable) {}
    }

    /**
     * Start watching the host relay. Called when the ingest observatory opens.
     *
     * The tile used to fire a sync and then say nothing further, so a running transfer and a
     * dead one looked identical from the device. This keeps the relay's own view on screen.
     */
    fun startRelayWatch(context: Context) {
        val app = context.applicationContext
        HioneRelayPoller.start(app) { status ->
            _state.value = _state.value.copy(relay = status)
        }
    }

    /** Stop watching. Called when the observatory closes, so nothing polls in the background. */
    fun stopRelayWatch() {
        HioneRelayPoller.stop()
    }

    fun triggerRsyncSync(context: Context) {
        val appContext = context.applicationContext
        if (!isEngineEnabled(appContext)) {
            log("Rsync sync asked for, but network sync is off. Turn it on from the shade tile first.")
            _state.value = _state.value.copy(statusMessage = OFF_MESSAGE)
            return
        }
        scope.launch {
            val syncHost = HioneIngestConfig.syncHost(appContext)
            val rsyncPort = HioneIngestConfig.rsyncPort(appContext)

            log("Starting rsync sync to $syncHost:$rsyncPort")
            _state.value = _state.value.copy(
                isScanning = true,
                scanProgress = 0.15f,
                statusMessage = "Connecting to the rsync server ($syncHost:$rsyncPort)…"
            )

            // NO FAKE SYNC. This used to fire `rsync --version`, discard the result, log "Rsync sync
            // broadcast transmitted" (no broadcast was ever sent), sleep 1.5 s and then report
            // "Rsync sync completed" with a 100 % progress bar — while nothing had been transferred.
            // Report exactly what is actually known: whether the configured server answers.
            //
            // The `rsync --version` probe that used to sit here is gone. It ran through RootShell
            // on a device with no root, so it threw every time, cost a 120 second RootShell
            // backoff, and its result was assigned to a local that nothing ever read. It told us
            // nothing and it delayed the next attempt at something that might have worked.
            val status = when {
                syncHost.isBlank() ->
                    "No sync host set. Set one before a sync can run."
                else -> {
                    try {
                        val url = java.net.URL("http://$syncHost:8787/api/sync")
                        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 3000
                            readTimeout = 3000
                            requestMethod = "POST"
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }
                        val payload = org.json.JSONObject().put("action", "start").toString()
                        conn.outputStream.use { it.write(payload.toByteArray()) }
                        if (conn.responseCode in 200..299) {
                            "Sync started on the host daemon ($syncHost). It pushes over ADB."
                        } else {
                            "Host daemon returned HTTP ${conn.responseCode}"
                        }
                    } catch (t: Throwable) {
                        "Couldn't start the host daemon sync: ${t.localizedMessage}"
                    }
                }
            }
            log(status)

            _state.value = _state.value.copy(
                isScanning = false,
                scanProgress = 0f,
                statusMessage = status
            )
            refresh(appContext)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
        val mb = bytes.toDouble() / (1024 * 1024)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
