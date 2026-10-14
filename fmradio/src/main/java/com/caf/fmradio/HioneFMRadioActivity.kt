package com.caf.fmradio

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * UI state mirrored from [QualcommFmHardwareEngine]. Nothing here is pre-filled with a plausible
 * value: stereo, RSSI and SNR are null until the chip reports them, the station name is blank
 * until RDS decodes one, presets are the user's own, and the spectrum is the real FM PCM.
 */
data class FmState(
    val isPowerOn: Boolean = false,
    /** True only after the tuner chip acknowledged enable. */
    val isHardwareOnline: Boolean = false,
    val hardwareError: String? = null,
    val band: FmBandPlan = FmBandPlan.US,
    /** True when the band follows where the device is (Japan or North America). */
    val bandAuto: Boolean = true,
    /** Why automatic picked the band it did, for the settings sheet ("location: JP"). */
    val bandAutoReason: String? = null,
    val frequencyKHz: Int = 101100,
    /** What the chip reports. null = it has not said. */
    val isStereo: Boolean? = null,
    /** What the user asked for, which the chip may not be able to deliver. */
    val stereoRequested: Boolean = true,
    val isMuted: Boolean = false,
    val isRecording: Boolean = false,
    val recordedBytes: Long = 0L,
    val rssi: Int? = null,
    val signal: FmV4L2.Reading = FmV4L2.Reading.EMPTY,
    val stationName: String = "",
    val radioText: String = "",
    val programmeType: Int? = null,
    val programmeId: Int? = null,
    val rdsAvailable: Boolean? = null,
    val isHeadsetPlugged: Boolean = false,
    val favorites: List<Int> = emptyList(),
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val scanResults: List<FmScanHit> = emptyList(),
    val fmVolumeLevel: Int = 80,
    val afJump: Boolean = false,
    val softMute: Boolean = true,
    val seekSensitivity: Int = 1,
    val audioLevel: Float = 0f,
    val captureFraming: Boolean? = null,
    val spectrumTopHz: Int = 15000,
    val signalHistory: FloatArray = FloatArray(0),
    /** RSSI per channel from the newest band sweep; this is what the waterfall draws. */
    val bandProfile: FloatArray = FloatArray(0),
    val bandHistory: List<FloatArray> = emptyList(),
    val rdsSupported: Boolean? = null,
    val rdsArtist: String? = null,
    val rdsTitle: String? = null,
    val rdsGroups: Int = 0,
    val channelGrid: FmChannelGrid = FmChannelGrid.AUTO,
    val nearbyStations: List<FmStationCatalogue.Station> = emptyList(),
    val sdrSkin: SdrSkin = SdrSkin.CLASSIC,
    val viewMode: QualcommFmHardwareEngine.ViewMode = QualcommFmHardwareEngine.ViewMode.LIVE,
    val siphonState: QualcommFmHardwareEngine.SiphonState = QualcommFmHardwareEngine.SiphonState.UNTRIED,
    val siphonReason: String? = null,
    val tunedProfile: FmFresnel.Profile? = null,
    val bandHistoryTimes: List<Long> = emptyList(),
    val tunedStation: FmStationCatalogue.Station? = null,
    val listenerPlace: Pair<Double, Double>? = null,
    val catalogueSize: Int = 0,
    val diagnostics: FmDiagnostics = FmDiagnostics(),
    /** Song identification (Shazam-style), its last result and this session's history. */
    val songId: FmSongId.State = FmSongId.State(),
    /** Nearest NOAA Weather Radio transmitters; reference only, this tuner cannot receive 162 MHz. */
    val weatherRadios: List<FmWeatherRadio.Transmitter> = emptyList(),
    /** Active NWS alerts here (null = not fetched / unreachable, empty = none active). */
    val weatherAlerts: List<FmWeatherRadio.Alert>? = null,
    /** Calibration behind every station's reach verdict: offset in dB and how many stations it came from. */
    val reachOffsetDb: Double = FmReach.DEFAULT_OFFSET_DB,
    val reachCalibratedFrom: Int = 0,
) {
    /**
     * Compose and equals(): the FloatArray makes the generated equals reference-compare, which
     * is actually what we want here (the engine hands out a fresh array per update), but the
     * generated hashCode would then disagree with it. Spelled out so the pair is consistent.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FmState) return false
        return isPowerOn == other.isPowerOn && isHardwareOnline == other.isHardwareOnline &&
            hardwareError == other.hardwareError && band == other.band &&
            bandAuto == other.bandAuto && bandAutoReason == other.bandAutoReason &&
            frequencyKHz == other.frequencyKHz && isStereo == other.isStereo &&
            stereoRequested == other.stereoRequested && isMuted == other.isMuted &&
            isRecording == other.isRecording && recordedBytes == other.recordedBytes &&
            rssi == other.rssi && signal == other.signal && stationName == other.stationName &&
            radioText == other.radioText && programmeType == other.programmeType &&
            programmeId == other.programmeId && rdsAvailable == other.rdsAvailable &&
            isHeadsetPlugged == other.isHeadsetPlugged && favorites == other.favorites &&
            isScanning == other.isScanning && scanProgress == other.scanProgress &&
            scanResults == other.scanResults && fmVolumeLevel == other.fmVolumeLevel &&
            afJump == other.afJump && softMute == other.softMute &&
            seekSensitivity == other.seekSensitivity && audioLevel == other.audioLevel &&
            diagnostics == other.diagnostics && captureFraming == other.captureFraming &&
            spectrumTopHz == other.spectrumTopHz &&
            signalHistory === other.signalHistory && bandProfile === other.bandProfile &&
            bandHistory === other.bandHistory && rdsSupported == other.rdsSupported &&
            rdsArtist == other.rdsArtist && rdsTitle == other.rdsTitle &&
            rdsGroups == other.rdsGroups && channelGrid == other.channelGrid &&
            nearbyStations === other.nearbyStations && tunedStation == other.tunedStation &&
            sdrSkin == other.sdrSkin && viewMode == other.viewMode &&
            siphonState == other.siphonState && siphonReason == other.siphonReason && bandHistoryTimes === other.bandHistoryTimes &&
            tunedProfile === other.tunedProfile &&
            listenerPlace == other.listenerPlace && catalogueSize == other.catalogueSize &&
            songId == other.songId && weatherRadios === other.weatherRadios && weatherAlerts === other.weatherAlerts &&
            reachOffsetDb == other.reachOffsetDb && reachCalibratedFrom == other.reachCalibratedFrom
    }

    override fun hashCode(): Int {
        var r = isPowerOn.hashCode()
        r = 31 * r + frequencyKHz
        r = 31 * r + band.hashCode()
        r = 31 * r + (rssi ?: 0)
        r = 31 * r + stationName.hashCode()
        r = 31 * r + radioText.hashCode()
        return r
    }
}

/**
 * Process-wide owner of the tuner.
 *
 * It is a singleton rather than something the activity holds because [HioneFmService] keeps the
 * radio running while no activity exists. Both talk to the same engine; the activity is one
 * view onto it, the notification is another.
 */
object FmRadioManager {
    private const val TAG = "HioneDirectFmEngine"

    private var engine: QualcommFmHardwareEngine? = null
    private val _state = MutableStateFlow(FmState())
    val state: StateFlow<FmState> = _state
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * The live spectrum, on its own flow. It updates 25 times a second; folded into [state] it
     * made the whole screen recompose at that rate, which is most of why the tuner felt laggy.
     * Only the analyser should read this.
     */
    data class SpectrumFrame(
        val level: FloatArray = FloatArray(0),
        val peak: FloatArray = FloatArray(0),
        /** Centre frequency of each bin, Hz (log-spaced). */
        val binHz: FloatArray = FloatArray(0),
        val topHz: Int = 16_000,
    ) {
        override fun equals(other: Any?) = other is SpectrumFrame && level === other.level && peak === other.peak
        override fun hashCode() = System.identityHashCode(level)
    }
    private val _spectrum = MutableStateFlow(SpectrumFrame())
    val spectrum: StateFlow<SpectrumFrame> = _spectrum

    /** Programme-type names. Two tables, because RBDS and RDS assign the same codes differently. */
    private val PTY_RDS = listOf(
        "None", "News", "Current Affairs", "Information", "Sport", "Education", "Drama",
        "Culture", "Science", "Varied", "Pop Music", "Rock Music", "Easy Listening",
        "Light Classical", "Serious Classical", "Other Music", "Weather", "Finance",
        "Children's Programmes", "Social Affairs", "Religion", "Phone In", "Travel", "Leisure",
        "Jazz Music", "Country Music", "National Music", "Oldies Music", "Folk Music",
        "Documentary", "Alarm Test", "Alarm"
    )
    private val PTY_RBDS = listOf(
        "None", "News", "Information", "Sports", "Talk", "Rock", "Classic Rock", "Adult Hits",
        "Soft Rock", "Top 40", "Country", "Oldies", "Soft", "Nostalgia", "Jazz", "Classical",
        "Rhythm and Blues", "Soft R&B", "Foreign Language", "Religious Music", "Religious Talk",
        "Personality", "Public", "College", "Spanish Talk", "Spanish Music", "Hip Hop",
        "", "", "Weather", "Emergency Test", "Emergency"
    )

    /** Null when the code is out of range or that slot is unassigned in this standard. */
    fun programmeTypeName(code: Int?, band: FmBandPlan): String? {
        if (code == null || code <= 0) return null
        val table = if (band.rdsStd == qcom.fmradio.FmConfig.FM_RDS_STD_RBDS) PTY_RBDS else PTY_RDS
        return table.getOrNull(code)?.takeIf { it.isNotEmpty() }
    }

    fun ensure(ctx: Context): QualcommFmHardwareEngine {
        engine?.let { return it }
        val eng = QualcommFmHardwareEngine(ctx.applicationContext)
        engine = eng
        val app = ctx.applicationContext
        scope.launch {
            launch { eng.isPoweredOn.collect { v -> _state.update { it.copy(isPowerOn = v) } } }
            launch { eng.hardwareOnline.collect { v -> _state.update { it.copy(isHardwareOnline = v) } } }
            launch { eng.hardwareError.collect { v -> _state.update { it.copy(hardwareError = v) } } }
            launch { eng.band.collect { v -> _state.update { it.copy(band = v) } } }
            launch { eng.bandAuto.collect { v -> _state.update { it.copy(bandAuto = v) } } }
            launch { eng.bandAutoReason.collect { v -> _state.update { it.copy(bandAutoReason = v) } } }
            // The live spectrum is NOT folded into state any more: it is published on its own
            // flow below and read only by the analyser's draw phase. At 25 fps in here it made
            // the whole screen recompose every frame.
            launch {
                eng.spectrumPeaks.collect { pk ->
                    _spectrum.value = SpectrumFrame(eng.spectrum.value, pk, eng.spectrumBinHz.value, eng.spectrumTopHz.value)
                }
            }
            launch { FmSongId.state.collect { v -> _state.update { it.copy(songId = v) } } }
            launch { eng.weatherRadios.collect { v -> _state.update { it.copy(weatherRadios = v) } } }
            launch { eng.weatherAlerts.collect { v -> _state.update { it.copy(weatherAlerts = v) } } }
            // The engine updates the RMS level on every capture block (dozens of times a
            // second). Only a printed number reads it, so it is sampled once a second and
            // rounded to what is printed: each change is a full window frame on this GPU.
            launch {
                var last = -1f
                while (true) {
                    val v = (eng.audioLevel.value * 100f).roundToInt() / 100f
                    if (v != last) { last = v; _state.update { it.copy(audioLevel = v) } }
                    delay(1000)
                }
            }
            launch { eng.captureFraming.collect { v -> _state.update { it.copy(captureFraming = v) } } }
            launch { eng.spectrumTopHz.collect { v -> _state.update { it.copy(spectrumTopHz = v) } } }
            launch { eng.signalHistory.collect { v -> _state.update { it.copy(signalHistory = v) } } }
            launch { eng.bandProfile.collect { v -> _state.update { it.copy(bandProfile = v) } } }
            launch { eng.bandHistory.collect { v -> _state.update { it.copy(bandHistory = v) } } }
            launch { eng.rdsSupported.collect { v -> _state.update { it.copy(rdsSupported = v) } } }
            launch { eng.rdsArtist.collect { v -> _state.update { it.copy(rdsArtist = v) } } }
            launch { eng.rdsTitle.collect { v -> _state.update { it.copy(rdsTitle = v) } } }
            // The group counter ticks on every RDS poll (up to ~16 Hz while decoding) and no
            // screen prints it live; sampled once a second so it cannot drive recomposition.
            launch {
                while (true) {
                    val v = eng.rdsGroups.value
                    _state.update { if (it.rdsGroups == v) it else it.copy(rdsGroups = v) }
                    delay(1000)
                }
            }
            launch { eng.channelGrid.collect { v -> _state.update { it.copy(channelGrid = v) } } }
            launch {
                eng.nearbyStations.collect { v ->
                    _state.update { it.copy(nearbyStations = v, reachOffsetDb = FmReach.offsetDb, reachCalibratedFrom = FmReach.calibratedFrom) }
                }
            }
            launch { eng.sdrSkin.collect { v -> _state.update { it.copy(sdrSkin = v) } } }
            launch { eng.viewMode.collect { v -> _state.update { it.copy(viewMode = v) } } }
            launch { eng.siphonState.collect { v -> _state.update { it.copy(siphonState = v) } } }
            launch { eng.siphonReason.collect { v -> _state.update { it.copy(siphonReason = v) } } }
            launch { eng.tunedProfile.collect { v -> _state.update { it.copy(tunedProfile = v) } } }
            launch { eng.bandHistoryTimes.collect { v -> _state.update { it.copy(bandHistoryTimes = v) } } }
            launch { eng.tunedStation.collect { v -> _state.update { it.copy(tunedStation = v) } } }
            launch { eng.listenerPlace.collect { v -> _state.update { it.copy(listenerPlace = v) } } }
            launch { eng.catalogueSize.collect { v -> _state.update { it.copy(catalogueSize = v) } } }
            // The position arrives asynchronously from HioneLocationFusion, so ask again on a
            // slow tick until it lands, then only when it has moved.
            launch {
                while (true) { eng.refreshNearby(); delay(45_000) }
            }
            launch { eng.currentFrequencyKHz.collect { v -> _state.update { it.copy(frequencyKHz = v) } } }
            launch { eng.isStereo.collect { v -> _state.update { it.copy(isStereo = v) } } }
            launch { eng.stereoRequested.collect { v -> _state.update { it.copy(stereoRequested = v) } } }
            launch { eng.isMuted.collect { v -> _state.update { it.copy(isMuted = v) } } }
            launch { eng.rssi.collect { v -> _state.update { it.copy(rssi = v) } } }
            launch { eng.signal.collect { v -> _state.update { it.copy(signal = v) } } }
            launch { eng.stationName.collect { v -> _state.update { it.copy(stationName = v) } } }
            launch { eng.radioText.collect { v -> _state.update { it.copy(radioText = v) } } }
            launch { eng.programmeType.collect { v -> _state.update { it.copy(programmeType = v) } } }
            launch { eng.programmeId.collect { v -> _state.update { it.copy(programmeId = v) } } }
            launch { eng.rdsAvailable.collect { v -> _state.update { it.copy(rdsAvailable = v) } } }
            launch { eng.presets.collect { v -> _state.update { it.copy(favorites = v) } } }
            launch { eng.isScanning.collect { v -> _state.update { it.copy(isScanning = v) } } }
            launch { eng.scanProgress.collect { v -> _state.update { it.copy(scanProgress = v) } } }
            launch { eng.scanResults.collect { v -> _state.update { it.copy(scanResults = v) } } }
            launch { eng.fmVolumeLevel.collect { v -> _state.update { it.copy(fmVolumeLevel = v) } } }
            launch { eng.afJumpEnabled.collect { v -> _state.update { it.copy(afJump = v) } } }
            launch { eng.softMuteEnabled.collect { v -> _state.update { it.copy(softMute = v) } } }
            launch { eng.seekSensitivity.collect { v -> _state.update { it.copy(seekSensitivity = v) } } }
            launch { eng.diagnostics.collect { v -> _state.update { it.copy(diagnostics = v) } } }

            // The headphone cable is the FM antenna on a board with no internal one, so this is
            // a real reading of what is plugged in, not a decoration.
            launch {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                while (true) {
                    val plugged = runCatching {
                        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                        } == true
                    }.getOrDefault(false)
                    _state.update { if (it.isHeadsetPlugged == plugged) it else it.copy(isHeadsetPlugged = plugged) }
                    delay(1500)
                }
            }

            // Recording length comes from the bytes actually written to the WAV.
            launch {
                while (true) {
                    val rec = engine?.isRecording() == true
                    val bytes = engine?.recordedBytes() ?: 0L
                    _state.update {
                        if (it.isRecording == rec && it.recordedBytes == bytes) it
                        else it.copy(isRecording = rec, recordedBytes = bytes)
                    }
                    delay(500)
                }
            }
        }
        return eng
    }

    fun initAndPowerOn(ctx: Context) { ensure(ctx).powerOn() }

    fun powerOff() { engine?.powerOff() }
    fun togglePower(ctx: Context) { ensure(ctx).togglePower() }
    fun tune(freqKHz: Int) { engine?.tune(freqKHz) }
    fun step(up: Boolean) { engine?.step(up) }
    fun seek(up: Boolean) { engine?.seek(up) }
    fun cancelSeek() { engine?.cancelSeek() }
    fun scanBand() { engine?.scanBand() }
    fun toggleMute() { engine?.toggleMute() }
    fun setStereo(on: Boolean) { engine?.setStereo(on) }
    fun setBand(plan: FmBandPlan) { engine?.setBand(plan) }
    /** Let the band follow the device's location again (Japan or North America). */
    fun setBandAuto() { engine?.setBandAuto() }
    fun setFmVolumeLevel(l: Int) { engine?.setFmVolumeLevel(l) }
    fun cycleSdrSkin() { engine?.cycleSdrSkin() }
    fun setViewMode(m: QualcommFmHardwareEngine.ViewMode) { engine?.setViewMode(m) }
    fun retrySiphon() { engine?.retrySiphon() }
    fun identifySong() { engine?.identifySong() }
    /** Blocking terrain fetch for one station; see QualcommFmHardwareEngine.terrainFor. */
    fun terrainFor(st: FmStationCatalogue.Station): FmStationCatalogue.Station? = engine?.terrainFor(st)
    fun dismissSongId() = FmSongId.dismiss()
    fun refreshWeatherAlerts() { engine?.refreshWeatherAlerts(force = true) }

    /**
     * Like the identified song: kept with station, frequency, time and position, and handed to
     * Hione Music as a liked track to acquire. Returns the new liked state.
     */
    fun toggleLikeSong(ctx: Context, m: FmSongId.Match): Boolean =
        FmLikes.toggle(ctx, m, _state.value.stationName.ifBlank { _state.value.tunedStation?.label ?: "FM" })
    fun isSongLiked(ctx: Context, m: FmSongId.Match): Boolean = FmLikes.isLiked(ctx, m.title, m.artist)

    /**
     * Search the whole station catalogue (call, city, format, genre, licensee, or a frequency
     * like "94.5"). Blocking SQLite: call from a background dispatcher. Results get the same
     * reach verdicts as the nearby list where they are in it.
     */
    fun searchStations(query: String): List<FmStationCatalogue.Station> {
        val pos = _state.value.listenerPlace
        val near = _state.value.nearbyStations.associateBy { it.call }
        val found = FmStationCatalogue.search(query, pos?.first, pos?.second)
        val predicted = if (pos == null) found else FmReach.annotate(found.filter { it.call !in near }, null, 0, 1).associateBy { it.call }.let { p ->
            found.map { p[it.call] ?: it }
        }
        return predicted.map { near[it.call] ?: it }
    }

    fun nudgeFmVolume(up: Boolean) { engine?.nudgeFmVolume(up) }
    fun setAfJump(on: Boolean) { engine?.setAfJump(on) }
    fun setSoftMute(on: Boolean) { engine?.setSoftMute(on) }
    fun setSeekSensitivity(level: Int) { engine?.setSeekSensitivity(level) }
    fun setChannelGrid(g: FmChannelGrid) { engine?.setChannelGrid(g) }
    fun refreshNearby() { engine?.refreshNearby(force = true) }
    fun togglePreset(freqKHz: Int) { engine?.togglePreset(freqKHz) }

    /**
     * The tuner screen being in front of the user is what decides whether the RADIO_TUNER
     * capture is held open on a wired route: the spectrum needs it, and nothing else does while
     * the radio plays through the hardware loopback in the background.
     */
    fun setUiVisible(visible: Boolean) { engine?.setUiVisible(visible) }

    /**
     * Start or stop a real WAV capture of the live FM PCM.
     *
     * Returns the message to show. It can fail honestly: with no audio bridge running there is
     * nothing to tee, and the caller is told that rather than shown a red dot over silence.
     */
    fun toggleRecording(ctx: Context): String {
        val eng = engine ?: return "Tuner is not running"
        if (eng.isRecording()) {
            val f = eng.stopRecording()
            return if (f != null) "Saved ${f.name}" else "Recording stopped"
        }
        if (!_state.value.isHardwareOnline) return "Turn the tuner on first"
        // On wired outputs the radio plays through a hardware loopback the app cannot hear;
        // recording there captured silence and could stop the radio. Only record real audio.
        if (engine?.canRecord() != true) return "Recording works over Bluetooth, or once the live " +
            "audio siphon has verified itself (Diagnostics)"
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
            "FM Recordings"
        )
        val name = "FM_%.1f_%s.wav".format(
            _state.value.frequencyKHz / 1000f,
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        )
        val f = java.io.File(dir, name)
        return if (eng.startRecording(f)) "Recording ${f.name}" else "Recording failed (no FM audio to capture)"
    }
}

class HioneFMRadioActivity : ComponentActivity() {

    private fun hideSystemBars() {
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        } catch (_: Throwable) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        hideSystemBars()
        // The station search has a text field. Edge-to-edge with adjustResize hands the IME
        // height to Compose as an inset (the sheet pads itself by it) instead of panning the
        // whole immersive window up under the keyboard.
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Hardware volume keys should move the radio, so route them at STREAM_MUSIC — which is
        // also the stream the engine's fm_volume tracks.

        // Build the engine but do not switch the tuner on behind the user's back: the power
        // button in the UI does that, and the service keeps it on afterwards.
        FmRadioManager.ensure(this)

        setContent { HioneFMRadioScreen(onBack = { finish() }) }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        FmRadioManager.setUiVisible(true)
    }

    /**
     * The wheel drives the TUNER's volume here, not the media stream.
     *
     * The M500's rotary wheel emits ordinary volume keys. While the tuner is in front of you
     * those belong to the tuner: its gain is its own now, and letting the wheel move
     * STREAM_MUSIC instead would change the music volume while appearing to do nothing to the
     * radio. Consumed so the system HUD does not also appear.
     */
    /**
     * The transport keys (play/pause, previous, next) drive the radio while it is in front,
     * the same as from the background: see [FmMediaKeys].
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (FmMediaKeys.isTransportKey(event.keyCode) && FmMediaKeys.handle(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_VOLUME_UP -> { FmRadioManager.nudgeFmVolume(true); true }
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> { FmRadioManager.nudgeFmVolume(false); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> true
        else -> super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        FmRadioManager.setUiVisible(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Leaving the screen no longer kills the radio. It used to call powerOff() here, which meant
     * the tuner could not survive a trip to the home screen; [HioneFmService] owns the lifetime
     * now and stops itself when the engine powers down.
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.d("HioneFMRadioActivity", "activity gone; tuner state left to HioneFmService")
    }
}
