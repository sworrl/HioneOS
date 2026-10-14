package com.hione.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-tap "RANDOM" mode: playback of a random track from the WHOLE library starts within a few
 * hundred ms, with no library walk, no art loading, no 20k-item queue build up front.
 *
 *  1. Pick ONE random track from FastLibraryStore's in-memory/binary index (0ms after first load).
 *  2. setMediaItems([that one]) + prepare + play — audio starts immediately.
 *  3. Off the main thread: sample up to [QUEUE_FILL] more distinct random tracks and append them
 *     as the up-next queue, then persist the queue + the shuffle flag so a restart comes back in
 *     random mode with the icon lit.
 *
 * Reachable from the library header dice, the Home "RANDOM" pill, Now Playing's dice, and
 * externally via the `com.hione.player.action.PLAY_RANDOM` broadcast (HionePrefsReceiver) so the
 * launcher can fire it without the activity being up.
 */
object InstantRandom {
    private const val QUEUE_FILL = 400
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var lastStartedId: Long = -1L
        private set

    /** Random-mode flag — true from the moment a RANDOM tap starts until an explicit album/list
     *  play replaces the queue. Compose state so every dice button lights up together. */
    var active: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** A restored queue was saved in random mode — light the dice back up. */
    fun markActive() { active = true }

    /** Random tracks straight from MediaStore — count once, then LIMIT 1 / random OFFSET per pick.
     *  Only used when the binary library index isn't available yet (first launch after an index
     *  format bump, or a wiped cache). A handful of tiny queries, never a full-library load. */
    private fun mediaStoreRandom(ctx: Context, n: Int): List<Track> {
        val out = ArrayList<Track>(n)
        try {
            val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val sel = "${android.provider.MediaStore.Audio.Media.IS_MUSIC}!=0"
            val total = ctx.contentResolver.safeQuery(uri, arrayOf(android.provider.MediaStore.Audio.Media._ID), sel, null, null)?.use { it.count } ?: 0
            if (total <= 0) return out
            val rnd = java.util.concurrent.ThreadLocalRandom.current()
            val proj = arrayOf(
                android.provider.MediaStore.Audio.Media._ID, android.provider.MediaStore.Audio.Media.TITLE,
                android.provider.MediaStore.Audio.Media.ARTIST, android.provider.MediaStore.Audio.Media.ALBUM,
                android.provider.MediaStore.Audio.Media.DURATION, android.provider.MediaStore.Audio.Media.DATA,
                android.provider.MediaStore.Audio.Media.ALBUM_ID, android.provider.MediaStore.Audio.Media.MIME_TYPE
            )
            val seen = HashSet<Long>()
            var guard = 0
            while (out.size < n && guard < n * 3) {
                guard++
                val args = android.os.Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
                    putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, rnd.nextInt(total))
                }
                ctx.contentResolver.safeQuery(uri, proj, args, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        if (seen.add(id)) out.add(
                            Track(
                                id = id, title = c.getString(1) ?: "Unknown", artist = c.getString(2) ?: "Unknown artist",
                                album = c.getString(3) ?: "", durationMs = c.getLong(4), sizeBytes = 0L, bitrateKbps = 0,
                                mime = c.getString(7) ?: "", path = c.getString(5) ?: "", albumId = c.getLong(6)
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) { android.util.Log.w("InstantRandom", "MediaStore random pick failed", t) }
        return out
    }

    fun start(ctx: Context, onStarted: ((Track) -> Unit)? = null): Boolean {
        val app = ctx.applicationContext
        var library = FastLibraryStore.loadSync(app) ?: emptyList()
        if (library.isEmpty()) {
            // No binary index yet — a couple of MediaStore probes still get audio out within ~100ms.
            library = mediaStoreRandom(app, 40)
            if (library.isEmpty()) return false
        }
        val rnd = java.util.concurrent.ThreadLocalRandom.current()
        val first = library[rnd.nextInt(library.size)]
        val player = PlayerHolder.ensure(app)
        runCatching { PlayerHolder.ensureSession(app); PlayerHolder.ensureControllerConnected(app) }
        val go = Runnable {
            player.shuffleModeEnabled = true
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.setMediaItems(listOf(mediaItemFor(first)), 0, 0L)
            player.prepare()
            player.play()
            active = true
            lastStartedId = first.id
            PlayerPreferences.saveShuffle(app, true)
            PlayerPreferences.saveLastPlayback(app, first.id, 0L)
            onStarted?.invoke(first)
            // Fill the up-next queue lazily — the user is already listening.
            scope.launch {
                val picks = ArrayList<Track>(QUEUE_FILL)
                val seen = HashSet<Long>().apply { add(first.id) }
                val target = minOf(QUEUE_FILL, library.size - 1)
                var guard = 0
                while (picks.size < target && guard < target * 4) {
                    val t = library[rnd.nextInt(library.size)]
                    if (seen.add(t.id)) picks.add(t)
                    guard++
                }
                val items = picks.map { mediaItemFor(it) }
                withContext(Dispatchers.Main) {
                    // Only append if the user hasn't started something else in the meantime.
                    if (player.currentMediaItem?.mediaId == first.id.toString() && player.mediaItemCount <= 1) {
                        player.addMediaItems(items)
                        PlayerPreferences.saveQueue(app, listOf(first.id) + picks.map { it.id }, 0)
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) go.run() else Handler(Looper.getMainLooper()).post(go)
        return true
    }

    /** An explicit album/list play took over the queue — random mode is no longer what's playing. */
    fun clear() { active = false }
}
