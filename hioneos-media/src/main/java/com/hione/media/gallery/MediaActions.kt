package com.hione.media.gallery

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.hione.media.ui.HioneSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

object MediaActions {

    fun isMediaStore(uri: Uri) = uri.authority == MediaStore.AUTHORITY

    fun share(ctx: Context, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val mime = when {
            items.all { it.isVideo } -> if (items.size == 1) items[0].mime else "video/*"
            items.none { it.isVideo } -> if (items.size == 1) items[0].mime else "image/*"
            else -> "*/*"
        }
        val send = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, items[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        }
        send.type = mime
        // ClipData carries the read grant to whichever app the user picks in the chooser.
        val clip = ClipData.newRawUri(null, items[0].uri)
        items.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /**
     * System delete confirmation for MediaStore items. Goes through createDeleteRequest rather
     * than ContentResolver.delete because most photos on the device belong to another app (the
     * stock camera, Camera Go, a messenger), and without MANAGE_MEDIA only the user can approve
     * deleting those. Returns null for URIs MediaStore does not own.
     */
    fun deleteRequest(ctx: Context, uris: List<Uri>): IntentSender? {
        val ms = uris.filter { isMediaStore(it) }
        if (ms.isEmpty()) return null
        return MediaStore.createDeleteRequest(ctx.contentResolver, ms).intentSender
    }

    /** Delete feedback: the swoosh, then "File deleted." when the gallery's lines are on. */
    fun deletedCue() {
        HioneSounds.sfx("gallery", "delete")
        HioneSounds.sayAfter("gallery", "deleted", HioneSounds.sfxDurationMs("gallery", "delete") / 2)
    }

    fun openWallpaper(ctx: Context, uri: Uri) {
        ctx.startActivity(
            Intent(Intent.ACTION_ATTACH_DATA)
                .setClass(ctx, WallpaperActivity::class.java)
                .setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    fun openEditor(ctx: Context, uri: Uri) {
        ctx.startActivity(
            Intent(Intent.ACTION_EDIT)
                .setClass(ctx, CropActivity::class.java)
                .setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    /** Display name for any content/file URI, for items that did not come from our own query. */
    suspend fun displayName(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext uri.lastPathSegment ?: "file"
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "item"
    }

    suspend fun sizeOf(ctx: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
    }

    /**
     * Full-screen image decode, capped so the long edge is at most [maxEdge] px. The cap keeps a
     * 12 MP photo to a few MB of graphics memory while still leaving detail for a 2x zoom on the
     * 1280 px panel. ImageDecoder applies the EXIF orientation, so camera photos come out
     * upright. Hardware bitmaps live in graphics memory instead of the Java heap.
     */
    suspend fun decodeForDisplay(ctx: Context, uri: Uri, maxEdge: Int = 2048, software: Boolean = false): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val long = max(w, h)
                    if (long > maxEdge) {
                        val f = maxEdge.toFloat() / long
                        dec.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
                    }
                    dec.allocator = if (software) ImageDecoder.ALLOCATOR_SOFTWARE else ImageDecoder.ALLOCATOR_DEFAULT
                    dec.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                }
            }.getOrNull()
        }

    suspend fun decodeImageBitmap(ctx: Context, uri: Uri, maxEdge: Int = 2048): ImageBitmap? =
        decodeForDisplay(ctx, uri, maxEdge)?.asImageBitmap()

    suspend fun thumbFor(ctx: Context, uri: Uri, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
    }

    /**
     * Insert a new image into MediaStore under [relativePath] and write it. The row is pending
     * while bytes are written so other apps never see a half-written file.
     */
    suspend fun saveBitmap(
        ctx: Context,
        bmp: Bitmap,
        displayName: String,
        relativePath: String = Environment.DIRECTORY_PICTURES + "/Edited",
        png: Boolean = false
    ): Uri? = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = runCatching { cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv) }.getOrNull()
            ?: return@withContext null
        val ok = runCatching {
            cr.openOutputStream(uri, "w")?.use { os ->
                bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, os)
            } ?: false
        }.getOrDefault(false)
        if (!ok) {
            runCatching { cr.delete(uri, null, null) }
            return@withContext null
        }
        cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        uri
    }
}
