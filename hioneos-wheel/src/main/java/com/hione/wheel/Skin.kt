package com.hione.wheel

import android.content.Context
import android.view.View
import androidx.media3.common.Player

enum class Btn { MENU, PLAY, NEXT, PREV, SELECT }

enum class NpMode { NORMAL, VOLUME, SCRUB }

/**
 * One running skin: the screen stack, what the wheel does on each screen, and the menus of
 * its era. Everything here runs on the main thread. [invalidate] asks the panel to redraw.
 */
class Skin(
    val era: Era,
    private val ctx: Context,
    val prefs: WheelPrefs,
    val link: PlayerLink,
    val art: ArtCache,
    val clicker: Clicker,
    val radio: RadioLink,
    var lib: Library,
    var libLoaded: Boolean,
    var hasPermission: Boolean,
    private val invalidate: () -> Unit,
) {
    val style: Style get() = Style.of(era, prefs)
    val stack = ArrayList<Screen>()
    val top: Screen get() = stack.last()

    /** Bumped whenever the stack or a setting changes, so the shell recomposes only then. */
    val stackVersion = androidx.compose.runtime.mutableIntStateOf(0)
    private fun changed() { stackVersion.intValue++ }

    // Now Playing wheel mode: volume (default) or scrubbing after a center press.
    var npMode = NpMode.NORMAL
    var npModeUntil = 0L
    var scrubMs = 0L

    // Backlight: lit until this time. Long.MAX_VALUE = always on.
    var litUntil = Long.MAX_VALUE

    private var lastScrollAt = 0L
    private var scrollStreak = 0

    var volumeShownUntil = 0L

    init {
        stack.add(rootMenu())
        stack.add(BootScreen(System.currentTimeMillis()))
        wake()
    }

    // ---- input -------------------------------------------------------------------------------

    private fun now() = System.currentTimeMillis()

    /** Any input relights the screen per the Backlight Timer setting. */
    fun wake() {
        clicker.wake()
        val secs = prefs.backlightSecs
        litUntil = when {
            secs < 0 -> Long.MAX_VALUE
            secs == 0 -> litUntil.takeIf { it > now() } ?: 0L
            else -> now() + secs * 1000L
        }
    }

    fun isLit(t: Long) = t < litUntil

    /** [steps] detents, positive = clockwise. Returns true when something moved (to click). */
    fun onScroll(steps: Int, view: View?): Boolean {
        wake()
        val t = now()
        // Acceleration on long lists: a fast, steady spin covers more ground per detent.
        scrollStreak = if (t - lastScrollAt < 45) scrollStreak + 1 else 0
        lastScrollAt = t
        val moved = when (val s = top) {
            is BootScreen -> { skipBoot(); false }
            is MenuScreen -> {
                val accel = if (s.rows.size > 60) when {
                    scrollStreak > 24 -> 8
                    scrollStreak > 12 -> 4
                    scrollStreak > 6 -> 2
                    else -> 1
                } else 1
                moveSel(s, steps * accel)
            }
            is NowPlayingScreen -> {
                if (npMode == NpMode.SCRUB) {
                    val d = link.durationMs
                    if (d > 0) {
                        scrubMs = (scrubMs + steps * maxOf(1000L, d / 100)).coerceIn(0L, d - 500)
                        link.seekTo(scrubMs)
                    }
                    npModeUntil = t + 4000
                } else {
                    val per = maxOf(1, link.maxVolume / 30)
                    link.adjustVolume(steps * per)
                    npMode = NpMode.VOLUME
                    npModeUntil = t + 2000
                }
                true
            }
            is CoverFlowScreen -> {
                if (s.flipped) {
                    val n = s.albums.getOrNull(s.sel)?.songs?.size ?: 0
                    val old = s.trackSel
                    s.trackSel = (s.trackSel + steps).coerceIn(0, maxOf(0, n - 1))
                    keepVisible(s.trackSel, n, 6, { s.trackTop }, { s.trackTop = it })
                    old != s.trackSel
                } else {
                    val old = s.sel
                    val accel = if (scrollStreak > 10) 2 else 1
                    s.sel = (s.sel + steps * accel).coerceIn(0, maxOf(0, s.albums.size - 1))
                    old != s.sel
                }
            }
            is BrickScreen -> { s.game.move(steps); false }
            is RadioScreen -> radio.step(steps)
            is TextScreen -> {
                val old = s.top
                s.top = (s.top + steps).coerceIn(0, maxOf(0, s.lineCount - style.textLines))
                old != s.top
            }
            else -> false
        }
        if (moved) clicker.step()
        if (android.util.Log.isLoggable("HionePodClick", android.util.Log.DEBUG))
            android.util.Log.d("HionePodClick", "wheel ${era.id} steps=$steps moved=$moved on ${top.javaClass.simpleName}")
        invalidate()
        return moved
    }

    private fun moveSel(s: MenuScreen, delta: Int): Boolean {
        val n = s.rows.size
        if (n == 0) return false
        val old = s.sel
        s.sel = (s.sel + delta).coerceIn(0, n - 1)
        keepVisible(s.sel, n, style.visibleRows, { s.top }, { s.top = it })
        return old != s.sel
    }

    private inline fun keepVisible(sel: Int, n: Int, vis: Int, get: () -> Int, set: (Int) -> Unit) {
        var t = get()
        if (sel < t) t = sel
        if (sel >= t + vis) t = sel - vis + 1
        set(t.coerceIn(0, maxOf(0, n - vis)))
    }

    fun onButton(b: Btn, view: View?) {
        if (android.util.Log.isLoggable("HionePodClick", android.util.Log.DEBUG))
            android.util.Log.d("HionePodClick", "button ${era.id} $b on ${top.javaClass.simpleName}")
        val wasLit = isLit(now())
        wake()
        // The click itself (sound and pulse) came from the wheel when the switch closed.
        // Like the originals, the first press on a dark screen only turns the light on,
        // except play/pause, which should always work blind.
        if (!wasLit && b != Btn.PLAY) { invalidate(); return }
        val s = top
        if (s is BootScreen) { skipBoot(); invalidate(); return }
        if (s is RadioScreen && b != Btn.MENU) {
            when (b) {
                Btn.SELECT -> s.dial = !s.dial
                Btn.PLAY -> radio.powerToggle()
                Btn.NEXT -> radio.skip(true)
                Btn.PREV -> radio.skip(false)
                else -> {}
            }
            changed(); invalidate(); return
        }
        when (b) {
            Btn.MENU -> back()
            Btn.PLAY -> when (s) {
                is BrickScreen -> s.game.paused = !s.game.paused
                is StopwatchScreen -> toggleStopwatch(s)
                else -> link.playPause()
            }
            Btn.NEXT -> when (s) {
                is BrickScreen, is CoverFlowScreen -> {}
                else -> link.next()
            }
            Btn.PREV -> when (s) {
                is BrickScreen, is CoverFlowScreen -> {}
                is StopwatchScreen -> { if (!s.running) s.accumulated = 0L }
                else -> link.previous()
            }
            Btn.SELECT -> select(s)
        }
        changed()
        invalidate()
    }

    /**
     * Held buttons: fast forward / rewind, the light, and the About screen's hidden game.
     * Returns false when a hold means nothing here, so the release still counts as a press.
     */
    fun onHold(b: Btn, view: View?): Boolean {
        wake()
        val s = top
        if (s is BootScreen || s is BrickScreen || s is CoverFlowScreen) return false
        if (s is RadioScreen) return radioHold(s, b)
        when (b) {
            Btn.NEXT -> link.seekBy(5000)
            Btn.PREV -> link.seekBy(-5000)
            Btn.SELECT -> {
                if (s !is AboutScreen) return false
                push(BrickScreen(newBrick()))
            }
            Btn.MENU -> {
                // Holding MENU toggles the light, as on the later models.
                litUntil = if (isLit(now())) 0L else {
                    val secs = prefs.backlightSecs
                    if (secs < 0) Long.MAX_VALUE else now() + maxOf(secs, 10) * 1000L
                }
            }
            Btn.PLAY -> {
                // Hold play: pause and go dark, the closest thing to "off".
                if (link.isPlaying) link.playPause()
                litUntil = 0L
            }
        }
        changed()
        invalidate()
        return true
    }

    /**
     * Radio holds, as on the Radio Remote and the 2009 nano: hold Center to add or remove a
     * favorite, hold Next or Previous to scan (a seek every 5 seconds while held, the nano's
     * five-second preview).
     */
    private fun radioHold(s: RadioScreen, b: Btn): Boolean {
        val t = now()
        when (b) {
            Btn.SELECT -> {
                val k = radio.dialKhz
                val added = radio.toggleFavorite(k)
                s.note = if (added) "Added to Favorites" else "Removed from Favorites"
                s.noteUntil = t + 1600
            }
            Btn.NEXT, Btn.PREV -> {
                if (t - s.lastSeekAt < 5000) return true
                s.lastSeekAt = t
                radio.seek(b == Btn.NEXT)
            }
            else -> return false
        }
        changed(); invalidate()
        return true
    }

    // ---- radio -------------------------------------------------------------------------------

    /** Main menu > Radio: the Radio menu with the tuner screen on top, and the radio on. */
    fun openRadio() {
        skipBoot()
        radio.connect()
        push(radioMenu())
        push(RadioScreen())
        if (radio.antenna && !radio.on) radio.play()
    }

    private fun radioRow() = if (radio.installed) Row("Radio") { openRadio() } else null

    private fun radioMenu() = MenuScreen("Radio", StaticRows {
        listOf(
            if (radio.on) Row("Stop Radio", arrow = false) { radio.stop() }
            else Row("Play Radio", arrow = false) { radio.play(); push(RadioScreen()) },
            Row("Favorites") { push(radioFavorites()) },
            Row("Radio Regions") { push(regionsMenu()) },
        )
    }, split = split())

    private fun radioFavorites(): Screen {
        val favs = radio.favorites
        if (favs.isEmpty()) return MessageScreen("Favorites",
            "No favorites yet. Tune to a station and hold the Center button to add it.")
        return MenuScreen("Favorites", ListRows(favs, { "${fmtMhz(it)} FM" }, false) { i ->
            radio.tune(favs[i])
            if (!radio.on) radio.play()
            push(RadioScreen())
        })
    }

    private fun regionsMenu() = MenuScreen("Radio Regions", StaticRows {
        RadioRegion.entries.map { r ->
            Row(r.label, arrow = false, checked = { radio.region == r }) { prefs.radioRegion = r.name }
        }
    })

    private fun select(s: Screen) {
        when (s) {
            is MenuScreen -> if (s.rows.size > 0) s.rows.select(s.sel)
            is NowPlayingScreen -> {
                npMode = if (npMode == NpMode.SCRUB) NpMode.NORMAL else NpMode.SCRUB
                scrubMs = link.positionMs
                npModeUntil = now() + 4000
            }
            is CoverFlowScreen -> {
                val album = s.albums.getOrNull(s.sel) ?: return
                if (!s.flipped) {
                    s.flipped = true; s.trackSel = 0; s.trackTop = 0
                } else {
                    play(album.songs.map { it.id }, s.trackSel)
                }
            }
            is BrickScreen -> s.game.press()
            is StopwatchScreen -> toggleStopwatch(s)
            else -> {}
        }
    }

    private fun toggleStopwatch(s: StopwatchScreen) {
        val t = now()
        if (s.running) { s.accumulated += t - s.startedAt; s.running = false }
        else { s.startedAt = t; s.running = true }
    }

    fun back(): Boolean {
        val s = top
        if (s is CoverFlowScreen && s.flipped) { s.flipped = false; return true }
        if (s is NowPlayingScreen && npMode != NpMode.NORMAL) { npMode = NpMode.NORMAL; return true }
        if (s is BrickScreen && s.game.score > prefs.brickBest) prefs.brickBest = s.game.score
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        changed()
        (top as? MenuScreen)?.let { m ->
            (m.rows as? StaticRows)?.refresh()
            m.sel = m.sel.coerceIn(0, maxOf(0, m.rows.size - 1))
        }
        return true
    }

    fun toRoot() {
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        ((top as? MenuScreen)?.rows as? StaticRows)?.refresh()
        changed()
        invalidate()
    }

    fun skipBoot() {
        if (top is BootScreen) { stack.removeAt(stack.size - 1); changed() }
    }

    /** Called by the panel's clock. Times out the volume bar and scrub mode. */
    fun tick(t: Long) {
        if (npMode != NpMode.NORMAL && t > npModeUntil) npMode = NpMode.NORMAL
        val s = top
        if (s is BootScreen && t - s.startedAt > BOOT_MS) skipBoot()
    }

    fun push(s: Screen) { stack.add(s); changed(); invalidate() }

    // ---- playback ----------------------------------------------------------------------------

    private fun play(ids: List<Long>, start: Int) {
        if (!link.connected) {
            link.connect()
            push(MessageScreen("${Mascot.musicAppName}", "${Mascot.musicAppName} isn't answering yet. Give it a second and try again."))
            return
        }
        if (link.playIds(ids, start)) push(NowPlayingScreen())
    }

    private fun shuffleSongs() {
        if (!link.connected) {
            link.connect()
            push(MessageScreen("${Mascot.musicAppName}", "${Mascot.musicAppName} isn't answering yet. Give it a second and try again."))
            return
        }
        if (link.shuffleAll()) push(NowPlayingScreen())
    }

    // ---- menus -------------------------------------------------------------------------------

    private fun needLibrary(): Screen? = when {
        !hasPermission -> MessageScreen("Music", "Allow music access to browse your library. Exit and open ${Mascot.name}Pod again to be asked.")
        !libLoaded -> MessageScreen("Music", "Reading your library...")
        lib.songs.isEmpty() -> MessageScreen("Music", "No music found.")
        else -> null
    }

    private fun open(build: () -> Screen) { push(needLibrary() ?: build()) }

    private fun nowPlayingRow() = Row("Now Playing", arrow = true) { push(NowPlayingScreen()) }

    fun rootMenu(): MenuScreen {
        val rows = StaticRows {
            val base = when (era) {
                Era.MONO_2001 -> listOfNotNull(
                    Row("Playlists") { push(playlistsMenu()) },
                    Row("Browse") { push(browseMenu()) },
                    radioRow(),
                    Row("Extras") { push(extrasMenu()) },
                    Row("Settings") { push(settingsMenu()) },
                    Row("Backlight", arrow = false) { onHold(Btn.MENU, null) },
                )
                Era.COLOR_2004 -> listOfNotNull(
                    Row("Music") { push(musicMenu()) },
                    // The gray 2004 models had no Photos menu.
                    if (prefs.color2004) Row("Photos") { push(photosMenu()) } else null,
                    radioRow(),
                    Row("Extras") { push(extrasMenu()) },
                    Row("Settings") { push(settingsMenu()) },
                    Row("Shuffle Songs", arrow = false) { shuffleSongs() },
                    Row("Backlight", arrow = false) { onHold(Btn.MENU, null) },
                )
                Era.VIDEO_2005 -> listOfNotNull(
                    Row("Music") { push(musicMenu()) },
                    Row("Photos") { push(photosMenu()) },
                    Row("Videos") { push(videosMessage()) },
                    radioRow(),
                    Row("Extras") { push(extrasMenu()) },
                    Row("Settings") { push(settingsMenu()) },
                    Row("Shuffle Songs", arrow = false) { shuffleSongs() },
                )
                Era.CLASSIC_2007 -> listOfNotNull(
                    Row("Music") { push(musicMenu()) },
                    Row("Videos") { push(videosMessage()) },
                    Row("Photos") { push(photosMenu()) },
                    Row("Podcasts") { open { podcasts() } },
                    radioRow(),
                    Row("Extras") { push(extrasMenu()) },
                    Row("Settings") { push(settingsMenu()) },
                    Row("Shuffle Songs", arrow = false) { shuffleSongs() },
                )
            }
            if (link.hasItem) base + nowPlayingRow() else base
        }
        return MenuScreen(Brand.NAME, rows, split = era == Era.CLASSIC_2007)
    }

    private fun split() = era == Era.CLASSIC_2007

    private fun browseMenu() = MenuScreen("Browse", StaticRows {
        listOf(
            Row("Artists") { open { artistsMenu(lib.artists, "Artists") } },
            Row("Albums") { open { albumsMenu(lib.albums, "Albums") } },
            Row("Songs") { open { songsMenu("Songs", lib.songs) } },
            Row("Genres") { open { genresMenu() } },
            Row("Composers") { open { composersMenu() } },
        )
    })

    private fun musicMenu() = MenuScreen("Music", StaticRows {
        buildList {
            if (era == Era.CLASSIC_2007) add(Row(COVER_FLOW) { open { coverFlow() } })
            add(Row("Playlists") { push(playlistsMenu()) })
            add(Row("Artists") { open { artistsMenu(lib.artists, "Artists") } })
            add(Row("Albums") { open { albumsMenu(lib.albums, "Albums") } })
            add(Row("Songs") { open { songsMenu("Songs", lib.songs) } })
            add(Row("Genres") { open { genresMenu() } })
            add(Row("Composers") { open { composersMenu() } })
            // With the Radio Remote attached, Radio also showed up under Music.
            if (era == Era.VIDEO_2005 || era == Era.CLASSIC_2007) radioRow()?.let { add(it) }
        }
    }, split = split())

    private fun coverFlow(): Screen {
        // Cover Flow ran through albums by artist, then by album.
        val albums = lib.albums.sortedWith(compareBy({ Library.sortKey(it.artist) }, { it.year }, { Library.sortKey(it.title) }))
        return CoverFlowScreen(albums)
    }

    private fun artistsMenu(groups: List<Group>, title: String): MenuScreen =
        MenuScreen(title, ListRows(groups, { it.name }, true, { it.songs.firstOrNull()?.albumId ?: -1L }) { i ->
            val g = groups[i]
            val albums = lib.albumsOf(g)
            if (albums.size <= 1) {
                push(songsMenu(g.name, g.songs.sortedWith(compareBy({ it.album }, { it.track }))))
            } else {
                push(MenuScreen(g.name, StaticRows {
                    listOf(Row("All") {
                        push(songsMenu(g.name, albums.flatMap { a -> a.songs.filter { it.artist == g.name || a.artist == g.name } }))
                    }) + albums.map { a ->
                        Row(a.title, albumId = a.id) {
                            push(songsMenu(a.title, a.songs.filter { it.artist == g.name }.ifEmpty { a.songs }))
                        }
                    }
                }))
            }
        })

    private fun albumsMenu(albums: List<Album>, title: String): MenuScreen =
        MenuScreen(title, ListRows(albums, { it.title }, true, { it.id }) { i ->
            val a = albums[i]
            push(songsMenu(a.title, a.songs))
        })

    private fun songsMenu(title: String, songs: List<Song>): MenuScreen {
        val ids = songs.map { it.id }
        return MenuScreen(title, ListRows(songs, { it.title }, false, { it.albumId }) { i -> play(ids, i) })
    }

    private fun genresMenu() = MenuScreen("Genres", ListRows(lib.genres, { it.name }, true) { i ->
        val g = lib.genres[i]
        val artists = g.songs.groupBy { it.artist }.map { (k, v) -> Group(k, v) }.sortedBy { Library.sortKey(it.name) }
        push(MenuScreen(g.name, ListRows(artists, { it.name }, true) { j ->
            val a = artists[j]
            push(songsMenu(a.name, a.songs.sortedWith(compareBy({ it.album }, { it.track }))))
        }))
    })

    private fun composersMenu() = MenuScreen("Composers", ListRows(lib.composers, { it.name }, true) { i ->
        val c = lib.composers[i]
        push(songsMenu(c.name, c.songs))
    })

    /** Playlists come from Hione Music's own tree (Liked Songs and friends). */
    private fun playlistsMenu(): Screen {
        val screen = MenuScreen("Playlists", StaticRows { emptyList() })
        if (!link.connected) {
            link.connect()
            return MessageScreen("Playlists", "${Mascot.musicAppName} isn't answering yet. Give it a second and try again.")
        }
        link.children("[playlists]") { items ->
            val rows = items.map { item ->
                Row(item.mediaMetadata.title?.toString() ?: "Playlist") {
                    val id = item.mediaId
                    link.children(id) { tracks ->
                        val ids = tracks.mapNotNull { it.mediaId.toLongOrNull() }
                        val names = tracks.map { it.mediaMetadata.title?.toString() ?: "" }
                        push(MenuScreen(item.mediaMetadata.title?.toString() ?: "Playlist",
                            ListRows(names.indices.toList(), { names[it] }, false,
                                { idx -> lib.song(ids.getOrElse(idx) { -1L })?.albumId ?: -1L }) { k -> play(ids, k) }))
                    }
                }
            }
            val idx = stack.indexOf(screen)
            if (idx >= 0) {
                stack[idx] = MenuScreen("Playlists", StaticRows { rows }, split = false)
                changed()
                invalidate()
            }
        }
        return screen
    }

    private fun photosMenu() = MenuScreen("Photos", StaticRows {
        listOf(
            Row("Photo Library") { open { photoLibrary() } },
            Row("Slideshow Settings") { push(TextScreen("Slideshow", listOf(
                "There are no photos here, only album covers. Photo Library plays them as a slideshow, " +
                    "a few seconds each, with a slow pan.",
                "Press MENU to stop."))) },
        )
    }, split = split())

    private fun videosMessage() = MessageScreen("Videos", "No videos here. ${Brand.NAME} is all about the music.")

    /** Podcasts: whatever MediaStore files under a Podcasts folder or genre. */
    private fun podcasts(): Screen {
        val list = lib.songs.filter { it.genre.equals("Podcast", true) || it.album.contains("podcast", true) }
        return if (list.isEmpty()) MessageScreen("Podcasts", "No podcasts.") else songsMenu("Podcasts", list)
    }

    private fun photoLibrary(): Screen {
        val ids = lib.albums.map { it.id }.shuffled()
        return SlideshowScreen(ids)
    }

    private fun newBrick(): BrickGame {
        val st = style
        return BrickGame(st.w, st.h, st.titleH)
    }

    private fun extrasMenu() = MenuScreen("Extras", StaticRows {
        when (era) {
            Era.MONO_2001 -> listOf(
                Row("Clock") { push(ClockScreen()) },
                Row("Game") { push(BrickScreen(newBrick())) },
            )
            Era.COLOR_2004 -> listOf(
                Row("Clock") { push(ClockScreen()) },
                Row("Games") { push(gamesMenu()) },
            )
            Era.VIDEO_2005 -> listOf(
                Row("Clock") { push(ClockScreen()) },
                Row("Games") { push(gamesMenu()) },
                Row("Stopwatch") { push(StopwatchScreen()) },
            )
            Era.CLASSIC_2007 -> listOf(
                Row("Clocks") { push(ClockScreen()) },
                Row("Games") { push(gamesMenu()) },
                Row("Stopwatch") { push(StopwatchScreen()) },
            )
        }
    }, split = split())

    private fun gamesMenu() = MenuScreen("Games", StaticRows {
        listOf(Row("Brick", arrow = false) { push(BrickScreen(newBrick())) })
    })

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun clickerLabel(): String {
        val r = prefs.clickerRoute(era)
        return if (era == Era.COLOR_2004) r.label else onOff(r != ClickRoute.OFF)
    }

    private fun nextClicker() {
        val choices = ClickRoute.choices(era)
        val cur = choices.indexOf(prefs.clickerRoute(era)).coerceAtLeast(0)
        val r = choices[(cur + 1) % choices.size]
        prefs.setClickerRoute(era, r)
        clicker.route = r
    }

    /**
     * A hardware volume key. Turns the music volume one wheel step, ticks the clicker, and shows
     * the era's volume bar: the Now Playing bar when that screen is up, else a strip at the
     * bottom of the panel. Returns true when the volume changed.
     */
    fun volumeKey(dir: Int): Boolean {
        wake()
        val before = link.volume
        link.adjustVolume(dir * maxOf(1, link.maxVolume / 30))
        val t = now()
        if (top is NowPlayingScreen && npMode != NpMode.SCRUB) {
            npMode = NpMode.VOLUME
            npModeUntil = t + 2000
        } else {
            volumeShownUntil = t + 2000
        }
        val moved = link.volume != before
        if (moved) clicker.soundOnly()
        invalidate()
        return moved
    }

    private fun settingsMenu() = MenuScreen("Settings", StaticRows {
        buildList {
            add(Row("About") { push(AboutScreen()) })
            add(Row("Shuffle", arrow = false, value = { if (link.shuffle) "Songs" else "Off" }) {
                link.setShuffle(!link.shuffle)
            })
            add(Row("Repeat", arrow = false, value = {
                when (link.repeat) { Player.REPEAT_MODE_ONE -> "One"; Player.REPEAT_MODE_ALL -> "All"; else -> "Off" }
            }) {
                link.setRepeat(when (link.repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                })
            })
            add(Row("Backlight Timer") { push(backlightMenu()) })
            if (radio.installed) add(Row("Radio Regions") { push(regionsMenu()) })
            if (era == Era.MONO_2001) add(Row("Contrast", arrow = false, value = {
                when (prefs.contrast) { 1 -> "Low"; 3 -> "High"; else -> "Normal" }
            }) { prefs.contrast = prefs.contrast % 3 + 1 })
            if (era == Era.COLOR_2004) add(Row("Screen", arrow = false, value = {
                if (prefs.color2004) "Color" else "Gray"
            }) { prefs.color2004 = !prefs.color2004 })
            // 2004 offered Off, Speaker, Headphones and Both. The others had On and Off.
            add(Row("Clicker", arrow = false, value = { clickerLabel() }) { nextClicker() })
            // Not on the originals (they had no motor). Pulses also need the system's touch
            // feedback switch on.
            add(Row("Haptics", arrow = false, value = {
                when {
                    !prefs.haptics -> "Off"
                    !clicker.systemHapticsOn() -> "Off (system)"
                    else -> "On"
                }
            }) {
                prefs.haptics = !prefs.haptics; clicker.haptics = prefs.haptics
            })
            add(Row("Legal") { push(legalScreen()) })
        }
    }, split = split())

    private fun backlightMenu() = MenuScreen("Backlight", StaticRows {
        listOf(0 to "Off", 2 to "2 Seconds", 5 to "5 Seconds", 10 to "10 Seconds", 20 to "20 Seconds", -1 to "Always On")
            .map { (secs, label) ->
                Row(label, arrow = false, checked = { prefs.backlightSecs == secs }) {
                    prefs.backlightSecs = secs
                    litUntil = if (secs < 0) Long.MAX_VALUE else now() + maxOf(secs, 2) * 1000L
                }
            }
    })

    private fun legalScreen() = TextScreen("Legal", listOf(
        Brand.TRIBUTE,
        "${Brand.NAME} recreates the look of four click-wheel music players from 2001 to 2007 as a homage. " +
            "All art on these screens was redrawn for ${Mascot.osName}. No original images or fonts are included.",
        "Pixel Operator by Jayvee Enaguas. CC0 1.0, public domain.",
        "PT Sans by ParaType. SIL Open Font License 1.1.",
        "Liberation Sans by Red Hat. SIL Open Font License 1.1.",
        "Full license texts ship inside the app, in assets/licenses.",
        "Music plays through ${Mascot.musicAppName}.",
    ))

    companion object {
        const val BOOT_MS = 2200L
        const val COVER_FLOW = "Cover Flow"
    }
}

object Brand {
    const val NAME = "HionePod"
    const val TRIBUTE = "A fan tribute. Not affiliated with or endorsed by Apple Inc."
}
