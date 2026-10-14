package com.m500.hardware

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Blackjack Monitor: shows, live, which DAC driver is running and what it is doing.
 *
 * Blackjack is our clean-room driver pair (blackjack_codec for the two CS43198s at I2C 0x30 and
 * 0x33, blackjack_hooks for the platform glue machine_dlkm calls). This file only READS: nothing
 * here writes a node, a property or a register.
 *
 * Sources, as they work for this app (platform_app, uid u0_a59, SELinux enforcing):
 *  - Driver identity: readlink on the sysfs `driver` and `module` symlinks. Every domain may read
 *    sysfs symlinks (`allow domain sysfs:lnk_file read`), but not sysfs files or directory
 *    listings, so `regs`, module parameters and even stat() on /sys/module/x are refused.
 *  - Everything live: the kernel log (`logcat -b kernel`, READ_LOGS). With log_writes=1 every
 *    register write is one line, so the register picture below is rebuilt from those lines.
 *  - The rate Android sends: AudioFlinger's dump of its output threads (DUMP), with the hidden
 *    playback-config format info as the fallback.
 *  - High power and gain are not registers (high power is board GPIOs, gain is an offset folded
 *    into the volume register), so they come from the persist properties the DAC was last told.
 *
 * Register meanings: blackjack_new/docs/m500-audio-driver-spec.md sections 2.6, 7.3.1, 8 and 9.
 */
object BlackjackMonitor {
    private const val TAG = "BlackjackMonitor"

    // ---------------------------------------------------------------- identity

    enum class Driver { BLACKJACK, HIBY, NONE, UNKNOWN }

    data class Identity(
        val driver: Driver,
        /** Driver bound to 0-0030, e.g. blackjack-cs43198 or cs43198. */
        val codecDriver: String?,
        /** Module that owns that driver, e.g. blackjack_codec. */
        val codecModule: String?,
        /** Driver bound to the hiby,sound-plat platform device. */
        val hooksDriver: String?,
        val hooksModule: String?,
        /** name -> value, null when the node is not readable from this app. */
        val params: Map<String, String?>,
    ) {
        val ours get() = driver == Driver.BLACKJACK
    }

    private const val CODEC_DEV = "/sys/bus/i2c/devices/0-0030"
    private const val PLAT_DEV = "/sys/devices/platform/soc/soc:hiby,sound-plat"
    private val CODEC_PARAMS = listOf("chmap", "log_writes", "sos")
    private val HOOKS_PARAMS = listOf("clk44")

    private fun link(path: String): String? = runCatching { Os.readlink(path) }.getOrNull()

    /**
     * Module parameters are plain `sysfs` files, which platform_app may not read today. Try once
     * per path: a refused read is not retried, so the identity poll does not log an SELinux
     * denial every few seconds. A later image that labels them readable just works.
     */
    private val refused = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun param(path: String): String? {
        if (path in refused) return null
        return readFile(path).also { if (it == null) refused += path }
    }

    private fun readFile(path: String): String? =
        runCatching { File(path).readText().trim() }.getOrNull()?.ifEmpty { null }

    /** Cheap: one readlink. True when the 0x30 codec is bound to Blackjack's driver. */
    fun isBlackjackLoaded(): Boolean = link("$CODEC_DEV/driver")?.substringAfterLast('/')?.startsWith("blackjack") == true

    fun identity(): Identity {
        val codecDrv = link("$CODEC_DEV/driver")?.substringAfterLast('/')
        val hooksDrv = link("$PLAT_DEV/driver")?.substringAfterLast('/')
        val codecMod = codecDrv?.let { link("/sys/bus/i2c/drivers/$it/module")?.substringAfterLast('/') }
        val hooksMod = hooksDrv?.let { link("/sys/bus/platform/drivers/$it/module")?.substringAfterLast('/') }
        val params = LinkedHashMap<String, String?>()
        val cm = codecMod ?: "blackjack_codec"
        val hm = hooksMod ?: "blackjack_hooks"
        CODEC_PARAMS.forEach { params["$cm.$it"] = param("/sys/module/$cm/parameters/$it") }
        HOOKS_PARAMS.forEach { params["$hm.$it"] = param("/sys/module/$hm/parameters/$it") }
        val driver = when {
            codecDrv == null && hooksDrv == null -> Driver.UNKNOWN
            codecDrv?.startsWith("blackjack") == true || hooksDrv?.startsWith("blackjack") == true -> Driver.BLACKJACK
            codecDrv != null -> Driver.HIBY
            else -> Driver.NONE
        }
        return Identity(driver, codecDrv, codecMod, hooksDrv, hooksMod, params)
    }

    // ---------------------------------------------------------------- decoding

    /** Spec 2.6. Index = Output Select target. */
    fun targetName(t: Int?): String = when (t) {
        null -> "unknown"
        0 -> "None"
        1 -> "Line out"
        2 -> "3.5 mm"
        3 -> "4.4 mm balanced"
        4 -> "S/PDIF"
        5 -> "Coax"
        6 -> "I2S"
        7 -> "Balanced line out"
        8 -> "Speaker"
        9 -> "6.35 mm"
        else -> "target $t"
    }

    /** Chips a target drives (spec 2.4): 3.5 mm is 0x30 alone, 4.4 mm is both. */
    fun chipsFor(t: Int?): List<Int> = when (t) { 2 -> listOf(0x30); 3 -> listOf(0x30, 0x33); else -> emptyList() }

    /** Spec 9.2: 0x01000B ASP rate index. */
    private val RATES = intArrayOf(32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)
    fun rateFromIndex(i: Int?): Int? = i?.takeIf { it in RATES.indices }?.let { RATES[it] }

    fun formatRate(hz: Int?): String = when {
        hz == null -> "unknown"
        hz % 1000 == 0 -> "${hz / 1000} kHz"
        else -> "%.1f kHz".format(hz / 1000.0)
    }

    /** Spec 9.4: 0x01000C ASP_SPSIZE, 00 = 32, 01 = 24, 10 = 16 bits. */
    fun wordLength(v: Int?): String = when (v?.and(0x03)) { null -> "unknown"; 0 -> "32-bit"; 1 -> "24-bit"; 2 -> "16-bit"; else -> "reserved (0x%02X)".format(v) }

    /** Spec 8.1: 0x090000 bits 7:5. NOS (bit 5) wins; bits 7:6 are then ignored by the chip. */
    fun filterName(v: Int?): String = when {
        v == null -> "unknown"
        v and 0x20 != 0 -> "NOS"
        else -> when (v and 0xC0) {
            0x00 -> "Fast, low latency"
            0x40 -> "Fast, phase compensated"
            0x80 -> "Slow, low latency"
            else -> "Slow, phase compensated"
        }
    }

    /** Spec 8.5: 0.5 dB per count, 0x00 = 0 dB, 0xFF = mute. */
    fun volumeText(v: Int?): String = when (v) { null -> "unknown"; 0xFF -> "mute"; else -> if (v == 0) "0 dB" else "-%.1f dB".format(v * 0.5) }

    /** Spec 8.3: 0x0C0001 = 01 on, 00 off. */
    fun dreText(v: Int?): String = when (v) { null -> "unknown"; 0 -> "off"; 1 -> "on"; else -> "0x%02X".format(v) }

    /** Spec 7.3.1 / 7.7: 0x020000, PDN_ASP = 0x40, PDN_HP = 0x10, set = powered down. */
    fun powerText(v: Int?): String = when {
        v == null -> "unknown"
        v and 0x50 == 0 -> "on"
        v and 0x10 == 0 -> "output on, serial port off"
        v and 0x40 == 0 -> "serial port on, output off"
        else -> "powered down"
    }

    /** 0x090003 bits 1:0 mute B / A. */
    fun muteText(v: Int?): String = when (v?.and(0x03)) { null -> "unknown"; 0 -> "not muted"; 3 -> "muted"; 1 -> "B muted"; else -> "A muted" }

    // ---------------------------------------------------------------- live state from the log

    enum class Access { STARTING, OK, DENIED, FAILED }
    enum class Kind { POWER, ROUTE, STREAM, SETTING, WARN, ERROR, INFO }

    data class Event(val atMs: Long, val kind: Kind, val text: String)

    data class Chip(
        val addr: Int,
        val deviceId: String? = null,
        val rev: String? = null,
        val regs: Map<Int, Int> = emptyMap(),
        val writes: Int = 0,
    ) {
        fun reg(r: Int): Int? = regs[r]
    }

    data class State(
        val access: Access = Access.STARTING,
        val accessNote: String? = null,
        val linesSeen: Int = 0,
        val probeLine: String? = null,
        val hooksRegistered: Boolean = false,
        /** Last Output Select request (hiby_output_switch). */
        val selected: Int? = null,
        val selectedAtMs: Long? = null,
        /** Last target Blackjack powered on, and whether it is still on. */
        val powered: Boolean? = null,
        val poweredTarget: Int? = null,
        val powerAtMs: Long? = null,
        val chips: Map<Int, Chip> = mapOf(0x30 to Chip(0x30), 0x33 to Chip(0x33)),
        /** Backend rate from blackjack_hooks' hw_params line. */
        val hwRate: Int? = null,
        val hwFormat: Int? = null,
        val hwChannels: Int? = null,
        val hwAtMs: Long? = null,
        val writes: Int = 0,
        val failedWrites: Int = 0,
        val lastFailure: String? = null,
        val events: List<Event> = emptyList(),
        val problems: List<Event> = emptyList(),
        val updatedAtMs: Long = 0,
    ) {
        /** The chip whose registers describe the current output: 0x30 is in every DAC target. */
        val main: Chip get() = chips.getValue(0x30)
        val dacRate: Int? get() = rateFromIndex(main.reg(0x01000B))
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var users = 0
    private var reader: Thread? = null
    private var proc: Process? = null
    private var stopAt = 0L
    private const val LINGER_MS = 10 * 60_000L

    /** True while a reader is running (so the log was allowed in this process). */
    val isReading: Boolean get() = synchronized(lock) { reader?.isAlive == true }

    /**
     * Start (or keep) the kernel log reader. Pair with [release].
     *
     * An app uid reading the kernel log makes Android 13+ ask the person once ("Allow one-time
     * access"): logd exempts only uids below 10000, and LogcatManagerService shows the dialog
     * while the app is in front and silently declines it otherwise. An allowed reader keeps
     * streaming until it exits. So only the Monitor screen starts it ([start] = true); the DAC
     * page card just [watch]es one that is already running.
     */
    fun acquire(start: Boolean = true) = synchronized(lock) {
        users++
        stopAt = 0
        if (start && reader?.isAlive != true) startReader()
    }

    /** Start again after the person declined, or logcat ended. */
    fun retry() = synchronized(lock) {
        // A declined reader stays connected but only gets this app's own lines: replace it.
        runCatching { proc?.destroy() }
        proc = null
        startReader()
    }

    /**
     * Stop the reader ten minutes after the last screen lets go. Moving between the DAC page and
     * the Monitor then neither restarts logcat nor asks for log access again.
     */
    fun release() = synchronized(lock) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            stopAt = System.currentTimeMillis() + LINGER_MS
            Thread {
                Thread.sleep(LINGER_MS + 1000)
                synchronized(lock) {
                    if (users == 0 && stopAt != 0L && System.currentTimeMillis() >= stopAt) {
                        runCatching { proc?.destroy() }
                        proc = null
                    }
                }
            }.apply { isDaemon = true; name = "bj-linger" }.start()
        }
    }

    // ---- Facts printed once per boot (probe, hooks registered, chip ids). The kernel ring is
    // 256 KiB and with log_writes=1 it rotates within the hour, so these are kept per boot.
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun attach(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("blackjack_monitor", Context.MODE_PRIVATE)
    }

    private fun bootEpochMs() = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

    private fun seeded(): State {
        val p = prefs ?: return State()
        if (kotlin.math.abs(p.getLong("boot", 0) - bootEpochMs()) > 30_000) return State()
        var st = State(probeLine = p.getString("probe", null), hooksRegistered = p.getBoolean("hooks", false))
        for (a in listOf(0x30, 0x33)) {
            val id = p.getString("id_$a", null) ?: continue
            st = st.copy(chips = st.chips + (a to Chip(a, id, p.getString("rev_$a", null))))
        }
        return st
    }

    private var savedKey = ""
    private fun saveBootFacts(st: State) {
        val p = prefs ?: return
        val key = "${st.probeLine}|${st.hooksRegistered}|${st.chips[0x30]?.deviceId}|${st.chips[0x30]?.rev}|${st.chips[0x33]?.deviceId}|${st.chips[0x33]?.rev}"
        if (key == savedKey) return
        savedKey = key
        p.edit().apply {
            putLong("boot", bootEpochMs())
            putString("probe", st.probeLine)
            putBoolean("hooks", st.hooksRegistered)
            for (a in listOf(0x30, 0x33)) {
                putString("id_$a", st.chips[a]?.deviceId)
                putString("rev_$a", st.chips[a]?.rev)
            }
        }.apply()
    }

    private fun startReader() {
        _state.value = seeded().copy(access = Access.STARTING)
        val t = Thread({ runReader() }, "bj-kernel-log")
        t.isDaemon = true
        reader = t
        t.start()
    }

    // Kernel lines in `-v epoch`: "  1791691101.248   630   630 D blackjack-cs43198 0-0030: msg"
    private val LINE = Regex("""^\s*(\d+)\.(\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.*)$""")
    private val WRITE = Regex("""cs43198_i2c_write_reg addr=0x([0-9a-fA-F]+) reg=0x([0-9a-fA-F]+) data=0x([0-9a-fA-F]+) r=(-?\d+)""")
    private val POWER = Regex("""power (on|off) target (\d+)""")
    private val SWITCH = Regex("""hiby_output_switch: (\S+) -> target (\d+)""")
    private val HWP = Regex("""hiby_snd_hw_params: rate=(\d+) format=(\d+) channels=(\d+)""")
    private val DEVID = Regex("""0x([0-9a-fA-F]{2}): device id (\w+) rev (0x[0-9a-fA-F]+)""")

    private fun runReader() {
        val me = Thread.currentThread()
        var p: Process? = null
        try {
            p = ProcessBuilder("logcat", "-b", "kernel", "-v", "epoch")
                .redirectErrorStream(true).start()
            synchronized(lock) { proc = p }
            // With READ_LOGS refused (or the one-time consent declined), logd only sends this
            // app's own lines, and the kernel buffer has none: the stream stays silent. The kernel
            // buffer is never empty after boot, so silence for a few seconds means no access.
            val watchdog = Thread {
                runCatching { Thread.sleep(4000) }
                if (_state.value.linesSeen == 0 && _state.value.access == Access.STARTING) {
                    update { it.copy(access = Access.DENIED, accessNote = "Waiting for log access. Android asks once each time the Monitor starts reading: choose Allow one-time access.") }
                }
            }.apply { isDaemon = true }
            watchdog.start()
            BufferedReader(InputStreamReader(p.inputStream), 16 * 1024).use { br ->
                var batch = 0
                var s = _state.value
                while (true) {
                    val line = br.readLine() ?: break
                    s = parse(s, line)
                    batch++
                    // Publish in batches while the backlog streams in, then every line once live.
                    if (batch >= 200 || !br.ready()) {
                        if (synchronized(lock) { reader } !== me) break
                        s = s.copy(access = Access.OK, accessNote = null, updatedAtMs = System.currentTimeMillis())
                        _state.value = s
                        saveBootFacts(s)
                        batch = 0
                    }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            Log.i(TAG, "logcat ended ($code) after ${_state.value.linesSeen} lines")
            if (_state.value.linesSeen == 0 && synchronized(lock) { reader } === me) {
                update { it.copy(access = Access.DENIED, accessNote = "Log access was not allowed, so the live driver state cannot be read.") }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "kernel log reader failed: $t")
            update { it.copy(access = Access.FAILED, accessNote = "Could not run logcat: ${t.message}") }
        } finally {
            runCatching { p?.destroy() }
            synchronized(lock) { if (proc === p) proc = null; if (reader === Thread.currentThread()) reader = null }
        }
    }

    private inline fun update(f: (State) -> State) { _state.value = f(_state.value) }

    private const val MAX_EVENTS = 300

    private fun State.addEvent(e: Event, problem: Boolean = false): State {
        val ev = (events + e).let { if (it.size > MAX_EVENTS) it.drop(it.size - MAX_EVENTS) else it }
        val pr = if (problem) (problems + e).let { if (it.size > 100) it.drop(it.size - 100) else it } else problems
        return copy(events = ev, problems = pr)
    }

    /** One kernel log line in, the next state out. Ignores everything that is not the DAC stack. */
    internal fun parse(s0: State, line: String): State {
        val s = s0.copy(linesSeen = s0.linesSeen + 1)
        if (!(line.contains("blackjack") || line.contains("cs43198") || line.contains("hiby_output_switch") || line.contains("hiby_snd_hw_params"))) return s
        val m = LINE.find(line) ?: return s
        val at = m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong()
        val level = m.groupValues[3][0]
        val rest = m.groupValues[4]

        WRITE.find(rest)?.let { w ->
            val addr = w.groupValues[1].toInt(16)
            val reg = w.groupValues[2].toInt(16)
            val data = w.groupValues[3].toInt(16)
            val r = w.groupValues[4].toInt()
            var out = s.copy(writes = s.writes + 1)
            if (r != 0) {
                val msg = "Write failed: 0x%02X reg 0x%06X = 0x%02X (r=%d)".format(addr, reg, data, r)
                out = out.copy(failedWrites = out.failedWrites + 1, lastFailure = msg).addEvent(Event(at, Kind.ERROR, msg), problem = true)
                return out
            }
            val chip = out.chips[addr] ?: Chip(addr)
            val old = chip.regs[reg]
            val newChip = chip.copy(regs = chip.regs + (reg to data), writes = chip.writes + 1)
            out = out.copy(chips = out.chips + (addr to newChip))
            // Timeline: the settings a person would care about, on 0x30 only (0x33 mirrors it).
            if (addr == 0x30 && old != data) {
                val txt = when (reg) {
                    0x01000B -> "DAC rate ${formatRate(rateFromIndex(data))}"
                    0x01000C -> "Word length ${wordLength(data)}"
                    0x090000 -> if (old == null || (old and 0xE0) != (data and 0xE0)) "Filter ${filterName(data)}" else null
                    0x0C0001 -> "DRE ${dreText(data)}"
                    else -> null
                }
                if (txt != null) out = out.addEvent(Event(at, Kind.SETTING, txt))
            }
            return out
        }

        POWER.find(rest)?.let { pm ->
            val on = pm.groupValues[1] == "on"
            val t = pm.groupValues[2].toInt()
            return s.copy(powered = on, poweredTarget = t, powerAtMs = at)
                .addEvent(Event(at, Kind.POWER, "Power ${if (on) "on" else "off"}: ${targetName(t)}"))
        }

        SWITCH.find(rest)?.let { sw ->
            val t = sw.groupValues[2].toInt()
            return s.copy(selected = t, selectedAtMs = at).addEvent(Event(at, Kind.ROUTE, "Output select: ${targetName(t)}"))
        }

        HWP.find(rest)?.let { h ->
            val rate = h.groupValues[1].toInt()
            val fmt = h.groupValues[2].toInt()
            val ch = h.groupValues[3].toInt()
            return s.copy(hwRate = rate, hwFormat = fmt, hwChannels = ch, hwAtMs = at)
                .addEvent(Event(at, Kind.STREAM, "Stream start: ${formatRate(rate)}, ${alsaFormat(fmt)}, $ch ch"))
        }

        DEVID.find(rest)?.let { d ->
            val addr = d.groupValues[1].toInt(16)
            val chip = (s.chips[addr] ?: Chip(addr)).copy(deviceId = d.groupValues[2], rev = d.groupValues[3])
            return s.copy(chips = s.chips + (addr to chip))
        }

        if (rest.contains("blackjack codec:")) {
            return s.copy(probeLine = rest.substringAfter("blackjack codec:").trim())
                .addEvent(Event(at, Kind.INFO, "Codec loaded: " + rest.substringAfter("blackjack codec:").trim()))
        }
        if (rest.contains("blackjack hooks: registered")) {
            return s.copy(hooksRegistered = true).addEvent(Event(at, Kind.INFO, "Hooks registered"))
        }

        // Anything else Blackjack says at warning level or above.
        if (rest.contains("blackjack") && (level == 'W' || level == 'E' || level == 'F')) {
            // Drop the "blackjack-cs43198 0-0030: " / "blackjack-hooks soc: hiby,sound-plat: " prefix.
            val msg = when {
                rest.contains("0-0030: ") -> rest.substringAfter("0-0030: ")
                rest.contains("sound-plat: ") -> rest.substringAfter("sound-plat: ")
                else -> rest.substringAfter(": ", rest)
            }.trim()
            return s.addEvent(Event(at, if (level == 'W') Kind.WARN else Kind.ERROR, msg), problem = true)
        }
        return s
    }

    /** ALSA snd_pcm_format_t numbers used by hw_params. */
    fun alsaFormat(f: Int?): String = when (f) { null -> "unknown"; 2 -> "16-bit"; 6 -> "24-bit"; 10 -> "32-bit"; 32 -> "24-bit packed"; 14 -> "float"; else -> "format $f" }

    // ---------------------------------------------------------------- what Android sends

    data class Stream(
        val rate: Int?,
        val format: String?,
        val thread: String?,
        val device: String?,
        /** Where the numbers came from, shown under them. */
        val source: String,
    )

    /**
     * The rate of the active wired output thread in AudioFlinger, or the playback-config format
     * as a fallback. Null when nothing is playing or nothing could be read. Blocks up to ~2 s.
     */
    fun androidStream(ctx: Context): Stream? = flingerStream() ?: playbackConfigStream(ctx)

    private fun flingerStream(): Stream? {
        val text = flingerDump() ?: return null
        // Split per thread; keep live (not standby) output threads, wired first.
        val blocks = text.split(Regex("(?m)^(?=Output thread )"))
            .filter { it.startsWith("Output thread ") }
        data class T(val name: String, val type: String, val standby: Boolean, val rate: Int?, val fmt: String?, val dev: String)
        val ts = blocks.map { b ->
            val head = b.lineSequence().first()
            T(
                name = Regex("""name (\S+?),""").find(head)?.groupValues?.get(1) ?: "?",
                type = Regex("""\((\w+)\):""").find(head)?.groupValues?.get(1) ?: "?",
                standby = Regex("""Standby: (\w+)""").find(b)?.groupValues?.get(1) == "yes",
                rate = Regex("""Sample rate: (\d+) Hz""").find(b)?.groupValues?.get(1)?.toIntOrNull(),
                fmt = Regex("""HAL format: \S+ \(AUDIO_FORMAT_(\w+)\)""").find(b)?.groupValues?.get(1),
                dev = Regex("""Output devices: \S+ \(([^)]*)\)""").find(b)?.groupValues?.get(1) ?: "",
            )
        }
        val live = ts.filter { !it.standby && it.rate != null && !it.dev.contains("TELEPHONY") && it.dev.isNotBlank() && !it.dev.contains("Empty") }
        val pick = live.firstOrNull { it.dev.contains("WIRED") || it.dev.contains("LINE") }
            ?: live.firstOrNull { it.dev.contains("SPEAKER") } ?: live.firstOrNull()
        if (pick == null) return Stream(null, null, null, null, "AudioFlinger: no output is playing")
        val fmt = pick.fmt?.removePrefix("PCM_")?.lowercase()?.replace('_', ' ')
        val dev = pick.dev.removePrefix("AUDIO_DEVICE_OUT_").replace("AUDIO_DEVICE_OUT_", "").lowercase().replace('_', ' ')
        return Stream(pick.rate, fmt, "${pick.name} (${pick.type.lowercase()})", dev, "AudioFlinger output thread")
    }

    @Volatile private var loggedDump = false

    private fun flingerDump(): String? = runCatching {
        val sm = Class.forName("android.os.ServiceManager")
        val b = sm.getMethod("getService", String::class.java).invoke(null, "media.audio_flinger") as? IBinder ?: return null
        val pipe = ParcelFileDescriptor.createPipe()
        val w = pipe[1]
        val t = Thread {
            runCatching { b.dump(w.fileDescriptor, arrayOf()) }.onFailure { Log.w(TAG, "flinger dump: $it") }
            runCatching { w.close() }
        }.apply { isDaemon = true; start() }
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 2000
        ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).bufferedReader().use { r ->
            val buf = CharArray(8192)
            while (System.currentTimeMillis() < deadline) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
                if (sb.length > 2_000_000) break
            }
        }
        t.join(200)
        if (!loggedDump) { loggedDump = true; Log.i(TAG, "flinger dump: ${sb.length} chars") }
        sb.toString().ifBlank { null }
    }.onFailure { Log.w(TAG, "flinger dump failed: $it") }.getOrNull()

    /** Android 14's hidden AudioPlaybackConfiguration.getFormatInfo(): the mixer format a player lands on. */
    private fun playbackConfigStream(ctx: Context): Stream? = runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val active = am.activePlaybackConfigurations.filter { it.isActiveCompat() }
        for (c in active) {
            val fi = runCatching { c.javaClass.getMethod("getFormatInfo").invoke(c) }.getOrNull() ?: continue
            val rate = runCatching { fi.javaClass.getMethod("getSampleRate").invoke(fi) as Int }.getOrNull()
                ?: runCatching { fi.javaClass.getDeclaredField("mSampleRate").apply { isAccessible = true }.getInt(fi) }.getOrNull()
            if (rate != null && rate > 0) {
                val dev = runCatching { c.audioDeviceInfo?.productName?.toString() }.getOrNull()
                return@runCatching Stream(rate, null, null, dev, "Playback config (Android mixer)")
            }
        }
        null
    }.getOrNull()

    private fun android.media.AudioPlaybackConfiguration.isActiveCompat(): Boolean =
        runCatching { javaClass.getMethod("isActive").invoke(this) as Boolean }.getOrDefault(true)
}
