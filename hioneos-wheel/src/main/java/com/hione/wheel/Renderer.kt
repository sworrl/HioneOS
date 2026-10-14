package com.hione.wheel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Environment
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws one era's screen at its native resolution (160 x 128 up to 320 x 240) into a bitmap
 * that the panel scales up with no filtering, so every pixel stays a pixel. Paints, paths,
 * rects and gradients are allocated once and reused; a frame only allocates the odd string.
 */
class Renderer(ctx: Context, val st: Style) {
    val w = st.w
    val h = st.h
    val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val c = Canvas(bitmap)

    /** The back of a flipped cover in Cover Flow: its track list. */
    val backSize = 140
    val back: Bitmap = Bitmap.createBitmap(backSize, backSize, Bitmap.Config.ARGB_8888)
    private val bc = Canvas(back)

    private val text = Paint().apply { isAntiAlias = st.aa; isSubpixelText = st.aa; isLinearText = false }
    private val fill = Paint().apply { isAntiAlias = st.aa; style = Paint.Style.FILL }
    private val stroke = Paint().apply { isAntiAlias = st.aa; style = Paint.Style.STROKE; strokeWidth = 1f }
    private val img = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    private val logoPaint = Paint().apply { colorFilter = PorterDuffColorFilter(st.bootLogo, PorterDuff.Mode.SRC_IN) }
    private val rf = RectF()
    private val r1 = Rect()
    private val r2 = Rect()
    private val path = Path()
    private val matrix = Matrix()
    private val camera = Camera()
    private val shaders = HashMap<Long, Shader>()

    private val logo: Bitmap? = try {
        Mascot.decodeResource(ctx.resources, st.logoRes, BitmapFactory.Options().apply { inScaled = false })
    } catch (_: Throwable) { null }

    var batteryPct = 100
    var charging = false

    // Live palette: swapped on mono screens when the backlight is off.
    private var bg = st.bg
    private var fg = st.fg
    private var dim = st.dim
    private var selFg = st.selFg
    private var selTop = st.selTop
    private var selBot = st.selBot
    private var titleTop = st.titleTop
    private var titleBot = st.titleBot
    private var titleFg = st.titleFg
    private var titleLine = st.titleLine

    private val clock12 = SimpleDateFormat("h:mm", Locale.US)
    private val ampm = SimpleDateFormat("a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEE MMM d", Locale.US)
    private val cal = Calendar.getInstance()
    private val date = Date()

    private val storage: Pair<String, String> by lazy {
        try {
            val s = StatFs(Environment.getExternalStorageDirectory().path)
            gb(s.totalBytes) to gb(s.availableBytes)
        } catch (_: Throwable) { "?" to "?" }
    }

    private fun gb(b: Long): String {
        val g = b / 1_000_000_000.0
        return if (g >= 10) "${g.toInt()} GB" else String.format(Locale.US, "%.1f GB", g)
    }

    // ---- entry ------------------------------------------------------------------------------

    fun render(skin: Skin, now: Long) {
        val lit = skin.isLit(now)
        palette(lit)
        c.drawColor(bg)
        when (val s = skin.top) {
            is BootScreen -> boot(s, now)
            is MenuScreen -> menu(skin, s, now)
            is NowPlayingScreen -> nowPlaying(skin, now)
            is CoverFlowScreen -> coverFlowBack(skin, s)
            is ClockScreen -> clock(now)
            is StopwatchScreen -> stopwatch(s, now)
            is BrickScreen -> brick(s)
            is AboutScreen -> about(skin)
            is TextScreen -> textScreen(s)
            is SlideshowScreen -> slideshow(skin, s, now)
            is MessageScreen -> message(s)
            is RadioScreen -> radio(skin, s, now)
        }
        if (now < skin.volumeShownUntil && skin.top !is NowPlayingScreen && skin.top !is BootScreen) volumeStrip(skin)
        if (!lit && !st.mono) c.drawColor(0xD0000000.toInt())
    }

    private fun palette(lit: Boolean) {
        if (st.mono && !lit) {
            bg = st.unlitBg; fg = st.unlitFg; dim = st.unlitFg; selFg = st.unlitBg
            selTop = st.unlitFg; selBot = st.unlitFg
            titleTop = st.unlitBg; titleBot = st.unlitBg; titleFg = st.unlitFg; titleLine = st.unlitFg
        } else {
            bg = st.bg; fg = st.fg; dim = st.dim; selFg = st.selFg
            selTop = st.selTop; selBot = st.selBot
            titleTop = st.titleTop; titleBot = st.titleBot; titleFg = st.titleFg; titleLine = st.titleLine
        }
    }

    // ---- text helpers ------------------------------------------------------------------------

    private fun font(face: Typeface, px: Float, color: Int) {
        text.typeface = face; text.textSize = px; text.color = color
    }

    private fun capH(px: Float) = if (st.pixelFont) px * 0.5625f else px * 0.70f

    private fun fit(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        val ell = "..."
        val room = maxW - text.measureText(ell)
        if (room <= 0) return ""
        val n = text.breakText(s, true, room, null)
        return s.substring(0, n).trimEnd() + ell
    }

    private fun draw(s: String, x: Float, y: Float) = c.drawText(s, x, y, text)

    private fun drawCentered(s: String, cx: Float, y: Float, maxW: Float = w - 8f) {
        val t = fit(s, maxW)
        c.drawText(t, cx - text.measureText(t) / 2, y, text)
    }

    private fun drawRight(s: String, right: Float, y: Float) = c.drawText(s, right - text.measureText(s), y, text)

    private fun wrap(s: String, maxW: Float, out: MutableList<String>) {
        val words = s.split(' ')
        var line = StringBuilder()
        for (word in words) {
            val trial = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(trial) <= maxW || line.isEmpty()) {
                line = StringBuilder(trial)
            } else {
                out.add(line.toString()); line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
    }

    private fun mmss(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val m = t / 60
        val s = t % 60
        return if (m >= 60) "${m / 60}:${pad(m % 60)}:${pad(s)}" else "$m:${pad(s)}"
    }

    private fun pad(v: Long) = if (v < 10) "0$v" else v.toString()

    // ---- shapes ------------------------------------------------------------------------------

    private fun gradient(top: Int, bot: Int, height: Int): Shader {
        val key = (top.toLong() shl 32) xor (bot.toLong() and 0xFFFFFFFFL) xor (height.toLong() * 0x9E3779B1L)
        return shaders.getOrPut(key) {
            LinearGradient(0f, 0f, 0f, height.toFloat(), top, bot, Shader.TileMode.CLAMP)
        }
    }

    private fun gradRect(cv: Canvas, l: Float, t: Float, r: Float, b: Float, top: Int, bot: Int, radius: Float = 0f) {
        if (top == bot) {
            fill.shader = null; fill.color = top
            if (radius > 0) { rf.set(l, t, r, b); cv.drawRoundRect(rf, radius, radius, fill) } else cv.drawRect(l, t, r, b, fill)
            return
        }
        cv.save()
        cv.translate(l, t)
        fill.shader = gradient(top, bot, (b - t).toInt().coerceAtLeast(1))
        fill.color = 0xFF000000.toInt()
        rf.set(0f, 0f, r - l, b - t)
        if (radius > 0) cv.drawRoundRect(rf, radius, radius, fill) else cv.drawRect(rf, fill)
        fill.shader = null
        cv.restore()
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.shader = null; fill.color = color; c.drawRect(l, t, r, b, fill)
    }

    private fun frame(l: Float, t: Float, r: Float, b: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = 1f
        c.drawRect(l + 0.5f, t + 0.5f, r - 0.5f, b - 0.5f, stroke)
    }

    private fun chevron(cv: Canvas, right: Float, midY: Float, color: Int) {
        val s = if (st.pixelFont) 3f else 4f
        stroke.color = color; stroke.strokeWidth = if (st.pixelFont) 2f else 1.8f
        path.reset()
        path.moveTo(right - s * 1.4f, midY - s)
        path.lineTo(right - s * 0.2f, midY)
        path.lineTo(right - s * 1.4f, midY + s)
        cv.drawPath(path, stroke)
    }

    private fun check(right: Float, midY: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = 2f
        path.reset()
        path.moveTo(right - 10f, midY)
        path.lineTo(right - 6f, midY + 4f)
        path.lineTo(right, midY - 5f)
        c.drawPath(path, stroke)
    }

    private fun playIcon(x: Float, midY: Float, size: Float, color: Int) {
        fill.shader = null; fill.color = color
        path.reset()
        path.moveTo(x, midY - size / 2)
        path.lineTo(x + size * 0.85f, midY)
        path.lineTo(x, midY + size / 2)
        path.close()
        c.drawPath(path, fill)
    }

    private fun pauseIcon(x: Float, midY: Float, size: Float, color: Int) {
        val bw = maxOf(2f, size / 3.2f)
        rect(x, midY - size / 2, x + bw, midY + size / 2, color)
        rect(x + bw * 2, midY - size / 2, x + bw * 3, midY + size / 2, color)
    }

    private fun battery(right: Float, midY: Float) {
        val bw = when (st.scale) { 4 -> 13f; 3 -> 16f; else -> 21f }
        val bh = when (st.scale) { 4 -> 7f; 3 -> 8f; else -> 10f }
        val l = right - bw - 2
        val t = (midY - bh / 2).toInt().toFloat()
        frame(l, t, l + bw, t + bh, if (st.mono) titleFg else 0xFF4F555C.toInt())
        rect(l + bw, t + bh / 3, l + bw + 2, t + bh * 2 / 3, if (st.mono) titleFg else 0xFF4F555C.toInt())
        val inner = (bw - 4) * (batteryPct.coerceIn(0, 100) / 100f)
        if (inner >= 1f) {
            if (st.mono) rect(l + 2, t + 2, l + 2 + inner, t + bh - 2, titleFg)
            else {
                val low = batteryPct <= 20
                gradRect(c, l + 2, t + 2, l + 2 + inner, t + bh - 2,
                    if (low) 0xFFFF8A80.toInt() else 0xFF9BEA7A.toInt(),
                    if (low) 0xFFD32F2F.toInt() else 0xFF2E9A1F.toInt())
            }
        }
        if (charging) {
            // A small bolt over the cell.
            fill.shader = null; fill.color = if (st.mono) bg else 0xFFFFFFFF.toInt()
            val cx = l + bw / 2
            path.reset()
            path.moveTo(cx + 1, t + 1); path.lineTo(cx - 2, t + bh / 2 + 0.5f); path.lineTo(cx, t + bh / 2 + 0.5f)
            path.lineTo(cx - 1, t + bh - 1); path.lineTo(cx + 2, t + bh / 2 - 0.5f); path.lineTo(cx, t + bh / 2 - 0.5f)
            path.close()
            c.drawPath(path, fill)
        }
    }

    private fun titleBar(skin: Skin, title: String) {
        val th = st.titleH.toFloat()
        gradRect(c, 0f, 0f, w.toFloat(), th, titleTop, titleBot)
        rect(0f, th - 1, w.toFloat(), th, titleLine)
        val mid = th / 2
        val iconColor = if (st.mono) titleFg else 0xFF2F6FD6.toInt()
        val isz = if (st.pixelFont) 7f else 9f
        // 2007 put the title on the left and the play state next to the battery.
        val ix = if (st.titleLeft) w - 40f else 4f
        if (skin.link.isPlaying) playIcon(ix, mid, isz, iconColor)
        else if (skin.link.hasItem) pauseIcon(ix, mid, isz - 1, iconColor)
        battery(w - 3f, mid)
        font(st.titleFace, st.titlePx, titleFg)
        if (st.titleLeft) draw(fit(title, w - 60f), 7f, mid + capH(st.titlePx) / 2)
        else drawCentered(title, w / 2f, mid + capH(st.titlePx) / 2, w - 52f)
    }

    // ---- screens -----------------------------------------------------------------------------

    private fun boot(s: BootScreen, now: Long) {
        c.drawColor(st.bootBg)
        val lg = logo ?: return
        val x = (w - lg.width) / 2f
        val y = (h - lg.height) / 2f - h / 14f
        c.drawBitmap(lg, x, y.toInt().toFloat(), logoPaint)
        val p = ((now - s.startedAt).toFloat() / Skin.BOOT_MS).coerceIn(0f, 1f)
        val bw = w * 0.38f
        val bx = (w - bw) / 2
        val by = (y + lg.height + h / 10f).toInt().toFloat()
        val bh = if (st.pixelFont) 4f else 5f
        stroke.color = st.bootLogo; stroke.strokeWidth = 1f
        c.drawRect(bx + 0.5f, by + 0.5f, bx + bw - 0.5f, by + bh - 0.5f, stroke)
        fill.shader = null; fill.color = st.bootLogo
        c.drawRect(bx + 1, by + 1, bx + 1 + (bw - 2) * p, by + bh - 1, fill)
    }

    private fun menu(skin: Skin, m: MenuScreen, now: Long) {
        titleBar(skin, m.title)
        val split = m.split
        val listW = if (split) w / 2f else w.toFloat()
        list(m, 0f, listW)
        if (split) preview(skin, m, listW, now)
    }

    private fun list(m: MenuScreen, x0: Float, width: Float) {
        val rows = m.rows
        val n = rows.size
        val vis = st.visibleRows
        val rh = st.rowH.toFloat()
        val th = st.titleH.toFloat()
        val sb = n > vis
        val sbW = if (!sb) 0f else if (st.pixelFont) 6f else 7f
        val right = x0 + width - sbW
        val pad = if (st.pixelFont) 4f else 7f
        val base = ((rh + capH(st.listPx)) / 2).toInt().toFloat()
        for (i in 0 until min(vis, n - m.top)) {
            val idx = m.top + i
            val y = th + i * rh
            val selected = idx == m.sel
            if (selected) {
                gradRect(c, x0, y, right, y + rh, selTop, selBot)
                if (!st.mono) rect(x0, y, right, y + 1, 0x40FFFFFF)
            }
            val color = if (selected) selFg else fg
            var avail = right - x0 - pad * 2
            if (rows.arrow(idx)) {
                chevron(c, right - pad + 1, y + rh / 2, color)
                avail -= 10f
            }
            if (rows.checked(idx)) { check(right - pad, y + rh / 2, color); avail -= 14f }
            val v = rows.value(idx)
            font(st.listFace, st.listPx, color)
            if (v != null) {
                font(st.bodyFace, st.bodyPx, if (selected) selFg else dim)
                val vw = text.measureText(v)
                draw(v, right - pad - vw, y + base)
                avail -= vw + 8
                font(st.listFace, st.listPx, color)
            }
            draw(fit(rows.label(idx), avail), x0 + pad, y + base)
        }
        if (sb) {
            val l = x0 + width - sbW
            val trackT = th
            val trackB = h.toFloat()
            if (st.mono) {
                rect(l, trackT, l + sbW, trackB, bg)
                frame(l, trackT, l + sbW, trackB, fg)
            } else {
                rect(l, trackT, l + sbW, trackB, 0xFFEDEFF2.toInt())
                rect(l, trackT, l + 1, trackB, 0xFFC4C9CF.toInt())
            }
            val span = trackB - trackT - 2
            val thumbH = (span * vis / n).coerceAtLeast(6f)
            val thumbT = trackT + 1 + (span - thumbH) * (m.top.toFloat() / (n - vis).coerceAtLeast(1))
            if (st.mono) rect(l + 1, thumbT, l + sbW - 1, thumbT + thumbH, fg)
            else gradRect(c, l + 1.5f, thumbT, l + sbW - 1, thumbT + thumbH, 0xFF9DA4AC.toInt(), 0xFF6A727B.toInt(), 2.5f)
        }
    }

    /** The 2007 split screen: the right half pans slowly across album art. */
    private fun preview(skin: Skin, m: MenuScreen, x0: Float, now: Long) {
        val l = x0
        val t = st.titleH.toFloat()
        val r = w.toFloat()
        val b = h.toFloat()
        rect(l, t, r, b, 0xFF101114.toInt())
        val albums = skin.lib.albums
        val period = 7000L
        val k = now / period
        val p = (now % period).toFloat() / period
        val pinned = m.rows.albumId(m.sel)
        val albumId = when {
            pinned > 0 -> pinned
            albums.isEmpty() -> -1L
            else -> albums[((k * 7919L + 13) % albums.size).toInt()].id
        }
        val art = skin.art.get(albumId)
        if (art != null) {
            kenBurns(art.bitmap, l, t, r, b, p, (k % 2L) == 0L)
        } else noArt(l, t, r, b)
        // Soft shadow where the list meets the picture.
        gradRectH(l, t, l + 6, b, 0x55000000, 0x00000000)
    }

    private fun gradRectH(l: Float, t: Float, r: Float, b: Float, left: Int, right: Int) {
        val key = (left.toLong() shl 32) xor (right.toLong() and 0xFFFFFFFFL) xor 0x5A5AL
        val sh = shaders.getOrPut(key) { LinearGradient(0f, 0f, r - l, 0f, left, right, Shader.TileMode.CLAMP) }
        c.save(); c.translate(l, t)
        fill.shader = sh; c.drawRect(0f, 0f, r - l, b - t, fill); fill.shader = null
        c.restore()
    }

    private fun kenBurns(src: Bitmap, l: Float, t: Float, r: Float, b: Float, p: Float, zoomIn: Boolean) {
        val dw = r - l
        val dh = b - t
        val z = if (zoomIn) 1f + 0.22f * p else 1.22f - 0.22f * p
        // Crop the square art to the box's aspect, then zoom and drift.
        val aspect = dw / dh
        var sw = src.width / z
        var sh = sw / aspect
        if (sh > src.height / z) { sh = src.height / z; sw = sh * aspect }
        val maxX = src.width - sw
        val maxY = src.height - sh
        val px = if (zoomIn) 0.3f + 0.4f * p else 0.7f - 0.4f * p
        val sx = maxX * px
        val sy = maxY * (0.5f + 0.2f * (p - 0.5f))
        r1.set(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
        rf.set(l, t, r, b)
        c.drawBitmap(src, r1, rf, img)
    }

    private fun noArt(l: Float, t: Float, r: Float, b: Float) {
        gradRect(c, l, t, r, b, 0xFFB9C0C8.toInt(), 0xFF6D757E.toInt())
        // A simple eighth note.
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val s = min(r - l, b - t) / 5
        fill.shader = null; fill.color = 0xE6FFFFFF.toInt()
        rf.set(cx - s * 0.9f, cy + s * 0.2f, cx + s * 0.1f, cy + s * 0.95f)
        c.drawOval(rf, fill)
        c.drawRect(cx - s * 0.05f, cy - s * 1.3f, cx + s * 0.12f, cy + s * 0.55f, fill)
        c.drawRect(cx - s * 0.05f, cy - s * 1.3f, cx + s * 0.75f, cy - s * 1.05f, fill)
    }

    private fun drawArt(skin: Skin, albumId: Long, l: Float, t: Float, size: Float) {
        val art = skin.art.get(albumId)
        if (art == null) { noArt(l, t, l + size, t + size); return }
        r1.set(0, 0, art.bitmap.width, art.bitmap.height)
        rf.set(l, t, l + size, t + size)
        c.drawBitmap(art.bitmap, r1, rf, img)
    }

    private fun nowPlaying(skin: Skin, now: Long) {
        titleBar(skin, "Now Playing")
        val link = skin.link
        if (!link.hasItem) {
            font(st.bodyFace, st.bodyPx, dim)
            drawCentered(if (link.connected) "Nothing playing" else "Waiting for ${Mascot.musicAppName}", w / 2f, h / 2f + 4)
            return
        }
        val albumId = skin.lib.song(link.mediaId)?.albumId ?: -1L
        val counter = "${link.index} of ${link.count}"
        val showArt = !st.mono && st.era != Era.MONO_2001
        when {
            !showArt -> {
                // 2001 (and the gray 2004 screen): everything centered, one line each.
                font(st.smallFace, st.smallPx, fg)
                draw(counter, 4f, st.titleH + 11f)
                font(st.listFace, st.listPx, fg)
                drawCentered(link.title, w / 2f, st.titleH + 30f)
                font(st.bodyFace, st.bodyPx, fg)
                drawCentered(link.artist, w / 2f, st.titleH + 45f)
                drawCentered(link.album, w / 2f, st.titleH + 60f)
                bottomBar(skin, now, 8f, h - 30f, w - 8f)
            }
            st.era == Era.COLOR_2004 -> {
                font(st.smallFace, st.smallPx, dim)
                draw(counter, 6f, st.titleH + 12f)
                val size = 72f
                val at = st.titleH + 18f
                drawArt(skin, albumId, 8f, at, size)
                frame(8f, at, 8f + size, at + size, 0xFF9AA1A9.toInt())
                val tx = 8f + size + 8f
                val tw = w - tx - 4f
                font(st.listFace, st.listPx, fg)
                draw(fit(link.title, tw), tx, at + 16f)
                font(st.bodyFace, st.bodyPx, fg)
                draw(fit(link.artist, tw), tx, at + 36f)
                font(st.bodyFace, st.bodyPx, dim)
                draw(fit(link.album, tw), tx, at + 56f)
                bottomBar(skin, now, 10f, h - 34f, w - 10f)
            }
            else -> {
                val size = if (st.era == Era.CLASSIC_2007) 118f else 128f
                val at = st.titleH + 14f
                val ax = 12f
                if (st.era == Era.CLASSIC_2007) tiltedArt(skin, albumId, ax, at, size)
                else {
                    drawArt(skin, albumId, ax, at, size)
                    frame(ax, at, ax + size, at + size, 0xFFB3B9C0.toInt())
                }
                val tx = ax + size + 14f
                val tw = w - tx - 6f
                font(st.smallFace, st.smallPx, dim)
                draw(counter, tx, at + 14f)
                font(st.listFace, st.listPx, fg)
                draw(fit(link.title, tw), tx, at + 42f)
                font(st.bodyFace, st.bodyPx, fg)
                draw(fit(link.artist, tw), tx, at + 64f)
                font(st.bodyFace, st.bodyPx, dim)
                draw(fit(link.album, tw), tx, at + 86f)
                bottomBar(skin, now, 14f, h - 44f, w - 14f)
            }
        }
    }

    /** 2007: the cover turned a little in 3D, with a reflection fading into the white. */
    private fun tiltedArt(skin: Skin, albumId: Long, l: Float, t: Float, size: Float) {
        val art = skin.art.get(albumId)
        c.save()
        camera.save()
        camera.rotateY(-18f)
        camera.getMatrix(matrix)
        camera.restore()
        val cx = l + size / 2
        val cy = t + size / 2
        matrix.preTranslate(-cx, -cy)
        matrix.postTranslate(cx, cy)
        c.concat(matrix)
        if (art != null) {
            r1.set(0, 0, art.bitmap.width, art.bitmap.height)
            rf.set(l, t, l + size, t + size)
            c.drawBitmap(art.bitmap, r1, rf, img)
            // Reflection: the art flipped under itself, washed out toward the background.
            val refl = size * 0.32f
            c.save()
            c.translate(0f, 2 * (t + size) + 2)
            c.scale(1f, -1f)
            c.drawBitmap(art.bitmap, r1, rf, img)
            c.restore()
            gradRect(c, l, t + size + 1, l + size, t + size + 2 + refl, 0x99FFFFFF.toInt(), bg)
            rect(l, t + size + 2 + refl, l + size, t + size * 2 + 4, bg)
        } else {
            noArt(l, t, l + size, t + size)
        }
        c.restore()
    }

    /** Progress, or the volume bar, or the scrubber, with times under it. */
    private fun bottomBar(skin: Skin, now: Long, l: Float, t: Float, r: Float) {
        val link = skin.link
        val bh = if (st.pixelFont) 6f else 9f
        val dur = link.durationMs
        when (skin.npMode) {
            NpMode.VOLUME -> {
                val v = link.volume.toFloat() / link.maxVolume.coerceAtLeast(1)
                speaker(l, t + bh / 2, false)
                speaker(r - 10f, t + bh / 2, true)
                bar(l + 14f, t, r - 16f, t + bh, v, false)
                return
            }
            NpMode.SCRUB -> bar(l, t, r, t + bh, if (dur > 0) skin.scrubMs.toFloat() / dur else 0f, true)
            NpMode.NORMAL -> bar(l, t, r, t + bh, if (dur > 0) link.positionMs.toFloat() / dur else 0f, false)
        }
        val pos = if (skin.npMode == NpMode.SCRUB) skin.scrubMs else link.positionMs
        font(st.smallFace, if (st.pixelFont) 8f else st.smallPx, fg)
        val ty = t + bh + if (st.pixelFont) 11f else 15f
        draw(mmss(pos), l, ty)
        if (dur > 0) drawRight("-" + mmss(dur - pos), r, ty)
    }

    /**
     * Volume keys away from Now Playing: the same speaker-bar-speaker row the Now Playing screen
     * shows when the wheel turns the volume, on a plain strip at the bottom of the panel.
     */
    private fun volumeStrip(skin: Skin) {
        val link = skin.link
        val bh = if (st.pixelFont) 6f else 9f
        val pad = if (st.pixelFont) 5f else 8f
        val boxH = bh + pad * 2
        val l = 4f
        val r = w - 4f
        val b = h - 4f
        val t = b - boxH
        rect(l, t, r, b, bg)
        frame(l, t, r, b, if (st.mono) fg else 0xFFB3B9C0.toInt())
        val v = link.volume.toFloat() / link.maxVolume.coerceAtLeast(1)
        speaker(l + pad, t + pad + bh / 2, false)
        speaker(r - pad - 10f, t + pad + bh / 2, true)
        bar(l + pad + 14f, t + pad, r - pad - 16f, t + pad + bh, v, false)
    }

    private fun bar(l: Float, t: Float, r: Float, b: Float, frac: Float, knob: Boolean) {
        val f = frac.coerceIn(0f, 1f)
        if (st.mono) {
            frame(l, t, r, b, fg)
            if (!knob) rect(l + 1, t + 1, l + 1 + (r - l - 2) * f, b - 1, fg)
            else {
                rect(l + 1, (t + b) / 2 - 0.5f, r - 1, (t + b) / 2 + 0.5f, fg)
                diamond(l + (r - l) * f, (t + b) / 2, (b - t) / 2 + 2, fg)
            }
            return
        }
        val rad = (b - t) / 2
        gradRect(c, l, t, r, b, 0xFFD2D7DC.toInt(), st.barTrack, rad)
        if (!knob) {
            val fw = (r - l) * f
            if (fw > 2) {
                gradRect(c, l, t, l + fw, b, st.barTop, st.barBot, rad)
                rect(l + rad, t + 1, l + fw - rad, t + (b - t) * 0.4f, 0x55FFFFFF)
            }
        } else diamond(l + (r - l) * f, (t + b) / 2, rad + 3, 0xFF2D3238.toInt())
        stroke.color = st.barBorder; stroke.strokeWidth = 1f
        rf.set(l + 0.5f, t + 0.5f, r - 0.5f, b - 0.5f)
        c.drawRoundRect(rf, rad, rad, stroke)
    }

    private fun diamond(x: Float, y: Float, s: Float, color: Int) {
        fill.shader = null; fill.color = color
        path.reset()
        path.moveTo(x, y - s); path.lineTo(x + s, y); path.lineTo(x, y + s); path.lineTo(x - s, y); path.close()
        c.drawPath(path, fill)
    }

    private fun speaker(x: Float, midY: Float, loud: Boolean) {
        fill.shader = null; fill.color = fg
        val s = if (st.pixelFont) 3f else 4f
        c.drawRect(x, midY - s / 2, x + s, midY + s / 2, fill)
        path.reset()
        path.moveTo(x + s, midY - s / 2); path.lineTo(x + s * 2, midY - s * 1.3f)
        path.lineTo(x + s * 2, midY + s * 1.3f); path.lineTo(x + s, midY + s / 2); path.close()
        c.drawPath(path, fill)
        if (loud) {
            stroke.color = fg; stroke.strokeWidth = 1f
            c.drawLine(x + s * 2.6f, midY - s, x + s * 2.6f, midY + s, stroke)
        }
    }

    private fun clock(now: Long) {
        // Title bar without the play icon looks closest to the Clock screens.
        val th = st.titleH.toFloat()
        gradRect(c, 0f, 0f, w.toFloat(), th, titleTop, titleBot)
        rect(0f, th - 1, w.toFloat(), th, titleLine)
        font(st.titleFace, st.titlePx, titleFg)
        drawCentered("Clock", w / 2f, th / 2 + capH(st.titlePx) / 2)
        date.time = now
        if (st.pixelFont) {
            val big = 32f
            font(st.listFace, big, fg)
            val t = clock12.format(date)
            val tw = text.measureText(t)
            font(st.bodyFace, 16f, fg)
            val aw = text.measureText(ampm.format(date))
            val x = (w - tw - aw - 4) / 2
            font(st.listFace, big, fg)
            draw(t, x, h / 2f + 12)
            font(st.bodyFace, 16f, fg)
            draw(ampm.format(date), x + tw + 4, h / 2f + 12)
            drawCentered(dateFmt.format(date), w / 2f, h / 2f + 34)
            return
        }
        // Analog face for the later screens.
        cal.timeInMillis = now
        val cx = w / 2f
        val cy = th + (h - th) / 2f - 10
        val rad = (h - th) / 2f - 22
        gradRect(c, cx - rad, cy - rad, cx + rad, cy + rad, 0xFFFFFFFF.toInt(), 0xFFE3E7EB.toInt(), rad)
        stroke.color = 0xFF9AA2AB.toInt(); stroke.strokeWidth = 2f
        c.drawCircle(cx, cy, rad, stroke)
        for (i in 0 until 12) {
            val a = Math.PI * 2 * i / 12
            val l1 = if (i % 3 == 0) rad - 10 else rad - 6
            stroke.color = 0xFF30343A.toInt(); stroke.strokeWidth = if (i % 3 == 0) 2.5f else 1.2f
            c.drawLine(cx + (sin(a) * l1).toFloat(), cy - (cos(a) * l1).toFloat(),
                cx + (sin(a) * (rad - 3)).toFloat(), cy - (cos(a) * (rad - 3)).toFloat(), stroke)
        }
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = cal.get(Calendar.HOUR) + min / 60f
        hand(cx, cy, hr / 12f, rad * 0.5f, 4f, 0xFF1D2025.toInt())
        hand(cx, cy, min / 60f, rad * 0.78f, 3f, 0xFF1D2025.toInt())
        hand(cx, cy, sec / 60f, rad * 0.86f, 1.2f, 0xFFD8322B.toInt())
        fill.shader = null; fill.color = 0xFFD8322B.toInt()
        c.drawCircle(cx, cy, 3f, fill)
        font(st.listFace, st.listPx, fg)
        drawCentered(clock12.format(date) + " " + ampm.format(date) + "   " + dateFmt.format(date), cx, h - 8f)
    }

    private fun hand(cx: Float, cy: Float, turn: Float, len: Float, width: Float, color: Int) {
        val a = Math.PI * 2 * turn
        stroke.color = color; stroke.strokeWidth = width; stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, cy, cx + (sin(a) * len).toFloat(), cy - (cos(a) * len).toFloat(), stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun stopwatch(s: StopwatchScreen, now: Long) {
        val th = st.titleH.toFloat()
        gradRect(c, 0f, 0f, w.toFloat(), th, titleTop, titleBot)
        rect(0f, th - 1, w.toFloat(), th, titleLine)
        font(st.titleFace, st.titlePx, titleFg)
        drawCentered("Stopwatch", w / 2f, th / 2 + capH(st.titlePx) / 2)
        val e = s.elapsed(now)
        val t = "${pad(e / 60000)}:${pad((e / 1000) % 60)}.${(e / 100) % 10}"
        font(st.listFace, st.listPx * 2.4f, fg)
        drawCentered(t, w / 2f, h / 2f + 14)
        font(st.bodyFace, st.bodyPx, dim)
        drawCentered(if (s.running) "Press center to stop" else "Center: start   Left: reset", w / 2f, h - 14f)
    }

    private val brickColors = intArrayOf(
        0xFFE8473F.toInt(), 0xFFF39A2B.toInt(), 0xFFF2D23B.toInt(), 0xFF4DBE4A.toInt(), 0xFF3E8DE8.toInt()
    )

    private fun brick(s: BrickScreen) {
        val g = s.game
        val th = st.titleH.toFloat()
        gradRect(c, 0f, 0f, w.toFloat(), th, titleTop, titleBot)
        rect(0f, th - 1, w.toFloat(), th, titleLine)
        font(st.titleFace, st.titlePx, titleFg)
        draw("Score ${g.score}", 4f, th / 2 + capH(st.titlePx) / 2)
        drawRight("Ball ${g.lives}", w - 4f, th / 2 + capH(st.titlePx) / 2)
        val bw = g.brickW()
        for (row in 0 until g.rows) for (col in 0 until g.cols) {
            if (!g.bricks[row * g.cols + col]) continue
            val l = col * bw + 1
            val t = g.brickTop + row * g.brickH + 1f
            val color = if (st.mono) fg else brickColors[row % brickColors.size]
            if (st.mono && row % 2 == 1) frame(l, t, l + bw - 2, t + g.brickH - 2, fg)
            else rect(l, t, l + bw - 2, t + g.brickH - 2, color)
        }
        val px = g.paddleX - g.paddleW / 2
        if (st.mono) rect(px, g.paddleY, px + g.paddleW, g.paddleY + g.paddleH, fg)
        else gradRect(c, px, g.paddleY, px + g.paddleW, g.paddleY + g.paddleH, 0xFF9DA4AC.toInt(), 0xFF3B4148.toInt(), g.paddleH / 2)
        val r = g.ball / 2
        if (!g.over) {
            if (st.mono || st.pixelFont) rect(g.bx - r, g.by - r, g.bx + r, g.by + r, fg)
            else { fill.shader = null; fill.color = fg; c.drawCircle(g.bx, g.by, r, fill) }
        }
        font(st.listFace, st.listPx, fg)
        when {
            g.over -> {
                drawCentered("Game Over", w / 2f, h / 2f)
                font(st.bodyFace, st.bodyPx, fg)
                drawCentered("Press center to play", w / 2f, h / 2f + st.rowH)
            }
            g.paused -> drawCentered("Paused", w / 2f, h / 2f + 10)
            !g.served -> {
                font(st.bodyFace, st.bodyPx, fg)
                drawCentered("Press center", w / 2f, h / 2f + 10)
            }
        }
    }

    private fun about(skin: Skin) {
        val th = st.titleH.toFloat()
        gradRect(c, 0f, 0f, w.toFloat(), th, titleTop, titleBot)
        rect(0f, th - 1, w.toFloat(), th, titleLine)
        font(st.titleFace, st.titlePx, titleFg)
        drawCentered("About", w / 2f, th / 2 + capH(st.titlePx) / 2)
        val lh = if (st.pixelFont) 13f else 19f
        var y = th + lh + 2
        font(st.listFace, st.listPx, fg)
        drawCentered(Brand.NAME, w / 2f, y)
        y += lh + 2
        val pairs = arrayOf(
            "Songs" to "%,d".format(Locale.US, skin.lib.songs.size),
            "Capacity" to storage.first,
            "Available" to storage.second,
            "Version" to "${skin.era.id} (0.1.0)",
        )
        font(if (st.pixelFont) st.smallFace else st.bodyFace, if (st.pixelFont) 8f else st.bodyPx, fg)
        val step = if (st.pixelFont) 10f else lh
        for ((k, v) in pairs) {
            draw(k, 6f, y)
            drawRight(v, w - 6f, y)
            y += step
        }
        font(st.smallFace, st.smallPx, dim)
        val lines = ArrayList<String>(4)
        wrap(Brand.TRIBUTE, w - 10f, lines)
        var ty = h - 4f - (lines.size - 1) * (st.smallPx + 2)
        for (l in lines) { drawCentered(l, w / 2f, ty); ty += st.smallPx + 2 }
    }

    private var wrappedFor: TextScreen? = null
    private val wrapped = ArrayList<String>()

    private fun textScreen(s: TextScreen) {
        val th = st.titleH.toFloat()
        gradRect(c, 0f, 0f, w.toFloat(), th, titleTop, titleBot)
        rect(0f, th - 1, w.toFloat(), th, titleLine)
        font(st.titleFace, st.titlePx, titleFg)
        drawCentered(s.title, w / 2f, th / 2 + capH(st.titlePx) / 2)
        font(st.bodyFace, st.bodyPx, fg)
        if (wrappedFor !== s) {
            wrapped.clear()
            for ((i, p) in s.paragraphs.withIndex()) {
                if (i > 0) wrapped.add("")
                wrap(p, w - 14f, wrapped)
            }
            wrappedFor = s
            s.lineCount = wrapped.size
        }
        val lh = st.lineH.toFloat()
        val n = st.textLines
        for (i in 0 until n) {
            val line = wrapped.getOrNull(s.top + i) ?: break
            draw(line, 5f, th + 2 + lh * (i + 1) - (lh - capH(st.bodyPx)) / 2)
        }
        if (wrapped.size > n) {
            val l = w - 4f
            rect(l, th, w.toFloat(), h.toFloat(), if (st.mono) bg else 0xFFEDEFF2.toInt())
            val span = h - th
            val thumbH = span * n / wrapped.size
            val thumbT = th + (span - thumbH) * s.top / (wrapped.size - n).coerceAtLeast(1)
            rect(l + 1, thumbT, w - 1f, thumbT + thumbH, if (st.mono) fg else 0xFF8A929B.toInt())
        }
    }

    /**
     * The tuner. 2005 and 2007 follow the Radio Remote screen: the frequency large, the station
     * under it, and below that either the radio dial (wheel tunes, favorites marked with small
     * triangles) or the RDS text, switched with Center. 2001 and 2004 never had a radio; theirs
     * is the same screen drawn in their own type and bars.
     */
    private fun radio(skin: Skin, s: RadioScreen, now: Long) {
        val r = skin.radio
        titleBar(skin, "Radio")
        val th = st.titleH.toFloat()
        val px = st.pixelFont
        if (!r.installed || !r.antenna) {
            font(st.bodyFace, st.bodyPx, fg)
            val msg = if (!r.installed) "There is no radio on this player."
                else "Connect headphones. The cord is the radio antenna."
            val lines = ArrayList<String>(4)
            wrap(msg, w - 16f, lines)
            val lh = st.lineH.toFloat()
            var y = th + (h - th - lines.size * lh) / 2 + lh - (lh - capH(st.bodyPx)) / 2
            for (l in lines) { drawCentered(l, w / 2f, y); y += lh }
            return
        }
        val k = r.dialKhz
        val fav = r.isFavorite(k)
        // Frequency, large, with "FM" after it.
        val bigPx = if (px) 32f else if (st.era == Era.VIDEO_2005) 40f else 38f
        val fy = th + (h - th) * 0.30f + capH(bigPx) / 2
        font(st.listFace, bigPx, fg)
        val f = fmtMhz(k)
        val fw = text.measureText(f)
        font(st.smallFace, if (px) 16f else st.bodyPx, dim)
        val unit = "FM"
        val uw = text.measureText(unit)
        val x0 = (w - fw - uw - 4f) / 2
        font(st.listFace, bigPx, fg)
        draw(f, x0, fy)
        font(st.smallFace, if (px) 16f else st.bodyPx, dim)
        draw(unit, x0 + fw + 4f, fy)
        // Favorite marker left of the number, signal icon right of the panel.
        val accent = if (st.mono) fg else st.barBot
        if (fav) favTri(x0 - (if (px) 9f else 12f), fy - capH(bigPx) / 2, if (px) 4f else 5f, accent)
        if (r.online) signal(w - (if (px) 18f else 24f), th + (if (px) 6f else 8f), if (px) 3f else 4f, fg)
        // Station line.
        font(st.bodyFace, st.bodyPx, fg)
        val stationLine = when {
            !r.connected -> "Waiting for the radio"
            r.error != null -> r.error ?: ""
            !r.on -> "Radio off. Press play."
            !r.online -> "Starting the radio"
            else -> r.station
        }
        val sy = fy + (if (px) 18f else 24f)
        drawCentered(stationLine, w / 2f, sy)
        val lowerT = sy + (if (px) 8f else 12f)
        if (s.dial) dial(r, k, lowerT, h - (if (px) 4f else 8f))
        else {
            // RDS: the station's own text, up to two lines.
            font(st.bodyFace, st.bodyPx, dim)
            val rds = r.text.takeIf { r.online && it.isNotBlank() && it != r.station } ?: "No station info"
            val lines = ArrayList<String>(4)
            wrap(rds, w - 16f, lines)
            var y = lowerT + st.lineH
            for (l in lines.take(2)) { drawCentered(l, w / 2f, y); y += st.lineH }
        }
        if (now < s.noteUntil) {
            font(st.bodyFace, st.bodyPx, fg)
            val nw = text.measureText(s.note) + 12f
            val nh = st.lineH + 6f
            val nt = (h + th) / 2 - nh / 2
            rect((w - nw) / 2, nt, (w + nw) / 2, nt + nh, bg)
            frame((w - nw) / 2, nt, (w + nw) / 2, nt + nh, fg)
            drawCentered(s.note, w / 2f, nt + nh / 2 + capH(st.bodyPx) / 2)
        }
    }

    /** The radio dial: a scale around the tuned frequency, a needle, favorites as triangles. */
    private fun dial(r: RadioLink, k: Int, top: Float, bottom: Float) {
        val px = st.pixelFont
        val reg = r.region
        val l = 8f
        val rr = w - 8f
        val span = if (w <= 160) 6_000 else if (w <= 220) 8_000 else 10_000
        val lo = (k - span / 2).coerceIn(reg.lowKhz - 500, reg.highKhz + 500 - span)
        val hi = lo + span
        fun xOf(f: Int) = l + (rr - l) * (f - lo).toFloat() / span
        val base = top + (bottom - top) * 0.45f
        val tickC = if (st.mono) fg else 0xFF5C636B.toInt()
        // Base line, then ticks: every 0.2 MHz short, every 1 MHz taller, labels every 2 MHz.
        rect(l, base, rr, base + 1, tickC)
        var f = (lo / 200) * 200
        font(st.smallFace, if (px) 8f else st.smallPx, dim)
        while (f <= hi) {
            if (f >= lo) {
                val x = xOf(f)
                val major = f % 1000 == 0
                val tall = if (major) (if (px) 6f else 9f) else (if (px) 3f else 4f)
                rect(x, base - tall, x + 1, base, tickC)
                if (f % 2000 == 0) {
                    val lab = (f / 1000).toString()
                    draw(lab, x - text.measureText(lab) / 2, base + (if (px) 10f else 15f))
                }
            }
            f += 200
        }
        // Favorites under the scale.
        val accent = if (st.mono) fg else st.barBot
        for (fv in r.favorites) if (fv in lo..hi) favTri(xOf(fv), base + 2f, if (px) 2.5f else 3.5f, accent, up = true)
        // Needle.
        val needle = if (st.mono) fg else 0xFFE0392B.toInt()
        val x = xOf(k)
        val nt = top + 1f
        rect(x - (if (px) 0f else 0.5f), nt, x + 1f + (if (px) 0f else 0.5f), base + 1f, needle)
    }

    private fun favTri(x: Float, y: Float, s: Float, color: Int, up: Boolean = false) {
        fill.shader = null; fill.color = color
        path.reset()
        if (up) { path.moveTo(x, y); path.lineTo(x + s, y + s * 1.6f); path.lineTo(x - s, y + s * 1.6f) }
        else { path.moveTo(x - s, y - s); path.lineTo(x + s, y - s); path.lineTo(x, y + s * 0.8f) }
        path.close()
        c.drawPath(path, fill)
    }

    /** Signal icon: four rising bars, shown while the tuner is up. */
    private fun signal(x: Float, y: Float, u: Float, color: Int) {
        for (i in 0 until 4) {
            val bh = u * (i + 1)
            rect(x + i * (u + 1), y + u * 4 - bh, x + i * (u + 1) + u, y + u * 4, color)
        }
    }

    private fun message(s: MessageScreen) {
        val th = st.titleH.toFloat()
        gradRect(c, 0f, 0f, w.toFloat(), th, titleTop, titleBot)
        rect(0f, th - 1, w.toFloat(), th, titleLine)
        font(st.titleFace, st.titlePx, titleFg)
        drawCentered(s.title, w / 2f, th / 2 + capH(st.titlePx) / 2)
        font(st.bodyFace, st.bodyPx, fg)
        val lines = ArrayList<String>(6)
        wrap(s.text, w - 16f, lines)
        val lh = st.lineH.toFloat()
        var y = th + (h - th - lines.size * lh) / 2 + lh - (lh - capH(st.bodyPx)) / 2
        for (l in lines) { drawCentered(l, w / 2f, y); y += lh }
    }

    private fun slideshow(skin: Skin, s: SlideshowScreen, now: Long) {
        c.drawColor(0xFF000000.toInt())
        if (s.albumIds.isEmpty()) return
        val period = 5000L
        val t = now - s.startedAt
        val k = (t / period).toInt()
        val p = (t % period).toFloat() / period
        val id = s.albumIds[k % s.albumIds.size]
        val art = skin.art.get(id)
        // Warm the next one so the cut is clean.
        skin.art.get(s.albumIds[(k + 1) % s.albumIds.size])
        if (art != null) kenBurns(art.bitmap, 0f, 0f, w.toFloat(), h.toFloat(), p, k % 2 == 0)
        val fadeIn = (t % period).coerceAtMost(500L) / 500f
        if (fadeIn < 1f) c.drawColor(((1f - fadeIn) * 255).toInt() shl 24)
    }

    /** Cover Flow: the panel draws the black stage and the captions; the covers are Compose. */
    private fun coverFlowBack(skin: Skin, s: CoverFlowScreen) {
        c.drawColor(0xFF000000.toInt())
        val a = s.albums.getOrNull(s.sel) ?: run {
            font(st.bodyFace, st.bodyPx, 0xFFFFFFFF.toInt())
            drawCentered("No albums", w / 2f, h / 2f)
            return
        }
        if (!s.flipped) {
            font(st.listFace, st.listPx - 1, 0xFFFFFFFF.toInt())
            drawCentered(a.title, w / 2f, h - 26f)
            font(st.bodyFace, st.bodyPx - 1, 0xFFB5BAC0.toInt())
            drawCentered(a.artist, w / 2f, h - 9f)
        }
        renderCoverBack(s)
    }

    private fun renderCoverBack(s: CoverFlowScreen) {
        val a = s.albums.getOrNull(s.sel) ?: return
        val sz = backSize.toFloat()
        bc.drawColor(0xFFFFFFFF.toInt())
        val hh = 24f
        gradRect(bc, 0f, 0f, sz, hh, 0xFF5D636B.toInt(), 0xFF24282D.toInt())
        font(st.listFace, 11.5f, 0xFFFFFFFF.toInt())
        bcDraw(fit(a.title, sz - 8), 4f, 11f)
        font(st.bodyFace, 10f, 0xFFC9CED4.toInt())
        bcDraw(fit(a.artist, sz - 8), 4f, 21f)
        val rh = 19f
        val vis = 6
        for (i in 0 until vis) {
            val idx = s.trackTop + i
            val song = a.songs.getOrNull(idx) ?: break
            val y = hh + i * rh
            val sel = idx == s.trackSel
            if (sel) gradRect(bc, 0f, y, sz, y + rh, selTop, selBot)
            font(st.bodyFace, 11.5f, if (sel) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            val dur = mmss(song.durationMs)
            val dw = text.measureText(dur)
            bcDraw(fit(song.title, sz - dw - 14), 4f, y + 13.5f)
            text.color = if (sel) 0xFFFFFFFF.toInt() else 0xFF7A8189.toInt()
            bcDraw(dur, sz - dw - 4, y + 13.5f)
            if (!sel) { fill.shader = null; fill.color = 0xFFE3E6EA.toInt(); bc.drawRect(0f, y + rh - 1, sz, y + rh, fill) }
        }
        stroke.color = 0xFF8A929B.toInt(); stroke.strokeWidth = 1f
        bc.drawRect(0.5f, 0.5f, sz - 0.5f, sz - 0.5f, stroke)
    }

    private fun bcDraw(s: String, x: Float, y: Float) = bc.drawText(s, x, y, text)
}
