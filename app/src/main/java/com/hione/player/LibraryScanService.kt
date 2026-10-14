package com.hione.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Optimized foreground Library Scanner for 10k+ tracks:
 * 1. Rapid directory walk to identify all new/unindexed media.
 * 2. Sorts by lastModified descending so newest files and albums are ingested first.
 * 3. Batched concurrent MediaScanner ingestion with immediate checkpointing and live UI streaming.
 * 4. Defers old media housekeeping until all new tracks are fully processed.
 */
class LibraryScanService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var underPressure = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        underPressure = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ScanProgress.active) return START_NOT_STICKY
        startForeground(NOTIF_ID, buildNotification("Scanning for new audio files…"))
        val wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hione:libraryscan")
        wl.acquire(30 * 60_000L)
        wakeLock = wl
        runScan()
        return START_NOT_STICKY
    }

    private fun runScan() {
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        val roots = LinkedHashSet<String>()
        android.os.Environment.getExternalStorageDirectory()?.absolutePath?.let { roots.add(it) }
        externalCacheDirs.forEach { d -> d?.absolutePath?.substringBefore("/Android")?.let { roots.add(it) } }
        File("/storage").listFiles()?.forEach { if (it.isDirectory && it.name != "self" && it.name != "emulated") roots.add(it.absolutePath) }
        // No /mnt/media_rw roots. That is the same card again under its raw mount, and MediaStore
        // keys rows by the /storage path, so every file there misses the `known` check below and
        // goes to MediaScanner (or the jaudiotagger fallback insert) a second time under a path
        // nothing else uses.
        ScanProgress.reset()

        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
            val before = LibraryCounts.countTracks(app)

            // 1. Load known tracks from MediaStore and crash checkpoint.
            // Compared lowercased. The card is exFAT and its music folder is MUSIC on disk, while
            // every MediaStore row on the device spells it /Music/. With all-files access the walk
            // below reads the card and sees MUSIC, so a case-sensitive check calls all ~18k card
            // files "new" on every scan and sends each one back through MediaScanner.
            val known = object : HashSet<String>() {
                override fun add(element: String) = super.add(element.lowercase())
                override fun contains(element: String) = super.contains(element.lowercase())
            }
            try {
                app.contentResolver.safeQuery(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media.DATA), null, null, null
                )?.use { c ->
                    val col = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    while (c.moveToNext()) c.getString(col)?.let { known.add(it) }
                }
            } catch (_: Throwable) {}
            known.addAll(ScanCheckpoint.load(app))
            val checkpoint = ScanCheckpoint.Writer(app)

            val audioExts = setOf("flac", "mp3", "wav", "m4a", "aac", "ogg", "opus", "dsf", "dff", "alac", "aif", "aiff", "wma", "ape", "cue", "iso")
            val videoExts = setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v", "3gp")
            val mediaExts = audioExts + videoExts

            val visited = ScanProgress.visited
            val newFound = ScanProgress.newFound
            val done = AtomicBoolean(false)
            val lastRefreshAt = AtomicLong(0L)
            val lastNotifyAt = AtomicLong(0L)

            fun maybeRefreshUi() {
                val now = System.currentTimeMillis()
                if (now - lastRefreshAt.get() > 1500L) {
                    lastRefreshAt.set(now)
                    ScanProgress.bump()
                    checkpoint.flush()
                }
                if (now - lastNotifyAt.get() > 3000L) {
                    lastNotifyAt.set(now)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, buildNotification("${ScanProgress.tagsScanned.get()}/${ScanProgress.tagsTotal} tracks indexed"))
                }
            }

            fun reportFinish(completed: Boolean) {
                if (!done.compareAndSet(false, true)) return
                try {
                    ScanProgress.phase = "Finalizing library…"
                    val after = runCatching { LibraryCounts.countTracks(app) }.getOrDefault(before)
                    val delta = after - before
                    val msg = if (delta > 0) "Found $delta new track${if (delta == 1) "" else "s"} · Total $after"
                              else "Library up to date · $after tracks"
                    val albums = runCatching { LibraryCounts.countDistinct(app, MediaStore.Audio.Media.ALBUM) }.getOrDefault(0)
                    val artists = runCatching { LibraryCounts.countDistinct(app, MediaStore.Audio.Media.ARTIST) }.getOrDefault(0)
                    val formats = runCatching {
                        val extMap = mutableMapOf<String, Int>()
                        app.contentResolver.safeQuery(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Audio.Media.DATA), "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, null
                        )?.use { c ->
                            val col = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                            while (c.moveToNext()) {
                                val p = c.getString(col) ?: continue
                                val ext = p.substringAfterLast('.', "").uppercase()
                                if (ext.isNotBlank()) extMap[ext] = (extMap[ext] ?: 0) + 1
                            }
                        }
                        extMap.entries.sortedByDescending { it.value }.take(4).joinToString(" · ") { "${it.key} (${it.value})" }
                    }.getOrDefault("")
                    ScanProgress.finish(delta, after, albums, artists, formats)
                    main.post { android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show() }
                } finally {
                    checkpoint.flush(); checkpoint.close()
                    if (completed) ScanCheckpoint.clear(app)
                    ScanProgress.bump()
                    wakeLock?.let { if (it.isHeld) it.release() }
                    wakeLock = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ScanProgress.phase = "Looking for audio and video files…"
            val newFiles = mutableListOf<DiscoveredItem>()
            val jobDirs = mutableListOf<File>()
            try {
                roots.forEach { r ->
                    val rootFile = File(r)
                    rootFile.listFiles()?.forEach { entry ->
                        if (entry.isDirectory) { if (!entry.name.startsWith(".")) jobDirs.add(entry) }
                        else if (entry.isFile) {
                            val ext = entry.extension.lowercase()
                            if (ext in mediaExts) {
                                visited.incrementAndGet()
                                if (entry.absolutePath !in known) {
                                    newFiles.add(DiscoveredItem(entry.absolutePath, entry.lastModified()))
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}

            // Walk folders in parallel with background thread priority
            val cores = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 3)
            val walkPool = Executors.newFixedThreadPool(cores) { r ->
                Thread(r, "HioneScanWalker").apply {
                    priority = Thread.MIN_PRIORITY
                }
            }
            val discoveredNew = java.util.Collections.synchronizedList(newFiles)
            try {
                jobDirs.map { dir ->
                    walkPool.submit {
                        try {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
                            // Hidden folders are skipped: .m500staging holds albums m500d has not
                            // committed yet, and indexing them leaves a row behind for a path that
                            // is deleted seconds later, next to the row for the committed copy.
                            for (f in dir.walkTopDown().maxDepth(12).onEnter { it == dir || !it.name.startsWith(".") }) {
                                if (f.isFile) {
                                    val ext = f.extension.lowercase()
                                    // ONLY count and process genuine Audio & Video files (ignore all images/artwork)
                                    if (ext in mediaExts) {
                                        visited.incrementAndGet()
                                        val path = f.absolutePath
                                        if (path !in known) {
                                            discoveredNew.add(DiscoveredItem(path, f.lastModified()))
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }.forEach { try { it.get() } catch (_: Throwable) {} }
            } finally { walkPool.shutdown() }

            if (discoveredNew.isEmpty()) {
                reportFinish(completed = true)
                return@Thread
            }

            // Sort by pre-cached lastModified descending
            discoveredNew.sortByDescending { it.lastMod }
            val totalNew = discoveredNew.size
            newFound.set(totalNew)
            ScanProgress.tagsTotal = totalNew
            ScanProgress.phase = "Indexing $totalNew new files…"

            // Crash-proof SEQUENTIAL batching (one batch at a time) with background priority and
            // adaptive pacing — a 14k-track first-boot scan was saturating ~3 cores and making the
            // whole device feel laggy while in use. Now: single worker, and each batch yields harder
            // while the user is actively interacting (screen on), faster when idle/asleep.
            val scanGate = Semaphore(1)
            val batches = discoveredNew.map { it.path }.chunked(48)
            val batchPool = Executors.newFixedThreadPool(1) { r ->
                Thread(r, "HioneScanBatcher").apply {
                    priority = Thread.MIN_PRIORITY
                }
            }
            val powerMgr = app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager

            try {
                batches.forEach { batch ->
                    scanGate.acquire()
                    batchPool.submit {
                        try {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE)
                            android.media.MediaScannerConnection.scanFile(
                                app,
                                batch.toTypedArray(),
                                null
                            ) { path, uri ->
                                if (path != null) {
                                    val ext = path.substringAfterLast('.', "").lowercase()
                                    val isExotic = ext in listOf("dsf", "dff", "ape", "wv", "mpc", "tta")
                                    // Fallback for exotic formats that MediaStore misses
                                    if (uri == null || isExotic) {
                                        JaudiotaggerScanner.fallbackParseAndInsert(app, path)
                                    }
                                    checkpoint.record(path)
                                    val count = ScanProgress.tagsScanned.incrementAndGet()
                                    ScanProgress.updateProgress(path.substringAfterLast('/'), count, totalNew)
                                    maybeRefreshUi()
                                }
                            }
                            // Adaptive yield: back off hard while the user is actively using the
                            // device so the first-boot scan never makes the UI feel laggy; run
                            // faster when the screen is off. Also always yields to audio threads.
                            Thread.sleep(if (powerMgr?.isInteractive == true) 55L else 10L)
                        } catch (_: Throwable) {
                            val count = ScanProgress.tagsScanned.addAndGet(batch.size)
                            ScanProgress.updateProgress("", count, totalNew)
                            maybeRefreshUi()
                        } finally {
                            scanGate.release()
                        }
                    }
                }
            } finally {
                batchPool.shutdown()
            }

            // Wait for in-flight batches to finish
            while (ScanProgress.tagsScanned.get() < totalNew && !done.get()) {
                HioneBrain.heartbeat(
                    HioneBrain.BoneType.LIBRARY_SCANNER,
                    HioneBrain.BoneState.ACTIVE,
                    "Indexed ${ScanProgress.tagsScanned.get()}/$totalNew files",
                    totalNew - ScanProgress.tagsScanned.get()
                )
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }

            HioneBrain.heartbeat(HioneBrain.BoneType.LIBRARY_SCANNER, HioneBrain.BoneState.IDLE, "Scan finished")
            reportFinish(completed = true)
            main.postDelayed({ reportFinish(completed = false) }, 30 * 60_000L)
        }.start()
    }

    private fun buildNotification(status: String): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Library Scan", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while ${Mascot.musicAppName} scans your library"
                }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("${Mascot.musicAppName}: scanning library")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "hione_library_scan"
        private const val NOTIF_ID = 7200
    }
}

data class DiscoveredItem(val path: String, val lastMod: Long)

object ScanCheckpoint {
    private const val FILE_NAME = "scan_checkpoint.txt"

    fun load(ctx: Context): Set<String> {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return emptySet()
        return try { f.readLines().toHashSet() } catch (_: Throwable) { emptySet() }
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE_NAME).delete() }
    }

    class Writer(ctx: Context) {
        private val file = File(ctx.filesDir, FILE_NAME)
        private val writer = try { java.io.BufferedWriter(java.io.FileWriter(file, true)) } catch (_: Throwable) { null }

        @Synchronized fun record(path: String) {
            try { writer?.write(path); writer?.newLine() } catch (_: Throwable) {}
        }
        @Synchronized fun flush() { try { writer?.flush() } catch (_: Throwable) {} }
        @Synchronized fun close() { try { writer?.close() } catch (_: Throwable) {} }
    }
}

object LibraryCounts {
    fun countTracks(ctx: Context): Int {
        var n = 0
        ctx.contentResolver.safeQuery(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, null
        )?.use { n = it.count }
        return n
    }

    fun countDistinct(ctx: Context, column: String): Int {
        val set = HashSet<String>()
        ctx.contentResolver.safeQuery(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(column),
            "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, null
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(column)
            while (c.moveToNext()) c.getString(col)?.takeIf { it.isNotBlank() }?.let { set.add(it) }
        }
        return set.size
    }
}
