package com.hione.wheel

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A radio region, as on the 2009 nano's Radio Regions menu (range and step from Apple's table).
 * The 2006 Radio Remote had the same idea in Settings: 0.2 MHz steps in the US, 0.1 in Europe.
 */
enum class RadioRegion(val label: String, val lowKhz: Int, val highKhz: Int, val stepKhz: Int) {
    AMERICAS("Americas", 87_500, 107_900, 200),
    ASIA("Asia", 87_500, 108_000, 100),
    AUSTRALIA("Australia", 87_500, 107_900, 200),
    EUROPE("Europe", 87_500, 108_000, 100),
    JAPAN("Japan", 76_000, 90_000, 100);

    /** The nearest channel on this region's grid. */
    fun snap(khz: Int): Int {
        val n = Math.round((khz - lowKhz).toDouble() / stepKhz).toInt()
        return (lowKhz + n * stepKhz).coerceIn(lowKhz, highKhz)
    }

    companion object {
        fun byName(n: String?) = entries.firstOrNull { it.name == n } ?: AMERICAS
    }
}

fun fmtMhz(khz: Int): String = "%d.%d".format(khz / 1000, (khz % 1000) / 100)

/**
 * The FM tuner, driven only through the FM app's media session (com.caf.fmradio). HionePod never
 * opens the radio device itself: there is one tuner engine and it lives in the FM app.
 *
 * Commands used: play / stop (power), playFromMediaId("fm_freq:<kHz>") (tune, powers on),
 * the SEEK_UP / SEEK_DOWN custom actions, the TOGGLE_FAVORITE custom action when the FM app
 * offers it, and the browse tree's Presets folder.
 * Main thread only.
 */
class RadioLink(private val ctx: Context, private val prefs: WheelPrefs, private val changed: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null

    var connected = false; private set
    /** Tuner powered (playing, muted or still starting). */
    var on = false; private set
    /** Tuner up and receiving. */
    var online = false; private set
    var khz = 0; private set
    var station = ""; private set
    var text = ""; private set
    var error: String? = null; private set
    /**
     * Presets from the FM app plus any kept here. An FM app without TOGGLE_FAVORITE cannot take
     * favorites, so they live in [WheelPrefs.radioFavorites]. Once it offers the action, each one
     * kept here is handed over and dropped locally when the FM app's Presets list shows it.
     */
    var favorites: List<Int> = emptyList(); private set
    private var fmPresets: List<Int> = emptyList()
    /** The FM app's Presets list has arrived on this connection; until then it is not known. */
    private var presetsLoaded = false
    /** Wired headphones in: the cord is the antenna. */
    var antenna = false; private set

    // Tuning while the wheel spins: show the target at once, send at most every SEND_MS.
    private var target = 0
    private var targetAt = 0L
    private var sentAt = 0L
    private var sendQueued = false

    val installed: Boolean get() = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    val region: RadioRegion get() = RadioRegion.byName(prefs.radioRegion)

    /** What the dial shows: the frequency being tuned to, else the tuner's. */
    val dialKhz: Int get() =
        if (target > 0 && System.currentTimeMillis() - targetAt < 1500) target
        else if (khz > 0) khz else region.lowKhz

    fun connect() {
        if (browser != null) return
        am.registerAudioDeviceCallback(devices, main)
        checkAntenna()
        if (!installed) return
        browser = MediaBrowser(ctx, ComponentName(PKG, SERVICE), conn, null).also {
            try { it.connect() } catch (t: Throwable) { Log.w(TAG, "connect failed", t) }
        }
    }

    fun release() {
        try { am.unregisterAudioDeviceCallback(devices) } catch (_: Throwable) { }
        controller?.unregisterCallback(ctl)
        try { browser?.disconnect() } catch (_: Throwable) { }
        browser = null; controller = null; connected = false
    }

    // ---- commands ----------------------------------------------------------------------------

    /** Asked to play before the session connected: play as soon as it does. */
    private var playOnConnect = false

    fun play() {
        val c = controller
        if (c != null) c.transportControls.play() else { playOnConnect = true; connect() }
    }
    fun stop() { controller?.transportControls?.stop() }
    fun powerToggle() = if (on) stop() else play()

    /** Move [steps] channels on the region's grid. Returns true when the frequency moved. */
    fun step(steps: Int): Boolean {
        val r = region
        val from = r.snap(dialKhz)
        val to = (from + steps * r.stepKhz).coerceIn(r.lowKhz, r.highKhz)
        if (to == dialKhz) return false
        tune(to)
        return true
    }

    fun tune(k: Int) {
        target = k
        targetAt = System.currentTimeMillis()
        val wait = SEND_MS - (targetAt - sentAt)
        if (wait <= 0) send()
        else if (!sendQueued) { sendQueued = true; main.postDelayed({ sendQueued = false; send() }, wait) }
        changed()
    }

    private fun send() {
        val c = controller ?: return
        sentAt = System.currentTimeMillis()
        c.transportControls.playFromMediaId("$FREQ$target", null)
    }

    fun seek(up: Boolean) {
        controller?.transportControls?.sendCustomAction(if (up) SEEK_UP else SEEK_DOWN, null)
    }

    /** Next or previous favorite, or a seek when there are none (as on the 2009 nano). */
    fun skip(up: Boolean) {
        val favs = favorites
        if (favs.isEmpty()) { seek(up); return }
        val cur = dialKhz
        val t = if (up) favs.firstOrNull { it > cur } ?: favs.first() else favs.lastOrNull { it < cur } ?: favs.last()
        tune(t)
    }

    fun isFavorite(k: Int) = k in favorites

    /**
     * Add or remove the station as a favorite. Uses the FM app's preset action when it offers
     * one, else keeps the favorite in HionePod. Returns true if it is now a favorite.
     */
    fun toggleFavorite(k: Int): Boolean {
        val c = controller
        val add = k !in favorites
        val mine = prefs.radioFavorites.toMutableSet()
        if (c != null && fmTakesFavorites(c)) {
            sendFavorite(c, k, add)
            // A removal also covers a copy still waiting to be handed over.
            if (!add && mine.remove(k)) prefs.radioFavorites = mine
        } else {
            if (add) mine.add(k) else mine.remove(k)
            prefs.radioFavorites = mine
        }
        favorites = if (add) (favorites + k).distinct().sorted() else favorites - k
        changed()
        return add
    }

    /** The FM app's session offers the preset action (FM app builds after 0.3.0). */
    private fun fmTakesFavorites(c: MediaController) =
        c.playbackState?.customActions?.any { it.action == TOGGLE_FAVORITE } == true

    /** An explicit add or remove, so a repeat can never undo the first one. */
    private fun sendFavorite(c: MediaController, k: Int, add: Boolean) {
        c.transportControls.sendCustomAction(TOGGLE_FAVORITE, Bundle().apply {
            putInt("khz", k); putBoolean("add", add)
        })
    }

    /** Local favorites already sent to the FM app on this connection. */
    private val handedOver = HashSet<Int>()

    /**
     * Move favorites kept here into the FM app, once it can take them. One is dropped here only
     * when the FM app's Presets list shows it, so nothing is lost if a send goes missing: it is
     * sent again on the next connection.
     */
    private fun migrateFavorites() {
        val c = controller ?: return
        if (!presetsLoaded || !fmTakesFavorites(c)) return
        val mine = prefs.radioFavorites
        if (mine.isEmpty()) return
        val landed = mine.filter { it in fmPresets }
        if (landed.isNotEmpty()) {
            prefs.radioFavorites = mine - landed.toSet()
            Log.i(TAG, "favorites now in the FM app: $landed")
        }
        for (k in mine) if (k !in fmPresets && handedOver.add(k)) sendFavorite(c, k, true)
    }

    // ---- state -------------------------------------------------------------------------------

    private fun read() {
        val c = controller ?: return
        val pb = c.playbackState
        val s = pb?.state ?: PlaybackState.STATE_NONE
        on = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_BUFFERING
        online = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_PAUSED
        error = if (s == PlaybackState.STATE_ERROR) pb?.errorMessage?.toString() else null
        val md = c.metadata
        md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.removePrefix(FREQ)?.toIntOrNull()?.let { khz = it }
        station = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "state=$s on=$on online=$online khz=$khz station=$station text=$text")
        migrateFavorites()
        changed()
    }

    private fun mergeFavorites() {
        favorites = (fmPresets + prefs.radioFavorites).distinct().sorted()
        changed()
    }

    private fun checkAntenna() {
        antenna = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
        } catch (_: Throwable) { false }
        changed()
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = checkAntenna()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = checkAntenna()
    }

    private val ctl = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = read()
        override fun onMetadataChanged(metadata: MediaMetadata?) = read()
        override fun onSessionDestroyed() {
            connected = false; controller = null; changed()
        }
    }

    private val presets = object : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
            fmPresets = children.mapNotNull { it.mediaId?.removePrefix(FREQ)?.toIntOrNull() }
            presetsLoaded = true
            migrateFavorites()
            mergeFavorites()
        }
    }

    private val conn = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val b = browser ?: return
            val c = MediaController(ctx, b.sessionToken)
            controller = c
            c.registerCallback(ctl, main)
            connected = true
            presetsLoaded = false
            handedOver.clear()
            b.subscribe(PRESETS, presets)
            mergeFavorites()
            read()
            if (target > 0 && System.currentTimeMillis() - targetAt < 3000) send()
            else if (playOnConnect && !on) c.transportControls.play()
            playOnConnect = false
            Log.i(TAG, "connected to the FM session: on=$on khz=$khz")
        }

        override fun onConnectionSuspended() {
            controller?.unregisterCallback(ctl)
            controller = null; connected = false; changed()
        }

        override fun onConnectionFailed() {
            Log.w(TAG, "FM session refused the connection")
            browser = null; connected = false; changed()
        }
    }

    companion object {
        private const val TAG = "HionePodRadio"
        const val PKG = "com.caf.fmradio"
        private const val SERVICE = "com.caf.fmradio.FmMediaBrowserService"
        private const val FREQ = "fm_freq:"
        private const val PRESETS = "fm_presets"
        private const val SEEK_UP = "com.caf.fmradio.media.SEEK_UP"
        private const val SEEK_DOWN = "com.caf.fmradio.media.SEEK_DOWN"
        /** Offered by FM app builds after 0.3.0. Older ones lack it and favorites stay here. */
        const val TOGGLE_FAVORITE = "com.caf.fmradio.media.TOGGLE_FAVORITE"
        private const val SEND_MS = 120L
    }
}
