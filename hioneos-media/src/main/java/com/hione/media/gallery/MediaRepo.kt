package com.hione.media.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val isVideo: Boolean,
    val dateTaken: Long,      // ms since epoch; falls back to date added when the file has no capture time
    val dateModified: Long,   // seconds, used to invalidate cached thumbnails after an edit
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String
)

data class Album(
    val bucketId: Long,
    val name: String,
    val count: Int,
    val cover: MediaItem
)

/** What the caller is allowed to see: picker requests can narrow to images or videos only. */
enum class MediaFilter { ALL, IMAGES, VIDEOS }

object MediaRepo {
    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.WIDTH,
        MediaStore.Files.FileColumns.HEIGHT,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH
    )

    private fun selection(filter: MediaFilter): String {
        val img = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
        val vid = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
        return when (filter) {
            MediaFilter.ALL -> "($img OR $vid)"
            MediaFilter.IMAGES -> img
            MediaFilter.VIDEOS -> vid
        }
    }

    /**
     * Every visible photo and video, newest first. One query against the Files table instead of
     * one each for Images and Video, so the timeline is already merged and sorted by the
     * provider. Pending and trashed rows are excluded by MediaStore's defaults.
     */
    suspend fun query(ctx: Context, filter: MediaFilter, bucketId: Long? = null): List<MediaItem> =
        withContext(Dispatchers.IO) {
            var sel = selection(filter)
            val args = mutableListOf<String>()
            if (bucketId != null) {
                sel += " AND ${MediaStore.MediaColumns.BUCKET_ID}=?"
                args += bucketId.toString()
            }
            val qargs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
                putString(
                    ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
                )
            }
            val out = ArrayList<MediaItem>()
            runCatching {
                ctx.contentResolver.query(filesUri, projection, qargs, null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                    val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val iAdded = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                    val iMod = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val iW = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                    val iH = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                    val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                    val iBid = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                    val iBn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        val video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000)
                        out += MediaItem(
                            id = id,
                            uri = ContentUris.withAppendedId(base, id),
                            name = c.getString(iName) ?: "",
                            mime = c.getString(iMime) ?: if (video) "video/*" else "image/*",
                            isVideo = video,
                            dateTaken = taken,
                            dateModified = c.getLong(iMod),
                            size = c.getLong(iSize),
                            width = c.getInt(iW),
                            height = c.getInt(iH),
                            durationMs = c.getLong(iDur),
                            bucketId = c.getLong(iBid),
                            bucketName = c.getString(iBn) ?: "",
                            relativePath = c.getString(iRel) ?: ""
                        )
                    }
                }
            }
            out
        }

    /** Albums are MediaStore buckets (one per folder), ordered by their newest item. */
    fun albums(items: List<MediaItem>): List<Album> {
        val byBucket = LinkedHashMap<Long, MutableList<MediaItem>>()
        for (it in items) byBucket.getOrPut(it.bucketId) { ArrayList() }.add(it)
        return byBucket.map { (id, list) ->
            Album(id, list.first().bucketName.ifEmpty { "Internal storage" }, list.size, list.first())
        }.sortedWith(
            // Camera roll first, then screenshots, then everything else by recency. That is the
            // order people actually look for things in on a device without a phone's photo habit.
            compareBy<Album> { albumRank(it) }.thenByDescending { it.cover.dateTaken }
        )
    }

    private fun albumRank(a: Album): Int {
        val p = a.cover.relativePath.lowercase()
        return when {
            p.startsWith("dcim/camera") -> 0
            p.contains("screenshots") -> 1
            else -> 2
        }
    }

    /**
     * Resolve a URI handed to us by another app back to a MediaStore row, when it is one, so the
     * viewer can offer swipe-through of the rest of that album. Works for content://media/...
     * URIs; anything else (a file manager's FileProvider, a download) is shown on its own.
     */
    suspend fun resolve(ctx: Context, uri: Uri): MediaItem? = withContext(Dispatchers.IO) {
        if (uri.authority != MediaStore.AUTHORITY) return@withContext null
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull()?.takeIf { it >= 0 } ?: return@withContext null
        val qargs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns._ID}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
        }
        runCatching {
            ctx.contentResolver.query(filesUri, arrayOf(MediaStore.MediaColumns.BUCKET_ID), qargs, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        }.getOrNull()?.let { bucket ->
            query(ctx, MediaFilter.ALL, bucket).firstOrNull { it.id == id }
        }
    }

    /** Emits once per MediaStore change, so grids refresh after a capture, delete or download. */
    fun changes(ctx: Context): Flow<Unit> = callbackFlow {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        ctx.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs)
        awaitClose { ctx.contentResolver.unregisterContentObserver(obs) }
    }
}

/**
 * Grid thumbnails. loadThumbnail() hands back MediaProvider's cached thumbnail when one exists,
 * which is far cheaper than decoding the original. Results are kept in an in-memory LRU keyed by
 * id + modified time, so scrolling back up the grid does not ask the provider again.
 *
 * Decodes run on a 4-wide slice of the IO pool: enough to fill a screen quickly, few enough that
 * a fling does not queue hundreds of decodes that will be off-screen before they finish.
 */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtMost(48 shl 20)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)

    private fun key(item: MediaItem, px: Int) = "${item.id}:${item.dateModified}:$px"

    fun cached(item: MediaItem, px: Int): Bitmap? = cache.get(key(item, px))

    suspend fun load(ctx: Context, item: MediaItem, px: Int): Bitmap? {
        cache.get(key(item, px))?.let { return it }
        return withContext(decodeDispatcher) {
            val signal = CancellationSignal()
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { signal.cancel() }
                val bmp = runCatching {
                    ctx.contentResolver.loadThumbnail(item.uri, Size(px, px), signal)
                }.getOrNull()
                if (bmp != null) cache.put(key(item, px), bmp)
                if (cont.isActive) cont.resumeWith(Result.success(bmp))
            }
        }
    }

    /** Thumbnail for an arbitrary URI (a single shared file, a camera capture). Not cached. */
    suspend fun loadUri(ctx: Context, uri: Uri, px: Int): Bitmap? = withContext(decodeDispatcher) {
        runCatching { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
    }
}
