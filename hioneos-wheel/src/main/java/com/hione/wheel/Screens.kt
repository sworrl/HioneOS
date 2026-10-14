package com.hione.wheel

/** One row of a menu. [value] is a right-aligned setting value; [checked] draws a check mark. */
class Row(
    val label: String,
    val arrow: Boolean = true,
    val albumId: Long = -1,
    val value: (() -> String)? = null,
    val checked: (() -> Boolean)? = null,
    val action: () -> Unit,
)

/** A menu's rows. Big lists (17k songs) are computed per row, never copied. */
abstract class Rows {
    abstract val size: Int
    abstract fun label(i: Int): String
    open fun arrow(i: Int): Boolean = false
    open fun value(i: Int): String? = null
    open fun checked(i: Int): Boolean = false
    open fun albumId(i: Int): Long = -1
    abstract fun select(i: Int)
}

class StaticRows(private val rows: () -> List<Row>) : Rows() {
    private var cache: List<Row>? = null
    private fun r(): List<Row> = cache ?: rows().also { cache = it }
    /** Rebuild on the next read (used by the main menu, whose Now Playing row comes and goes). */
    fun refresh() { cache = null }
    override val size get() = r().size
    override fun label(i: Int) = r()[i].label
    override fun arrow(i: Int) = r()[i].arrow
    override fun value(i: Int) = r()[i].value?.invoke()
    override fun checked(i: Int) = r()[i].checked?.invoke() == true
    override fun albumId(i: Int) = r()[i].albumId
    override fun select(i: Int) { r().getOrNull(i)?.action?.invoke() }
}

class ListRows<T>(
    private val items: List<T>,
    private val labelOf: (T) -> String,
    private val hasArrow: Boolean,
    private val albumOf: (T) -> Long = { -1L },
    private val onSelect: (Int) -> Unit,
) : Rows() {
    override val size get() = items.size
    override fun label(i: Int) = labelOf(items[i])
    override fun arrow(i: Int) = hasArrow
    override fun albumId(i: Int) = albumOf(items[i])
    override fun select(i: Int) = onSelect(i)
}

sealed class Screen(val title: String)

class MenuScreen(title: String, val rows: Rows, val split: Boolean = false) : Screen(title) {
    var sel = 0
    var top = 0
}

class NowPlayingScreen : Screen("Now Playing")

class CoverFlowScreen(val albums: List<Album>) : Screen("Cover Flow") {
    var sel = 0
    var flipped = false
    var trackSel = 0
    var trackTop = 0
}

class ClockScreen : Screen("Clock")

class StopwatchScreen : Screen("Stopwatch") {
    var running = false
    var startedAt = 0L
    var accumulated = 0L
    fun elapsed(now: Long) = accumulated + if (running) now - startedAt else 0L
}

class BrickScreen(val game: BrickGame) : Screen("Brick")

class AboutScreen : Screen("About")

/** Scrollable text (Legal, notices). Lines are wrapped by the renderer at the era's width. */
class TextScreen(title: String, val paragraphs: List<String>) : Screen(title) {
    var top = 0
    var lineCount = 0
}

class SlideshowScreen(val albumIds: List<Long>) : Screen("Slideshow") {
    val startedAt = System.currentTimeMillis()
}

class BootScreen(val startedAt: Long) : Screen("")

class MessageScreen(title: String, val text: String) : Screen(title)

/** The tuner. [dial] = the radio dial below the frequency, else the station's RDS text. */
class RadioScreen : Screen("Radio") {
    var dial = true
    /** A short note over the screen ("Added to Favorites"), shown until [noteUntil]. */
    var note = ""
    var noteUntil = 0L
    var lastSeekAt = 0L
}
