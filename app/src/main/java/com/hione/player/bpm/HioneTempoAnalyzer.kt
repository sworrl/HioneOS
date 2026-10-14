package com.hione.player.bpm

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Whole-track tempo for one file, cached in [HioneTempoDb].
 *
 * ORDER OF SOURCES.
 *  1. The cache row, when the file's size and mtime still match.
 *  2. The file's own BPM tag (ID3 TBPM, Vorbis BPM, MP4 tmpo), but only when it reads 60..200.
 *     Taggers write 0, 999, or a value in the wrong unit often enough that anything outside that
 *     range is ignored and the file is analysed instead.
 *  3. Analysis: MediaExtractor + MediaCodec decode of up to [MAX_ANALYSE_SEC] seconds, handed to
 *     libhionebeat, which downmixes to mono, resamples to 44.1 kHz and runs QM-DSP's
 *     TempoTrackV2 (the beat tracker Mixxx uses). A constant grid is fitted to the tracked beats
 *     and the tempo is folded by octaves into 70..180 BPM. Confidence is how regular the beats
 *     are around that grid.
 *
 * WHY THE WHOLE TRACK. The 0.3.0 analyzer looked at 32 s from a quarter of the way in. A quiet
 * bridge or a breakdown in that window gave a different answer from the rest of the song, which is
 * a large part of why the same track could show two tempos on two days.
 */
object HioneTempoAnalyzer {
    private const val TAG = "HioneTempoAnalyzer"

    /** Bump when the analysis changes enough that cached rows should be redone. */
    const val VERSION = 3

    /** Long files (DJ mixes, whole-disc rips without a cue split) are analysed for this long. */
    const val MAX_ANALYSE_SEC = 480

    /** Below this the tempo is kept in the cache but not shown as the track's tempo. */
    const val MIN_SHOW_CONFIDENCE = 0.3f

    const val SOURCE_TAG = "tag"
    const val SOURCE_ANALYSIS = "qm-tempotrackv2"

    /** Set by the first caller with a Context, for path-only entry points (HioneBpmAnalyzer, BpmEngine). */
    @Volatile var appContext: Context? = null
        private set

    fun attach(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    private val inFlight = ConcurrentHashMap<String, Any>()
    /** One decode at a time across the app: the library job and now-playing never stack up. */
    private val gate = Semaphore(1, true)
    private val foregroundWaiting = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Cached or fresh tempo for [path]. With [allowAnalysis] false only the cache and the tag are
     * consulted. Returns null when nothing could be read. Blocking: call off the main thread.
     */
    fun resolve(
        ctx: Context,
        path: String,
        clipStartMs: Long = 0L,
        clipEndMs: Long = 0L,
        allowAnalysis: Boolean = true,
        shouldStop: () -> Boolean = { false },
        background: Boolean = false,
    ): HioneTempoDb.Row? {
        if (path.isBlank()) return null
        attach(ctx)
        val f = File(path)
        if (!f.isFile) return null
        val db = HioneTempoDb.get(ctx)
        db.lookup(path, clipStartMs)?.let { return it }

        if (clipStartMs == 0L) {
            val tag = HioneBpmAnalyzer.readTaggedBpm(path)
            if (tag != null && tag in 60f..200f) {
                val row = HioneTempoDb.Row(
                    path, 0L, f.length(), f.lastModified(), tag, tag, 1f, -1.0, SOURCE_TAG,
                    VERSION, System.currentTimeMillis()
                )
                db.store(row)
                return row
            }
        }
        if (!allowAnalysis || !HioneBeatNative.available) return null

        val key = "$path#$clipStartMs"
        val lock = inFlight.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            try {
                db.lookup(path, clipStartMs)?.let { return it }
                // The library job gives way: a now-playing request waiting for the gate stops the
                // background decode at its next buffer, and the job retries that file later.
                val stop: () -> Boolean = if (background) ({ shouldStop() || foregroundWaiting.get() > 0 }) else shouldStop
                if (!background) foregroundWaiting.incrementAndGet()
                try { gate.acquire() } finally { if (!background) foregroundWaiting.decrementAndGet() }
                try {
                    if (stop()) return null
                    val r = analyse(path, clipStartMs, clipEndMs, stop) ?: return null
                    // r[0] == 0: decoded fine but no steady beat. Stored as bpm 0 so the library
                    // job does not decode it again until the file changes.
                    val ok = r[0] > 0.5
                    val row = HioneTempoDb.Row(
                        path, clipStartMs, f.length(), f.lastModified(),
                        if (ok) r[1].toFloat() else 0f, if (ok) r[2].toFloat() else 0f,
                        if (ok) r[3].toFloat() else 0f, if (ok) r[4] * 1000.0 else -1.0,
                        SOURCE_ANALYSIS, VERSION, System.currentTimeMillis()
                    )
                    db.store(row)
                    return row
                } finally {
                    gate.release()
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** Displayable tempo of a row, or 0 when it should read as unknown. */
    fun shownBpm(row: HioneTempoDb.Row?): Float =
        if (row != null && row.bpm > 0f && row.confidence >= MIN_SHOW_CONFIDENCE) row.bpm else 0f

    /**
     * Decodes and analyses. Returns libhionebeat's result array (r[0] is 0 when no steady beat was
     * found), or null when the file could not be decoded or the analysis was stopped. Nothing is
     * cached for a null, so a later attempt can still succeed.
     */
    private fun analyse(path: String, clipStartMs: Long, clipEndMs: Long, shouldStop: () -> Boolean): DoubleArray? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var handle = 0L
        try {
            extractor.setDataSource(path)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val tf = extractor.getTrackFormat(i)
                if ((tf.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { trackIdx = i; format = tf; break }
            }
            if (trackIdx < 0 || format == null) return null
            extractor.selectTrack(trackIdx)
            if (clipStartMs > 0) extractor.seekTo(clipStartMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val endUs = if (clipEndMs > clipStartMs) clipEndMs * 1000L else Long.MAX_VALUE
            val capUs = clipStartMs * 1000L + MAX_ANALYSE_SEC * 1_000_000L
            val stopUs = minOf(endUs, capUs)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            // Ask for float output so 24-bit files are not truncated; decoders that cannot do it
            // ignore the key and hand back 16-bit, which the loop below also handles.
            runCatching { format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT) }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var buf = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            var idle = 0

            while (!outEos) {
                if (shouldStop()) return null
                if (!inEos) {
                    val ix = codec.dequeueInputBuffer(10_000)
                    if (ix >= 0) {
                        val ib = codec.getInputBuffer(ix)!!
                        val n = extractor.readSampleData(ib, 0)
                        val t = extractor.sampleTime
                        if (n < 0 || t > stopUs) {
                            codec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(ix, 0, n, t, 0)
                            extractor.advance()
                        }
                    }
                }
                val ox = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    ox >= 0 -> {
                        idle = 0
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                        val out = codec.getOutputBuffer(ox)
                        val keep = info.presentationTimeUs >= clipStartMs * 1000L - 50_000L
                        if (out != null && info.size > 0 && keep) {
                            if (handle == 0L) {
                                handle = HioneBeatNative.offlineCreate(rate, channels)
                                if (handle == 0L) return null
                            }
                            out.order(ByteOrder.LITTLE_ENDIAN)
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val bytesPerSample = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                else -> 2
                            }
                            val samples = info.size / bytesPerSample
                            if (buf.size < samples) buf = FloatArray(samples)
                            when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> out.asFloatBuffer().get(buf, 0, samples)
                                AudioFormat.ENCODING_PCM_32BIT -> {
                                    val ib = out.asIntBuffer()
                                    for (i in 0 until samples) buf[i] = ib.get(i) / 2147483648f
                                }
                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                    var p = info.offset
                                    for (i in 0 until samples) {
                                        val v = (out.get(p).toInt() and 0xFF) or ((out.get(p + 1).toInt() and 0xFF) shl 8) or (out.get(p + 2).toInt() shl 16)
                                        buf[i] = v / 8388608f
                                        p += 3
                                    }
                                }
                                AudioFormat.ENCODING_PCM_8BIT -> for (i in 0 until samples) buf[i] = ((out.get(info.offset + i).toInt() and 0xFF) - 128) / 128f
                                else -> {
                                    val sb = out.asShortBuffer()
                                    for (i in 0 until samples) buf[i] = sb.get(i) / 32768f
                                }
                            }
                            HioneBeatNative.offlineFeed(handle, buf, samples / channels)
                        }
                        codec.releaseOutputBuffer(ox, false)
                        if (info.presentationTimeUs > stopUs) outEos = true
                    }
                    ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        if (handle != 0L) {
                            // A format change mid-stream is rare; the analysis so far is for the
                            // old format, so start over with the new one.
                            HioneBeatNative.offlineRelease(handle)
                            handle = 0L
                        }
                    }
                    else -> if (inEos && ++idle > 300) outEos = true // decoder stalled after EOS
                }
            }
            if (handle == 0L) return null
            val r = HioneBeatNative.offlineFinish(handle) ?: return null
            Log.i(TAG, "analysed ${File(path).name}: ${"%.2f".format(r[1])} BPM (raw ${"%.2f".format(r[2])}, " +
                "conf ${"%.2f".format(r[3])}, ${r[5].toInt()} beats, ${"%.0f".format(r[6])} s) in " +
                "${android.os.SystemClock.elapsedRealtime() - t0} ms")
            return r
        } catch (t: Throwable) {
            Log.w(TAG, "analysis failed for $path: ${t.message}")
            return null
        } finally {
            if (handle != 0L) HioneBeatNative.offlineRelease(handle)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
