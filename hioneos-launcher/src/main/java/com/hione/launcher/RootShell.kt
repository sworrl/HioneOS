package com.hione.launcher

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object RootShell {
    /**
     * ROOT IS OPTIONAL. HioneOS never requires su: every feature has a platform-signed, root-free
     * path, and that path is what ships. Root is a power-user ENHANCEMENT — where it exists it can
     * unlock extra hardware, and this class is how that gets used.
     *
     * This flag only stops us PAYING to ask when the answer is no. execFast forked `su -c` per
     * call, and the LED animation loops call it every 35 ms, so on an unrooted unit the process
     * forked and logged a stack trace about thirty times a second, forever. Probe once, remember,
     * stop paying. The answer is not permanent — [recheck] clears it for a user who grants root
     * later; never call recheck from a hot loop.
     */
    @Volatile private var suAbsent = false
    private val loggedAbsence = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun latchAbsent(reason: String) {
        suAbsent = true
        if (loggedAbsence.compareAndSet(false, true)) {
            Log.i(TAG, "no root available ($reason) - optional root-backed extras are off. " +
                "This is the normal supported state on ${Mascot.osName}; every feature has a root-free path.")
        }
    }

    private const val TAG = "HioneOS_RootShell"
    private const val DELIMITER = "__HIONE_SETTINGS_EOF__"

    private var suProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val lock = ReentrantLock()
    @Volatile private var isSessionActive = false

    // On an UNROOTED boot every su spawn throws — and the lockscreen/volume/BPM paths call
    // through here on timers, which turned into a continuous fork/IOException flood (battery +
    // logcat) AND starved unrelated callers queued on [lock]. After a failed spawn, fail fast
    // (no lock, no fork) for this window before probing su again.
    private const val SU_BACKOFF_MS = 120_000L
    @Volatile private var suMissingUntil = 0L

    private fun suOnCooldown(): Boolean = System.currentTimeMillis() < suMissingUntil
    private fun markSuMissing() { suMissingUntil = System.currentTimeMillis() + SU_BACKOFF_MS }

    fun isAvailable(): Boolean {
        if (suAbsent) return false
        if (suOnCooldown()) return false
        lock.withLock {
            if (isSessionActive && suProcess?.isAlive == true) return true
            return initSessionInternal()
        }
    }

    fun exec(cmd: String): Boolean {
        if (suAbsent) return false
        val out = execOut(cmd)
        return out != null
    }

    fun execFast(cmd: String) {
        // The fork-per-call that made this the flood source. One latched check, then nothing.
        if (suAbsent) return
        if (suOnCooldown()) return
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        } catch (_: Throwable) {
            lock.withLock {
                if (!isSessionActive || suProcess?.isAlive != true) {
                    if (!initSessionInternal()) return
                }
                try {
                    val w = writer ?: return
                    w.write(cmd)
                    w.newLine()
                    w.flush()
                } catch (e: Throwable) {
                    Log.w(TAG, "execFast failed, restarting shell", e)
                    closeInternal()
                }
            }
        }
    }

    fun execOut(cmd: String): String? {
        if (suAbsent) return null
        if (suOnCooldown()) return null
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
                Log.e(TAG, "Command execution failed: $cmd", e)
                closeInternal()
                null
            }
        }
    }

    private fun findSuBinary(): String {
        val paths = listOf(
            "su",
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
                if (p == "su") return "su"
                val f = java.io.File(p)
                if (f.exists() && f.canExecute()) return p
            } catch (_: Throwable) {}
        }
        return "su"
    }

    private fun initSessionInternal(): Boolean {
        return try {
            closeInternal()
            val proc = ProcessBuilder(findSuBinary()).redirectErrorStream(true).start()
            val w = BufferedWriter(OutputStreamWriter(proc.outputStream))
            val r = BufferedReader(InputStreamReader(proc.inputStream))

            w.write("id")
            w.newLine()
            w.write("echo \"$DELIMITER $?\"")
            w.newLine()
            w.flush()

            val sb = java.lang.StringBuilder()
            var line: String?
            var gotRoot = false

            while (r.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                if (currentLine.contains("uid=0")) {
                    gotRoot = true
                }
                if (currentLine.startsWith(DELIMITER)) {
                    break
                }
                sb.append(currentLine)
            }

            if (gotRoot && proc.isAlive) {
                suProcess = proc
                writer = w
                reader = r
                isSessionActive = true
                suMissingUntil = 0L
                Log.i(TAG, "Persistent root shell successfully initialized (uid=0)")
                true
            } else {
                proc.destroyForcibly()
                isSessionActive = false
                markSuMissing()
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to start su process — backing off ${SU_BACKOFF_MS / 1000}s", e)
            closeInternal()
            markSuMissing()
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
