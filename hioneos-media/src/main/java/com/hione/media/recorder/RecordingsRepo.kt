package com.hione.media.recorder

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

data class Recording(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val durationMs: Long,
    val size: Long,
    val dateAdded: Long
)

/** Outcome of a write that may need the user's say-so (files another app created). */
sealed interface WriteResult {
    data object Done : WriteResult
    data class NeedsConsent(val sender: IntentSender) : WriteResult
    data object Failed : WriteResult
}

object RecordingsRepo {
    private val audio: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /**
     * Everything under Recordings/. Our own files are always visible; ones made by other
     * recorders show up too once READ_MEDIA_AUDIO is granted.
     */
    suspend fun list(ctx: Context): List<Recording> = withContext(Dispatchers.IO) {
        val out = ArrayList<Recording>()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(Environment.DIRECTORY_RECORDINGS + "/%"))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
        }
        runCatching {
            ctx.contentResolver.query(
                audio,
                arrayOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED
                ),
                args, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += Recording(
                        id, ContentUris.withAppendedId(audio, id),
                        c.getString(1) ?: "", c.getString(2) ?: "audio/*",
                        c.getLong(3), c.getLong(4), c.getLong(5) * 1000
                    )
                }
            }
        }
        out
    }

    fun changes(ctx: Context): Flow<Unit> = callbackFlow {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        ctx.contentResolver.registerContentObserver(audio, true, obs)
        awaitClose { ctx.contentResolver.unregisterContentObserver(obs) }
    }

    /** Rename, keeping the extension so the file stays playable and correctly typed. */
    suspend fun rename(ctx: Context, r: Recording, newBase: String): WriteResult = withContext(Dispatchers.IO) {
        val ext = r.name.substringAfterLast('.', "")
        val clean = newBase.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").ifEmpty { return@withContext WriteResult.Failed }
        val name = if (ext.isNotEmpty()) "$clean.$ext" else clean
        try {
            val n = ctx.contentResolver.update(r.uri, ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, name) }, null, null)
            if (n > 0) WriteResult.Done else WriteResult.Failed
        } catch (e: RecoverableSecurityException) {
            WriteResult.NeedsConsent(MediaStore.createWriteRequest(ctx.contentResolver, listOf(r.uri)).intentSender)
        } catch (e: SecurityException) {
            WriteResult.NeedsConsent(MediaStore.createWriteRequest(ctx.contentResolver, listOf(r.uri)).intentSender)
        } catch (t: Throwable) {
            WriteResult.Failed
        }
    }

    /** Our own recordings delete without a prompt; another app's need the system confirmation. */
    suspend fun delete(ctx: Context, r: Recording): WriteResult = withContext(Dispatchers.IO) {
        try {
            if (ctx.contentResolver.delete(r.uri, null, null) > 0) WriteResult.Done else WriteResult.Failed
        } catch (e: SecurityException) {
            WriteResult.NeedsConsent(MediaStore.createDeleteRequest(ctx.contentResolver, listOf(r.uri)).intentSender)
        } catch (t: Throwable) {
            WriteResult.Failed
        }
    }
}
