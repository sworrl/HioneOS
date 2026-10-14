package com.hione.settings.pages

import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.hione.settings.ui.*

/**
 * Home screen (route "home_layout", or intent action com.hione.settings.action.HOME_LAYOUT; the
 * launcher's home long-press menu > Home settings opens it).
 *
 * Writes the Settings.Global keys the launcher's HioneDeck reads (hioneos-launcher
 * ui/HioneDeck.kt, keep the two in sync):
 *   hione_deck_mode         top (default) | pullup | autopop | pinned
 *   hione_deck_pop_unlock   1 (default) / 0    unlock or screen on
 *   hione_deck_pop_track    1 (default) / 0    a new track starts
 *   hione_deck_pop_charger  1 (default) / 0    charger plugged in
 *   hione_deck_pop_weather  1 (default) / 0    a new weather alert
 *   hione_deck_pop_battery  1 (default) / 0    battery low
 *   hione_deck_pop_secs     3 | 5 (default) | 8
 * The launcher watches them, so a change shows on the home screen right away.
 */
private object DeckKeys {
    const val MODE = "hione_deck_mode"
    const val POP_UNLOCK = "hione_deck_pop_unlock"
    const val POP_TRACK = "hione_deck_pop_track"
    const val POP_CHARGER = "hione_deck_pop_charger"
    const val POP_WEATHER = "hione_deck_pop_weather"
    const val POP_BATTERY = "hione_deck_pop_battery"
    const val POP_SECS = "hione_deck_pop_secs"
}

@Composable
fun HomeLayoutPage() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    fun read(k: String): String? = runCatching { Settings.Global.getString(cr, k) }.getOrNull()
    fun write(k: String, v: String): Boolean {
        val ok = runCatching { Settings.Global.putString(cr, k, v) }.getOrDefault(false)
        if (!ok) toast(ctx, "That setting was refused")
        return ok
    }
    var mode by remember { mutableStateOf(read(DeckKeys.MODE)?.takeIf { it in setOf("top", "pullup", "autopop", "pinned") } ?: "top") }
    var secs by remember { mutableStateOf(read(DeckKeys.POP_SECS)?.toIntOrNull()?.takeIf { it in setOf(3, 5, 8) } ?: 5) }
    fun setMode(v: String) { if (write(DeckKeys.MODE, v)) mode = v }
    val autoPop = mode == "autopop"

    PageList {
        item {
            Section("Status card", note = "The clock, date, status chips, weather and controls card on the home screen.") {
                RadioRow("At the top", "Under the status bar, as before", mode == "top") { setMode("top") }
                RadioRow("Pull up", "Hidden so the whole wallpaper shows. Tap or drag up the handle above the dock to see it.", mode == "pullup") { setMode("pullup") }
                RadioRow("Pull up and pop up", "Hidden, and shows itself for a few seconds when something happens", mode == "autopop") { setMode("autopop") }
                RadioRow("Pinned above the dock", "Always shown, just above the dock", mode == "pinned") { setMode("pinned") }
            }
        }
        item {
            Section("Pop up when", note = if (autoPop) null else "Used by Pull up and pop up.") {
                PopToggle("Unlock or screen on", null, DeckKeys.POP_UNLOCK, autoPop, ::read, ::write)
                PopToggle("New track", "A different song starts playing", DeckKeys.POP_TRACK, autoPop, ::read, ::write)
                PopToggle("Charger plugged in", null, DeckKeys.POP_CHARGER, autoPop, ::read, ::write)
                PopToggle("Weather alert", "A new alert for where you are", DeckKeys.POP_WEATHER, autoPop, ::read, ::write)
                PopToggle("Low battery", null, DeckKeys.POP_BATTERY, autoPop, ::read, ::write)
            }
        }
        item {
            Section("Stay up for", note = if (autoPop) null else "Used by Pull up and pop up.") {
                listOf(3, 5, 8).forEach { n ->
                    RadioRow("$n seconds", null, secs == n) {
                        if (write(DeckKeys.POP_SECS, n.toString())) secs = n
                    }
                }
            }
        }
        item {
            Section {
                BodyText("Swipe up on the home screen or the dock still opens the app drawer. " +
                    "To hide the card, drag it down, tap outside it or press back.")
            }
        }
    }
}

@Composable
private fun PopToggle(
    title: String,
    summary: String?,
    key: String,
    enabled: Boolean,
    read: (String) -> String?,
    write: (String, String) -> Boolean,
) {
    var on by remember(key) { mutableStateOf(read(key) != "0") }
    ToggleRow(title, summary, on, enabled = enabled) { if (write(key, if (it) "1" else "0")) on = it }
}
