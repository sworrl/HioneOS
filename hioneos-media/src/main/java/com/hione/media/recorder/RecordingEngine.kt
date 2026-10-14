package com.hione.media.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** Output formats. AMR/3GPP exist only for legacy "attach a voice clip" pickers that ask for them. */
enum class RecFormat(val ext: String, val mime: String, val label: String) {
    M4A("m4a", "audio/mp4", "M4A"),
    WAV("wav", "audio/x-wav", "WAV"),
    AMR("amr", "audio/amr", "AMR"),
    THREE_GPP("3gp", "audio/3gpp", "3GPP")
}

interface RecordingEngine {
    /** Human description shown under the timer, e.g. "WAV 48 kHz 24-bit". */
    val description: String
    fun start()
    fun pause()
    fun resume()
    /** Stops and finalises the file. Safe to call twice. */
    fun stop()
    /** Peak level since the last call, 0..1 on a dB scale. */
    fun pollLevel(): Float
}

/** Map a linear peak (0..1) to 0..1 over a 60 dB window, which is how meters read to people. */
internal fun peakToLevel(peak: Float): Float {
    if (peak <= 0f) return 0f
    val db = 20f * log10(peak)
    return ((db + 60f) / 60f).coerceIn(0f, 1f)
}

/**
 * Compressed recording through MediaRecorder: AAC in MP4 (.m4a) by default, AMR for the legacy
 * picker types. MediaRecorder has native pause/resume (API 24+) and reports the peak since the
 * last poll through getMaxAmplitude, which is all the meter needs.
 */
class MediaRecorderEngine(
    private val ctx: Context,
    private val pfd: ParcelFileDescriptor,
    private val format: RecFormat,
    private val maxBytes: Long,
    private val onLimit: () -> Unit
) : RecordingEngine {
    private var rec: MediaRecorder? = null
    private var stopped = false

    override val description: String = when (format) {
        RecFormat.AMR, RecFormat.THREE_GPP -> "${format.label} 8 kHz voice"
        else -> "M4A AAC 48 kHz 192 kbps"
    }

    override fun start() {
        val r = MediaRecorder(ctx)
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        when (format) {
            RecFormat.AMR -> {
                r.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                r.setAudioSamplingRate(8000)
            }
            RecFormat.THREE_GPP -> {
                r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                r.setAudioSamplingRate(8000)
            }
            else -> {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(48000)
                r.setAudioEncodingBitRate(192_000)
                r.setAudioChannels(1)
            }
        }
        r.setOutputFile(pfd.fileDescriptor)
        if (maxBytes > 0) r.setMaxFileSize(maxBytes)
        r.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimit()
        }
        r.prepare()
        r.start()
        rec = r
    }

    override fun pause() { runCatching { rec?.pause() } }
    override fun resume() { runCatching { rec?.resume() } }

    override fun stop() {
        if (stopped) return
        stopped = true
        rec?.let {
            // stop() throws if no audio was captured at all (stopped within a few ms of start).
            runCatching { it.stop() }.onFailure { e -> Log.w("HioneRecorder", "MediaRecorder.stop", e) }
            it.release()
        }
        rec = null
    }

    override fun pollLevel(): Float = peakToLevel((rec?.let { runCatching { it.maxAmplitude }.getOrDefault(0) } ?: 0) / 32767f)
}

/**
 * Lossless WAV through AudioRecord. Tries 48 kHz 24-bit packed PCM first and drops to 16-bit
 * if the input path refuses it, then writes the samples straight to the MediaStore file with a
 * RIFF header that is patched with the real sizes when recording stops.
 *
 * Mono, because the M500 has a single microphone; a stereo file would be two copies of it.
 */
class WavEngine(
    private val pfd: ParcelFileDescriptor,
    private val maxBytes: Long,
    private val onLimit: () -> Unit
) : RecordingEngine {
    private val rate = 48000
    private var record: AudioRecord? = null
    private var bits = 16
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var peak = 0f
    private var dataBytes = 0L
    private var stopped = false

    override val description: String get() = "WAV 48 kHz $bits-bit"

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        for (enc in intArrayOf(AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_16BIT)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, enc)
            if (min <= 0) continue
            val r = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(enc).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(max(min * 4, rate))
                    .build()
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                bits = if (enc == AudioFormat.ENCODING_PCM_24BIT_PACKED) 24 else 16
                return r
            }
            r?.release()
        }
        return null
    }

    override fun start() {
        val r = open() ?: throw IllegalStateException("Microphone could not be opened")
        record = r
        val out = FileOutputStream(pfd.fileDescriptor)
        out.channel.position(0)
        out.write(header(0))
        running = true
        r.startRecording()
        val bytesPerSample = bits / 8
        thread = Thread({
            // ~20 ms per read keeps the meter lively without a wakeup storm.
            val buf = ByteArray(rate / 50 * bytesPerSample)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (paused) continue
                var p = 0
                var i = 0
                if (bits == 24) {
                    while (i + 2 < n) {
                        val s = (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8) or (buf[i + 2].toInt() shl 16)
                        val a = abs(s); if (a > p) p = a
                        i += 3
                    }
                    peak = max(peak, p / 8388607f)
                } else {
                    while (i + 1 < n) {
                        val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                        val a = abs(s); if (a > p) p = a
                        i += 2
                    }
                    peak = max(peak, p / 32767f)
                }
                var len = n
                if (maxBytes > 0 && dataBytes + 44 + len > maxBytes) {
                    len = ((maxBytes - 44 - dataBytes).coerceAtLeast(0) / bytesPerSample * bytesPerSample).toInt()
                }
                if (len > 0) {
                    runCatching { out.write(buf, 0, len) }.onFailure { running = false }
                    dataBytes += len
                }
                if (maxBytes > 0 && dataBytes + 44 >= maxBytes - bytesPerSample) {
                    running = false
                    onLimit()
                }
            }
        }, "HioneWavWriter").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    override fun pause() { paused = true }
    override fun resume() { paused = false }

    override fun stop() {
        if (stopped) return
        stopped = true
        running = false
        runCatching { record?.stop() }
        thread?.join(1500)
        record?.release()
        record = null
        // Patch the RIFF sizes now that the length is known.
        runCatching {
            val ch = FileOutputStream(pfd.fileDescriptor).channel
            ch.position(0)
            ch.write(ByteBuffer.wrap(header(dataBytes)))
            ch.force(true)
        }.onFailure { Log.w("HioneRecorder", "WAV header patch failed", it) }
    }

    override fun pollLevel(): Float {
        val p = peak
        peak = 0f
        return peakToLevel(p)
    }

    private fun header(data: Long): ByteArray {
        val bytesPerSample = bits / 8
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + data).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1)                                  // PCM
        b.putShort(1)                                  // mono
        b.putInt(rate)
        b.putInt(rate * bytesPerSample)                // byte rate
        b.putShort(bytesPerSample.toShort())           // block align
        b.putShort(bits.toShort())
        b.put("data".toByteArray()); b.putInt(data.toInt())
        return b.array()
    }
}
