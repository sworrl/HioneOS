package com.hione.media.gallery

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hione.media.ui.HioneTheme

/**
 * Gallery entry point: launcher, APP_GALLERY, and the PICK / GET_CONTENT picker other apps use
 * to attach a photo. Picker mode is the same browser with tap-to-return and an optional
 * multi-select, so people only learn one gallery.
 */
class GalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = parseMode(intent)
        setContent {
            HioneTheme {
                GalleryApp(mode = mode, onPicked = ::returnPicked)
            }
        }
    }

    private fun parseMode(i: Intent): GalleryMode {
        val action = i.action
        if (action != Intent.ACTION_PICK && action != Intent.ACTION_GET_CONTENT) return GalleryMode.Browse
        // Types arrive as "image/*", "video/mp4", the legacy "vnd.android.cursor.dir/image", or a
        // list in EXTRA_MIME_TYPES alongside "*/*". Narrow the grid to what the caller can accept.
        val types = buildList {
            i.type?.let { add(it) }
            i.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.let { addAll(it) }
        }.map { it.lowercase() }
        val wantsImage = types.isEmpty() || types.any { it.startsWith("image/") || it == "*/*" || it.endsWith("/image") }
        val wantsVideo = types.isEmpty() || types.any { it.startsWith("video/") || it == "*/*" || it.endsWith("/video") }
        val filter = when {
            wantsImage && !wantsVideo -> MediaFilter.IMAGES
            wantsVideo && !wantsImage -> MediaFilter.VIDEOS
            else -> MediaFilter.ALL
        }
        val multiple = i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        return GalleryMode.Pick(filter, multiple)
    }

    private fun returnPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val data = Intent().apply {
            data = uris[0]
            // Callers that asked for multiple read ClipData; single-pick callers read getData().
            // Setting both serves either kind, and ClipData is also what carries the URI grant.
            val clip = ClipData.newRawUri(null, uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }
}

sealed interface GalleryMode {
    data object Browse : GalleryMode
    data class Pick(val filter: MediaFilter, val multiple: Boolean) : GalleryMode
}
