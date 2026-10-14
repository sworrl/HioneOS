package com.hione.player.wanted

import com.hione.player.Track
import java.text.Normalizer
import java.util.Locale

/**
 * Loose title/artist matching between what the radio identified and what is in the library.
 *
 * Radio IDs and file tags disagree in boring ways: "(Remastered 2011)", "- Radio Edit",
 * "feat. Someone", "&" vs "and", accents, punctuation. Both sides are folded the same way and then
 * compared. Titles must agree after folding; artists only need to share the primary name, because
 * one side often lists the featured artist and the other does not.
 */
object WantedMatch {
    private val BRACKETS = Regex("""\s*[(\[{][^)\]}]*[)\]}]""")
    private val TITLE_FEAT = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val DASH_TAG = Regex(
        """\s+-\s+.*\b(remaster(ed)?|version|edit|live|mix|mono|stereo|demo|acoustic|single|radio|bonus|explicit|clean)\b.*$""",
        RegexOption.IGNORE_CASE
    )
    private val ARTIST_SPLIT = Regex(
        """\s+(feat\.?|ft\.?|featuring|with|x|vs\.?|and)\s+|\s*[&,;/+]\s*""",
        RegexOption.IGNORE_CASE
    )
    private val MARKS = Regex("""\p{Mn}+""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")

    private fun fold(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFD)
        t = MARKS.replace(t, "")
        t = NON_ALNUM.replace(t, " ")
        return SPACES.replace(t, " ").trim()
    }

    fun title(raw: String): String {
        var t = raw.lowercase(Locale.US)
        t = BRACKETS.replace(t, " ")
        t = DASH_TAG.replace(t, "")
        t = TITLE_FEAT.replace(t, "")
        t = t.replace("&", " and ")
        val f = fold(t)
        return f.ifEmpty { fold(raw.lowercase(Locale.US)) }
    }

    /** The whole artist credit, folded. */
    fun artistFull(raw: String): String {
        var t = raw.lowercase(Locale.US)
        t = BRACKETS.replace(t, " ")
        t = t.replace("&", " and ")
        return fold(t).removePrefix("the ").trim()
    }

    /** Just the first-billed artist, folded. */
    fun artistPrimary(raw: String): String {
        val t = BRACKETS.replace(raw.lowercase(Locale.US), " ")
        val first = ARTIST_SPLIT.split(t).firstOrNull { it.isNotBlank() } ?: t
        return fold(first).removePrefix("the ").trim()
    }

    fun key(title: String, artist: String): String = title(title) + "|" + artistPrimary(artist)

    fun artistMatches(wanted: String, candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val wp = artistPrimary(wanted)
        val cp = artistPrimary(candidate)
        if (wp.isEmpty() || cp.isEmpty()) return false
        if (wp == cp) return true
        val wf = artistFull(wanted)
        val cf = artistFull(candidate)
        if (wf == cf) return true
        // "Artist A and Artist B" in the library vs "Artist B" from the radio, or the reverse.
        return (cp.length >= 3 && containsWord(wf, cp)) || (wp.length >= 3 && containsWord(cf, wp))
    }

    private fun containsWord(hay: String, needle: String): Boolean =
        (" $hay ").contains(" $needle ")

    /**
     * Finds the best library track for a wanted song, or null. Callers that match many rows should
     * use [index] once and [find] per row instead of re-folding the whole library each time.
     */
    class Index(tracks: List<Track>) {
        private val byTitle: Map<String, List<Track>> = HashMap<String, MutableList<Track>>().also { m ->
            for (t in tracks) {
                if (t.title.isBlank()) continue
                m.getOrPut(title(t.title)) { ArrayList(1) }.add(t)
            }
        }

        fun find(wantTitle: String, wantArtist: String): Track? {
            val cands = byTitle[title(wantTitle)] ?: return null
            val hits = cands.filter { artistMatches(wantArtist, it.artist) || artistMatches(wantArtist, it.albumArtist) }
            // Prefer a real file over a slice of a disc image, then the better-sounding copy.
            return hits.sortedWith(compareBy<Track> { it.parentId != 0L }.thenByDescending { it.bitrateKbps }).firstOrNull()
        }

        /** Looser search for the "find in library" button: any track whose title shares the folded title. */
        fun search(tracks: List<Track>, wantTitle: String, wantArtist: String, limit: Int = 8): List<Track> {
            val exact = byTitle[title(wantTitle)].orEmpty()
            val ft = title(wantTitle)
            val wp = artistPrimary(wantArtist)
            val loose = if (ft.length < 3) emptyList() else tracks.asSequence()
                .filter { it !in exact }
                .filter { t -> title(t.title).contains(ft) || (wp.length >= 3 && containsWord(artistFull(t.artist), wp) && title(t.title).split(' ').any { w -> w.length > 3 && ft.contains(w) }) }
                .take(limit)
                .toList()
            return (exact + loose).distinctBy { it.id }.take(limit)
        }
    }
}
