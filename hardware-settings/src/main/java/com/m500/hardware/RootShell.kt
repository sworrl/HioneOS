package com.m500.hardware

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * High-performance, single-session interactive root shell for M500 hardware settings.
 */
object RootShell {
    private const val TAG = "RootShell"
    private const val DELIMITER = "__M500_SHELL_EOF__"

    private var suProcess: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val lock = ReentrantLock()
    @Volatile private var isSessionActive = false

    /**
     * ROOT IS OPTIONAL. HioneOS never requires su for anything: every feature has a platform-signed,
     * root-free path, and that path is what ships. Root is a power-user ENHANCEMENT — where it is
     * present it can unlock extra hardware (the SELinux-locked LED nodes, for one) and this class
     * is how that gets used.
     *
     * What this flag fixes is the COST of asking when the answer is no. execFast forked `su -c`
     * on every call, and PulsarLight's animation loops call it every 35 ms, so on an unrooted unit
     * the always-alive hardware daemon was forking a process and printing a stack trace about
     * thirty times a second, forever — the log flood in logcat, and real CPU taken from the
     * ambient-light sampler that shares this process.
     *
     * So: probe once, remember the answer, and stop paying for it. The answer is NOT permanent —
     * [recheck] clears it, which is what a user who has just installed Magisk or granted the su
     * prompt needs. Call recheck from a user action or on app resume; never from a hot loop.
     */
    @Volatile private var suAbsent = false
    private val loggedAbsence = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun latchAbsent(reason: String) {
        suAbsent = true
        if (loggedAbsence.compareAndSet(false, true)) {
            Log.i(TAG, "no root available ($reason) - optional root-backed extras are off. This is the " +
                "normal, supported state: ${Mascot.osName} is platform-signed and every feature has a " +
                "root-free path. Call RootShell.recheck() if the user grants root later.")
        }
    }

    fun isAvailable(): Boolean {
        if (suAbsent) return false
        lock.withLock {
            if (suAbsent) return false
            if (isSessionActive && suProcess?.isAlive == true) return true
            val ok = initSessionInternal()
            if (!ok) latchAbsent("session init failed")
            return ok
        }
    }

    /**
     * Re-probe for su, clearing any previous negative answer first.
     *
     * This is the path for a user who roots the device (or grants the su prompt) after the process
     * has already concluded there was no root. It is deliberately the ONLY thing that un-latches,
     * and it is never called from an animation or polling loop — only from an explicit user action
     * or an app-resume, so a genuinely unrooted device still pays for exactly one probe.
     */
    fun recheck(): Boolean {
        lock.withLock {
            suAbsent = false
            loggedAbsence.set(false)
            closeInternal()
            val ok = initSessionInternal()
            if (!ok) latchAbsent("recheck found no su")
            return ok
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
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        } catch (_: Throwable) {
            latchAbsent("su binary not executable")
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
                Log.i(TAG, "Persistent root shell successfully initialized (uid=0)")
                true
            } else {
                proc.destroyForcibly()
                isSessionActive = false
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to start su process", e)
            closeInternal()
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
