package com.hione.player.discsplit

import android.content.Context
import android.util.Log
import com.hione.player.DiscImage
import com.hione.player.Track
import java.io.File

/**
 * Per-album MANUAL override: a `tracks.txt` the user drops beside a whole-disc image. Registered
 * before [MusicBrainzSplitter] so a hand-written list always beats an online guess (and works
 * offline). One track per line; `#` lines are comments. Two layouts, not mixed in one file:
 *
 *   0:00 Intro                 ← leading time = START offset (YouTube-chapter style, h:mm:ss ok)
 *   4:12 Second Song
 *   01. Intro (4:12)           ← trailing time = DURATION; starts are accumulated from 0
 *   02. Second Song 5:03
 *
 * A leading "01." / "1)" / "01 -" is the track number. Duration lists must add up to the file
 * length within 3 % (back-cover timings are rounded), otherwise the file is ignored with a log line.
 */
object TracksTxtSplitter : DiscImage.WholeDiscSplitter {
    override val name = "tracks.txt"
    private const val TAG = "TracksTxtSplitter"
    const val FILE_NAME = "tracks.txt"

    private val LEAD_NUM_RE = Regex("^(\\d{1,3})\\s*[.)\\-:]?\\s+")
    private val LEAD_TIME_RE = Regex("^\\[?((?:\\d{1,2}:)?\\d{1,3}:\\d{2}(?:[.,]\\d{1,3})?)]?\\s*[-–—.)]?\\s*(.*)$")
    private val TRAIL_TIME_RE = Regex("^(.*?)\\s*[\\[(]?\\s*((?:\\d{1,2}:)?\\d{1,3}:\\d{2}(?:[.,]\\d{1,3})?)\\s*[\\])]?\\s*$")

    data class Entry(val number: Int, val title: String, val startMs: Long)

    override fun split(ctx: Context, image: Track): List<Track>? {
        if (image.path.isBlank() || image.durationMs <= 0L) return null
        val dir = File(image.path).parentFile ?: return null
        val f = File(dir, FILE_NAME)
        if (!runCatching { f.isFile }.getOrDefault(false)) return null
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val entries = parse(text, image.durationMs)
        if (entries == null) { Log.w(TAG, "Ignoring ${f.absolutePath}: timings not understood"); return null }
        if (entries.size < 2) return null
        val total = image.durationMs
        return entries.mapIndexed { i, e ->
            val end = entries.getOrNull(i + 1)?.startMs ?: total
            image.copy(
                id = -(image.id * 1000L + (i + 1)),
                title = e.title,
                durationMs = (end - e.startMs).coerceAtLeast(0L),
                sizeBytes = 0L,
                trackNumber = e.number,
                isDiscImage = true,
                parentId = image.id,
                clipStartMs = e.startMs,
                clipEndMs = if (end > e.startMs && end < total) end else 0L,
            )
        }
    }

    /** Parsed, validated entries sorted by start, or null when the file can't be trusted. */
    fun parse(text: String, totalMs: Long): List<Entry>? {
        val starts = ArrayList<Entry>(); val durations = ArrayList<Entry>()
        var idx = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) continue
            idx++
            var number = idx
            var body = line
            LEAD_NUM_RE.find(body)?.let { m -> number = m.groupValues[1].toInt(); body = body.substring(m.range.last + 1).trim() }
            val lead = LEAD_TIME_RE.matchEntire(body)
            if (lead != null) {
                val ms = parseTime(lead.groupValues[1]) ?: return null
                starts += Entry(number, lead.groupValues[2].trim().ifBlank { "Track $number" }, ms)
                continue
            }
            val trail = TRAIL_TIME_RE.matchEntire(body)
            if (trail != null) {
                val ms = parseTime(trail.groupValues[2]) ?: return null
                durations += Entry(number, trail.groupValues[1].trim().ifBlank { "Track $number" }, ms)
                continue
            }
            return null            // a line with no timing → not a timing file
        }
        if (starts.isNotEmpty() && durations.isNotEmpty()) return null   // mixed layouts
        if (starts.isNotEmpty()) {
            val sorted = starts.sortedBy { it.startMs }
            for (i in 1 until sorted.size) if (sorted[i].startMs <= sorted[i - 1].startMs) return null
            if (sorted.last().startMs >= totalMs - 1_000L) return null
            return sorted
        }
        if (durations.isNotEmpty()) {
            val sum = durations.sumOf { it.startMs }
            if (kotlin.math.abs(totalMs - sum) > totalMs * 0.03) return null
            var cursor = 0L
            return durations.map { d -> Entry(d.number, d.title, cursor).also { cursor += d.startMs } }
        }
        return null
    }

    private fun parseTime(s: String): Long? {
        val main = s.substringBefore('.').substringBefore(',')
        val frac = s.substringAfter('.', "").ifBlank { s.substringAfter(',', "") }
        val parts = main.split(':').map { it.toLongOrNull() ?: return null }
        val secs = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        val fracMs = if (frac.isBlank()) 0L else (frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
        return secs * 1000L + fracMs
    }
}
