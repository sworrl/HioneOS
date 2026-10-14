package com.hione.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors

/**
 * Publisher for `Settings.Global.hione_now_playing_format`.
 *
 * WHY THIS EXISTS. Three OS surfaces read that key — the launcher status bar's quality chip, the
 * lockscreen's format badge and the anatomical observatory's now-playing pill — and until now
 * nothing in the tree ever wrote it. Each consumer had been taught, separately, to render the
 * absence honestly ("— (not published)", a hidden badge, an empty chip), which was the right call
 * at the time but left three dead surfaces. Before that they were worse than dead: the observatory
 * read the unset key as "idle" and declared the device idle with music playing, and the lockscreen
 * fell back to a hardcoded "DTA 24/96" that was a pure invention.
 *
 * WHERE THE NUMBERS COME FROM. [onTrackInitialized] is called from the sink immediately after the
 * real [AudioTrack] is constructed, and reads [AudioTrack.getFormat] — the format the platform
 * actually GRANTED, not the one we asked for. That distinction is the whole point. The request can
 * be refused (see the platform-minimum buffer fallback in HioneDirectAudioSink) and the policy can
 * land the track on a mixer at a different rate than the file; reading back means the chip shows
 * what is really on the wire.
 *
 * WHAT IT DELIBERATELY DOES NOT CLAIM. It never prints "DIRECT" or "bit-perfect". There is no
 * public API to read an AudioTrack's granted output flags, so a DIRECT claim would be an inference
 * dressed as a measurement, which is exactly the class of defect the fake-data sweeps exist to
 * remove. What it can state as fact is whether the sink resampled: output rate against the decoded
 * input rate is a comparison of two known numbers. "native" means those matched. Nothing more.
 *
 * STRING SHAPE. "24-bit 96 kHz" leads, always, because the status bar parses bit depth and rate
 * out of it with a regex that takes the first match. Qualifiers follow after "·" and use a bare
 * "k" rather than a second "kHz" so they cannot be mistaken for the headline rate.
 */
object HioneNowPlayingFormat {

    private const val TAG = "HioneNowPlayingFormat"
    const val KEY = "hione_now_playing_format"

    /**
     * Writes happen here, never on the caller's thread.
     *
     * [onTrackInitialized] runs on ExoPlayer's playback thread, and Settings.Global.putString is a
     * synchronous binder round trip into the system server. Doing that inline would add an
     * unbounded stall to track-change latency on the one thread that must keep the buffer fed.
     */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hione-nowplaying-format").apply { isDaemon = true }
    }

    /** Last value written, so an unchanged format does not re-enter the system server. */
    @Volatile private var published: String? = null

    /**
     * Bit depth for an [AudioFormat] encoding, or null when the encoding is not linear PCM.
     *
     * ENCODING_PCM_24BIT_PACKED and ENCODING_PCM_32BIT are API 31 symbols against a minSdk 26
     * module, and they are referenced by name on purpose. They are `static final int`, so Kotlin
     * inlines the value at compile time from compileSdk 34 and nothing is looked up on an older
     * device — there is no API-level guard to forget. Naming them also means the values come from
     * the SDK rather than from whoever wrote this line: the 24-bit case is exactly the one that
     * matters on this hardware, and a mistyped literal would have mislabelled every hi-res track
     * while looking entirely plausible.
     */
    @Suppress("InlinedApi")
    private fun bitsOf(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "8"
        AudioFormat.ENCODING_PCM_16BIT -> "16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24"
        AudioFormat.ENCODING_PCM_32BIT -> "32"
        AudioFormat.ENCODING_PCM_FLOAT -> "32f"
        else -> null            // compressed passthrough: there is no "bit depth" to state
    }

    /** "96" for 96000, "44.1" for 44100. Trailing ".0" is dropped; the chip has little room. */
    private fun kHzOf(rateHz: Int): String {
        val tenths = Math.round(rateHz / 100.0).toInt()
        return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    }

    /**
     * Called by the sink once per AudioTrack construction, on the playback thread.
     *
     * @param track the live track, read back for the format the platform granted
     * @param decodedSampleRateHz the rate the decoder is producing, for the resample comparison;
     *   pass 0 when it is not known, and no claim is made either way
     * @param bluetoothRoute true when the output is A2DP/BT, where the sink keeps Media3's buffer
     *   and the policy refuses DIRECT regardless of what we ask for
     */
    @JvmStatic
    fun onTrackInitialized(
        ctx: Context?,
        track: AudioTrack?,
        decodedSampleRateHz: Int,
        bluetoothRoute: Boolean,
    ) {
        if (ctx == null || track == null) return
        val granted = runCatching { track.format }.getOrNull() ?: return
        val rate = granted.sampleRate
        val bits = bitsOf(granted.encoding)
        if (rate <= 0 || bits == null) {
            // A rate of 0 or a non-PCM encoding is not something to paraphrase. Clear the key so
            // the consumers fall back to their honest "not published" states.
            clear(ctx)
            return
        }

        val parts = ArrayList<String>(3)
        parts += "$bits-bit ${kHzOf(rate)} kHz"
        if (decodedSampleRateHz > 0) {
            parts += if (decodedSampleRateHz == rate) "native"
            else "resampled from ${kHzOf(decodedSampleRateHz)}k"
        }
        if (bluetoothRoute) parts += "Bluetooth"

        write(ctx, parts.joinToString(" · "))
    }

    /** Playback stopped or the format became unknowable: publish nothing rather than the last one. */
    @JvmStatic
    fun clear(ctx: Context?) {
        if (ctx == null) return
        write(ctx, "")
    }

    private fun write(ctx: Context, value: String) {
        if (value == published) return
        published = value
        val app = ctx.applicationContext ?: ctx
        io.execute {
            try {
                Settings.Global.putString(app.contentResolver, KEY, value)
                if (value.isNotEmpty()) Log.i(TAG, "published format: $value")
            } catch (t: Throwable) {
                // A failed write means the surfaces keep showing their unset state, which is the
                // correct outcome. Drop the memo of it so the next attempt is not short-circuited
                // by the dedupe above.
                published = null
                Log.w(TAG, "could not publish $KEY: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
