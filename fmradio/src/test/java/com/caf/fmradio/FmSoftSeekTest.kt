package com.caf.fmradio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FmSoftSeekTest {

    private val us = FmSoftSeek.grid(87_500, 108_000, 200, 87_900)
    private val t = FmSoftSeek.thresholds(1)

    /** A band with nothing but noise except where [stations] says (kHz to rssi/snr/offset). */
    private fun band(stations: Map<Int, FmSoftSeek.Probe>): (Int) -> FmSoftSeek.Probe =
        { f -> stations[f] ?: FmSoftSeek.Probe(rssi = 4, snr = 1, offsetKHz = 0) }

    private fun seek(
        grid: IntArray, step: Int, start: Int, up: Boolean, startRssi: Int?,
        stations: Map<Int, FmSoftSeek.Probe>, visited: MutableList<Int> = ArrayList(),
    ): Int? = runBlocking {
        val b = band(stations)
        FmSoftSeek.seek(FmSoftSeek.order(grid, start, up), start, startRssi, step, t) { f -> visited += f; b(f) }
    }

    @Test fun usGridIsOddTenthsInsideTheBand() {
        // Anchored on 87.9 but the band starts at 87.5, which is also an odd tenth.
        assertEquals(87_500, us.first())
        assertEquals(107_900, us.last())
        assertEquals(103, us.size)
        assertTrue(us.all { (it / 100) % 2 == 1 })
    }

    @Test fun japanGridAnchoredBelowTheBase() {
        val jp = FmSoftSeek.grid(76_000, 95_000, 100, 87_500)
        assertEquals(76_000, jp.first())
        assertEquals(95_000, jp.last())
        assertEquals(191, jp.size)
    }

    @Test fun orderStartsPastTheStartAndWrapsOnce() {
        val g = intArrayOf(100, 200, 300, 400)
        assertArrayEquals(intArrayOf(300, 400, 100), FmSoftSeek.order(g, 200, up = true))
        assertArrayEquals(intArrayOf(100, 400, 300), FmSoftSeek.order(g, 200, up = false))
        // Off the grid: every channel is visited.
        assertArrayEquals(intArrayOf(300, 400, 100, 200), FmSoftSeek.order(g, 250, up = true))
        assertArrayEquals(intArrayOf(200, 100, 400, 300), FmSoftSeek.order(g, 250, up = false))
        // Past the top edge wraps to the bottom.
        assertArrayEquals(intArrayOf(100, 200, 300, 400), FmSoftSeek.order(g, 450, up = true))
    }

    /** The bug: on a strong station, seek must leave it, not report where it started. */
    @Test fun seekUpLeavesTheStartStation() {
        val st = mapOf(
            101_900 to FmSoftSeek.Probe(30, 21, -3),
            103_500 to FmSoftSeek.Probe(18, 9, 1),
            94_100 to FmSoftSeek.Probe(22, 10, 0),
        )
        assertEquals(103_500, seek(us, 200, 101_900, true, 30, st))
        assertEquals(94_100, seek(us, 200, 101_900, false, 30, st))
    }

    @Test fun seekWrapsAtTheBandEdge() {
        val st = mapOf(88_300 to FmSoftSeek.Probe(19, 8, 2), 107_100 to FmSoftSeek.Probe(25, 12, 0))
        assertEquals(88_300, seek(us, 200, 107_100, true, 25, st))
        assertEquals(107_100, seek(us, 200, 88_300, false, 19, st))
    }

    @Test fun nothingElseInTheBandGivesUpAfterOnePass() {
        val visited = ArrayList<Int>()
        val st = mapOf(101_900 to FmSoftSeek.Probe(30, 21, -3))
        assertNull(seek(us, 200, 101_900, true, 30, st, visited))
        assertEquals(us.size - 1, visited.size)
        assertFalse(101_900 in visited)
    }

    @Test fun skirtOfTheStartIsNotANewStation() {
        // 100 kHz grid: a strong local bleeds into both neighbours with a valid-looking read.
        val eu = FmSoftSeek.grid(87_500, 108_000, 100, 87_500)
        val st = mapOf(
            101_900 to FmSoftSeek.Probe(34, 22, 0),
            102_000 to FmSoftSeek.Probe(20, 7, 4),
            102_100 to FmSoftSeek.Probe(14, 5, 6),
            104_400 to FmSoftSeek.Probe(16, 8, 0),
        )
        assertEquals(104_400, seek(eu, 100, 101_900, true, 34, st))
    }

    @Test fun climbsFromTheEdgeToTheCarrier() {
        val eu = FmSoftSeek.grid(87_500, 108_000, 100, 87_500)
        val st = mapOf(
            97_900 to FmSoftSeek.Probe(15, 6, 9),
            98_000 to FmSoftSeek.Probe(29, 18, 1),
            98_100 to FmSoftSeek.Probe(16, 6, -9),
        )
        assertEquals(98_000, seek(eu, 100, 95_000, true, 20, st))
        assertEquals(98_000, seek(eu, 100, 100_000, false, 20, st))
    }

    @Test fun offsetRejectsANeighboursCarrier() {
        assertFalse(FmSoftSeek.isStation(FmSoftSeek.Probe(25, 12, 90), t))
        assertTrue(FmSoftSeek.isStation(FmSoftSeek.Probe(25, 12, -3), t))
        assertTrue(FmSoftSeek.isStation(FmSoftSeek.Probe(25, 12, null), t))
    }

    @Test fun noiseIsNotAStationAtAnySensitivity() {
        for (lvl in 0..3) {
            assertFalse(FmSoftSeek.isStation(FmSoftSeek.Probe(6, 2, 0), FmSoftSeek.thresholds(lvl)))
            assertTrue(FmSoftSeek.isStation(FmSoftSeek.Probe(30, 21, -3), FmSoftSeek.thresholds(lvl)))
        }
        // Stricter levels drop the weak ones.
        val weak = FmSoftSeek.Probe(14, 8, 0)
        assertTrue(FmSoftSeek.isStation(weak, FmSoftSeek.thresholds(1)))
        assertFalse(FmSoftSeek.isStation(weak, FmSoftSeek.thresholds(2)))
    }

    @Test fun tornReadsAreSkipped() = runBlocking {
        val g = intArrayOf(100, 200, 300)
        val found = FmSoftSeek.seek(FmSoftSeek.order(g, 100, true), 100, 30, 100, t) { f ->
            if (f == 200) null else FmSoftSeek.Probe(25, 12, 0)
        }
        assertEquals(300, found)
    }

    @Test fun scanKeepsStationsAndDropsSkirts() {
        val g = FmSoftSeek.grid(100_000, 100_600, 100, 100_000)
        val r = arrayOf<FmSoftSeek.Probe?>(
            FmSoftSeek.Probe(4, 1, 0),      // 100.0 noise
            FmSoftSeek.Probe(20, 8, 5),     // 100.1 skirt of 100.2
            FmSoftSeek.Probe(32, 20, 0),    // 100.2 station
            FmSoftSeek.Probe(4, 1, 0),      // 100.3
            FmSoftSeek.Probe(18, 9, 0),     // 100.4 station
            FmSoftSeek.Probe(16, 8, 0),     // 100.5 a second station, comparable, kept
            null,                           // 100.6 torn read
        )
        assertEquals(listOf(100_200, 100_400, 100_500), FmSoftSeek.stations(g, r, 100, t))
    }

    @Test fun signedOffsetFromTheDriverByte() {
        assertEquals(-3, FmSoftSeek.signedOffset(253))
        assertEquals(5, FmSoftSeek.signedOffset(5))
        assertNull(FmSoftSeek.signedOffset(null))
    }

    @Test fun frequencyReadbackUnwraps() {
        // Measured: 101.9 MHz reads back 57536.
        assertEquals(101_900, FmSoftSeek.unwrapKHz(57_536, 101_900))
        assertEquals(101_900, FmSoftSeek.unwrapKHz(57_536, 100_500))
        assertTrue(FmSoftSeek.landedOn(57_536, 101_900))
        assertFalse(FmSoftSeek.landedOn(57_536, 102_100))
        for (f in 64_000..108_000 step 50) {
            assertEquals(f, FmSoftSeek.unwrapKHz((f * 16) and 0xFFFF, f + 1_900))
        }
    }
}
