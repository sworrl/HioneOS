package com.caf.fmradio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * The reach model on Japanese stations, with the catalogue's own rows and real terrain.
 *
 * Station figures are the stations.sqlite rows that tools/radiodb/build_stations_jp.py wrote on
 * 2026-10-10: power and ERP from Japanese Wikipedia's transmitter tables, antenna heights from
 * the sources quoted in its TOWER_AGL table, ground from the Copernicus GLO-90 DEM. The ground
 * profiles below are GLO-90 samples along the great circle, spaced the way FmFresnel samples
 * them on the device (Open-Meteo serves the same DEM). GLO-90 is a surface model, so central
 * Tokyo reads a few tens of metres of buildings.
 *
 * Two listeners. Shinjuku Station (35.6896, 139.7006): NHK-FM Tokyo and J-WAVE from the Skytree
 * 10 km east, TOKYO FM and InterFM from Tokyo Tower 5 km south-east, and two community stations,
 * 渋谷のラジオ 2.7 km south and かつしかFM 14.5 km north-east. Kofu Station (35.6667, 138.5686):
 * NHK-FM Kofu 2.5 km away, and NHK-FM Tokyo 112 km away behind the mountains east of the
 * Kofu basin.
 *
 * Verdicts are with the default -25 dB offset (headphone-cable antenna, indoors, nothing
 * measured yet). None of this was measured in Japan; these are the predictions the app makes.
 */
class FmReachJapanTest {

    @After fun reset() { FmReach.restore(FmReach.DEFAULT_OFFSET_DB, 2) }

    private fun jp(
        call: String, service: String, khz: Int, erp: Double, haat: Double, rcamsl: Double?, km: Double,
        nameJa: String, name: String? = null,
    ) = FmStationCatalogue.Station(
        call = call, service = service, khz = khz, city = null, state = "東京都", licensee = null,
        lat = 0.0, lon = 0.0, erpKw = erp, haatM = haat, callsignSince = null,
        distanceKm = km, score = 0.0, rcamslM = rcamsl, country = "JP", name = name, nameJa = nameJa,
    )

    private fun profile(st: FmStationCatalogue.Station, g: List<Double>, total: Double): FmFresnel.Profile {
        val n = g.size
        val dist = (0 until n).map { total * it / (n - 1) }
        return FmFresnel.build(st, 0.0, 0.0, List(n) { 0.0 to 0.0 }, dist, g,
                               FmFresnel.txAntennaAmslM(st, g.last()), 0L)
    }

    private fun rate(st: FmStationCatalogue.Station, p: FmFresnel.Profile?): FmStationCatalogue.Station {
        val r = FmReach.annotate(listOf(st), null, 76000, 100, if (p != null) mapOf(st.call to p) else emptyMap()).single()
        println("%-12s %5.1f MHz %6.1f km  terrain %s %4.1f dB  predicted %5.1f dBuV -> %s".format(
            st.label, st.khz / 1000.0, st.distanceKm, p?.verdict ?: "-", p?.diffractionLossDb ?: 0.0,
            r.predictedDbuv, r.reach))
        return r
    }

    /** 35.6896 139.7006 35.710139 139.810833: 68 GLO-90 samples, listener first, 10.21 km. */
    private val shinjukuSkytree = listOf(
        49, 47, 57, 62, 43, 44, 45, 41, 31, 32, 38, 38, 35, 36, 37, 35, 36, 35, 25, 26,
        28, 33, 33, 31, 17, 13, 19, 20, 22, 18, 19, 6, 20, 14, 13, 23, 33, 37, 18, 44,
        26, 25, 13, 14, 13, 9, 12, 10, 8, 10, 9, 7, 10, 9, 8, 10, 10, 12, 11, 0,
        9, 7, 6, 6, 6, 6, 14, 7,
    ).map { it.toDouble() }
    private val shinjukuSkytreeM = 10212.6

    /** 35.6896 139.7006 35.658611 139.745556: 60 GLO-90 samples, listener first, 5.33 km. */
    private val shinjukuTokyoTower = listOf(
        49, 48, 41, 45, 59, 37, 40, 44, 37, 36, 36, 42, 44, 33, 30, 33, 36, 32, 30, 33,
        34, 39, 46, 36, 32, 32, 36, 42, 37, 38, 38, 39, 39, 35, 36, 40, 39, 33, 34, 25,
        22, 33, 25, 20, 27, 33, 25, 25, 28, 30, 34, 37, 24, 25, 18, 25, 25, 25, 33, 30,
    ).map { it.toDouble() }
    private val shinjukuTokyoTowerM = 5325.8

    /** 35.6896 139.7006 35.665771 139.699391: 60 GLO-90 samples, listener first, 2.65 km. */
    private val shinjukuShibuya = listOf(
        49, 49, 51, 51, 51, 51, 42, 42, 38, 38, 37, 37, 38, 38, 40, 40, 37, 37, 36, 36,
        34, 34, 37, 37, 45, 45, 52, 44, 53, 53, 53, 60, 60, 50, 50, 43, 43, 46, 46, 46,
        46, 45, 45, 48, 48, 49, 49, 42, 42, 40, 40, 42, 42, 37, 37, 37, 37, 36, 36, 34,
    ).map { it.toDouble() }
    private val shinjukuShibuyaM = 2651.9

    /** 35.6896 139.7006 35.742972 139.847417: 96 GLO-90 samples, listener first, 14.52 km. */
    private val shinjukuKatsushika = listOf(
        49, 42, 57, 69, 61, 44, 39, 36, 40, 40, 39, 38, 35, 40, 44, 37, 32, 25, 32, 33,
        31, 32, 31, 30, 23, 23, 19, 14, 6, 9, 10, 24, 35, 19, 10, 13, 17, 26, 31, 28,
        28, 32, 21, 10, 9, 14, 25, 24, 26, 19, 27, 24, 7, 10, 6, 7, 7, 6, 8, 7,
        8, 9, 6, 7, 6, 7, 4, 6, 2, 4, 4, 0, 8, 8, 8, 4, 3, 4, 1, 4,
        2, 0, 1, 0, 4, 1, 2, 4, 4, 4, 5, 3, 3, 1, 2, 3,
    ).map { it.toDouble() }
    private val shinjukuKatsushikaM = 14522.8

    /** 35.6667 138.5686 35.710139 139.810833: 200 GLO-90 samples, listener first, 112.29 km. */
    private val kofuSkytree = listOf(
        280, 283, 389, 261, 280, 438, 482, 381, 491, 706, 540, 354, 286, 285, 289, 293, 298, 303, 305, 308,
        316, 322, 335, 346, 356, 373, 383, 488, 471, 566, 702, 789, 942, 1195, 1218, 1085, 1256, 1382, 1294, 1344,
        1405, 1384, 1338, 1500, 1563, 1658, 1543, 1637, 1577, 1406, 1589, 1500, 1580, 1433, 1285, 1137, 1200, 909, 1002, 970,
        914, 1082, 1040, 806, 641, 667, 729, 1028, 1137, 1111, 1097, 910, 678, 707, 574, 485, 549, 451, 554, 543,
        571, 702, 594, 724, 808, 636, 628, 868, 755, 517, 674, 506, 885, 679, 494, 531, 686, 706, 670, 660,
        598, 592, 413, 379, 410, 360, 350, 357, 226, 233, 267, 269, 226, 191, 163, 167, 177, 156, 191, 198,
        152, 132, 125, 175, 96, 91, 80, 86, 89, 84, 83, 82, 89, 91, 90, 90, 86, 87, 85, 83,
        81, 80, 84, 82, 82, 74, 76, 78, 77, 74, 73, 71, 70, 70, 66, 65, 64, 62, 60, 68,
        61, 60, 63, 58, 54, 58, 54, 46, 49, 51, 49, 49, 42, 49, 43, 44, 42, 36, 42, 35,
        28, 35, 34, 33, 28, 16, 12, 13, 20, 33, 12, 29, 20, 22, 13, 8, 12, 6, 6, 7,
    ).map { it.toDouble() }
    private val kofuSkytreeM = 112292.6

    /** 35.6667 138.5686 35.6779 138.5929: 60 GLO-90 samples, listener first, 2.52 km. */
    private val kofuKofu = listOf(
        280, 280, 280, 280, 277, 282, 289, 289, 285, 284, 284, 284, 284, 286, 284, 284, 286, 286, 289, 289,
        292, 292, 301, 301, 320, 320, 358, 363, 403, 403, 443, 439, 441, 441, 436, 436, 426, 426, 436, 436,
        451, 451, 475, 475, 490, 490, 538, 538, 563, 540, 547, 547, 520, 528, 528, 509, 482, 487, 454, 454,
    ).map { it.toDouble() }
    private val kofuKofuM = 2523.7

    // Catalogue rows (erp kW, HAAT m, antenna m above sea level).
    private val nhkTokyo = jp("JOAK-FM", "FM", 82500, 57.0, 540.0, 547.4, shinjukuSkytreeM / 1000, "NHK-FM 東京", "NHK-FM Tokyo")
    private val jwave = jp("JOAV-FM", "FM", 81300, 57.0, 540.0, 547.4, shinjukuSkytreeM / 1000, "J-WAVE", "J-Wave")
    private val tokyoFm = jp("JOAU-FM", "FM", 80000, 125.0, 344.8, 362.6, shinjukuTokyoTowerM / 1000, "エフエム東京", "Tokyo FM")
    private val interFm = jp("JODW-FM", "FM", 89700, 13.0, 331.8, 349.6, shinjukuTokyoTowerM / 1000, "InterFM897", "InterFM")
    /** 20 W transmitter, ERP from the catalogue's community median gain (estimated), placed at the ward. */
    private val shibuya = jp("JOZZ3CG-FM", "FL", 87600, 0.037125, 34.1, 59.4, shinjukuShibuyaM / 1000, "渋谷のラジオ")
    /** 20 W, 46 W ERP as its site article states, on the Katsushika ward office. */
    private val katsushika = jp("JOZZ3AP-FM", "FL", 78900, 0.046, 25.0, 28.4, shinjukuKatsushikaM / 1000, "かつしかFM", "Katsushika FM")
    private val nhkKofu = jp("JOKG-FM", "FM", 85600, 4.6, 60.0, 513.5, kofuKofuM / 1000, "NHK-FM 甲府", "NHK-FM Kofu")
    private val nhkTokyoFromKofu = nhkTokyo.copy(distanceKm = kofuSkytreeM / 1000)

    @Test fun tokyoMainStationsComeInAcrossTheCity() {
        for ((st, g, m) in listOf(
            Triple(nhkTokyo, shinjukuSkytree, shinjukuSkytreeM),
            Triple(jwave, shinjukuSkytree, shinjukuSkytreeM),
            Triple(tokyoFm, shinjukuTokyoTower, shinjukuTokyoTowerM),
            Triple(interFm, shinjukuTokyoTower, shinjukuTokyoTowerM),
        )) {
            val p = profile(st, g, m)
            assertTrue("${st.label}: a 540 m or 330 m antenna clears the city", !p.blocked)
            val r = rate(st, p)
            assertEquals("${st.label} from Shinjuku", FmReach.Verdict.LIKELY, r.reach)
            assertTrue("${st.label}: strong, not marginal (${r.predictedDbuv})", r.predictedDbuv!! > FmReach.LISTENABLE_DBUV + 20)
        }
    }

    @Test fun communityFmIsLocal() {
        // Two and a half km from 20 W: should come in. Fourteen km from 46 W ERP: out of reach on
        // a headphone cable indoors, which is what community FM is licensed for.
        val near = rate(shibuya, profile(shibuya, shinjukuShibuya, shinjukuShibuyaM))
        assertTrue("渋谷のラジオ at 2.7 km should be in reach, was ${near.reach}", near.reach.inReach)
        val far = rate(katsushika, profile(katsushika, shinjukuKatsushika, shinjukuKatsushikaM))
        assertEquals("かつしかFM at 14.5 km", FmReach.Verdict.UNLIKELY, far.reach)
        assertTrue("low power reads well under a main station", near.predictedDbuv!! < rate(nhkTokyo, null).predictedDbuv!!)
    }

    @Test fun kofuHearsKofuNotTokyo() {
        val local = rate(nhkKofu, profile(nhkKofu, kofuKofu, kofuKofuM))
        assertEquals(FmReach.Verdict.LIKELY, local.reach)
        val p = profile(nhkTokyoFromKofu, kofuSkytree, kofuSkytreeM)
        assertTrue("the ranges between Kofu and Tokyo cut the line of sight", p.blocked)
        val far = rate(nhkTokyoFromKofu, p)
        // One equivalent knife edge is optimistic over several ranges (see FmFresnel), so this
        // lands at marginal rather than out of reach. What matters: not a station to expect,
        // and below what the tuner needs to be listenable.
        assertTrue("NHK-FM Tokyo, 112 km over the mountains, was ${far.reach}",
            far.reach == FmReach.Verdict.MARGINAL || far.reach == FmReach.Verdict.UNLIKELY)
        assertTrue(far.predictedDbuv!! < FmReach.LISTENABLE_DBUV)
        assertTrue("the local station is far stronger", local.predictedDbuv!! > far.predictedDbuv!! + 20)
    }

    @Test fun egliUsesTheStationsFrequency() {
        // The same transmitter at 76.1 and 101.9 MHz: Egli's 20 log f puts 76.1 2.5 dB stronger.
        val low = nhkTokyo.copy(khz = 76100)
        val high = nhkTokyo.copy(khz = 101900)
        assertEquals(20 * log10(101.9 / 76.1), FmReach.rawDbuv(low)!! - FmReach.rawDbuv(high)!!, 0.01)
    }

    @Test fun japaneseRowsShowTheirNames() {
        assertEquals("NHK-FM 東京", nhkTokyo.label)
        assertEquals("NHK 東京", nhkTokyo.chipLabel)
        assertEquals("NHK-FM Tokyo", nhkTokyo.altName)
        assertEquals("main", nhkTokyo.kind)
        assertEquals("community", shibuya.kind)
        assertNull(shibuya.altName)
        val us = nhkTokyo.copy(call = "WVAQ-FM", country = "US")
        assertEquals("WVAQ-FM", us.label)
        assertEquals("WVAQ", us.chipLabel)
        assertEquals("full power", us.kind)
    }

    @Test fun searchTakesJapaneseFrequencies() {
        assertEquals(76300, FmStationCatalogue.parseSearchFrequencyKHz("76.3"))
        assertEquals(76300, FmStationCatalogue.parseSearchFrequencyKHz("763"))
        assertEquals(90500, FmStationCatalogue.parseSearchFrequencyKHz("90.5 FM"))
        assertEquals(94500, FmStationCatalogue.parseSearchFrequencyKHz("945"))
        assertNull(FmStationCatalogue.parseSearchFrequencyKHz("60"))
        assertNull(FmStationCatalogue.parseSearchFrequencyKHz("J-WAVE"))
    }
}
