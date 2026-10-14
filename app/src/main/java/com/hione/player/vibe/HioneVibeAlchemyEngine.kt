package com.hione.player.vibe

import android.content.Context
import com.hione.player.BpmEngine
import com.hione.player.LikeStore
import com.hione.player.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * High-Performance Offline Music Vibe & Ontology Alchemy Engine for Hione Music.
 *
 * Implements non-blocking chunked batch processing with coroutine yielding,
 * musical taxonomy clustering, genre ontology mapping, and acoustic attribute synthesis.
 */
object HioneVibeAlchemyEngine {

    data class VibeResult(
        val vibeTitle: String,
        val prompt: String,
        val playlist: List<Track>,
        val algorithmName: String,
        val matchDescription: String
    )

    // =========================================================================
    // 1. GENRE & ARTIST ONTOLOGY KNOWLEDGE GRAPH
    // =========================================================================

    private data class GenreCluster(
        val name: String,
        val emoji: String,
        val keywords: Set<String>,
        val subgenres: Set<String>,
        val relatedGenres: Set<String>,
        val canonicalArtists: Set<String>,
        val typicalBpmRange: IntRange,
        val energyWeight: Float, // 0.0 (ambient) to 1.0 (thrash/metalcore/hardstyle)
        val preferredDurationRange: LongRange = 120_000L..480_000L
    )

    private val GENRE_CLUSTERS = listOf(
        GenreCluster(
            name = "Metal & Heavy Rock",
            emoji = "🤘",
            keywords = setOf("metal", "heavy", "headbang", "riff", "thrash", "death", "black", "doom", "power", "prog", "djent", "nu-metal", "blastbeat", "brutal", "breakdown", "shred", "sludge", "grindcore", "distortion", "guitar solo", "metalcore", "deathcore"),
            subgenres = setOf("heavy metal", "thrash metal", "death metal", "black metal", "doom metal", "progressive metal", "power metal", "nu metal", "djent", "metalcore", "deathcore", "industrial metal", "sludge metal", "post-metal", "speed metal", "symphonic metal", "grindcore", "mathcore", "hard rock", "grunge"),
            relatedGenres = setOf("Punk", "Hard Rock", "Industrial", "Alternative"),
            canonicalArtists = setOf(
                "metallica", "iron maiden", "black sabbath", "judas priest", "slayer", "megadeth", "pantera", "tool", "slipknot", "opeth", "gojira", "mastodon", "avenged sevenfold", "system of a down", "rammstein", "nightwish", "lamb of god", "sepultura", "meshuggah", "korn", "deftones", "killswitch engage", "arch enemy", "trivium", "in flames", "death", "morbid angel", "dio", "ozzy", "motorhead", "ghost", "dream theater", "dragonforce", "anthrax", "danzig", "electric wizard", "sleep", "boris", "deafheaven", "emperor", "mayhem", "burzum", "darkthrone", "cannibal corpse", "behemoth", "cattle decapitation", "lorna shore", "suicide silence", "whitechapel", "periphery", "tesseract", "polyphia", "animals as leaders", "bring me the horizon", "architects", "parkway drive", "august burns red", "bullet for my valentine", "alter bridge", "halestorm", "babymetal", "jinjer", "spiritbox", "underoath", "silverstein", "as i lay dying", "converge", "dillinger escape plan", "static-x", "rob zombie", "mudvayne", "disturbed", "godsmack", "five finger death punch", "seether", "breaking benjamin", "chevelle", "three days grace", "alice in chains", "soundgarden", "queens of the stone age", "kyuss", "clutch", "bad omens", "sleep token", "electric callboy", "cannibal corpse", "lord of acid", "lords of acid", "cradle of filth", "dimmu borgir", "fear factory", "soilwork", "type o negative", "prong", "ministry", "coal chamber", "machine head", "testament", "exodus", "overkill", "kreator", "sodom"
            ),
            typicalBpmRange = 110..220,
            energyWeight = 0.95f,
            preferredDurationRange = 150_000L..600_000L
        ),

        GenreCluster(
            name = "Rock, Punk & Alternative",
            emoji = "🎸",
            keywords = setOf("rock", "punk", "alternative", "grunge", "emo", "indie rock", "post-punk", "pop punk", "garage", "classic rock", "psychedelic", "hard rock", "shoegaze"),
            subgenres = setOf("punk rock", "hard rock", "grunge", "indie rock", "post-punk", "pop punk", "garage rock", "stoner rock", "psychedelic rock", "classic rock", "alternative rock", "emo", "shoegaze", "post-rock"),
            relatedGenres = setOf("Metal & Heavy Rock", "Indie & Folk", "Pop"),
            canonicalArtists = setOf(
                "nirvana", "soundgarden", "pearl jam", "alice in chains", "foo fighters", "green day", "blink-182", "the offspring", "ramones", "the clash", "sex pistols", "queens of the stone age", "arctic monkeys", "radiohead", "muse", "led zeppelin", "ac/dc", "queen", "pink floyd", "the who", "the rolling stones", "deep purple", "jimi hendrix", "guns n' roses", "guns n roses", "aerosmith", "red hot chili peppers", "linkin park", "smashing pumpkins", "weezer", "pixies", "sonic youth", "my chemical romance", "fall out boy", "paramore", "sum 41", "rise against", "the strokes", "the white stripes", "cage the elephant", "royal blood", "the black keys", "greta van fleet", "the killers", "franz ferdinand", "interpol", "joy division", "new order", "the cure", "the smiths", "oasis", "blur", "pulp", "the doors", "creedence clearwater revival", "david bowie", "the beatles"
            ),
            typicalBpmRange = 100..180,
            energyWeight = 0.75f
        ),

        GenreCluster(
            name = "Cyberpunk, Synth & Electronic",
            emoji = "⚡",
            keywords = setOf("cyberpunk", "synth", "electronic", "techno", "house", "trance", "dnb", "drum and bass", "synthwave", "darksynth", "industrial", "dubstep", "bass", "breakbeat", "electro", "glitch", "hardstyle", "future bass", "idm", "edm", "dance"),
            subgenres = setOf("techno", "house", "trance", "drum and bass", "synthwave", "darksynth", "industrial", "dubstep", "breakbeat", "electro", "glitch", "hardstyle", "future bass", "idm", "midtempo", "ebm", "cyberpunk"),
            relatedGenres = setOf("Vocaloid & J-Pop", "Ambient & Atmospheric", "Hip Hop & Trap"),
            canonicalArtists = setOf(
                "daft punk", "the prodigy", "the chemical brothers", "deadmau5", "skrillex", "pendulum", "perturbator", "carpenter brut", "kavinsky", "gesaffelstein", "justice", "aphex twin", "boards of canada", "kraftwerk", "underworld", "m83", "porter robinson", "madeon", "rezz", "noisia", "sub focus", "chase & status", "excision", "subtronics", "flume", "disclosure", "fatboy slim", "massive attack", "portishead", "the glitch mob", "crystal method", "danger", "gessafelstein", "dan terminus", "gunship", "the midnight", "timecop1983", "fm-84", "magic sword", "scandroid", "celldweller", "blue stahli", "kmfdm", "front line assembly", "nine inch nails", "combichrist", "covenant", "vnv nation", "nero", "feed me", "illenium", "san holo", "rl grime", "robert miles", "real mccoy", "2 unlimited"
            ),
            typicalBpmRange = 120..175,
            energyWeight = 0.85f
        ),

        GenreCluster(
            name = "Vocaloid, Anime & J-Pop",
            emoji = "🌸",
            keywords = setOf("vocaloid", "hione", "hatsune", "anime", "j-pop", "jpop", "japan", "japanese", "touhou", "doujin", "utaite", "kawaii", "cute", "j-rock", "city pop", "anison"),
            subgenres = setOf("vocaloid", "j-pop", "anime ost", "city pop", "j-rock", "touhou", "doujin", "utaite", "denpa", "future funk"),
            relatedGenres = setOf("Cyberpunk, Synth & Electronic", "Pop", "Rock, Punk & Alternative"),
            canonicalArtists = setOf(
                "hatsune miku", "hione", "deco*27", "wowaka", "mitchie m", "pinocchiop", "kikuo", "eve", "yoasobi", "kenshi yonezu", "king gnu", "radwimps", "lisa", "aimer", "tatsuro yamashita", "mariya takeuchi", "anri", "miki matsubara", "neru", "cosmo@bousoup", "maretu", "inabakumori", "kanaria", "syudou", "chinozo", "tuyu", "zutomayo", "yorushika", "ado", "minami", "clariS", "supercell", "egoist", "garnidelia", "flow", "asian kung-fu generation", "one ok rock", "man with a mission", "polkadot stingray", "myth & roid", "sawano hiroyuki", "hiroyuki sawano", "yuki kajiura", "reol", "giga", "kz", "livetune", "hachioji p", "ryo", "doriko", "40mp", "jin", "kemu"
            ),
            typicalBpmRange = 120..190,
            energyWeight = 0.80f
        ),

        GenreCluster(
            name = "Hip Hop, Trap & R&B",
            emoji = "🎤",
            keywords = setOf("rap", "hip hop", "hiphop", "trap", "drill", "r&b", "rnb", "boom bap", "bars", "beats", "flow", "rhymes", "urban", "street"),
            subgenres = setOf("boom bap", "trap", "drill", "west coast", "east coast", "lofi hip hop", "conscious rap", "cloud rap", "r&b", "contemporary r&b", "neo-soul"),
            relatedGenres = setOf("Jazz, Soul & Funk", "Cyberpunk, Synth & Electronic", "Pop"),
            canonicalArtists = setOf(
                "kendrick lamar", "eminem", "tupac", "2pac", "notorious b.i.g.", "biggie", "wu-tang clan", "mf doom", "kanye west", "drake", "travis scott", "j. cole", "nas", "jay-z", "outkast", "snoop dogg", "dr. dre", "denzel curry", "jid", "tyler, the creator", "tyler the creator", "mac miller", "the weeknd", "frank ocean", "childish gambino", "asap rocky", "future", "metro boomin", "21 savage", "playboi carti", "lil uzi vert", "post malone", "kid cudi", "run the jewels", "freddie gibbs", "pusha t", "joey badass", "logic", "danny brown", "earl sweatshirt", "schoolboy q", "sza", "anderson .paak", "brent faiyaz", "rakim", "canibus", "sosmula", "\$uicideboy\$"
            ),
            typicalBpmRange = 75..150,
            energyWeight = 0.70f
        ),

        GenreCluster(
            name = "Jazz, Soul & Funk",
            emoji = "🎷",
            keywords = setOf("jazz", "blues", "soul", "funk", "groove", "brass", "sax", "trumpet", "smooth", "swing", "bebop", "motown", "improvisation"),
            subgenres = setOf("bebop", "hard bop", "cool jazz", "modal jazz", "jazz fusion", "smooth jazz", "delta blues", "chicago blues", "soul", "motown", "funk", "neo-soul", "nu jazz"),
            relatedGenres = setOf("Hip Hop, Trap & R&B", "Ambient & Atmospheric", "Indie & Folk"),
            canonicalArtists = setOf(
                "miles davis", "john coltrane", "thelonious monk", "bill evans", "charlie parker", "dave brubeck", "herbie hancock", "b.b. king", "bb king", "muddy waters", "stevie wonder", "marvin gaye", "earth, wind & fire", "earth wind & fire", "parliament", "funkadelic", "james brown", "aretha franklin", "otis redding", "al green", "ray charles", "chet baker", "duke ellington", "louis armstrong", "ella fitzgerald", "nina simone", "stan getz", "kamasi washington", "snarky puppy", "robert glasper", "cory wong", "vulfpeck", "thundercat", "badbadnotgood", "hiatus kaiyote", "casiopea", "t-square"
            ),
            typicalBpmRange = 65..130,
            energyWeight = 0.50f
        ),

        GenreCluster(
            name = "Ambient, Chill & Lo-Fi",
            emoji = "🌙",
            keywords = setOf("ambient", "chill", "relax", "calm", "sleep", "night", "lofi", "lo-fi", "drone", "meditation", "peaceful", "soft", "rain", "study", "cozy", "dream", "ethereal"),
            subgenres = setOf("ambient", "dark ambient", "space ambient", "drone", "lo-fi beats", "chillhop", "downtempo", "chillout", "sleep music", "binaural"),
            relatedGenres = setOf("Classical, Cinema & Soundtracks", "Cyberpunk, Synth & Electronic", "Indie & Folk"),
            canonicalArtists = setOf(
                "brian eno", "stars of the lid", "william basinski", "tim hecker", "biosphere", "steve roach", "robert rich", "carbon based lifeforms", "solar fields", "tycho", "bonobo", "emancipator", "kikagaku moyo", "nujabes", "j dilla", "chilledcow", "lofi girl", "potsu", "idealism", "kupla", "tomppabeats", "bsd.u", "eef", "saib", "elijah who", "in love with a ghost"
            ),
            typicalBpmRange = 50..95,
            energyWeight = 0.15f,
            preferredDurationRange = 180_000L..900_000L
        ),

        GenreCluster(
            name = "Pop, Indie & Acoustic",
            emoji = "✨",
            keywords = setOf("pop", "indie", "acoustic", "folk", "singer-songwriter", "ballad", "vocal", "melodic", "catchy", "radio", "unplugged"),
            subgenres = setOf("indie pop", "folk", "acoustic pop", "singer-songwriter", "dream pop", "bedroom pop", "dance-pop", "synth-pop", "indie folk"),
            relatedGenres = setOf("Rock, Punk & Alternative", "Jazz, Soul & Funk", "Vocaloid & J-Pop"),
            canonicalArtists = setOf(
                "taylor swift", "billie eilish", "lorde", "lana del rey", "phoebe bridgers", "bon iver", "fleet foxes", "sufjan stevens", "cocteau twins", "slowdive", "my bloody valentine", "boygenius", "clairo", "beabadoobee", "mitski", "japanese breakfast", "men i trust", "alvvays", "chvrches", "dualipa", "dua lipa", "ariana grande", "olivia rodrigo", "chappell roan", "charli xcx", "harry styles", "ed sheeran", "adele", "bruno mars", "the 1975", "alanis morissette", "ace of base", "roisin murphy"
            ),
            typicalBpmRange = 90..135,
            energyWeight = 0.55f
        )
    )

    // =========================================================================
    // 2. INTENT PARSER & PROMPT DECONSTRUCTOR
    // =========================================================================

    private data class VibeIntent(
        val targetClusterNames: List<String>,
        val targetEnergy: Float, // 0.0 to 1.0
        val targetMinBpm: Int,
        val targetMaxBpm: Int,
        val audiophileBias: Boolean = false,
        val eraMinYear: Int = 0,
        val eraMaxYear: Int = 9999,
        val titleFormat: String
    )

    private fun parsePromptIntent(prompt: String): VibeIntent {
        val lower = prompt.lowercase(Locale.ROOT)
        val tokens = lower.split(Regex("[\\s,;:.!?_\\-/]+")).filter { it.isNotBlank() }

        val clusterScores = mutableMapOf<String, Float>()
        for (cluster in GENRE_CLUSTERS) {
            var score = 0f
            for (tok in tokens) {
                if (cluster.keywords.contains(tok)) score += 50f
                if (cluster.subgenres.any { it.contains(tok) }) score += 40f
                if (cluster.name.lowercase().contains(tok)) score += 60f
                if (cluster.canonicalArtists.any { it.contains(tok) }) score += 70f
            }
            if (score > 0f) clusterScores[cluster.name] = score
        }

        val isAggressive = tokens.any { it in setOf("heavy", "angry", "rage", "aggressive", "intense", "brutal", "insane", "kill", "fight", "war", "workout", "gym", "pump", "hardcore", "metal", "thrash") }
        val isRelaxed = tokens.any { it in setOf("chill", "relax", "calm", "sleep", "night", "slow", "soft", "peaceful", "rain", "cozy", "study", "focus", "rest", "bed", "dream") }
        val isEuphoric = tokens.any { it in setOf("happy", "hype", "party", "dance", "upbeat", "summer", "sun", "bounce", "joy", "fun", "club", "fest") }
        val isMelancholic = tokens.any { it in setOf("sad", "cry", "tears", "depressed", "melancholy", "heartbreak", "lonely", "dark", "gloom", "winter", "lost") }
        val isCyberpunk = tokens.any { it in setOf("cyberpunk", "neon", "future", "tokyo", "synth", "matrix", "hacker", "drive", "nightdrive", "scifi", "sci-fi") }
        val isAudiophile = tokens.any { it in setOf("audiophile", "dsd", "flac", "hires", "hi-res", "lossless", "master", "acoustic", "studio", "hifi", "masterhifi") }

        val matchedClusters = clusterScores.entries.sortedByDescending { it.value }.map { it.key }
        val targetClusters = if (matchedClusters.isNotEmpty()) {
            matchedClusters
        } else {
            when {
                isAggressive -> listOf("Metal & Heavy Rock", "Rock, Punk & Alternative", "Cyberpunk, Synth & Electronic")
                isRelaxed -> listOf("Ambient, Chill & Lo-Fi", "Jazz, Soul & Funk")
                isCyberpunk -> listOf("Cyberpunk, Synth & Electronic", "Vocaloid & J-Pop")
                isEuphoric -> listOf("Vocaloid & J-Pop", "Pop, Indie & Acoustic", "Cyberpunk, Synth & Electronic")
                isMelancholic -> listOf("Rock, Punk & Alternative", "Ambient, Chill & Lo-Fi", "Pop, Indie & Acoustic")
                else -> emptyList()
            }
        }

        var targetEnergy = 0.5f
        var minBpm = 60
        var maxBpm = 200

        when {
            isAggressive -> { targetEnergy = 0.95f; minBpm = 120; maxBpm = 240 }
            isRelaxed -> { targetEnergy = 0.20f; minBpm = 45; maxBpm = 98 }
            isEuphoric -> { targetEnergy = 0.80f; minBpm = 118; maxBpm = 175 }
            isMelancholic -> { targetEnergy = 0.35f; minBpm = 55; maxBpm = 110 }
            isCyberpunk -> { targetEnergy = 0.75f; minBpm = 110; maxBpm = 160 }
        }

        var eraMin = 0
        var eraMax = 9999
        when {
            tokens.any { it in setOf("70s", "1970s", "seventies") } -> { eraMin = 1970; eraMax = 1979 }
            tokens.any { it in setOf("80s", "1980s", "eighties") } -> { eraMin = 1980; eraMax = 1989 }
            tokens.any { it in setOf("90s", "1990s", "nineties") } -> { eraMin = 1990; eraMax = 1999 }
            tokens.any { it in setOf("2000s", "00s", "noughties", "y2k") } -> { eraMin = 2000; eraMax = 2009 }
            tokens.any { it in setOf("2010s", "10s") } -> { eraMin = 2010; eraMax = 2019 }
            tokens.any { it in setOf("2020s", "modern", "new", "recent") } -> { eraMin = 2020; eraMax = 2030 }
        }

        val cleanName = prompt.trim().split(" ").filter { it.isNotBlank() }.take(3).joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
        val prefixEmoji = if (targetClusters.isNotEmpty()) {
            GENRE_CLUSTERS.firstOrNull { it.name == targetClusters.first() }?.emoji ?: "✨"
        } else "✨"

        return VibeIntent(
            targetClusterNames = targetClusters,
            targetEnergy = targetEnergy,
            targetMinBpm = minBpm,
            targetMaxBpm = maxBpm,
            audiophileBias = isAudiophile,
            eraMinYear = eraMin,
            eraMaxYear = eraMax,
            titleFormat = "$cleanName mix"
        )
    }

    // =========================================================================
    // 3. ASYNCHRONOUS BATCHED TRACK SCORING & SYNTHESIS
    // =========================================================================

    suspend fun synthesizeFromPrompt(
        context: Context,
        tracks: List<Track>,
        prompt: String,
        onProgress: ((processed: Int, total: Int, status: String) -> Unit)? = null
    ): VibeResult = withContext(Dispatchers.Default) {
        if (tracks.isEmpty()) {
            return@withContext VibeResult("Empty Library", prompt, emptyList(), "None", "No tracks available")
        }

        val rawClean = prompt.trim()
        if (rawClean.isBlank()) {
            return@withContext generateRandomVibe(context, tracks, onProgress)
        }

        val totalTracks = tracks.size
        onProgress?.invoke(0, totalTracks, "Reading your request…")

        val intent = parsePromptIntent(rawClean)
        val lowerPrompt = rawClean.lowercase(Locale.ROOT)
        val promptTokens = lowerPrompt.split(Regex("[\\s,;:.!?_\\-/]+")).filter { it.length > 1 }.toSet()
        val matchedClusters = GENRE_CLUSTERS.filter { it.name in intent.targetClusterNames }

        val scoredTracks = mutableListOf<Pair<Track, Float>>()
        val chunkSize = 500
        var processedCount = 0

        // Batched iteration with coroutine yielding to keep DAP CPU smooth
        val chunks = tracks.chunked(chunkSize)
        for (chunk in chunks) {
            for (track in chunk) {
                var score = 0f
                val tArtist = track.artist.lowercase(Locale.ROOT)
                val tTitle = track.title.lowercase(Locale.ROOT)
                val tAlbum = track.album.lowercase(Locale.ROOT)
                val tPath = track.path.lowercase(Locale.ROOT)

                // A. ONTOLOGY & KNOWLEDGE GRAPH CLUSTERING (Strongest weight)
                for (cluster in matchedClusters) {
                    // 1. Direct Canonical Artist Match
                    val artistMatches = cluster.canonicalArtists.any { canon ->
                        tArtist.contains(canon) || tPath.contains("/$canon/") || tPath.contains("/$canon -")
                    }
                    if (artistMatches) {
                        score += 90f
                    }

                    // 2. Folder / Path / Tag Genre Semantics
                    val pathOrAlbumMatchesGenre = cluster.subgenres.any { subg ->
                        tPath.contains(subg) || tAlbum.contains(subg)
                    } || cluster.keywords.any { kw ->
                        tPath.contains("/$kw/") || tPath.contains(" $kw ") || tAlbum.contains(kw)
                    }
                    if (pathOrAlbumMatchesGenre) {
                        score += 50f
                    }

                    // 3. Subgenre match in title
                    val titleMatchesSubgenre = cluster.subgenres.any { tTitle.contains(it) }
                    if (titleMatchesSubgenre) {
                        score += 25f
                    }
                }

                // B. EXACT METADATA MATCH (Artist gets high boost, Title gets modest boost only if not filler)
                for (tok in promptTokens) {
                    // Do not let "metal" match Metallica purely as a title token without artist ontology
                    if (tArtist.contains(tok)) {
                        score += 40f
                    }
                    if (tAlbum.contains(tok)) {
                        score += 20f
                    }
                    if (tTitle.contains(tok) && tok !in setOf("music", "song", "track", "audio", "vibe", "sound", "band", "metal", "rock", "rap", "pop")) {
                        score += 10f
                    }
                }

                // C. TEMPO & BPM COHERENCE
                val cachedBpm = BpmEngine.getCachedBpm(track.path)
                if (cachedBpm != null && cachedBpm > 0) {
                    if (cachedBpm in intent.targetMinBpm..intent.targetMaxBpm) {
                        score += 25f
                    } else {
                        val diff = abs(cachedBpm - (intent.targetMinBpm + intent.targetMaxBpm) / 2)
                        if (diff > 50) score -= 20f
                    }
                }

                // D. DURATION & ACOUSTIC FORMAT
                val isLossless = track.mime.contains("flac", true) || track.path.endsWith(".dsf", true) || track.path.endsWith(".dff", true) || track.bitrateKbps > 850
                if (intent.audiophileBias && isLossless) {
                    score += 35f
                    if (track.bitrateKbps > 1400) score += 20f
                }

                // E. DECADE / ERA MATCHING
                if (intent.eraMinYear > 0 && track.year in intent.eraMinYear..intent.eraMaxYear) {
                    score += 40f
                }

                // F. USER LIKES AFFINITY BOOST
                if (LikeStore.isLiked(track.id)) {
                    score += 12f
                }

                // G. DETERMINISTIC HARMONIC JITTER
                val seedJitter = ((track.id * 37 + rawClean.hashCode()) % 100).toFloat() / 100f * 6f
                score += seedJitter

                scoredTracks.add(Pair(track, score))
            }

            processedCount += chunk.size
            onProgress?.invoke(processedCount, totalTracks, "Checked $processedCount of $totalTracks tracks…")
            yield() // Non-blocking yield for other tasks / UI updates
        }

        // Sort candidates
        val sortedScored = scoredTracks.sortedByDescending { it.second }
        val maxScore = sortedScored.firstOrNull()?.second ?: 0f

        val threshold = if (maxScore > 30f) maxScore * 0.25f else 10f
        val strongCandidates = sortedScored.filter { it.second >= threshold }.map { it.first }

        // Artist diversity guard: Max 2 tracks per artist in final mix
        val finalPlaylist = mutableListOf<Track>()
        val artistCountMap = mutableMapOf<String, Int>()

        val candidatePool = if (strongCandidates.size >= 12) {
            strongCandidates
        } else {
            (strongCandidates + tracks.shuffled(Random(rawClean.hashCode()))).distinctBy { it.id }
        }

        for (track in candidatePool) {
            val count = artistCountMap[track.artist] ?: 0
            if (count < 2) {
                finalPlaylist.add(track)
                artistCountMap[track.artist] = count + 1
                if (finalPlaylist.size >= 32) break
            }
        }

        val primaryClusterName = intent.targetClusterNames.firstOrNull() ?: "Mixed"
        onProgress?.invoke(totalTracks, totalTracks, "Picked ${finalPlaylist.size} tracks")

        return@withContext VibeResult(
            vibeTitle = intent.titleFormat,
            prompt = rawClean,
            playlist = finalPlaylist,
            algorithmName = "Genre and tag match",
            matchDescription = "${finalPlaylist.size} tracks matched to $primaryClusterName"
        )
    }

    // =========================================================================
    // 4. SERENDIPITOUS RANDOM VIBE GENERATOR
    // =========================================================================

    private val RANDOM_PRESETS = listOf(
        Pair("Cyberpunk night", "cyberpunk darksynth industrial electronic fast bass future tokyo"),
        Pair("Metal shred", "heavy metal thrash progressive metalcore guitar solo distortion brutal"),
        Pair("Upbeat Vocaloid", "hatsune miku vocaloid anime cute upbeat jpop idol"),
        Pair("Lo-fi for focus", "lofi chill relax ambient calm night rain soft piano beats"),
        Pair("90s grunge and alt-rock", "grunge 90s alternative rock nirvana guitars heavy distortion raw"),
        Pair("Hi-res acoustic", "audiophile dsd flac acoustic vocal master reference lossless"),
        Pair("Workout (150+ BPM)", "fast running workout gym high energy bpm sprint rhythm pump"),
        Pair("Jazz and soul", "jazz bebop smooth sax trumpet soul funk groove chill lounge"),
        Pair("80s city pop", "city pop 80s vintage synthwave disco funk retro japan summer"),
        Pair("Film and game scores", "classical orchestral soundtrack film score hans zimmer strings epic")
    )

    suspend fun generateRandomVibe(
        context: Context,
        tracks: List<Track>,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): VibeResult {
        if (tracks.isEmpty()) {
            return VibeResult("Empty Library", "", emptyList(), "Serendipity", "No tracks available")
        }

        val (title, promptKeywords) = RANDOM_PRESETS[Random.nextInt(RANDOM_PRESETS.size)]
        val result = synthesizeFromPrompt(context, tracks, promptKeywords, onProgress)
        return result.copy(
            vibeTitle = title,
            prompt = promptKeywords,
            matchDescription = "Random mix · ${result.playlist.size} tracks"
        )
    }
}
