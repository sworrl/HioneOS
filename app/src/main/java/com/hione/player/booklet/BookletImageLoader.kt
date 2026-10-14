package com.hione.player.booklet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.hione.player.loadArtHiRes
import com.hione.player.loadArtThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Decodes booklet pages for the viewer — always downsampled to the requested longest side (never
 * a full 6000px scan into memory), always off the main thread, memory-LRU'd, and PDF pages
 * rendered once then parked as WebP in the app cache dir so the next open is a plain image decode.
 */
object BookletImageLoader {
    /** Thumbnail strip size (longest side, px). */
    const val THUMB_PX = 160

    private val mem = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceAtLeast(8 * 1024)) {
        override fun sizeOf(key: String, v: ImageBitmap) = (v.width * v.height * 4) / 1024
    }
    private val misses = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MISS_TTL_MS = 60_000L

    /** PdfRenderer is not thread-safe and two renderers on one file misbehave — serialise. */
    private val pdfMutex = Mutex()

    fun peek(page: ArtPage, targetPx: Int): ImageBitmap? = mem.get(key(page, targetPx))
    fun clearMemory() { mem.evictAll(); misses.clear() }

    private fun key(page: ArtPage, targetPx: Int) = "${page.key}@$targetPx"

    /** Full-size page target for the current screen: enough over 1x to zoom into, capped hard. */
    fun fullTargetPx(widthPx: Int, heightPx: Int): Int = (maxOf(widthPx, heightPx) * 3 / 2).coerceIn(720, 1800)

    suspend fun load(ctx: Context, page: ArtPage, targetPx: Int): ImageBitmap? {
        val k = key(page, targetPx)
        mem.get(k)?.let { return it }
        val missAt = misses[k]
        if (missAt != null && android.os.SystemClock.elapsedRealtime() - missAt < MISS_TTL_MS) return null
        val app = ctx.applicationContext
        val bmp: Bitmap? = withContext(Dispatchers.IO) {
            try {
                when (val ref = page.ref) {
                    is ArtSourceRef.ImageFile -> decodeSampled(ref.file, targetPx, lowQuality = targetPx <= THUMB_PX)
                    is ArtSourceRef.PdfPage -> renderPdfPage(app, ref, targetPx)
                    is ArtSourceRef.Embedded -> null
                }
            } catch (_: OutOfMemoryError) { mem.evictAll(); null } catch (_: Throwable) { null }
        }
        val img: ImageBitmap? = bmp?.asImageBitmap() ?: (page.ref as? ArtSourceRef.Embedded)?.let { e ->
            // Route through the app's own art pipeline (embedded APIC → folder → MediaStore) so an
            // embedded cover is exactly what every other screen already shows for this album.
            try {
                if (targetPx <= THUMB_PX) loadArtThumb(app, e.trackId, e.trackPath) else loadArtHiRes(app, e.trackId, e.trackPath)
            } catch (_: Throwable) { null }
        }
        if (img != null) mem.put(k, img) else misses[k] = android.os.SystemClock.elapsedRealtime()
        return img
    }

    // ------------------------------------------------------------------ images

    private fun decodeSampled(file: File, targetPx: Int, lowQuality: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        // A page is long-side bound: keep sampling while BOTH sides still exceed the target...
        // ...then one more step if the LONG side alone is still more than 2x over.
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (lowQuality) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return fitToTarget(raw, targetPx)
    }

    /** Power-of-two sampling can still land up to 2x over; squeeze to the target so a page never
     *  costs more than ~target² × 4 bytes. */
    private fun fitToTarget(src: Bitmap, targetPx: Int): Bitmap {
        val longSide = maxOf(src.width, src.height)
        if (longSide <= targetPx * 5 / 4) return src
        val s = targetPx.toFloat() / longSide
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, w, h, true)
        if (out !== src) src.recycle()
        return out
    }

    // ------------------------------------------------------------------ PDF

    private fun pdfCacheDir(ctx: Context, file: File): File {
        val id = md5("${file.absolutePath}|${file.length()}|${file.lastModified()}")
        return File(File(ctx.cacheDir, "booklet_pdf"), id).apply { mkdirs() }
    }

    /** Where an online source would park downloaded files (see ArtSourceRef.Remote). */
    fun remoteCacheDir(ctx: Context): File = File(ctx.cacheDir, "booklet_remote").apply { mkdirs() }

    private suspend fun renderPdfPage(ctx: Context, ref: ArtSourceRef.PdfPage, targetPx: Int): Bitmap? {
        val dir = pdfCacheDir(ctx, ref.file)
        // Render at the FULL target once; thumbs are cut from that render rather than rendering twice.
        val renderPx = maxOf(targetPx, 900)
        val cached = File(dir, "p${ref.pageIndex}_$renderPx.webp")
        if (cached.exists() && cached.length() > 0) {
            decodeSampled(cached, targetPx, lowQuality = targetPx <= THUMB_PX)?.let { return it }
        }
        val rendered = pdfMutex.withLock { renderPdfPageLocked(ref, renderPx) } ?: return null
        writeWebp(rendered, cached)
        return fitToTarget(rendered, targetPx)
    }

    private fun renderPdfPageLocked(ref: ArtSourceRef.PdfPage, renderPx: Int): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            pfd = ParcelFileDescriptor.open(ref.file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (ref.pageIndex !in 0 until renderer.pageCount) return null
            page = renderer.openPage(ref.pageIndex)
            val pw = page.width.coerceAtLeast(1); val ph = page.height.coerceAtLeast(1)
            val s = renderPx.toFloat() / maxOf(pw, ph)
            val w = (pw * s).toInt().coerceAtLeast(1); val h = (ph * s).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)   // PDF pages are transparent where nothing is printed
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } catch (_: Throwable) { null } finally {
            try { page?.close() } catch (_: Throwable) {}
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    private fun writeWebp(bmp: Bitmap, f: File) {
        try {
            f.outputStream().use {
                if (Build.VERSION.SDK_INT >= 30) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, it)
                else @Suppress("DEPRECATION") bmp.compress(Bitmap.CompressFormat.WEBP, 90, it)
            }
        } catch (_: Throwable) { runCatching { f.delete() } }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
