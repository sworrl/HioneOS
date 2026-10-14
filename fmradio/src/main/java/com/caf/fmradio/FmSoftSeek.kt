package com.caf.fmradio

import kotlin.math.abs

/**
 * Seek and scan, done by stepping the Si4705 ourselves.
 *
 * The hardware seek this app used to call (`FmReceiver.searchStations`) goes over HCI to the
 * Qualcomm WCN FM core, and that core has no antenna on the M500. It searches a band of noise,
 * finds nothing, and reports SEEK_CMPL on the frequency it started from. Measured on 0.3.1
 * tuned to 101.9 (RSSI 30, SNR 21): seek up ran for 3.4 s and came back with
 * `SEEK_CMPL: Freq: 101900`. That is the "it scans and stays on the station" bug.
 *
 * The Si4705 driver does have a V4L2 hardware seek, but HiBy's JNI does not expose it, so the
 * seek here tunes channel by channel through [FmV4L2.probe] and reads the chip's own signal
 * report. Everything in this object is pure logic so it can be tested off the device.
 */
object FmSoftSeek {

    /** One signal reading at one channel. [offsetKHz] is signed, null when unreadable. */
    data class Probe(val rssi: Int, val snr: Int, val offsetKHz: Int?)

    /** What a channel has to read to count as a station. */
    data class Thresholds(val minRssi: Int, val minSnr: Int, val maxOffsetKHz: Int)

    /**
     * A channel next to one at least this much stronger is that station's skirt, not a station
     * of its own. Only applied to channels one grid step apart.
     */
    const val SKIRT_DB = 6

    /**
     * Thresholds for the seek sensitivity setting, 0 (Weakest, finds the most) to 3 (Strongest).
     *
     * Set from a full US sweep on 0.3.1 with the 3.5 mm cable as the antenna (2026-10-10).
     * Noise channels read RSSI 0-6 with SNR 0-2, and now and then SNR 3-7 at RSSI 6 or below,
     * or RSSI up to 11 with a carrier offset of 40-70 kHz. The weakest real station in that
     * sweep, 101.9, read RSSI 8, SNR 10, offset 7 kHz; with a better antenna the same station
     * read 30/21, and earlier sweeps put weak listenable stations at RSSI 11-29, SNR 8-10.
     * The default (1) takes the 8/10 station and none of the noise. The offset limit starts at
     * the chip's own AFC rail default (FM_MAX_TUNE_ERROR, 30 kHz): a real carrier reads within
     * a few kHz, noise and a neighbour's skirt do not.
     */
    fun thresholds(sensitivity: Int): Thresholds = when (sensitivity.coerceIn(0, 3)) {
        0 -> Thresholds(minRssi = 5, minSnr = 4, maxOffsetKHz = 30)
        1 -> Thresholds(minRssi = 8, minSnr = 6, maxOffsetKHz = 25)
        2 -> Thresholds(minRssi = 15, minSnr = 8, maxOffsetKHz = 20)
        else -> Thresholds(minRssi = 25, minSnr = 12, maxOffsetKHz = 15)
    }

    fun isStation(p: Probe, t: Thresholds): Boolean =
        p.rssi >= t.minRssi && p.snr >= t.minSnr &&
            (p.offsetKHz == null || abs(p.offsetKHz) <= t.maxOffsetKHz)

    /** FM_RSQ_STATUS FREQOFF arrives as an unsigned byte: 253 is -3 kHz. */
    fun signedOffset(raw: Int?): Int? = raw?.let { val b = it and 0xFF; if (b > 127) b - 256 else b }

    /**
     * The driver reports the tuned frequency in 1/16 kHz units truncated to 16 bits: 101.9 MHz
     * (1 630 400) reads back as 57 536. Recover it as the candidate nearest [nearKHz]. The
     * candidates are 4096 kHz apart, so this is exact for anything within 2 MHz of the guess.
     */
    fun unwrapKHz(raw16: Int, nearKHz: Int): Int {
        val near = nearKHz.toLong() * 16
        val base = near - Math.floorMod(near, 65536L)
        val r = (raw16 and 0xFFFF).toLong()
        val best = listOf(base - 65536 + r, base + r, base + 65536 + r).minByOrNull { abs(it - near) }!!
        return (best / 16).toInt()
    }

    /** Whether a 16-bit frequency readback says the chip is on [khz]. */
    fun landedOn(raw16: Int?, khz: Int): Boolean = raw16 != null && (raw16 and 0xFFFF) == ((khz * 16) and 0xFFFF)

    /**
     * Every channel of the grid inside [lowKHz]..[highKHz], ascending. [baseKHz] anchors the
     * grid (87.9 for North America's odd tenths) and may sit above [lowKHz].
     */
    fun grid(lowKHz: Int, highKHz: Int, stepKHz: Int, baseKHz: Int): IntArray {
        require(stepKHz > 0) { "step must be positive" }
        val first = baseKHz + Math.floorDiv(lowKHz - baseKHz + stepKHz - 1, stepKHz) * stepKHz
        if (first > highKHz) return IntArray(0)
        return IntArray((highKHz - first) / stepKHz + 1) { first + it * stepKHz }
    }

    /**
     * The order a seek visits channels in: starting one past [startKHz] in the seek direction,
     * wrapping at the band edge, every channel once, and never [startKHz] itself.
     */
    fun order(grid: IntArray, startKHz: Int, up: Boolean): IntArray {
        if (grid.isEmpty()) return grid
        val n = grid.size
        val first = if (up) grid.indexOfFirst { it > startKHz }.let { if (it < 0) 0 else it }
            else grid.indexOfLast { it < startKHz }.let { if (it < 0) n - 1 else it }
        val out = ArrayList<Int>(n)
        for (k in 0 until n) {
            val i = if (up) (first + k) % n else Math.floorMod(first - k, n)
            if (grid[i] != startKHz) out += grid[i]
        }
        return out.toIntArray()
    }

    /**
     * One seek. Walks [order] (see [order]) reading each channel with [probe] and returns the
     * first station, or null after a full pass with nothing found.
     *
     * Two things stop it landing on something that is not a new station:
     *  - channels right next to the start that read weaker than the start are the start
     *    station's own skirt and are passed over ([startRssi] is what the start read).
     *  - when it does find one, it keeps going while the next channel is the same station
     *    reading stronger, so it lands on the carrier, not on the edge it arrived at.
     *
     * [probe] returning null (a torn read) counts as no station there.
     */
    suspend fun seek(
        order: IntArray,
        startKHz: Int,
        startRssi: Int?,
        stepKHz: Int,
        t: Thresholds,
        probe: suspend (Int) -> Probe?,
    ): Int? {
        var prev = startKHz
        var inSkirt = startRssi != null
        var i = 0
        while (i < order.size) {
            val f = order[i]
            val adjacent = abs(f - prev) == stepKHz
            inSkirt = inSkirt && adjacent
            prev = f
            i++
            val p = probe(f)
            if (p == null || !isStation(p, t)) { inSkirt = false; continue }
            if (inSkirt && p.rssi < startRssi!!) continue
            // A station. Climb to its strongest channel.
            var best = f
            var bestRssi = p.rssi
            while (i < order.size && abs(order[i] - best) == stepKHz) {
                val np = probe(order[i]) ?: break
                if (!isStation(np, t) || np.rssi <= bestRssi) break
                best = order[i]; bestRssi = np.rssi
                i++
            }
            return best
        }
        return null
    }

    /**
     * The stations in a full band read: every channel that reads as a station, less the ones
     * that are only the skirt of a stronger neighbour one grid step away.
     *
     * [readings] is indexed like [grid]. The reach model is not consulted: a station that the
     * catalogue calls out of reach but that the chip hears is still a station.
     */
    fun stations(grid: IntArray, readings: Array<Probe?>, stepKHz: Int, t: Thresholds): List<Int> {
        val out = ArrayList<Int>()
        for (i in grid.indices) {
            val p = readings.getOrNull(i) ?: continue
            if (!isStation(p, t)) continue
            val skirt = listOf(i - 1, i + 1).any { j ->
                val q = readings.getOrNull(j)
                j in grid.indices && q != null && abs(grid[j] - grid[i]) == stepKHz &&
                    isStation(q, t) && q.rssi - p.rssi >= SKIRT_DB
            }
            if (!skirt) out += grid[i]
        }
        return out
    }
}
