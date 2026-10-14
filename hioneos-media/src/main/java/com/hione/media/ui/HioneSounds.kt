package com.hione.media.ui

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaActionSound
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Sound effects and Hione voice lines for the three apps, from assets/sounds (copied out of
 * hioneos/data at build time, see copyHioneSounds in build.gradle.kts).
 *
 * Two paths, because the jobs differ:
 *  - SFX (shutter, focus, beeps, chimes) go through one SoundPool, decoded up front so a shutter
 *    plays the moment the button is pressed instead of after a MediaPlayer prepare.
 *  - Voice lines are longer and only one should speak at a time, so they use a MediaPlayer with
 *    transient may-duck audio focus: music in the player dips under the line and comes back.
 *
 * Settings live in one SharedPreferences file shared by Camera, Gallery and Recorder, since all
 * three run in this one process:
 *  - sounds_on: chimes and effects (default on)
 *  - voice: off / mirai / hoshi / cyber (default from the index, Mirai)
 *  - lines_<app>: whether that app speaks at all (Gallery defaults off, the others on)
 *  - timer_cue: voice or beeps for the camera self-timer
 */
object HioneSounds {
    private const val TAG = "HioneSounds"
    private const val PREFS = "hione_sounds"
    const val KEY_SOUNDS = "sounds_on"
    const val KEY_VOICE = "voice"
    const val KEY_TIMER_CUE = "timer_cue"
    fun linesKey(app: String) = "lines_$app"

    val VOICES = listOf("mirai", "hoshi", "cyber")
    fun voiceLabel(v: String) = v.replaceFirstChar { it.uppercase() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var pool: SoundPool? = null
    private val sfxPath = HashMap<String, String>()                  // "camera/shutter" -> sfx/camera/shutter.ogg
    private val sfxMs = HashMap<String, Long>()
    private val voicePath = HashMap<String, Map<String, String>>()   // "camera/say_cheese" -> voice -> path
    private val voiceMs = HashMap<String, Map<String, Long>>()
    private val loaded = HashMap<String, Int>()                      // key -> SoundPool sample id
    private val ready = HashSet<Int>()
    private var defaultVoice = "mirai"
    private var shutterForced = false

    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching { parseIndex() }.onFailure { Log.w(TAG, "no sound index, running silent", it) }

        // Some regions (Japan and Korea among them) legally require a camera shutter sound the
        // user cannot turn off, and the platform reports that through mustPlayShutterSound().
        // When it says so, the shutter ignores the Sounds toggle and is flagged audibility-
        // enforced so silent mode does not swallow it either. Everywhere else the toggle wins.
        shutterForced = runCatching { MediaActionSound.mustPlayShutterSound() }.getOrDefault(false)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .apply { if (shutterForced) setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED) }
            .build()
        pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build().apply {
            setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready += id } }
        }
    }

    private fun parseIndex() {
        val json = JSONObject(app.assets.open("sounds/sounds_index.json").bufferedReader().use { it.readText() })
        defaultVoice = json.optString("default_voice", "mirai")
        json.optJSONArray("sfx")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val key = r.getString("app") + "/" + r.getString("id")
                sfxPath[key] = "sounds/" + r.getString("file")
                sfxMs[key] = r.optLong("duration_ms", 0)
            }
        }
        json.optJSONArray("voice")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val key = r.getString("app") + "/" + r.getString("id")
                val files = r.optJSONObject("files") ?: continue
                val durs = r.optJSONObject("duration_ms")
                voicePath[key] = files.keys().asSequence().associateWith { "sounds/" + files.getString(it) }
                voiceMs[key] = files.keys().asSequence().associateWith { durs?.optLong(it, 0) ?: 0L }
            }
        }
    }

    // ---------------------------------------------------------------- settings

    fun prefs(ctx: Context): SharedPreferences { init(ctx); return prefs }
    val soundsOn: Boolean get() = prefs.getBoolean(KEY_SOUNDS, true)
    /** Selected voice, or null when voice is off. */
    val voice: String? get() = prefs.getString(KEY_VOICE, defaultVoice).takeIf { it in VOICES }
    fun linesOn(appName: String): Boolean = prefs.getBoolean(linesKey(appName), appName != "gallery")
    val timerCueVoice: Boolean get() = prefs.getString(KEY_TIMER_CUE, "voice") == "voice"

    /** True when [appName] would actually speak: a voice is chosen and the app's lines are on. */
    fun speaks(appName: String) = voice != null && linesOn(appName)

    // ---------------------------------------------------------------- SFX

    /** Decode an app's effects ahead of time. Call from the screen's first composition. */
    fun preload(ctx: Context, appName: String) {
        init(ctx)
        val p = pool ?: return
        scope.launch(Dispatchers.IO) {
            for ((key, path) in sfxPath) {
                if (!key.startsWith("$appName/")) continue
                if (synchronized(loaded) { key in loaded }) continue
                val id = runCatching { app.assets.openFd(path).use { p.load(it, 1) } }
                    .onFailure { Log.w(TAG, "load $path", it) }.getOrNull() ?: continue
                synchronized(loaded) { loaded[key] = id }
            }
        }
    }

    fun sfxDurationMs(appName: String, id: String): Long = sfxMs["$appName/$id"] ?: 0L

    /** Play an effect if sounds are on. Returns false if it did not play. */
    fun sfx(appName: String, id: String): Boolean {
        if (!::app.isInitialized || !soundsOn) return false
        return playSample("$appName/$id")
    }

    /**
     * The shutter. Same as [sfx] except where the region requires an unmutable shutter sound
     * (see init): there it plays regardless of the Sounds setting. Voice settings never affect it.
     */
    fun shutter(id: String = "shutter"): Boolean {
        if (!::app.isInitialized) return false
        if (!soundsOn && !shutterForced) return false
        return playSample("camera/$id")
    }

    private fun playSample(key: String): Boolean {
        val p = pool ?: return false
        val id = synchronized(loaded) { loaded[key] } ?: return false
        if (synchronized(ready) { id !in ready }) return false
        return p.play(id, 1f, 1f, 1, 0, 1f) != 0
    }

    // ---------------------------------------------------------------- voice

    fun voiceDurationMs(appName: String, id: String, v: String? = voice): Long =
        v?.let { voiceMs["$appName/$id"]?.get(it) } ?: 0L

    /** Fire-and-forget voice line, honoring the voice and per-app settings. */
    fun say(appName: String, id: String) {
        if (!::app.isInitialized || !speaks(appName)) return
        scope.launch { speakNow("$appName/$id", voice!!) }
    }

    /** [say] after [delayMs], so a line can follow an effect instead of talking over it. */
    fun sayAfter(appName: String, id: String, delayMs: Long) {
        if (!::app.isInitialized || !speaks(appName)) return
        scope.launch { kotlinx.coroutines.delay(delayMs); speakNow("$appName/$id", voice ?: return@launch) }
    }

    /**
     * Speak and report whether playback actually started, for callers that time something to
     * the line (the self-timer fires the shutter 3.0 s after "Three. Two. One." begins).
     * [force] ignores the per-app lines toggle (used by voice previews in settings).
     */
    suspend fun speak(appName: String, id: String, voiceOverride: String? = null, force: Boolean = false): Boolean {
        if (!::app.isInitialized) return false
        val v = voiceOverride ?: voice ?: return false
        if (!force && !linesOn(appName)) return false
        return speakNow("$appName/$id", v)
    }

    private suspend fun speakNow(key: String, v: String): Boolean {
        val path = voicePath[key]?.get(v) ?: return false
        stopVoice()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val mp = withContext(Dispatchers.IO) {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    app.assets.openFd(path).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                    prepare()
                }
            }.onFailure { Log.w(TAG, "voice $path", it) }.getOrNull()
        } ?: return false
        val am = app.getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        // A refused focus request (a call in progress) means stay quiet rather than talk over it.
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mp.release()
            return false
        }
        focus = req
        player = mp
        mp.setOnCompletionListener { stopVoice() }
        mp.start()
        return true
    }

    fun stopVoice() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        focus?.let { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }
}
