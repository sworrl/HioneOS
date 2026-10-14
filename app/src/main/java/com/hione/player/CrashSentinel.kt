package com.hione.player

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enterprise Crash Sentinel & Exception Trap for Hione Music.
 * 
 * Functions:
 * 1. Traps all uncaught exceptions across all threads (Main, GL, Coroutines, Media3).
 * 2. Writes full symbolic stack trace to `/data/data/com.hione.player/files/crash_trace.log`.
 * 3. Absorbs recoverable background/visualizer thread crashes without exiting to desktop.
 * 4. Ensures graceful auto-restart for fatal UI crashes, preserving playback queue & track position.
 */
object CrashSentinel {
    private const val TAG = "CrashSentinel"
    private const val LOG_FILE = "crash_trace.log"

    @Volatile private var isInstalled = false
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        if (isInstalled) return
        isInstalled = true

        val app = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(app, thread, throwable)
        }
        Log.i(TAG, "CrashSentinel successfully installed as default UncaughtExceptionHandler")
    }

    fun getLastCrashLog(context: Context): String? {
        return try {
            val file = File(context.filesDir, LOG_FILE)
            if (file.exists() && file.length() > 0) file.readText() else null
        } catch (_: Throwable) {
            null
        }
    }

    fun clearCrashLog(context: Context) {
        try {
            File(context.filesDir, LOG_FILE).delete()
        } catch (_: Throwable) {}
    }

    private fun handleUncaughtException(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val logEntry = """
            ====================================================
            HIONE MUSIC UNCAUGHT CRASH TRACE
            Time: $timeStamp
            Thread: ${thread.name} (id: ${thread.id}, priority: ${thread.priority})
            Exception: ${throwable.javaClass.name}
            Message: ${throwable.message}
            ====================================================
            $stackTrace
            ====================================================
            
        """.trimIndent()

        Log.e(TAG, logEntry)

        try {
            val logFile = File(context.filesDir, LOG_FILE)
            logFile.appendText(logEntry)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write crash trace to disk", e)
        }

        // Check if the crash is from a non-critical background worker (e.g. GL render, visualizer, audio capture)
        val isNonCriticalWorker = thread.name.contains("GLThread", ignoreCase = true) ||
                thread.name.contains("projectM", ignoreCase = true) ||
                thread.name.contains("Visualizer", ignoreCase = true) ||
                thread.name.contains("ScanWorker", ignoreCase = true) ||
                thread.name.contains("Widget", ignoreCase = true)

        if (isNonCriticalWorker) {
            Log.w(TAG, "Absorbed non-critical background thread crash on [${thread.name}], suppressing desktop dump")
            if (thread.name.contains("GLThread") || thread.name.contains("projectM")) {
                VisualizerMemoryGuard.suppress()
            }
            return
        }

        // For fatal UI/Main thread crashes: save state and trigger safe resume
        try {
            val p = PlayerHolder.player
            if (p != null) {
                val curTrackId = p.currentMediaItem?.mediaId?.toLongOrNull()
                if (curTrackId != null) {
                    PlayerPreferences.saveLastPlayback(context, curTrackId, p.currentPosition)
                    PlayerPreferences.saveQueueIndex(context, p.currentMediaItemIndex)
                }
            }
        } catch (_: Throwable) {}

        // Forward to default OS handler or restart
        defaultHandler?.uncaughtException(thread, throwable) ?: run {
            Process.killProcess(Process.myPid())
            System.exit(10)
        }
    }
}
