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
 * The rest of the graph's math, pure Kotlin like [DacFilterModel]: the listening profile's EQ
 * curve, headphone CSV import, fractional octave smoothing, and the sine sweep measurement
 * (sweep, deconvolution, suggested correction).
 */
object ResponseMath {

    /** A frequency response: ascending Hz and dB, same length. */
    class Curve(val hz: DoubleArray, val db: DoubleArray) {
        val isEmpty: Boolean get() = hz.isEmpty()

        /** dB at [f], linear in log frequency, held flat past the ends. */
        fun at(f: Double): Double {
            if (hz.isEmpty()) return 0.0
            if (f <= hz[0]) return db[0]
            if (f >= hz[hz.size - 1]) return db[db.size - 1]
            var lo = 0
            var hi = hz.size - 1
            while (hi - lo > 1) { val m = (lo + hi) / 2; if (hz[m] <= f) lo = m else hi = m }
            val t = ln(f / hz[lo]) / ln(hz[hi] / hz[lo])
            return db[lo] + (db[hi] - db[lo]) * t
        }

        /** Shifted so the average from [fromHz] to [toHz] (log spaced) is 0 dB. */
        fun normalized(fromHz: Double = 300.0, toHz: Double = 3000.0): Curve {
            var s = 0.0
            val n = 32
            for (i in 0 until n) s += at(fromHz * (toHz / fromHz).pow(i / (n - 1.0)))
            val off = s / n
            return Curve(hz, DoubleArray(db.size) { db[it] - off })
        }

        fun toCsv(): String = buildString {
            append("frequency,raw\n")
            for (i in hz.indices) append(String.format(java.util.Locale.US, "%.2f,%.3f\n", hz[i], db[i]))
        }
    }

    // ------------------------------------------------------------------------------ EQ

    enum class BandType { PEAKING, LOW_SHELF, HIGH_SHELF }

    data class Band(val type: BandType, val hz: Double, val gainDb: Double, val q: Double)

    /**
     * Magnitude of a parametric EQ at [f], preamp included. The same RBJ cookbook biquads Hione
     * Music's HioneEq runs (HioneEq.design / responseDb), so the curve here is the one you hear.
     */
    fun eqDb(preampDb: Double, bands: List<Band>, f: Double, sampleRate: Int = 48000): Double {
        var db = preampDb
        val fs = sampleRate.toDouble()
        val w = 2.0 * PI * f / fs
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        for (b in bands) {
            if (b.hz <= 0.0 || b.hz >= fs * 0.49 || abs(b.gainDb) < 1e-9) continue
            val a = 10.0.pow(b.gainDb / 40.0)
            val w0 = 2.0 * PI * b.hz / fs
            val cw = cos(w0); val sw = sin(w0)
            val q = if (b.q > 0) b.q else 0.707
            val alpha = sw / (2.0 * q)
            val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
            when (b.type) {
                BandType.PEAKING -> {
                    b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                    a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
                }
                BandType.LOW_SHELF -> {
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cw + s); b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                    b2 = a * ((a + 1) - (a - 1) * cw - s); a0 = (a + 1) + (a - 1) * cw + s
                    a1 = -2 * ((a - 1) + (a + 1) * cw); a2 = (a + 1) + (a - 1) * cw - s
                }
                BandType.HIGH_SHELF -> {
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cw + s); b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                    b2 = a * ((a + 1) + (a - 1) * cw - s); a0 = (a + 1) - (a - 1) * cw + s
                    a1 = 2 * ((a - 1) - (a + 1) * cw); a2 = (a + 1) - (a - 1) * cw - s
                }
            }
            val nr = (b0 + b1 * c1 + b2 * c2) / a0
            val ni = -(b1 * s1 + b2 * s2) / a0
            val dr = 1 + (a1 * c1 + a2 * c2) / a0
            val di = -(a1 * s1 + a2 * s2) / a0
            val num = nr * nr + ni * ni
            val den = dr * dr + di * di
            if (num > 0 && den > 0) db += 10 * log10(num / den)
        }
        return db
    }

    /** "PK"/"LSC"/"HSC" and the other spellings HioneEq accepts. */
    fun bandType(code: String?): BandType = when (code?.uppercase()) {
        "LSC", "LS", "LSQ", "LOW_SHELF" -> BandType.LOW_SHELF
        "HSC", "HS", "HSQ", "HIGH_SHELF" -> BandType.HIGH_SHELF
        else -> BandType.PEAKING
    }

    // ------------------------------------------------------------------------------ CSV

    /**
     * Reads "frequency,dB" text: AutoEq / Squiglink / REW exports and plain two-column files.
     * Comma, semicolon, tab or space separated; header and comment lines skipped; extra columns
     * ignored (the second column is used). Returns null when fewer than 8 usable points.
     */
    fun parseCsv(text: String): Curve? {
        val pts = ArrayList<Pair<Double, Double>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#' || line[0] == '*') continue
            val parts = line.split(',', ';', '\t', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val f = parts[0].toDoubleOrNull() ?: continue
            val d = parts[1].toDoubleOrNull() ?: continue
            if (f <= 0 || f > 100_000 || d.isNaN() || abs(d) > 300) continue
            pts.add(f to d)
        }
        if (pts.size < 8) return null
        pts.sortBy { it.first }
        val dedup = ArrayList<Pair<Double, Double>>()
        for (p in pts) if (dedup.isEmpty() || p.first > dedup.last().first) dedup.add(p)
        return Curve(DoubleArray(dedup.size) { dedup[it].first }, DoubleArray(dedup.size) { dedup[it].second })
    }

    // ------------------------------------------------------------------------------ smoothing

    /** Log-spaced frequencies, [perOctave] points per octave. */
    fun logGrid(fromHz: Double, toHz: Double, perOctave: Int): DoubleArray {
        val n = (ln(toHz / fromHz) / ln(2.0) * perOctave).toInt() + 1
        return DoubleArray(n) { fromHz * 2.0.pow(it.toDouble() / perOctave) }
    }

    /**
     * 1/[fraction] octave smoothing of a power response: each output point is the mean power of
     * the input points within +- half that band. [powerHz]/[power] is linear power on any grid.
     */
    fun smoothOctave(powerHz: DoubleArray, power: DoubleArray, outHz: DoubleArray, fraction: Int): DoubleArray {
        val half = 2.0.pow(0.5 / fraction)
        // Prefix sums for O(1) band means.
        val prefix = DoubleArray(power.size + 1)
        for (i in power.indices) prefix[i + 1] = prefix[i] + power[i]
        fun lowerBound(x: Double): Int {
            var lo = 0; var hi = powerHz.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (powerHz[m] < x) lo = m + 1 else hi = m }
            return lo
        }
        return DoubleArray(outHz.size) { i ->
            val a = lowerBound(outHz[i] / half)
            var b = lowerBound(outHz[i] * half)
            if (b <= a) b = min(a + 1, power.size)
            val mean = (prefix[b] - prefix[a]) / max(1, b - a)
            10 * log10(max(mean, 1e-30))
        }
    }

    // ------------------------------------------------------------------------------ sweep

    /**
     * Exponential (log) sine sweep, Farina's form, from [f1] to [f2] over [seconds], peak
     * [amplitude] (0..1), with 50 ms raised-cosine fades at both ends.
     */
    fun logSweep(sampleRate: Int, f1: Double, f2: Double, seconds: Double, amplitude: Double): FloatArray {
        val n = (seconds * sampleRate).toInt()
        val k = ln(f2 / f1)
        val t1 = seconds
        val fade = (0.05 * sampleRate).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val ph = 2 * PI * f1 * t1 / k * (exp(t / t1 * k) - 1)
            var g = amplitude
            if (i < fade) g *= 0.5 - 0.5 * cos(PI * i / fade)
            if (i > n - fade) g *= 0.5 - 0.5 * cos(PI * (n - i) / fade)
            (g * sin(ph)).toFloat()
        }
    }

    /** One sweep's result: power on the FFT grid, and where the impulse peak landed. */
    class SweepResult(val hz: DoubleArray, val power: DoubleArray, val peakSeconds: Double, val peakToNoiseDb: Double)

    /**
     * Transfer function from one recorded sweep. Regularised spectral division
     * H = Y X* / (|X|^2 + e), then the impulse is cut to [windowSec] after its peak (and 50 ms
     * before, which the band limits need at the low end). That drops the harmonic distortion products, which a log sweep puts before the
     * main impulse, and most of the room noise. The magnitude of what is left is the response.
     */
    fun deconvolve(recorded: FloatArray, sweep: FloatArray, sampleRate: Int, f1: Double, f2: Double, windowSec: Double = 0.25): SweepResult {
        var n = 1
        while (n < recorded.size + sampleRate / 2) n = n shl 1
        val xr = DoubleArray(n); val xi = DoubleArray(n)
        val yr = DoubleArray(n); val yi = DoubleArray(n)
        for (i in sweep.indices) if (i < n) xr[i] = sweep[i].toDouble()
        for (i in recorded.indices) yr[i] = recorded[i].toDouble()
        DacFilterModel.fft(xr, xi, false)
        DacFilterModel.fft(yr, yi, false)
        var maxX = 0.0
        for (k in 0..n / 2) maxX = max(maxX, xr[k] * xr[k] + xi[k] * xi[k])
        for (k in 0 until n) {
            val kk = if (k <= n / 2) k else n - k
            val f = kk.toDouble() * sampleRate / n
            val inBand = f >= f1 * 0.9 && f <= f2 * 1.05
            val x2 = xr[k] * xr[k] + xi[k] * xi[k]
            val eps = if (inBand) maxX * 1e-6 else maxX * 10.0
            val re = (yr[k] * xr[k] + yi[k] * xi[k]) / (x2 + eps)
            val im = (yi[k] * xr[k] - yr[k] * xi[k]) / (x2 + eps)
            yr[k] = re; yi[k] = im
        }
        DacFilterModel.fft(yr, yi, true)
        // yr is the impulse response. The useful peak is in the first half (causal part).
        var peak = 0
        for (i in 0 until n / 2) if (abs(yr[i]) > abs(yr[peak])) peak = i
        // Distortion products land at least ~0.8 s before the peak with an 8 s sweep, so 50 ms
        // of lead-in is safe and keeps the band edges' own ringing.
        val pre = (0.05 * sampleRate).toInt()
        val len = (windowSec * sampleRate).toInt()
        val start = max(0, peak - pre)
        var m = 1
        while (m < pre + len) m = m shl 1
        m = max(m, 1 shl 14)
        val wr = DoubleArray(m); val wi = DoubleArray(m)
        val total = min(pre + len, n - start)
        val fadeIn = max(1, (peak - start) / 2)
        val fadeOut = len / 4
        for (i in 0 until total) {
            var g = 1.0
            if (i < fadeIn) g = 0.5 - 0.5 * cos(PI * i / fadeIn)
            if (i > total - fadeOut) g = 0.5 - 0.5 * cos(PI * (total - i) / fadeOut)
            wr[i] = yr[start + i] * g
        }
        // Noise estimate: RMS of the impulse far from the peak, for a quality figure.
        var noise = 0.0
        var cnt = 0
        val ns = min(n / 2, peak + (0.6 * sampleRate).toInt())
        val ne = min(n / 2, ns + sampleRate / 2)
        for (i in ns until ne) { noise += yr[i] * yr[i]; cnt++ }
        val pnr = if (cnt > 0 && noise > 0) 10 * log10(yr[peak] * yr[peak] / (noise / cnt)) else 0.0
        DacFilterModel.fft(wr, wi, false)
        val bins = m / 2 + 1
        val hz = DoubleArray(bins) { it.toDouble() * sampleRate / m }
        val pw = DoubleArray(bins) { wr[it] * wr[it] + wi[it] * wi[it] }
        return SweepResult(hz, pw, peak.toDouble() / sampleRate, pnr)
    }

    /**
     * Average several sweeps (mean power), smooth to 1/6 octave on a 1/24 octave grid from
     * 20 Hz to 20 kHz, and normalise so 300 Hz to 3 kHz averages 0 dB.
     */
    fun combineSweeps(results: List<SweepResult>, fromHz: Double = 20.0, toHz: Double = 20000.0): Curve {
        require(results.isNotEmpty())
        val hz = results[0].hz
        val pw = DoubleArray(hz.size)
        for (r in results) for (i in pw.indices) pw[i] += r.power[i] / results.size
        val grid = logGrid(fromHz, toHz, 24)
        val db = smoothOctave(hz, pw, grid, 6)
        return Curve(grid, db).normalized()
    }

    /**
     * A gentle correction for a measured curve: cuts only, at most [maxCutDb] each, nothing below
     * 40 Hz, Q 0.7 to 2.5, up to [maxBands] peaking filters at the biggest bumps above the
     * curve's own 2-octave trend (on a 1/3 octave view of it). Never a boost, so it cannot clip and cannot push a small speaker into a dip
     * it cannot fill.
     */
    fun suggestCuts(measured: Curve, maxBands: Int = 5, maxCutDb: Double = 6.0, minBumpDb: Double = 2.0): List<Band> {
        val grid = logGrid(40.0, 16000.0, 24)
        // Work on a 1/3 octave view (mean of +-1/6 octave): a phone mic's narrow wiggles are not
        // worth an EQ band, and narrow cuts are the opposite of gentle.
        val m = DoubleArray(grid.size) { i ->
            var sum = 0.0
            for (k in -4..4) sum += measured.at(grid[i] * 2.0.pow(k / 24.0))
            sum / 9
        }
        // Trend: the curve smoothed over 2 octaves. Bumps are what sticks out of it.
        val trend = DoubleArray(grid.size) { i ->
            var s = 0.0; var c = 0
            for (j in max(0, i - 24)..min(grid.size - 1, i + 24)) { s += m[j]; c++ }
            s / c
        }
        val excess = DoubleArray(grid.size) { m[it] - trend[it] }
        val bands = ArrayList<Band>()
        val used = BooleanArray(grid.size)
        repeat(maxBands) {
            var best = -1
            for (i in grid.indices) if (!used[i] && excess[i] >= minBumpDb && (best < 0 || excess[i] > excess[best])) best = i
            if (best < 0) return bands
            val h = excess[best]
            // Width: where the bump falls to half its height (in dB) on each side.
            var lo = best; while (lo > 0 && excess[lo - 1] > h / 2) lo--
            var hi = best; while (hi < grid.size - 1 && excess[hi + 1] > h / 2) hi++
            val octaves = max(1.0 / 3, (hi - lo + 1) / 24.0)
            val q = (sqrt(2.0.pow(octaves)) / (2.0.pow(octaves) - 1)).coerceIn(0.7, 2.5)
            bands.add(Band(BandType.PEAKING, Math.round(grid[best]).toDouble(), -min(maxCutDb, h).let { Math.round(it * 10) / 10.0 }, Math.round(q * 100) / 100.0))
            for (j in max(0, lo - 6)..min(grid.size - 1, hi + 6)) used[j] = true
        }
        return bands.sortedBy { it.hz }
    }
}
