package com.hione.player.bpm

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Live beat tracking on the player's own PCM.
 *
 * WHERE THE AUDIO COMES FROM. HioneDirectAudioSink tees every buffer it writes to its AudioTrack
 * into [offer], the same point the TV cast tap and the multi-output mirrors use. These are the
 * file's own samples at the file's own rate. The launcher's Visualizer on session 0 never saw
 * them: the DIRECT path bypasses the mixer, so for bit-perfect playback that detector heard
 * silence and the BPM game ran on whatever the player's pulse loop guessed.
 *
 * WHAT HAPPENS TO IT. A worker thread downmixes to mono and feeds libhionebeat's BTrack (which
 * resamples to 44.1 kHz itself). BTrack reports beats as sample positions in the stream written
 * to the track, which is ahead of what is audible by the track's buffer. Each position is turned
 * into the moment it leaves the DAC with AudioTrack.getTimestamp (frame N was presented at time
 * T), and handed to [HioneBeatClock]. Because the tap runs ahead of playback, a beat is known
 * before it is heard, so the pulses go out on time instead of a beat late.
 *
 * SEEDING. [seed] gives BTrack the track's file tempo as soon as it is known, so it starts on the
 * right tempo and, with a confident file tempo, stays there and only tracks phase.
 *
 * AUDIO THREAD RULES. [offer], [onPrimaryTrack] and [onPrimaryFlush] run on the playback thread.
 * They copy into a ring, bump a counter or swap a reference. They never block or allocate.
 *
 * COST. Nothing runs unless [setWanted] is on (Hione Music turns it on while playing with the
 * screen on). When on: one 1024-point FFT per 11.6 ms of audio plus BTrack's tempo update on each
 * beat, measured at 3.5 to 4.5 % of one core on the M500 for a 16/44.1 file.
 */
object HioneBeatTap {
    private const val TAG = "HioneBeatTap"
    private const val CAPACITY = 1 shl 20

    private val ring = ByteArray(CAPACITY)
    /** Bytes offered since the current track was built. */
    private val written = AtomicLong(0L)

    @Volatile private var track: AudioTrack? = null
    @Volatile private var rate = 0
    @Volatile private var frameSize = 0
    @Volatile private var bytesPerSample = 0
    @Volatile private var isFloat = false
    /** Bumped on every new track or flush; the worker restarts its tracker when it changes. */
    @Volatile private var epoch = 0
    /** Bumped on every flush (a seek), for the pulse loop to re-place the grid. */
    @Volatile var flushCount = 0
        private set

    @Volatile private var wanted = false
    @Volatile private var seedBpm = 0.0
    @Volatile private var seedFixed = false
    @Volatile private var seedGen = 0

    private var worker: Thread? = null
    private val lock = Object()

    // ------------------------------------------------------------------ playback thread

    /**
     * The sink built a new AudioTrack. [encoding] is the Media3 C.ENCODING_* of what is written;
     * only PCM is tracked.
     */
    @JvmStatic
    fun onPrimaryTrack(t: AudioTrack, sampleRate: Int, pcmFrameSize: Int, encoding: Int, isPcm: Boolean) {
        val bps = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        if (!isPcm || bps == 0 || pcmFrameSize <= 0 || sampleRate <= 0) {
            track = null
            frameSize = 0
            epoch++
            return
        }
        rate = sampleRate
        bytesPerSample = bps
        isFloat = encoding == C.ENCODING_PCM_FLOAT
        frameSize = pcmFrameSize
        written.set(0L)
        track = t
        epoch++
    }

    /** A seek or stop: the track is about to be released, its positions mean nothing any more. */
    @JvmStatic
    fun onPrimaryFlush() {
        track = null
        epoch++
        flushCount++
    }

    /** AUDIO THREAD. Leaves the buffer's position as found. */
    @JvmStatic
    fun offer(buffer: ByteBuffer, size: Int) {
        if (size <= 0) return
        if (!wanted || track == null) { written.addAndGet(size.toLong()); return }
        val pos = buffer.position()
        try {
            val start = (written.get() % CAPACITY).toInt()
            val first = minOf(size, CAPACITY - start)
            buffer.get(ring, start, first)
            if (first < size) buffer.get(ring, 0, size - first)
        } catch (_: Throwable) {
        } finally {
            buffer.position(pos)
            written.addAndGet(size.toLong())
        }
    }

    // ------------------------------------------------------------------ control

    /** Run the tracker or not. Off costs nothing but a counter on the audio thread. */
    fun setWanted(on: Boolean) {
        if (on && !HioneBeatNative.available) return
        wanted = on
        if (on) ensureWorker() else synchronized(lock) { lock.notifyAll() }
    }

    /** File tempo for the current track (0 = none). [fixed] holds BTrack on it. */
    fun seed(bpm: Float, fixed: Boolean) {
        seedBpm = bpm.toDouble()
        seedFixed = fixed && bpm > 0f
        seedGen++
    }

    private fun ensureWorker() {
        synchronized(lock) {
            if (worker?.isAlive == true) { lock.notifyAll(); return }
            worker = Thread({ loop() }, "HioneBeatTap").apply { isDaemon = true; start() }
        }
    }

    // ------------------------------------------------------------------ worker

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var handle = 0L
        var myEpoch = -1
        var mySeedGen = -1
        var cursor = 0L        // bytes of this track already consumed
        var originFrame = 0L   // track frame where the tracker's frame 0 is
        var skipped = 0L       // frames dropped after the tracker started (it never saw them)
        var myRate = 0
        val chunk = ByteArray(16 * 1024)
        var mono = FloatArray(4096)
        val beats = DoubleArray(8)
        val ts = AudioTimestamp()
        try {
            while (true) {
                if (!wanted) {
                    if (handle != 0L) { HioneBeatNative.liveRelease(handle); handle = 0L }
                    myEpoch = -1
                    synchronized(lock) { if (!wanted) lock.wait(5_000) }
                    continue
                }
                val t = track
                val fs = frameSize
                if (t == null || fs <= 0) { Thread.sleep(50); continue }

                if (epoch != myEpoch || rate != myRate || handle == 0L) {
                    if (handle != 0L) HioneBeatNative.liveRelease(handle)
                    myEpoch = epoch
                    myRate = rate
                    handle = HioneBeatNative.liveCreate(myRate)
                    // Start from what is being written now, not from a backlog.
                    cursor = written.get() / fs * fs
                    originFrame = cursor / fs
                    skipped = 0L
                    mySeedGen = -1
                }
                if (seedGen != mySeedGen) {
                    mySeedGen = seedGen
                    if (seedBpm > 0.0) HioneBeatNative.liveSeed(handle, seedBpm, seedFixed)
                    else HioneBeatNative.liveUnfix(handle)
                }

                val w = written.get()
                var avail = w - cursor
                if (avail > CAPACITY / 2) {
                    // Fell behind (the thread was starved): skip to recent audio. BTrack copes with
                    // the gap; the frame accounting must jump with it.
                    val skip = (avail - CAPACITY / 4) / fs * fs
                    cursor += skip
                    skipped += skip / fs
                    avail = w - cursor
                }
                val n = (minOf(avail, chunk.size.toLong()) / fs * fs).toInt()
                if (n <= 0) { Thread.sleep(15); continue }
                val start = (cursor % CAPACITY).toInt()
                val first = minOf(n, CAPACITY - start)
                System.arraycopy(ring, start, chunk, 0, first)
                if (first < n) System.arraycopy(ring, 0, chunk, first, n - first)
                cursor += n

                val frames = n / fs
                if (mono.size < frames) mono = FloatArray(frames)
                toMono(chunk, frames, fs, mono)
                val got = HioneBeatNative.liveFeed(handle, mono, frames, beats)
                if (got <= 0) continue
                if (epoch != myEpoch) continue
                val tr = track ?: continue
                val okTs = runCatching { tr.getTimestamp(ts) }.getOrDefault(false)
                if (!okTs || ts.framePosition <= 0L) continue
                // AudioTimestamp.nanoTime is CLOCK_MONOTONIC; the grid lives on elapsedRealtime.
                val monoToElapsed = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
                for (i in 0 until got) {
                    val trackFrame = originFrame + skipped + beats[i]
                    val presentNs = ts.nanoTime + ((trackFrame - ts.framePosition) * 1e9 / myRate).toLong()
                    HioneBeatClock.onLiveBeat(presentNs + monoToElapsed)
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.e(TAG, "beat tap worker died", t)
        } finally {
            if (handle != 0L) HioneBeatNative.liveRelease(handle)
            synchronized(lock) { worker = null }
        }
    }

    private fun toMono(src: ByteArray, frames: Int, fs: Int, out: FloatArray) {
        val bps = bytesPerSample
        val ch = fs / bps
        val inv = 1f / ch
        var p = 0
        for (f in 0 until frames) {
            var s = 0f
            for (c in 0 until ch) {
                s += when (bps) {
                    2 -> ((src[p].toInt() and 0xFF) or (src[p + 1].toInt() shl 8)) / 32768f
                    3 -> ((src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8) or (src[p + 2].toInt() shl 16)) / 8388608f
                    else -> {
                        val v = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8) or
                            ((src[p + 2].toInt() and 0xFF) shl 16) or (src[p + 3].toInt() shl 24)
                        if (isFloat) java.lang.Float.intBitsToFloat(v) else v / 2147483648f
                    }
                }
                p += bps
            }
            out[f] = s * inv
        }
    }
}
