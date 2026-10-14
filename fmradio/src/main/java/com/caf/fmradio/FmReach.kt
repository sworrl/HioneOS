package com.caf.fmradio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Should this station come in here, on this radio?
 *
 * The catalogue's score ranks stations against each other but cannot answer that, and the
 * nearby list was showing big-city stations from a valley where they had never once been heard. This turns transmitter power, height and distance into a predicted
 * level at the tuner's input in dBµV (the unit the Si4705's RSSI reports), then corrects that
 * prediction against what this device has actually measured.
 *
 * THE PATH MODEL. Without a terrain profile, Egli's empirical VHF formula (a plain-terrain fit
 * that already includes more loss than free space), plus an extra loss for every kilometre past
 * the radio horizon. With a profile (FmFresnel), the level is the lower of Egli and free space
 * less the terrain's diffraction loss. Diffraction loss in ITU-R P.526 is measured against free
 * space, so adding it on top of Egli, which already carries typical terrain loss, counted the
 * same hill twice. None of this is precise. It is meant to be right about "next town over"
 * versus "two counties and a ridge away", which is the question being asked.
 *
 * THE CALIBRATION. The antenna is a headphone cable and the listener is usually indoors, which
 * costs tens of dB that no formula knows about. So stations this device has measured (a band
 * sweep, or a steady reading while tuned) are compared with the prediction for them, terrain
 * included where known, and the median difference becomes the offset applied to everything.
 * It describes this device and its antenna, not a place, so it is kept across restarts. Until
 * anything has been measured, a default stands in for it.
 *
 * MEASUREMENT BEATS PREDICTION. A station read at a listenable level here is HEARD, whatever
 * the model or the terrain says; a full-power station whose frequency read at the noise floor
 * is NOT_HEARD.
 *
 * WHY A STRONG LOCAL STATION READ "OUT OF REACH" (2026-10-10). 50 kW, 152 m HAAT, close by, a
 * ridge 0.8 km from the listener about 110 m above the line of sight (~23 dB of diffraction).
 * The old sum was Egli 69.6 dBuV, less the -38 dB default offset, less 23 dB of terrain:
 * 8 dBuV, under the 12 dBuV threshold. But the -38 had been fitted without terrain, from a
 * reading of another station that was itself behind that ridge and taken while 100.1 could not be tuned
 * (the 200 kHz grid bug), so the ridge was in the offset and then subtracted again. Now:
 * min(Egli 69.6, free space 89.5 - 23.4) = 66.1, less the -25 dB default, is 41 dBuV: LIKELY,
 * and HEARD as soon as the tuner reads it.
 */
object FmReach {

    enum class Verdict(val label: String) {
        /** The tuner read it at a listenable level here (while tuned, or in a band sweep). */
        HEARD("heard here"),
        /** Predicted comfortably above what this tuner needs. */
        LIKELY("should come in"),
        /** Near the threshold: antenna, position and weather decide. */
        MARGINAL("marginal"),
        /** Well below threshold, or beyond the horizon. */
        UNLIKELY("out of reach"),
        /** The tuner read only noise on its frequency here. */
        NOT_HEARD("not heard here"),
        /** No ERP in the catalogue, or no position. */
        UNKNOWN("unknown"),
    }

    /** Typical RSSI where the Si4705 gives listenable mono; also roughly where RDS begins. */
    const val LISTENABLE_DBUV = 20.0
    /** Below this, nothing useful. */
    const val THRESHOLD_DBUV = 12.0
    /** A sweep reading at or under this on a frequency is the noise floor here. */
    const val NOISE_FLOOR_DBUV = 10

    private const val RX_HEIGHT_M = 1.5

    /**
     * The frequency term uses the station's own frequency. It used to be a fixed 98 MHz, the
     * middle of the US band, which is within 1 dB anywhere in 88-108 but 2.2 dB pessimistic for
     * a Japanese station at 76 MHz (Egli's loss rises 20 log f). Out-of-band values (AM rows)
     * fall back to 98.
     */
    fun freqMhz(st: FmStationCatalogue.Station): Double =
        (st.khz / 1000.0).takeIf { it in 64.0..108.0 } ?: 98.0

    /**
     * Headphone-cable antenna, indoors, before anything has been measured. From the first
     * readings on this device (2026-10-09): one station read 29 dBµV against 53.6 predicted with
     * its terrain (-24.6). A second reading from the same day (8 dBµV) is left out: it was taken
     * while the dial could not land on that station's frequency.
     * Replaced as soon as two stations have been measured.
     */
    const val DEFAULT_OFFSET_DB = -25.0

    @Volatile var offsetDb: Double = DEFAULT_OFFSET_DB
        private set
    /** How many heard stations the current offset was fitted from; 0 = the default. */
    @Volatile var calibratedFrom: Int = 0
        private set

    /** Distance to the radio horizon for two antenna heights, 4/3 earth. */
    fun horizonKm(txM: Double, rxM: Double = RX_HEIGHT_M): Double = 4.12 * (sqrt(txM) + sqrt(rxM))

    /**
     * Predicted level at the tuner input, dBµV, before calibration. Null without ERP.
     * [terrainLossDb] is the Fresnel knife-edge loss when the profile is known.
     */
    fun rawDbuv(st: FmStationCatalogue.Station, terrainLossDb: Double? = null): Double? {
        val erpKw = st.erpKw ?: return null
        if (erpKw <= 0) return null
        // Translators carry HAAT 0 in the FCC data, meaning "not given", not "on the ground".
        val hTx = (st.haatM?.takeIf { it > 0.0 } ?: 30.0).coerceAtLeast(10.0)
        val d = st.distanceKm.coerceAtLeast(0.5)
        val erpDbm = 10 * log10(erpKw * 1e6)
        // Egli: L = 20log f + 40log d - 20log hb + 76.3 - 10log hm   (f MHz, d km, h m; hm < 10 m)
        val fMhz = freqMhz(st)
        var loss = 20 * log10(fMhz) + 40 * log10(d) - 20 * log10(hTx) + 76.3 - 10 * log10(RX_HEIGHT_M)
        val horizon = horizonKm(hTx)
        if (d > horizon) loss += 0.35 * (d - horizon)      // diffraction beyond the horizon, ~0.3-0.4 dB/km at VHF
        val egli = erpDbm - loss + 107.0                    // dBm → dBµV across 50 Ω
        if (terrainLossDb == null) return egli
        // With the real terrain: free space less its diffraction loss, never above Egli.
        val freeSpace = erpDbm - (32.45 + 20 * log10(fMhz) + 20 * log10(d)) + 107.0
        return min(egli, freeSpace - terrainLossDb)
    }

    fun predictedDbuv(st: FmStationCatalogue.Station, terrainLossDb: Double? = null): Double? =
        rawDbuv(st, terrainLossDb)?.let { it + offsetDb }

    /** Put back an offset fitted in an earlier run (the engine keeps it in its preferences). */
    fun restore(offset: Double, from: Int) {
        if (from < 2 || offset.isNaN()) return
        offsetDb = offset.coerceIn(-70.0, 0.0)
        calibratedFrom = from
    }

    /**
     * Fit [offsetDb] from measurements: for each frequency a sweep heard, the strongest
     * catalogued station there is assumed to be what was heard. [live] (call sign to RSSI read
     * while tuned) takes precedence over the sweep for the same station. The prediction each is
     * compared with includes its terrain when [terrain] has it, so the offset is the antenna
     * and the building, not the hills. Returns true when the offset changed.
     */
    fun calibrate(
        stations: List<FmStationCatalogue.Station>,
        hits: List<FmScanHit>,
        terrain: Map<String, FmFresnel.Profile> = emptyMap(),
        live: Map<String, Int> = emptyMap(),
    ): Boolean {
        val readings = LinkedHashMap<String, Pair<FmStationCatalogue.Station, Int>>()
        for (h in hits) {
            val st = stations.filter { kotlin.math.abs(it.khz - h.freqKHz) <= 50 }.maxByOrNull { it.score } ?: continue
            readings[st.call] = st to (h.rssi ?: continue)
        }
        for ((call, rssi) in live) {
            val st = stations.firstOrNull { it.call == call } ?: continue
            if (rssi >= LISTENABLE_DBUV) readings[call] = st to rssi
        }
        val diffs = ArrayList<Double>()
        for ((st, rssi) in readings.values) {
            val raw = rawDbuv(st, terrain[st.call]?.diffractionLossDb) ?: continue
            diffs += rssi - raw
        }
        if (diffs.size < 2) return false
        diffs.sort()
        val med = if (diffs.size % 2 == 1) diffs[diffs.size / 2]
                  else (diffs[diffs.size / 2 - 1] + diffs[diffs.size / 2]) / 2.0
        val before = offsetDb
        offsetDb = med.coerceIn(-70.0, 0.0)
        calibratedFrom = diffs.size
        return kotlin.math.abs(before - offsetDb) > 0.05
    }

    /**
     * Annotate a station list. [sweepRssi] is RSSI per channel of the latest sweep made at
     * this position (null when there is none), indexed from [sweepLowKHz] in [sweepStepKHz].
     * [terrain] maps a call sign to its terrain profile from here, where one is known. [live]
     * is RSSI read while tuned to a station here (call sign to dBµV), which beats the sweep.
     */
    fun annotate(
        stations: List<FmStationCatalogue.Station>,
        sweepRssi: IntArray?,
        sweepLowKHz: Int,
        sweepStepKHz: Int,
        terrain: Map<String, FmFresnel.Profile> = emptyMap(),
        live: Map<String, Int> = emptyMap(),
    ): List<FmStationCatalogue.Station> {
        // One station per frequency owns any measurement there: the one most likely to be it.
        val owner = stations.groupBy { it.khz }.mapValues { (_, v) -> v.maxByOrNull { it.score }?.call }
        return stations.map { st ->
            val prof = terrain[st.call]
            val predicted = predictedDbuv(st, prof?.diffractionLossDb)
            val tuned = live[st.call]
            val swept = sweepRssi?.let { r ->
                val i = (st.khz - sweepLowKHz) / max(1, sweepStepKHz)
                if (i in r.indices && (st.khz - sweepLowKHz) % max(1, sweepStepKHz) == 0) r[i] else null
            }?.takeIf { owner[st.khz] == st.call }
            val measured = tuned ?: swept
            val verdict = when {
                measured != null && measured >= LISTENABLE_DBUV -> Verdict.HEARD
                measured != null && measured <= NOISE_FLOOR_DBUV && st.service == "FM" -> Verdict.NOT_HEARD
                // Read while tuned, above the floor but under listenable: that is marginal,
                // whatever the model hoped. (A sweep reading here can be a neighbour's splatter.)
                tuned != null -> Verdict.MARGINAL
                predicted == null -> Verdict.UNKNOWN
                predicted >= LISTENABLE_DBUV + 6 -> Verdict.LIKELY
                predicted >= THRESHOLD_DBUV -> Verdict.MARGINAL
                else -> Verdict.UNLIKELY
            }
            st.copy(
                reach = verdict, predictedDbuv = predicted, measuredDbuv = measured,
                measuredSource = when {
                    tuned != null -> FmStationCatalogue.MEASURED_TUNED
                    swept != null -> FmStationCatalogue.MEASURED_SWEEP
                    else -> null
                },
                fresnelFraction = prof?.worst?.fresnelFraction,
                terrainVerdict = prof?.verdict,
                terrainLossDb = prof?.diffractionLossDb,
            )
        }
    }
}
