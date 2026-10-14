package com.caf.fmradio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The radio as a media session, so Android Auto (and anything else that reads sessions) can see
 * it, browse it and drive it.
 *
 * There is one session per process and it never owns the tuner. Every command goes through
 * [FmRadioManager] and [HioneFmService], the same calls the tuner screen makes, so there is
 * still exactly one engine and one open /dev/radio0. Audio focus is taken by the engine on
 * power-on, as it always was. This only reports state and forwards commands.
 *
 * Framework MediaSession / MediaBrowserService rather than the androidx or Media3 wrappers:
 * the radio has no Player to hand Media3, and the platform classes need no new dependency.
 *
 * Commands:
 *  - play: power the tuner on (or unmute it if a focus loss muted it)
 *  - pause / stop: power it off, the same as the power button
 *  - next / previous: next or previous preset, or a hardware seek when there are no presets
 *  - custom actions: seek up and seek down, and add or remove a preset (HionePod, Riot mode, the car)
 *  - play from media id: tune to that frequency, powering on if needed
 *  - play from search: a frequency ("101.1") or anything the station catalogue matches
 *
 * Main thread only.
 */
object FmMediaSession {

    private const val TAG = "FmMediaSession"

    const val ROOT = "fm_root"
    const val NOW = "fm_now"
    const val PRESETS = "fm_presets"
    const val NEARBY = "fm_nearby"
    private const val FREQ = "fm_freq:"

    private const val ACTION_SEEK_UP = "com.caf.fmradio.media.SEEK_UP"
    private const val ACTION_SEEK_DOWN = "com.caf.fmradio.media.SEEK_DOWN"

    /**
     * Add or remove a preset, in the same store the tuner screen's star uses. Offered whether or
     * not the tuner is on, so HionePod and Riot mode can tell this FM app takes presets from them.
     *
     * Extras (both optional):
     *  - [EXTRA_KHZ] (int): the frequency. Without it, the tuned frequency.
     *  - [EXTRA_ADD] (boolean): true adds, false removes, and either is a no-op when the preset
     *    is already that way. Without it, the frequency is toggled.
     */
    const val ACTION_TOGGLE_FAVORITE = "com.caf.fmradio.media.TOGGLE_FAVORITE"
    const val EXTRA_KHZ = "khz"
    const val EXTRA_ADD = "add"

    /** Enough for a car screen. Each item carries a small bitmap over binder. */
    private const val MAX_NEARBY = 40
    private const val LIST_ICON_PX = 96
    private const val ART_PX = 320

    // Content-style keys Android Auto reads from the root extras.
    private const val STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    private const val STYLE_LIST = 1

    private var app: Context? = null
    private var session: MediaSession? = null
    private var watcher: Job? = null
    private var browsers = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())

    /**
     * RadioText is not cleared on a retune; the old station's text stays until the new one
     * sends some. Remember which frequency the current text arrived on, and ignore it elsewhere.
     */
    private var rtSeen = ""
    private var rtFreq = -1

    private val tiles = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Everything the session shows. A change in anything else does not touch the session. */
    private data class Shown(
        val on: Boolean, val online: Boolean, val muted: Boolean, val error: String?,
        val khz: Int, val title: String, val subtitle: String, val detail: String,
        val call: String?, val genre: String?,
        /** The RDS and signal details below go out as PlaybackState extras (see [EXTRA_PS]). */
        val ps: String, val radioText: String?, val rtArtist: String?, val rtTitle: String?,
        val ptyName: String?, val stereo: Boolean?, val rssi: Int?,
        val songTitle: String?, val songArtist: String?, val songStatus: String,
        /** Presets in kHz, sorted. A change republishes the state (extras and the star action). */
        val presets: List<Int>,
    ) {
        /** What the metadata (and its art tile) is built from; extras-only changes skip it. */
        val metaKey: List<Any?> get() = listOf(khz, title, subtitle, detail, call, genre)
    }

    // PlaybackState extras for the retro modes (Riot, HionePod) and anything else that reads the
    // session. Strings are omitted when empty; STEREO is omitted until the chip has said.
    const val EXTRA_PS = "com.caf.fmradio.extra.PS"
    const val EXTRA_RADIOTEXT = "com.caf.fmradio.extra.RADIOTEXT"
    const val EXTRA_RTPLUS_ARTIST = "com.caf.fmradio.extra.RTPLUS_ARTIST"
    const val EXTRA_RTPLUS_TITLE = "com.caf.fmradio.extra.RTPLUS_TITLE"
    const val EXTRA_PTY_NAME = "com.caf.fmradio.extra.PTY_NAME"
    const val EXTRA_STEREO = "com.caf.fmradio.extra.STEREO"
    /** Int, dBuV. At most one change a second reaches the session. */
    const val EXTRA_RSSI = "com.caf.fmradio.extra.RSSI"
    const val EXTRA_SONGID_TITLE = "com.caf.fmradio.extra.SONGID_TITLE"
    const val EXTRA_SONGID_ARTIST = "com.caf.fmradio.extra.SONGID_ARTIST"
    /** "idle", "listening", "matching", "found", "not found", "unavailable", or the failure in words. */
    const val EXTRA_SONGID_STATUS = "com.caf.fmradio.extra.SONGID_STATUS"
    /** IntArray, kHz, sorted: the presets, the same list as the browse tree's Presets folder. */
    const val EXTRA_PRESETS = "com.caf.fmradio.extra.PRESETS"
    private const val RSSI_MIN_INTERVAL_MS = 1000L

    private var rssiShown: Int? = null
    private var rssiShownAt = 0L
    private var lastMetaKey: List<Any?>? = null

    // ------------------------------------------------------------------ lifecycle

    /** Create the session if it does not exist yet, and start mirroring the tuner into it. */
    fun ensure(ctx: Context): MediaSession {
        session?.let { return it }
        val a = ctx.applicationContext
        app = a
        // The state flow only moves once the engine exists. Building it does not power anything.
        FmRadioManager.ensure(a)

        val s = MediaSession(a, "HioneFm")
        s.setCallback(callback, main)
        s.setPlaybackToLocal(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        s.setSessionActivity(
            PendingIntent.getActivity(
                a, 0,
                Intent(a, HioneFMRadioActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        session = s

        watcher = scope.launch {
            FmRadioManager.state
                .map { shownFrom(it) }
                .distinctUntilChanged()
                .collect { publish(it) }
        }
        Log.i(TAG, "media session created")
        return s
    }

    /** A browser (Android Auto) connected. Keep the session visible while it is there. */
    fun attachBrowser(ctx: Context): MediaSession {
        val s = ensure(ctx)
        browsers++
        updateActive(FmRadioManager.state.value.isPowerOn)
        return s
    }

    fun detachBrowser() {
        browsers = (browsers - 1).coerceAtLeast(0)
        updateActive(FmRadioManager.state.value.isPowerOn)
    }

    private fun updateActive(on: Boolean) {
        val s = session ?: return
        val want = on || browsers > 0
        if (s.isActive != want) s.isActive = want
        // The hardware keys follow the tuner, not the session: see FmMediaKeys.
        app?.let { FmMediaKeys.setTunerOn(it, on) }
    }

    // ------------------------------------------------------------------ state -> session

    private fun stationTitle(st: FmState): String =
        st.stationName.trim().ifBlank { st.tunedStation?.label ?: "${fmtMhz(st.frequencyKHz)} FM" }

    /** Whether radioText is decoded RDS, as opposed to one of the engine's own status lines. */
    private fun isRdsText(rt: String): Boolean =
        rt.isNotBlank() && rt != "Tuner off" && !rt.startsWith("Live tuner") && !rt.startsWith("Tune failed")

    private fun shownFrom(st: FmState): Shown {
        if (st.radioText != rtSeen) { rtSeen = st.radioText; rtFreq = st.frequencyKHz }
        val freqLine = "${fmtMhz(st.frequencyKHz)} MHz"
        val genre = st.tunedStation?.let { it.genre ?: it.format }
        val rtPlus = st.rdsTitle?.takeIf { it.isNotBlank() }?.let { t ->
            st.rdsArtist?.takeIf { it.isNotBlank() }?.let { "$it - $t" } ?: t
        }
        val rt = st.radioText.trim().takeIf { isRdsText(it) && rtFreq == st.frequencyKHz }
        val subtitle = when {
            !st.isPowerOn && st.hardwareError != null -> st.hardwareError
            !st.isPowerOn -> "Tuner off"
            !st.isHardwareOnline -> "Starting the tuner"
            rtPlus != null -> rtPlus
            rt != null -> rt
            else -> listOfNotNull(freqLine, genre).joinToString(" · ")
        }
        // RSSI moves every poll; let at most one change a second through to the session.
        val now = android.os.SystemClock.elapsedRealtime()
        if (st.rssi != rssiShown && (now - rssiShownAt >= RSSI_MIN_INTERVAL_MS || rssiShown == null || st.rssi == null)) {
            rssiShown = st.rssi; rssiShownAt = now
        }
        val sid = st.songId
        val songStatus = when {
            sid.message != null && (sid.phase == FmSongId.Phase.NOT_FOUND || sid.phase == FmSongId.Phase.UNAVAILABLE) -> sid.message
            else -> sid.phase.name.lowercase(java.util.Locale.US).replace('_', ' ')
        }
        return Shown(
            on = st.isPowerOn, online = st.isHardwareOnline, muted = st.isMuted,
            error = st.hardwareError, khz = st.frequencyKHz, title = stationTitle(st),
            subtitle = subtitle, detail = listOfNotNull(freqLine, genre).joinToString(" · "),
            call = st.tunedStation?.label, genre = genre,
            ps = st.stationName.trim(), radioText = rt,
            rtArtist = st.rdsArtist?.trim()?.takeIf { it.isNotEmpty() },
            rtTitle = st.rdsTitle?.trim()?.takeIf { it.isNotEmpty() },
            ptyName = FmRadioManager.programmeTypeName(st.programmeType, st.band),
            stereo = st.isStereo, rssi = if (st.isPowerOn) rssiShown else null,
            songTitle = sid.match?.title, songArtist = sid.match?.artist, songStatus = songStatus,
            presets = st.favorites.distinct().sorted(),
        )
    }

    private fun publish(v: Shown) {
        val s = session ?: return

        val state = when {
            v.error != null && !v.online -> PlaybackState.STATE_ERROR
            !v.on -> PlaybackState.STATE_STOPPED
            !v.online -> PlaybackState.STATE_BUFFERING
            v.muted -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        val pb = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
            )
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
        if (state == PlaybackState.STATE_ERROR) pb.setErrorMessage(v.error)
        pb.setExtras(Bundle().apply {
            if (v.ps.isNotEmpty()) putString(EXTRA_PS, v.ps)
            v.radioText?.let { putString(EXTRA_RADIOTEXT, it) }
            v.rtArtist?.let { putString(EXTRA_RTPLUS_ARTIST, it) }
            v.rtTitle?.let { putString(EXTRA_RTPLUS_TITLE, it) }
            v.ptyName?.let { putString(EXTRA_PTY_NAME, it) }
            v.stereo?.let { putBoolean(EXTRA_STEREO, it) }
            v.rssi?.let { putInt(EXTRA_RSSI, it) }
            v.songTitle?.let { putString(EXTRA_SONGID_TITLE, it) }
            v.songArtist?.let { putString(EXTRA_SONGID_ARTIST, it) }
            putString(EXTRA_SONGID_STATUS, v.songStatus)
            putIntArray(EXTRA_PRESETS, v.presets.toIntArray())
        })
        // Always offered: it only edits the preset list, which needs no tuner.
        val starred = v.khz in v.presets
        pb.addCustomAction(
            PlaybackState.CustomAction.Builder(
                ACTION_TOGGLE_FAVORITE,
                if (starred) "Remove from presets" else "Add to presets",
                if (starred) R.drawable.ic_fm_star else R.drawable.ic_fm_star_border,
            ).build()
        )
        // Seeking needs the chip up; offering it before then would do nothing.
        if (v.online) {
            pb.addCustomAction(
                PlaybackState.CustomAction.Builder(ACTION_SEEK_DOWN, "Seek down", R.drawable.ic_fm_seek_down).build()
            )
            pb.addCustomAction(
                PlaybackState.CustomAction.Builder(ACTION_SEEK_UP, "Seek up", R.drawable.ic_fm_seek_up).build()
            )
        }
        s.setPlaybackState(pb.build())

        // A signal or RDS-only change needs the new extras, not a new art tile over binder.
        if (v.metaKey == lastMetaKey) { updateActive(v.on); return }
        lastMetaKey = v.metaKey
        val art = tile(v.call, v.khz, ART_PX)
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "$FREQ${v.khz}")
                .putString(MediaMetadata.METADATA_KEY_TITLE, v.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, v.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, v.subtitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, v.subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "${fmtMhz(v.khz)} FM")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, v.detail)
                .apply { v.genre?.let { putString(MediaMetadata.METADATA_KEY_GENRE, it) } }
                .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
                .build()
        )
        updateActive(v.on)
    }

    // ------------------------------------------------------------------ commands

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = play()
        override fun onPause() = powerOff()
        override fun onStop() = powerOff()
        override fun onSkipToNext() = presetStep(true)
        override fun onSkipToPrevious() = presetStep(false)

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val id = mediaId ?: return
            when {
                id == NOW -> play()
                id.startsWith(FREQ) -> id.removePrefix(FREQ).toIntOrNull()?.let { playFrequency(it) }
                else -> Log.w(TAG, "unknown media id $id")
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val q = query?.trim().orEmpty()
            if (q.isEmpty()) { play(); return }
            parseFrequency(q)?.let { playFrequency(it); return }
            scope.launch {
                // Catalogue search is blocking SQLite.
                val hit = withContext(Dispatchers.IO) {
                    runCatching { FmRadioManager.searchStations(q) }.getOrNull()
                        ?.firstOrNull { it.service != "AM" }
                }
                if (hit != null) playFrequency(hit.khz) else play()
            }
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                ACTION_SEEK_UP -> FmRadioManager.seek(true)
                ACTION_SEEK_DOWN -> FmRadioManager.seek(false)
                ACTION_TOGGLE_FAVORITE -> setFavorite(extras)
            }
        }
    }

    /**
     * See [ACTION_TOGGLE_FAVORITE]. Reads the engine's own list rather than [FmRadioManager.state],
     * which trails it by a main-thread hop, so a burst of adds (a client handing over its
     * favorites) cannot read a stale list and undo one of its own.
     */
    private fun setFavorite(extras: Bundle?) {
        val a = ctx() ?: return
        val eng = FmRadioManager.ensure(a)
        val khz = extras?.takeIf { it.containsKey(EXTRA_KHZ) }?.getInt(EXTRA_KHZ)
            ?: FmRadioManager.state.value.frequencyKHz
        if (khz !in QualcommFmHardwareEngine.CHIP_LOW_KHZ..QualcommFmHardwareEngine.CHIP_HIGH_KHZ) {
            Log.w(TAG, "preset $khz kHz is outside what the chip tunes, ignored")
            return
        }
        val has = khz in eng.presets.value
        val want = if (extras?.containsKey(EXTRA_ADD) == true) extras.getBoolean(EXTRA_ADD) else !has
        if (want != has) FmRadioManager.togglePreset(khz)
        Log.i(TAG, "preset $khz kHz ${if (want) "on" else "off"} (from a session client)")
    }

    private fun ctx(): Context? = app

    /** Power on, or unmute if a focus loss or the user muted it: playing means hearing it. */
    private fun play() {
        FmMediaKeys.play(ctx() ?: return)
    }

    private fun powerOff() {
        FmMediaKeys.powerOff(ctx() ?: return)
    }

    /** Tune, then make sure the tuner is on. Tuning first means power-on lands on the right station. */
    private fun playFrequency(khz: Int) {
        val a = ctx() ?: return
        FmRadioManager.ensure(a)
        if (khz != FmRadioManager.state.value.frequencyKHz) FmRadioManager.tune(khz)
        play()
    }

    /** Next or previous preset, wrapping; a seek when there are none. Same as the hardware keys. */
    private fun presetStep(up: Boolean) {
        FmMediaKeys.presetStep(ctx() ?: return, up)
    }

    /** "101.1", "101.1 FM", "101" -> kHz, when it lands in the broadcast band. */
    private fun parseFrequency(q: String): Int? {
        val m = Regex("""^\s*(\d{2,3}(?:\.\d{1,2})?)\s*(?:fm|mhz)?\s*$""", RegexOption.IGNORE_CASE).find(q) ?: return null
        val mhz = m.groupValues[1].toDoubleOrNull() ?: return null
        if (mhz < 64.0 || mhz > 108.0) return null
        return Math.round(mhz * 1000).toInt()
    }

    // ------------------------------------------------------------------ browse tree

    fun rootExtras(): Bundle = Bundle().apply {
        putBoolean(STYLE_SUPPORTED, true)
        putInt(STYLE_BROWSABLE, STYLE_LIST)
        putInt(STYLE_PLAYABLE, STYLE_LIST)
    }

    fun children(parentId: String): MutableList<MediaBrowser.MediaItem> {
        val st = FmRadioManager.state.value
        return when (parentId) {
            ROOT -> mutableListOf(
                folder(NOW, "Now playing"),
                folder(PRESETS, "Presets"),
                folder(NEARBY, "Nearby stations"),
            )
            NOW -> mutableListOf(nowItem(st))
            PRESETS -> st.favorites.sorted().map { khz -> presetItem(khz, st) }.toMutableList()
            NEARBY -> st.nearbyStations
                .filter { it.service != "AM" && it.khz in st.band.lowKHz..st.band.highKHz }
                .take(MAX_NEARBY)
                .map { stationItem(it) }
                .toMutableList()
            else -> mutableListOf()
        }
    }

    private fun folder(id: String, title: String) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).build(),
        MediaBrowser.MediaItem.FLAG_BROWSABLE
    )

    private fun playable(khz: Int, title: String, subtitle: String, description: String?, call: String?) =
        MediaBrowser.MediaItem(
            MediaDescription.Builder()
                .setMediaId("$FREQ$khz")
                .setTitle(title)
                .setSubtitle(subtitle)
                .setDescription(description)
                .setIconBitmap(tile(call, khz, LIST_ICON_PX))
                .build(),
            MediaBrowser.MediaItem.FLAG_PLAYABLE
        )

    private fun nowItem(st: FmState): MediaBrowser.MediaItem {
        val v = shownFrom(st)
        return MediaBrowser.MediaItem(
            MediaDescription.Builder()
                .setMediaId(NOW)
                .setTitle(v.title)
                .setSubtitle(v.subtitle)
                .setDescription(v.detail)
                .setIconBitmap(tile(v.call, v.khz, LIST_ICON_PX))
                .build(),
            MediaBrowser.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun presetItem(khz: Int, st: FmState): MediaBrowser.MediaItem {
        val station = stationOn(khz, st.nearbyStations)
        val rdsName = st.stationName.trim().takeIf { khz == st.frequencyKHz && it.isNotEmpty() }
        val title = rdsName ?: station?.label ?: "${fmtMhz(khz)} FM"
        val genre = station?.let { it.genre ?: it.format }
        return playable(
            khz, title,
            listOfNotNull("${fmtMhz(khz)} MHz", genre).joinToString(" · "),
            station?.let { place(it) },
            station?.label,
        )
    }

    private fun stationItem(s: FmStationCatalogue.Station): MediaBrowser.MediaItem {
        val genre = s.genre ?: s.format
        return playable(
            s.khz, s.label,
            listOfNotNull("${fmtMhz(s.khz)} MHz", genre).joinToString(" · "),
            listOfNotNull(place(s), s.reach.label.takeIf { s.reach != FmReach.Verdict.UNKNOWN })
                .joinToString(" · ").ifBlank { null },
            s.label,
        )
    }

    private fun place(s: FmStationCatalogue.Station): String? =
        listOfNotNull(s.city?.takeIf { it.isNotBlank() }, s.state?.takeIf { it.isNotBlank() })
            .joinToString(", ").ifBlank { null }

    // ------------------------------------------------------------------ artwork

    /**
     * A square in the station's own colour with the frequency on it. The same colour the preset
     * bar and station list use, so a station looks the same on the car screen.
     */
    private fun tile(call: String?, khz: Int, px: Int): Bitmap {
        val key = "${call.orEmpty()}|$khz|$px"
        tiles.get(key)?.let { return it }

        val color = (if (call != null) stationColor(call) else frequencyColor(khz)).toArgb()
        val dark = android.graphics.Color.rgb(
            android.graphics.Color.red(color) / 3,
            android.graphics.Color.green(color) / 3,
            android.graphics.Color.blue(color) / 3,
        )
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, px.toFloat(), px.toFloat(), color, dark, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, px.toFloat(), px.toFloat(), bg)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(px * 0.02f, 0f, px * 0.01f, 0x80000000.toInt())
        }
        val freq = fmtMhz(khz)
        text.textSize = px * 0.30f
        val maxW = px * 0.86f
        if (text.measureText(freq) > maxW) text.textSize *= maxW / text.measureText(freq)
        val cx = px / 2f
        c.drawText(freq, cx, px * 0.54f, text)

        text.typeface = Typeface.DEFAULT
        text.textSize = px * 0.14f
        val label = call ?: "FM"
        if (text.measureText(label) > maxW) text.textSize *= maxW / text.measureText(label)
        c.drawText(label, cx, px * 0.78f, text)

        tiles.put(key, bmp)
        return bmp
    }
}
