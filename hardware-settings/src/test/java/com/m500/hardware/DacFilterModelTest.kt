package com.m500.hardware

import com.m500.hardware.DacFilterModel.Filter
import com.m500.hardware.DacFilterModel.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * Checks the filter models against Cirrus DS1156F2 Table 3-7 (pages 16-18). Each assertion names
 * the row it checks. Run: ./gradlew :hardware-settings:testReleaseUnitTest
 */
class DacFilterModelTest {

    private fun maxStopband(m: DacFilterModel.Model, fromFs: Double, toFs: Double): Double {
        var worst = -400.0
        for (k in m.magDb.indices) {
            val fFs = k * m.binHz / m.rateHz
            if (fFs in fromFs..toFs) worst = maxOf(worst, m.magDb[k])
        }
        return worst
    }

    private fun passbandRange(m: DacFilterModel.Model, toFs: Double): Pair<Double, Double> {
        var lo = 0.0
        var hi = -400.0
        for (k in 1 until m.magDb.size) {
            val f = k * m.binHz
            if (f < 10.0) continue
            if (f / m.rateHz > toFs) break
            lo = minOf(lo, m.magDb[k]); hi = maxOf(hi, m.magDb[k])
        }
        return lo to hi
    }

    @Test
    fun nosDroopAt20kHzIs3point2dB() {
        // Table 3-7, NOS single speed: "Passband droop 10 Hz to 20 kHz ... 3.2 dB" (max).
        // A pure zero-order hold gives 20 log10(sinc(20000/44100)) = -3.17 dB.
        val m = DacFilterModel.model(Filter.NOS, 44100)
        val x = PI * 20000.0 / 44100
        val sinc = 20 * log10(sin(x) / x)
        assertEquals(-3.17, sinc, 0.01)
        assertEquals("NOS @ 20 kHz, 44.1 kHz", -3.2, m.dbAt(20000.0), 0.06)
        // The -3 dB corner, Table 3-7: 0.443 Fs.
        assertEquals(0.443, DacFilterModel.minus3dBHz(m) / 44100, 0.004)
        // Double and quad speed droop rows: at most 0.73 dB and 0.17 dB. The hold alone gives
        // 0.63 and 0.15 dB, so the model must sit between the sinc and the table's maximum.
        for ((rate, maxDroop) in listOf(96000 to 0.73, 192000 to 0.17)) {
            val y = PI * 20000.0 / rate
            val hold = 20 * log10(sin(y) / y)
            val v = DacFilterModel.model(Filter.NOS, rate).dbAt(20000.0)
            assertTrue("NOS @$rate 20 kHz = $v, hold $hold, max -$maxDroop", v <= hold + 0.01 && v >= -maxDroop - 0.01)
        }
        // NOS removes no images: at 1.5 Fs it is still far above any oversampling filter.
        assertTrue(DacFilterModel.model(Filter.NOS, 44100).dbAt(1.5 * 44100) > -40)
    }

    @Test
    fun fastFiltersFlatWithinRippleToPassbandEdge() {
        for (rate in listOf(44100, 48000, 88200, 96000, 176400, 192000)) {
            val speed = DacFilterModel.speedOf(rate)
            val spec = DacFilterModel.SPECS.getValue(speed to false)
            for (f in listOf(Filter.FAST_PC, Filter.FAST_LL)) {
                val m = DacFilterModel.model(f, rate)
                val (lo, hi) = passbandRange(m, spec.passbandEdge)
                assertTrue("$f @$rate ripple low $lo (Table 3-7: +-${spec.ripple} dB to ${spec.passbandEdge} Fs)", lo >= -spec.ripple!! - 0.002)
                assertTrue("$f @$rate ripple high $hi", hi <= spec.ripple + 0.002)
            }
        }
        // Single speed fast: 0.4535 Fs is 20.0 kHz at 44.1 kHz, so the audio band is flat.
        assertEquals(0.0, DacFilterModel.model(Filter.FAST_PC, 44100).dbAt(20000.0), 0.01)
    }

    @Test
    fun slowRollOffAt20kHz() {
        // Table 3-7, slow single speed: -0.01 dB to 0.417 Fs (18.4 kHz at 44.1k), -3 dB corner
        // 0.482 Fs (21.3 kHz). 20 kHz sits between them, so the datasheet bounds it to
        // -0.01..-3 dB. The model gives about -0.42 dB there (a model value, not a table number).
        val m = DacFilterModel.model(Filter.SLOW_PC, 44100)
        val at20k = m.dbAt(20000.0)
        assertTrue("slow @20k = $at20k", at20k < -0.01 && at20k > -3.0)
        assertEquals(-0.42, at20k, 0.1)
        assertEquals(0.482, DacFilterModel.minus3dBHz(m) / 44100, 0.005)
        // Same magnitude for the low latency version (the table lists one magnitude per roll-off).
        assertEquals(at20k, DacFilterModel.model(Filter.SLOW_LL, 44100).dbAt(20000.0), 0.01)
        // Passband ripple to 0.417 Fs.
        val (lo, hi) = passbandRange(m, 0.417)
        assertTrue(lo >= -0.012 && hi <= 0.012)
    }

    @Test
    fun attenuationAtHalfFsAndMinus3dBCorners() {
        for ((key, spec) in DacFilterModel.SPECS) {
            val rate = when (key.first) { Speed.SINGLE -> 44100; Speed.DOUBLE -> 96000; else -> 192000 }
            val f = if (key.second) Filter.SLOW_PC else Filter.FAST_PC
            val m = DacFilterModel.model(f, rate)
            val half = m.dbAt(rate / 2.0)
            assertTrue("$key Fs/2 = $half, Table 3-7 min ${spec.attenAtHalf}", half <= -spec.attenAtHalf!!)
            val c = DacFilterModel.minus3dBHz(m) / rate
            assertEquals("$key -3 dB corner", spec.minus3dB, c, 0.02)
        }
    }

    @Test
    fun stopbandAttenuationMeetsDatasheet() {
        for ((key, spec) in DacFilterModel.SPECS) {
            val rate = when (key.first) { Speed.SINGLE -> 48000; Speed.DOUBLE -> 88200; else -> 176400 }
            for (f in if (key.second) listOf(Filter.SLOW_PC, Filter.SLOW_LL) else listOf(Filter.FAST_PC, Filter.FAST_LL)) {
                val m = DacFilterModel.model(f, rate)
                val worst = maxStopband(m, spec.stopbandEdge!!, spec.stopbandBwEnd!!)
                // Low latency versions come from the cepstrum, which is good to about 1 dB at
                // these depths: allow that, the chip's own figures show 10 dB or more margin.
                val slack = if (f.linearPhase) 0.0 else 1.5
                assertTrue("$f @$rate stopband $worst dB, Table 3-7 min ${spec.stopbandAtten} dB", worst <= -spec.stopbandAtten!! + slack)
            }
        }
    }

    @Test
    fun lowLatencyHasNoPreRingingLinearPhaseDoes() {
        val lin = DacFilterModel.ringing(DacFilterModel.model(Filter.FAST_PC, 44100))
        val min = DacFilterModel.ringing(DacFilterModel.model(Filter.FAST_LL, 44100))
        assertTrue("linear pre ${lin.preSeconds}", lin.preSeconds > 0.0002)
        assertEquals(lin.preSeconds, lin.postSeconds, 0.00005) // symmetric
        assertTrue("minimum pre ${min.preSeconds}", min.preSeconds < 0.00002)
        assertTrue(min.postSeconds > lin.postSeconds)
        // Same magnitude everywhere in band.
        val a = DacFilterModel.model(Filter.FAST_PC, 44100)
        val b = DacFilterModel.model(Filter.FAST_LL, 44100)
        for (hz in listOf(100.0, 1000.0, 10000.0, 20000.0, 21000.0)) assertEquals(a.dbAt(hz), b.dbAt(hz), 0.01)
    }

    @Test
    fun octupleSpeedIgnoresTheChoice() {
        val m = DacFilterModel.model(Filter.SLOW_LL, 384000)
        assertTrue(m.choiceIgnored)
        // Table 3-7 octuple: droop 0.04 dB to 20 kHz, -3 dB corner 0.263 Fs.
        assertTrue(abs(m.dbAt(20000.0)) <= 0.06)
        assertEquals(0.263, DacFilterModel.minus3dBHz(m) / 384000, 0.01)
    }

    @Test
    fun eqMatchesKnownPeak() {
        val db = ResponseMath.eqDb(0.0, listOf(ResponseMath.Band(ResponseMath.BandType.PEAKING, 1000.0, -6.0, 1.0)), 1000.0)
        assertEquals(-6.0, db, 0.01)
    }

    @Test
    fun sweepRoundTripIsFlat() {
        val fs = 48000
        val sweep = ResponseMath.logSweep(fs, 20.0, 20000.0, 2.0, 0.5)
        // "Recording": 0.2 s of silence, the sweep at half level, a tail.
        val rec = FloatArray(sweep.size + fs) { 0f }
        val lead = (0.2 * fs).toInt()
        for (i in sweep.indices) rec[lead + i] = sweep[i] * 0.5f
        val r = ResponseMath.deconvolve(rec, sweep, fs, 20.0, 20000.0)
        assertEquals(0.2, r.peakSeconds, 0.002)
        val c = ResponseMath.combineSweeps(listOf(r))
        for (hz in listOf(50.0, 200.0, 1000.0, 5000.0, 15000.0)) assertEquals("flat at $hz", 0.0, c.at(hz), 0.5)
        assertTrue(ResponseMath.suggestCuts(c).isEmpty())
    }

    @Test
    fun csvParses() {
        val c = ResponseMath.parseCsv("frequency,raw\n20,1\n50,2\n100,3\n200,3\n500,2\n1000,0\n2000,1\n5000,4\n10000,-2\n20000,-8\n")
        assertTrue(c != null && c.hz.size == 10)
        assertEquals(0.0, c!!.at(1000.0), 1e-9)
    }
}
