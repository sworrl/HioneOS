package com.m500.hardware

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.log10
import kotlin.math.max

/**
 * The DAC graph's data from outside the DAC: which listening profile each output uses (published
 * by Hione Music), the headphone curves the user imported, the curves measured with the
 * built-in mic, and the sweep measurement itself.
 */

/**
 * Listening profile per output, read-only. Hione Music's ListeningProfileManager publishes it as
 * Settings.Global "hione_listening_outputs" (JSON, see there for the shape) whenever a profile,
 * a pin or the connected outputs change. Settings.Global is readable by every app, the same way
 * this app reads hione_dac_state.
 */
object OutputProfiles {
    const val KEY = "hione_listening_outputs"
    const val PORT_35 = "3.5mm"
    const val PORT_44 = "4.4mm"

    data class Entry(
        val port: String,
        val label: String,
        val connected: Boolean,
        val profileId: String?,
        val name: String?,
        /** HardwareType id: iem, over-ear, line-out, ... */
        val type: String?,
        val impedanceOhm: Double?,
        val pinned: Boolean,
        val eqEnabled: Boolean,
        val preampDb: Double,
        val bands: List<ResponseMath.Band>,
        val filter: String?,
        val gain: String?,
        val highPower: Boolean?,
        val dre: Boolean?,
    ) {
        val eqAudible: Boolean get() = eqEnabled && (bands.any { kotlin.math.abs(it.gainDb) > 1e-9 } || kotlin.math.abs(preampDb) > 1e-9)
        val isLineOut: Boolean get() = type == "line-out"
        /** Key for this output's stored curves: the profile when there is one, else the port. */
        val storeKey: String get() = profileId ?: "port-$port"
    }

    data class Snapshot(val active: String?, val entries: List<Entry>) {
        fun forPort(port: String): Entry? = entries.firstOrNull { it.port == port }
    }

    fun read(ctx: Context): Snapshot =
        parse(runCatching { Settings.Global.getString(ctx.contentResolver, KEY) }.getOrNull())

    fun parse(raw: String?): Snapshot {
        if (raw.isNullOrBlank()) return Snapshot(null, emptyList())
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("outputs") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val eq = e.optJSONObject("eq")
                val bands = eq?.optJSONArray("bands")?.let { b ->
                    (0 until b.length()).mapNotNull { j ->
                        val x = b.optJSONObject(j) ?: return@mapNotNull null
                        ResponseMath.Band(ResponseMath.bandType(x.optString("type", "PK")), x.optDouble("fc", 1000.0),
                            x.optDouble("gain", 0.0), x.optDouble("q", 0.707).let { q -> if (q.isNaN() || q <= 0) 0.707 else q })
                    }
                }.orEmpty()
                Entry(
                    port = e.optString("port"),
                    label = e.optString("label", e.optString("port")),
                    connected = e.optBoolean("connected", false),
                    profileId = e.optString("id").ifEmpty { null },
                    name = e.optString("name").ifEmpty { null },
                    type = e.optString("type").ifEmpty { null },
                    impedanceOhm = if (e.has("impedanceOhm")) e.optDouble("impedanceOhm").takeIf { !it.isNaN() } else null,
                    pinned = e.optBoolean("pinned", false),
                    eqEnabled = e.optBoolean("eqEnabled", false),
                    preampDb = eq?.optDouble("preamp", 0.0) ?: 0.0,
                    bands = bands,
                    filter = e.optString("digitalFilter").ifEmpty { null },
                    gain = e.optString("gain").ifEmpty { null },
                    highPower = if (e.has("highPower")) e.optBoolean("highPower") else null,
                    dre = if (e.has("dre")) e.optBoolean("dre") else null,
                )
            }
            Snapshot(o.optString("active").ifEmpty { null }, list)
        }.getOrElse { Snapshot(null, emptyList()) }
    }

    /**
     * Ask Hione Music to put [bands] (cuts from a measurement) on a profile's EQ. Hione Music
     * registers the receiver at runtime and only accepts it from apps holding
     * com.hione.permission.SYSTEM_BRIDGE (signature), which this app does.
     */
    fun sendEqToHioneMusic(ctx: Context, profileId: String, bands: List<ResponseMath.Band>, preampDb: Double) {
        val eq = JSONObject().apply {
            put("preamp", preampDb)
            put("bands", JSONArray().apply {
                bands.forEach { b ->
                    put(JSONObject().apply {
                        put("type", when (b.type) { ResponseMath.BandType.LOW_SHELF -> "LSC"; ResponseMath.BandType.HIGH_SHELF -> "HSC"; else -> "PK" })
                        put("fc", b.hz); put("gain", b.gainDb); put("q", b.q)
                    })
                }
            })
        }
        ctx.sendBroadcast(
            Intent("com.hione.player.profiles.SET_EQ").setPackage("com.hione.player")
                .putExtra("profileId", profileId).putExtra("eq", eq.toString()),
            "com.hione.permission.SYSTEM_BRIDGE",
        )
    }
}

/** Imported headphone curves and measured curves, per profile (or per port), in filesDir/response. */
object ResponseStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "response").apply { mkdirs() }
    private fun safe(key: String) = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    data class Stored(val curve: ResponseMath.Curve, val label: String, val atMillis: Long)

    private fun file(ctx: Context, key: String, kind: String) = File(dir(ctx), "${safe(key)}.$kind.json")

    fun save(ctx: Context, key: String, kind: String, s: Stored) {
        val o = JSONObject().apply {
            put("label", s.label); put("at", s.atMillis)
            put("hz", JSONArray().apply { s.curve.hz.forEach { put(it) } })
            put("db", JSONArray().apply { s.curve.db.forEach { put(it) } })
        }
        runCatching { file(ctx, key, kind).writeText(o.toString()) }.onFailure { Log.w("ResponseStore", "save failed", it) }
    }

    fun load(ctx: Context, key: String, kind: String): Stored? = runCatching {
        val f = file(ctx, key, kind)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val hz = o.getJSONArray("hz"); val db = o.getJSONArray("db")
        Stored(ResponseMath.Curve(DoubleArray(hz.length()) { hz.getDouble(it) }, DoubleArray(db.length()) { db.getDouble(it) }),
            o.optString("label"), o.optLong("at"))
    }.getOrNull()

    fun delete(ctx: Context, key: String, kind: String) { runCatching { file(ctx, key, kind).delete() } }

    const val HEADPHONE = "headphone"
    const val MEASURED = "measured"
}

/**
 * Sine sweep measurement through one jack, recorded with the built-in mic. The mic is an
 * uncalibrated phone-style capsule, so the result is good for the shape, not for absolute level.
 */
object SweepMeasurement {
    private const val TAG = "SweepMeasurement"
    const val RATE = 48000
    const val SWEEP_SECONDS = 8.0
    private const val LEAD_SECONDS = 0.5
    private const val TAIL_SECONDS = 1.5

    class Outcome(
        val curve: ResponseMath.Curve?,
        val sweepsUsed: Int,
        /** Recorded level of the loudest sweep, dBFS. */
        val recordedPeakDbfs: Double,
        val notes: List<String>,
        val error: String?,
    )

    /** The output device for [port], or null when nothing is plugged into it. */
    fun jackDevice(ctx: Context, port: String): AudioDeviceInfo? {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return null
        val wired = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_LINE_ANALOG
        }
        // HiBy names the balanced jack "balance..." (the same test Hione Music uses).
        return if (port == OutputProfiles.PORT_44) wired.firstOrNull { it.productName?.toString()?.contains("balance", true) == true }
        else wired.firstOrNull { it.productName?.toString()?.contains("balance", true) != true }
    }

    private fun builtInMic(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    fun hasBuiltInMic(ctx: Context): Boolean =
        ctx.getSystemService(AudioManager::class.java)?.let { builtInMic(it) } != null

    /**
     * Play [sweeps] log sweeps (20 Hz to 20 kHz, [SWEEP_SECONDS] each, peak [amplitude]) out of
     * [port] and record each with the built-in mic. Needs RECORD_AUDIO. Blocks for about
     * 10 s per sweep; call off the main thread. [onProgress] gets (sweep index, fraction 0..1).
     */
    suspend fun run(ctx: Context, port: String, sweeps: Int, amplitude: Double, onProgress: (Int, Float) -> Unit): Outcome = withContext(Dispatchers.IO) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return@withContext Outcome(null, 0, -200.0, emptyList(), "No audio service.")
        val notes = ArrayList<String>()
        val out = jackDevice(ctx, port)
        if (out == null) notes.add("Nothing was detected in the $port jack, so the sweep played on the current output.")
        val mic = builtInMic(am)
        val sweep = ResponseMath.logSweep(RATE, 20.0, 20000.0, SWEEP_SECONDS, amplitude)
        val stereo = FloatArray(sweep.size * 2) { sweep[it / 2] }
        val total = ((LEAD_SECONDS + SWEEP_SECONDS + TAIL_SECONDS) * RATE).toInt()
        val lead = (LEAD_SECONDS * RATE).toInt()

        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        // Pause whatever is playing (Hione Music gives up focus) for the length of the run.
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).setAudioAttributes(attrs).build()
        runCatching { am.requestAudioFocus(focus) }

        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        notes.add(if (unprocessed) "Recorded with the unprocessed mic source." else "Recorded with the voice recognition mic source (this device has no unprocessed source).")

        val results = ArrayList<ResponseMath.SweepResult>()
        var peakDb = -200.0
        var error: String? = null
        try {
            for (s in 0 until sweeps) {
                onProgress(s, 0f)
                val rec = recordOne(ctx, source, mic, out, attrs, stereo, total, lead) { f -> onProgress(s, f) }
                if (rec == null) { error = "The mic could not be opened. Check the microphone permission."; break }
                var pk = 0f
                var sum = 0.0
                for (i in lead until minOf(rec.size, lead + sweep.size)) { val a = kotlin.math.abs(rec[i]); if (a > pk) pk = a; sum += a * a }
                val rmsDb = 10 * log10(max(sum / sweep.size, 1e-20))
                peakDb = max(peakDb, 20 * log10(max(pk.toDouble(), 1e-10)))
                Log.i(TAG, "sweep $s: recorded peak ${"%.1f".format(20 * log10(max(pk.toDouble(), 1e-10)))} dBFS, rms ${"%.1f".format(rmsDb)} dBFS")
                if (pk >= 0.99f) notes.add("Sweep ${s + 1} clipped the mic. Turn the volume down and measure again.")
                results.add(ResponseMath.deconvolve(rec, sweep, RATE, 20.0, 20000.0))
                onProgress(s, 1f)
                if (s < sweeps - 1) delay(400)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "measurement failed", t)
            error = "Measurement failed: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            runCatching { am.abandonAudioFocusRequest(focus) }
        }
        if (error != null || results.isEmpty()) return@withContext Outcome(null, results.size, peakDb, notes, error ?: "No sweep was recorded.")
        if (peakDb < -60) notes.add("The mic barely heard the sweep (peak ${"%.0f".format(peakDb)} dBFS). The curve is mostly noise. Raise the volume a little and try again.")
        Outcome(ResponseMath.combineSweeps(results), results.size, peakDb, notes, null)
    }

    private suspend fun recordOne(
        ctx: Context, source: Int, mic: AudioDeviceInfo?, out: AudioDeviceInfo?, attrs: AudioAttributes,
        stereo: FloatArray, total: Int, lead: Int, onProgress: (Float) -> Unit,
    ): FloatArray? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) return null
        val record = try {
            @Suppress("MissingPermission")
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(max(minBuf * 4, RATE * 4))
                .build()
        } catch (t: Throwable) { Log.w(TAG, "AudioRecord: $t"); return null }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return null }
        mic?.let { record.setPreferredDevice(it) }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(stereo.size * 4)
            .build()
        out?.let { track.setPreferredDevice(it) }
        track.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING)
        val buf = FloatArray(total)
        var got = 0
        var played = false
        try {
            record.startRecording()
            while (got < total) {
                if (!played && got >= lead) { track.play(); played = true }
                val n = record.read(buf, got, minOf(2048, total - got), AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                got += n
                if (got % (RATE / 4) < 2048) onProgress(got.toFloat() / total)
            }
        } finally {
            runCatching { record.stop() }; record.release()
            runCatching { track.stop() }; track.release()
        }
        return if (got < total / 2) null else buf
    }
}

/** Plain-text helpers the card shares with the doc screenshots. */
internal fun fmtRate(hz: Int): String = when (hz % 1000) {
    0 -> "${hz / 1000} kHz"
    else -> String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0)
}

/** "24-bit 44.1 kHz · native" (Settings.Global hione_now_playing_format) -> 44100. */
internal fun parseNowPlayingRate(format: String?): Int? {
    val m = Regex("""(\d+(?:\.\d+)?)\s*kHz""").find(format ?: return null) ?: return null
    val hz = (m.groupValues[1].toDoubleOrNull() ?: return null) * 1000
    return DacFilterModel.RATES.minByOrNull { kotlin.math.abs(it - hz) }?.takeIf { kotlin.math.abs(it - hz) < 500 }
}
