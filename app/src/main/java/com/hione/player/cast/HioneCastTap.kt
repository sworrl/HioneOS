package com.hione.player.cast

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * The point where PCM leaves for the TV.
 *
 * WHY HERE AND NOT MediaProjection. The obvious way to mirror a device's audio is
 * AudioPlaybackCapture, and it is the wrong way for us. That API taps AFTER the system mixer, so
 * what you capture has already been resampled to the mixer's rate (48 kHz here) and had every
 * other stream folded in. Mirroring a 24/96 FLAC through it would hand the TV 48 kHz of
 * already-degraded audio, which defeats the point of the device.
 *
 * We own the decoder, so we do not have to capture anything. [HioneDirectAudioSink] holds the
 * decoded buffer immediately before `AudioTrack.write`, at the file's native rate and depth,
 * having bypassed the mixer entirely (see the DIRECT output work). Teeing there means the samples
 * the TV receives are the samples in the file. That is bit-perfect mirroring, which the usual
 * capture route cannot do.
 *
 * COST. [offer] runs on the audio thread, once per buffer. It must never block, never allocate
 * beyond a copy, and never throw, or it will glitch playback on the device itself. Everything here
 * is written to that constraint: a bounded ring, a copy, an index bump. If no TV is listening it
 * returns on the first line.
 */
object HioneCastTap {

    private const val TAG = "HioneCastTap"

    /**
     * Ring capacity in bytes.
     *
     * 4 MiB is about 5.5 seconds of 24/96 stereo, which is far more than the network needs and
     * deliberately so: it absorbs a Wi-Fi stall without the audio thread ever waiting. When the
     * ring does fill, the OLDEST audio is dropped rather than the newest, because a listener that
     * has fallen behind wants to catch up to live, not replay a backlog.
     */
    private const val CAPACITY = 4 * 1024 * 1024

    private val ring = ByteArray(CAPACITY)

    /** Monotonic count of bytes ever offered. Readers track their own position against it. */
    private val written = AtomicLong(0L)

    @Volatile private var enabled = false
    @Volatile var format: CastFormat? = null
        private set

    /** Bytes dropped because every reader fell behind. Reported, never hidden. */
    val overruns = AtomicLong(0L)

    data class CastFormat(
        val sampleRate: Int,
        /** Bytes per sample per channel: 2 for 16-bit, 3 for packed 24, 4 for 32. */
        val bytesPerSample: Int,
        val channelCount: Int,
        /** Media3 `C.Encoding` of the stream, passed through so the TV configures identically. */
        val encoding: Int,
    ) {
        val frameSize: Int get() = bytesPerSample * channelCount
        val byteRate: Int get() = frameSize * sampleRate
    }

    /**
     * Notified when casting starts or stops, so the sink can re-apply its gain immediately.
     * Without this the local output would stay audible until the next configure().
     */
    @Volatile private var stateListener: Runnable? = null

    @JvmStatic
    fun setStateListener(r: Runnable?) { stateListener = r }

    /** Turned on only while a TV is actually connected, so the tap costs nothing when unused. */
    @JvmStatic
    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        Log.i(TAG, if (on) "tap enabled, local output muted" else "tap disabled, local output restored")
        runCatching { stateListener?.run() }
    }

    @JvmStatic
    fun isEnabled(): Boolean = enabled

    /** Called by the sink when the output format changes, before any [offer] for that format. */
    @JvmStatic
    fun onFormat(sampleRate: Int, bytesPerSample: Int, channelCount: Int, encoding: Int) {
        val f = CastFormat(sampleRate, bytesPerSample, channelCount, encoding)
        if (f != format) {
            format = f
            Log.i(TAG, "format ${f.sampleRate}Hz ${f.bytesPerSample * 8}-bit x${f.channelCount}")
        }
    }

    /**
     * Offer one buffer of decoded PCM. AUDIO THREAD. Must not block or allocate meaningfully.
     *
     * The buffer's position and limit are left exactly as found: the sink hands this to
     * AudioTrack straight afterwards and any change here would corrupt local playback.
     */
    @JvmStatic
    fun offer(buffer: ByteBuffer, size: Int) {
        if (!enabled || size <= 0) return
        val pos = buffer.position()
        try {
            val start = (written.get() % CAPACITY).toInt()
            val first = minOf(size, CAPACITY - start)
            buffer.get(ring, start, first)
            if (first < size) buffer.get(ring, 0, size - first)
            written.addAndGet(size.toLong())
        } catch (t: Throwable) {
            // Never let a tap problem reach the audio thread's caller.
            overruns.addAndGet(size.toLong())
        } finally {
            buffer.position(pos)
        }
    }

    /** Total bytes ever offered; a reader's cursor is compared against this. */
    @JvmStatic
    fun totalWritten(): Long = written.get()

    /**
     * Copy out everything from [cursor] up to now, into [dest].
     *
     * Returns the number of bytes copied and advances nothing: the caller owns its cursor. When a
     * reader has fallen more than [CAPACITY] behind, the data it wanted is gone; rather than send
     * stale or torn audio we skip it forward to the oldest byte still intact and count the loss.
     */
    @JvmStatic
    fun read(cursor: Long, dest: ByteArray): ReadResult {
        val now = written.get()
        var from = cursor
        var skipped = 0L
        val behind = now - from
        if (behind > CAPACITY) {
            skipped = behind - CAPACITY
            from = now - CAPACITY
            overruns.addAndGet(skipped)
        }
        val avail = (now - from).coerceAtMost(dest.size.toLong()).toInt()
        if (avail <= 0) return ReadResult(0, from, skipped)
        val start = (from % CAPACITY).toInt()
        val first = minOf(avail, CAPACITY - start)
        System.arraycopy(ring, start, dest, 0, first)
        if (first < avail) System.arraycopy(ring, 0, dest, first, avail - first)
        return ReadResult(avail, from + avail, skipped)
    }

    data class ReadResult(val bytes: Int, val nextCursor: Long, val skipped: Long)
}
