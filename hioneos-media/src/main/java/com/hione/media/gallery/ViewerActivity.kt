package com.hione.media.gallery

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hione.media.ui.HioneSounds
import com.hione.media.ui.HioneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles ACTION_VIEW and the REVIEW family for single photos and videos from any app.
 *
 * A MediaStore URI is resolved back to its album so the user can swipe to neighbouring shots,
 * which is what a camera's "review" tap expects. Anything else (a file manager's FileProvider
 * URI, an email attachment) is shown on its own, since there is no album to page through.
 */
class ViewerActivity : ComponentActivity() {

    companion object {
        /** Set by our own camera: the URIs captured in this lock-screen session, newest first. */
        const val EXTRA_SESSION_URIS = "com.hione.media.extra.SESSION_URIS"
        const val EXTRA_SECURE = "com.hione.media.extra.SECURE"
        private const val ACTION_REVIEW_SECURE = "android.provider.action.REVIEW_SECURE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        HioneSounds.preload(this, "gallery")
        val i = intent
        val secure = i.action == ACTION_REVIEW_SECURE || i.getBooleanExtra(EXTRA_SECURE, false)
        if (secure) {
            // Only items the camera passed in are reachable, so it is safe to show over the
            // keyguard. Anything else in the library stays behind the lock.
            setShowWhenLocked(true)
        }
        val uri = i.data
        val sessionUris: List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableArrayListExtra(EXTRA_SESSION_URIS, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION") i.getParcelableArrayListExtra<Uri>(EXTRA_SESSION_URIS).orEmpty()
        }
        if (uri == null && sessionUris.isEmpty()) {
            finish()
            return
        }

        setContent {
            HioneTheme {
                val loaded by produceState<Pair<List<MediaItem>, Int>?>(null) {
                    value = load(uri, i.type, sessionUris, secure)
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    loaded?.let { (list, start) ->
                        ViewerScreen(list, start, secure, onClose = { finish() })
                    }
                }
            }
        }
    }

    private suspend fun load(uri: Uri?, type: String?, session: List<Uri>, secure: Boolean): Pair<List<MediaItem>, Int> {
        if (session.isNotEmpty()) {
            val list = session.map { single(it, null) }
            return list to (session.indexOf(uri).takeIf { it >= 0 } ?: 0)
        }
        uri!!
        if (!secure && MediaActions.isMediaStore(uri)) {
            val hit = MediaRepo.resolve(this, uri)
            if (hit != null) {
                val album = MediaRepo.query(this, MediaFilter.ALL, hit.bucketId)
                val idx = album.indexOfFirst { it.id == hit.id }
                if (idx >= 0) return album to idx
            }
        }
        return listOf(single(uri, type)) to 0
    }

    /** A viewer item for a URI we have no MediaStore row for (or must not list siblings of). */
    private suspend fun single(uri: Uri, type: String?): MediaItem {
        val mime = type?.takeIf { it.contains('/') && !it.endsWith("/*") }
            ?: withContext(Dispatchers.IO) { runCatching { contentResolver.getType(uri) }.getOrNull() }
            ?: type
            ?: guessMime(uri)
        val isVideo = mime.startsWith("video/")
        return MediaItem(
            id = -1,
            uri = uri,
            name = MediaActions.displayName(this, uri),
            mime = mime,
            isVideo = isVideo,
            dateTaken = 0,
            dateModified = 0,
            size = -1,
            width = 0,
            height = 0,
            durationMs = 0,
            bucketId = -1,
            bucketName = "",
            relativePath = ""
        )
    }

    private fun guessMime(uri: Uri): String {
        val p = uri.path?.lowercase() ?: return "image/*"
        return when {
            p.endsWith(".mp4") || p.endsWith(".3gp") || p.endsWith(".mkv") || p.endsWith(".webm") || p.endsWith(".mov") -> "video/mp4"
            uri.toString().contains(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.path ?: "/video/") -> "video/mp4"
            else -> "image/*"
        }
    }
}
