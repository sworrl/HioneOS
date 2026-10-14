package com.hione.riot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import org.json.JSONObject

/** A 1-bit image: true where there is ink. */
class Sprite(val w: Int, val h: Int, val bits: BooleanArray) {
    fun at(x: Int, y: Int) = bits[y * w + x]

    companion object {
        /** Ink = an opaque, dark pixel. Works for black-on-transparent and black-on-white sheets. */
        fun fromBitmap(b: Bitmap, x0: Int = 0, y0: Int = 0, w: Int = b.width, h: Int = b.height): Sprite {
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, x0, y0, w, h)
            val bits = BooleanArray(w * h) { i ->
                val c = px[i]
                Color.alpha(c) > 127 && (Color.red(c) + Color.green(c) + Color.blue(c)) < 384
            }
            return Sprite(w, h, bits)
        }
    }
}

/**
 * A bitmap font read from assets: a one-row PNG sheet plus a JSON of metrics (see
 * tools/make_riot_assets.py for the format). Swapping the look is a data change.
 */
class BitmapFont(
    val height: Int,
    val ascent: Int,
    val space: Int,
    private val glyphs: Map<Char, Glyph>,
    /** True when the sheet is already bold; otherwise bold is made by a 1 px smear. */
    val isBold: Boolean
) {
    class Glyph(val ox: Int, val adv: Int, val sprite: Sprite)

    fun glyph(c: Char): Glyph? = glyphs[c]

    companion object {
        fun load(ctx: Context, name: String): BitmapFont? = runCatching {
            val json = JSONObject(ctx.assets.open("riot/$name.json").bufferedReader().use { it.readText() })
            val opts = BitmapFactory.Options().apply { inScaled = false; inPremultiplied = false }
            val sheet = ctx.assets.open("riot/$name.png").use { BitmapFactory.decodeStream(it, null, opts) } ?: error("no sheet")
            val h = json.getInt("height")
            val g = json.getJSONObject("glyphs")
            val map = HashMap<Char, Glyph>()
            for (k in g.keys()) {
                if (k.isEmpty()) continue
                val o = g.getJSONObject(k)
                val w = o.getInt("w")
                val sprite = if (w > 0) Sprite.fromBitmap(sheet, o.getInt("x"), 0, w, h) else Sprite(0, h, BooleanArray(0))
                map[k[0]] = Glyph(o.optInt("ox", 0), o.getInt("adv"), sprite)
            }
            sheet.recycle()
            BitmapFont(h, json.getInt("ascent"), json.optInt("space", 3), map, json.optBoolean("bold", false))
        }.onFailure { Log.e("RiotAssets", "font $name: $it") }.getOrNull()
    }
}

/** Everything the LCD draws that is not geometry. Loaded once per process. */
object RiotAssets {
    lateinit var large: BitmapFont
    lateinit var medium: BitmapFont
    lateinit var small: BitmapFont
    lateinit var tiny: BitmapFont
    /** Bold sheets are optional; when absent the regular sheet is smeared 1 px. */
    var mediumBold: BitmapFont? = null
    var smallBold: BitmapFont? = null
    val icons = HashMap<String, Sprite>()
    /** The boot/About logo, one replaceable file: assets/riot/logo_rioriot.png. Null if removed. */
    var logo: Sprite? = null
    @Volatile private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        large = BitmapFont.load(ctx, "font_large")!!
        medium = BitmapFont.load(ctx, "font_medium")!!
        small = BitmapFont.load(ctx, "font_small")!!
        tiny = BitmapFont.load(ctx, "font_tiny")!!
        mediumBold = if (exists(ctx, "font_medium_bold.json")) BitmapFont.load(ctx, "font_medium_bold") else null
        smallBold = if (exists(ctx, "font_small_bold.json")) BitmapFont.load(ctx, "font_small_bold") else null
        val opts = BitmapFactory.Options().apply { inScaled = false; inPremultiplied = false }
        runCatching {
            for (f in ctx.assets.list("riot/icons").orEmpty()) {
                if (!f.endsWith(".png")) continue
                ctx.assets.open("riot/icons/$f").use { s ->
                    BitmapFactory.decodeStream(s, null, opts)?.let { icons[f.removeSuffix(".png")] = Sprite.fromBitmap(it); it.recycle() }
                }
            }
        }
        logo = runCatching {
            ctx.assets.open("riot/logo_rioriot.png").use { s ->
                BitmapFactory.decodeStream(s, null, opts)?.let { val sp = Sprite.fromBitmap(it); it.recycle(); sp }
            }
        }.getOrNull()
        loaded = true
    }

    private fun exists(ctx: Context, name: String) = runCatching { ctx.assets.list("riot")?.contains(name) == true }.getOrDefault(false)

    fun boldOf(f: BitmapFont): BitmapFont? = when (f) {
        medium -> mediumBold
        small -> smallBold
        else -> null
    }
}

/**
 * The emulated 240 x 160 LCD. Each pixel holds one of four levels:
 * 0 = clear, 1 = light gray, 2 = dark gray, 3 = full ink. The real panel showed grays (the
 * dimmed screen under the menu, the empty part of the volume bar, the menu tabs), so a pure
 * 1-bit buffer would not be faithful.
 */
class Lcd(val w: Int = W, val h: Int = H) {
    val px = ByteArray(w * h)
    private var cx0 = 0; private var cy0 = 0; private var cx1 = w; private var cy1 = h

    fun clear() { px.fill(0); unclip() }

    fun clip(x0: Int, y0: Int, x1: Int, y1: Int) {
        cx0 = maxOf(0, x0); cy0 = maxOf(0, y0); cx1 = minOf(w, x1); cy1 = minOf(h, y1)
    }
    fun unclip() { cx0 = 0; cy0 = 0; cx1 = w; cy1 = h }

    fun set(x: Int, y: Int, lvl: Int = INK) {
        if (x < cx0 || y < cy0 || x >= cx1 || y >= cy1) return
        px[y * w + x] = lvl.toByte()
    }

    fun hline(x0: Int, x1: Int, y: Int, lvl: Int = INK) { for (x in x0..x1) set(x, y, lvl) }
    fun vline(x: Int, y0: Int, y1: Int, lvl: Int = INK) { for (y in y0..y1) set(x, y, lvl) }
    fun fill(x: Int, y: Int, rw: Int, rh: Int, lvl: Int = INK) {
        for (yy in y until y + rh) for (xx in x until x + rw) set(xx, yy, lvl)
    }
    fun rect(x: Int, y: Int, rw: Int, rh: Int, lvl: Int = INK) {
        hline(x, x + rw - 1, y, lvl); hline(x, x + rw - 1, y + rh - 1, lvl)
        vline(x, y, y + rh - 1, lvl); vline(x + rw - 1, y, y + rh - 1, lvl)
    }

    /** Horizontal inset of a rounded corner of radius [r] at row [dy] from the top or bottom. */
    private fun inset(r: Int, dy: Int): Int {
        if (dy >= r) return 0
        val yy = r - dy - 0.5
        return (r - kotlin.math.sqrt((r * r - yy * yy).coerceAtLeast(0.0))).toInt()
    }

    /**
     * Rounded rectangle. [openRight] leaves the right side square and without a border line, the
     * way a menu tab runs off the edge of the screen.
     */
    fun round(x: Int, y: Int, rw: Int, rh: Int, r: Int, fill: Int? = null, line: Int? = INK,
              openRight: Boolean = false) {
        if (rw <= 0 || rh <= 0) return
        val rr = minOf(r, rh / 2, rw / 2)
        val inside = BooleanArray(rw * rh)
        for (dy in 0 until rh) {
            val e = minOf(dy, rh - 1 - dy)
            val li = inset(rr, e)
            val ri = if (openRight) 0 else li
            for (dx in li until rw - ri) inside[dy * rw + dx] = true
        }
        fun ins(dx: Int, dy: Int): Boolean {
            if (dy < 0 || dy >= rh || dx < 0) return false
            if (dx >= rw) return openRight
            return inside[dy * rw + dx]
        }
        for (dy in 0 until rh) for (dx in 0 until rw) {
            if (!inside[dy * rw + dx]) continue
            val edge = !ins(dx - 1, dy) || !ins(dx + 1, dy) || !ins(dx, dy - 1) || !ins(dx, dy + 1)
            if (edge && line != null) set(x + dx, y + dy, line)
            else if (fill != null) set(x + dx, y + dy, fill)
        }
    }

    fun sprite(s: Sprite?, x: Int, y: Int, lvl: Int = INK) {
        s ?: return
        for (yy in 0 until s.h) for (xx in 0 until s.w) if (s.at(xx, yy)) set(x + xx, y + yy, lvl)
    }

    /** Lightens everything already drawn, the "track info dims" step before the menu slides in. */
    fun dim() { for (i in px.indices) if (px[i] > 1) px[i] = 1 }

    // ---- text ---------------------------------------------------------------------------------

    private val fbPaint = Paint().apply { isAntiAlias = false; color = Color.BLACK; typeface = Typeface.DEFAULT }
    private var fbBmp: Bitmap? = null
    private var fbCanvas: Canvas? = null

    fun width(f: BitmapFont, s: String, bold: Boolean = false): Int = measure(f, s, bold)

    /** Draws [s] with the top of the font cell at [y]. Returns the pen x after the text. */
    fun text(f0: BitmapFont, x: Int, y: Int, s: String, lvl: Int = INK, bold: Boolean = false): Int {
        val f = if (bold) RiotAssets.boldOf(f0) ?: f0 else f0
        val smear = bold && f === f0 && !f.isBold
        var pen = x
        for (c in s) {
            if (pen >= cx1) break
            val g = f.glyph(c) ?: f.glyph(fold(c))
            if (g != null) {
                val sp = g.sprite
                for (yy in 0 until sp.h) for (xx in 0 until sp.w) if (sp.at(xx, yy)) {
                    set(pen + g.ox + xx, y + yy, lvl)
                    if (smear) set(pen + g.ox + xx + 1, y + yy, lvl)
                }
                pen += g.adv + if (smear) 1 else 0
            } else if (c == ' ') {
                pen += f.space
            } else {
                pen += fallback(f, pen, y, c, lvl, smear)
            }
        }
        return pen
    }

    /**
     * Characters the sheet does not have (kana, kanji, most symbols) go through the system font
     * with aliasing off at the sheet's own size, then a hard threshold, so they land on the same
     * pixel grid. The real player could not show them at all; this keeps a Japanese library usable.
     */
    private fun fallback(f: BitmapFont, x: Int, y: Int, c: Char, lvl: Int, smear: Boolean): Int {
        val size = f.ascent + 1
        val bw = size * 2 + 2; val bh = f.height + 2
        var b = fbBmp
        if (b == null || b.width < bw || b.height < bh) {
            b = Bitmap.createBitmap(maxOf(bw, 32), maxOf(bh, 32), Bitmap.Config.ARGB_8888)
            fbBmp = b; fbCanvas = Canvas(b)
        }
        b.eraseColor(Color.TRANSPARENT)
        fbPaint.textSize = size.toFloat()
        fbCanvas!!.drawText(c.toString(), 0f, f.ascent.toFloat(), fbPaint)
        val cw = fbPaint.measureText(c.toString()).toInt().coerceIn(2, size + 2)
        for (yy in 0 until f.height) for (xx in 0 until cw) {
            if (Color.alpha(b.getPixel(xx, yy)) > 127) {
                set(x + xx, y + yy, lvl)
                if (smear) set(x + xx + 1, y + yy, lvl)
            }
        }
        return cw + 1 + if (smear) 1 else 0
    }

    /** Clipped at [maxW] with no ellipsis, the way the real screen cut long titles. */
    fun textClip(f: BitmapFont, x: Int, y: Int, maxW: Int, s: String, lvl: Int = INK, bold: Boolean = false) {
        val s0 = cx0; val s1 = cy0; val s2 = cx1; val s3 = cy1
        clip(maxOf(x, cx0), cy0, minOf(x + maxW, cx1), cy1)
        text(f, x, y, s, lvl, bold)
        cx0 = s0; cy0 = s1; cx1 = s2; cy1 = s3
    }

    fun textRight(f: BitmapFont, xr: Int, y: Int, s: String, lvl: Int = INK, bold: Boolean = false) =
        text(f, xr - width(f, s, bold), y, s, lvl, bold)

    fun textCenter(f: BitmapFont, xc: Int, y: Int, s: String, lvl: Int = INK, bold: Boolean = false) =
        text(f, xc - width(f, s, bold) / 2, y, s, lvl, bold)

    /** Greedy word wrap into lines no wider than [maxW]. */
    fun wrap(f: BitmapFont, s: String, maxW: Int, bold: Boolean = false): List<String> {
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (width(f, cand, bold) <= maxW || line.isEmpty()) line = cand
                else { out.add(line); line = word }
            }
            out.add(line)
        }
        return out
    }

    companion object {
        const val W = 240
        const val H = 160
        const val CLEAR = 0
        const val LIGHT = 1
        const val GRAY = 2
        const val INK = 3

        private val measurePaint = Paint().apply { isAntiAlias = false; typeface = Typeface.DEFAULT }

        private fun charAdv(f: BitmapFont, c: Char, bold: Boolean): Int {
            val g = f.glyph(c) ?: f.glyph(fold(c))
            val extra = if (bold && RiotAssets.boldOf(f) == null && !f.isBold) 1 else 0
            if (g != null) return g.adv + extra
            if (c == ' ') return f.space
            measurePaint.textSize = (f.ascent + 1).toFloat()
            return measurePaint.measureText(c.toString()).toInt().coerceIn(2, f.ascent + 3) + 1 + extra
        }

        /** Pixel width of [s] in [f0]. Safe to call from anywhere, no buffer needed. */
        fun measure(f0: BitmapFont, s: String, bold: Boolean = false): Int {
            val f = if (bold) RiotAssets.boldOf(f0) ?: f0 else f0
            var n = 0
            for (c in s) n += charAdv(f, c, bold && f === f0)
            return maxOf(0, n - 1)
        }

        fun fold(c: Char): Char = when (c) {
            '‘', '’' -> '\''
            '“', '”' -> '"'
            '–', '—' -> '-'
            else -> c
        }
    }
}
