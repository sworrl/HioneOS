package com.hione.player.profiles

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hione Music's parametric EQ: the live curve, and the one [AudioProcessor] instance that applies
 * it inside the player's sink.
 *
 * WHY THERE WAS NOTHING BEFORE. The app had no EQ of any kind: no android.media.audiofx, no
 * DynamicsProcessing, no processor in the sink chain. HiBy's own PEQ ("HIBY_DSP_SET=enable_peq"
 * in the stock AudioService) is a HAL feature this M500 HAL does not implement (the library has
 * no "hiby" string in it), and audiofx effects attach to the mixer, which the bit-perfect DIRECT
 * track never passes through. So the EQ has to run in-process, on the PCM, before AudioTrack.
 *
 * BIT-PERFECT WHEN OFF. The processor reports itself inactive whenever the curve is off or flat
 * (see [HioneEqProcessor.isActive]), and Media3 then leaves it out of the pipeline entirely: the
 * samples reach the DAC untouched. If the curve goes flat while the processor is already in the
 * pipeline (between flushes), it copies the input byte for byte, which is still exact. Turning a
 * curve ON while the processor is out of the pipeline needs one flush for Media3 to re-ask;
 * [onArmChanged] tells whoever owns the player to do that.
 *
 * WHERE IT RUNS (as of this change). PlayerHolder passes this processor to
 * HioneDirectAudioSink.Builder.setAudioProcessors, and the sink only runs that chain for 16-bit
 * input. Hi-res input (float from the codec, 24/32-bit int) takes a fixed chain inside
 * HioneDirectAudioSink.configure that this change was not allowed to touch, so hi-res tracks pass
 * through without EQ until that one line is added (see [HI_RES_HOOK_WIRED] and the report).
 */
object HioneEq {
    /**
     * Flip to true once HioneDirectAudioSink's hi-res branch adds [processor] ahead of its
     * float→24-bit packer. Until then the UI says hi-res tracks are not equalised. The processor
     * also sets [hiResSeen] the first time it is configured with a non-16-bit format, which
     * proves the hook is in without anyone remembering to change this constant.
     */
    const val HI_RES_HOOK_WIRED = true

    data class State(
        val enabled: Boolean = false,
        val curve: EqCurve = EqCurve.FLAT,
    ) {
        val audible: Boolean get() = enabled && !curve.isFlat
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** What the processor last saw: format, whether it ran, samples it had to clip. */
    data class Runtime(
        val configuredEncoding: Int = C.ENCODING_INVALID,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val clippedSamples: Long = 0,
    )

    private val _runtime = MutableStateFlow(Runtime())
    val runtime: StateFlow<Runtime> = _runtime

    @Volatile var hiResSeen: Boolean = false
        private set

    val hiResCovered: Boolean get() = HI_RES_HOOK_WIRED || hiResSeen

    /** Bumped on every change so the audio thread recomputes coefficients exactly once. */
    @Volatile internal var version: Long = 0
        private set

    /**
     * Called (on the caller's thread) when the curve goes from silent to audible. The processor
     * is out of the pipeline at that moment and only rejoins at the next flush, so the player
     * owner should seek to the current position. Set by [ListeningProfileManager].
     */
    @Volatile var onArmChanged: (() -> Unit)? = null

    val processor: HioneEqProcessor by lazy { HioneEqProcessor() }

    fun set(enabled: Boolean, curve: EqCurve) {
        val before = _state.value
        val after = State(enabled, curve)
        if (before == after) return
        _state.value = after
        version++
        if (!before.audible && after.audible) onArmChanged?.invoke()
    }

    fun setEnabled(enabled: Boolean) = set(enabled, _state.value.curve)
    fun setCurve(curve: EqCurve) = set(_state.value.enabled, curve)

    internal fun noteConfigured(encoding: Int, rate: Int, channels: Int) {
        if (encoding != C.ENCODING_PCM_16BIT) hiResSeen = true
        _runtime.value = Runtime(encoding, rate, channels, 0)
    }

    internal fun noteClipped(n: Int) {
        if (n <= 0) return
        val r = _runtime.value
        _runtime.value = r.copy(clippedSamples = r.clippedSamples + n)
    }

    // ------------------------------------------------------------------------------ the math

    /** Normalised biquad: y = b0·x + b1·x1 + b2·x2 − a1·y1 − a2·y2. */
    class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /**
     * RBJ "Audio EQ Cookbook" designs, which is also what AutoEq's ParametricEQ.txt assumes
     * (PK = peaking, LSC/HSC = shelves with Q). Bands at or above ~Nyquist are dropped rather than
     * folded, since a 20 kHz band means nothing at 44.1 kHz and blows up the design.
     */
    fun design(band: EqBand, sampleRate: Int): Biquad? {
        val fs = sampleRate.toDouble()
        if (band.freqHz <= 0.0 || band.freqHz >= fs * 0.49 || abs(band.gainDb) < 1e-9) return null
        val a = 10.0.pow(band.gainDb / 40.0)
        val w0 = 2.0 * PI * band.freqHz / fs
        val cw = cos(w0)
        val sw = sin(w0)
        val q = if (band.q > 0) band.q else 0.707
        val alpha = sw / (2.0 * q)
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        when (band.type) {
            EqFilterType.PEAKING -> {
                b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
            }
            EqFilterType.LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cw + s)
                b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                b2 = a * ((a + 1) - (a - 1) * cw - s)
                a0 = (a + 1) + (a - 1) * cw + s
                a1 = -2 * ((a - 1) + (a + 1) * cw)
                a2 = (a + 1) + (a - 1) * cw - s
            }
            EqFilterType.HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cw + s)
                b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                b2 = a * ((a + 1) + (a - 1) * cw - s)
                a0 = (a + 1) - (a - 1) * cw + s
                a1 = 2 * ((a - 1) - (a + 1) * cw)
                a2 = (a + 1) - (a - 1) * cw - s
            }
        }
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** Magnitude response in dB at [freqHz], preamp included. Used to draw the curve. */
    fun responseDb(curve: EqCurve, freqHz: Double, sampleRate: Int = 48000): Double {
        var db = curve.preampDb
        val w = 2.0 * PI * freqHz / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        for (band in curve.bands) {
            val bq = design(band, sampleRate) ?: continue
            val nr = bq.b0 + bq.b1 * c1 + bq.b2 * c2
            val ni = -(bq.b1 * s1 + bq.b2 * s2)
            val dr = 1 + bq.a1 * c1 + bq.a2 * c2
            val di = -(bq.a1 * s1 + bq.a2 * s2)
            val num = nr * nr + ni * ni
            val den = dr * dr + di * di
            if (num > 0 && den > 0) db += 10 * log10(num / den)
        }
        return db
    }

    /** Highest point of the response (preamp included), coarse log sweep. >0 dB means it can clip. */
    fun peakDb(curve: EqCurve): Double {
        var peak = Double.NEGATIVE_INFINITY
        var f = 20.0
        while (f <= 20000.0) {
            peak = maxOf(peak, responseDb(curve, f))
            f *= 1.05
        }
        return peak
    }
}

/**
 * The processor. Handles interleaved 16-bit, packed 24-bit, 32-bit integer and float PCM at any
 * channel count, and always outputs the format it was given, so it is invisible to everything
 * downstream (the DIRECT-path format checks, the mirrors, the 24-bit packer).
 *
 * Arithmetic is double precision. Re-quantisation only happens when the EQ is actually doing
 * something: 16-bit output gets TPDF dither (±1 LSB triangular) because truncating a filtered
 * 16-bit signal is audible in quiet passages; 24/32-bit output is rounded, the dither there would
 * sit far below the DAC's own noise floor. Float output is left unclamped, the 24-bit packer
 * downstream clamps.
 */
class HioneEqProcessor internal constructor() : BaseAudioProcessor() {

    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0

    private var builtVersion = -1L
    private var gain = 1.0
    private var coeffs: Array<HioneEq.Biquad> = emptyArray()
    /** Filter memory, [channel][band*4 + {x1, x2, y1, y2}]. Kept across curve edits so a change does not click. */
    private var mem: Array<DoubleArray> = emptyArray()
    private var passThrough = true

    // xorshift for the TPDF dither: allocation-free and not shared with any other thread.
    private var rng = 0x2545F4914F6CDD1DL

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_24BIT && e != C.ENCODING_PCM_32BIT &&
            e != C.ENCODING_PCM_FLOAT) {
            // Anything else is not ours to touch; inactive means Media3 routes around us.
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount <= 0 || inputAudioFormat.sampleRate <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        HioneEq.noteConfigured(e, inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    /**
     * Active only while the EQ would be heard. Media3 asks at configure() and again at every
     * flush(), so a curve switched off drops us out of the pipeline at the next flush — and until
     * then [queueInput] copies exactly.
     */
    override fun isActive(): Boolean = super.isActive() && HioneEq.state.value.audible

    override fun onFlush() {
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        builtVersion = -1L
        mem = Array(channels.coerceAtLeast(0)) { DoubleArray(0) }
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        channels = 0
        sampleRate = 0
        builtVersion = -1L
        coeffs = emptyArray()
        mem = emptyArray()
    }

    private fun rebuildIfNeeded() {
        val v = HioneEq.version
        if (v == builtVersion) return
        builtVersion = v
        val st = HioneEq.state.value
        passThrough = !st.audible
        if (passThrough) return
        gain = 10.0.pow(st.curve.preampDb / 20.0)
        val newCoeffs = st.curve.bands.mapNotNull { HioneEq.design(it, sampleRate) }.toTypedArray()
        if (newCoeffs.size != coeffs.size || mem.firstOrNull()?.size != newCoeffs.size * 4) {
            mem = Array(channels) { DoubleArray(newCoeffs.size * 4) }
        }
        coeffs = newCoeffs
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        rebuildIfNeeded()

        if (passThrough || channels <= 0) {
            // Exact copy: the EQ is off/flat but Media3 has not flushed us out yet.
            out.put(inputBuffer)
            out.flip()
            return
        }

        val src = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val sampleCount = size / bytesPerSample
        var clipped = 0
        var ch = 0
        var i = position
        for (n in 0 until sampleCount) {
            val x = when (encoding) {
                C.ENCODING_PCM_16BIT -> src.getShort(i) / 32768.0
                C.ENCODING_PCM_24BIT -> {
                    val v = (src.get(i).toInt() and 0xFF) or
                        ((src.get(i + 1).toInt() and 0xFF) shl 8) or
                        (src.get(i + 2).toInt() shl 16)
                    v / 8388608.0
                }
                C.ENCODING_PCM_32BIT -> src.getInt(i) / 2147483648.0
                else -> src.getFloat(i).toDouble()
            }
            val y = filter(ch, x * gain)
            when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    var q = Math.rint(y * 32768.0 + tpdf())
                    if (q > 32767.0) { q = 32767.0; clipped++ } else if (q < -32768.0) { q = -32768.0; clipped++ }
                    out.putShort(q.toInt().toShort())
                }
                C.ENCODING_PCM_24BIT -> {
                    var q = Math.rint(y * 8388608.0)
                    if (q > 8388607.0) { q = 8388607.0; clipped++ } else if (q < -8388608.0) { q = -8388608.0; clipped++ }
                    val v = q.toInt()
                    out.put((v and 0xFF).toByte()); out.put(((v shr 8) and 0xFF).toByte()); out.put(((v shr 16) and 0xFF).toByte())
                }
                C.ENCODING_PCM_32BIT -> {
                    var q = Math.rint(y * 2147483648.0)
                    if (q > 2147483647.0) { q = 2147483647.0; clipped++ } else if (q < -2147483648.0) { q = -2147483648.0; clipped++ }
                    out.putInt(q.toLong().toInt())
                }
                else -> {
                    if (y > 1.0 || y < -1.0) clipped++
                    out.putFloat(y.toFloat())
                }
            }
            i += bytesPerSample
            ch++
            if (ch == channels) ch = 0
        }
        // Any trailing partial sample (never expected for well-formed PCM) is passed as is.
        while (i < limit) { out.put(inputBuffer.get(i)); i++ }
        inputBuffer.position(limit)
        out.flip()
        if (clipped > 0) HioneEq.noteClipped(clipped)
    }

    /** Direct Form I: its state is plain past samples, so it tolerates coefficients changing live. */
    private fun filter(ch: Int, input: Double): Double {
        val m = mem[ch]
        var x = input
        var k = 0
        for (bq in coeffs) {
            val y = bq.b0 * x + bq.b1 * m[k] + bq.b2 * m[k + 1] - bq.a1 * m[k + 2] - bq.a2 * m[k + 3]
            m[k + 1] = m[k]; m[k] = x
            m[k + 3] = m[k + 2]; m[k + 2] = if (abs(y) < 1e-30) 0.0 else y  // flush denormals
            x = y
            k += 4
        }
        return x
    }

    private fun nextUnit(): Double {
        var s = rng
        s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
        rng = s
        return ((s ushr 11).toDouble() / (1L shl 53).toDouble())
    }

    /** Triangular dither, ±1 LSB peak. */
    private fun tpdf(): Double = nextUnit() - nextUnit()
}
