package com.hione.player.booklet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hione.player.CrashSentinel
import com.hione.player.Track
import com.hione.player.releaseTag
import java.io.File

/**
 * Hosts [BookletViewerScreen] in its own activity so this ONE screen may rotate to landscape (the
 * manifest pins it to fullSensor; the rest of Hione Music stays portrait) and so a folded-open
 * booklet is a real back-stack entry the a11y-nav "back" lands on.
 */
class BookletViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        val folders = intent.getStringArrayExtra(EXTRA_FOLDERS).orEmpty().map { File(it) }
        val album = intent.getStringExtra(EXTRA_ALBUM).orEmpty()
        val artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
        val trackId = intent.getLongExtra(EXTRA_TRACK_ID, 0L)
        val trackPath = intent.getStringExtra(EXTRA_TRACK_PATH).orEmpty()
        val tag = intent.getStringExtra(EXTRA_RELEASE_TAG)
        val quality = intent.getStringExtra(EXTRA_QUALITY_TAG)
        val query = AlbumArtQuery(folders, album, artist, trackId, trackPath)
        setContent {
            BookletViewerScreen(
                query = query,
                albumTitle = album.ifBlank { "Album art" },
                releaseTag = tag,
                qualityTag = quality,
                onClose = { finish() }
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) BookletImageLoader.clearMemory()
    }

    companion object {
        private const val EXTRA_FOLDERS = "folders"
        private const val EXTRA_ALBUM = "album"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_TRACK_ID = "trackId"
        private const val EXTRA_TRACK_PATH = "trackPath"
        private const val EXTRA_RELEASE_TAG = "releaseTag"
        private const val EXTRA_QUALITY_TAG = "qualityTag"

        /** The discovery query for an album's tracks — shared by the entry button and the viewer so
         *  both hit the same [AlbumArtSources] memo entry. */
        fun queryFrom(tracks: List<Track>, albumTitle: String): AlbumArtQuery {
            val rep = tracks.firstOrNull()
            val artist = rep?.let { it.albumArtist.ifBlank { it.artist } } ?: ""
            return AlbumArtQuery(
                folders = LocalFolderArtSource.foldersFor(tracks),
                album = albumTitle.ifBlank { rep?.album.orEmpty() },
                artist = artist,
                representativeTrackId = rep?.id ?: 0L,
                representativeTrackPath = rep?.path.orEmpty(),
            )
        }

        fun launch(ctx: Context, tracks: List<Track>, albumTitle: String, qualityTag: String? = null) {
            val q = queryFrom(tracks, albumTitle)
            val i = Intent(ctx, BookletViewerActivity::class.java).apply {
                putExtra(EXTRA_FOLDERS, q.folders.map { it.absolutePath }.toTypedArray())
                putExtra(EXTRA_ALBUM, q.album)
                putExtra(EXTRA_ARTIST, q.artist)
                putExtra(EXTRA_TRACK_ID, q.representativeTrackId)
                putExtra(EXTRA_TRACK_PATH, q.representativeTrackPath)
                putExtra(EXTRA_RELEASE_TAG, releaseTag(q.album))
                putExtra(EXTRA_QUALITY_TAG, qualityTag)
                if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { ctx.startActivity(i) } catch (_: Throwable) {}
        }
    }
}
