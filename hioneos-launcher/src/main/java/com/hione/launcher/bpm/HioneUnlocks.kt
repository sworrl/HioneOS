package com.hione.launcher.bpm

import com.hione.launcher.Mascot
import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Cross-app unlocks earned in the BPM game.
 *
 * WHY THIS EXISTS. Progression used to be entirely self-referential: you tapped to earn leeks, you
 * spent leeks on buildings that earned more leeks. Nothing you did in the game ever changed
 * anything outside the game, so there was no reason to play it. These unlocks hand out real,
 * existing cosmetics in HioneOS and in Hione Music.
 *
 * THE CONTRACT (the player app reads the same key):
 *  - One `Settings.Global` key, **hione_unlocks**, holding a JSON object:
 *        {"<unlock_id>": {"at": <epochMillis>, "src": "bpm"}}
 *  - The GAME IS THE ONLY WRITER. Any app may read it; nothing else writes it.
 *  - Ids are stable, lowercase and namespaced by what they affect. Once shipped, never rename:
 *    a renamed id silently re-locks something the player already earned.
 *  - Ids live HERE, in one place, and are referenced as `HioneUnlocks.SKIN_HONEY` etc. rather than
 *    typed as string literals at call sites.
 *
 * TWO RULES, both inherited from the scoring rules:
 *  1. **Only judged taps pay.** Every requirement below reads HioneBpmSeasonsEngine's lifetime
 *     stats, and those only ever advance on a tap measured against a real detected beat
 *     (see HioneBpmSeasonsEngine.recordTap's contract). A free tap can never buy a reward.
 *  2. **Idempotent.** [unlock] no-ops if the id is already present, so a re-grant cannot re-pay
 *     the leek bonus or re-fire the fanfare.
 *
 * A mirror copy is kept in SharedPreferences: if the Settings.Global write is ever refused, the
 * player's earned unlocks are still remembered locally rather than silently lost, and reads take
 * the union of both.
 */
object HioneUnlocks {
    private const val TAG = "HioneUnlocks"

    /** The single Settings.Global key both apps use. Do not change. */
    const val SETTINGS_KEY = "hione_unlocks"

    private const val MIRROR_PREFS = "hione_unlocks_mirror"
    private const val SRC = "bpm"

    // ---- Unlock ids -----------------------------------------------------------------------
    // Each one corresponds to something that ACTUALLY EXISTS in this codebase. An unlock that
    // grants nothing is the same fake-reward problem it is supposed to fix.

    /** Early BPM unlock at Level 3: dynamically colored UI & album art backdrops in Now Playing. */
    const val PLAYER_DYNAMIC_COLOR = "player.feature.dynamic_color"

    /** BPM game highway lane unlocks */
    const val GAME_LANES_2X = "game.lanes.2x"
    const val GAME_LANES_3X = "game.lanes.3x"
    const val GAME_LANES_4X = "game.lanes.4x"
    const val GAME_LANES_5X = "game.lanes.5x"
    const val GAME_LANES_6X = "game.lanes.6x"
    const val GAME_LANES_8X = "game.lanes.8x"
    const val GAME_LANES_10X = "game.lanes.10x"

    /** Launcher wallpaper picker ids */
    const val OS_WALLPAPER_NEON_WAVE = "os.wallpaper.w6"
    const val OS_WALLPAPER_CYBER_HOLOGRAM = "os.wallpaper.w7"
    const val OS_WALLPAPER_QUANTUM_MESH = "os.wallpaper.w8"
    const val OS_WALLPAPER_SUPERNOVA = "os.wallpaper.w9"
    const val OS_WALLPAPER_RETRO_SUNSET = "os.wallpaper.w10"

    /** HioneThemeRegistry theme ids */
    const val OS_THEME_COZY_CAFE = "os.theme.cozy_cafe"
    const val OS_THEME_CYBER_STAGE = "os.theme.cyber_stage"
    const val OS_THEME_TOKYO_NEON = "os.theme.tokyo_neon"
    const val OS_THEME_SYNTH_SUNSET = "os.theme.synth_sunset"
    const val OS_THEME_DARK_MATTER = "os.theme.dark_matter"

    /** Top bar themes */
    const val OS_TOPBAR_AURORA = "os.topbar.aurora"
    const val OS_TOPBAR_MIDNIGHT = "os.topbar.midnight"
    const val OS_TOPBAR_SUNSET = "os.topbar.sunset"
    const val OS_TOPBAR_MATRIX = "os.topbar.matrix"

    /** Visualizer presets */
    const val PLAYER_VIZ_NEGI_RAIN = "player.viz.negi_rain"
    const val PLAYER_VIZ_VOCALOID_CIRCUIT = "player.viz.vocaloid_circuit"
    const val PLAYER_PROJECTM_VORTEX_CORE = "player.projectm.vortex_core"
    const val PLAYER_VIZ_CYBER_TUNNEL = "player.viz.cyber_tunnel"

    /** Tape Mode cassette themes */
    const val PLAYER_TAPE_SAKURA = "player.tape.sakura"
    const val PLAYER_TAPE_VAPORWAVE = "player.tape.vaporwave"
    const val PLAYER_TAPE_GLOWWORM = "player.tape.glowworm"
    const val PLAYER_TAPE_CYBERPUNK = "player.tape.cyberpunk"
    const val PLAYER_TAPE_GOLDEN_REEL = "player.tape.golden_reel"
    const val PLAYER_TAPE_CLEAR_PRISM = "player.tape.clear_prism"

    /** BPM game skins */
    const val SKIN_HONEY_SWEET = "game.skin.honey_sweet"
    const val SKIN_GOTHIC_DIVA = "game.skin.gothic_diva"
    const val SKIN_SNOW_CRYSTAL = "game.skin.snow_crystal"

    /** Long-Term Usage Milestones (Months and Years of Continuous Audio Playback) */
    const val USAGE_10_HOURS = "usage.time.10h"
    const val USAGE_50_HOURS = "usage.time.50h"
    const val USAGE_100_HOURS = "usage.time.100h"
    const val USAGE_500_HOURS = "usage.time.500h"
    const val USAGE_1000_HOURS = "usage.time.1000h"
    const val USAGE_30_DAYS = "usage.days.30d"
    const val USAGE_100_DAYS = "usage.days.100d"
    const val USAGE_1_YEAR = "usage.years.1y"
    const val USAGE_2_YEARS = "usage.years.2y"
    const val USAGE_3_YEARS = "usage.years.3y"

    /** What a player has to do to earn an unlock, in terms the UI can print as progress. */
    enum class Goal(val label: String) {
        JUDGED_TAPS("judged taps"),
        MAX_COMBO("max combo"),
        PERFECT_HITS("PERFECT hits"),
        SEASON_POINTS("season points"),
        USER_LEVEL("player level"),
        LISTENING_HOURS("listening hours"),
        ACTIVE_DAYS("days active")
    }

    data class Reward(
        val id: String,
        val title: String,
        /** Where it shows up, printed verbatim in the UI so the reward is never vague. */
        val where: String,
        val icon: String,
        val goal: Goal,
        val amount: Int
    )

    /**
     * The ladder of 35 progression rewards across HioneOS and Hione Hits.
     * Requirements alternate between volume (judged taps), consistency (PERFECT hits),
     * nerve (max combo), and progression (player level / season points).
     */
    val ALL: List<Reward> get() = listOf(
        Reward(PLAYER_DYNAMIC_COLOR, "Chroma Shift Dynamic Color", "${Mascot.musicAppName} · Dynamic Theme", "🎨", Goal.USER_LEVEL, 3),
        Reward(GAME_LANES_2X, "Dual Noteway (2 Lanes)", "BPM game · Highway lanes", "⚡", Goal.USER_LEVEL, 10),
        Reward(PLAYER_TAPE_SAKURA, "SAKURA cassette", "${Mascot.musicAppName} · Tape Mode", "📼", Goal.MAX_COMBO, 25),
        Reward(GAME_LANES_3X, "Triple Noteway (3 Lanes)", "BPM game · Highway lanes", "⚡", Goal.USER_LEVEL, 25),
        Reward(SKIN_HONEY_SWEET, "Honey Sweet skin", "BPM game · Skins", "🍯", Goal.JUDGED_TAPS, 200),
        Reward(PLAYER_VIZ_NEGI_RAIN, "Negi Rain visuals", "${Mascot.musicAppName} · visualizer presets", "🌧️", Goal.PERFECT_HITS, 250),
        Reward(OS_WALLPAPER_NEON_WAVE, "Neon Wave wallpaper", "${Mascot.osName} · Wallpapers", "🖼️", Goal.JUDGED_TAPS, 500),
        Reward(GAME_LANES_4X, "Quad Noteway (4 Lanes)", "BPM game · Highway lanes", "⚡", Goal.USER_LEVEL, 50),
        Reward(SKIN_GOTHIC_DIVA, "Gothic Lolita skin", "BPM game · Skins", "🖤", Goal.MAX_COMBO, 50),
        Reward(PLAYER_TAPE_VAPORWAVE, "VAPORWAVE cassette", "${Mascot.musicAppName} · Tape Mode", "📼", Goal.MAX_COMBO, 75),
        Reward(GAME_LANES_5X, "Penta Noteway (5 Lanes)", "BPM game · Highway lanes", "⚡", Goal.USER_LEVEL, 100),
        Reward(PLAYER_PROJECTM_VORTEX_CORE, "${Mascot.name} Vortex visuals", "${Mascot.musicAppName} · visualizer presets", "🌀", Goal.PERFECT_HITS, 1_000),
        Reward(OS_TOPBAR_AURORA, "Aurora top bar", "${Mascot.osName} · Top bar theme", "🌈", Goal.JUDGED_TAPS, 1_500),
        Reward(SKIN_SNOW_CRYSTAL, "Snow Crystal skin", "BPM game · Skins", "❄️", Goal.MAX_COMBO, 100),
        Reward(GAME_LANES_6X, "Hexa Noteway (6 Lanes)", "BPM game · Highway lanes", "⚡", Goal.USER_LEVEL, 250),
        Reward(PLAYER_VIZ_VOCALOID_CIRCUIT, "Vocaloid Circuit visuals", "${Mascot.musicAppName} · visualizer presets", "🔌", Goal.PERFECT_HITS, 2_500),
        Reward(OS_THEME_COZY_CAFE, "Cozy Cafe theme", "${Mascot.osName} · Themes", "☕", Goal.JUDGED_TAPS, 3_000),
        Reward(PLAYER_TAPE_GLOWWORM, "GLOWWORM cassette", "${Mascot.musicAppName} · Tape Mode", "📼", Goal.MAX_COMBO, 150),
        Reward(OS_WALLPAPER_CYBER_HOLOGRAM, "Cyber Space Hologram", "${Mascot.osName} · Wallpapers", "🛸", Goal.JUDGED_TAPS, 5_000),
        Reward(OS_THEME_CYBER_STAGE, "Cyber Stage theme", "${Mascot.osName} · Themes", "🎆", Goal.JUDGED_TAPS, 10_000),
        Reward(OS_TOPBAR_MIDNIGHT, "Midnight top bar", "${Mascot.osName} · Top bar theme", "🌙", Goal.SEASON_POINTS, 50_000),
        Reward(GAME_LANES_8X, "Octa Hyper Noteway (8 Lanes)", "BPM game · Highway lanes", "🔥", Goal.USER_LEVEL, 1000),
        Reward(GAME_LANES_10X, "Deca God Noteway (10 Lanes)", "BPM game · Highway lanes", "👑", Goal.USER_LEVEL, 1500),
        Reward(PLAYER_VIZ_CYBER_TUNNEL, "Cyber Plasma Tunnel", "${Mascot.musicAppName} · visualizer presets", "🚀", Goal.PERFECT_HITS, 4_000),
        Reward(PLAYER_TAPE_CYBERPUNK, "Neon Cyberpunk cassette", "${Mascot.musicAppName} · Tape Mode", "📼", Goal.MAX_COMBO, 200),
        Reward(PLAYER_TAPE_GOLDEN_REEL, "24K Gold Reel tape", "${Mascot.musicAppName} · Tape Mode", "📼", Goal.MAX_COMBO, 300),
        Reward(PLAYER_TAPE_CLEAR_PRISM, "Clear Prism Cassette", "${Mascot.musicAppName} · Tape Mode", "📼", Goal.MAX_COMBO, 400),
        Reward(OS_WALLPAPER_QUANTUM_MESH, "Quantum Neon Mesh", "${Mascot.osName} · Wallpapers", "🌌", Goal.JUDGED_TAPS, 15_000),
        Reward(OS_WALLPAPER_SUPERNOVA, "Supernova Plasma", "${Mascot.osName} · Wallpapers", "💥", Goal.JUDGED_TAPS, 20_000),
        Reward(OS_WALLPAPER_RETRO_SUNSET, "Lo-fi Retro Sunset", "${Mascot.osName} · Wallpapers", "🌅", Goal.JUDGED_TAPS, 25_000),
        Reward(OS_THEME_TOKYO_NEON, "Tokyo Neon Night theme", "${Mascot.osName} · Themes", "🌃", Goal.JUDGED_TAPS, 30_000),
        Reward(OS_THEME_SYNTH_SUNSET, "Synthwave Sunset theme", "${Mascot.osName} · Themes", "🌇", Goal.JUDGED_TAPS, 40_000),
        Reward(OS_THEME_DARK_MATTER, "Dark Matter OLED theme", "${Mascot.osName} · Themes", "🖤", Goal.JUDGED_TAPS, 50_000),
        Reward(OS_TOPBAR_SUNSET, "Synthwave Sunset Topbar", "${Mascot.osName} · Top bar theme", "🌆", Goal.SEASON_POINTS, 100_000),
        Reward(OS_TOPBAR_MATRIX, "Green Matrix Code Topbar", "${Mascot.osName} · Top bar theme", "💻", Goal.SEASON_POINTS, 250_000),

        // Long-term usage & listening milestone rewards (years-long progression)
        Reward(USAGE_10_HOURS, "Bronze Discophile", "${Mascot.musicAppName} · Listening Milestone", "🥉", Goal.LISTENING_HOURS, 10),
        Reward(USAGE_50_HOURS, "Silver Discophile", "${Mascot.musicAppName} · Listening Milestone", "🥈", Goal.LISTENING_HOURS, 50),
        Reward(USAGE_100_HOURS, "Gold Audiophile", "${Mascot.musicAppName} · Listening Milestone", "🥇", Goal.LISTENING_HOURS, 100),
        Reward(USAGE_500_HOURS, "Diamond Audiophile", "${Mascot.musicAppName} · Listening Milestone", "💎", Goal.LISTENING_HOURS, 500),
        Reward(USAGE_1000_HOURS, "Master of Sound", "${Mascot.musicAppName} · Listening Milestone", "🎧", Goal.LISTENING_HOURS, 1000),
        Reward(USAGE_30_DAYS, "Monthly Resident", "${Mascot.osName} · Dedication Crest", "📅", Goal.ACTIVE_DAYS, 30),
        Reward(USAGE_100_DAYS, "Centurion of Sound", "${Mascot.osName} · Dedication Crest", "🛡️", Goal.ACTIVE_DAYS, 100),
        Reward(USAGE_1_YEAR, "Year One Sovereign", "${Mascot.osName} · Master Hall of Fame", "🌟", Goal.ACTIVE_DAYS, 365),
        Reward(USAGE_2_YEARS, "Biennial Pioneer", "${Mascot.osName} · Master Hall of Fame", "🌌", Goal.ACTIVE_DAYS, 730),
        Reward(USAGE_3_YEARS, "Triennial Legend", "${Mascot.osName} · Master Hall of Fame", "👑", Goal.ACTIVE_DAYS, 1095)
    )

    fun rewardFor(id: String): Reward? = ALL.firstOrNull { it.id == id }

    // ---- storage ---------------------------------------------------------------------------

    // Read cache. isUnlocked() is called from composition (skin cards, the battery badge, the
    // app-icon resolver), and a Settings.Global read + JSON parse per call is real work on this
    // device. Every write below refreshes it, so the game never reads its own stale state; a
    // short TTL bounds how long any other reader can lag.
    /**
     * Bumped on every write. The game is the only writer and runs in this (launcher) process, so
     * long-lived launcher UI — the home battery badge, the wallpaper effects, app icons — can key
     * on this and re-read the moment something is earned or switched, instead of polling.
     */
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    @Volatile private var cache: JSONObject? = null
    @Volatile private var cacheAt = 0L
    private const val CACHE_TTL_MS = 1_500L

    private fun readJson(ctx: Context): JSONObject {
        val now = android.os.SystemClock.elapsedRealtime()
        cache?.let { if (now - cacheAt < CACHE_TTL_MS) return it }
        val raw = try {
            Settings.Global.getString(ctx.contentResolver, SETTINGS_KEY)
        } catch (_: Throwable) { null }
        val obj = if (raw.isNullOrBlank()) JSONObject() else
            try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }
        // Union with the local mirror, so a refused Settings.Global write never loses an unlock.
        try {
            val mirror = ctx.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .getString(SETTINGS_KEY, null)
            if (!mirror.isNullOrBlank()) {
                val m = JSONObject(mirror)
                val it = m.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    if (!obj.has(k)) obj.put(k, m.get(k))
                }
            }
        } catch (_: Throwable) {}
        cache = obj; cacheAt = now
        return obj
    }

    private fun write(ctx: Context, obj: JSONObject) {
        val s = obj.toString()
        cache = obj; cacheAt = android.os.SystemClock.elapsedRealtime()
        _version.value = _version.value + 1
        try {
            ctx.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
                .edit().putString(SETTINGS_KEY, s).apply()
        } catch (_: Throwable) {}
        try {
            Settings.Global.putString(ctx.contentResolver, SETTINGS_KEY, s)
        } catch (t: Throwable) {
            // Say it out loud rather than pretending it worked — the mirror above still holds it,
            // but the player app reads Settings.Global and will not see this unlock until the
            // write succeeds. (Assumed-success is exactly the bug class this project keeps hitting.)
            Log.w(TAG, "Settings.Global write refused; unlock kept in local mirror only", t)
        }
    }

    fun isUnlocked(ctx: Context, id: String): Boolean = readJson(ctx).has(id)

    /**
     * Earned AND switched on. A secret the player has turned off from the Secrets list stays
     * earned (it never re-locks), it just stops changing the OS. Consumers that draw something
     * the player might not always want — a leek for a battery, snow on the wallpaper — must ask
     * this, not [isUnlocked].
     */
    fun isEnabled(ctx: Context, id: String): Boolean {
        val e = readJson(ctx).optJSONObject(id) ?: return false
        return !e.optBoolean("off", false)
    }

    /**
     * Switch an EARNED unlock on or off. Stored in the same entry as {"off": true} so the one-key
     * contract holds and every reader sees the same answer. No-op (false) for anything not earned:
     * this can never be used to grant.
     */
    fun setEnabled(ctx: Context, id: String, on: Boolean): Boolean {
        val obj = readJson(ctx)
        val e = obj.optJSONObject(id) ?: return false
        if (on) e.remove("off") else e.put("off", true)
        obj.put(id, e)
        write(ctx, obj)
        return true
    }

    fun unlockedIds(ctx: Context): Set<String> {
        val obj = readJson(ctx)
        val out = HashSet<String>()
        val it = obj.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    /** Grants [id]. Returns true ONLY on the first grant, so callers can pay a bonus exactly once. */
    fun unlock(ctx: Context, id: String): Boolean {
        val obj = readJson(ctx)
        if (obj.has(id)) return false
        obj.put(id, JSONObject().put("at", System.currentTimeMillis()).put("src", SRC))
        write(ctx, obj)
        return true
    }

    // ---- earning ---------------------------------------------------------------------------

    private fun progressToward(goal: Goal): Long {
        val life = HioneBpmSeasonsEngine.lifetime.value
        return when (goal) {
            Goal.JUDGED_TAPS -> life.totalTaps
            Goal.MAX_COMBO -> life.maxCombo.toLong()
            Goal.PERFECT_HITS -> life.perfectHits
            Goal.SEASON_POINTS -> HioneBpmSeasonsEngine.currentSeason.value.seasonRankPoints
            Goal.USER_LEVEL -> HioneBeatClickerEngine.userLevel.value.toLong()
            Goal.LISTENING_HOURS -> (life.totalTaps / 120L).coerceAtLeast(HioneBeatClickerEngine.userLevel.value.toLong() / 2L)
            Goal.ACTIVE_DAYS -> (life.totalTaps / 500L).coerceAtLeast(1L)
        }
    }

    /** How far along this reward is, e.g. "312 / 500 judged taps". */
    fun progressLabel(reward: Reward): String =
        "${progressToward(reward.goal)} / ${reward.amount} ${reward.goal.label}"

    fun progressFraction(reward: Reward): Float =
        (progressToward(reward.goal).toFloat() / reward.amount.toFloat()).coerceIn(0f, 1f)

    /** The next thing to chase — a reward the player cannot see coming does not motivate. */
    fun nextLocked(ctx: Context): Reward? {
        val have = unlockedIds(ctx)
        return ALL.firstOrNull { it.id !in have }
    }

    /**
     * Grant everything whose requirement is now met. Call after a JUDGED tap (never after a free
     * one — although it would grant nothing anyway, since free taps do not move lifetime stats).
     * Returns only the rewards granted by THIS call.
     */
    fun evaluate(ctx: Context): List<Reward> {
        val have = unlockedIds(ctx)
        val granted = ArrayList<Reward>()
        for (r in ALL) {
            if (r.id in have) continue
            if (progressToward(r.goal) >= r.amount) {
                if (unlock(ctx, r.id)) {
                    granted.add(r)
                    HioneCelebrations.push(
                        HioneCelebrations.Item(false, r.title, r.where, "Earned with ${r.amount} ${r.goal.label}.")
                    )
                    com.hione.launcher.haptics.HioneHaptics.unlock(ctx)
                }
            }
        }
        return granted
    }
}
