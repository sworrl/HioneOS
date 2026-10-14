package com.hione.riot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.media3.common.Player
import com.hione.riot.Lcd.Companion.CLEAR
import com.hione.riot.Lcd.Companion.H
import com.hione.riot.Lcd.Companion.INK
import com.hione.riot.Lcd.Companion.W
import com.hione.riot.RiotUi.battery
import com.hione.riot.RiotUi.list
import com.hione.riot.RiotUi.message
import com.hione.riot.RiotUi.tag
import com.hione.riot.RiotUi.volume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Riot mode's whole firmware: the home screens (Now Playing, FM Tuner), the menu tree from the
 * guide's page 39, the backlight and power saver, and the keys. Draws into [lcd]; the Compose
 * layer turns that into pixels and bumps [frame] to show a new one.
 */
class RiotShell(private val activity: RiotActivity, unlocked: Boolean) {

    val prefs = RiotPrefs(activity)
    val lib = RiotLibrary(activity, prefs)
    val media = RiotMedia(activity)
    val lcd = Lcd()
    val frame = mutableIntStateOf(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var unlocked = unlocked
    private val stack = ArrayList<Page>()
    var animating = false

    // ---- time-driven state -------------------------------------------------------------------
    private var bootStart = SystemClock.uptimeMillis()
    private var lastInput = SystemClock.uptimeMillis()
    private var asleep = false
    /** Hold switch on the body. */
    var locked = false
        set(v) { field = v; flash(if (v) "LOCK" else null); dirty = true }
    private var msg: String? = null
    private var msgTitle: String? = null
    private var msgUntil = 0L
    private var msgAction: (() -> Unit)? = null
    private var lockFlashUntil = 0L
    private var dirty = true
    private var lastPoll = 0L

    // ---- now playing -------------------------------------------------------------------------
    private var now = RiotMedia.Now()
    private var showInfo = false
    private var npCursor = -1
    private var npCursorUntil = 0L
    private var format: String? = null
    private var batt = RiotSystem.Battery(100, false)
    private var vol = 0 to 15
    private var played = HashSet<Int>()
    private var usbWas = false
    private var usbScreen = false

    // ---- radio ---------------------------------------------------------------------------------
    /** The FM app's media session. Riot mode never opens the tuner itself. */
    val fm = RiotFm(activity)
    private var fmKHz = prefs.fmKHz
    private var presetSel = 0
    /** Last local tune: the session's frequency is ignored for a moment so steps don't bounce. */
    private var tunedAt = 0L
    /** Tune [fmKHz] as soon as the session connects (entering radio mode, or starting in it). */
    private var tuneOnConnect = false
    /** Back on the FM screen: the station details instead of the presets. */
    private var fmInfo = false
    /** Volume bar popup, for screens that hide the home screen's own bar. */
    private var volUntil = 0L

    // ---- keys ----------------------------------------------------------------------------------
    private val downAt = HashMap<Key, Long>()
    private val held = HashSet<Key>()

    val lit: Boolean get() {
        val ms = RiotPrefs.BACKLIGHT_MS[prefs.backlight.coerceIn(0, 4)]
        return !asleep && ms > 0 && SystemClock.uptimeMillis() - lastInput < ms
    }
    val sleeping get() = asleep

    // ---- lifecycle -----------------------------------------------------------------------------

    fun start() {
        RiotAssets.load(activity)
        media.onTrackStarted = { id -> id.toLongOrNull()?.let { lib.countPlay(it) } }
        media.connect { dirty = true }
        fm.onChange = { onFm() }
        if (prefs.radioMode) { tuneOnConnect = true; fm.connect() }
        loadLibrary()
        dirty = true
    }

    fun stop() {
        media.release()
        fm.release()
    }

    fun setUnlocked(u: Boolean) { if (u != unlocked) { unlocked = u; dirty = true } }

    fun loadLibrary() {
        if (!hasAudioPermission()) {
            activity.requestAudioPermission()
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) { lib.load() }
            dirty = true
        }
    }

    private fun hasAudioPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun goHome() { stack.clear(); showInfo = false; dirty = true }

    fun push(p: Page) { p.shell = this; stack.add(p); dirty = true }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); dirty = true }
    fun flash(text: String?, title: String? = null, ms: Long = 2200, action: (() -> Unit)? = null) {
        if (text == "LOCK") { lockFlashUntil = SystemClock.uptimeMillis() + 1500; dirty = true; return }
        msg = text; msgTitle = title; msgAction = action
        msgUntil = if (action != null) Long.MAX_VALUE else SystemClock.uptimeMillis() + ms
        dirty = true
    }

    // ---- input ---------------------------------------------------------------------------------

    /** Returns true when the key was consumed (it always is, unless it should reach the system). */
    private fun wakeUp(): Boolean {
        lastInput = SystemClock.uptimeMillis()
        dirty = true
        if (asleep) { asleep = false; activity.setPowerSaving(false); return true }
        return false
    }

    fun wheel(d: Int) {
        if (wakeUp()) return
        if (locked) { flash("LOCK"); return }
        if (bootDone().not()) return
        if (msg != null) { msg = null; return }
        if (usbScreen && stack.isEmpty()) { usbScreen = false; return }
        activity.tickFeedback()
        val top = stack.lastOrNull()
        if (top != null) top.wheel(d)
        else if (prefs.radioMode) presetSel = (presetSel + d).coerceIn(0, 7)
        else if (now.queue.isNotEmpty()) {
            val from = if (npCursor >= 0) npCursor else now.index
            npCursor = (from + d).coerceIn(0, now.queue.size - 1)
            npCursorUntil = SystemClock.uptimeMillis() + 4000
        }
        dirty = true
    }

    /** Haptic pulse for a key on the body. */
    fun buttonFeedback(strong: Boolean = false) = activity.pressFeedback(strong)

    /** A tap on the glass: lights the backlight and wakes the player, nothing else. */
    fun touchLcd() { wakeUp() }

    /** The pinhole on the body. Locked or not, it always offers the way out. */
    fun exitRequested() {
        wakeUp()
        if (!unlocked) { activity.exitRiot(); return }
        if (!bootDone()) return
        msg = null
        if (stack.lastOrNull() is DialogPage && (stack.last() as DialogPage).isExit) return
        openExit()
    }

    fun keyDown(k: Key) {
        if (k == Key.VOL_UP || k == Key.VOL_DOWN) {
            if (locked) { flash("LOCK"); return }
            wakeUp()
            media.adjustVolume(if (k == Key.VOL_UP) 1 else -1)
            vol = media.volume()
            volUntil = SystemClock.uptimeMillis() + VOLUME_POPUP_MS
            dirty = true
            return
        }
        if (wakeUp()) { downAt.remove(k); return }
        if (locked) { flash("LOCK"); return }
        downAt[k] = SystemClock.uptimeMillis()
    }

    fun keyUp(k: Key) {
        val t0 = downAt.remove(k) ?: return
        val wasHeld = held.remove(k)
        if (wasHeld) return
        if (SystemClock.uptimeMillis() - t0 >= 0) press(k)
    }

    /** Called from tick for keys still down: hold actions (scan, seek, save preset). */
    private fun holds(nowMs: Long) {
        for ((k, t0) in downAt.toMap()) {
            val dt = nowMs - t0
            when (k) {
                Key.FWD, Key.REW -> if (dt > 600) {
                    val first = held.add(k)
                    if (prefs.radioMode && stack.isEmpty()) {
                        if (first) { fm.seek(k == Key.FWD); tunedAt = 0 }
                    } else if (stack.isEmpty() || stack.last().overlay) {
                        media.seekBy(if (k == Key.FWD) 4000 else -4000)
                    }
                }
                Key.SELECT -> if (dt > 1000 && prefs.radioMode && stack.isEmpty() && held.add(k)) {
                    savePreset(presetSel)
                }
                Key.MENU -> if (dt > 2500 && held.add(k)) openExit()
                else -> {}
            }
        }
    }

    private fun press(k: Key) {
        lastInput = SystemClock.uptimeMillis()
        dirty = true
        if (!bootDone()) return
        if (!unlocked) { activity.exitRiot(); return }
        if (usbScreen) { usbScreen = false; return }
        msg?.let {
            val a = msgAction
            msg = null; msgAction = null
            if (k == Key.SELECT && a != null) a()
            return
        }
        when (k) {
            Key.PLAY -> {
                if (prefs.radioMode) { if (fm.state.on) fm.powerOff() else radioOn() }
                else media.playPause()
                return
            }
            Key.STOP -> {
                if (prefs.radioMode) fm.powerOff() else media.stop()
                return
            }
            Key.FWD, Key.REW -> {
                val d = if (k == Key.FWD) 1 else -1
                if (prefs.radioMode && (stack.isEmpty() || stack.last().overlay)) tune(fmKHz + d * 100)
                else if (d > 0) media.next() else media.prev()
                return
            }
            Key.MENU -> {
                if (stack.isNotEmpty()) goHome() else push(mainMenu(slide = true))
                return
            }
            else -> {}
        }
        val top = stack.lastOrNull()
        if (top != null) {
            if (!top.key(k) && k == Key.BACK) pop()
            return
        }
        // Home screens.
        if (prefs.radioMode) {
            if (k == Key.SELECT) {
                val f = prefs.presets()[presetSel]
                if (f > 0) tune(f)
            } else if (k == Key.BACK) {
                fmInfo = !fmInfo
            }
        } else {
            when (k) {
                Key.BACK -> showInfo = !showInfo
                Key.SELECT -> {
                    if (npCursor >= 0 && npCursor != now.index) { media.jumpTo(npCursor); npCursor = -1 }
                    else if (!now.playing) media.play()
                }
                else -> {}
            }
        }
    }

    // ---- ticking -------------------------------------------------------------------------------

    fun tick() {
        val t = SystemClock.uptimeMillis()
        holds(t)
        if (t - lastPoll > 500) {
            lastPoll = t
            val wantQueue = !prefs.radioMode && stack.isEmpty()
            val n = media.snapshot(wantQueue).let { if (wantQueue) it else it.copy(queue = now.queue) }
            if (n.index != now.index || n.mediaId != now.mediaId) { played.add(now.index); dirty = true }
            if (n != now) dirty = true
            now = n
            format = RiotSystem.global(activity, "hione_now_playing_format")
            batt = RiotSystem.battery(activity)
            val usb = RiotSystem.usbTransfer(activity)
            if (usb != usbWas) { usbWas = usb; usbScreen = usb; dirty = true }
            vol = media.volume()
            if (volUntil in 1 until t) { volUntil = 0; dirty = true }
            if (npCursor >= 0 && t > npCursorUntil) { npCursor = -1; dirty = true }
            if (msg != null && t > msgUntil) { msg = null; dirty = true }
            if (lockFlashUntil in 1 until t) { lockFlashUntil = 0; dirty = true }
            // Power Saver: sleep after the set idle time, never while music plays.
            val idle = t - lastInput
            val limit = RiotPrefs.POWER_SAVER_MS[prefs.powerSaver.coerceIn(0, 4)]
            val busy = now.playing || (prefs.radioMode && fm.state.on)
            if (!asleep && !busy && idle > limit) { asleep = true; activity.setPowerSaving(true); dirty = true }
        }
        // Blink, slide and backlight edges need frames even with no new data.
        val wasLit = lastLit
        lastLit = lit
        if (wasLit != lastLit) dirty = true
        if (stack.isNotEmpty() && (t / 300) % 2 != lastBlink) { lastBlink = (t / 300) % 2; dirty = true }
        if (!bootDone() || animating || stack.lastOrNull() is NameEntryPage) dirty = true
        if (dirty) render(t)
    }

    private var lastLit = false
    private var lastBlink = 0L

    private fun bootDone() = SystemClock.uptimeMillis() - bootStart > BOOT_MS

    // ---- drawing -------------------------------------------------------------------------------

    private fun render(t: Long) {
        dirty = false
        animating = false
        val c = lcd
        c.clear()
        when {
            asleep -> {}
            !bootDone() -> drawBoot(c, t - bootStart)
            !unlocked -> drawLocked(c)
            else -> {
                val top = stack.lastOrNull()
                if (top == null && usbScreen) {
                    drawUsb(c)
                } else if (top == null || top.overlay) {
                    if (prefs.radioMode) drawFm(c) else drawNowPlaying(c, t)
                    if (top != null) { c.dim(); top.draw(c, t) }
                } else {
                    top.draw(c, t)
                }
                // The home screens carry their own volume bar; anywhere else it pops up briefly.
                if (volUntil > t && (top != null || usbScreen)) drawVolumePopup(c)
                msg?.let { c.message(it, msgTitle) }
                if (lockFlashUntil > 0) drawLockFlash(c)
            }
        }
        frame.intValue++
    }

    private fun drawBoot(c: Lcd, dt: Long) {
        if (dt < 400) return
        // The firmware's own boot logo (240 x 55), a little above the middle, status bottom left.
        RiotAssets.logo?.let { c.sprite(it, (W - it.w) / 2, (H - it.h) / 2 - 8) }
        val status = if (dt < 1300) "Battery Manager Setup" else "Hard Disk Setup"
        c.text(RiotAssets.small, 4, H - 15, status)
    }

    private fun drawLocked(c: Lcd) {
        RiotAssets.logo?.let { c.sprite(it, (W - it.w) / 2, 22) }
        c.textCenter(RiotAssets.medium, W / 2, 96, "Not yet. Keep playing.", INK, bold = true)
        c.textCenter(RiotAssets.small, W / 2, 118, "Press any button to go back.")
    }

    /** Connected to a computer for file transfer: the firmware's USB picture, as the Riot showed. */
    private fun drawUsb(c: Lcd) {
        val icon = RiotAssets.icons["usb_connected"]
        if (icon != null) c.sprite(icon, (W - icon.w) / 2, 52)
        c.textCenter(RiotAssets.medium, W / 2, 82, "USB Connected", INK, bold = true)
        c.textCenter(RiotAssets.small, W / 2, 100, "Press any button to keep listening.")
    }

    private fun drawVolumePopup(c: Lcd) {
        val w = 14 + 10 * 5 + 10
        val x = (W - w) / 2
        c.round(x - 1, 63, w + 2, 25, 6, fill = CLEAR, line = CLEAR)
        c.round(x, 64, w, 23, 6, fill = CLEAR, line = INK)
        c.volume(x + 6, 70, vol.first, vol.second)
    }

    private fun drawLockFlash(c: Lcd) {
        val f = RiotAssets.medium
        val w = Lcd.measure(f, "LOCK", true) + 26
        val x = (W - w) / 2
        c.round(x, 60, w, 22, 6, fill = CLEAR, line = INK)
        c.sprite(RiotAssets.icons["lock"], x + 6, 67)
        c.text(f, x + 16, 65, "LOCK", INK, bold = true)
    }

    private fun mmss(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun drawNowPlaying(c: Lcd, t: Long) {
        val large = RiotAssets.large
        val med = RiotAssets.medium
        val tiny = RiotAssets.tiny
        // Title, clipped at the edge like the real screen.
        val title = when {
            !media.connected -> "${Mascot.musicAppName} is off"
            now.loaded -> now.title.ifBlank { "Untitled" }
            else -> "No music loaded"
        }
        c.textClip(large, 2, 4, W - 4, title)
        // Big elapsed time, small remaining time over the play-mode tags.
        val tx = c.text(large, 2, 28, mmss(now.positionMs)) + 3
        if (now.durationMs > 0) c.text(tiny, tx, 33, "-" + mmss(now.durationMs - now.positionMs))
        var gx = tx
        if (now.shuffle) gx = c.tag(gx, 40, "RND") + 2
        when (now.repeat) {
            Player.REPEAT_MODE_ALL -> c.tag(gx, 40, "RPT")
            Player.REPEAT_MODE_ONE -> c.tag(gx, 40, "RPT 1")
        }
        c.volume(118, 34, vol.first, vol.second)
        val icon = when {
            now.playing -> "play"
            now.positionMs > 0 -> "pause"
            else -> "stop"
        }
        val sp = RiotAssets.icons[icon]
        if (sp != null) c.sprite(sp, W - 4 - sp.w, 31 + (15 - sp.h) / 2)
        c.text(med, 3, 51, "Upcoming Tracks", INK, bold = true)
        c.battery(W - 3, 53, batt)
        c.hline(0, W - 1, 66)
        if (showInfo) { drawTrackInfo(c); return }
        val q = now.queue
        if (q.isEmpty()) {
            c.text(RiotAssets.small, 8, 74, if (media.connected) "Press MENU and pick some music." else "Start ${Mascot.musicAppName}, then come back.")
            return
        }
        val cur = now.index.coerceIn(0, q.size - 1)
        val rows = 5
        val sel = if (npCursor >= 0) npCursor else cur
        // Two played tracks above the current one, like the guide's drawing.
        val first = if (npCursor >= 0) RiotUi.follow(sel, (cur - 2).coerceAtLeast(0), rows, q.size)
                    else (cur - 2).coerceIn(0, maxOf(0, q.size - rows))
        val checks = (0 until cur).toSet()
        c.list(1, 70, W - 2, rows, 15, q, sel, first, checks = checks, f = med)
    }

    private fun drawTrackInfo(c: Lcd) {
        val f = RiotAssets.small
        c.fill(0, 67, W, H - 67, CLEAR)
        val s = now.mediaId.toLongOrNull()?.let { lib.song(it) }
        val codec = s?.mime?.substringAfter('/')?.uppercase()?.replace("MPEG", "MP3")?.replace("X-", "") ?: "?"
        val lines = listOf(
            "Artist: " + now.artist.ifBlank { s?.artist ?: "" },
            "Album: " + now.album.ifBlank { s?.album ?: "" },
            "Codec: $codec",
            "Bitrate: " + (s?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "?"),
            "Length: " + (if (now.durationMs > 0) mmssShort(now.durationMs) else "?"),
            "File Size: " + (s?.size?.let { "$it bytes" } ?: "?"),
            "Output: " + (format?.takeIf { it.isNotBlank() } ?: "not published")
        )
        lines.forEachIndexed { i, l -> c.textClip(f, 6, 70 + i * 12, W - 10, l) }
    }

    private fun mmssShort(ms: Long): String { val s = ms / 1000; return "${s / 60}:" + String.format(Locale.US, "%02d", s % 60) }

    private fun drawFm(c: Lcd) {
        val large = RiotAssets.large
        val tiny = RiotAssets.tiny
        val lo = 87500; val hi = 108000
        fun fx(k: Int) = 6 + ((k - lo).toLong() * (W - 13) / (hi - lo)).toInt()
        // The dial: ticks every MHz, numbers every 4, a pointer over the station.
        c.hline(fx(lo), fx(hi), 4)
        for (m in 88..108) {
            val x = fx(m * 1000)
            val major = (m - 88) % 4 == 0
            c.vline(x, 4, if (major) 7 else 6)
            if (major) c.textCenter(tiny, x + 1, 9, m.toString())
        }
        c.sprite(RiotAssets.icons["pointer"], fx(fmKHz) - 2, 0)
        val freq = String.format(Locale.US, "%.1f", fmKHz / 1000.0)
        val x = c.text(large, 2, 16, freq) + 3
        val st = fm.state
        // TUNED as on the Riot; under it, stacked like Now Playing's time over RND, what the
        // M500's tuner adds: STEREO or MONO when the FM app reports it, AD during an ad break.
        val extra = when {
            !st.tuned -> null
            st.adBreak == true -> "AD"
            st.stereo == true -> "STEREO"
            st.stereo == false -> "MONO"
            else -> null
        }
        if (st.tuned) c.tag(x, if (extra != null) 18 else 27, "TUNED")
        if (extra != null) c.tag(x, 27, extra)
        c.volume(118, 22, vol.first, vol.second)
        val fmx = W - 3 - Lcd.measure(large, "FM")
        c.text(large, fmx, 16, "FM")
        c.text(tiny, fmx + 1, 37, "TUNER")
        c.text(RiotAssets.medium, 3, 47, "Radio Presets", INK, bold = true)
        c.battery(W - 3, 49, batt)
        c.hline(0, W - 1, 62)
        if (fmInfo) { drawFmInfo(c); return }
        val presets = prefs.presets()
        val labels = presets.mapIndexed { i, f ->
            "Preset ${i + 1} - " + if (f > 0) String.format(Locale.US, "%5.1f FM", f / 1000.0) else "empty"
        }
        val line = fmLine()
        val rows = if (line != null) 5 else 6
        c.list(1, 66, W - 2, rows, 15, labels, presetSel, RiotUi.follow(presetSel, 0, rows, 8), f = RiotAssets.medium)
        if (line != null) {
            // What the station is sending, under a rule, in the small face of the detail block.
            c.hline(0, W - 1, 143)
            marquee(c, RiotAssets.small, 3, 147, W - 6, line)
        }
    }

    /** The decoded line under the presets: station name, then the song or RadioText. */
    private fun fmLine(): String? {
        val st = fm.state
        if (!st.on) return null
        val name = (st.ps ?: st.station).trim().takeIf { it.isNotBlank() && !it.endsWith(" FM") }
        val body = st.song ?: st.rt
        return listOfNotNull(name, body).joinToString("  ").ifBlank { null }
    }

    /** Text wider than [w] scrolls left, pauses at each end, and starts over. */
    private fun marquee(c: Lcd, f: BitmapFont, x: Int, y: Int, w: Int, s: String, lvl: Int = INK) {
        val tw = Lcd.measure(f, s)
        if (tw <= w) { c.text(f, x, y, s, lvl); return }
        val over = tw - w
        val ms = SystemClock.uptimeMillis()
        val cycle = 1500 + over * 40L + 1500
        val p = ms % cycle
        val off = when {
            p < 1500 -> 0
            p < 1500 + over * 40L -> ((p - 1500) / 40).toInt()
            else -> over
        }
        c.clip(x, y, x + w, y + f.height + 2)
        c.text(f, x - off, y, s, lvl)
        c.unclip()
        animating = true
    }

    /**
     * Back on the FM screen. The Riot had no RDS; this borrows the layout of its own track detail
     * block (Back on Now Playing) for what the M500's tuner adds.
     */
    private fun drawFmInfo(c: Lcd) {
        val f = RiotAssets.small
        c.fill(0, 63, W, H - 63, CLEAR)
        val st = fm.state
        val lines = mutableListOf<String>()
        lines += "Station: " + when {
            !st.connected -> "the FM app isn't answering"
            !st.on -> "tuner off"
            else -> listOfNotNull(st.ps ?: st.station, st.pty ?: st.genre).joinToString(", ")
        }
        if (st.on) {
            st.song?.let { lines += "Song: $it" }
            st.songIdStatus?.takeIf { st.songIdTitle == null }?.let { lines += "Song ID: $it" }
            st.rt?.takeIf { it != st.song }?.let { lines += c.wrap(f, "Text: $it", W - 12).take(3) }
            if (st.adBreak == true) lines += "Ad break"
            val sig = listOfNotNull(
                when (st.stereo) { true -> "Stereo"; false -> "Mono"; null -> null },
                st.rssi?.let { "$it dBuV" }
            )
            if (sig.isNotEmpty()) lines += "Signal: " + sig.joinToString(", ")
        }
        lines += "Frequency: " + String.format(Locale.US, "%.1f MHz", fmKHz / 1000.0)
        lines += "Antenna: " + if (fm.hasAntenna()) "headphone cable" else "none, plug in wired headphones"
        st.error?.let { lines += "Tuner: $it" }
        lines.take(7).forEachIndexed { i, l -> c.textClip(f, 6, 67 + i * 12, W - 10, l) }
    }

    // ---- radio actions ---------------------------------------------------------------------------

    /** Tunes through the FM app's session; that also powers the tuner on, as tuning did on the Riot. */
    private fun tune(khz: Int) {
        val k = khz.coerceIn(87500, 108000)
        fmKHz = k; prefs.fmKHz = k
        tunedAt = SystemClock.uptimeMillis()
        if (fm.state.connected) { antennaCheck(); fm.tune(k) } else { tuneOnConnect = true; fm.connect() }
        dirty = true
    }

    private fun radioOn() {
        if (!fm.state.connected) { tuneOnConnect = true; fm.connect(); return }
        antennaCheck()
        fm.tune(fmKHz)
    }

    /** The M500's antenna is the headphone cable. The Riot's was too, but it never said so. */
    private fun antennaCheck() {
        if (msg == null && !fm.hasAntenna()) flash("Plug in wired headphones. They are the FM antenna.", "FM Tuner")
    }

    /** Session news: follow the tuner's frequency (seek lands somewhere new), tune if asked to. */
    private fun onFm() {
        val st = fm.state
        if (st.connected && tuneOnConnect) {
            tuneOnConnect = false
            if (prefs.radioMode) { antennaCheck(); fm.tune(fmKHz) }
        }
        if (st.connected && st.on && st.khz in 87500..108000 && st.khz != fmKHz &&
            SystemClock.uptimeMillis() - tunedAt > 1500) {
            fmKHz = st.khz; prefs.fmKHz = st.khz
        }
        // First visit: fill empty preset slots from the FM app's own starred stations.
        if (st.presets.isNotEmpty() && !prefs.presetsSeeded) {
            prefs.presetsSeeded = true
            if (prefs.presets().all { it == 0 }) st.presets.take(8).forEachIndexed { i, k -> prefs.setPreset(i, k) }
        }
        // Once the FM app takes presets: hand it the slots saved before it could, once.
        if (!prefs.presetsSynced && fm.handOver(prefs.presets())) prefs.presetsSynced = true
        dirty = true
    }

    private fun enterRadio() {
        prefs.radioMode = true
        media.stop()
        fmInfo = false
        tuneOnConnect = true
        if (fm.state.connected) onFm() else fm.connect()
        goHome()
    }

    private fun enterPlayer() {
        prefs.radioMode = false
        fm.powerOff()
        goHome()
    }

    /** The silver button: leave Riot mode. The radio goes off with it, music keeps playing. */
    fun powerButton() {
        if (prefs.radioMode) fm.powerOff()
        activity.leave()
    }

    private fun savePreset(i: Int) {
        val cur = prefs.presets()[i]
        if (cur > 0 && cur != fmKHz) {
            flash("Overwrite Preset?\nPress SELECT to save.", "Preset ${i + 1}") { setSlot(i, fmKHz); dirty = true }
        } else setSlot(i, fmKHz)
        dirty = true
    }

    /**
     * Save (or with 0, clear) a slot, and mirror it into the FM app's presets when the FM app
     * takes them: the new frequency is added, and the old one removed unless another slot holds it.
     */
    private fun setSlot(i: Int, khz: Int) {
        val old = prefs.presets()[i]
        prefs.setPreset(i, khz)
        if (old == khz) { fm.setFavorite(khz, true); return }
        if (old > 0 && old !in prefs.presets()) fm.setFavorite(old, false)
        if (khz > 0) fm.setFavorite(khz, true)
    }

    // ---- loading music -------------------------------------------------------------------------

    /** Loads [songs] into Hione Music and shows Now Playing, ready for SELECT or PLAY. */
    private fun loadSongs(songs: List<Song>, start: Int = 0) {
        if (songs.isEmpty()) { flash("No songs match."); return }
        if (prefs.radioMode) enterPlayer()
        if (!media.load(songs.map { it.id }, start)) {
            flash("${Mascot.musicAppName} isn't answering. Open it once, then try again.")
            return
        }
        played.clear()
        goHome()
    }

    // ---- the menu tree (guide p39) ---------------------------------------------------------------

    private fun mainMenu(slide: Boolean): MenuPage = MenuPage("Main Menu", {
        listOf(
            MenuItem("Play Music") { push(playMusicMenu()) },
            if (prefs.radioMode) MenuItem("Player") { enterPlayer() } else MenuItem("Radio") { enterRadio() },
            MenuItem("Equalizer") { push(MenuPage("Equalizer", { listOf(MenuItem("Bass/Treble") { push(bassTreble()) }) })) },
            MenuItem("Organize") { push(if (prefs.radioMode) organizeRadio() else organizeMenu()) },
            MenuItem("Preferences") { push(preferencesMenu()) }
        )
    }, slide)

    private fun needLibrary(): Boolean {
        if (!lib.loaded) { flash(if (hasAudioPermission()) "Reading your music..." else "Riot mode needs to read your music. Allow it, then try again."); loadLibrary(); return false }
        return true
    }

    private fun playMusicMenu() = MenuPage("Play Music", {
        listOf(
            MenuItem("Rio DJ") { push(rioDjMenu()) },
            MenuItem("Albums") { if (needLibrary()) push(letterPage("Play Music", LetterPage.Kind.ALBUMS, LetterPage.Mode.PLAY)) },
            MenuItem("Artists") { if (needLibrary()) push(letterPage("Play Music", LetterPage.Kind.ARTISTS, LetterPage.Mode.PLAY)) },
            MenuItem("Genre") { if (needLibrary()) push(genrePage()) },
            MenuItem("Songs") { if (needLibrary()) push(letterPage("Play Music", LetterPage.Kind.SONGS, LetterPage.Mode.PLAY)) },
            MenuItem("Favorites") { push(favoritesMenu()) },
            MenuItem("Playlists") { push(playlistsPage()) }
        )
    })

    /** Picks route by mode: play it, add it to the playlist being built, or "delete" it. */
    private var draft: Playlist? = null
    private var draftIsNew = false

    private fun letterPage(parent: String, kind: LetterPage.Kind, mode: LetterPage.Mode): LetterPage {
        val count: (() -> String?)? = if (mode == LetterPage.Mode.ADD) ({ draftCount() }) else null
        return LetterPage(parent, kind, lib, mode, count) { page, pick -> onPick(page, kind, mode, pick) }
    }

    private fun draftCount(): String? = draft?.let { "There are ${it.ids.size} tracks in ${it.name}." }

    @Suppress("UNCHECKED_CAST")
    private fun onPick(page: Page, kind: LetterPage.Kind, mode: LetterPage.Mode, pick: Any) {
        when (pick) {
            is List<*> -> { // the "Play All" / "Add All" row
                val songs = (pick as List<Any>).flatMap { songsOf(it) }
                act(mode, songs, 0, page, "these")
            }
            is Album -> if (mode == LetterPage.Mode.DELETE) confirmDelete(pick.name) else push(albumPage(pick, mode))
            is Artist -> if (mode == LetterPage.Mode.DELETE) confirmDelete(pick.name) else push(artistPage(pick, mode))
            is Song -> if (mode == LetterPage.Mode.DELETE) confirmDelete(pick.title) else {
                if (mode == LetterPage.Mode.PLAY) {
                    // A single song plays in its letter's list, so playback carries on.
                    val all = lib.songs.filter { lib.letterOf(it.title) == lib.letterOf(pick.title) }
                    act(mode, all, all.indexOf(pick).coerceAtLeast(0), page, pick.title)
                } else act(mode, listOf(pick), 0, page, pick.title)
            }
        }
    }

    private fun songsOf(o: Any): List<Song> = when (o) {
        is Album -> o.songs; is Artist -> o.albums.flatMap { it.songs }; is Song -> listOf(o); else -> emptyList()
    }

    private fun act(mode: LetterPage.Mode, songs: List<Song>, start: Int, page: Page, what: String) {
        when (mode) {
            LetterPage.Mode.PLAY -> loadSongs(songs, start)
            LetterPage.Mode.ADD -> {
                val d = draft ?: return
                var added = 0
                for (s in songs) if (s.id !in d.ids) { d.ids.add(s.id); added++ }
                flash(if (added == 1) "Added." else "Added $added tracks.", ms = 900)
            }
            LetterPage.Mode.DELETE -> confirmDelete(what)
        }
    }

    private fun albumPage(a: Album, mode: LetterPage.Mode): ListPage {
        val all = if (mode == LetterPage.Mode.ADD) "Add All Tracks" else "Play All Tracks"
        lateinit var page: ListPage
        page = ListPage("Albums", lib.display(a.name), { "${a.songs.size} Tracks on ${lib.display(a.name)}" }, {
            listOf(Row(all, bold = true) { act(mode, a.songs, 0, page, a.name) }) +
                a.songs.mapIndexed { i, s -> Row("•" + s.title) { act(mode, if (mode == LetterPage.Mode.PLAY) a.songs else listOf(s), if (mode == LetterPage.Mode.PLAY) i else 0, page, s.title) } }
        }, topRight = if (mode == LetterPage.Mode.ADD) ({ draftCount() }) else null)
        return page
    }

    private fun artistPage(a: Artist, mode: LetterPage.Mode): ListPage {
        val all = if (mode == LetterPage.Mode.ADD) "Add All Albums/Tracks" else "Play All Albums/Tracks"
        lateinit var page: ListPage
        page = ListPage("Artists", lib.display(a.name), { "${a.albums.size} Albums by ${lib.display(a.name)}" }, {
            listOf(Row(all, bold = true) { act(mode, a.albums.flatMap { it.songs }, 0, page, a.name) }) +
                a.albums.map { al -> Row("•" + lib.display(al.name)) { push(albumPage(al, mode)) } }
        }, topRight = if (mode == LetterPage.Mode.ADD) ({ draftCount() }) else null)
        return page
    }

    private fun genrePage(): ListPage {
        val genres = lib.genres()
        val picked = HashSet<Int>()
        lateinit var page: ListPage
        page = ListPage("Play Music", "Genre", { "${genres.size} Genres on Player" }, {
            genres.mapIndexed { i, g -> Row(g.name) { if (!picked.add(i)) picked.remove(i); dirty = true } } +
                Row("Done", bold = true) {
                    val songs = picked.sorted().flatMap { lib.genreSongs(genres[it]) }.distinctBy { it.id }
                    loadSongs(songs)
                }
        }, checked = { picked })
        return page
    }

    private fun playlistsPage(): ListPage {
        val pls = prefs.playlists()
        return ListPage("Play Music", "Playlists", { "${pls.size} Playlists on Player" }, {
            if (pls.isEmpty()) listOf(Row("No playlists yet. Make one in Organize."))
            else pls.map { pl -> Row(pl.name) { loadSongs(pl.ids.mapNotNull { lib.song(it) }) } }
        })
    }

    private fun favoritesMenu() = MenuPage("Favorites", {
        listOf(
            MenuItem("Albums") {
                if (!needLibrary()) return@MenuItem
                val fav = lib.favoriteAlbums()
                lateinit var page: ListPage
                page = ListPage("Favorites", "Albums", { "${fav.size} Favorite Albums" }, {
                    if (fav.isEmpty()) listOf(Row("Nothing yet. Play some music in Riot mode first."))
                    else listOf(Row("Play All Albums/Tracks", bold = true) { loadSongs(fav.flatMap { it.songs }) }) +
                        fav.map { a -> Row("•" + lib.display(a.name)) { push(albumPage(a, LetterPage.Mode.PLAY)) } }
                })
                push(page)
            },
            MenuItem("Artists") {
                if (!needLibrary()) return@MenuItem
                val fav = lib.favoriteArtists()
                push(ListPage("Favorites", "Artists", { "${fav.size} Favorite Artists" }, {
                    if (fav.isEmpty()) listOf(Row("Nothing yet. Play some music in Riot mode first."))
                    else fav.map { a -> Row("•" + lib.display(a.name)) { push(artistPage(a, LetterPage.Mode.PLAY)) } }
                }))
            },
            MenuItem("Songs") {
                if (!needLibrary()) return@MenuItem
                val fav = lib.topSongs(40)
                push(ListPage("Favorites", "Songs", { "${fav.size} Favorite Songs" }, {
                    if (fav.isEmpty()) listOf(Row("Nothing yet. Play some music in Riot mode first."))
                    else listOf(Row("Play All Tracks", bold = true) { loadSongs(fav) }) +
                        fav.mapIndexed { i, s -> Row("•" + s.title) { loadSongs(fav, i) } }
                }))
            }
        )
    })

    // ---- Rio DJ ----------------------------------------------------------------------------------

    private val LENGTHS = listOf("15 minutes", "30 minutes", "45 minutes", "1 hour", "2 hours", "4 hours", "8 hours")
    private val LENGTH_MIN = listOf(15, 30, 45, 60, 120, 240, 480)
    private val SPANS = listOf("1 day", "1 week", "2 weeks", "1 month", "3 months", "6 months", "1 year")
    private val SPAN_DAYS = listOf(1, 7, 14, 30, 91, 182, 365)

    private fun dj(item: String, rows: List<DRow>, build: () -> List<Song>) =
        DialogPage("Rio DJ", item, item, rows) { _, b ->
            if (b == 0) { if (needLibrary()) loadSongs(build()) } else pop()
        }

    private fun rioDjMenu() = MenuPage("Rio DJ", {
        listOf(
            MenuItem("Entertain Me!") {
                val ch = Choice(LENGTHS + "Everything", 0)
                push(dj("Entertain Me!", listOf(DRow.Text("Play a mix of music that lasts"), DRow.Boxes("about", listOf(ch)))) {
                    lib.entertainMe(LENGTH_MIN.getOrElse(ch.index) { 0 })
                })
            },
            MenuItem("Play All") {
                push(dj("Play All", listOf(DRow.Text("Play every song on the player.")) ) { lib.playAll() })
            },
            MenuItem("Top Tunes") {
                val ch = Choice(listOf("10", "25", "50", "100", "250"), 0)
                push(dj("Top Tunes", listOf(DRow.Text("Play the songs you play the most."), DRow.Boxes("Play", listOf(ch), "tracks"))) {
                    lib.topSongs(ch.label.toInt())
                })
            },
            MenuItem("New Music") {
                val ch = Choice(SPANS, 0)
                push(dj("New Music", listOf(DRow.Text("Play the songs loaded on the player in the last"), DRow.Boxes("", listOf(ch)))) {
                    lib.newMusic(SPAN_DAYS[ch.index])
                })
            },
            MenuItem("Memory Lane") {
                val ch = Choice(SPANS, 0)
                push(dj("Memory Lane", listOf(DRow.Text("Play the songs you have played least in the last"), DRow.Boxes("", listOf(ch)))) {
                    lib.memoryLane(SPAN_DAYS[ch.index])
                })
            },
            MenuItem("Sounds Of...") {
                val year = Calendar.getInstance().get(Calendar.YEAR)
                val decades = (1940..(year / 10 * 10) step 10).map { "${it}'s" }
                val years = (1950..year).map { it.toString() }
                val ch = Choice(decades + years, 0)
                push(dj("Sounds Of...", listOf(DRow.Text("Play the music of"), DRow.Boxes("the", listOf(ch)))) {
                    val v = ch.label
                    if (v.endsWith("'s")) lib.soundsOf(v.removeSuffix("'s").toInt(), null) else lib.soundsOf(null, v.toInt())
                })
            },
            MenuItem("Random Play") {
                val ch = Choice(LENGTHS, 0)
                push(dj("Random Play", listOf(DRow.Text("Play a random mix that lasts"), DRow.Boxes("about", listOf(ch)))) {
                    lib.randomPlay(LENGTH_MIN[ch.index])
                })
            }
        )
    })

    // ---- Equalizer -------------------------------------------------------------------------------

    private fun bassTreble() = SliderPage("Equalizer", "Bass Treble", "Bass/Treble", listOf("Bass", "Treble"),
        intArrayOf(prefs.bass, prefs.treble), -6, 6, onLive = {}) { v ->
        if (v != null) {
            prefs.bass = v[0]; prefs.treble = v[1]
            pop()
            flash("Saved. ${Mascot.musicAppName}'s own EQ shapes the sound. This one is only for the look.", ms = 2600)
        } else pop()
    }

    // ---- Organize --------------------------------------------------------------------------------

    private fun organizeMenu() = MenuPage("Organize", {
        listOf(
            MenuItem("My Playlists") { push(myPlaylistsMenu()) },
            MenuItem("Delete...") { push(deleteMenu()) }
        )
    })

    private fun organizeRadio() = MenuPage("Organize", {
        listOf(
            MenuItem("Select Preset") { push(presetList("Select Preset") { i -> prefs.presets()[i].takeIf { it > 0 }?.let { tune(it); presetSel = i; goHome() } }) },
            MenuItem("Set Preset") { push(presetList("Set Preset") { i -> savePreset(i); presetSel = i; goHome() }) },
            MenuItem("Clear Preset") { push(presetList("Clear Preset") { i -> setSlot(i, 0); goHome() }) }
        )
    })

    private fun presetList(title: String, onPick: (Int) -> Unit): ListPage = ListPage("Organize", title, { "Radio Presets" }, {
        prefs.presets().mapIndexed { i, f ->
            Row("Preset ${i + 1} - " + if (f > 0) String.format(Locale.US, "%.1f FM", f / 1000.0) else "empty") { onPick(i) }
        }
    })

    private fun myPlaylistsMenu() = MenuPage("My Playlists", {
        listOf(
            MenuItem("Create") {
                if (!needLibrary()) return@MenuItem
                push(NameEntryPage("") { name ->
                    pop()
                    if (name != null) { draft = Playlist(name, mutableListOf()); draftIsNew = true; push(addTracksMenu()) }
                })
            },
            MenuItem("Edit") {
                push(MenuPage("Edit", {
                    listOf(
                        MenuItem("Add Tracks") {
                            if (!needLibrary()) return@MenuItem
                            push(pickPlaylist("Add Tracks") { pl -> draft = Playlist(pl.name, pl.ids.toMutableList()); draftIsNew = false; push(addTracksMenu()) })
                        },
                        MenuItem("Delete Tracks") { if (needLibrary()) push(pickPlaylist("Delete Tracks") { pl -> push(deleteTracksPage(pl)) }) }
                    )
                }))
            },
            MenuItem("Delete") {
                push(pickPlaylist("Delete") { pl ->
                    push(DialogPage("My Playlists", "Delete", "Delete Playlist", listOf(DRow.Text("Delete the playlist ${pl.name}?")), listOf("Delete", "Cancel")) { _, b ->
                        if (b == 0) { val all = prefs.playlists(); all.removeAll { it.name == pl.name }; prefs.savePlaylists(all); pop(); pop(); flash("Deleted.") } else pop()
                    })
                })
            },
            MenuItem("Rename") {
                push(pickPlaylist("Rename") { pl ->
                    push(NameEntryPage(pl.name) { name ->
                        pop()
                        if (name != null) push(DialogPage("My Playlists", "Rename", "Rename Playlist", listOf(DRow.Text("Rename ${pl.name} to $name?")), listOf("Save", "Cancel")) { _, b ->
                            if (b == 0) {
                                val all = prefs.playlists(); all.firstOrNull { it.name == pl.name }?.name = name; prefs.savePlaylists(all)
                                pop(); pop(); flash("Saved.")
                            } else pop()
                        })
                    })
                })
            }
        )
    })

    private fun pickPlaylist(crumb: String, onPick: (Playlist) -> Unit): ListPage {
        val pls = prefs.playlists()
        return ListPage("My Playlists", crumb, { "${pls.size} Playlists on Player" }, {
            if (pls.isEmpty()) listOf(Row("No playlists yet.")) else pls.map { pl -> Row(pl.name) { onPick(pl) } }
        })
    }

    /** "Add Tracks": Albums / Artists / Tracks. Backing out asks to save, as the guide describes. */
    private fun addTracksMenu(): Page {
        val menu = MenuPage("Add Tracks", {
            listOf(
                MenuItem("Albums") { push(letterPage("Add Tracks", LetterPage.Kind.ALBUMS, LetterPage.Mode.ADD)) },
                MenuItem("Artists") { push(letterPage("Add Tracks", LetterPage.Kind.ARTISTS, LetterPage.Mode.ADD)) },
                MenuItem("Tracks") { push(letterPage("Add Tracks", LetterPage.Kind.SONGS, LetterPage.Mode.ADD)) }
            )
        })
        return object : Page() {
            override val overlay = true
            override fun draw(c: Lcd, t: Long) { menu.shell = shell; menu.draw(c, t) }
            override fun wheel(d: Int) = menu.wheel(d)
            override fun key(k: Key): Boolean {
                if (k == Key.BACK) { askSaveDraft(); return true }
                return menu.key(k)
            }
        }
    }

    private fun askSaveDraft() {
        val d = draft ?: run { pop(); return }
        val verb = if (draftIsNew) "created" else "changed"
        push(DialogPage("My Playlists", d.name, "Save Playlist",
            listOf(DRow.Text("You have $verb the playlist: ${d.name}. Do you want to save the changes or exit without saving?")),
            listOf("Save", "Exit")) { _, b ->
            if (b == 0) {
                val all = prefs.playlists()
                val i = all.indexOfFirst { it.name == d.name }
                if (i >= 0) all[i] = d else all.add(d)
                prefs.savePlaylists(all)
            }
            draft = null
            goHome()
            if (b == 0) flash("Saved ${d.name}.")
        })
    }

    private fun deleteTracksPage(pl: Playlist): ListPage {
        lateinit var page: ListPage
        page = ListPage("Delete Tracks", pl.name, { "${pl.ids.size} tracks in ${pl.name}" }, {
            pl.ids.mapNotNull { id -> lib.song(id)?.let { s -> Row("•" + s.title) {
                push(DialogPage("Delete Tracks", pl.name, "Delete Track", listOf(DRow.Text("Remove ${s.title} from ${pl.name}?")), listOf("Delete", "Cancel")) { _, b ->
                    if (b == 0) {
                        pl.ids.remove(id)
                        val all = prefs.playlists(); all.firstOrNull { it.name == pl.name }?.ids?.remove(id); prefs.savePlaylists(all)
                        page.reload()
                    }
                    pop()
                })
            } } }
        })
        return page
    }

    private fun deleteMenu() = MenuPage("Delete...", {
        listOf(
            MenuItem("an Album") { if (needLibrary()) push(letterPage("Delete...", LetterPage.Kind.ALBUMS, LetterPage.Mode.DELETE)) },
            MenuItem("an Artist") { if (needLibrary()) push(letterPage("Delete...", LetterPage.Kind.ARTISTS, LetterPage.Mode.DELETE)) },
            MenuItem("a Song") { if (needLibrary()) push(letterPage("Delete...", LetterPage.Kind.SONGS, LetterPage.Mode.DELETE)) },
            MenuItem("Everything!") {
                push(DialogPage("Delete...", "Everything!", "Delete Everything!",
                    listOf(DRow.Text("Remove all playlists, songs and preferences from the player?")), listOf("Delete All", "Cancel")) { _, b ->
                    if (b == 0) {
                        prefs.wipe(); lib.clearCounts()
                        goHome()
                        flash("Riot mode's playlists and settings are cleared. Your music files are all still here.", ms = 3500)
                    } else pop()
                })
            }
        )
    })

    /**
     * The original really deleted files. Riot mode never touches the library: Hione Music owns it,
     * and a nostalgia skin is the wrong place to lose an album.
     */
    private fun confirmDelete(name: String) {
        push(DialogPage("Delete...", name, "Delete File", listOf(DRow.Text("Delete $name from the player?")), listOf("Delete File", "Cancel")) { _, b ->
            pop()
            if (b == 0) flash("Riot mode leaves your files alone. Delete them in ${Mascot.musicAppName} if you mean it.", ms = 3200)
        })
    }

    // ---- Preferences -----------------------------------------------------------------------------

    private fun preferencesMenu() = MenuPage("Preferences", {
        listOf(
            MenuItem("Play Options") { push(playOptions()) },
            MenuItem("Contrast") { push(contrastPage()) },
            MenuItem("Backlight") {
                val ch = Choice(RiotPrefs.BACKLIGHT, prefs.backlight)
                push(DialogPage("Preferences", "Backlight", "Backlight", listOf(DRow.Boxes("Backlight on:", listOf(ch)))) { _, b ->
                    if (b == 0) prefs.backlight = ch.index
                    pop()
                })
            },
            MenuItem("Power Saver") {
                val ch = Choice(RiotPrefs.POWER_SAVER, prefs.powerSaver)
                push(DialogPage("Preferences", "Power Saving", "Power Saving",
                    listOf(DRow.Text("Player automatically turns"), DRow.Boxes("off", listOf(ch), "after"), DRow.Text("inactivity"))) { _, b ->
                    if (b == 0) prefs.powerSaver = ch.index
                    pop()
                })
            },
            MenuItem("Time / Date") { push(timeDate()) },
            MenuItem("Information") { push(InfoPage { infoLines() }) },
            MenuItem("\"The\" Filter") {
                val ch = Choice(listOf("Off", "On"), if (prefs.theFilter) 1 else 0)
                push(DialogPage("Preferences", "\"The\" Filter", "\"The\" Filter", listOf(DRow.Boxes("Turn \"The\" Filter", listOf(ch)))) { _, b ->
                    if (b == 0 && (ch.index == 1) != prefs.theFilter) {
                        prefs.theFilter = ch.index == 1
                        scope.launch { withContext(Dispatchers.Default) { lib.resort() }; dirty = true }
                    }
                    pop()
                })
            },
            MenuItem("Exit Riot Mode") { openExit() }
        )
    })

    private fun playOptions(): DialogPage {
        val rep = Choice(listOf("All", "Track", "Off"), when (now.repeat) { Player.REPEAT_MODE_ALL -> 0; Player.REPEAT_MODE_ONE -> 1; else -> 2 })
        val rnd = Choice(listOf("On", "Off"), if (now.shuffle) 0 else 1)
        return DialogPage("Preferences", "Play Options", "Track Play Options",
            listOf(DRow.Boxes("Repeat:", listOf(rep)), DRow.Boxes("Random:", listOf(rnd)))) { _, b ->
            if (b == 0) {
                media.setRepeat(when (rep.index) { 0 -> Player.REPEAT_MODE_ALL; 1 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF })
                media.setShuffle(rnd.index == 0)
            }
            pop()
        }
    }

    private fun contrastPage(): SliderPage {
        val before = prefs.contrast
        return SliderPage("Preferences", "Contrast", "Contrast", listOf(""), intArrayOf(before), 0, 14,
            onLive = { v -> prefs.contrast = v[0]; dirty = true }) { v ->
            prefs.contrast = v?.get(0) ?: before
            pop()
        }
    }

    /** The clock comes from Android, so the boxes show it but do not edit it. */
    private fun timeDate(): DialogPage {
        val cal = Calendar.getInstance()
        val month = Choice(listOf(SimpleDateFormat("MMMM", Locale.US).format(cal.time)), 0, editable = false)
        val day = Choice(listOf(String.format(Locale.US, "%02d", cal.get(Calendar.DAY_OF_MONTH))), 0, editable = false)
        val yr = Choice(listOf(cal.get(Calendar.YEAR).toString()), 0, editable = false)
        val hh = Choice(listOf(String.format(Locale.US, "%02d", (cal.get(Calendar.HOUR) + 11) % 12 + 1)), 0, editable = false)
        val mm = Choice(listOf(String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE))), 0, editable = false)
        val ap = Choice(listOf(if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"), 0, editable = false)
        return DialogPage("Preferences", "Time / Date", "Set Time / Date:",
            listOf(DRow.Boxes("Date:", listOf(month, day, yr)), DRow.Boxes("Time:", listOf(hh, mm, ap)),
                DRow.Text("${Mascot.osName} keeps the clock set for you."))) { _, _ -> pop() }
    }

    private fun infoLines(): Pair<String, List<String>> {
        val time = SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date())
        val (used, total) = RiotSystem.disk()
        val free = total - used
        fun gb(b: Long) = String.format(Locale.US, "%.2f", b / 1_000_000_000.0)
        val avg = if (lib.songs.isNotEmpty()) lib.songs.sumOf { it.size } / lib.songs.size else 8_000_000L
        val room = if (avg > 0) free / avg else 0
        val ver = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "?"
        return time to listOf(
            "Firmware Version: $ver",
            "Total Storage: ${gb(total)} Gbytes",
            "Free Storage: ${gb(free)} Gbytes",
            "Supports: MP3, FLAC, WAV, AAC and more",
            "${lib.albums.size} Albums and ${lib.songs.size} tracks in use",
            "(room for approx. $room more tracks)",
            "",
            TRIBUTE
        )
    }

    fun openExit() {
        val home = RiotSystem.isDefaultHome(activity)
        val text = (if (home) "Go back to the ${Mascot.name} home screen. Riot mode stops being your launcher." else "Go back to the ${Mascot.name} home screen.") +
            "\n\n" + TRIBUTE
        push(DialogPage("Preferences", "Exit Riot Mode", "Exit Riot Mode", listOf(DRow.Text(text)), listOf("Exit", "Cancel")) { _, b ->
            if (b == 0) { if (prefs.radioMode) fm.powerOff(); activity.exitRiot() } else pop()
        }.apply { isExit = true })
    }

    companion object {
        const val TICK_MS = 40L
        const val BOOT_MS = 2600L
        const val VOLUME_POPUP_MS = 1500L
        const val TRIBUTE = "A fan tribute. Not affiliated with or endorsed by SonicBlue, D&M Holdings, or Rio."
    }
}
