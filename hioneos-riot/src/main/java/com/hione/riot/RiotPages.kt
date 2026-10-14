package com.hione.riot

import com.hione.riot.Lcd.Companion.CLEAR
import com.hione.riot.Lcd.Companion.H
import com.hione.riot.Lcd.Companion.INK
import com.hione.riot.Lcd.Companion.W
import com.hione.riot.RiotUi.crumbs
import com.hione.riot.RiotUi.list
import com.hione.riot.RiotUi.panel
import com.hione.riot.RiotUi.panelTitle
import com.hione.riot.RiotUi.sideButtons
import com.hione.riot.RiotUi.tab
import com.hione.riot.RiotUi.valueBox
import com.hione.riot.RiotUi.vslider

enum class Key { MENU, SELECT, BACK, PLAY, STOP, FWD, REW, VOL_UP, VOL_DOWN }

/** One screen on the stack. Menus are overlays drawn over the dimmed home screen. */
abstract class Page {
    open val overlay: Boolean = false
    abstract fun draw(c: Lcd, t: Long)
    open fun wheel(d: Int) {}
    /** Return false to let the shell apply its default (BACK pops). */
    open fun key(k: Key): Boolean = false
    lateinit var shell: RiotShell
}

// ---- menus -------------------------------------------------------------------------------------

class MenuItem(val label: String, val action: () -> Unit)

/**
 * The tab menu. The first menu opened from the home screen slides in from the right over the
 * dimmed screen, which is what the guide describes; deeper menus swap in place.
 */
class MenuPage(val title: String, private val itemsFn: () -> List<MenuItem>, private val slide: Boolean = false) : Page() {
    override val overlay = true
    private var items = itemsFn()
    var sel = 0
    private var first = 0
    private var openedAt = -1L

    fun refresh() { items = itemsFn(); sel = sel.coerceIn(0, maxOf(0, items.size - 1)) }

    override fun draw(c: Lcd, t: Long) {
        if (openedAt < 0) openedAt = t
        val p = if (slide) ((t - openedAt) / 140f).coerceIn(0f, 1f) else 1f
        val off = ((1f - p) * (W - RiotUi.TAB_X_HEADER)).toInt()
        if (p < 1f) shell.animating = true
        val maxRows = (H - 2) / RiotUi.TAB_PITCH - 1
        first = RiotUi.follow(sel, first, maxRows, items.size)
        c.tab(RiotUi.TAB_X_HEADER + off, 1, title, RiotUi.Tab.HEADER)
        for (r in 0 until minOf(maxRows, items.size - first)) {
            val i = first + r
            val y = 1 + (r + 1) * RiotUi.TAB_PITCH
            if (i == sel) c.tab(RiotUi.TAB_X_SEL + off, y, items[i].label, RiotUi.Tab.SELECTED)
            else c.tab(RiotUi.TAB_X_ITEM + off, y, items[i].label, RiotUi.Tab.ITEM)
        }
    }

    override fun wheel(d: Int) { if (items.isNotEmpty()) sel = (sel + d).coerceIn(0, items.size - 1) }

    override fun key(k: Key): Boolean {
        if (k == Key.SELECT && items.isNotEmpty()) { items[sel].action(); return true }
        return false
    }
}

// ---- lists ------------------------------------------------------------------------------------

class Row(val label: String, val bold: Boolean = false, val action: (() -> Unit)? = null)

/** A full page: two crumb tabs, a panel, a bold header line and a list. */
open class ListPage(
    private val parent: String?,
    private val crumb: String,
    private val header: () -> String,
    private val rowsFn: () -> List<Row>,
    private val checked: (() -> Set<Int>)? = null,
    private val topRight: (() -> String?)? = null
) : Page() {
    var sel = 0
    private var first = 0
    protected var rows = rowsFn()

    fun reload() { rows = rowsFn(); sel = sel.coerceIn(0, maxOf(0, rows.size - 1)) }

    override fun draw(c: Lcd, t: Long) {
        c.crumbs(parent, crumb)
        topRight?.invoke()?.let { c.textRight(RiotAssets.small, W - 2, 0, it) }
        c.panel(0, RiotUi.PANEL_Y, W, H - RiotUi.PANEL_Y)
        val y0 = c.panelTitleSmall(0, RiotUi.PANEL_Y, W, header())
        val visible = (H - 3 - y0) / PITCH
        first = RiotUi.follow(sel, first, visible, rows.size)
        c.list(3, y0, W - 6, visible, PITCH, rows.map { it.label }, sel, first,
            boldRows = rows.indices.filter { rows[it].bold }.toSet(), checks = checked?.invoke() ?: emptySet())
    }

    override fun wheel(d: Int) { if (rows.isNotEmpty()) sel = (sel + d).coerceIn(0, rows.size - 1) }

    override fun key(k: Key): Boolean {
        if (k == Key.SELECT) { rows.getOrNull(sel)?.action?.invoke(); return true }
        return false
    }

    companion object { const val PITCH = 12 }
}

/** Small bold panel header with a rule, the style of "9 Albums Match "B"". */
fun Lcd.panelTitleSmall(x: Int, y: Int, w: Int, title: String): Int {
    textClip(RiotAssets.small, x + 6, y + 2, w - 12, title, INK, bold = true)
    hline(x + 3, x + w - 4, y + 14)
    return y + 17
}

/**
 * Albums, Artists and Songs: pick a first letter on the left, then an item on the right.
 * [mode] decides what picking does: play, add to a playlist, or delete.
 */
class LetterPage(
    private val parent: String,
    private val kind: Kind,
    private val lib: RiotLibrary,
    private val mode: Mode,
    private val topRight: (() -> String?)? = null,
    private val onPick: (LetterPage, Any) -> Unit
) : Page() {
    enum class Kind { ALBUMS, ARTISTS, SONGS }
    enum class Mode { PLAY, ADD, DELETE }

    private val letters = ('A'..'Z').toList() + '#'
    private var li = 0
    private var inList = false
    private var sel = 0
    private var first = 0
    private var items: List<Any> = emptyList()

    init {
        // Start on the first letter that has anything.
        li = letters.indexOfFirst { matches(it).isNotEmpty() }.coerceAtLeast(0)
        items = matches(letters[li])
    }

    private fun nameOf(o: Any): String = when (o) {
        is Album -> o.name; is Artist -> o.name; is Song -> o.title; else -> o.toString()
    }

    private fun matches(l: Char): List<Any> = when (kind) {
        Kind.ALBUMS -> lib.albums.filter { lib.letterOf(it.name) == l }
        Kind.ARTISTS -> lib.artists.filter { lib.letterOf(it.name) == l }
        Kind.SONGS -> lib.songs.filter { lib.letterOf(it.title) == l }
    }

    private val noun get() = when (kind) { Kind.ALBUMS -> "Albums"; Kind.ARTISTS -> "Artists"; Kind.SONGS -> "Songs" }

    /** The "Play All" row each list starts with (Artists start straight with names). */
    private val allRow: String? get() = when {
        mode == Mode.DELETE -> null
        kind == Kind.ALBUMS -> if (mode == Mode.ADD) "Add All Albums/Tracks" else "Play All Albums/Tracks"
        kind == Kind.SONGS -> if (mode == Mode.ADD) "Add All Tracks" else "Play All Tracks"
        else -> null
    }

    private fun rowLabels(): List<String> = listOfNotNull(allRow) + items.map { "•" + lib.display(nameOf(it)) }

    override fun draw(c: Lcd, t: Long) {
        c.crumbs(parent, noun)
        topRight?.invoke()?.let { c.textRight(RiotAssets.small, W - 2, 0, it) }
        // Letter column, the selected letter circled.
        val pitch = 12
        val vis = (H - RiotUi.PANEL_Y) / pitch
        val lFirst = (li - vis / 2).coerceIn(0, maxOf(0, letters.size - vis))
        for (r in 0 until vis) {
            val i = lFirst + r
            if (i >= letters.size) break
            val y = RiotUi.PANEL_Y + 1 + r * pitch
            if (i == li) c.round(0, y - 1, 13, 12, 5, fill = CLEAR, line = INK)
            c.textCenter(RiotAssets.small, 7, y, letters[i].toString(), INK, bold = i == li && !inList)
        }
        val x0 = 15
        c.panel(x0, RiotUi.PANEL_Y, W - x0, H - RiotUi.PANEL_Y)
        val count = items.size
        val header = "$count $noun Match \"${letters[li]}\""
        val y0 = c.panelTitleSmall(x0, RiotUi.PANEL_Y, W - x0, header)
        val labels = rowLabels()
        val visible = (H - 3 - y0) / ListPage.PITCH
        first = RiotUi.follow(sel, first, visible, labels.size)
        val bold = if (allRow != null) setOf(0) else emptySet()
        c.list(x0 + 3, y0, W - x0 - 6, visible, ListPage.PITCH, labels, sel, if (inList) first else 0,
            boldRows = bold, showSel = inList)
    }

    override fun wheel(d: Int) {
        if (!inList) {
            li = (li + d).coerceIn(0, letters.size - 1)
            items = matches(letters[li])
            sel = 0; first = 0
        } else {
            val n = rowLabels().size
            if (n > 0) sel = (sel + d).coerceIn(0, n - 1)
        }
    }

    override fun key(k: Key): Boolean {
        when (k) {
            Key.SELECT -> {
                if (!inList) {
                    if (rowLabels().isNotEmpty()) { inList = true; sel = 0; first = 0 }
                } else {
                    val off = if (allRow != null) 1 else 0
                    if (sel < off) onPick(this, items) else items.getOrNull(sel - off)?.let { onPick(this, it) }
                }
                return true
            }
            Key.BACK -> {
                if (inList) { inList = false; return true }
                return false
            }
            else -> return false
        }
    }

    fun refresh() { items = matches(letters[li]); sel = sel.coerceIn(0, maxOf(0, rowLabels().size - 1)) }
}

// ---- dialogs ------------------------------------------------------------------------------------

/** A value picked by scrolling, shown in a rounded box. */
class Choice(val options: List<String>, var index: Int, val editable: Boolean = true, val minW: Int = 0) {
    val label get() = options[index]
}

sealed class DRow {
    class Text(val s: String) : DRow()
    /** prefix [box] [box] ... suffix */
    class Boxes(val prefix: String, val boxes: List<Choice>, val suffix: String = "") : DRow()
}

/**
 * The firmware's dialog: crumbs, a panel with a bold title and rule, lines of text with value
 * boxes in them, and Done / Cancel tabs at the bottom right. Scroll moves between boxes and
 * buttons; SELECT on a box makes it blink and the wheel changes it; SELECT again sets it.
 */
open class DialogPage(
    private val parent: String?,
    private val crumb: String,
    private val title: String,
    private val rows: List<DRow>,
    private val buttons: List<String> = listOf("Done", "Cancel"),
    private val onButton: (DialogPage, Int) -> Unit
) : Page() {
    private val boxes = rows.flatMap { (it as? DRow.Boxes)?.boxes ?: emptyList() }.filter { it.editable }
    private var focus = 0
    private var editing = false
    var titleRight: String? = null
    var isExit = false

    override fun draw(c: Lcd, t: Long) {
        c.crumbs(parent, crumb)
        c.panel(0, RiotUi.PANEL_Y, W, H - RiotUi.PANEL_Y)
        var y = c.panelTitle(0, RiotUi.PANEL_Y, W, title, titleRight) + 3
        val f = RiotAssets.small
        val blink = (t / 300) % 2 == 0L
        for (r in rows) when (r) {
            is DRow.Text -> {
                for (line in c.wrap(f, r.s, 155)) { c.text(f, 7, y, line); y += 12 }
            }
            is DRow.Boxes -> {
                var x = 7
                if (r.prefix.isNotEmpty()) x = c.text(f, x, y + 1, r.prefix) + 4
                for (b in r.boxes) {
                    val bi = boxes.indexOf(b)
                    val w = maxOf(b.minW, b.options.maxOf { Lcd.measure(f, it, true) } + 16)
                    x = c.valueBox(x, y, w, b.label, focused = bi == focus && bi >= 0, editing = editing && bi == focus, blink = blink) + 4
                }
                if (r.suffix.isNotEmpty()) c.text(f, x, y + 1, r.suffix)
                y += 16
            }
        }
        val bsel = focus - boxes.size
        c.sideButtons(buttons, bsel, H - 4)
    }

    override fun wheel(d: Int) {
        if (editing) {
            val b = boxes[focus]
            b.index = (b.index + d).coerceIn(0, b.options.size - 1)
            onChange(b)
        } else {
            focus = (focus + d).coerceIn(0, boxes.size + buttons.size - 1)
        }
    }

    open fun onChange(b: Choice) {}

    override fun key(k: Key): Boolean {
        when (k) {
            Key.SELECT -> {
                if (focus < boxes.size) editing = !editing
                else onButton(this, focus - boxes.size)
                return true
            }
            Key.BACK -> {
                if (editing) { editing = false; return true }
                return false
            }
            else -> return false
        }
    }
}

/** Slider pages: Contrast (one slider, live) and Bass/Treble (two). */
class SliderPage(
    private val parent: String,
    private val crumb: String,
    private val title: String,
    private val labels: List<String>,
    private val values: IntArray,
    private val min: Int,
    private val max: Int,
    private val onLive: (IntArray) -> Unit,
    private val onDone: (IntArray?) -> Unit
) : Page() {
    private var focus = 0
    private var editing = false

    override fun draw(c: Lcd, t: Long) {
        c.crumbs(parent, crumb)
        c.panel(0, RiotUi.PANEL_Y, W, H - RiotUi.PANEL_Y)
        c.panelTitleSmall(0, RiotUi.PANEL_Y, W, title)
        val top = RiotUi.PANEL_Y + 22
        val h = 92
        val blink = (t / 300) % 2 == 0L
        val tiny = RiotAssets.tiny
        if (min < 0) {
            // Bass/Treble: "dB" at left, a +6 / 0 / -6 scale between the two sliders.
            c.text(RiotAssets.small, 8, top + h / 2 - 5, "dB")
            c.text(tiny, 62, top + 3, "+6"); c.text(tiny, 64, top + h / 2 - 2, "0"); c.text(tiny, 62, top + h - 8, "-6")
        } else {
            // Contrast: the bar stack beside the slider shows the ink at the chosen level.
            for (i in 0 until 7) c.round(60, top + 6 + i * 12, 22, 8, 3, fill = INK, line = INK)
        }
        labels.forEachIndexed { i, l ->
            val x = if (labels.size == 1) 34 else 36 + i * 54
            c.vslider(x, top, h, values[i], min, max, focus == i, editing && focus == i, blink)
            c.textCenter(RiotAssets.small, x, top + h + 2, l)
        }
        c.sideButtons(listOf("Done", "Cancel"), focus - labels.size, H - 4)
    }

    override fun wheel(d: Int) {
        if (editing) {
            // Up the wheel is up the slider.
            values[focus] = (values[focus] - d).coerceIn(min, max)
            onLive(values)
        } else focus = (focus + d).coerceIn(0, labels.size + 1)
    }

    override fun key(k: Key): Boolean {
        when (k) {
            Key.SELECT -> {
                if (focus < labels.size) editing = !editing
                else { onDone(if (focus == labels.size) values else null) }
                return true
            }
            Key.BACK -> { if (editing) { editing = false; return true }; onDone(null); return true }
            else -> return false
        }
    }
}

/** The playlist name matrix from the guide, page 23. */
class NameEntryPage(
    initial: String,
    private val onDone: (String?) -> Unit
) : Page() {
    private var name = initial
    private var upper = false
    private var sel = 0

    private val lowerCols = listOf("abcdef", "ghijkl", "mnopqr", "stuvwx", "yz()!@", "#$%^&*", "/?|-\\ ")
    private val upperCols = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789", ".,':_ ")
    private val cols get() = if (upper) upperCols else lowerCols
    // 42 cells, then the case toggle, Done and Cancel.
    private val count = 42 + 3

    override fun draw(c: Lcd, t: Long) {
        c.panel(0, 0, W, H)
        val f = RiotAssets.small
        var x = c.text(f, 6, 3, "Playlist Name:", INK, bold = true) + 4
        x = c.text(f, x, 3, name)
        if ((t / 400) % 2 == 0L) c.hline(x + 1, x + 6, 13)
        c.hline(3, W - 4, 17)
        cols.forEachIndexed { ci, col ->
            col.forEachIndexed { ri, ch ->
                val i = ci * 6 + ri
                val cx = 12 + ci * 20
                val cy = 22 + ri * 22
                val label = if (ch == ' ' && ci == 6) "space" else ch.toString()
                val w = Lcd.measure(f, label) + 8
                val bx = if (label.length > 1) cx - 4 else cx - w / 2
                if (i == sel) c.round(bx, cy - 1, w, 13, 6, fill = INK, line = INK)
                c.text(f, bx + 4, cy, label, if (i == sel) CLEAR else INK)
            }
        }
        // Case toggle at top right, Done / Cancel below.
        val toggle = if (upper) "Uppercase" else "Lowercase"
        val tw = Lcd.measure(f, toggle, true) + 14
        val tx = W - tw - 4
        c.round(tx, 22, tw, 14, 6, fill = if (sel == 42) INK else CLEAR, line = INK)
        c.text(f, tx + 7, 23, toggle, if (sel == 42) CLEAR else INK, bold = true)
        c.sideButtons(listOf("Done", "Cancel"), sel - 43, H - 4, xLeft = 180)
    }

    override fun wheel(d: Int) { sel = (sel + d).coerceIn(0, count - 1) }

    override fun key(k: Key): Boolean {
        when (k) {
            Key.SELECT -> {
                when {
                    sel < 42 -> if (name.length < 24) name += cols[sel / 6][sel % 6]
                    sel == 42 -> upper = !upper
                    sel == 43 -> onDone(name.trim().ifEmpty { null })
                    else -> onDone(null)
                }
                return true
            }
            // BACK rubs out the last letter; on an empty name it backs out.
            Key.BACK -> { if (name.isNotEmpty()) { name = name.dropLast(1); return true }; onDone(null); return true }
            else -> return false
        }
    }
}

/** Preferences > Information. Scrolls, since the tribute line is longer than the original list. */
class InfoPage(private val lines: () -> Pair<String, List<String>>) : Page() {
    private var first = 0
    private var content: Pair<String, List<String>> = lines()

    override fun draw(c: Lcd, t: Long) {
        c.crumbs("Preferences", "Information")
        c.panel(0, RiotUi.PANEL_Y, W, H - RiotUi.PANEL_Y)
        val f = RiotAssets.small
        val y0 = c.panelTitleSmall(0, RiotUi.PANEL_Y, W, "Rio Riot")
        c.textRight(f, W - 7, RiotUi.PANEL_Y + 2, content.first)
        val wrapped = content.second.flatMap { c.wrap(f, it, 220) }
        val vis = 8
        first = first.coerceIn(0, maxOf(0, wrapped.size - vis))
        c.clip(0, y0, W, H - 18)
        for (i in 0 until vis) {
            val l = wrapped.getOrNull(first + i) ?: break
            c.text(f, 7, y0 + i * 11, l)
        }
        c.unclip()
        c.sideButtons(listOf("Done"), 0, H - 3)
    }

    override fun wheel(d: Int) { first = (first + d).coerceAtLeast(0); content = lines() }

    override fun key(k: Key): Boolean {
        if (k == Key.SELECT) { shell.pop(); return true }
        return false
    }
}
