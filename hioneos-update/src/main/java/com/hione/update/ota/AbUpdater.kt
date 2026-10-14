package com.hione.update.ota

import com.hione.update.Mascot
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs a HioneOS system image on the device through update_engine (A/B, Virtual A/B).
 *
 *   download payload.bin into /data/ota_package (resume)  ->  size + SHA-256 equal the signed
 *   manifest  ->  UpdateEngine.applyPayload(file://..., headers)  ->  update_engine checks the
 *   payload signature against otacerts.zip and writes the inactive slot  ->  "Restart to finish"
 *   ->  the bootloader tries the new slot. update_verifier marks it good once /data is up; if it
 *   never gets that far the bootloader goes back to the old slot, which update_engine left alone.
 *   After a good boot update_engine merges the snapshots in the background (MERGING).
 *
 * update_engine keeps going if this process dies. Binding again gets its current status, so the
 * screen picks up where it was. What we need across a restart (the build we are installing, the
 * boot count before the restart) sits in SharedPreferences.
 */
object AbUpdater {
    private const val TAG = "HioneUpdate"
    private const val PAYLOAD_DIR = "/data/ota_package"
    private const val FILE_PREFIX = "hioneos-"

    // update_engine's UpdateStatus values (update_status.h). 11 is not in the public constants.
    private const val ST_IDLE = 0
    private const val ST_DOWNLOADING = 3
    private const val ST_VERIFYING = 4
    private const val ST_FINALIZING = 5
    private const val ST_NEED_REBOOT = 6
    private const val ST_REPORTING_ERROR = 7
    private const val ST_CLEANUP_PREVIOUS = 11

    // update_engine's ErrorCode values (error_code.h) that get their own wording.
    private const val E_SUCCESS = 0
    private const val E_USER_CANCELED = 48

    /** Free space on /data needed on top of the payload, for the copy-on-write data. */
    private const val SPACE_MARGIN = 3L * 1024 * 1024 * 1024
    private const val MIN_BATTERY = 30

    enum class Phase { IDLE, DOWNLOADING, VERIFYING, APPLYING, FINALIZING, NEED_REBOOT, MERGING, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val build: String? = null,
        val delta: Boolean = false,
        val done: Long = 0,
        val total: Long = 0,
        /** update_engine's own progress, 0..1, while it writes or merges. */
        val percent: Float = 0f,
        val message: String? = null,
    ) {
        val busy get() = phase in setOf(Phase.DOWNLOADING, Phase.VERIFYING, Phase.APPLYING, Phase.FINALIZING)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var appCtx: Context? = null
    private var engine: UpdateEngine? = null
    private val running = AtomicBoolean(false)
    private val cancel = AtomicBoolean(false)
    /** True from applyPayload until update_engine reports the result, so a stray completion is ignored. */
    @Volatile private var applying = false
    /** What the current install was started with, for the automatic retry with the full payload. */
    @Volatile private var lastRequest: Triple<OtaManifest.SystemUpdate, URL, OtaManifest.Payload>? = null

    // ------------------------------------------------------------------ binding

    private val callback = object : UpdateEngineCallback() {
        override fun onStatusUpdate(status: Int, percent: Float) = onStatus(status, percent)
        override fun onPayloadApplicationComplete(errorCode: Int) = onComplete(errorCode)
    }

    /** Connects to update_engine. Safe to call often. False when this device has none. */
    fun bind(ctx: Context): Boolean {
        appCtx = ctx.applicationContext
        if (engine != null) return true
        if (!AbUpdateEnginePath.isAbDevice()) return false
        return try {
            val e = UpdateEngine()
            if (e.bind(callback, Handler(Looper.getMainLooper()))) {
                engine = e
                true
            } else {
                Log.w(TAG, "update_engine bind returned false")
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "update_engine not reachable", t)
            false
        }
    }

    private fun onStatus(status: Int, percent: Float) {
        val prefs = appCtx?.let { Prefs(it) }
        val pending = prefs?.abPendingBuild?.ifBlank { null }
        _state.update { s ->
            // Our own download and hash check run before update_engine is involved; its IDLE
            // reports during that time say nothing about us.
            if (s.phase == Phase.DOWNLOADING || s.phase == Phase.VERIFYING) return@update s
            when (status) {
                ST_DOWNLOADING, ST_VERIFYING -> {
                    applying = true
                    s.copy(phase = Phase.APPLYING, percent = percent, build = s.build ?: pending, message = null)
                }
                ST_FINALIZING -> {
                    applying = true
                    s.copy(phase = Phase.FINALIZING, percent = percent, build = s.build ?: pending, message = null)
                }
                ST_NEED_REBOOT -> s.copy(phase = Phase.NEED_REBOOT, percent = 1f, build = s.build ?: pending, message = null)
                ST_CLEANUP_PREVIOUS -> s.copy(phase = Phase.MERGING, percent = percent, message = null)
                ST_REPORTING_ERROR -> s
                ST_IDLE -> when (s.phase) {
                    Phase.MERGING, Phase.NEED_REBOOT, Phase.APPLYING, Phase.FINALIZING ->
                        // NEED_REBOOT -> IDLE means the update was reset; APPLYING -> IDLE without a
                        // result means update_engine restarted. Either way nothing is pending now.
                        if (applying) s else s.copy(phase = Phase.IDLE, percent = 0f)
                    else -> s
                }
                else -> s
            }
        }
    }

    private fun onComplete(code: Int) {
        val ctx = appCtx ?: return
        if (_state.value.phase == Phase.MERGING) {
            _state.update { it.copy(phase = Phase.IDLE, percent = 0f) }
            return
        }
        if (!applying) return
        applying = false
        val prefs = Prefs(ctx)
        val build = _state.value.build ?: prefs.abPendingBuild
        when (code) {
            E_SUCCESS -> {
                prefs.abApplied = true
                _state.update { it.copy(phase = Phase.NEED_REBOOT, percent = 1f, message = null) }
                build?.let { Notifier.systemReady(ctx, it) }
            }
            E_USER_CANCELED -> {
                clearPending(ctx, deleteFile = false)
                _state.update { it.copy(phase = Phase.IDLE, percent = 0f, message = "Stopped. Nothing changed on the system you are running.") }
            }
            else -> {
                val req = lastRequest
                val wasDelta = _state.value.delta
                Log.w(TAG, "update_engine finished with error $code (delta=$wasDelta)")
                // A delta only applies on top of the exact build it was made from. If the device
                // differs, update_engine stops before touching the running slot; try the full one.
                if (wasDelta && req != null && code in DELTA_MISMATCH) {
                    clearPending(ctx, deleteFile = true)
                    _state.update { it.copy(phase = Phase.IDLE, message = "The smaller download did not fit this device. Getting the full one.") }
                    kotlin.concurrent.thread(name = "ab-retry") {
                        kotlinx.coroutines.runBlocking { start(ctx, req.first, req.second, skipDelta = true) }
                    }
                    return
                }
                clearPending(ctx, deleteFile = code in BAD_FILE)
                val msg = describe(code)
                _state.update { it.copy(phase = Phase.FAILED, percent = 0f, message = msg) }
                Notifier.systemFailed(ctx, msg)
            }
        }
    }

    private val DELTA_MISMATCH = setOf(20, 27, 28, 29)
    private val BAD_FILE = setOf(10, 11, 12, 18, 21, 22, 23, 24, 25, 26, 32, 33, 39)

    fun describe(code: Int): String = when (code) {
        60 -> "There is not enough free space for the update. Free up a few GB and try again."
        10, 11, 12, 18, 21, 22, 23, 24, 25, 26, 32, 33, 39 ->
            "The update file did not pass update_engine's signature or hash check, so nothing was installed."
        51 -> "This update is older than the system on the device, so it was refused."
        9 -> "The update file could not be read. Try again."
        20, 27, 28, 29 -> "The update did not match what is on the device now. Nothing was installed."
        44, 45 -> "This update needs a newer update_engine than the one on the device."
        61 -> "update_engine says the storage looks damaged. Nothing was installed."
        else -> "The update stopped with update_engine error $code. The system you are running did not change."
    }

    // ------------------------------------------------------------------ install

    /**
     * Downloads and applies the best payload for [su]. [manifestUrl] resolves the payload URL.
     * Returns a message for the UI when it does not even start.
     */
    suspend fun start(ctx: Context, su: OtaManifest.SystemUpdate, manifestUrl: URL, skipDelta: Boolean = false): String? {
        val app = ctx.applicationContext
        appCtx = app
        val device = BuildInfo.hioneosVersion()
        val payload = AbUpdateEnginePath.choosePayload(app, su, device, skipDelta)
            ?: return "This update can not be installed on the device. Use the web installer."
        if (!running.compareAndSet(false, true)) return "An update is already running."
        cancel.set(false)
        try {
            return withContext(Dispatchers.IO) { doStart(app, su, manifestUrl, payload) }
        } finally {
            running.set(false)
        }
    }

    private fun doStart(ctx: Context, su: OtaManifest.SystemUpdate, manifestUrl: URL, p: OtaManifest.Payload): String? {
        val prefs = Prefs(ctx)
        if (!bind(ctx)) return "update_engine is not reachable on this device."
        val cur = _state.value.phase
        if (cur == Phase.APPLYING || cur == Phase.FINALIZING || cur == Phase.NEED_REBOOT || cur == Phase.MERGING) {
            return "update_engine is busy with another update."
        }
        batteryProblem(ctx)?.let { return it }

        val dir = File(PAYLOAD_DIR)
        val name = FILE_PREFIX + su.build + (if (p.isFull) "-full" else "-from-${p.sourceBuild}") + ".bin"
        val file = File(dir, name)
        // Earlier attempts for other builds only take space.
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith(FILE_PREFIX) && f.name != name && f.name != "$name.part") f.delete()
        }
        val have = if (file.isFile) file.length() else File(dir, "$name.part").length()
        val free = runCatching { StatFs(PAYLOAD_DIR).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < (p.size - have) + SPACE_MARGIN) {
            val needGb = ((p.size - have + SPACE_MARGIN) / (1024.0 * 1024 * 1024))
            return "The update needs about %.1f GB free. Free up some space and try again.".format(needGb)
        }

        lastRequest = Triple(su, manifestUrl, p)
        _state.value = State(Phase.DOWNLOADING, su.build, !p.isFull, done = have, total = p.size)
        try {
            if (!(file.isFile && file.length() == p.size && Http.sha256(file) == p.sha256)) {
                val sha = Http.download(
                    URL(manifestUrl, p.url), file, p.size,
                    onProgress = { d, _ -> _state.update { it.copy(done = d) } },
                    isCancelled = { cancel.get() },
                )
                _state.update { it.copy(phase = Phase.VERIFYING, done = p.size) }
                if (sha != p.sha256) {
                    file.delete()
                    return fail(ctx, "The download did not match its signed hash, so it was thrown away.")
                }
            }
            // update_engine runs as root without permission to override file modes.
            file.setReadable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "payload download failed", e)
            val msg = if (cancel.get()) "Stopped. The part already downloaded is kept for next time."
            else "The download stopped: ${e.message ?: e.javaClass.simpleName}. Try again to pick up where it left off."
            _state.update { it.copy(phase = Phase.IDLE, message = msg) }
            return null
        }
        if (cancel.get()) {
            _state.update { it.copy(phase = Phase.IDLE, message = "Stopped.") }
            return null
        }

        prefs.abPendingBuild = su.build
        prefs.abFromBuild = BuildInfo.hioneosVersion() ?: ""
        prefs.abFile = file.path
        prefs.abApplied = false
        prefs.abBootCount = bootCount(ctx)
        applying = true
        _state.update { it.copy(phase = Phase.APPLYING, percent = 0f, message = null) }
        return try {
            engine!!.applyPayload("file://${file.path}", p.offset, p.size, p.headers.toTypedArray())
            null
        } catch (e: Exception) {
            // update_engine reports a refusal (busy, bad headers) as a ServiceSpecificException.
            applying = false
            clearPending(ctx, deleteFile = false)
            fail(ctx, "update_engine did not take the update: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fail(ctx: Context, msg: String): String {
        _state.update { it.copy(phase = Phase.FAILED, message = msg) }
        return msg
    }

    private fun batteryProblem(ctx: Context): String? {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val pct = if (level < 0) 100 else level * 100 / scale
        return if (!plugged && pct < MIN_BATTERY) "Charge to $MIN_BATTERY% or plug in first. The update takes a while." else null
    }

    private fun bootCount(ctx: Context): Int =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    /** Stops a download, stops update_engine mid-write, or undoes a written but not yet booted update. */
    fun cancelOrUndo(ctx: Context) {
        val e = engine
        when (_state.value.phase) {
            Phase.DOWNLOADING, Phase.VERIFYING -> cancel.set(true)
            Phase.APPLYING, Phase.FINALIZING -> runCatching { e?.cancel() }
                .onFailure { Log.w(TAG, "cancel failed", it) }
            Phase.NEED_REBOOT -> {
                // resetStatus drops the written slot and keeps booting the current one.
                val ok = runCatching { e?.resetStatus() }.isSuccess
                if (ok) {
                    clearPending(ctx, deleteFile = true)
                    _state.update { it.copy(phase = Phase.IDLE, percent = 0f, message = "The update was undone. The device stays on the system it runs now.") }
                } else {
                    _state.update { it.copy(message = "update_engine would not undo the update.") }
                }
            }
            else -> {}
        }
    }

    /** Restarts into the new slot. Hione Update holds REBOOT as a platform-signed system app. */
    fun restart(ctx: Context) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        runCatching { pm.reboot(null) }.onFailure {
            Log.w(TAG, "reboot failed", it)
            _state.update { s -> s.copy(message = "Could not restart. Restart the M500 by hand to finish.") }
        }
    }

    fun clearMessage() = _state.update { if (it.phase == Phase.FAILED) it.copy(phase = Phase.IDLE, message = null) else it.copy(message = null) }

    private fun clearPending(ctx: Context, deleteFile: Boolean) {
        val prefs = Prefs(ctx)
        if (deleteFile) prefs.abFile.takeIf { it.isNotBlank() }?.let { File(it).delete(); File("$it.part").delete() }
        prefs.abPendingBuild = ""
        prefs.abApplied = false
    }

    // ------------------------------------------------------------------ after a restart

    /**
     * Called at boot and when the app starts. Works out what happened to an update that was
     * pending before the restart: it booted (the build changed), or the bootloader went back to
     * the old slot, or the restart came before update_engine had finished writing.
     */
    fun afterBoot(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        bind(app)
        val prefs = Prefs(app)
        val pending = prefs.abPendingBuild.ifBlank { return }
        val now = BuildInfo.hioneosVersion()
        val restarted = bootCount(app).let { it > 0 && prefs.abBootCount in 0 until it }
        when {
            now == pending -> {
                Notifier.systemInstalled(app, pending)
                clearPending(app, deleteFile = true)
                _state.update { it.copy(message = "${Mascot.osName} $pending is installed.") }
            }
            restarted && prefs.abApplied -> {
                val msg = "${Mascot.osName} $pending did not start, so the M500 went back to ${Mascot.osName} ${now ?: "it had"}. Your data is as it was."
                Notifier.systemFailed(app, msg)
                clearPending(app, deleteFile = false)
                _state.update { it.copy(phase = Phase.FAILED, message = msg) }
            }
            restarted -> {
                // update_engine does not carry on after a restart by itself. Applying the same
                // payload again resumes from its last checkpoint.
                clearPending(app, deleteFile = false)
                _state.update { it.copy(phase = Phase.IDLE, message = "The update was cut off by a restart. Install again to pick up where it stopped.") }
            }
        }
    }
}
