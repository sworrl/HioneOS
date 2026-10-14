package com.hione.player.stats

import com.hione.player.Mascot
import android.content.Context
import com.hione.player.PlayerPreferences
import com.hione.player.taste.GenreIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TreeSet

/**
 * "Hione Rewind" — the Spotify-Wrapped-style recap. This file is the DATA side: the period model,
 * the story (card) model, and [RewindBuilder], which computes every story from the real listen DB
 * ([ListenStatsDb] `listens` table) plus the heart-event log in [PlayerPreferences] and the
 * file-tag genre index ([GenreIndex]).
 *
 * Honesty rules (the whole point of this feature):
 *  - Every number comes from an SQL aggregate over ended listens (`ended_at IS NOT NULL`) inside
 *    the period, or from real heart events with a timestamp inside the period. Nothing is
 *    estimated, extrapolated or invented.
 *  - Every card has a data threshold ([Threshold]). A card whose threshold isn't met is replaced by
 *    a [RewindStory.NotEnough] card saying exactly "N of M" so the user knows why, not by filler.
 *  - If the period as a whole has fewer than [Threshold.DECK] listens, the deck is ONE NotEnough
 *    card and nothing else.
 *
 * All computation runs on Dispatchers.IO in one pass ([RewindBuilder.build]); the UI only ever
 * renders an immutable [RewindDeck].
 */

// ============================================================================================ periods

data class RewindPeriod(val kind: Kind, val label: String, val start: Long, val end: Long, val year: Int = 0) {
    enum class Kind { WEEK, MONTH, YEAR, ALL, PAST_YEAR }

    /** Short possessive-friendly name for card copy: "this week", "in 2025", "all time". */
    val inWords: String get() = when (kind) {
        Kind.WEEK -> "this week"; Kind.MONTH -> "this month"; Kind.YEAR -> "this year"
        Kind.ALL -> "all time"; Kind.PAST_YEAR -> "in $year"
    }
    val key: String get() = if (kind == Kind.PAST_YEAR) "year$year" else kind.name

    companion object {
        private fun midnight(now: Long): Calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        /** Same alignment as StatsRepository.bounds: last 7 / 30 / 365 whole days incl. today. */
        fun standard(now: Long = System.currentTimeMillis()): List<RewindPeriod> {
            fun back(days: Int) = midnight(now).apply { add(Calendar.DAY_OF_YEAR, -days) }.timeInMillis
            return listOf(
                RewindPeriod(Kind.WEEK, "This week", back(6), Long.MAX_VALUE),
                RewindPeriod(Kind.MONTH, "This month", back(29), Long.MAX_VALUE),
                RewindPeriod(Kind.YEAR, "This year", back(364), Long.MAX_VALUE),
                RewindPeriod(Kind.ALL, "All time", 0L, Long.MAX_VALUE)
            )
        }

        /** A whole calendar year [Jan 1, Jan 1 next). */
        fun calendarYear(year: Int): RewindPeriod {
            val c = Calendar.getInstance().apply { clear(); set(year, Calendar.JANUARY, 1) }
            val s = c.timeInMillis
            c.add(Calendar.YEAR, 1)
            return RewindPeriod(Kind.PAST_YEAR, year.toString(), s, c.timeInMillis, year)
        }

        /** Standard periods + one entry per PAST calendar year that actually has ended listens. */
        suspend fun available(ctx: Context): List<RewindPeriod> = withContext(Dispatchers.IO) {
            val out = ArrayList(standard())
            val db = ListenStatsDb.get(ctx)
            val thisYear = Calendar.getInstance().get(Calendar.YEAR)
            var first = 0L
            runCatching {
                db.query("SELECT MIN(started_at) FROM ${ListenStatsDb.TABLE_LISTENS} WHERE ended_at IS NOT NULL", null).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) first = c.getLong(0)
                }
            }
            if (first > 0L) {
                val firstYear = Calendar.getInstance().apply { timeInMillis = first }.get(Calendar.YEAR)
                for (y in (thisYear - 1) downTo firstYear) {
                    val p = calendarYear(y)
                    val n = runCatching {
                        db.query(
                            "SELECT COUNT(*) FROM ${ListenStatsDb.TABLE_LISTENS} WHERE ended_at IS NOT NULL AND started_at >= ? AND started_at < ?",
                            arrayOf(p.start.toString(), p.end.toString())
                        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    }.getOrDefault(0)
                    if (n > 0) out.add(p)
                }
            }
            out
        }
    }
}

// ============================================================================================ thresholds

/** Minimum real data each card needs before it is shown. Surfaced verbatim in NotEnough cards. */
object Threshold {
    const val DECK = 10            // ended listens in the period, else the deck is one NotEnough card
    const val TOP_TRACKS = 3       // distinct tracks with >= 1 listen
    const val TOP_ARTISTS = 3      // distinct non-blank artists
    const val TOP_ALBUMS = 3       // distinct non-blank albums
    const val CLOCK = 20           // listens (heatmap + persona)
    const val STREAK_DAYS = 2      // longest run of consecutive listening days (>= 30 s/day)
    const val DISCOVERIES = 3      // tracks whose first-ever listen is inside the period
    const val OBSESSION_RECENT = 4 // plays in the recent half (and >= 2x the earlier half)
    const val SKIPS = 20           // listens for a skip rate
    const val MOST_SKIPPED = 3     // skips (<50 % heard) on one track
    const val FINISHED = 3         // qualified (>= 94 %) plays on one track
    const val HEARTS = 1           // heart taps inside the period
    const val QUALITY = 10         // listens whose format/rate/bits is known
    const val OUTPUTS = 10         // listens with a known output route
    const val GENRES = 10          // listens on tracks that carry a genre tag
    const val MIN_LISTEN_MS = 30_000L   // a listen shorter than this never counts toward streaks/obsession
}

// ============================================================================================ stories

/** One row in a top-5 / discovery / skip card. `artTrackId` is any real track id that carries the
 *  art to show (a track of the artist/album for those lists). */
data class RewindEntry(
    val title: String,
    val subtitle: String?,
    val plays: Int,
    val qualifiedPlays: Int,
    val playedMs: Long,
    val trackId: Long? = null,
    val artTrackId: Long? = null,
    val artPath: String = ""
)

sealed class RewindStory {
    /** Threshold not met: shown INSTEAD of the card, with the exact gap. */
    data class NotEnough(val card: String, val have: Int, val need: Int, val unit: String) : RewindStory()

    data class TotalTime(
        val playedMs: Long, val listens: Int, val distinctTracks: Int, val distinctArtists: Int,
        /** Top album (by listened ms) + its real end-to-end running time from the listens table. */
        val albumTitle: String?, val albumArtist: String?, val albumMs: Long, val albumsBackToBack: Int,
        val albumArtTrackId: Long?, val albumArtPath: String
    ) : RewindStory()

    enum class TopKind { TRACKS, ARTISTS, ALBUMS }
    data class Top(val kind: TopKind, val entries: List<RewindEntry>) : RewindStory()

    data class Clock(
        /** [dow 0=Sun..6][hour 0..23] played_ms. */
        val heat: Array<LongArray>, val persona: String, val blurb: String,
        val peakHour: Int, val peakDow: Int, val totalMs: Long
    ) : RewindStory() {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class Streak(val longestDays: Int, val streakStart: Long, val streakEnd: Long, val activeDays: Int, val spanDays: Int) : RewindStory()

    data class Discoveries(val count: Int, val newest: List<RewindEntry>, val obsession: RewindEntry?, val obsessionRecent: Int, val obsessionEarlier: Int, val obsessionWindowDays: Int) : RewindStory()

    data class Skips(val rate: Float, val skipped: Int, val listens: Int, val mostSkipped: RewindEntry?, val mostSkippedCount: Int) : RewindStory()

    data class Finished(val entry: RewindEntry) : RewindStory()

    data class Hearts(val hearts: Int, val tracksHearted: Int, val top: RewindEntry?, val topCount: Int) : RewindStory()

    data class Quality(val hiResMs: Long, val losslessMs: Long, val lossyMs: Long, val knownListens: Int, val bestRateHz: Int?, val bestBits: Int?) : RewindStory()

    data class Outputs(val shares: List<OutputShare>) : RewindStory()
    data class OutputShare(val output: String, val playedMs: Long, val listens: Int)

    data class Genres(val shares: List<Pair<String, Long>>, val totalMs: Long, val taggedListens: Int) : RewindStory()

    data class Summary(
        val minutes: Long, val listens: Int, val topTrack: String?, val topArtist: String?, val topAlbum: String?,
        val topGenre: String?, val persona: String?, val hearts: Int, val discoveries: Int, val longestStreak: Int,
        val artTrackId: Long?, val artPath: String
    ) : RewindStory()
}

/** Immutable, fully precomputed deck for one period. */
data class RewindDeck(val period: RewindPeriod, val listens: Int, val stories: List<RewindStory>)

// ============================================================================================ builder

class RewindBuilder(context: Context) {
    private val app = context.applicationContext
    private val db get() = ListenStatsDb.get(app)

    private fun where(p: RewindPeriod) =
        "ended_at IS NOT NULL AND started_at >= ? AND started_at < ?" to arrayOf(p.start.toString(), p.end.toString())

    private inline fun <T> q(sql: String, args: Array<String>?, block: (android.database.Cursor) -> T): T = db.query(sql, args).use(block)

    suspend fun build(p: RewindPeriod): RewindDeck = withContext(Dispatchers.IO) {
        val (w, args) = where(p)
        val stories = ArrayList<RewindStory>(16)

        // ---- totals (also the deck gate) ----
        var listens = 0; var qualified = 0; var skipped = 0; var playedMs = 0L
        var dTracks = 0; var dArtists = 0; var dAlbums = 0; var heartsDuring = 0
        q(
            """SELECT COUNT(*), COALESCE(SUM(qualified),0), COALESCE(SUM(skipped),0), COALESCE(SUM(played_ms),0),
                      COUNT(DISTINCT track_id), COUNT(DISTINCT artist), COUNT(DISTINCT artist || char(0) || COALESCE(album,'')),
                      COALESCE(SUM(hearts_during),0)
               FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w""", args
        ) { c ->
            if (c.moveToFirst()) {
                listens = c.getInt(0); qualified = c.getInt(1); skipped = c.getInt(2); playedMs = c.getLong(3)
                dTracks = c.getInt(4); dArtists = c.getInt(5); dAlbums = c.getInt(6); heartsDuring = c.getInt(7)
            }
        }
        if (listens < Threshold.DECK) {
            return@withContext RewindDeck(p, listens, listOf(RewindStory.NotEnough("${Mascot.name} Rewind ${p.inWords}", listens, Threshold.DECK, "listens")))
        }

        // ---- 1. total time + "albums back to back" ----
        val topAlbum = topAlbums(p, 1).firstOrNull()
        var albumMs = 0L
        if (topAlbum != null) {
            albumMs = q(
                "SELECT COALESCE(SUM(d),0) FROM (SELECT MAX(duration_ms) AS d FROM ${ListenStatsDb.TABLE_LISTENS} WHERE ended_at IS NOT NULL AND artist = ? AND album = ? GROUP BY track_id)",
                arrayOf(topAlbum.subtitle ?: "", topAlbum.title)
            ) { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }
        val backToBack = if (albumMs >= 10 * 60_000L && playedMs >= albumMs) (playedMs / albumMs).toInt() else 0
        stories.add(
            RewindStory.TotalTime(
                playedMs, listens, dTracks, dArtists,
                topAlbum?.title, topAlbum?.subtitle, albumMs, backToBack, topAlbum?.artTrackId, topAlbum?.artPath ?: ""
            )
        )

        // ---- 2-4. top tracks / artists / albums ----
        val topTracks = topTracks(p, 5)
        stories.add(if (dTracks >= Threshold.TOP_TRACKS) RewindStory.Top(RewindStory.TopKind.TRACKS, topTracks) else RewindStory.NotEnough("Top tracks", dTracks, Threshold.TOP_TRACKS, "different tracks"))
        val topArtists = topArtists(p, 5)
        val nArtists = q("SELECT COUNT(DISTINCT artist) FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w AND artist <> ''", args) { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        stories.add(if (nArtists >= Threshold.TOP_ARTISTS) RewindStory.Top(RewindStory.TopKind.ARTISTS, topArtists) else RewindStory.NotEnough("Top artists", nArtists, Threshold.TOP_ARTISTS, "different artists"))
        val nAlbums = q("SELECT COUNT(DISTINCT artist || char(0) || album) FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w AND album IS NOT NULL AND album <> ''", args) { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        stories.add(if (nAlbums >= Threshold.TOP_ALBUMS) RewindStory.Top(RewindStory.TopKind.ALBUMS, topAlbums(p, 5)) else RewindStory.NotEnough("Top albums", nAlbums, Threshold.TOP_ALBUMS, "different albums"))

        // ---- 5. listening clock ----
        var clock: RewindStory.Clock? = null
        if (listens >= Threshold.CLOCK) {
            val heat = Array(7) { LongArray(24) }
            q("SELECT day_of_week, hour, SUM(played_ms) FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w GROUP BY day_of_week, hour", args) { c ->
                while (c.moveToNext()) {
                    val d = c.getInt(0) - 1; val h = c.getInt(1)
                    if (d in 0..6 && h in 0..23) heat[d][h] = c.getLong(2)
                }
            }
            clock = clockStory(heat)
            stories.add(clock)
        } else stories.add(RewindStory.NotEnough("Your listening clock", listens, Threshold.CLOCK, "listens"))

        // ---- 6. streak inside the period ----
        val streak = streak(p)
        stories.add(if (streak.longestDays >= Threshold.STREAK_DAYS) streak else RewindStory.NotEnough("Longest streak", streak.longestDays, Threshold.STREAK_DAYS, "days in a row"))

        // ---- 7. discoveries + newest obsession ----
        val discoveries = discoveries(p, 5)
        val discoveryCount = discoveryCount(p)
        val obs = obsession(p)
        val obsession = obs.entry; val obsRecent = obs.recent; val obsEarlier = obs.earlier; val obsDays = obs.windowDays
        stories.add(
            if (discoveryCount >= Threshold.DISCOVERIES || obsession != null)
                RewindStory.Discoveries(discoveryCount, discoveries, obsession, obsRecent, obsEarlier, obsDays)
            else RewindStory.NotEnough("Discoveries", discoveryCount, Threshold.DISCOVERIES, "first-ever listens")
        )

        // ---- 8. skips ----
        if (listens >= Threshold.SKIPS) {
            val ms = mostSkipped(p)
            stories.add(RewindStory.Skips(skipped.toFloat() / listens, skipped, listens, ms?.first, ms?.second ?: 0))
        } else stories.add(RewindStory.NotEnough("Skip rate", listens, Threshold.SKIPS, "listens"))

        // ---- 9. finished most ----
        val finished = topTracks.firstOrNull()?.takeIf { it.qualifiedPlays >= Threshold.FINISHED }
        stories.add(if (finished != null) RewindStory.Finished(finished) else RewindStory.NotEnough("The one you finished most", topTracks.firstOrNull()?.qualifiedPlays ?: 0, Threshold.FINISHED, "full plays of one track"))

        // ---- 10. hearts ----
        val hearts = hearts(p, heartsDuring)
        stories.add(if (hearts.hearts >= Threshold.HEARTS) hearts else RewindStory.NotEnough("Hearts given", hearts.hearts, Threshold.HEARTS, "hearts"))

        // ---- 11. audio quality ----
        val quality = quality(p)
        stories.add(if (quality.knownListens >= Threshold.QUALITY) quality else RewindStory.NotEnough("Audio quality", quality.knownListens, Threshold.QUALITY, "listens with a known format"))

        // ---- 12. outputs ----
        val outputs = ArrayList<RewindStory.OutputShare>()
        q("SELECT output, COUNT(*), SUM(played_ms) FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w AND output IS NOT NULL AND output <> '' AND output <> 'unknown' GROUP BY output ORDER BY 3 DESC", args) { c ->
            while (c.moveToNext()) outputs.add(RewindStory.OutputShare(c.getString(0), c.getLong(2), c.getInt(1)))
        }
        val knownOut = outputs.sumOf { it.listens }
        stories.add(if (knownOut >= Threshold.OUTPUTS) RewindStory.Outputs(outputs) else RewindStory.NotEnough("Your outputs", knownOut, Threshold.OUTPUTS, "listens with a known output"))

        // ---- 13. taste profile (genres from the files' own tags) ----
        val genres = genres(p)
        stories.add(if (genres.taggedListens >= Threshold.GENRES) genres else RewindStory.NotEnough("Your taste profile", genres.taggedListens, Threshold.GENRES, "listens on genre-tagged tracks"))

        // ---- 14. summary / share ----
        val topTrack = topTracks.firstOrNull()
        stories.add(
            RewindStory.Summary(
                minutes = playedMs / 60_000L, listens = listens,
                topTrack = topTrack?.title, topArtist = topArtists.firstOrNull()?.title, topAlbum = topAlbum?.title,
                topGenre = genres.shares.firstOrNull()?.first?.takeIf { genres.taggedListens >= Threshold.GENRES },
                persona = clock?.persona, hearts = hearts.hearts, discoveries = discoveryCount, longestStreak = streak.longestDays,
                artTrackId = topTrack?.trackId, artPath = topTrack?.artPath ?: ""
            )
        )

        RewindDeck(p, listens, stories)
    }

    // ------------------------------------------------------------------ top lists

    private fun topTracks(p: RewindPeriod, limit: Int): List<RewindEntry> {
        val (w, args) = where(p)
        val out = ArrayList<RewindEntry>(limit)
        q(
            """SELECT track_id, title, artist, album, COUNT(*) AS n, SUM(qualified) AS qq, SUM(played_ms) AS ms, MAX(path)
               FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w GROUP BY track_id ORDER BY qq DESC, ms DESC, n DESC LIMIT ?""",
            args + limit.toString()
        ) { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out.add(
                    RewindEntry(
                        title = c.getString(1), subtitle = listOfNotNull(c.getString(2).ifBlank { null }, c.getString(3)?.ifBlank { null }).joinToString(" · "),
                        plays = c.getInt(4), qualifiedPlays = c.getInt(5), playedMs = c.getLong(6), trackId = id, artTrackId = id, artPath = c.getString(7) ?: ""
                    )
                )
            }
        }
        return out
    }

    private fun topArtists(p: RewindPeriod, limit: Int): List<RewindEntry> {
        val (w, args) = where(p)
        val out = ArrayList<RewindEntry>(limit)
        q(
            """SELECT artist, COUNT(*) AS n, SUM(qualified) AS qq, SUM(played_ms) AS ms, COUNT(DISTINCT track_id)
               FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w AND artist <> '' GROUP BY artist ORDER BY qq DESC, ms DESC, n DESC LIMIT ?""",
            args + limit.toString()
        ) { c ->
            while (c.moveToNext()) {
                val artist = c.getString(0)
                val n = c.getInt(4)
                val (artId, artPath) = artFor(p, "artist = ?", arrayOf(artist))
                out.add(RewindEntry(artist, "$n track${if (n == 1) "" else "s"}", c.getInt(1), c.getInt(2), c.getLong(3), null, artId, artPath))
            }
        }
        return out
    }

    private fun topAlbums(p: RewindPeriod, limit: Int): List<RewindEntry> {
        val (w, args) = where(p)
        val out = ArrayList<RewindEntry>(limit)
        q(
            """SELECT artist, album, COUNT(*) AS n, SUM(qualified) AS qq, SUM(played_ms) AS ms
               FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w AND album IS NOT NULL AND album <> '' GROUP BY artist, album ORDER BY ms DESC, qq DESC, n DESC LIMIT ?""",
            args + limit.toString()
        ) { c ->
            while (c.moveToNext()) {
                val artist = c.getString(0); val album = c.getString(1)
                val (artId, artPath) = artFor(p, "artist = ? AND album = ?", arrayOf(artist, album))
                out.add(RewindEntry(album, artist, c.getInt(2), c.getInt(3), c.getLong(4), null, artId, artPath))
            }
        }
        return out
    }

    /** The most-played real track matching [cond] inside the period — its art stands for the group. */
    private fun artFor(p: RewindPeriod, cond: String, condArgs: Array<String>): Pair<Long?, String> {
        val (w, args) = where(p)
        return q(
            "SELECT track_id, MAX(path), SUM(played_ms) AS ms FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w AND $cond GROUP BY track_id ORDER BY ms DESC LIMIT 1",
            args + condArgs
        ) { c -> if (c.moveToFirst()) c.getLong(0) to (c.getString(1) ?: "") else null to "" }
    }

    // ------------------------------------------------------------------ clock

    private fun clockStory(heat: Array<LongArray>): RewindStory.Clock {
        var total = 0L
        val byHour = LongArray(24)
        var peakHour = 0; var peakDow = 0; var peakVal = -1L
        val byDow = LongArray(7)
        for (d in 0..6) for (h in 0..23) {
            val v = heat[d][h]; total += v; byHour[h] += v; byDow[d] += v
            if (v > peakVal) { peakVal = v; peakHour = h; peakDow = d }
        }
        // Day-part bands (local start hour): night 22-04, morning 05-10, day 11-17, evening 18-21.
        fun band(hours: IntRange) = hours.sumOf { byHour[it] }
        val night = band(22..23) + band(0..4)
        val morning = band(5..10)
        val day = band(11..17)
        val evening = band(18..21)
        val t = total.coerceAtLeast(1L)
        val bands = listOf(
            Triple("Night owl", night, "between 10 PM and 5 AM"),
            Triple("Early bird", morning, "between 5 AM and 11 AM"),
            Triple("Daytime listener", day, "between 11 AM and 6 PM"),
            Triple("Evening listener", evening, "between 6 PM and 10 PM")
        )
        val top = bands.maxByOrNull { it.second }!!
        val share = (top.second * 100 / t).toInt()
        val persona: String; val blurb: String
        if (share >= 35) {
            persona = top.first
            blurb = "$share% of your listening happened ${top.third}"
        } else {
            persona = "All-day listener"
            blurb = "No single part of the day claims more than $share% of your listening"
        }
        val dowName = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val peakDowIdx = byDow.indices.maxByOrNull { byDow[it] } ?: 0
        val blurb2 = "$blurb. Your busiest slot: ${dowName[peakDow]} at ${hourLabel12(peakHour)}, busiest day: ${dowName[peakDowIdx]}."
        return RewindStory.Clock(heat, persona, blurb2, peakHour, peakDow, total)
    }

    // ------------------------------------------------------------------ streak (inside the period)

    private fun streak(p: RewindPeriod): RewindStory.Streak {
        val (w, args) = where(p)
        val days = TreeSet<Long>()
        val cal = Calendar.getInstance()
        q("SELECT DISTINCT started_at FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w AND played_ms >= ?", args + Threshold.MIN_LISTEN_MS.toString()) { c ->
            while (c.moveToNext()) days.add(dayStart(cal, c.getLong(0)))
        }
        if (days.isEmpty()) return RewindStory.Streak(0, 0L, 0L, 0, 0)
        val dayMs = 86_400_000L
        var longest = 0; var run = 0; var prev = -1L; var runStart = 0L
        var bestStart = 0L; var bestEnd = 0L
        for (d in days) {
            if (prev >= 0 && d - prev <= dayMs + 3_600_000L) run++ else { run = 1; runStart = d }
            if (run > longest) { longest = run; bestStart = runStart; bestEnd = d }
            prev = d
        }
        val span = ((days.last() - days.first()) / dayMs).toInt() + 1
        return RewindStory.Streak(longest, bestStart, bestEnd, days.size, span)
    }

    // ------------------------------------------------------------------ discoveries / obsession

    private fun discoveries(p: RewindPeriod, limit: Int): List<RewindEntry> {
        val out = ArrayList<RewindEntry>(limit)
        q(
            """SELECT track_id, title, artist, album, MIN(started_at) AS first_at, COUNT(*) AS n, SUM(qualified), SUM(played_ms), MAX(path)
               FROM ${ListenStatsDb.TABLE_LISTENS} WHERE ended_at IS NOT NULL
               GROUP BY track_id HAVING first_at >= ? AND first_at < ? ORDER BY n DESC, first_at DESC LIMIT ?""",
            arrayOf(p.start.toString(), p.end.toString(), limit.toString())
        ) { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out.add(RewindEntry(c.getString(1), listOfNotNull(c.getString(2).ifBlank { null }, c.getString(3)?.ifBlank { null }).joinToString(" · "), c.getInt(5), c.getInt(6), c.getLong(7), id, id, c.getString(8) ?: ""))
            }
        }
        return out
    }

    private fun discoveryCount(p: RewindPeriod): Int = q(
        """SELECT COUNT(*) FROM (SELECT track_id, MIN(started_at) AS first_at FROM ${ListenStatsDb.TABLE_LISTENS}
           WHERE ended_at IS NOT NULL GROUP BY track_id HAVING first_at >= ? AND first_at < ?)""",
        arrayOf(p.start.toString(), p.end.toString())
    ) { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private data class Obsession(val entry: RewindEntry?, val recent: Int, val earlier: Int, val windowDays: Int)

    /** Highest recent play growth: plays in the recent window vs the earlier part of the period.
     *  Window = second half of the period (ALL time: the last 30 days). Needs >= OBSESSION_RECENT
     *  recent plays AND recent >= 2x earlier, so a steady favourite is not mislabelled "new". */
    private fun obsession(p: RewindPeriod): Obsession {
        val now = System.currentTimeMillis()
        val end = if (p.end == Long.MAX_VALUE) now else p.end
        val (mid, windowDays) = when (p.kind) {
            RewindPeriod.Kind.ALL -> (end - 30L * 86_400_000L) to 30
            else -> { val half = (end - p.start) / 2; (end - half) to ((half + 86_399_999L) / 86_400_000L).toInt() }
        }
        val (w, args) = where(p)
        return q(
            """SELECT track_id, title, artist, album,
                      SUM(CASE WHEN started_at >= ? THEN 1 ELSE 0 END) AS r,
                      SUM(CASE WHEN started_at < ? THEN 1 ELSE 0 END) AS e,
                      SUM(qualified), SUM(played_ms), MAX(path)
               FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w AND played_ms >= ?
               GROUP BY track_id HAVING r >= ? AND r >= 2 * e ORDER BY (r - e) DESC, r DESC LIMIT 1""",
            arrayOf(mid.toString(), mid.toString()) + args + arrayOf(Threshold.MIN_LISTEN_MS.toString(), Threshold.OBSESSION_RECENT.toString())
        ) { c ->
            if (!c.moveToFirst()) Obsession(null, 0, 0, windowDays)
            else {
                val id = c.getLong(0)
                val r = c.getInt(4); val e = c.getInt(5)
                Obsession(
                    RewindEntry(c.getString(1), listOfNotNull(c.getString(2).ifBlank { null }, c.getString(3)?.ifBlank { null }).joinToString(" · "), r + e, c.getInt(6), c.getLong(7), id, id, c.getString(8) ?: ""),
                    r, e, windowDays
                )
            }
        }
    }

    // ------------------------------------------------------------------ skips

    private fun mostSkipped(p: RewindPeriod): Pair<RewindEntry, Int>? {
        val (w, args) = where(p)
        return q(
            """SELECT track_id, title, artist, album, COUNT(*) AS n, SUM(CASE WHEN skipped = 1 AND fraction < 0.5 THEN 1 ELSE 0 END) AS s, SUM(qualified), SUM(played_ms), MAX(path)
               FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w GROUP BY track_id HAVING s >= ? ORDER BY s DESC, n DESC LIMIT 1""",
            args + Threshold.MOST_SKIPPED.toString()
        ) { c ->
            if (!c.moveToFirst()) null
            else {
                val id = c.getLong(0)
                RewindEntry(c.getString(1), listOfNotNull(c.getString(2).ifBlank { null }, c.getString(3)?.ifBlank { null }).joinToString(" · "), c.getInt(4), c.getInt(6), c.getLong(7), id, id, c.getString(8) ?: "") to c.getInt(5)
            }
        }
    }

    // ------------------------------------------------------------------ hearts

    /** Heart taps inside the period from the per-track event log (the ground truth for taps, incl.
     *  hearts given outside a listen). Falls back to the DB's hearts_during sum if the log is empty. */
    private fun hearts(p: RewindPeriod, heartsDuringDb: Int): RewindStory.Hearts {
        val end = if (p.end == Long.MAX_VALUE) Long.MAX_VALUE else p.end
        val perTrack = HashMap<Long, Int>()
        var total = 0
        runCatching {
            for (id in PlayerPreferences.loadLikedTracks(app)) {
                var n = 0
                for ((epoch, _, _) in PlayerPreferences.getHeartEvents(app, id)) if (epoch >= p.start && epoch < end) n++
                if (n > 0) { perTrack[id] = n; total += n }
            }
        }
        if (total == 0 && heartsDuringDb > 0) {
            // Log empty (older builds) — use the listen rows' own heart counter.
            val (w, args) = where(p)
            val top = q("SELECT track_id, title, artist, album, SUM(hearts_during) AS h, COUNT(*), SUM(qualified), SUM(played_ms), MAX(path) FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w AND hearts_during > 0 GROUP BY track_id ORDER BY h DESC LIMIT 1", args) { c ->
                if (!c.moveToFirst()) null else {
                    val id = c.getLong(0)
                    RewindEntry(c.getString(1), listOfNotNull(c.getString(2).ifBlank { null }, c.getString(3)?.ifBlank { null }).joinToString(" · "), c.getInt(5), c.getInt(6), c.getLong(7), id, id, c.getString(8) ?: "") to c.getInt(4)
                }
            }
            val nTracks = q("SELECT COUNT(DISTINCT track_id) FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w AND hearts_during > 0", args) { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            return RewindStory.Hearts(heartsDuringDb, nTracks, top?.first, top?.second ?: 0)
        }
        val topId = perTrack.maxByOrNull { it.value }?.key
        val top = topId?.let { id ->
            val fromDb = q("SELECT title, artist, album, COUNT(*), SUM(qualified), SUM(played_ms), MAX(path) FROM ${ListenStatsDb.TABLE_LISTENS} WHERE ended_at IS NOT NULL AND track_id = ? GROUP BY track_id", arrayOf(id.toString())) { c ->
                if (!c.moveToFirst()) null
                else RewindEntry(c.getString(0), listOfNotNull(c.getString(1).ifBlank { null }, c.getString(2)?.ifBlank { null }).joinToString(" · "), c.getInt(3), c.getInt(4), c.getLong(5), id, id, c.getString(6) ?: "")
            }
            fromDb ?: PlayerPreferences.loadLikedTrackMeta(app)[id]?.let { (t, a) -> RewindEntry(t, a, 0, 0, 0L, id, id) }
        }
        return RewindStory.Hearts(total, perTrack.size, top, topId?.let { perTrack[it] } ?: 0)
    }

    // ------------------------------------------------------------------ quality

    private val lossyExt = setOf("mp3", "aac", "m4a", "ogg", "oga", "opus", "wma", "mp4", "m4b", "mpc", "amr", "3gp")
    private val losslessExt = setOf("flac", "wav", "aiff", "aif", "ape", "dsf", "dff", "wv", "tta", "tak", "alac", "caf", "w64")

    private fun quality(p: RewindPeriod): RewindStory.Quality {
        val (w, args) = where(p)
        var hi = 0L; var lossless = 0L; var lossy = 0L; var known = 0
        var bestRate: Int? = null; var bestBits: Int? = null
        q("SELECT path, sample_rate_hz, bit_depth, played_ms FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w", args) { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: ""
                val rate = if (c.isNull(1)) null else c.getInt(1)
                val bits = if (c.isNull(2)) null else c.getInt(2)
                val ms = c.getLong(3)
                val ext = path.substringAfterLast('.', "").lowercase()
                val isHiRes = (rate != null && rate > 48_000) || (bits != null && bits > 16)
                when {
                    ext in lossyExt && !isHiRes -> { lossy += ms; known++ }
                    isHiRes -> { hi += ms; known++ }
                    ext in losslessExt || bits != null -> { lossless += ms; known++ }
                    else -> {}   // format genuinely unknown: not counted, not guessed
                }
                if (rate != null && (bestRate == null || rate > bestRate!!)) bestRate = rate
                if (bits != null && (bestBits == null || bits > bestBits!!)) bestBits = bits
            }
        }
        return RewindStory.Quality(hi, lossless, lossy, known, bestRate, bestBits)
    }

    // ------------------------------------------------------------------ genres

    private fun genres(p: RewindPeriod): RewindStory.Genres {
        val (w, args) = where(p)
        val acc = HashMap<String, Long>()
        var tagged = 0; var total = 0L
        q("SELECT track_id, SUM(played_ms), COUNT(*) FROM ${ListenStatsDb.TABLE_LISTENS} WHERE $w GROUP BY track_id", args) { c ->
            while (c.moveToNext()) {
                val g = runCatching { GenreIndex.genreOf(app, c.getLong(0)) }.getOrDefault("")
                if (g.isBlank()) continue
                val ms = c.getLong(1)
                acc[g] = (acc[g] ?: 0L) + ms
                total += ms; tagged += c.getInt(2)
            }
        }
        val shares = acc.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        return RewindStory.Genres(shares, total, tagged)
    }

    // ------------------------------------------------------------------ helpers

    private fun dayStart(cal: Calendar, t: Long): Long {
        cal.timeInMillis = t
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

internal fun hourLabel12(h: Int): String = when {
    h == 0 -> "12 AM"; h < 12 -> "$h AM"; h == 12 -> "12 PM"; else -> "${h - 12} PM"
}

internal fun outputName(o: String): String = when (o) {
    "bt" -> "Bluetooth"; "usb" -> "USB DAC"; "wired" -> "Wired"; "speaker" -> "Speaker"; "digital" -> "Digital"; else -> o.replaceFirstChar { it.uppercase() }
}
