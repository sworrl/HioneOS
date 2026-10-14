package com.hione.player.artistart

import android.content.Context
import com.hione.player.canonicalArtistKey
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Everything we know about a resolved artist photo — where it came from and how to credit it.
 * Persisted as JSON in the [ArtistPhotoCache] index so the credit dialog works offline too.
 */
data class ArtistPhotoInfo(
    /** "wikidata" (Commons file via a Wikidata P18 claim), "wikipedia" (page thumbnail), "local" (user file). */
    val source: String,
    /** Remote image URL, or the absolute path / content URI of a local override file. */
    val imageUrl: String,
    /** Commons/Wikipedia file name (without the "File:" prefix), or the local file name. */
    val fileName: String,
    /** Page to send the user to for the full attribution (file description page / article). */
    val pageUrl: String,
    val license: String = "",
    val licenseUrl: String = "",
    val author: String = "",
    /** Wikidata Q-id when the match came through Wikidata, "" otherwise. */
    val entityId: String = "",
    /** The matched entity's label / article title — shown so the user can sanity-check the match. */
    val entityLabel: String = "",
    val resolvedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("source", source); put("imageUrl", imageUrl); put("fileName", fileName); put("pageUrl", pageUrl)
        put("license", license); put("licenseUrl", licenseUrl); put("author", author)
        put("entityId", entityId); put("entityLabel", entityLabel); put("at", resolvedAtMs)
    }.toString()

    companion object {
        fun fromJson(s: String): ArtistPhotoInfo? = runCatching {
            val o = JSONObject(s)
            if (!o.has("imageUrl")) return null
            ArtistPhotoInfo(
                source = o.optString("source"), imageUrl = o.optString("imageUrl"),
                fileName = o.optString("fileName"), pageUrl = o.optString("pageUrl"),
                license = o.optString("license"), licenseUrl = o.optString("licenseUrl"),
                author = o.optString("author"), entityId = o.optString("entityId"),
                entityLabel = o.optString("entityLabel"), resolvedAtMs = o.optLong("at")
            )
        }.getOrNull()
    }
}

/**
 * Finds a real photo for an artist name, or nothing at all. The bar for "found" is deliberately
 * high — a wrong person's face on an artist page is far worse than the album-art fallback:
 *
 *  1. Wikidata `wbsearchentities` on the name. Only candidates whose label or alias equals the
 *     name after the same normalisation the library uses to merge artist spellings
 *     ([canonicalArtistKey]) survive — no fuzzy/prefix matches.
 *  2. `wbgetentities` for those: reject disambiguation/list pages; accept musical groups outright;
 *     accept humans only when their occupation (P106) or description says they make music.
 *     Prefer an exact-label match, then the entity with the most sitelinks (fame proxy) when two
 *     genuinely-musical exact matches collide.
 *  3. Image = P18 (Commons file) → Special:FilePath thumbnail; license/author from the Commons
 *     `imageinfo` extmetadata so the credit is honest. If the entity has no P18 but has an enwiki
 *     article, use that article's REST-summary thumbnail instead.
 *  4. No Wikidata match at all → Wikipedia REST summary for the plain title (+ "(band)" /
 *     "(musician)"), accepted only when the page is a standard article whose title equals the
 *     name and whose description/extract is about music.
 *
 * Pure network + parsing; no caching here (see [ArtistPhotoCache]). Network failures, HTTP 429
 * and 5xx throw [TransientFailure] so callers do NOT negative-cache them for 30 days.
 */
object ArtistPhotoResolver {
    // Wikimedia's API etiquette requires an identifying User-Agent with contact info.
    const val USER_AGENT = "HioneMusic/2.0 (HiBy M500 HioneOS; contact@falcontechnix.com)"
    const val IMAGE_WIDTH = 512

    class TransientFailure(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    // Wikidata "instance of" (P31) values that are unambiguously music acts.
    private val GROUP_TYPES = setOf(
        "Q215380",   // musical group
        "Q2088357",  // musical ensemble
        "Q5741069",  // rock band
        "Q9212979",  // musical duo
        "Q641066",   // girl group
        "Q216337",   // boy band
        "Q42998",    // orchestra (instance-of on many named orchestras)
        "Q2020329",  // string quartet
        "Q1076486"   // big band
    )
    private val HUMAN_TYPES = setOf("Q5", "Q215627")          // human, person
    private val REJECT_TYPES = setOf("Q4167410", "Q13406463") // disambiguation page, list page
    // P106 occupations that make a same-named human a plausible music act.
    private val MUSIC_OCCUPATIONS = setOf(
        "Q177220",   // singer
        "Q639669",   // musician
        "Q2252262",  // rapper
        "Q36834",    // composer
        "Q130857",   // DJ
        "Q183945",   // record producer
        "Q753110",   // songwriter
        "Q855091",   // guitarist
        "Q488205",   // singer-songwriter
        "Q158852",   // conductor
        "Q486748",   // pianist
        "Q386854",   // drummer
        "Q1259917",  // violinist
        "Q1415090",  // film score composer
        "Q15981151", // jazz musician
        "Q806349",   // bandleader
        "Q584301",   // electronic musician (also used for "electronic music artist")
        "Q1278335",  // beatmaker
        "Q2865819"   // opera singer
    )
    private val MUSIC_DESC_RE = Regex(
        "(?i)\\b(singer|musician|band|rapper|composer|dj|disc jockey|record producer|music producer|songwriter|vocalist|" +
            "guitarist|pianist|drummer|bassist|violinist|cellist|conductor|orchestra|choir|vocaloid|virtual singer|" +
            "idol|beatmaker|music project|musical project|recording artist|music artist|music duo|musical duo|" +
            "(?:music|musical|hip.?hop|rock|pop|vocal|jazz|metal|punk|rap|electronic|indie|folk|soul|r&b|reggae|" +
            "country|blues|k-?pop|j-?pop|dance|house|techno|trance|ska|emo|noise|ambient|dubstep|edm|grime|drill)" +
            "\\s+(?:group|duo|trio|quartet|project|act|collective|unit|artist|band|ensemble|producer|outfit|supergroup))\\b"
    )

    // ---------------------------------------------------------------------------------------------
    // Public entry point
    // ---------------------------------------------------------------------------------------------

    /**
     * @param names one or more spellings to try, best first (display name, parenthetical English
     *              reading, transliteration). Stops at the first confident hit.
     */
    fun resolve(ctx: Context, names: List<String>): ArtistPhotoInfo? {
        val tried = LinkedHashSet<String>()
        for (raw in names) {
            val name = raw.trim()
            if (name.length < 2 || !tried.add(name.lowercase())) continue
            val wantKey = canonicalArtistKey(name, ctx)
            if (wantKey.isBlank()) continue
            resolveViaWikidata(ctx, name, wantKey)?.let { return it }
        }
        // Wikidata found nothing usable for any spelling — try Wikipedia article titles directly.
        for (name in tried) {
            val wantKey = canonicalArtistKey(name, ctx)
            if (wantKey.isBlank()) continue
            resolveViaWikipedia(ctx, name, wantKey)?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Wikidata
    // ---------------------------------------------------------------------------------------------

    private class Candidate(val id: String, val label: String, val exactLabel: Boolean)

    private fun resolveViaWikidata(ctx: Context, name: String, wantKey: String): ArtistPhotoInfo? {
        val hasCjk = name.any { it.code > 0x2E7F }
        val languages = if (hasCjk) listOf("en", "ja") else listOf("en")
        val candidates = LinkedHashMap<String, Candidate>()
        for (lang in languages) {
            val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=${enc(name)}" +
                "&language=$lang&uselang=en&type=item&limit=7&format=json"
            val res = getJson(url)?.optJSONArray("search") ?: continue
            for (i in 0 until res.length()) {
                val e = res.optJSONObject(i) ?: continue
                val id = e.optString("id"); if (id.isBlank()) continue
                val label = e.optString("label")
                val matchText = e.optJSONObject("match")?.optString("text") ?: ""
                val exactLabel = label.isNotBlank() && canonicalArtistKey(label, ctx) == wantKey
                val exactAlias = matchText.isNotBlank() && canonicalArtistKey(matchText, ctx) == wantKey
                if (exactLabel || exactAlias) candidates.putIfAbsent(id, Candidate(id, label, exactLabel))
            }
            if (candidates.isNotEmpty()) break
        }
        if (candidates.isEmpty()) return null

        val ids = candidates.keys.take(6).joinToString("|")
        val entities = getJson(
            "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${enc(ids)}" +
                "&props=claims|descriptions|labels|sitelinks&languages=en&format=json"
        )?.optJSONObject("entities") ?: return null

        class Accepted(val c: Candidate, val image: String?, val enwiki: String?, val sitelinks: Int, val label: String)
        val accepted = ArrayList<Accepted>()
        for ((id, c) in candidates) {
            val ent = entities.optJSONObject(id) ?: continue
            val claims = ent.optJSONObject("claims") ?: JSONObject()
            val types = claimItemIds(claims, "P31")
            if (types.any { it in REJECT_TYPES }) continue
            val desc = ent.optJSONObject("descriptions")?.optJSONObject("en")?.optString("value") ?: ""
            val musical = when {
                types.any { it in GROUP_TYPES } -> true
                types.any { it in HUMAN_TYPES } ->
                    claimItemIds(claims, "P106").any { it in MUSIC_OCCUPATIONS } || MUSIC_DESC_RE.containsMatchIn(desc)
                // Unknown/other types (virtual singers, projects, characters): the description has
                // to say "music" in so many words, on top of the exact-name match.
                else -> MUSIC_DESC_RE.containsMatchIn(desc)
            }
            if (!musical) continue
            val image = claims.optJSONArray("P18")?.let { arr ->
                (0 until arr.length()).asSequence().mapNotNull { i ->
                    arr.optJSONObject(i)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")
                }.firstOrNull { it.isNotBlank() }
            }
            val enwiki = ent.optJSONObject("sitelinks")?.optJSONObject("enwiki")?.optString("title")?.takeIf { it.isNotBlank() }
            val sitelinks = ent.optJSONObject("sitelinks")?.length() ?: 0
            val label = ent.optJSONObject("labels")?.optJSONObject("en")?.optString("value")?.takeIf { it.isNotBlank() } ?: c.label
            accepted += Accepted(c, image, enwiki, sitelinks, label)
        }
        if (accepted.isEmpty()) return null

        val best = accepted
            .sortedWith(compareByDescending<Accepted> { it.image != null }.thenByDescending { it.c.exactLabel }.thenByDescending { it.sitelinks })
            .first()

        best.image?.let { file ->
            val meta = commonsMeta(file)
            return ArtistPhotoInfo(
                source = "wikidata",
                imageUrl = commonsThumbUrl(file, IMAGE_WIDTH),
                fileName = file,
                pageUrl = "https://commons.wikimedia.org/wiki/File:${enc(file.replace(' ', '_'))}",
                license = meta?.license ?: "",
                licenseUrl = meta?.licenseUrl ?: "",
                author = meta?.author ?: "",
                entityId = best.c.id,
                entityLabel = best.label
            )
        }
        // Confident entity, but Wikidata has no P18 — the enwiki article's lead image will do.
        best.enwiki?.let { title ->
            summaryPhoto(title, best.c.id, best.label, requireMusicDesc = false, wantKey = null, ctx = ctx)?.let { return it }
        }
        return null
    }

    private fun claimItemIds(claims: JSONObject, prop: String): List<String> {
        val arr = claims.optJSONArray(prop) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
                ?.optJSONObject("value")?.optString("id")
            if (!id.isNullOrBlank()) out += id
        }
        return out
    }

    private fun commonsThumbUrl(file: String, width: Int): String =
        "https://commons.wikimedia.org/wiki/Special:FilePath/${enc(file.replace(' ', '_'))}?width=$width"

    private class CommonsMeta(val license: String, val licenseUrl: String, val author: String)

    /** License + author for a Commons file (best effort — a failure here never blocks the photo). */
    private fun commonsMeta(file: String): CommonsMeta? = try {
        val url = "https://commons.wikimedia.org/w/api.php?action=query&titles=${enc("File:$file")}" +
            "&prop=imageinfo&iiprop=extmetadata&iiextmetadatafilter=LicenseShortName|LicenseUrl|Artist|Credit&format=json"
        val pages = getJson(url)?.optJSONObject("query")?.optJSONObject("pages")
        val page = pages?.keys()?.asSequence()?.firstOrNull()?.let { pages.optJSONObject(it) }
        val meta = page?.optJSONArray("imageinfo")?.optJSONObject(0)?.optJSONObject("extmetadata")
        if (meta == null) null else CommonsMeta(
            license = meta.optJSONObject("LicenseShortName")?.optString("value")?.let(::stripHtml) ?: "",
            licenseUrl = meta.optJSONObject("LicenseUrl")?.optString("value")?.let(::stripHtml) ?: "",
            author = (meta.optJSONObject("Artist")?.optString("value") ?: meta.optJSONObject("Credit")?.optString("value") ?: "")
                .let(::stripHtml).take(160)
        )
    } catch (_: Throwable) { null }

    // ---------------------------------------------------------------------------------------------
    // Wikipedia REST summary
    // ---------------------------------------------------------------------------------------------

    private fun resolveViaWikipedia(ctx: Context, name: String, wantKey: String): ArtistPhotoInfo? {
        for (title in listOf(name, "$name (band)", "$name (musician)")) {
            summaryPhoto(title, "", "", requireMusicDesc = true, wantKey = wantKey, ctx = ctx)?.let { return it }
        }
        return null
    }

    private fun summaryPhoto(
        title: String, entityId: String, entityLabel: String,
        requireMusicDesc: Boolean, wantKey: String?, ctx: Context
    ): ArtistPhotoInfo? {
        val s = getJson("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_'))}") ?: return null
        if (s.optString("type") != "standard") return null // "disambiguation", "no-extract", …
        val pageTitle = s.optString("title")
        if (wantKey != null) {
            val plain = pageTitle.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")
            if (canonicalArtistKey(plain, ctx) != wantKey) return null
            val desc = s.optString("description") + " " + s.optString("extract").take(400)
            if (requireMusicDesc && !MUSIC_DESC_RE.containsMatchIn(desc)) return null
        }
        val thumb = s.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() } ?: return null
        val original = s.optJSONObject("originalimage")
        val origW = original?.optInt("width") ?: 0
        val imageUrl = when {
            origW in 1..IMAGE_WIDTH -> original?.optString("source")?.takeIf { it.isNotBlank() } ?: thumb
            else -> thumb.replace(Regex("/(\\d+)px-"), "/${IMAGE_WIDTH}px-")
        }
        val fileName = runCatching {
            java.net.URLDecoder.decode(thumb.substringAfterLast('/'), "UTF-8").replace(Regex("^\\d+px-"), "").replace('_', ' ')
        }.getOrDefault(thumb.substringAfterLast('/'))
        val onCommons = thumb.contains("/wikipedia/commons/")
        val meta = if (onCommons) commonsMeta(fileName) else null
        val filePage = if (onCommons) "https://commons.wikimedia.org/wiki/File:${enc(fileName.replace(' ', '_'))}"
                       else "https://en.wikipedia.org/wiki/File:${enc(fileName.replace(' ', '_'))}"
        return ArtistPhotoInfo(
            source = if (entityId.isNotBlank()) "wikidata" else "wikipedia",
            imageUrl = imageUrl,
            fileName = fileName,
            pageUrl = filePage,
            license = meta?.license ?: "",
            licenseUrl = meta?.licenseUrl ?: "",
            author = meta?.author ?: "",
            entityId = entityId,
            entityLabel = entityLabel.ifBlank { pageTitle }
        )
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", accept)
        return c
    }

    /** GET a JSON document. 404 → null; 429/5xx/network error → [TransientFailure]; other → null. */
    fun getJson(url: String): JSONObject? {
        var c: HttpURLConnection? = null
        try {
            c = open(url, "application/json")
            val code = c.responseCode
            when {
                code == 200 -> {
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    return runCatching { JSONObject(text) }.getOrNull()
                }
                code == 429 || code >= 500 -> throw TransientFailure("HTTP $code for $url")
                else -> return null
            }
        } catch (e: IOException) {
            throw TransientFailure(e.message ?: "network error", e)
        } finally { c?.disconnect() }
    }

    /**
     * Download image bytes (≤ [maxBytes]). 404/other → null (permanent for this URL);
     * network/429/5xx → [TransientFailure].
     */
    fun downloadBytes(url: String, maxBytes: Int = 6 * 1024 * 1024): ByteArray? {
        var c: HttpURLConnection? = null
        try {
            c = open(url, "image/*")
            val code = c.responseCode
            if (code == 429 || code >= 500) throw TransientFailure("HTTP $code for $url")
            if (code != 200) return null
            val out = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            throw TransientFailure(e.message ?: "network error", e)
        } finally { c?.disconnect() }
    }
}
