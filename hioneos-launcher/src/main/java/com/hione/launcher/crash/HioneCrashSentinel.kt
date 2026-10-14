package com.hione.launcher.crash

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/**
 * OS-Wide Uncaught Exception Sentinel for HioneOS.
 * Intercepts unhandled crashes, logs telemetry, and immediately surfaces HioneCrashActivity.
 */
object HioneCrashSentinel {
    private const val TAG = "HioneCrashSentinel"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "FATAL CRASH DETECTED ON THREAD ${thread.name}", throwable)
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stacktrace = sw.toString()

                val intent = Intent(context, HioneCrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("crash_details", stacktrace)
                    putExtra("error_type", throwable.javaClass.simpleName)
                }
                context.startActivity(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to launch crash activity", e)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
