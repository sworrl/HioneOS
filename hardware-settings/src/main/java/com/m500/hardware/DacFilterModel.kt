package com.m500.hardware

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Models of the CS43198's five PCM filters, for the graph on the DAC screen. Pure Kotlin, no
 * Android imports, so the JVM unit test (DacFilterModelTest) checks it against the datasheet.
 *
 * SOURCE. Cirrus Logic CS43198 datasheet DS1156F2 (Dec 2022):
 *  - Table 3-7 "Combined DAC Digital, On-Chip Analog and AOUTx Filter Characteristics", pages
 *    16-18: passband edge (-0.01 dB corner), -3 dB corner, attenuation at Fs/2, passband ripple,
 *    stopband edge, stopband attenuation, group delay, NOS passband droop. The rows used are
 *    copied into [SPECS], [NOS_SPECS] and [OCTUPLE_SPEC] below.
 *  - Section 4.7 "Filtering Options", page 37: five filters (fast/slow roll-off x low
 *    latency/phase compensated, plus NOS). NOS overrides the other two bits. At 352.8 and 384 kHz
 *    (octuple speed) none of the options apply and the chip uses one fixed filter.
 *  - Section 9.1 "Digital Filter Response", figures 9-13 to 9-64, pages 124-132: magnitude,
 *    impulse and step plots. Used for the stopband depth the table does not give (it lists a
 *    minimum only) and for the shape of the NOS response above Fs/2.
 *  The table names the phase variants "linear phase" (phase compensated, PHCOMP_LOWLATB = 1) and
 *  "minimum phase" (low latency, PHCOMP_LOWLATB = 0). The magnitude rows are the same for both,
 *  so the two fast filters draw the same magnitude curve, and so do the two slow ones. They differ
 *  only in time (the impulse tab).
 *
 * HOW THE CURVES ARE MADE. Each roll-off is a Kaiser-windowed sinc running at [OVERSAMPLE] x Fs
 * (an interpolation filter), with cutoff, length and window chosen so the result meets that
 * row of Table 3-7: flat within +-0.01 dB to the passband edge, the -3 dB corner close to the
 * table's, at least the listed attenuation at Fs/2, and at least the listed stopband
 * attenuation from the stopband edge out to the table's measurement bandwidth. [DESIGNS] holds the
 * parameters. They came from a search over cutoff and length (scratch script d4.py, kept in the
 * report) and DacFilterModelTest re-checks every row. The low latency versions are the minimum
 * phase filters with the same magnitude, made from the linear phase ones with the real cepstrum
 * (homomorphic method), which keeps the magnitude and moves the energy to the start.
 * The magnitude is the FFT of the impulse ([NFFT] points).
 *
 * NOS is a zero-order hold (each sample held for 1/Fs): magnitude sinc(f/Fs), -3.92 dB at Fs/2,
 * -3.17 dB at 20 kHz when Fs = 44.1 kHz (table: droop up to 3.2 dB), nulls at Fs, 2 Fs, and the
 * images in between are not removed. Figures 9-54, 9-58 and 9-62 show extra attenuation above
 * Fs/2 on top of the sinc (the chip calls it "NOS emulation", and its analog stage filters
 * too). That part is modelled as a Butterworth-shaped low-pass per speed, fitted by eye to those
 * figures ([NOS_POST]). It changes nothing below 20 kHz at 44.1/48 kHz.
 *
 * WHAT IS NOT MODELLED. The exact tap values (Cirrus does not publish them), so the passband
 * ripple here is smaller than the chip's (about 0.007 dB in figure 9-19) and the stopband ripple
 * pattern differs. The model's own delay differs from the table's group delay, because the table
 * includes the modulator and analog stage, so the UI quotes the table's group delay. The
 * high-pass filter (on by default, -3 dB at about 0.9 Hz at 44.1 kHz, Table 3-9) is below
 * the graph and left out.
 */
object DacFilterModel {
    /** The model runs at this multiple of the input rate. 16 x keeps the NOS sinc within 0.01 dB. */
    const val OVERSAMPLE = 16
    /** FFT size for the magnitude. Bin spacing is 16 x Fs / 32768 = Fs / 2048 (21.5 Hz at 44.1k). */
    const val NFFT = 32768

    /** Rates the selector offers. */
    val RATES = intArrayOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)

    enum class Filter(val id: String, val shortLabel: String) {
        FAST_PC("fast_rolloff_phase_compensated", "Fast, phase comp."),
        FAST_LL("fast_rolloff_low_latency", "Fast, low latency"),
        SLOW_PC("slow_rolloff_phase_compensated", "Slow, phase comp."),
        SLOW_LL("slow_rolloff_low_latency", "Slow, low latency"),
        NOS("nos", "NOS");

        val slow: Boolean get() = this == SLOW_PC || this == SLOW_LL
        val linearPhase: Boolean get() = this == FAST_PC || this == SLOW_PC

        companion object {
            fun of(id: String?): Filter? = entries.firstOrNull { it.id == id }
        }
    }

    enum class Speed(val label: String) {
        SINGLE("single speed (32 to 48 kHz)"),
        DOUBLE("double speed (88.2 and 96 kHz)"),
        QUAD("quad speed (176.4 and 192 kHz)"),
        OCTUPLE("octuple speed (352.8 and 384 kHz)"),
    }

    fun speedOf(rateHz: Int): Speed = when {
        rateHz <= 50_000 -> Speed.SINGLE
        rateHz <= 100_000 -> Speed.DOUBLE
        rateHz <= 200_000 -> Speed.QUAD
        else -> Speed.OCTUPLE
    }

    /**
     * One row of DS1156F2 Table 3-7. Frequencies are in units of Fs, dB as positive attenuation.
     * Null means the table does not list it for that row.
     */
    data class Spec(
        /** Passband to the -0.01 dB corner. For NOS the table lists the same corner. */
        val passbandEdge: Double,
        /** Passband to the -3 dB corner (the table's "Maximum" column). */
        val minus3dB: Double,
        /** Attenuation at Fs/2, minimum. */
        val attenAtHalf: Double? = null,
        /** Passband ripple, +- dB, 10 Hz to the passband edge. */
        val ripple: Double? = null,
        val stopbandEdge: Double? = null,
        /** Stopband attenuation, minimum, dB. */
        val stopbandAtten: Double? = null,
        /** Footnote 6: where the stopband measurement ends, in Fs. */
        val stopbandBwEnd: Double? = null,
        /** Group delay in samples of Fs: linear phase (phase compensated). */
        val delayLinear: Double? = null,
        /** Group delay in samples of Fs: minimum phase (low latency). */
        val delayMinimum: Double? = null,
        /** NOS and octuple: one group delay. */
        val delay: Double? = null,
        /** NOS and octuple: passband droop 10 Hz to 20 kHz, dB, maximum. */
        val droop20k: Double? = null,
        /** Where in the datasheet. */
        val source: String,
    )

    /** Table 3-7, roll-off rows, keyed by speed and slow = true/false. */
    val SPECS: Map<Pair<Speed, Boolean>, Spec> = mapOf(
        (Speed.SINGLE to false) to Spec(0.4535, 0.482, 8.44, 0.01, 0.547, 110.0, 3.0, 39.5, 6.3,
            source = "DS1156F2 Table 3-7, fast roll-off, single speed, p. 16; figures 9-19 to 9-24, p. 125"),
        (Speed.SINGLE to true) to Spec(0.417, 0.482, 6.45, 0.01, 0.583, 64.0, 3.0, 34.5, 5.6,
            source = "DS1156F2 Table 3-7, slow roll-off, single speed, p. 17; figures 9-13 to 9-18, p. 124"),
        (Speed.DOUBLE to false) to Spec(0.227, 0.48, 7.77, 0.01, 0.583, 80.0, 3.0, 22.3, 7.5,
            source = "DS1156F2 Table 3-7, fast roll-off, double speed, p. 17; figures 9-31 to 9-36, p. 127"),
        (Speed.DOUBLE to true) to Spec(0.208, 0.458, 7.0, 0.01, 0.792, 70.0, 3.0, 22.3, 6.7,
            source = "DS1156F2 Table 3-7, slow roll-off, double speed, p. 17; figures 9-25 to 9-30, p. 126"),
        (Speed.QUAD to false) to Spec(0.114, 0.46, 9.44, 0.01, 0.583, 80.0, 1.34, 20.7, 11.3,
            source = "DS1156F2 Table 3-7, fast roll-off, quad speed, p. 17"),
        (Speed.QUAD to true) to Spec(0.104, 0.43, 7.0, 0.01, 0.792, 75.0, 1.34, 20.7, 10.6,
            source = "DS1156F2 Table 3-7, slow roll-off, quad speed, p. 17"),
    )

    /** Table 3-7, NOS rows (NOS = 1). Figures 9-53 to 9-64, pages 130-132. */
    val NOS_SPECS: Map<Speed, Spec> = mapOf(
        Speed.SINGLE to Spec(0.026, 0.443, delay = 2.7, droop20k = 3.2,
            source = "DS1156F2 Table 3-7, NOS, single speed, p. 17; figures 9-53 to 9-56, pp. 130-131"),
        Speed.DOUBLE to Spec(0.0246, 0.446, delay = 4.5, droop20k = 0.73,
            source = "DS1156F2 Table 3-7, NOS, double speed, p. 17; figures 9-57 to 9-60, p. 131"),
        Speed.QUAD to Spec(0.026, 0.405, delay = 8.4, droop20k = 0.17,
            source = "DS1156F2 Table 3-7, NOS, quad speed, p. 17; figures 9-61 to 9-64, p. 132"),
    )

    /** Table 3-7, octuple speed (the only filter at 352.8/384 kHz). Figures 9-49 to 9-52, p. 130. */
    val OCTUPLE_SPEC = Spec(0.0299, 0.263, delay = 17.0, droop20k = 0.04,
        source = "DS1156F2 Table 3-7, octuple speed, p. 18; section 4.7, p. 37; figures 9-49 to 9-52, p. 130")

    /**
     * Kaiser low-pass designs that meet the rows above. [cutoff] is the -6 dB point in Fs,
     * [taps8] the length at 8 x Fs (doubled for [OVERSAMPLE] = 16), [attenDb] sets the Kaiser beta.
     * The stopband depths follow the datasheet figures where they are deeper than the table's
     * minimum: about 120 dB for fast single speed (fig. 9-20), 100 dB for slow single (fig. 9-14),
     * 95 dB for fast double (fig. 9-32), 80 dB for slow double (fig. 9-26). Quad uses the table.
     */
    data class Design(val cutoff: Double, val taps8: Int, val attenDb: Double)

    val DESIGNS: Map<Pair<Speed, Boolean>, Design> = mapOf(
        (Speed.SINGLE to false) to Design(0.495, 605, 120.0),
        (Speed.SINGLE to true) to Design(0.4975, 301, 100.0),
        (Speed.DOUBLE to false) to Design(0.4925, 287, 95.0),
        (Speed.DOUBLE to true) to Design(0.4875, 85, 80.0),
        (Speed.QUAD to false) to Design(0.48, 215, 80.0),
        (Speed.QUAD to true) to Design(0.485, 69, 77.0),
    )

    /** Octuple speed: a short filter fitted to Table 3-7's corners and figure 9-49. Kaiser beta 3. */
    val OCTUPLE_DESIGN = Design(0.33, 41, 35.9)

    /**
     * NOS: extra low-pass on top of the zero-order hold, as (order, corner in Fs), Butterworth
     * magnitude. Fitted to figures 9-54 (single: about -20 dB at 1.25 Fs, -51 dB at 2.2 Fs),
     * 9-58 (double: about -26 dB at 1.2 Fs, -82 dB at 2.5 Fs) and 9-61/9-62 (quad: the -3 dB
     * corner at 0.405 Fs and about -25 dB at 0.8 Fs). Rough above Fs/2; flat to 0.01 dB in band.
     */
    val NOS_POST: Map<Speed, Pair<Double, Double>> = mapOf(
        Speed.SINGLE to (5.4 to 1.164),
        Speed.DOUBLE to (8.55 to 1.055),
        Speed.QUAD to (3.6 to 0.546),
    )

    /** What the graph needs for one filter at one rate. */
    class Model(
        val filter: Filter,
        val rateHz: Int,
        val speed: Speed,
        /** Impulse at OVERSAMPLE x rate, DC gain 1. */
        val impulse: DoubleArray,
        /** Index of the largest sample in [impulse]. */
        val peakIndex: Int,
        /** |H| in dB, NFFT/2 + 1 bins, bin k at k * OVERSAMPLE * rate / NFFT Hz. */
        val magDb: DoubleArray,
        /** The datasheet row this follows. */
        val spec: Spec,
        /** Datasheet group delay for this filter, in samples of Fs. */
        val datasheetDelay: Double?,
        /** True when the chip ignores the filter choice at this rate. */
        val choiceIgnored: Boolean,
    ) {
        val binHz: Double get() = OVERSAMPLE.toDouble() * rateHz / NFFT

        /** Magnitude in dB at [hz], linear between bins. */
        fun dbAt(hz: Double): Double {
            val x = hz / binHz
            val i = x.toInt().coerceIn(0, magDb.size - 2)
            val t = (x - i).coerceIn(0.0, 1.0)
            return magDb[i] * (1 - t) + magDb[i + 1] * t
        }

        /** Seconds per impulse sample. */
        val dt: Double get() = 1.0 / (OVERSAMPLE.toDouble() * rateHz)
    }

    private val cache = HashMap<String, Model>()

    /** The model for [filter] at [rateHz], computed once and cached. Thread-safe. */
    fun model(filter: Filter, rateHz: Int): Model {
        val key = "${filter.id}@$rateHz"
        synchronized(cache) { cache[key]?.let { return it } }
        val m = build(filter, rateHz)
        synchronized(cache) { cache[key] = m }
        return m
    }

    private fun build(filter: Filter, rateHz: Int): Model {
        val speed = speedOf(rateHz)
        if (speed == Speed.OCTUPLE) {
            val h = kaiserLowpass(OCTUPLE_DESIGN.cutoff, taps16(OCTUPLE_DESIGN.taps8), kaiserBeta(OCTUPLE_DESIGN.attenDb))
            return finish(filter, rateHz, speed, h, OCTUPLE_SPEC, OCTUPLE_SPEC.delay, choiceIgnored = true)
        }
        if (filter == Filter.NOS) {
            val spec = NOS_SPECS.getValue(speed)
            return finish(filter, rateHz, speed, nosImpulse(speed), spec, spec.delay, choiceIgnored = false)
        }
        val key = speed to filter.slow
        val d = DESIGNS.getValue(key)
        val spec = SPECS.getValue(key)
        val linear = kaiserLowpass(d.cutoff, taps16(d.taps8), kaiserBeta(d.attenDb))
        val h = if (filter.linearPhase) linear else minimumPhase(linear, linear.size * 2)
        return finish(filter, rateHz, speed, h, spec, if (filter.linearPhase) spec.delayLinear else spec.delayMinimum, false)
    }

    private fun finish(filter: Filter, rate: Int, speed: Speed, h: DoubleArray, spec: Spec, delay: Double?, choiceIgnored: Boolean): Model {
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        for (i in h.indices) re[i] = h[i]
        fft(re, im, false)
        val mag = DoubleArray(NFFT / 2 + 1) { k -> 10 * log10(max(re[k] * re[k] + im[k] * im[k], 1e-30)) }
        var peak = 0
        for (i in h.indices) if (abs(h[i]) > abs(h[peak])) peak = i
        return Model(filter, rate, speed, h, peak, mag, spec, delay, choiceIgnored)
    }

    private fun taps16(taps8: Int) = (taps8 - 1) * (OVERSAMPLE / 8) + 1

    fun kaiserBeta(attenDb: Double): Double = when {
        attenDb > 50 -> 0.1102 * (attenDb - 8.7)
        attenDb >= 21 -> 0.5842 * (attenDb - 21).pow(0.4) + 0.07886 * (attenDb - 21)
        else -> 0.0
    }

    /** Linear phase low-pass at OVERSAMPLE x Fs, -6 dB at [cutoff] x Fs, DC gain 1. */
    fun kaiserLowpass(cutoff: Double, taps: Int, beta: Double): DoubleArray {
        val wc = 2.0 * cutoff / OVERSAMPLE
        val mid = (taps - 1) / 2.0
        val i0b = besselI0(beta)
        val h = DoubleArray(taps) { n ->
            val x = n - mid
            val sinc = if (abs(x) < 1e-12) wc else sin(PI * wc * x) / (PI * x)
            val r = x / mid
            sinc * besselI0(beta * sqrt(max(0.0, 1 - r * r))) / i0b
        }
        val s = h.sum()
        for (i in h.indices) h[i] /= s
        return h
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        var k = 1
        while (k < 200) {
            term *= q / (k.toDouble() * k)
            sum += term
            if (term < sum * 1e-17) break
            k++
        }
        return sum
    }

    /**
     * Minimum phase filter with the magnitude of [h], by the real cepstrum (fold the cepstrum
     * onto positive quefrency, exponentiate). Returns [outLen] samples.
     */
    fun minimumPhase(h: DoubleArray, outLen: Int): DoubleArray {
        val n = NFFT
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in h.indices) re[i] = h[i]
        fft(re, im, false)
        for (k in 0 until n) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k])
            re[k] = ln(max(m, 1e-12))
            im[k] = 0.0
        }
        fft(re, im, true)
        // re is now the real cepstrum. Fold: keep c0 and c(n/2), double 1..n/2-1, drop the rest.
        for (i in 1 until n / 2) { re[i] *= 2; im[i] = 0.0 }
        for (i in n / 2 + 1 until n) { re[i] = 0.0; im[i] = 0.0 }
        im[0] = 0.0; im[n / 2] = 0.0
        fft(re, im, false)
        for (k in 0 until n) {
            val a = exp(re[k])
            val ph = im[k]
            re[k] = a * cos(ph)
            im[k] = a * sin(ph)
        }
        fft(re, im, true)
        return DoubleArray(min(outLen, n)) { re[it] }
    }

    /**
     * NOS: hold each sample for one input period (a box of OVERSAMPLE samples), then the fitted
     * post low-pass from [NOS_POST] as a minimum phase filter.
     */
    private fun nosImpulse(speed: Speed): DoubleArray {
        val (order, corner) = NOS_POST.getValue(speed)
        val n = NFFT
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        // Magnitude of the post filter on the full FFT circle, then its minimum phase version.
        val mag = DoubleArray(n) { k ->
            val kk = if (k <= n / 2) k else n - k
            val fFs = kk.toDouble() * OVERSAMPLE / n
            1.0 / sqrt(1.0 + (fFs / corner).pow(2 * order))
        }
        for (k in 0 until n) { re[k] = ln(max(mag[k], 1e-12)); im[k] = 0.0 }
        fft(re, im, true)
        for (i in 1 until n / 2) { re[i] *= 2; im[i] = 0.0 }
        for (i in n / 2 + 1 until n) { re[i] = 0.0; im[i] = 0.0 }
        im[0] = 0.0; im[n / 2] = 0.0
        fft(re, im, false)
        // exp, then multiply by the spectrum of the box (OVERSAMPLE ones, scaled to DC gain 1).
        val bRe = DoubleArray(n)
        val bIm = DoubleArray(n)
        for (i in 0 until OVERSAMPLE) bRe[i] = 1.0 / OVERSAMPLE
        fft(bRe, bIm, false)
        for (k in 0 until n) {
            val a = exp(re[k])
            val pr = a * cos(im[k])
            val pi = a * sin(im[k])
            re[k] = pr * bRe[k] - pi * bIm[k]
            im[k] = pr * bIm[k] + pi * bRe[k]
        }
        fft(re, im, true)
        // The response is short: a few input periods.
        return DoubleArray(OVERSAMPLE * 48) { re[it] }
    }

    // ------------------------------------------------------------------------------ queries

    /** dB at [count] log-spaced points from [fromHz] to [toHz]. */
    fun logCurve(m: Model, fromHz: Double, toHz: Double, count: Int): FloatArray {
        val a = ln(fromHz)
        val b = ln(toHz)
        return FloatArray(count) { i -> m.dbAt(exp(a + (b - a) * i / (count - 1))).toFloat() }
    }

    /** The -3 dB point in Hz, searched from 0.3 Fs (NOS and octuple: from 0). */
    fun minus3dBHz(m: Model): Double {
        var k = 1
        while (k < m.magDb.size && m.magDb[k] > -3.0) k++
        if (k >= m.magDb.size) return Double.NaN
        val a = m.magDb[k - 1]
        val b = m.magDb[k]
        return ((k - 1) + (a + 3.0) / (a - b)) * m.binHz
    }

    data class Ringing(
        /** Seconds before the peak where the impulse first reaches [threshold] of the peak. */
        val preSeconds: Double,
        /** Seconds after the peak until it last reaches [threshold]. */
        val postSeconds: Double,
        val threshold: Double,
    )

    /** How long the impulse rings before and after its peak, above [threshold] x peak. */
    fun ringing(m: Model, threshold: Double = 0.01): Ringing {
        val h = m.impulse
        val p = h[m.peakIndex]
        val lim = abs(p) * threshold
        var first = m.peakIndex
        for (i in 0 until m.peakIndex) if (abs(h[i]) >= lim) { first = i; break }
        var last = m.peakIndex
        for (i in h.indices.reversed()) if (abs(h[i]) >= lim) { last = i; break }
        // The main lobe itself is not ringing: start counting at the first zero crossing.
        var lobeStart = m.peakIndex
        while (lobeStart > 0 && h[lobeStart - 1] * p > 0) lobeStart--
        var lobeEnd = m.peakIndex
        while (lobeEnd < h.size - 1 && h[lobeEnd + 1] * p > 0) lobeEnd++
        val pre = max(0, lobeStart - first) * m.dt
        val post = max(0, last - lobeEnd) * m.dt
        return Ringing(pre, post, threshold)
    }

    // ------------------------------------------------------------------------------ FFT

    /** In-place radix-2 complex FFT. [inverse] scales by 1/n. Size must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size && n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
