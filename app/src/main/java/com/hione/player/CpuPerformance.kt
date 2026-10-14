package com.hione.player

import android.util.Log
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Optional CPU governor override: pin every core's cpufreq governor to "performance" while the
 * player is in the foreground, instead of relying only on `Window.setSustainedPerformanceMode`
 * (an official Android *hint* the OS may or may not honor, and which keeps working regardless of
 * this feature).
 *
 * ROOT-FREE AND HONEST. This used to run every read and write through a `su` shell, which does not
 * exist on HioneOS: `isSupported()` reported whatever the shell said, the toggle appeared to work,
 * and not one governor ever changed. Now everything goes through plain java.io.File on the cpufreq
 * nodes, and [isSupported] is a REAL probe - the node must exist and be writable by this process.
 * On a stock kernel the scaling_governor nodes are root-owned 0644, so this normally reports
 * unsupported and the UI must say so rather than offering a switch that does nothing.
 *
 * The stash/restore discipline is unchanged: record the real per-core governor before touching
 * anything, restore it faithfully rather than guessing a default, and restore defensively from
 * several places so a crash mid-session cannot strand the device pinned at max clock.
 */
object CpuPerformance {
    private const val TAG = "CpuPerformance"
    private const val PERF_GOVERNOR = "performance"
    private const val CPU_ROOT = "/sys/devices/system/cpu"

    /** True only when the cpufreq governor nodes exist AND this process can actually write them. */
    suspend fun isSupported(): Boolean = withContext(Dispatchers.IO) { isWritable() }

    private fun isWritable(): Boolean = corePaths().any { runCatching { File(it).canWrite() }.getOrDefault(false) }

    /** Every cpuN/cpufreq/scaling_governor node present on the device (readable or not). */
    private val CPU_DIR_RE = Regex("cpu[0-9]+")

    private fun corePaths(): List<String> = runCatching {
        val dirs = File(CPU_ROOT).listFiles { f: File -> f.isDirectory && CPU_DIR_RE.matches(f.name) }
            ?: return@runCatching emptyList<String>()
        dirs.mapNotNull { d ->
            val node = File(d, "cpufreq/scaling_governor")
            if (node.exists()) node.absolutePath else null
        }
    }.getOrDefault(emptyList())

    private fun readNode(path: String): String? = runCatching {
        val f = File(path)
        if (f.exists() && f.canRead()) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }.getOrNull()

    private fun writeNode(path: String, value: String): Boolean = runCatching {
        val f = File(path)
        if (!f.exists() || !f.canWrite()) return@runCatching false
        f.writeText(value)
        true
    }.getOrDefault(false)

    /**
     * Turn the pin on/off. The preference is only persisted when the device can genuinely honor
     * it - otherwise the toggle would come back "on" forever while nothing happened.
     */
    suspend fun setEnabled(ctx: Context, on: Boolean) = withContext(Dispatchers.IO) {
        if (!isWritable()) {
            Log.w(TAG, "setEnabled($on) ignored: cpufreq scaling_governor is not writable by this process on this kernel")
            PlayerPreferences.saveCpuPerfEnabled(ctx, false)
            return@withContext
        }
        PlayerPreferences.saveCpuPerfEnabled(ctx, on)
        if (on) applyPerformance(ctx) else restoreStock(ctx)
    }

    /** Call defensively (Activity onResume) - cheap no-op when unsupported or not enabled. */
    suspend fun applyIfEnabled(ctx: Context) = withContext(Dispatchers.IO) {
        if (isWritable() && PlayerPreferences.loadCpuPerfEnabled(ctx)) applyPerformance(ctx)
    }

    /** Self-heal a "performance" pin stranded by an earlier crashed/killed session. */
    suspend fun restoreIfStranded(ctx: Context) = withContext(Dispatchers.IO) {
        if (!PlayerPreferences.loadCpuPerfEnabled(ctx)) restoreStock(ctx)
    }

    /**
     * Always release the pin (backgrounding/pausing) WITHOUT touching the persisted toggle -
     * [applyIfEnabled] re-applies it on the next resume if the user still has it on.
     */
    suspend fun onBackground(ctx: Context) = withContext(Dispatchers.IO) {
        restoreStock(ctx)
    }

    private fun applyPerformance(ctx: Context) {
        val paths = corePaths()
        if (paths.isEmpty()) return
        // Stash real governors once - never overwrite an existing stash with "performance" (that
        // would corrupt the record on a second consecutive enable, e.g. after a quick toggle).
        if (PlayerPreferences.loadStashedGovernors(ctx) == null) {
            val current = paths.associateWith { readNode(it).orEmpty() }
            if (current.values.any { it.isNotBlank() }) PlayerPreferences.saveStashedGovernors(ctx, current)
        }
        val available = readNode("$CPU_ROOT/cpu0/cpufreq/scaling_available_governors").orEmpty()
        if (available.isNotBlank() && !available.contains(PERF_GOVERNOR)) {
            Log.i(TAG, "kernel does not offer the '$PERF_GOVERNOR' governor - nothing applied")
            return
        }
        val ok = paths.count { writeNode(it, PERF_GOVERNOR) }
        Log.i(TAG, "performance governor applied to $ok of ${paths.size} core(s)")
    }

    private fun restoreStock(ctx: Context) {
        val stashed = PlayerPreferences.loadStashedGovernors(ctx) ?: return
        stashed.forEach { (path, governor) -> if (governor.isNotBlank()) writeNode(path, governor) }
        PlayerPreferences.clearStashedGovernors(ctx)
    }
}
