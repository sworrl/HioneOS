package com.hione.player

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Android Auto browse + playback bridge for HioneMusic.
 *
 * This is the [MediaLibrarySession.Callback] wired into the app's ONE existing ExoPlayer /
 * MediaSession (see [PlayerHolder]). It exposes a browsable media tree built from the real on-device
 * library (the same [FastLibraryStore] the UI uses), resolves a tapped item into a fully-playable
 * [MediaItem] (via the shared [mediaItemFor]) so playback drives the existing player, and hooks the
 * analog-audio routing ([HioneCarAudioRouter]) when a car controller connects.
 *
 * SCOPE / HARD ANDROID-AUTO LIMIT: Android Auto renders its OWN templated car UI from this browse
 * tree — a media-category app may NOT draw a custom car screen (no custom lists, no visualizer, no
 * tape/cassette canvas). So there is intentionally no custom UI here; we only populate the tree +
 * metadata (title / artist / album / album-art Uri) and the standard AA now-playing surface renders
 * it. The app's custom widescreen visuals (projectM visualizer, TapeMode canvas) only exist on the
 * device itself; projecting them to a head unit would require a separate external-display path
 * (USB DisplayPort alt-mode + a Presentation on a second Display), which is different, unverified
 * hardware and out of scope for the Android Auto media integration.
 *
 * Browse tree:
 *   [root]
 *     ├─ Albums     → one browsable per album  → its tracks (playable)
 *     ├─ Artists    → one browsable per artist → its tracks (playable)
 *     ├─ Songs      → every track (playable, paginated)
 *     ├─ Playlists  → Liked Songs → liked tracks (playable)
 *     └─ Modes      → Normal / Shuffle Mix / Repeat All / Repeat One / Tape (playable "actions"
 *                     that switch the live player mode hands-free, then keep the current queue)
 */
class HioneLibraryCallback(context: Context) : MediaLibrarySession.Callback {

    private val appContext: Context = context.applicationContext

    // ---- browse-tree memoization (single-threaded: all callbacks run on the session app thread) --
    private var groupsForTracks: List<Track>? = null
    private var cachedAlbums: List<AlbumGroup> = emptyList()
    private var cachedArtists: List<ArtistGroup> = emptyList()

    // ------------------------------------------------------------------------------------------
    // Library source
    // ------------------------------------------------------------------------------------------

    /** Full track list from the fast on-disk cache (survives a cold process start, so Android Auto
     *  can browse without MainActivity ever having run this session). Falls back to a lightweight
     *  MediaStore query only when the cache is genuinely empty (first-ever run before any scan). */
    private fun allTracks(): List<Track> {
        FastLibraryStore.loadSync(appContext)?.let { if (it.isNotEmpty()) return it }
        return HioneMediaStoreFallback.query(appContext)
    }

    private fun ensureGroups(tracks: List<Track>) {
        if (tracks === groupsForTracks) return
        cachedAlbums = tracks.albums(appContext)
        cachedArtists = tracks.artists(appContext)
        groupsForTracks = tracks
    }

    private fun albumsList(): List<AlbumGroup> {
        val t = allTracks(); ensureGroups(t); return cachedAlbums
    }

    private fun artistsList(): List<ArtistGroup> {
        val t = allTracks(); ensureGroups(t); return cachedArtists
    }

    private fun likedTracks(): List<Track> = try {
        LikeStore.resolveLiked(appContext, allTracks())
    } catch (t: Throwable) {
        Log.e(TAG, "likedTracks failed", t); emptyList()
    }

    private fun trackById(mediaId: String): Track? {
        val tid = mediaId.toLongOrNull() ?: return null
        return allTracks().firstOrNull { it.id == tid }
    }

    private fun albumKey(a: AlbumGroup): String = canonicalAlbumKey(a.artist, a.name, appContext)
    private fun artistKey(a: ArtistGroup): String = canonicalArtistKey(a.name, appContext)

    // ------------------------------------------------------------------------------------------
    // Connection — accept with default commands, and hook car audio routing
    // ------------------------------------------------------------------------------------------

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        HioneCarAudioRouter.onControllerConnected(appContext, controller.packageName)

        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .add(SessionCommand(ACTION_REWIND_15, Bundle.EMPTY))
            .add(SessionCommand(ACTION_FAST_FORWARD_15, Bundle.EMPTY))
            .add(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
            .add(SessionCommand(ACTION_SHUFFLE_MODE, Bundle.EMPTY))
            .build()

        val customLayout = buildCustomLayout()

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setCustomLayout(customLayout)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        val p = PlayerHolder.player
        when (customCommand.customAction) {
            ACTION_REWIND_15 -> {
                p?.let { it.seekTo(maxOf(0L, it.currentPosition - 15_000L)) }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            ACTION_FAST_FORWARD_15 -> {
                p?.let { it.seekTo(minOf(it.duration, it.currentPosition + 15_000L)) }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            ACTION_TOGGLE_LIKE -> {
                val tid = p?.currentMediaItem?.mediaId?.toLongOrNull() ?: PlayerPreferences.loadLastTrackId(appContext)
                if (tid > 0) {
                    LikeStore.init(appContext)
                    LikeStore.toggle(appContext, tid)
                    session.setCustomLayout(buildCustomLayout())
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            ACTION_SHUFFLE_MODE -> {
                p?.let {
                    val next = !it.shuffleModeEnabled
                    it.shuffleModeEnabled = next
                    PlayerPreferences.saveShuffle(appContext, next)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }

    private fun buildCustomLayout(): ImmutableList<CommandButton> {
        val tid = PlayerHolder.player?.currentMediaItem?.mediaId?.toLongOrNull() ?: PlayerPreferences.loadLastTrackId(appContext)
        val isLiked = if (tid > 0) LikeStore.isLiked(tid) else false

        val btnRewind = CommandButton.Builder()
            .setDisplayName("Rewind 15s")
            .setIconResId(android.R.drawable.ic_media_rew)
            .setSessionCommand(SessionCommand(ACTION_REWIND_15, Bundle.EMPTY))
            .build()

        val btnFF = CommandButton.Builder()
            .setDisplayName("Forward 15s")
            .setIconResId(android.R.drawable.ic_media_ff)
            .setSessionCommand(SessionCommand(ACTION_FAST_FORWARD_15, Bundle.EMPTY))
            .build()

        val btnLike = CommandButton.Builder()
            .setDisplayName(if (isLiked) "Liked" else "Like")
            .setIconResId(if (isLiked) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
            .build()

        return ImmutableList.of(btnRewind, btnFF, btnLike)
    }

    // ------------------------------------------------------------------------------------------
    // Browse tree
    // ------------------------------------------------------------------------------------------

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // Content-style hints so Android Auto renders browsable nodes as a grid and playable nodes
        // as a list (raw keys avoid needing the androidx.media compat constants).
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        }
        val rootParams = LibraryParams.Builder().setExtras(extras).build()
        val root = browsable(ID_ROOT, "${Mascot.musicAppName}", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item: MediaItem? = when {
            mediaId == ID_ROOT -> browsable(ID_ROOT, "${Mascot.musicAppName}", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            mediaId == ID_ALBUMS -> browsable(ID_ALBUMS, "Albums", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
            mediaId == ID_ARTISTS -> browsable(ID_ARTISTS, "Artists", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
            mediaId == ID_SONGS -> browsable(ID_SONGS, "Songs", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            mediaId == ID_PLAYLISTS -> browsable(ID_PLAYLISTS, "Playlists", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            mediaId == ID_MODES -> browsable(ID_MODES, "Player Modes", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            mediaId == ID_PLAYLIST_LIKED -> browsable(ID_PLAYLIST_LIKED, "Liked Songs", null, likedArtUri(), MediaMetadata.MEDIA_TYPE_PLAYLIST)
            mediaId.startsWith(PREFIX_ALBUM) -> albumsList().firstOrNull { albumKey(it) == mediaId.removePrefix(PREFIX_ALBUM) }?.let { albumItem(it) }
            mediaId.startsWith(PREFIX_ARTIST) -> artistsList().firstOrNull { artistKey(it) == mediaId.removePrefix(PREFIX_ARTIST) }?.let { artistItem(it) }
            mediaId.startsWith(PREFIX_MODE) -> modeItems().firstOrNull { it.mediaId == mediaId }
            else -> trackById(mediaId)?.let { playableTrackItem(it) }
        }
        return if (item != null) {
            Futures.immediateFuture(LibraryResult.ofItem(item, null))
        } else {
            Futures.immediateFuture(LibraryResult.ofError<MediaItem>(SessionResult.RESULT_ERROR_BAD_VALUE))
        }
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val children: List<MediaItem> = try {
            when {
                parentId == ID_ROOT -> rootCategories()
                parentId == ID_ALBUMS -> pagedItems(albumsList(), page, pageSize) { albumItem(it) }
                parentId == ID_ARTISTS -> pagedItems(artistsList(), page, pageSize) { artistItem(it) }
                parentId == ID_SONGS -> pagedItems(allTracks(), page, pageSize) { playableTrackItem(it) }
                parentId == ID_PLAYLISTS -> listOf(
                    browsable(ID_PLAYLIST_LIKED, "Liked Songs", "${likedTracks().size} songs", likedArtUri(), MediaMetadata.MEDIA_TYPE_PLAYLIST)
                )
                parentId == ID_MODES -> modeItems()
                parentId == ID_PLAYLIST_LIKED -> pagedItems(likedTracks(), page, pageSize) { playableTrackItem(it) }
                parentId.startsWith(PREFIX_ALBUM) -> {
                    val key = parentId.removePrefix(PREFIX_ALBUM)
                    val tracks = albumsList().firstOrNull { albumKey(it) == key }?.tracks ?: emptyList()
                    pagedItems(tracks, page, pageSize) { playableTrackItem(it) }
                }
                parentId.startsWith(PREFIX_ARTIST) -> {
                    val key = parentId.removePrefix(PREFIX_ARTIST)
                    val tracks = artistsList().firstOrNull { artistKey(it) == key }?.tracks ?: emptyList()
                    pagedItems(tracks, page, pageSize) { playableTrackItem(it) }
                }
                else -> emptyList()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onGetChildren($parentId) failed", t); emptyList()
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
    }

    // ------------------------------------------------------------------------------------------
    // Playback resolution — attach real URIs so the EXISTING player plays the tapped item
    // ------------------------------------------------------------------------------------------

    /**
     * The main play hook. A controller (Android Auto) tapping a browse item arrives here (the legacy
     * playFromMediaId path routes through onSetMediaItems). We:
     *  - a MODE action item  → switch the live player mode, keep the current queue playing;
     *  - a container (album / artist / Liked / Songs) → expand into that whole list;
     *  - a single track       → play it in the context of its album (so playback continues), else solo;
     *  - anything else        → resolve 1:1.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (HioneCarAudioRouter.isCarPackage(controller.packageName)) {
            HioneCarAudioRouter.ensureAnalogIfCar(appContext, "set-media-items")
        }

        if (mediaItems.size == 1) {
            val id = mediaItems[0].mediaId

            if (id.startsWith(PREFIX_MODE)) {
                applyMode(id)
                // Don't disturb playback: hand back the current queue at its current position.
                return Futures.immediateFuture(currentQueue())
            }

            val expanded = expandContainer(id)
            if (expanded != null) {
                val items = expanded.map { mediaItemFor(it) }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, 0, C.TIME_UNSET))
            }

            val tid = id.toLongOrNull()
            if (tid != null) {
                val (queue, index) = queueForTrack(tid)
                if (queue.isNotEmpty()) {
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(queue, index, startPositionMs))
                }
            }
        }

        val resolved = mediaItems.map { resolvePlayable(it) }
        val safeIndex = if (startIndex in resolved.indices) startIndex else 0
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(resolved, safeIndex, startPositionMs))
    }

    /** Belt-and-suspenders for a controller that adds items directly (not via setMediaItems). */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        if (HioneCarAudioRouter.isCarPackage(controller.packageName)) {
            HioneCarAudioRouter.ensureAnalogIfCar(appContext, "add-media-items")
        }
        val resolved: List<MediaItem> = mediaItems.flatMap { item ->
            expandContainer(item.mediaId)?.map { mediaItemFor(it) } ?: listOf(resolvePlayable(item))
        }
        return Futures.immediateFuture(resolved)
    }

    /** trackId mediaId → fully-playable MediaItem (URI + metadata) via the shared builder. */
    private fun resolvePlayable(item: MediaItem): MediaItem {
        val t = trackById(item.mediaId)
        return if (t != null) mediaItemFor(t) else item
    }

    /** A browsable container mediaId → the flat track list it represents, else null. */
    private fun expandContainer(mediaId: String): List<Track>? = when {
        mediaId == ID_SONGS -> allTracks()
        mediaId == ID_PLAYLIST_LIKED -> likedTracks()
        mediaId.startsWith(PREFIX_ALBUM) ->
            albumsList().firstOrNull { albumKey(it) == mediaId.removePrefix(PREFIX_ALBUM) }?.tracks
        mediaId.startsWith(PREFIX_ARTIST) ->
            artistsList().firstOrNull { artistKey(it) == mediaId.removePrefix(PREFIX_ARTIST) }?.tracks
        else -> null
    }

    /** Play a single tapped song in the context of its album so playback naturally continues; falls
     *  back to solo playback if the album can't be resolved. */
    private fun queueForTrack(tid: Long): Pair<List<MediaItem>, Int> {
        val album = albumsList().firstOrNull { g -> g.tracks.any { it.id == tid } }
        if (album != null) {
            val idx = album.tracks.indexOfFirst { it.id == tid }.coerceAtLeast(0)
            return album.tracks.map { mediaItemFor(it) } to idx
        }
        val solo = allTracks().firstOrNull { it.id == tid } ?: return emptyList<MediaItem>() to 0
        return listOf(mediaItemFor(solo)) to 0
    }

    /** Snapshot of the live player's current timeline, so a MODE switch keeps playback undisturbed. */
    private fun currentQueue(): MediaSession.MediaItemsWithStartPosition {
        val p = PlayerHolder.player
        if (p == null || p.mediaItemCount == 0) {
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        }
        val items = ArrayList<MediaItem>(p.mediaItemCount)
        for (i in 0 until p.mediaItemCount) items.add(p.getMediaItemAt(i))
        val idx = p.currentMediaItemIndex.coerceIn(0, p.mediaItemCount - 1)
        return MediaSession.MediaItemsWithStartPosition(items, idx, p.currentPosition)
    }

    // ------------------------------------------------------------------------------------------
    // Player modes — switch the LIVE player mode hands-free (drives the existing session)
    // ------------------------------------------------------------------------------------------

    private fun applyMode(modeId: String) {
        val p = PlayerHolder.player
        when (modeId) {
            MODE_NORMAL -> {
                p?.shuffleModeEnabled = false
                p?.repeatMode = Player.REPEAT_MODE_OFF
                PlayerPreferences.saveShuffle(appContext, false)
                PlayerPreferences.saveRepeat(appContext, false)
            }
            MODE_SHUFFLE -> {
                p?.shuffleModeEnabled = true
                PlayerPreferences.saveShuffle(appContext, true)
            }
            MODE_REPEAT_ALL -> {
                p?.repeatMode = Player.REPEAT_MODE_ALL
                PlayerPreferences.saveRepeat(appContext, true)
            }
            MODE_REPEAT_ONE -> {
                p?.repeatMode = Player.REPEAT_MODE_ONE
                PlayerPreferences.saveRepeat(appContext, true)
            }
            MODE_TAPE -> {
                // The tape/cassette VISUAL is an on-device Activity screen (Android Auto can't render
                // it — template-only). We persist the last-view signal the on-device UI restores from,
                // so the device drops into Tape mode when next foregrounded. Playback is unaffected.
                PlayerPreferences.saveLastView(appContext, "tape")
            }
        }
        Log.i(TAG, "applyMode($modeId): shuffle=${p?.shuffleModeEnabled} repeat=${p?.repeatMode}")
    }

    private fun modeItems(): List<MediaItem> = listOf(
        modeItem(MODE_NORMAL, "Normal", "Shuffle off · Repeat off"),
        modeItem(MODE_SHUFFLE, "Shuffle", "Shuffle the queue"),
        modeItem(MODE_REPEAT_ALL, "Repeat All", "Loop the whole queue"),
        modeItem(MODE_REPEAT_ONE, "Repeat One", "Loop the current track"),
        modeItem(MODE_TAPE, "Tape / Cassette", "Cassette view on the device")
    )

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val q = currentQueue()
        if (q.mediaItems.isNotEmpty()) {
            return Futures.immediateFuture(q)
        }
        val lastTrackId = PlayerPreferences.loadLastTrackId(appContext)
        if (lastTrackId > 0) {
            val (queue, index) = queueForTrack(lastTrackId)
            val pos = PlayerPreferences.loadLastPositionMs(appContext)
            if (queue.isNotEmpty()) {
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(queue, index, pos))
            }
        }
        val tracks = allTracks()
        if (tracks.isNotEmpty()) {
            val items = tracks.map { mediaItemFor(it) }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, 0, 0L))
        }
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET))
    }

    // ------------------------------------------------------------------------------------------
    // Media-button passthrough — kept identical to the previous MediaSession.Callback
    // ------------------------------------------------------------------------------------------

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent
    ): Boolean {
        if (AlarmRingService.interceptMediaButtonIntent(appContext, intent)) return true   // ringing alarm: play/pause = snooze, next/prev = dismiss
        if (HionePocketLockManager.keysLocked(appContext)) {
            Log.d(TAG, "onMediaButtonEvent ignored: Fn key lock engaged")
            return true
        }
        val p = PlayerHolder.ensure(appContext)
        @Suppress("DEPRECATION")
        val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            ?: intent.getParcelableExtra("android.intent.extra.KEY_EVENT")
        if (keyEvent != null) {
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                        Log.d(TAG, "onMediaButtonEvent PLAY_PAUSE down, isPlaying=${p.isPlaying}")
                        if (p.isPlaying) {
                            p.pause()
                        } else {
                            ensureReadyAndPlay(p)
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                        ensureReadyAndPlay(p)
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                        p.pause()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                        if (p.hasNextMediaItem()) {
                            p.seekToNextMediaItem()
                        } else if (p.mediaItemCount > 0) {
                            p.seekToDefaultPosition(0)
                        } else {
                            ensureReadyAndPlay(p)
                        }
                        if (p.playbackState == Player.STATE_IDLE) p.prepare()
                        p.play()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                        if (p.currentPosition > 3000L) {
                            p.seekTo(0L)
                        } else if (p.hasPreviousMediaItem()) {
                            p.seekToPreviousMediaItem()
                        } else if (p.mediaItemCount > 0) {
                            p.seekToDefaultPosition(p.mediaItemCount - 1)
                        } else {
                            ensureReadyAndPlay(p)
                        }
                        if (p.playbackState == Player.STATE_IDLE) p.prepare()
                        p.play()
                    }
                    return true
                }
            }
        }
        return super.onMediaButtonEvent(session, controllerInfo, intent)
    }

    private fun ensureReadyAndPlay(p: androidx.media3.exoplayer.ExoPlayer) {
        if (p.mediaItemCount == 0) {
            val lastTrackId = PlayerPreferences.loadLastTrackId(appContext)
            if (lastTrackId > 0) {
                val (queue, index) = queueForTrack(lastTrackId)
                val pos = PlayerPreferences.loadLastPositionMs(appContext)
                if (queue.isNotEmpty()) {
                    p.setMediaItems(queue, index, pos)
                }
            }
            if (p.mediaItemCount == 0) {
                val tracks = allTracks()
                if (tracks.isNotEmpty()) {
                    p.setMediaItems(tracks.map { mediaItemFor(it) }, 0, 0L)
                }
            }
        }
        if (p.playbackState == Player.STATE_IDLE) {
            p.prepare()
        }
        p.play()
    }

    // ------------------------------------------------------------------------------------------
    // MediaItem builders
    // ------------------------------------------------------------------------------------------

    private fun rootCategories(): List<MediaItem> = listOf(
        browsable(ID_ALBUMS, "Albums", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        browsable(ID_ARTISTS, "Artists", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        browsable(ID_SONGS, "Songs", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browsable(ID_PLAYLISTS, "Playlists", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        browsable(ID_MODES, "Player Modes", null, null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    )

    private fun albumItem(a: AlbumGroup): MediaItem {
        val artUri = a.tracks.firstOrNull { it.albumId > 0 }?.let { albumArtUri(it.albumId) }
        return browsable(PREFIX_ALBUM + albumKey(a), a.name, a.artist, artUri, MediaMetadata.MEDIA_TYPE_ALBUM)
    }

    private fun artistItem(a: ArtistGroup): MediaItem {
        val cover = a.coverTrack(appContext)
        val artUri = cover?.takeIf { it.albumId > 0 }?.let { albumArtUri(it.albumId) }
        return browsable(PREFIX_ARTIST + artistKey(a), a.name, "${a.tracks.size} songs", artUri, MediaMetadata.MEDIA_TYPE_ARTIST)
    }

    /** Playable browse item for a track. NO uri is set here (URIs must not cross the Binder to the
     *  browser); the real uri is attached in onSetMediaItems/onAddMediaItems via [mediaItemFor]. */
    private fun playableTrackItem(t: Track): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
            .setAlbumTitle(t.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(if (t.albumId > 0) albumArtUri(t.albumId) else null)
            .build()
        return MediaItem.Builder().setMediaId(t.id.toString()).setMediaMetadata(meta).build()
    }

    private fun modeItem(id: String, title: String, subtitle: String): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(meta).build()
    }

    private fun browsable(id: String, title: String, subtitle: String?, artUri: Uri?, mediaType: Int): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .setArtworkUri(artUri)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(meta).build()
    }

    private fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId)

    private fun likedArtUri(): Uri? = likedTracks().firstOrNull { it.albumId > 0 }?.let { albumArtUri(it.albumId) }

    private fun <T> pagedItems(all: List<T>, page: Int, pageSize: Int, map: (T) -> MediaItem): List<MediaItem> {
        val slice = if (pageSize <= 0) all else {
            val from = page * pageSize
            if (from >= all.size) return emptyList()
            all.subList(from, minOf(from + pageSize, all.size))
        }
        return slice.map(map)
    }

    companion object {
        private const val TAG = "HioneMediaLibrary"

        // Standard MediaStore album-art base uri (same scheme MainActivity.mediaItemFor uses).
        private val ALBUM_ART_BASE_URI: Uri = Uri.parse("content://media/external/audio/albumart")

        // Media id scheme (stable strings, parsed back in the browse callbacks).
        private const val ID_ROOT = "[root]"
        private const val ID_ALBUMS = "[albums]"
        private const val ID_ARTISTS = "[artists]"
        private const val ID_SONGS = "[songs]"
        private const val ID_PLAYLISTS = "[playlists]"
        private const val ID_MODES = "[modes]"
        private const val ID_PLAYLIST_LIKED = "[playlist]liked"
        private const val PREFIX_ALBUM = "[album]"
        private const val PREFIX_ARTIST = "[artist]"
        private const val PREFIX_MODE = "[mode]"
        private const val MODE_NORMAL = "[mode]normal"
        private const val MODE_SHUFFLE = "[mode]shuffle"
        private const val MODE_REPEAT_ALL = "[mode]repeat_all"
        private const val MODE_REPEAT_ONE = "[mode]repeat_one"
        private const val MODE_TAPE = "[mode]tape"

        // Android Auto custom command action identifiers
        const val ACTION_REWIND_15 = "com.hione.player.action.REWIND_15"
        const val ACTION_FAST_FORWARD_15 = "com.hione.player.action.FF_15"
        const val ACTION_TOGGLE_LIKE = "com.hione.player.action.TOGGLE_LIKE"
        const val ACTION_SHUFFLE_MODE = "com.hione.player.action.TOGGLE_SHUFFLE"

        // Android Auto content-style hint keys (raw string keys → no androidx.media dependency).
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
        private const val CONTENT_STYLE_GRID = 2
    }
}

/**
 * Minimal MediaStore audio query, used ONLY as a cold-start fallback when [FastLibraryStore] has no
 * cache yet (a fresh install that has never scanned). Populates just the fields the browse grouping
 * needs (id/title/artist/album/albumId/path/track#/albumArtist/duration); bitrate is left 0 since the
 * car UI doesn't surface it. In the normal case the fast binary cache is used and this never runs.
 */
private object HioneMediaStoreFallback {
    private const val TAG = "HioneMediaStoreFallback"

    fun query(context: Context): List<Track> {
        val out = ArrayList<Track>()
        val hasAlbumArtist = android.os.Build.VERSION.SDK_INT >= 30
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DATE_ADDED)
            if (hasAlbumArtist) add("album_artist")
        }.toTypedArray()
        try {
            context.contentResolver.safeQuery(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC}=1",
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iAlbArt = if (hasAlbumArtist) c.getColumnIndex("album_artist") else -1
                while (c.moveToNext()) {
                    val rawTrack = c.getInt(iTrack)
                    val trackNo = if (rawTrack > 1000) rawTrack % 1000 else rawTrack
                    out.add(
                        Track(
                            id = c.getLong(iId),
                            title = c.getString(iTitle) ?: "",
                            artist = c.getString(iArtist) ?: "",
                            album = c.getString(iAlbum) ?: "",
                            durationMs = c.getLong(iDur),
                            sizeBytes = c.getLong(iSize),
                            bitrateKbps = 0,
                            mime = c.getString(iMime) ?: "",
                            path = c.getString(iData) ?: "",
                            year = c.getInt(iYear),
                            albumId = c.getLong(iAlbumId),
                            trackNumber = trackNo,
                            albumArtist = if (iAlbArt >= 0) (c.getString(iAlbArt) ?: "") else "",
                            dateAddedSec = c.getLong(iAdded)
                        )
                    )
                }
            }
            Log.i(TAG, "fallback MediaStore query returned ${out.size} tracks")
        } catch (t: Throwable) {
            Log.e(TAG, "fallback MediaStore query failed", t)
        }
        return out
    }
}
