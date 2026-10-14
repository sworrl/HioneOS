package com.hione.player.discsplit

import kotlin.math.abs

/**
 * Pure (no Android, no network) duration-based matching of a whole-disc image against MusicBrainz
 * media. Kept free of I/O so the thresholds can be reasoned about (and unit-tested) in isolation.
 *
 * The image's duration (from MediaStore) is THE disambiguator: artist + album finds candidate
 * releases, but only the medium whose summed track lengths reproduce the file's length is
 * accepted. Thresholds:
 *
 *  • [TOLERANCE_FRACTION] 1.5 % of the image length (never less than [MIN_TOLERANCE_MS]) either
 *    way. CD track lengths on MusicBrainz come from the TOC in whole frames, encoders pad the tail
 *    of the last track, and some rips drop the lead-out — all well inside 1.5 %.
 *  • plus [PREGAP_MS] of extra file length on top of that: a standard Red Book image carries the
 *    2-second track-1 pregap that the TOC's track lengths exclude.
 *  • A candidate is rejected as AMBIGUOUS (return null → image stays whole, never guessed) when
 *    a rival medium with a materially different track list fits within [AMBIGUITY_MARGIN_FRACTION]
 *    of the best one. Rivals with the same TOC (re-issues, country variants) are NOT ambiguity —
 *    any of them yields the same cut points.
 */
object DiscMatcher {
    const val TOLERANCE_FRACTION = 0.015
    const val MIN_TOLERANCE_MS = 1_500L
    const val PREGAP_MS = 2_200L
    const val AMBIGUITY_MARGIN_FRACTION = 0.002
    /** A fit this close is treated as exact: the lookup stops fetching further candidates. */
    const val TIGHT_MATCH_FRACTION = 0.002
    /** Track-by-track length slack under which two media count as "the same TOC". */
    private const val SAME_TOC_TRACK_SLACK_MS = 1_500L

    data class MbTrack(val position: Int, val title: String, val artist: String, val lengthMs: Long)
    data class MbMedium(val position: Int, val format: String, val tracks: List<MbTrack>) {
        val totalMs: Long get() = tracks.sumOf { it.lengthMs }
    }
    data class MbRelease(
        val id: String, val title: String, val artist: String, val date: String, val country: String,
        val score: Int, val media: List<MbMedium>,
    )
    data class Candidate(val release: MbRelease, val medium: MbMedium, val diffMs: Long)
    /** One cut of the image. [endMs] == 0 means "to the end of the file". */
    data class Cut(val number: Int, val title: String, val artist: String, val startMs: Long, val endMs: Long)

    fun toleranceMs(imageDurationMs: Long): Long =
        maxOf((imageDurationMs * TOLERANCE_FRACTION).toLong(), MIN_TOLERANCE_MS)

    /** diff = image − Σ track lengths. Negative = file shorter than the TOC says. */
    fun fits(diffMs: Long, imageDurationMs: Long): Boolean {
        val tol = toleranceMs(imageDurationMs)
        return diffMs >= -tol && diffMs <= tol + PREGAP_MS
    }

    fun isTight(diffMs: Long, imageDurationMs: Long): Boolean =
        abs(diffMs) <= maxOf((imageDurationMs * TIGHT_MATCH_FRACTION).toLong(), 1_000L)

    /** Every (release, medium) whose length fits the image, best first. */
    fun fittingCandidates(imageDurationMs: Long, discNumber: Int, releases: List<MbRelease>): List<Candidate> {
        val fits = ArrayList<Candidate>()
        for (r in releases) for (m in r.media) {
            if (m.tracks.size < 2) continue
            if (m.tracks.any { it.lengthMs <= 0L }) continue          // unknown length → can't verify
            val diff = imageDurationMs - m.totalMs
            if (!fits(diff, imageDurationMs)) continue
            fits += Candidate(r, m, diff)
        }
        // A ".2" image wants medium 2; otherwise closest length, then MusicBrainz's own score.
        return fits.sortedWith(
            compareBy<Candidate>({ if (discNumber > 0 && it.medium.position != discNumber) 1 else 0 })
                .thenBy { abs(it.diffMs) }
                .thenByDescending { it.release.score }
        )
    }

    /** The winning medium, or null when nothing fits or the best fit is ambiguous. */
    fun choose(imageDurationMs: Long, discNumber: Int, releases: List<MbRelease>): Candidate? {
        val sorted = fittingCandidates(imageDurationMs, discNumber, releases)
        if (sorted.isEmpty()) return null
        val best = sorted[0]
        val bestTier = if (discNumber > 0 && best.medium.position != discNumber) 1 else 0
        val margin = (imageDurationMs * AMBIGUITY_MARGIN_FRACTION).toLong()
        for (rival in sorted.drop(1)) {
            val tier = if (discNumber > 0 && rival.medium.position != discNumber) 1 else 0
            if (tier != bestTier) break
            if (sameToc(rival.medium, best.medium)) continue
            if (abs(rival.diffMs) - abs(best.diffMs) <= margin) return null   // two different discs fit equally → don't guess
            break
        }
        return best
    }

    /** Same track count and every track length within slack, OR identical normalised titles. */
    fun sameToc(a: MbMedium, b: MbMedium): Boolean {
        if (a.tracks.size != b.tracks.size) return false
        var lengthsAgree = true; var titlesAgree = true
        for (i in a.tracks.indices) {
            if (abs(a.tracks[i].lengthMs - b.tracks[i].lengthMs) > SAME_TOC_TRACK_SLACK_MS) lengthsAgree = false
            if (norm(a.tracks[i].title) != norm(b.tracks[i].title)) titlesAgree = false
            if (!lengthsAgree && !titlesAgree) return false
        }
        return lengthsAgree || titlesAgree
    }

    private val NON_ALNUM_RE = Regex("[^\\p{L}\\p{Nd}]+")
    fun norm(s: String): String = NON_ALNUM_RE.replace(s.lowercase(), "")

    /**
     * Cumulative cut points. Any positive diff up to [PREGAP_MS] is treated as a leading pregap
     * (track 1 starts at 0 and simply contains that silence; every later boundary shifts by it).
     * Whatever remains of the diff lands in the last track, which always runs to the end of file.
     */
    fun cuts(imageDurationMs: Long, medium: MbMedium, diffMs: Long): List<Cut> {
        val lead = diffMs.coerceIn(0L, PREGAP_MS)
        val tracks = medium.tracks
        val starts = LongArray(tracks.size)
        var cursor = lead
        for (i in tracks.indices) {
            starts[i] = if (i == 0) 0L else cursor
            cursor += tracks[i].lengthMs
        }
        val out = ArrayList<Cut>(tracks.size)
        for (i in tracks.indices) {
            val start = starts[i]
            if (i > 0 && start >= imageDurationMs - 1_000L) break      // TOC longer than file: drop the tail stub
            val end = if (i == tracks.lastIndex) 0L else minOf(starts[i + 1], imageDurationMs)
            out += Cut(tracks[i].position.takeIf { it > 0 } ?: (i + 1), tracks[i].title, tracks[i].artist, start, end)
        }
        // If the tail stub was dropped, the new last cut must run to the end.
        if (out.isNotEmpty() && out.size < tracks.size) {
            val last = out.removeAt(out.lastIndex)
            out += last.copy(endMs = 0L)
        }
        return out
    }
}
