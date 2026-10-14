package com.hione.launcher.weather

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Rain nowcast maths, ported from the KDE Windy Weather widget (contents/code/nowcast.js, v3.2.0).
 * Pure Kotlin, no Android types, so it can be reasoned about and tested on its own.
 *
 * Source: RainViewer public tiles. The tile cache serves one palette whatever colour scheme is
 * asked for, so intensity is recovered by classifying that palette back to dBZ ([dbzFromArgb]).
 * The public API stopped carrying nowcast frames and anything past z7 in 2026, so arrival comes
 * from storm motion measured between past frames; nowcast frames are still used if they come back.
 */
object HioneNowcast {
    const val TILE = 256
    const val EARTH_M = 40075016.686
    const val NO_ECHO = -99f
    /** Highest zoom RainViewer still serves real tiles for; z8+ returns a placeholder image. */
    const val RAIN_MAX_ZOOM = 7

    // ------------------------------------------------------------------ slippy-map tile maths

    fun lonToTileX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * 2.0.pow(z)
    fun latToTileY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 2.0.pow(z)
    }
    fun tileXToLon(x: Double, z: Int) = x / 2.0.pow(z) * 360.0 - 180.0
    fun tileYToLat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / 2.0.pow(z)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }
    fun metersPerPixel(lat: Double, z: Int) = EARTH_M * cos(Math.toRadians(lat)) / (TILE * 2.0.pow(z))

    data class Tile(val x: Int, val y: Int, val col: Int, val row: Int, val valid: Boolean)

    /** n x n tiles centred on the tile that holds (lat, lon). px/py = the point in mosaic pixels. */
    data class Mosaic(
        val z: Int, val n: Int, val x0: Int, val y0: Int, val tiles: List<Tile>,
        val size: Int, val px: Double, val py: Double, val mpp: Double, val lat: Double, val lon: Double
    )

    fun mosaic(lat: Double, lon: Double, z: Int, n: Int = 3): Mosaic {
        val fx = lonToTileX(lon, z)
        val fy = latToTileY(lat, z)
        val tx = floor(fx).toInt()
        val ty = floor(fy).toInt()
        val half = n / 2
        val max = 1 shl z
        val x0 = tx - half
        val y0 = ty - half
        val tiles = ArrayList<Tile>(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            val x = ((x0 + i) % max + max) % max
            val y = y0 + j
            tiles.add(Tile(x, y, i, j, y in 0 until max))
        }
        return Mosaic(z, n, x0, y0, tiles, n * TILE, (fx - x0) * TILE, (fy - y0) * TILE, metersPerPixel(lat, z), lat, lon)
    }

    fun mosaicPixelToLatLon(m: Mosaic, px: Double, py: Double): Pair<Double, Double> =
        tileYToLat(m.y0 + py / TILE, m.z) to tileXToLon(m.x0 + px / TILE, m.z)

    fun rainviewerTile(host: String, path: String, z: Int, x: Int, y: Int, smooth: Boolean): String =
        "$host$path/256/$z/$x/$y/2/${if (smooth) "1_1" else "0_0"}.png"

    // ------------------------------------------------------------------ palette -> dBZ

    /**
     * Observed palette (51 opaque colours + translucent beige), ordered by intensity:
     *   translucent beige, alpha 20..190   very weak return (drizzle / clutter)   ~ -8 .. 8 dBZ
     *   20 blues  (136,221,238) -> (0,71,104)                                      10 .. 34
     *   11 yellow->orange (255,238,0) -> (255,68,0)                                35 .. 49
     *    9 reds   (242,54,0) -> (93,0,0)                                           51 .. 61
     *   10 magentas (255,170,255) -> (255,78,255)                                  62 .. 71
     *   white                                                                      75
     * The dBZ numbers are the widget's calibration of that ordering; RainViewer does not publish one.
     */
    fun dbzFromRgba(r: Int, g: Int, b: Int, a: Int): Float {
        if (a == 0) return NO_ECHO
        if (a < 255) {
            if (r >= g && g >= b && r - b < 90) // beige/grey: weak echo, alpha carries the strength
                return -8f + 16f * ((a - 20) / 170f).coerceIn(0f, 1f)
            val v = dbzFromRgba(r, g, b, 255) // a real colour thinned by blending at an edge
            return if (v == NO_ECHO) NO_ECHO else v * (a / 255f)
        }
        if (r == 255 && g == 255 && b == 255) return 75f
        if (r == 255 && b == 255) return 62f + (170 - g) / 92f * 9.5f
        if (r == 255 && b == 0) return if (g >= 129) 35f + (238 - g) / 109f * 10f else 45f + (129 - g) / 61f * 4f
        if (b == 0 && g <= 60) return 51f + (242 - r) / 149f * 10f
        if (b >= 200 && r <= 140 && g >= 160) return 10f + (136 - r) / 136f * 6.25f
        if (r == 0 && b < 230) return 16.25f + (163 - g) / 92f * 17.5f
        // Anything else is a blend from scaling: guess by hue
        if (b > r && b > g) return 10f + (255 - b) / 255f * 20f
        if (r > b && r >= g) return 35f + (255 - g) / 255f * 20f
        return NO_ECHO
    }

    /** Android colour int (non-premultiplied ARGB, as Bitmap.getPixels returns it) -> dBZ. */
    fun dbzFromArgb(c: Int): Float =
        dbzFromRgba((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, (c ushr 24) and 0xFF)

    // ------------------------------------------------------------------ grids

    class Grid(val w: Int, val h: Int, val v: FloatArray, val cover: Int = 0, val tiles: Int = 0)

    /** ARGB pixels (w x h) -> dBZ grid downsampled by [f] with max pooling. */
    fun gridFromArgb(px: IntArray, w: Int, h: Int, f: Int, tiles: Int): Grid {
        val gw = w / f
        val gh = h / f
        val v = FloatArray(gw * gh)
        var cover = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var best = NO_ECHO
            for (yy in 0 until f) {
                val row = (gy * f + yy) * w + gx * f
                for (xx in 0 until f) {
                    val c = px[row + xx]
                    if ((c ushr 24) == 0) continue
                    val d = dbzFromArgb(c)
                    if (d > best) best = d
                }
            }
            v[gy * gw + gx] = best
            if (best > NO_ECHO) cover++
        }
        return Grid(gw, gh, v, cover, tiles)
    }

    fun gridAt(g: Grid, x: Int, y: Int): Float =
        if (x < 0 || y < 0 || x >= g.w || y >= g.h) NO_ECHO else g.v[y * g.w + x]

    /** Max dBZ in a (2r+1)^2 neighbourhood. */
    fun maxAround(g: Grid, x: Double, y: Double, r: Int): Float {
        var best = NO_ECHO
        val cx = x.roundToInt()
        val cy = y.roundToInt()
        for (j in -r..r) for (i in -r..r) {
            val v = gridAt(g, cx + i, cy + j)
            if (v > best) best = v
        }
        return best
    }

    fun poolGrid(g: Grid, f: Int): Grid {
        val gw = g.w / f
        val gh = g.h / f
        val v = FloatArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            var best = NO_ECHO
            for (j in 0 until f) for (i in 0 until f) {
                val s = g.v[(y * f + j) * g.w + x * f + i]
                if (s > best) best = s
            }
            v[y * gw + x] = best
        }
        return Grid(gw, gh, v)
    }

    private class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val n: Int = 0)

    private fun rainBox(g: Grid, thr: Float): Box? {
        var x0 = g.w; var y0 = g.h; var x1 = -1; var y1 = -1; var n = 0
        for (y in 0 until g.h) for (x in 0 until g.w) {
            if (g.v[y * g.w + x] >= thr) {
                n++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        return if (n > 0) Box(x0, y0, x1, y1, n) else null
    }

    // ------------------------------------------------------------------ motion (block matching)

    data class Motion(val dx: Double = 0.0, val dy: Double = 0.0, val quality: Double = 0.0, val pixels: Int = 0)

    private fun intensity(v: Float, floor: Float) = if (v > floor) v - floor else 0f

    /** Mean |cur(x,y) - prev(x-dx, y-dy)| over the box. Positive dx = the rain moved east. */
    private fun sad(prev: Grid, cur: Grid, box: Box, dx: Int, dy: Int, floor: Float): Double {
        var s = 0.0
        var n = 0
        for (y in box.y0..box.y1) {
            val py = y - dy
            if (py < 0 || py >= cur.h) continue
            for (x in box.x0..box.x1) {
                val px = x - dx
                if (px < 0 || px >= cur.w) continue
                val a = intensity(cur.v[y * cur.w + x], floor)
                val b = intensity(prev.v[py * prev.w + px], floor)
                if (a == 0f && b == 0f) continue
                s += abs(a - b)
                n++
            }
        }
        return if (n > 0) s / n else Double.POSITIVE_INFINITY
    }

    private data class Shift(val dx: Int, val dy: Int, val sad: Double)

    private fun searchShift(prev: Grid, cur: Grid, box: Box, cx: Int, cy: Int, radius: Int, floor: Float): Shift {
        var best = Shift(cx, cy, Double.POSITIVE_INFINITY)
        for (dy in cy - radius..cy + radius) for (dx in cx - radius..cx + radius) {
            val s = sad(prev, cur, box, dx, dy, floor)
            if (s < best.sad) best = Shift(dx, dy, s)
        }
        return best
    }

    /**
     * Global motion between two grids, in grid px per frame. Coarse-to-fine: /4 with a wide search,
     * then +-3 at full resolution. quality = how much the best shift beats no shift; < 0.05 is "unknown".
     */
    fun estimateMotion(prev: Grid, cur: Grid, thr: Float = 10f, floor: Float = 5f, maxShift: Int = 12, minPixels: Int = 25): Motion {
        val boxC = rainBox(cur, thr)
        val boxP = rainBox(prev, thr)
        if (boxC == null || boxP == null || boxC.n < minPixels || boxP.n < minPixels) return Motion(pixels = boxC?.n ?: 0)
        val pad = maxShift
        val box = Box(
            max(0, min(boxC.x0, boxP.x0) - pad), max(0, min(boxC.y0, boxP.y0) - pad),
            min(cur.w - 1, max(boxC.x1, boxP.x1) + pad), min(cur.h - 1, max(boxC.y1, boxP.y1) + pad)
        )
        val f = 4
        val pc = poolGrid(prev, f)
        val cc = poolGrid(cur, f)
        val boxc = Box(box.x0 / f, box.y0 / f, min(pc.w - 1, box.x1 / f), min(pc.h - 1, box.y1 / f))
        val coarse = searchShift(pc, cc, boxc, 0, 0, ceil(maxShift / f.toDouble()).toInt(), floor)
        val fine = searchShift(prev, cur, box, coarse.dx * f, coarse.dy * f, 3, floor)
        val zero = sad(prev, cur, box, 0, 0, floor)
        val quality = if (zero > 0 && fine.sad.isFinite()) max(0.0, (zero - fine.sad) / zero) else 0.0
        return Motion(fine.dx.toDouble(), fine.dy.toDouble(), quality, boxC.n)
    }

    /** Combine per-pair motions (newest last) into one vector; newer pairs weigh more. */
    fun combineMotion(list: List<Motion>): Motion {
        var sx = 0.0; var sy = 0.0; var sw = 0.0; var best = 0.0
        list.forEachIndexed { i, m ->
            if (m.quality < 0.05) return@forEachIndexed
            val w = m.quality * (1 + i)
            sx += m.dx * w; sy += m.dy * w; sw += w
            if (m.quality > best) best = m.quality
        }
        if (sw == 0.0) return Motion()
        return Motion(sx / sw, sy / sw, best)
    }

    data class Block(val cx: Double, val cy: Double, val dx: Double, val dy: Double, val local: Boolean, val cover: Double, val mean: Double)

    /** Local vectors on a block grid (block size [bs] px), refined around the global vector. */
    fun motionField(prev: Grid, cur: Grid, global: Motion, bs: Int, thr: Float = 10f, floor: Float = 5f): List<Block> {
        val out = ArrayList<Block>()
        val minPx = max(6, (bs * bs * 0.04).toInt())
        var by = 0
        while (by < cur.h) {
            var bx = 0
            while (bx < cur.w) {
                val box = Box(bx, by, min(cur.w - 1, bx + bs - 1), min(cur.h - 1, by + bs - 1))
                var n = 0
                var sum = 0.0
                for (y in box.y0..box.y1) for (x in box.x0..box.x1) {
                    val v = cur.v[y * cur.w + x]
                    if (v >= thr) { n++; sum += v }
                }
                if (n >= minPx) {
                    var dx = global.dx
                    var dy = global.dy
                    var local = false
                    if (global.quality >= 0.05 && n >= minPx * 3) {
                        val gx = global.dx.roundToInt()
                        val gy = global.dy.roundToInt()
                        val r = searchShift(prev, cur, box, gx, gy, 3, floor)
                        val zero = sad(prev, cur, box, gx, gy, floor)
                        if (r.sad.isFinite() && zero > 0 && (zero - r.sad) / zero > 0.08) {
                            dx = r.dx.toDouble(); dy = r.dy.toDouble(); local = true
                        }
                    }
                    out.add(Block(bx + bs / 2.0, by + bs / 2.0, dx, dy, local, n / (bs * bs).toDouble(), sum / n))
                }
                bx += bs
            }
            by += bs
        }
        return out
    }

    // ------------------------------------------------------------------ geometry

    private val COMPASS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    fun compass(deg: Double): String = COMPASS[(((deg % 360) + 360) % 360 / 22.5).roundToInt() % 16]
    /** Grid axes: +x east, +y south. Bearing 0 = north, 90 = east. */
    fun bearingOf(dx: Double, dy: Double) = (Math.toDegrees(atan2(dx, -dy)) + 360) % 360
    fun speedKmh(dx: Double, dy: Double, mppGrid: Double, dtSec: Double): Double {
        val px = sqrt(dx * dx + dy * dy)
        return if (dtSec > 0) px * mppGrid / 1000.0 / dtSec * 3600.0 else 0.0
    }

    // ------------------------------------------------------------------ arrival estimate

    private class March(val steps: Int, val x: Double, val y: Double)

    /**
     * Walk from (px, py) along (ux, uy) up to [maxPx]. wantRain: first step whose neighbourhood
     * reaches [thr]. Otherwise: where the rain ends (a gap of [gapPx]). [cone] widens the search
     * with distance (0.27 ~ +-15 deg) because storms rarely track dead straight.
     */
    private fun marchTo(g: Grid, px: Double, py: Double, ux: Double, uy: Double, thr: Float, maxPx: Int,
                        wantRain: Boolean, gapPx: Int = 3, cone: Double = 0.0): March? {
        var gap = 0
        for (s in 1..maxPx) {
            val x = px + ux * s
            val y = py + uy * s
            if (x < 1 || y < 1 || x >= g.w - 1 || y >= g.h - 1) return null
            val r = if (cone > 0) min(12, max(1, (s * cone).roundToInt())) else 1
            val v = maxAround(g, x, y, r)
            if (wantRain) {
                if (v >= thr) return March(s, x, y)
            } else {
                if (v >= thr) gap = 0 else if (++gap >= gapPx) return March(s - gap + 1, x, y)
            }
        }
        return null
    }

    private class Near(val x: Int, val y: Int, val dist: Double)

    private fun nearestRain(g: Grid, px: Double, py: Double, thr: Float, maxPx: Int): Near? {
        var best: Near? = null
        var bestD2 = Double.MAX_VALUE
        val r2 = maxPx.toDouble() * maxPx
        val x0 = max(0, floor(px - maxPx).toInt()); val x1 = min(g.w - 1, ceil(px + maxPx).toInt())
        val y0 = max(0, floor(py - maxPx).toInt()); val y1 = min(g.h - 1, ceil(py + maxPx).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            if (g.v[y * g.w + x] < thr) continue
            val d2 = (x - px) * (x - px) + (y - py) * (y - py)
            if (d2 <= r2 && d2 < bestD2) { bestD2 = d2; best = Near(x, y, sqrt(d2)) }
        }
        return best
    }

    data class Approach(val distKm: Double, val minutes: Int, val from: String)

    /** Everything the UI needs from the analysed frames. Null fields = not determined. */
    data class Arrival(
        val status: String = "nodata", // nodata | now | eta | dry
        val source: String = "",        // nowcast | motion
        val minutes: Int? = null,
        val frameTime: Long? = null,
        val distKm: Double? = null,
        val from: String = "",
        val fromDeg: Double? = null,
        val clearKm: Double? = null,
        val clearMin: Int? = null,
        val nearestKm: Double? = null,
        val nearest: String = "",
        val movingAway: Boolean = false,
        val stationary: Boolean = false,
        val approaching: Approach? = null,
        val horizonMin: Int? = null,
        val speedKmh: Double = 0.0,
        val heading: String = "",
        val quality: Double = 0.0,
        val ageMin: Int = 0,
        val hereDbz: Float = NO_ECHO
    )

    class TimedGrid(val time: Long, val grid: Grid)

    /**
     * past: oldest -> newest. nowcast: soonest -> latest (may be empty). (px, py): the location in
     * grid pixels. motion in grid px per frame; frameSec between frames; times in unix seconds.
     */
    fun arrival(past: List<TimedGrid>, nowcast: List<TimedGrid>, px: Double, py: Double, mppGrid: Double,
                motion: Motion, frameSec: Double, thr: Float, nowSec: Long): Arrival {
        val latest = past.lastOrNull() ?: return Arrival()
        val g = latest.grid
        val kmPerPx = mppGrid / 1000.0
        val spd = speedKmh(motion.dx, motion.dy, mppGrid, frameSec)
        val moving = motion.quality >= 0.05 && spd >= 3
        val len = sqrt(motion.dx * motion.dx + motion.dy * motion.dy).takeIf { it > 0 } ?: 1.0
        val ux = motion.dx / len
        val uy = motion.dy / len
        var out = Arrival(
            speedKmh = if (moving) spd else 0.0,
            heading = if (moving) compass(bearingOf(motion.dx, motion.dy)) else "",
            quality = motion.quality,
            ageMin = max(0, ((nowSec - latest.time) / 60.0).roundToInt()),
            hereDbz = maxAround(g, px, py, 1)
        )

        // Raining now
        if (out.hereDbz >= thr) {
            out = out.copy(status = "now")
            if (moving) {
                marchTo(g, px, py, -ux, -uy, thr, (300 / kmPerPx).roundToInt(), false, 3)?.let { end ->
                    val km = end.steps * kmPerPx
                    out = out.copy(clearKm = km, clearMin = (km / spd * 60).roundToInt())
                }
            }
            return out
        }
        // Nowcast frames say when it arrives
        for (f in nowcast) {
            if (maxAround(f.grid, px, py, 1) >= thr) {
                out = out.copy(status = "eta", source = "nowcast", minutes = max(1, ((f.time - nowSec) / 60.0).roundToInt()), frameTime = f.time)
                nearestRain(g, px, py, thr, (150 / kmPerPx).roundToInt())?.let { nr ->
                    val deg = bearingOf(nr.x - px, nr.y - py)
                    out = out.copy(distKm = nr.dist * kmPerPx, fromDeg = deg, from = compass(deg))
                }
                return out
            }
        }
        // March upstream along the motion
        val near = nearestRain(g, px, py, thr, (150 / kmPerPx).roundToInt())
        if (near != null) out = out.copy(nearestKm = near.dist * kmPerPx, nearest = compass(bearingOf(near.x - px, near.y - py)))
        if (moving) {
            marchTo(g, px, py, -ux, -uy, thr, (150 / kmPerPx).roundToInt(), true, 3, 0.27)?.let { hit ->
                val distKm = hit.steps * kmPerPx
                val minutes = max(1, (distKm / spd * 60 - out.ageMin).roundToInt())
                val fromDeg = bearingOf(hit.x - px, hit.y - py)
                if (minutes <= 120) { // extrapolating radar further than ~2 h is guesswork
                    return out.copy(status = "eta", source = "motion", distKm = distKm, minutes = minutes, fromDeg = fromDeg, from = compass(fromDeg))
                }
                out = out.copy(approaching = Approach(distKm, minutes, compass(fromDeg)))
            }
            if (near != null) {
                // Rain nearby but not on the line: moving away if it lies ahead of us along the motion
                val rel = (near.x - px) * ux + (near.y - py) * uy
                out = out.copy(movingAway = rel > 0)
            }
        } else if (near != null && (out.nearestKm ?: 999.0) <= 40) {
            out = out.copy(stationary = true)
        }
        return out.copy(
            status = if (nowcast.isNotEmpty() || past.isNotEmpty()) "dry" else "nodata",
            // How far ahead "no rain" is worth claiming: the radar reach along the motion, capped at 2 h
            horizonMin = if (moving) (150 / spd * 60).roundToInt().coerceIn(30, 120) else 60
        )
    }

    // ------------------------------------------------------------------ Open-Meteo 15-minute fallback

    data class Om15(val fetchedMs: Long, val firstAtMs: Long?, val firstMm: Double?, val wetSlots: Int)

    // ------------------------------------------------------------------ text

    data class Summary(
        val text: String = "",
        val short: String = "",
        val color: Long = COLOR_NONE,
        val level: String = "unknown", // unknown | now | soon | later | dry
        val minutes: Int? = null,
        val detail: String = ""
    )

    const val COLOR_NOW = 0xFF4AA3DF
    const val COLOR_SOON = 0xFFF2A900
    const val COLOR_LATER = 0xFF5B8DEF
    const val COLOR_DRY = 0xFF5FB760
    const val COLOR_NONE = 0xFF8A8F98

    private fun fmtMin(min: Int): String {
        if (min < 60) return "~$min min"
        val h = min / 60
        val m = min % 60
        return "~$h h" + if (m >= 10) " $m min" else ""
    }

    private fun spd(kmh: Double, imperial: Boolean) =
        if (imperial) "${(kmh * 0.621371).roundToInt()} mph" else "${kmh.roundToInt()} km/h"

    private fun dist(km: Double, imperial: Boolean) =
        if (imperial) "${(km * 0.621371).roundToInt()} mi" else "${km.roundToInt()} km"

    private fun clock(ms: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))

    /** One line for the screen plus a short tag for chips and notifications. No emojis on purpose. */
    fun summarize(r: Arrival?, om: Om15?, imperial: Boolean, nowMs: Long): Summary {
        if (r == null || r.status == "nodata") {
            if (om?.firstAtMs != null) {
                val m0 = ((om.firstAtMs - nowMs) / 60000.0).roundToInt()
                return Summary(
                    text = "Rain likely around ${clock(om.firstAtMs)} (in ${fmtMin(max(1, m0)).removePrefix("~")}). No radar coverage here.",
                    short = "Rain ${clock(om.firstAtMs)}",
                    color = if (m0 <= 30) COLOR_SOON else COLOR_LATER,
                    level = if (m0 <= 30) "soon" else "later",
                    minutes = m0
                )
            }
            if (om != null) return Summary("No rain expected in the next 24 h. No radar coverage here.", "Dry 24 h", COLOR_DRY, "dry")
            return Summary("Rain nowcast not available yet.")
        }
        if (r.status == "now") {
            val text = when {
                r.clearMin != null -> "Raining now, clearing in ${fmtMin(max(1, r.clearMin))}"
                r.speedKmh > 0 -> "Raining now, moving ${r.heading} at ${spd(r.speedKmh, imperial)}"
                else -> "Raining now, not moving much"
            }
            val detail = r.clearKm?.let { "Back edge of the rain is ${dist(it, imperial)} upstream." } ?: ""
            return Summary(text, "Raining", COLOR_NOW, "now", 0, detail)
        }
        if (r.status == "eta") {
            val m = r.minutes ?: 0
            var text = "Rain in ${fmtMin(m)}"
            if (r.from.isNotEmpty()) text += " from the ${r.from}"
            if (r.speedKmh > 0) text += " at ${spd(r.speedKmh, imperial)}"
            val detail = when {
                r.source == "nowcast" && r.frameTime != null -> "From the RainViewer nowcast frame at ${clock(r.frameTime * 1000)}."
                r.distKm != null -> "Front edge is ${dist(r.distKm, imperial)} out, heading ${r.heading}."
                else -> ""
            }
            return Summary(text, "Rain " + if (m < 60) "$m min" else fmtMin(m).removePrefix("~"),
                if (m <= 30) COLOR_SOON else COLOR_LATER, if (m <= 30) "soon" else "later", m, detail)
        }
        // Dry within the radar horizon
        val horizon = r.horizonMin?.let { if (it >= 90) "${(it / 60.0).roundToInt()} h" else "$it min" } ?: "2 h"
        var text = "No rain in the next $horizon"
        var detail = ""
        var short = ""
        var level = "dry"
        var color = COLOR_DRY
        var minutes: Int? = null
        if (r.approaching != null) {
            text += ". Rain ${dist(r.approaching.distKm, imperial)} to the ${r.approaching.from} is heading this way (${fmtMin(r.approaching.minutes)})"
            detail = "That is past the 2 h the radar can be trusted for."
        } else if (r.nearestKm != null) {
            val where = "${dist(r.nearestKm, imperial)} to the ${r.nearest}"
            text += when {
                r.movingAway -> ". Rain $where is moving away"
                r.stationary -> ". Rain $where, not moving much"
                else -> ". Nearest rain is $where"
            }
        }
        if (om?.firstAtMs != null) {
            val mins = ((om.firstAtMs - nowMs) / 60000.0).roundToInt()
            if (mins > 0) {
                text += ". Likely around ${clock(om.firstAtMs)}"
                minutes = mins; short = "Rain ${clock(om.firstAtMs)}"; level = "later"; color = COLOR_LATER
            }
        } else if (om != null) {
            text += ". None expected in 24 h"
        }
        if (short.isEmpty()) short = "Dry $horizon"
        return Summary(text, short, color, level, minutes, detail)
    }

    // ------------------------------------------------------------------ drawing helpers

    /** Windy-style precipitation palette rows: dBZ, r, g, b, a. */
    private val PALETTE = arrayOf(
        intArrayOf(5, 168, 200, 255, 70), intArrayOf(10, 120, 175, 255, 130), intArrayOf(15, 70, 140, 245, 175),
        intArrayOf(20, 30, 95, 230, 205), intArrayOf(25, 0, 60, 210, 225), intArrayOf(30, 20, 170, 90, 235),
        intArrayOf(35, 120, 210, 40, 240), intArrayOf(40, 240, 230, 40, 245), intArrayOf(45, 250, 160, 20, 250),
        intArrayOf(50, 240, 70, 30, 252), intArrayOf(55, 210, 30, 30, 255), intArrayOf(60, 220, 40, 200, 255),
        intArrayOf(70, 255, 255, 255, 255)
    )

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** dBZ -> ARGB colour int in the Windy palette, 0 (transparent) below the first stop. */
    fun windyColor(dbz: Float): Int {
        val p0 = PALETTE[0]
        if (dbz < p0[0] - 3) return 0
        if (dbz <= p0[0]) return argb((p0[4] * (dbz - (p0[0] - 3)) / 3f).roundToInt().coerceIn(0, 255), p0[1], p0[2], p0[3])
        for (i in 1 until PALETTE.size) {
            val b = PALETTE[i]
            if (dbz <= b[0]) {
                val a = PALETTE[i - 1]
                val t = (dbz - a[0]) / (b[0] - a[0]).toFloat()
                fun lerp(k: Int) = (a[k] + (b[k] - a[k]) * t).roundToInt()
                return argb(lerp(4), lerp(1), lerp(2), lerp(3))
            }
        }
        val l = PALETTE.last()
        return argb(l[4], l[1], l[2], l[3])
    }

    data class Arrow(val cx: Double, val cy: Double, val ux: Double, val uy: Double, val local: Boolean)

    /** One arrow per block where rain covers at least [minCover] of it. */
    fun arrows(field: List<Block>, minCover: Double = 0.04): List<Arrow> = field.mapNotNull { f ->
        if (f.cover < minCover) return@mapNotNull null
        val len = sqrt(f.dx * f.dx + f.dy * f.dy)
        Arrow(f.cx, f.cy, if (len > 0) f.dx / len else 0.0, if (len > 0) f.dy / len else 0.0, f.local)
    }
}
