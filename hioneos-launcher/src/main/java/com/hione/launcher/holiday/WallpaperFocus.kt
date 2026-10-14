package com.hione.launcher.holiday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where her face is in an image: x, y as 0..1 of its width and height. */
data class FocusPoint(val x: Float, val y: Float)

/**
 * Where a screen wants the face, in 0..1 of the view. [maxZoom] caps the zoom past cover-fit,
 * [minFaceY] is the highest face position worth zooming for (above it the widget still hides
 * her, so the zoom would only cost sharpness), [deadband] skips a zoom for a near miss.
 */
data class FrameTarget(
    val x: Float,
    val y: Float,
    val maxZoom: Float,
    val minFaceY: Float,
    val deadband: Float = 0.04f,
)

/** A region of the image, in image pixels. */
data class SrcRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Focal-point framing for the home and lock wallpapers. Pure, so it runs on the JVM in tests.
 *
 * Pack art can name the center of her face: pack.json `art.focus` (`{"<art path>" or
 * "drawable:<name>": [x, y]}`) and calendar.json `home_focus` / `lock_focus` next to the
 * home_720 / lock_720 images. The renderer cover-fits the image, then shifts and zooms (at most
 * [FrameTarget.maxZoom]) so the face lands on the screen's target, clamped so it never shows past
 * the image edges. The zoom only answers the vertical miss; x takes whatever slack there is. No
 * focus = the plain center crop, as before.
 *
 * Mirrored in Python by the preview script that checks the focus points; keep them the same.
 */
object WallpaperFocus {
    /** Home: the gap between the top widget card (y 55..605 of 1280) and the dock (1085..1235). */
    val HOME = FrameTarget(x = 0.5f, y = 0.66f, maxZoom = 1.35f, minFaceY = 0.53f)

    /**
     * Home with the card put away (HioneDeck pull-up layouts): the band from the status bar to
     * the dock is open, so her face goes near the middle of it. Only the status bar (y < 0.04)
     * can hide her, so the no-zoom guard sits just under it.
     */
    val HOME_CLEAR = FrameTarget(x = 0.5f, y = 0.45f, maxZoom = 1.35f, minFaceY = 0.10f)

    /**
     * Home with the card pinned above the dock (HioneDeck PINNED, card at roughly y 0.40..0.83):
     * her face goes in the gap above it. The card is below the target here, so the "still under
     * the widget" guard does not apply (minFaceY 0).
     */
    val HOME_PINNED = FrameTarget(x = 0.5f, y = 0.22f, maxZoom = 1.35f, minFaceY = 0f)

    /** Lock: the gap between the weather capsule and the player card, where the lock art was built to put her face. */
    val LOCK = FrameTarget(x = 0.5f, y = 0.565f, maxZoom = 1.35f, minFaceY = 0.50f)

    /** The image region to draw so it fills a [viewW] x [viewH] view. */
    fun sourceRect(imgW: Int, imgH: Int, viewW: Float, viewH: Float, focus: FocusPoint?, t: FrameTarget): SrcRect {
        val iw = imgW.toFloat()
        val ih = imgH.toFloat()
        if (iw <= 0f || ih <= 0f || viewW <= 0f || viewH <= 0f) return SrcRect(0f, 0f, iw.coerceAtLeast(0f), ih.coerceAtLeast(0f))
        val cover = maxOf(viewW / iw, viewH / ih)
        if (focus == null) {
            val w = viewW / cover
            val h = viewH / cover
            return SrcRect((iw - w) / 2f, (ih - h) / 2f, w, h)
        }
        val fx = focus.x.coerceIn(0f, 1f)
        val fy = focus.y.coerceIn(0f, 1f)
        val base = place(iw, ih, viewW, viewH, fx, fy, t, cover)
        val baseY = (fy * ih - base.top) / base.height
        if (abs(baseY - t.y) <= t.deadband) return base
        var need = cover
        if (fy > 0f) need = maxOf(need, t.y * viewH / (fy * ih))
        if (fy < 1f) need = maxOf(need, (1f - t.y) * viewH / ((1f - fy) * ih))
        val s = minOf(need, cover * t.maxZoom)
        if (s <= cover) return base
        val r = place(iw, ih, viewW, viewH, fx, fy, t, s)
        val faceY = (fy * ih - r.top) / r.height
        // Zooming that still leaves her face under the widget only costs sharpness: don't.
        if (faceY < t.minFaceY) return base
        return r
    }

    private fun place(iw: Float, ih: Float, vw: Float, vh: Float, fx: Float, fy: Float, t: FrameTarget, s: Float): SrcRect {
        val w = vw / s
        val h = vh / s
        val left = (fx * iw - t.x * w).coerceIn(0f, (iw - w).coerceAtLeast(0f))
        val top = (fy * ih - t.y * h).coerceIn(0f, (ih - h).coerceAtLeast(0f))
        return SrcRect(left, top, w, h)
    }

    // ── Focus tables ────────────────────────────────────────────────────────────────────────

    /** [x, y] with both in 0..1, or null. */
    fun parsePoint(v: Any?): FocusPoint? {
        val a = v as? JSONArray ?: return null
        if (a.length() < 2) return null
        val x = a.optDouble(0, Double.NaN)
        val y = a.optDouble(1, Double.NaN)
        if (x.isNaN() || y.isNaN() || x !in 0.0..1.0 || y !in 0.0..1.0) return null
        return FocusPoint(x.toFloat(), y.toFloat())
    }

    /** pack.json `art.focus`: art path or "drawable:<name>" to the face center. */
    fun parsePack(o: JSONObject): Map<String, FocusPoint> {
        val f = o.optJSONObject("art")?.optJSONObject("focus") ?: return emptyMap()
        val out = HashMap<String, FocusPoint>()
        for (k in f.keys()) parsePoint(f.opt(k))?.let { out[k] = it }
        return out
    }

    /** calendar.json: home_focus / lock_focus next to each season and holiday art set, keyed by image path. */
    fun parseCalendar(text: String): Map<String, FocusPoint> {
        val t = text.trim()
        if (!t.startsWith("{")) return emptyMap()
        val root = JSONObject(t)
        val out = HashMap<String, FocusPoint>()
        fun take(a: JSONObject) {
            for (kind in listOf("home", "lock")) {
                val p = parsePoint(a.opt("${kind}_focus")) ?: continue
                for (k in listOf("${kind}_720", kind)) a.optString(k).ifBlank { null }?.let { out[it] = p }
            }
        }
        root.optJSONArray("seasons")?.let { arr ->
            for (i in 0 until arr.length()) {
                val art = arr.optJSONObject(i)?.optJSONArray("art") ?: continue
                for (j in 0 until art.length()) art.optJSONObject(j)?.let(::take)
            }
        }
        root.optJSONArray("holidays")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.optJSONObject("art")?.let(::take)
        }
        return out
    }

    private val DRAWABLE_PATH = Regex("""^res/drawable[^/]*/([^/]+)\.[A-Za-z0-9]+$""")

    /** The focus for art reference [ref] (a pack path or "drawable:<name>"), or null. */
    fun lookup(table: Map<String, FocusPoint>, ref: String?): FocusPoint? {
        if (ref.isNullOrBlank() || table.isEmpty()) return null
        table[ref]?.let { return it }
        // A pack file that replaces a drawable is the same image as "drawable:<name>".
        DRAWABLE_PATH.find(ref)?.let { m -> table["drawable:" + m.groupValues[1]]?.let { return it } }
        return null
    }
}

/**
 * A full-screen wallpaper framed on [focus] for target [target]. Without a focus it is the same
 * center crop as Image(contentScale = Crop).
 */
@Composable
fun FocusedWallpaper(
    bitmap: ImageBitmap,
    focus: FocusPoint?,
    target: FrameTarget,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    if (focus == null) {
        Image(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, contentScale = ContentScale.Crop)
        return
    }
    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        val r = WallpaperFocus.sourceRect(bitmap.width, bitmap.height, size.width, size.height, focus, target)
        val l = r.left.roundToInt().coerceIn(0, bitmap.width - 1)
        val t = r.top.roundToInt().coerceIn(0, bitmap.height - 1)
        val w = r.width.roundToInt().coerceIn(1, bitmap.width - l)
        val h = r.height.roundToInt().coerceIn(1, bitmap.height - t)
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(l, t),
            srcSize = IntSize(w, h),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}
