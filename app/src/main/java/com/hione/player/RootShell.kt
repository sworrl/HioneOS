package com.hione.player

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Legacy root shell shim.
 *
 * HioneOS is platform-signed and deliberately root-free: there is no `su` binary on the device, so
 * every spawn used to throw `IOException: error=2, No such file or directory`. The old
 * implementation retried on a 120 s backoff forever, which meant a permanent fork/exception flood
 * in logcat plus a real battery cost from the timer-driven managers that polled through here.
 *
 * The standing project directive is: never use su; anything that needs privilege goes through a
 * platform API (Settings puts on WRITE_SECURE_SETTINGS, AudioManager.setParameters for the HiBy
 * audio HAL, InputManager.disableInputDevice, PackageManager, AppOps, PowerManager...). Nothing in
 * this app calls into this object for real work any more; it survives only so an out-of-tree or
 * future caller still compiles and gets an honest "unavailable" instead of a silent no-op loop.
 *
 * Behaviour: ONE probe per process. The moment `su` is found to be missing (or refuses uid=0), the
 * absence is latched in [suAbsent] permanently and logged exactly once. Every entry point is then a
 * lock-free, fork-free no-op returning a clearly-unavailable result ([isAvailable]/[isAllowed] =
 * false, [exec] = false, [execOut] = null, [execFast] = nothing). [recheck] does NOT clear the
 * latch - su does not appear mid-process on this ROM, and un-latching is what produced the retry
 * flood in the first place.
 */
object RootShell {
    private const val TAG = "RootShell"
    private const val DELIMITER = "__HIONE_SHELL_EOF__"

    /** Reason reported when no root shell exists (the normal state on HioneOS). */
    const val REASON_NO_SU = "no su binary on this build (HioneOS is platform-signed and root-free)"

    private var suProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val lock = ReentrantLock()
    @Volatile private var isSessionActive = false

    /** Latched once su is proven absent/denied. Never cleared for the life of the process. */
    @Volatile private var suAbsentLatch = false
    @Volatile private var lastReason: String? = null
    private val loggedOnce = AtomicBoolean(false)

    /** True once this process has proven there is no usable root shell. Cheap, never re-probes. */
    val suAbsent: Boolean get() = suAbsentLatch

    /** Human-readable reason the shell is unavailable, or null while it is (still) usable. */
    val unavailableReason: String? get() = if (suAbsentLatch) (lastReason ?: REASON_NO_SU) else null

    private fun latchAbsent(reason: String, t: Throwable? = null) {
        lastReason = reason
        suAbsentLatch = true
        isSessionActive = false
        if (loggedOnce.compareAndSet(false, true)) {
            val detail = if (t != null) " [" + t.javaClass.simpleName + ": " + t.message + "]" else ""
            Log.i(TAG, "root shell unavailable for this process: $reason$detail - every RootShell call is a no-op from here (this message is logged once)")
        }
    }

    /** True if the user opted into root features AND a root shell is actually operational. */
    fun isAllowed(context: Context): Boolean {
        if (suAbsentLatch) return false
        if (!PlayerPreferences.loadRootEnabled(context)) return false
        return isAvailable()
    }

    /**
     * Whether a root shell is usable. Returns false immediately and forever once [suAbsent]
     * latched - no lock, no fork, no log.
     */
    fun isAvailable(): Boolean {
        if (suAbsentLatch) return false
        lock.withLock {
            if (isSessionActive && suProcess?.isAlive == true) return true
            return initSessionInternal()
        }
    }

    /**
     * Re-probe. Kept for API compatibility with the old "grant root" toggle; it deliberately does
     * NOT clear the [suAbsent] latch, so a device without su can never restart the retry flood.
     */
    fun recheck(): Boolean {
        if (suAbsentLatch) return false
        lock.withLock {
            closeInternal()
            return initSessionInternal()
        }
    }

    /** Execute synchronously. Returns false (not "unknown") when no root shell exists. */
    fun exec(cmd: String): Boolean {
        if (suAbsentLatch) return false
        return execOut(cmd) != null
    }

    /** Fire-and-forget through the persistent pipe. No-op (no fork, no exception) without su. */
    fun execFast(cmd: String) {
        if (suAbsentLatch) return
        lock.withLock {
            if (!isSessionActive || suProcess?.isAlive != true) {
                if (!initSessionInternal()) return
            }
            try {
                val w = writer ?: return
                w.write(cmd)
                w.newLine()
                w.flush()
            } catch (_: Throwable) {
                closeInternal()
            }
        }
    }

    /** Execute and capture stdout. Returns null when no root shell exists or the command failed. */
    fun execOut(cmd: String): String? {
        if (suAbsentLatch) return null
        lock.withLock {
            if (!isSessionActive || suProcess?.isAlive != true) {
                if (!initSessionInternal()) return null
            }

            return try {
                val w = writer ?: return null
                val r = reader ?: return null

                w.write(cmd)
                w.newLine()
                w.write("echo \"$DELIMITER $?\"")
                w.newLine()
                w.flush()

                val sb = java.lang.StringBuilder()
                var line: String?
                var exitCode = -1

                while (r.readLine().also { line = it } != null) {
                    val currentLine = line ?: break
                    if (currentLine.startsWith(DELIMITER)) {
                        val parts = currentLine.split(" ")
                        if (parts.size >= 2) {
                            exitCode = parts[1].trim().toIntOrNull() ?: -1
                        }
                        break
                    }
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(currentLine)
                }

                if (exitCode == 0) sb.toString() else null
            } catch (e: Throwable) {
                Log.w(TAG, "command failed: $cmd", e)
                closeInternal()
                null
            }
        }
    }

    /** Locate a su binary, or null when the ROM ships none (the HioneOS case). */
    private fun findSuBinary(): String? {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/data/adb/magisk/magisk",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
        )
        for (p in paths) {
            try {
                val f = java.io.File(p)
                if (f.exists() && f.canExecute()) return p
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun initSessionInternal(): Boolean {
        if (suAbsentLatch) return false
        val su = findSuBinary()
        if (su == null) {
            latchAbsent(REASON_NO_SU)
            return false
        }
        return try {
            closeInternal()
            val proc = ProcessBuilder(su).redirectErrorStream(true).start()
            val w = BufferedWriter(OutputStreamWriter(proc.outputStream))
            val r = BufferedReader(InputStreamReader(proc.inputStream))

            w.write("id")
            w.newLine()
            w.write("echo \"$DELIMITER $?\"")
            w.newLine()
            w.flush()

            var line: String?
            var gotRoot = false

            while (r.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                if (currentLine.contains("uid=0")) gotRoot = true
                if (currentLine.startsWith(DELIMITER)) break
            }

            if (gotRoot && proc.isAlive) {
                suProcess = proc
                writer = w
                reader = r
                isSessionActive = true
                Log.i(TAG, "persistent root shell initialized (uid=0) via $su")
                true
            } else {
                proc.destroyForcibly()
                latchAbsent("su at $su did not return uid=0 (root denied)")
                false
            }
        } catch (e: Throwable) {
            closeInternal()
            latchAbsent("su at $su could not be started", e)
            false
        }
    }

    fun close() {
        lock.withLock { closeInternal() }
    }

    private fun closeInternal() {
        isSessionActive = false
        try { writer?.write("exit\n"); writer?.flush() } catch (_: Throwable) {}
        try { writer?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { suProcess?.destroyForcibly() } catch (_: Throwable) {}
        writer = null
        reader = null
        suProcess = null
    }
}
