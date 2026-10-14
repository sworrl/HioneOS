package com.hione.launcher.lockscreen

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Global Persistent Playback History for Lockscreen & Music Widgets.
 *
 * Tracks the last 25 played tracks with metadata, timestamps, and direct-jump support.
 */
object HionePlayHistoryStore {
    private const val PREFS_NAME = "hione_playback_history_prefs"
    private const val KEY_HISTORY_JSON = "history_json"
    private const val MAX_HISTORY = 25

    data class HistoryEntry(
        val mediaId: String,
        val title: String,
        val artist: String,
        val album: String,
        val format: String,
        val timestampMs: Long,
        val durationMs: Long
    )

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        loadHistory()
    }

    private fun loadHistory() {
        val p = prefs ?: return
        val raw = p.getString(KEY_HISTORY_JSON, null) ?: return
        try {
            val arr = JSONArray(raw)
            val list = mutableListOf<HistoryEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    HistoryEntry(
                        mediaId = obj.optString("id", ""),
                        title = obj.optString("title", "Unknown"),
                        artist = obj.optString("artist", "Unknown"),
                        album = obj.optString("album", ""),
                        // An entry with no stored format has no known format. This used to
                        // re-materialize every such row as a "FLAC 24-bit" quality claim, which the
                        // history drawer then rendered as a green format badge.
                        format = obj.optString("format", ""),
                        timestampMs = obj.optLong("time", System.currentTimeMillis()),
                        durationMs = obj.optLong("dur", 0L)
                    )
                )
            }
            _history.value = list
        } catch (_: Throwable) {}
    }

    /**
     * Records a newly playing track into the history list.
     * Prevents consecutive duplicates.
     */
    fun recordPlay(
        mediaId: String,
        title: String,
        artist: String,
        album: String = "",
        // Blank = format unknown. It used to default to "Direct CS43131 DTA", asserting a bit-perfect
        // hardware path for any caller that omitted it. The renderer hides a blank format badge.
        format: String = "",
        durationMs: Long = 0L
    ) {
        if (title.isBlank()) return
        val current = _history.value.toMutableList()

        // Don't duplicate if identical to top item
        if (current.isNotEmpty() && current.first().title == title && current.first().artist == artist) {
            return
        }

        // Remove older entry of same song if exists
        current.removeAll { it.title == title && it.artist == artist }

        val newEntry = HistoryEntry(
            mediaId = mediaId,
            title = title,
            artist = artist,
            album = album,
            format = format,
            timestampMs = System.currentTimeMillis(),
            durationMs = durationMs
        )
        current.add(0, newEntry)

        val trimmed = current.take(MAX_HISTORY)
        _history.value = trimmed

        // Persist
        val p = prefs ?: return
        try {
            val arr = JSONArray()
            for (item in trimmed) {
                val obj = JSONObject()
                obj.put("id", item.mediaId)
                obj.put("title", item.title)
                obj.put("artist", item.artist)
                obj.put("album", item.album)
                obj.put("format", item.format)
                obj.put("time", item.timestampMs)
                obj.put("dur", item.durationMs)
                arr.put(obj)
            }
            p.edit().putString(KEY_HISTORY_JSON, arr.toString()).apply()
        } catch (_: Throwable) {}
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs?.edit()?.remove(KEY_HISTORY_JSON)?.apply()
    }
}
