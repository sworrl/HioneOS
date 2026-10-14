package com.hione.riot

import com.hione.riot.Lcd.Companion.CLEAR
import com.hione.riot.Lcd.Companion.GRAY
import com.hione.riot.Lcd.Companion.INK
import com.hione.riot.Lcd.Companion.LIGHT
import com.hione.riot.Lcd.Companion.W

/**
 * The firmware's widgets, recreated from the user guide's screen drawings (pages 18 to 32) and
 * the 2002 TechTV footage. Coordinates are LCD pixels on the 240 x 160 panel.
 */
object RiotUi {
    // Menu tabs, measured off the TechTV frame of the main menu: header tab further left, items
    // to the right, the highlighted item sticks out left and goes bold on a clear background.
    const val TAB_H = 16
    const val TAB_PITCH = 19
    const val TAB_X_HEADER = 122
    const val TAB_X_ITEM = 150
    const val TAB_X_SEL = 141

    // Breadcrumb tabs on full-screen pages ("Preferences" over "Contrast").
    const val CRUMB_H = 12
    const val PANEL_Y = 26

    val tabFont get() = RiotAssets.medium
    val font get() = RiotAssets.small

    enum class Tab { HEADER, ITEM, SELECTED }

    /** One menu tab, rounded on the left, running off the right edge. */
    fun Lcd.tab(x: Int, y: Int, label: String, style: Tab, h: Int = TAB_H, f: BitmapFont = tabFont) {
        val ty = y + (h - f.height) / 2 + 1
        when (style) {
            Tab.HEADER -> {
                round(x, y, W - x + 1, h, h / 2, fill = INK, line = INK, openRight = true)
                text(f, x + 7, ty, label, CLEAR, bold = true)
            }
            Tab.ITEM -> {
                round(x, y, W - x + 1, h, h / 2, fill = LIGHT, line = INK, openRight = true)
                text(f, x + 7, ty, label, INK)
            }
            Tab.SELECTED -> {
                round(x, y, W - x + 1, h, h / 2, fill = CLEAR, line = INK, openRight = true)
                text(f, x + 7, ty, label, INK, bold = true)
            }
        }
    }

    /** The two-tab trail at the top of a full page. The last tab is bold. */
    fun Lcd.crumbs(parent: String?, child: String) {
        val f = font
        if (parent != null) {
            round(0, 0, W + 1, CRUMB_H, CRUMB_H / 2, fill = CLEAR, line = INK, openRight = true)
            text(f, 6, 0, parent, INK, bold = true)
        }
        val y = if (parent != null) CRUMB_H + 1 else 0
        round(8, y, W - 7, CRUMB_H, CRUMB_H / 2, fill = CLEAR, line = INK, openRight = true)
        text(f, 14, y, child, INK, bold = true)
    }

    /** A rounded content box. */
    fun Lcd.panel(x: Int, y: Int, w: Int, h: Int) = round(x, y, w, h, 5, fill = CLEAR, line = INK)

    /** Bold title with a rule under it, inside a panel. Returns the y below the rule. */
    fun Lcd.panelTitle(x: Int, y: Int, w: Int, title: String, right: String? = null): Int {
        text(RiotAssets.medium, x + 5, y + 2, title, INK, bold = true)
        if (right != null) textRight(font, x + w - 6, y + 3, right)
        hline(x + 3, x + w - 4, y + 15)
        return y + 17
    }

    /**
     * A scrolling list with the firmware's highlight: a dark rounded bar, text knocked out of it.
     * [boldRows] are drawn bold (the "Play All ..." row). Returns nothing; [first] is the top row.
     */
    fun Lcd.list(
        x: Int, y: Int, w: Int, rows: Int, pitch: Int, items: List<String>, sel: Int, first: Int,
        boldRows: Set<Int> = emptySet(), checks: Set<Int> = emptySet(), f: BitmapFont = font,
        showSel: Boolean = true, scrollbar: Boolean = true
    ) {
        val textW = w - (if (scrollbar) 10 else 2)
        for (r in 0 until rows) {
            val i = first + r
            if (i >= items.size) break
            val ry = y + r * pitch
            val tx = x + 4 + if (checks.isNotEmpty()) 8 else 0
            val selected = showSel && i == sel
            if (selected) round(x, ry, textW, pitch - 1, (pitch - 1) / 2, fill = INK, line = INK)
            val lvl = if (selected) CLEAR else INK
            if (i in checks) sprite(RiotAssets.icons["check"], x + 3, ry + (pitch - 7) / 2, lvl)
            textClip(f, tx, ry + (pitch - 1 - f.height) / 2 + 1, textW - (tx - x) - 4, items[i], lvl, bold = i in boldRows)
        }
        if (scrollbar) scrollbar(x + w - 7, y, rows * pitch - 1, first, rows, items.size)
    }

    /** Thin track with a small capsule thumb, as in the guide's drawings. */
    fun Lcd.scrollbar(x: Int, y: Int, h: Int, first: Int, visible: Int, total: Int) {
        vline(x + 2, y + 1, y + h - 2)
        val thumbH = 7
        val travel = h - thumbH
        val pos = if (total <= visible) 0 else (travel * first / (total - visible).coerceAtLeast(1)).coerceIn(0, travel)
        round(x, y + pos, 5, thumbH, 2, fill = CLEAR, line = INK)
    }

    /** Keeps [sel] on screen; returns the new first row. */
    fun follow(sel: Int, first: Int, rows: Int, total: Int): Int {
        var f = first
        if (sel < f) f = sel
        if (sel >= f + rows) f = sel - rows + 1
        return f.coerceIn(0, maxOf(0, total - rows))
    }

    /**
     * A rounded value box ("15 minutes"). Focused: double outline. Editing: blinks, which is what
     * the guide describes ("the box will blink to indicate that it is selected").
     */
    fun Lcd.valueBox(x: Int, y: Int, w: Int, label: String, focused: Boolean, editing: Boolean, blink: Boolean): Int {
        val h = 13
        val inverted = editing && blink
        round(x, y, w, h, 6, fill = if (inverted) INK else CLEAR, line = INK)
        if (focused && !editing) round(x + 1, y + 1, w - 2, h - 2, 5, fill = null, line = INK)
        textCenter(font, x + w / 2, y + 1, label, if (inverted) CLEAR else INK, bold = true)
        return x + w
    }

    fun boxWidth(label: String, min: Int = 0): Int = maxOf(min, Lcd.measure(RiotAssets.small, label, true) + 16)

    /** Done / Cancel style buttons stacked at the bottom right, running off the panel's edge. */
    fun Lcd.sideButtons(labels: List<String>, sel: Int, bottom: Int, xLeft: Int = 172) {
        val h = 13
        labels.forEachIndexed { i, s ->
            val y = bottom - (labels.size - i) * (h + 2)
            val x = if (i == sel) xLeft - 6 else xLeft
            round(x, y, W - x + 1, h, 6, fill = if (i == sel) CLEAR else LIGHT, line = INK, openRight = true)
            text(font, x + 8, y + 1, s, INK, bold = i == sel)
        }
    }

    /** Vertical slider with a value bubble, for Bass, Treble and Contrast. */
    fun Lcd.vslider(x: Int, top: Int, h: Int, value: Int, min: Int, max: Int, focused: Boolean, editing: Boolean, blink: Boolean) {
        round(x - 2, top, 5, h, 2, fill = CLEAR, line = INK)
        val t = (value - min).toFloat() / (max - min)
        val ky = top + ((1f - t) * (h - 11)).toInt()
        val label = if (min < 0 && value > 0) "+$value" else "$value"
        val bw = maxOf(15, width(RiotAssets.tiny, label) + 8)
        val inv = editing && blink
        round(x - bw / 2, ky, bw, 11, 5, fill = if (inv) INK else CLEAR, line = INK)
        if (focused && !editing) round(x - bw / 2 + 1, ky + 1, bw - 2, 9, 4, fill = null, line = INK)
        textCenter(RiotAssets.tiny, x + 1, ky + 3, label, if (inv) CLEAR else INK)
    }

    /** Ten-segment volume bar after a speaker icon. Empty segments are gray, as on the real screen. */
    fun Lcd.volume(x: Int, y: Int, level: Int, max: Int) {
        sprite(RiotAssets.icons["speaker"], x, y + 1)
        val segs = 10
        val lit = if (max <= 0) 0 else ((level * segs + max - 1) / max).coerceIn(0, segs)
        for (i in 0 until segs) fill(x + 14 + i * 5, y, 3, 11, if (i < lit) INK else LIGHT)
    }

    /** Small inverse tag ("RND", "TUNED"). Returns the x after it. */
    fun Lcd.tag(x: Int, y: Int, s: String): Int {
        val w = width(RiotAssets.tiny, s) + 4
        fill(x, y, w, 7, INK)
        text(RiotAssets.tiny, x + 2, y + 1, s, CLEAR)
        return x + w
    }

    /**
     * "E [####] F" battery gauge, from the firmware's own gauge images (icons/battery_*.png):
     * four fill levels, a "LOW" cell under 15 percent, and the "CHARGE" cell while on external
     * power and still filling. The USB plug sits left of the gauge while a computer is connected,
     * as in the guide's page 18 drawing. The level thresholds are not known; these split the
     * range evenly.
     */
    fun Lcd.battery(xRight: Int, y: Int, b: RiotSystem.Battery) {
        val name = when {
            b.charging -> "battery_charging"
            b.plugged -> "battery_100"
            b.pct < 15 -> "battery_low"
            b.pct >= 88 -> "battery_100"
            b.pct >= 63 -> "battery_75"
            b.pct >= 38 -> "battery_50"
            else -> "battery_25"
        }
        val g = RiotAssets.icons[name] ?: RiotAssets.icons["battery_100"] ?: return
        val gx = xRight - g.w + 1
        sprite(g, gx, y)
        if (b.usb) RiotAssets.icons["usb"]?.let { sprite(it, gx - it.w - 3, y + 2) }
    }

    /** A centered message box. Lines wrap. */
    fun Lcd.message(lines: String, title: String? = null) {
        val f = font
        val maxW = 190
        val wrapped = wrap(f, lines, maxW - 16)
        val h = wrapped.size * 11 + 12 + if (title != null) 17 else 0
        val y = (Lcd.H - h) / 2
        val x = (W - maxW) / 2
        round(x - 1, y - 1, maxW + 2, h + 2, 6, fill = CLEAR, line = CLEAR)
        panel(x, y, maxW, h)
        var ty = y + 6
        if (title != null) ty = panelTitle(x, y, maxW, title) + 2
        for (l in wrapped) { textCenter(f, x + maxW / 2, ty, l); ty += 11 }
    }

    @Suppress("unused") private const val keepGray = GRAY
}
