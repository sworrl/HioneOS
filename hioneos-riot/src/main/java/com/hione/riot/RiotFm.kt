package com.hione.riot

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.util.Log

/**
 * The radio, through the FM app's own media session (com.caf.fmradio/.FmMediaBrowserService).
 * Riot mode never opens the tuner itself: play() asks the FM app to power on, stop() to power
 * off, and a frequency is tuned with playFromMediaId("fm_freq:<kHz>"). The FM app stays the
 * only process that touches /dev/radio0.
 *
 * Main thread only.
 */
class RiotFm(private val ctx: Context) {

    data class State(
        val connected: Boolean = false,
        /** The tuner is powered (playing, muted or still starting). */
        val on: Boolean = false,
        /** The chip is up and audio is flowing. */
        val tuned: Boolean = false,
        val khz: Int = 0,
        /** RDS station name, or the catalogue call sign, or "101.1 FM". */
        val station: String = "",
        /** RadioText (or RT+ "artist - title"), or the FM app's own status line. */
        val text: String = "",
        val error: String? = null,
        /** The FM app's starred frequencies, in kHz. */
        val presets: List<Int> = emptyList(),
        /** [presets] has arrived from the FM app on this connection (it may be empty). */
        val presetsLoaded: Boolean = false,
        /**
         * The FM app takes preset changes (its session offers TOGGLE_FAVORITE, builds after
         * 0.3.0). Older FM apps do not, and Riot's slots then stay Riot's own.
         */
        val canFavorite: Boolean = false,
        /** Station genre or format from the FM app's catalogue. */
        val genre: String? = null,
        // Read from the session's PlaybackState extras when the FM app publishes them (see
        // EXTRA_* below). Null means the FM app did not say.
        val ps: String? = null,
        val radioText: String? = null,
        val rtArtist: String? = null,
        val rtTitle: String? = null,
        val pty: String? = null,
        val stereo: Boolean? = null,
        val rssi: Int? = null,
        val songIdTitle: String? = null,
        val songIdArtist: String? = null,
        val songIdStatus: String? = null,
        val adBreak: Boolean? = null,
    ) {
        /** The song on air, best source first: RT+ tags, then a song ID match. */
        val song: String? get() = when {
            !rtTitle.isNullOrBlank() -> listOfNotNull(rtArtist?.takeIf { it.isNotBlank() }, rtTitle).joinToString(" - ")
            !songIdTitle.isNullOrBlank() -> listOfNotNull(songIdArtist?.takeIf { it.isNotBlank() }, songIdTitle).joinToString(" - ")
            else -> null
        }
        /**
         * RadioText as decoded. Until the FM app publishes it as an extra, the session's subtitle
         * carries RT+ "artist - title", else RT, else a frequency line or a status line; only the
         * first two are kept.
         */
        val rt: String? get() = radioText?.takeIf { it.isNotBlank() } ?: text.trim().takeIf {
            it.isNotBlank() && !it.contains("MHz") && it != "Tuner off" && it != "Starting the tuner" && it != error
        }
    }

    var state = State()
        private set
    var onChange: (() -> Unit)? = null

    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null

    private val controllerCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackState?) = refresh()
        override fun onMetadataChanged(m: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() { controller = null; refresh() }
    }

    private val presetsCb = object : MediaBrowser.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
            val list = children.mapNotNull { it.mediaId?.removePrefix(FREQ)?.toIntOrNull() }
            state = state.copy(presets = list, presetsLoaded = true)
            onChange?.invoke()
        }
    }

    fun connect() {
        if (browser != null) return
        val b = MediaBrowser(ctx, ComponentName(FM_PKG, FM_SERVICE), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val br = browser ?: return
                val c = MediaController(ctx, br.sessionToken)
                controller?.unregisterCallback(controllerCb)
                controller = c
                c.registerCallback(controllerCb)
                handedOver.clear()
                state = state.copy(presetsLoaded = false)
                runCatching { br.subscribe(PRESETS, presetsCb) }
                refresh()
            }
            override fun onConnectionSuspended() { controller = null; refresh() }
            override fun onConnectionFailed() {
                Log.w(TAG, "FM app refused the connection")
                browser = null; controller = null; refresh()
            }
        }, null)
        browser = b
        runCatching { b.connect() }.onFailure { Log.w(TAG, "connect: $it"); browser = null }
    }

    fun release() {
        controller?.unregisterCallback(controllerCb)
        controller = null
        runCatching { browser?.disconnect() }
        browser = null
        state = State()
    }

    private fun refresh() {
        val c = controller
        if (c == null) {
            state = state.copy(connected = false, on = false, tuned = false)
            onChange?.invoke(); return
        }
        val pb = c.playbackState
        val md = c.metadata
        val st = pb?.state ?: PlaybackState.STATE_NONE
        val khz = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.removePrefix(FREQ)?.toIntOrNull() ?: state.khz
        val x = pb?.extras
        fun str(k: String) = x?.getString(k)?.trim()?.takeIf { it.isNotEmpty() }
        fun bool(k: String) = if (x?.containsKey(k) == true) x.getBoolean(k) else null
        fun int(k: String) = if (x?.containsKey(k) == true) x.getInt(k) else null
        state = state.copy(
            genre = md?.getString(MediaMetadata.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() },
            ps = str(EXTRA_PS), radioText = str(EXTRA_RT),
            rtArtist = str(EXTRA_RTP_ARTIST), rtTitle = str(EXTRA_RTP_TITLE),
            pty = str(EXTRA_PTY), stereo = bool(EXTRA_STEREO), rssi = int(EXTRA_RSSI),
            songIdTitle = str(EXTRA_SONGID_TITLE), songIdArtist = str(EXTRA_SONGID_ARTIST),
            songIdStatus = str(EXTRA_SONGID_STATUS), adBreak = bool(EXTRA_AD),
            connected = true,
            canFavorite = pb?.customActions?.any { it.action == TOGGLE_FAVORITE } == true,
            on = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING,
            tuned = st == PlaybackState.STATE_PLAYING,
            khz = khz,
            station = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            error = if (st == PlaybackState.STATE_ERROR) pb?.errorMessage?.toString() else null,
        )
        onChange?.invoke()
    }

    private fun tc() = controller?.transportControls

    fun powerOn() { tc()?.play() ?: connect() }
    fun powerOff() { tc()?.stop() }
    fun tune(khz: Int) { tc()?.playFromMediaId("$FREQ$khz", null) ?: connect() }
    fun seek(up: Boolean) { tc()?.sendCustomAction(if (up) SEEK_UP else SEEK_DOWN, null) }

    /**
     * Add or remove a preset in the FM app. Explicit, so sending it twice does no harm. Does
     * nothing when the FM app cannot take it ([State.canFavorite]).
     */
    fun setFavorite(khz: Int, add: Boolean) {
        if (khz <= 0 || !state.canFavorite) return
        tc()?.sendCustomAction(TOGGLE_FAVORITE, Bundle().apply { putInt("khz", khz); putBoolean("add", add) })
    }

    /** Slot frequencies already sent by [handOver] on this connection. */
    private val handedOver = HashSet<Int>()

    /**
     * Make sure every frequency in [slots] is an FM app preset, sending each missing one once
     * per connection. True when the FM app's list shows them all, so the caller can stop asking.
     */
    fun handOver(slots: IntArray): Boolean {
        val st = state
        if (!st.canFavorite || !st.presetsLoaded) return false
        val missing = slots.filter { it > 0 && it !in st.presets }.distinct()
        for (k in missing) if (handedOver.add(k)) setFavorite(k, true)
        return missing.isEmpty()
    }

    /** The M500's FM antenna is the headphone cable. */
    fun hasAntenna(): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return true
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
    }

    companion object {
        private const val TAG = "RiotFm"
        const val FM_PKG = "com.caf.fmradio"
        const val FM_SERVICE = "com.caf.fmradio.FmMediaBrowserService"
        private const val FREQ = "fm_freq:"
        private const val PRESETS = "fm_presets"
        private const val SEEK_UP = "com.caf.fmradio.media.SEEK_UP"
        private const val SEEK_DOWN = "com.caf.fmradio.media.SEEK_DOWN"
        private const val TOGGLE_FAVORITE = "com.caf.fmradio.media.TOGGLE_FAVORITE"

        // PlaybackState extras Riot mode reads if the FM app's session sets them.
        const val EXTRA_PS = "com.caf.fmradio.extra.PS"
        const val EXTRA_RT = "com.caf.fmradio.extra.RADIOTEXT"
        const val EXTRA_RTP_ARTIST = "com.caf.fmradio.extra.RTPLUS_ARTIST"
        const val EXTRA_RTP_TITLE = "com.caf.fmradio.extra.RTPLUS_TITLE"
        const val EXTRA_PTY = "com.caf.fmradio.extra.PTY_NAME"
        const val EXTRA_STEREO = "com.caf.fmradio.extra.STEREO"
        const val EXTRA_RSSI = "com.caf.fmradio.extra.RSSI"
        const val EXTRA_SONGID_TITLE = "com.caf.fmradio.extra.SONGID_TITLE"
        const val EXTRA_SONGID_ARTIST = "com.caf.fmradio.extra.SONGID_ARTIST"
        const val EXTRA_SONGID_STATUS = "com.caf.fmradio.extra.SONGID_STATUS"
        const val EXTRA_AD = "com.caf.fmradio.extra.AD_BREAK"
    }
}
