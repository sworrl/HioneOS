package com.caf.fmradio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * The reach model against a real case: WVAQ 101.9 Morgantown, which the user hears strongly
 * (RDS decodes) and which the app called "out of reach".
 *
 * WVAQ per the FCC FM query (facility 71677): class B, 50 kW ERP, HAAT 152 m, RCAMSL 535 m,
 * 39-36-30.2 N 79-59-06.2 W. The listener spot is an estimate north-west of Morgantown that
 * reproduces the distances in the first calibration notes (WVAQ 12.4 km, WCLG 11.6 km, WVPM
 * 26 km). The ground profile is real: 82 Open-Meteo elevations along that path, fetched
 * 2026-10-10, listener first.
 */
class FmReachTest {

    @After fun reset() { FmReach.restore(FmReach.DEFAULT_OFFSET_DB, 2) }

    private val ground = listOf(
        327, 337, 338, 353, 384, 426, 438, 398, 392, 381, 372, 367, 374, 385, 391, 399, 393, 394,
        389, 377, 375, 383, 379, 381, 380, 384, 358, 358, 361, 319, 294, 305, 343, 362, 407, 423,
        398, 385, 378, 383, 365, 359, 346, 314, 341, 382, 387, 375, 354, 341, 381, 397, 400, 385,
        362, 354, 336, 345, 312, 354, 378, 392, 370, 347, 349, 352, 341, 346, 382, 379, 360, 344,
        335, 299, 280, 323, 362, 387, 392, 390, 405, 424,
    ).map { it.toDouble() }
    private val pathM = 12368.6

    private fun station(
        call: String, khz: Int, erp: Double, haat: Double, rcamsl: Double?, km: Double, score: Double = 0.0,
    ) = FmStationCatalogue.Station(
        call = call, service = "FM", khz = khz, city = null, state = null, licensee = null,
        lat = 0.0, lon = 0.0, erpKw = erp, haatM = haat, callsignSince = null,
        distanceKm = km, score = score, rcamslM = rcamsl,
    )

    private val wvaq = station("WVAQ", 101900, 50.0, 152.0, 535.0, pathM / 1000, score = 50.0)

    private fun profile(st: FmStationCatalogue.Station, g: List<Double>, total: Double): FmFresnel.Profile {
        val n = g.size
        val dist = (0 until n).map { total * it / (n - 1) }
        return FmFresnel.build(st, 39.700, -80.067, List(n) { 0.0 to 0.0 }, dist, g,
                               FmFresnel.txAntennaAmslM(st, g.last()), 0L)
    }

    @Test fun wvaqTerrainUsesLicensedAntennaHeight() {
        // Ground at the mast is 424 m: RCAMSL 535 is 111 m up, not the 152 m HAAT.
        assertEquals(535.0, FmFresnel.txAntennaAmslM(wvaq, 424.0), 0.01)
        // A translator with HAAT 0 and no RCAMSL is not put on the dirt.
        val fx = station("W283CR", 104500, 0.25, 0.0, null, 12.0)
        assertEquals(454.0, FmFresnel.txAntennaAmslM(fx, 424.0), 0.01)
        // An AM-style garbage RCAMSL is ignored.
        assertEquals(454.0, FmFresnel.txAntennaAmslM(fx.copy(rcamslM = 419745.0), 424.0), 0.01)
    }

    @Test fun wvaqIsBehindARidgeButStillComesIn() {
        val p = profile(wvaq, ground, pathM)
        println("WVAQ: worst ${"%.2f".format(p.worst!!.fresnelFraction)} of zone at " +
            "${"%.2f".format(p.worst!!.distM / 1000)} km, v=${"%.2f".format(p.diffractionV)}, " +
            "loss ${"%.1f".format(p.diffractionLossDb)} dB, verdict '${p.verdict}'")
        assertTrue("the ridge is in the line of sight", p.blocked)
        assertTrue("20-30 dB of diffraction is what this ridge costs", p.diffractionLossDb in 15.0..30.0)

        val egli = FmReach.rawDbuv(wvaq)!!
        val withTerrain = FmReach.rawDbuv(wvaq, p.diffractionLossDb)!!
        // The old model: Egli, the -38 default fitted without terrain, then the terrain again.
        val old = egli - 38.0 - p.diffractionLossDb
        val now = FmReach.predictedDbuv(wvaq, p.diffractionLossDb)!!
        println("WVAQ: Egli %.1f, with terrain %.1f, old prediction %.1f dBuV, new %.1f dBuV (offset %.0f)"
            .format(egli, withTerrain, old, now, FmReach.offsetDb))
        assertTrue("the old sum put it under the 12 dBuV threshold", old < FmReach.THRESHOLD_DBUV)

        val rated = FmReach.annotate(listOf(wvaq), null, 87500, 100, mapOf("WVAQ" to p)).single()
        assertTrue("a 50 kW station 12 km off is listenable, was ${rated.reach}", rated.reach == FmReach.Verdict.LIKELY)
    }

    @Test fun aSingleHillMatchesTheKnifeEdge() {
        // Flat 300 m ground, one 50 m spike halfway on a 10 km path, antennas 10 m up.
        val n = 101
        val g = List(n) { if (it == 50) 350.0 else 300.0 }
        val dist = DoubleArray(n) { 10_000.0 * it / (n - 1) }
        val lambda = 299_792_458.0 / 100e6
        val (v, loss) = FmFresnel.bullington(dist, g.toDoubleArray(), 310.0, 310.0, lambda)
        // h above the line (50 m less the 4/3-earth bulge... the bulge raises the spike 1.5 m).
        val h = 40.0 + 5000.0 * 5000.0 / (2 * 4.0 / 3.0 * 6_371_000.0)
        val expectV = h * Math.sqrt(2.0 * 10_000.0 / (lambda * 5000.0 * 5000.0))
        assertEquals(expectV, v, 0.01)
        assertEquals(FmFresnel.knifeEdgeDb(expectV), loss, 0.01)
    }

    @Test fun manyRidgesAreNotSummed() {
        // Five equal ridges: the equivalent edge costs about what one does, not five times it.
        val n = 101
        val g = List(n) { if (it % 20 == 10) 340.0 else 300.0 }
        val dist = DoubleArray(n) { 20_000.0 * it / (n - 1) }
        val lambda = 299_792_458.0 / 100e6
        val (_, loss) = FmFresnel.bullington(dist, g.toDoubleArray(), 310.0, 310.0, lambda)
        assertTrue("one equivalent edge, was $loss dB", loss in 10.0..30.0)
    }

    @Test fun farWeakStationsStayOutOfReach() {
        // A 6 kW, 100 m station 85 km off with a hill between is still not a promise.
        val far = station("WFAR", 99100, 6.0, 100.0, null, 85.0)
        val rated = FmReach.annotate(listOf(far), null, 87500, 100).single()
        println("85 km 6 kW: predicted %.1f dBuV -> %s".format(rated.predictedDbuv, rated.reach))
        assertTrue(rated.reach == FmReach.Verdict.UNLIKELY)
    }

    @Test fun aReadingBeatsThePrediction() {
        val p = profile(wvaq, ground, pathM)
        // Even with a pessimistic offset that predicts it dead, 45 dBuV read while tuned is HEARD.
        FmReach.restore(-60.0, 3)
        val heard = FmReach.annotate(listOf(wvaq), null, 87500, 100, mapOf("WVAQ" to p), live = mapOf("WVAQ" to 45)).single()
        assertEquals(FmReach.Verdict.HEARD, heard.reach)
        assertEquals(45, heard.measuredDbuv)
        assertEquals(FmStationCatalogue.MEASURED_TUNED, heard.measuredSource)
        assertTrue("the prediction is kept beside it", heard.predictedDbuv != null && heard.predictedDbuv!! < 12.0)

        // A sweep reading of the same works through the band grid.
        val sweep = IntArray(206).also { it[(101900 - 87500) / 100] = 38 }
        val swept = FmReach.annotate(listOf(wvaq), sweep, 87500, 100, mapOf("WVAQ" to p)).single()
        assertEquals(FmReach.Verdict.HEARD, swept.reach)
        assertEquals(FmStationCatalogue.MEASURED_SWEEP, swept.measuredSource)
    }

    @Test fun calibrationDoesNotCountTheRidgeTwice() {
        val p = profile(wvaq, ground, pathM)
        val other = station("WOTH", 90900, 5.0, 439.0, null, 26.0)
        val otherRaw = FmReach.rawDbuv(other)!!
        val wvaqRaw = FmReach.rawDbuv(wvaq, p.diffractionLossDb)!!
        // Both read 25 dB under their terrain-aware prediction: the antenna costs 25 dB.
        val hits = listOf(FmScanHit(101900, (wvaqRaw - 25).toInt(), 20), FmScanHit(90900, (otherRaw - 25).toInt(), 20))
        FmReach.calibrate(listOf(wvaq, other), hits, mapOf("WVAQ" to p))
        println("calibrated offset %.1f dB from %d".format(FmReach.offsetDb, FmReach.calibratedFrom))
        assertEquals(-25.0, FmReach.offsetDb, 1.0)
        assertEquals(2, FmReach.calibratedFrom)
        // A live reading overrides the sweep for its station.
        FmReach.calibrate(listOf(wvaq, other), hits, mapOf("WVAQ" to p), live = mapOf("WVAQ" to (wvaqRaw - 15).toInt()))
        assertEquals(-20.0, FmReach.offsetDb, 1.0)
    }

    @Test fun freeSpaceSanity() {
        // 50 kW at 12.4 km, 101.9 MHz: free space is ~89.5 dBuV on this scale.
        val fs = 10 * log10(50e6) - (32.45 + 20 * log10(101.9) + 20 * log10(12.3686)) + 107
        assertEquals(89.5, fs, 0.2)
        // With no terrain loss the model never exceeds Egli.
        assertEquals(FmReach.rawDbuv(wvaq)!!, FmReach.rawDbuv(wvaq, 0.0)!!, 0.001)
    }
}
