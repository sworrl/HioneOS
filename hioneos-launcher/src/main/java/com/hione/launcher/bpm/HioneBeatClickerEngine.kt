package com.hione.launcher.bpm

import com.hione.launcher.Mascot
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.pow
import kotlin.random.Random

/**
 * Hione Beat Clicker — An Addictive Incremental Rhythm Economy (Cookie Clicker for Beat Tappers).
 *
 * THE ONE RULE: [tap] is for taps that were JUDGED against a real detected beat. A tap with no
 * beat reference goes to [freeTap], which pays the idle-game base click and nothing else — it
 * touches no accuracy statistic, no combo, no fever, no season rank. Every number this object
 * exposes for display is therefore backed by a real measurement or is honestly absent (-1/"—").
 *
 * Game Theory Mechanics:
 * - Active Tap Yield: 1.0 base + 3% of passive BPS.
 * - Accuracy Scaler: the five graded tiers in HioneRhythmTiming.kt (OK 1.2x ... PERFECT 6x),
 *   plus a 9% CRIT chance (10x).
 * - Combo Compounding: a TIERED multiplier with visible breakpoints (x1.5 at 8, x2 at 16,
 *   x3 at 32, x4 at 64, x5 at 128, x6 at 256) instead of a flat per-tap trickle — a player can
 *   see the next rung and decide to reach for it.
 * - Combo Shields: earn one every 20 consecutive GREAT-or-better hits (max 3); a shield eats
 *   one MISS on a combo of 10+ instead of resetting it. Near-miss tension with a mercy rule.
 * - Adaptive difficulty: windows widen for a struggling player and tighten as they land hits,
 *   and the tighter tiers pay more (see HioneRhythmTiming.RhythmTier).
 * - Pentatonic Melodic Plucks: Consecutive combo hits play ascending notes on a synthesized scale!
 * - Fever Energy Meter: Taps charge the Fever Meter (0-100%). At 100%, automatic 15s 77x SUPER FEVER!
 * - Particle Bursts: Every tap explodes with colorful stars, hearts, and musical notes.
 * - Performance Grading: Real-time SSS+ / SS / S / A arcade grade rating.
 * - Golden Leek Spawns: Random high-value sparkling target triggers 77x FEVER FRENZY!
 * - Exponential Tiered Buildings: Standard Cookie Clicker 1.15^n cost scaling.
 * - Offline Earnings: Welcome-back cache calculation.
 */
object HioneBeatClickerEngine {
    private const val PREFS_NAME = "hione_beat_clicker_prefs"
    private const val KEY_LEEKS = "leeks"
    private const val KEY_TOTAL_EARNED = "total_earned"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    private const val KEY_USER_LEVEL = "user_level"
    private const val KEY_USER_XP = "user_xp"
    private const val KEY_ACTIVE_LANES = "active_lanes"

    data class Building(
        val id: String,
        val name: String,
        val icon: String,
        val baseCost: Double,
        val baseBps: Double,
        val owned: Int,
        val description: String
    ) {
        val currentCost: Double get() = baseCost * (1.15.pow(owned.toDouble()))
        val totalBps: Double get() = baseBps * owned
    }

    data class FloatingText(
        val id: Long,
        val text: String,
        val x: Float,
        val y: Float,
        val isCrit: Boolean,
        val isFever: Boolean
    )

    data class JuiceParticle(
        val id: Long,
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        val emoji: String,
        val colorHex: Long,
        val sizeDp: Float
    )

    data class SkillUpgrade(
        val id: String,
        val name: String,
        val icon: String,
        val baseCost: Double,
        val level: Int,
        val maxLevel: Int,
        val description: String
    ) {
        val currentCost: Double get() = baseCost * (1.6.pow(level.toDouble()))
    }

    /** Silhouette of the beat node itself — the single most legible difference between skins. */
    enum class NodeShape { ORB, DIAMOND, HEX, STAR, SNOWFLAKE, HEART }

    /** Idle background treatment drawn behind everything. */
    enum class AmbientStyle { PETALS, HEX_RAIN, HONEY_BUBBLES, DAMASK_VEIL, SNOW_DRIFT, HOLO_GRID }

    /** How a tap burst moves: gravity, speed, swirl, rise. */
    enum class BurstStyle { PETAL_FALL, SPARK_SHOT, BUBBLE_RISE, RIBBON_SWIRL, CRYSTAL_DRIFT }

    /** What a PERFECT does to the screen. */
    enum class HitEffect { RING, SHARD, SPLASH, VELVET_PULSE, FROST_CRACK, GLITCH }

    /**
     * How the note highway itself is textured. The lane is the thing the player stares at for a
     * whole song, so it is where a skin has to be most different — a recolour of the same lane is
     * exactly the "the skins are all the same" complaint.
     */
    enum class LaneStyle {
        /** Soft wash with petals drifting along the lane. */
        PETAL_STREAM,
        /** Hard scrolling grid lines and scanlines: a synth display. */
        NEON_GRID,
        /** Honeycomb cells sliding past. */
        HONEYCOMB,
        /** Dark velvet with lace bands and a slow heartbeat swell. */
        VELVET_LACE,
        /** Icy streaks and frost crystals. */
        FROST_STREAK,
        /** Interference bands and a glitch offset on the beat. */
        HOLO_SCAN
    }

    /**
     * Aesthetic skins.
     *
     * These used to differ ONLY by colour — same circle, same hearts drifting behind it, same
     * emoji burst, same type — so nobody could tell them apart and "unlocking" one meant nothing.
     * Each skin now changes what a player sees at a glance: the SHAPE of the beat node (and of
     * every note on the highway, which uses the same silhouette), the LANE texture, the whole
     * SHEET palette behind the game, the AMBIENT background, how the tap BURST moves (and which
     * glyphs it throws), and the hit EFFECT on a PERFECT — plus its own judgment typography.
     */
    enum class BpmSkin(
        val displayName: String,
        val icon: String,
        val primaryColor: Long,
        val accentColor: Long,
        val glowColor: Long,
        val patternName: String,
        val nodeShape: NodeShape,
        val ambient: AmbientStyle,
        val burst: BurstStyle,
        val hitEffect: HitEffect,
        /** Judgment text: monospaced arcade caps vs soft lower-case. */
        val judgmentArcade: Boolean,
        val judgmentSpacing: Float,
        val blurb: String,
        val laneStyle: LaneStyle,
        /** The game sheet's own background, top and bottom. */
        val sheetTop: Long,
        val sheetBottom: Long
    ) {
        SAKURA_DREAM("Sakura Dream", "🌸", 0xFFFF85B3, 0xFFFFAB2E, 0xFFFFB5D5, "sakura_lattice",
            NodeShape.ORB, AmbientStyle.PETALS, BurstStyle.PETAL_FALL, HitEffect.RING,
            false, 0f, "Round notes, petal lane, soft ring",
            LaneStyle.PETAL_STREAM, 0xF22A0E26, 0xFF12050F),
        CYBER_MIRAI("Cyber Mirai", "🌟", 0xFFFFC56B, 0xFF9D4EDD, 0xFF7000FF, "hex_matrix",
            NodeShape.HEX, AmbientStyle.HEX_RAIN, BurstStyle.SPARK_SHOT, HitEffect.SHARD,
            true, 1.5f, "Hex notes, neon grid lane, sparks",
            LaneStyle.NEON_GRID, 0xF2061826, 0xFF02060E),
        HONEY_SWEET("Honey Sweet", "🍯", 0xFFFFD166, 0xFFFF9F1C, 0xFFFFE49E, "honeycomb",
            NodeShape.DIAMOND, AmbientStyle.HONEY_BUBBLES, BurstStyle.BUBBLE_RISE, HitEffect.SPLASH,
            false, 0f, "Diamond notes, honeycomb lane, syrup splash",
            LaneStyle.HONEYCOMB, 0xF2281806, 0xFF120A02),
        GOTHIC_DIVA("Gothic Lolita", "🖤", 0xFFFF2277, 0xFF7B2CBF, 0xFFFF5599, "velvet_damask",
            NodeShape.STAR, AmbientStyle.DAMASK_VEIL, BurstStyle.RIBBON_SWIRL, HitEffect.VELVET_PULSE,
            true, 2.5f, "Star notes, lace lane, ribbons",
            LaneStyle.VELVET_LACE, 0xF2160410, 0xFF050105),
        SNOW_CRYSTAL("Snow Crystal", "❄️", 0xFFFFD08A, 0xFF48CAE4, 0xFFFFF3E0, "frost_shimmer",
            NodeShape.SNOWFLAKE, AmbientStyle.SNOW_DRIFT, BurstStyle.CRYSTAL_DRIFT, HitEffect.FROST_CRACK,
            true, 1.0f, "Snowflake notes, frost lane, ice cracks",
            LaneStyle.FROST_STREAK, 0xF20C2030, 0xFF040C14),
        // Secret skin: not in the selector at all until HioneSecrets.HOLO_SKIN is earned.
        HOLOGRAM_39("Hologram 39", "", 0xFFFFAB2E, 0xFFFF4FA3, 0xFFB8FFF9, "holo_scan",
            NodeShape.HEART, AmbientStyle.HOLO_GRID, BurstStyle.SPARK_SHOT, HitEffect.GLITCH,
            true, 3.0f, "Heart notes, scanline lane, glitchy hits",
            LaneStyle.HOLO_SCAN, 0xF2031A1C, 0xFF000608);

        /** Glyphs a tap burst throws — a different alphabet per skin, not a different tint. */
        val burstGlyphs: List<String>
            get() = when (this) {
                SAKURA_DREAM -> listOf("🌸", "💮", "🌷", "💗")
                CYBER_MIRAI -> listOf("✦", "⚡", "🔷", "💠")
                HONEY_SWEET -> listOf("🍯", "🐝", "🟡", "🍮")
                GOTHIC_DIVA -> listOf("🖤", "🥀", "🦇", "♠")
                SNOW_CRYSTAL -> listOf("❄", "💎", "✳", "✨")
                HOLOGRAM_39 -> listOf("39", "<3", "//", "~")
            }

        /** Particle tints, taken from the skin's own palette instead of one shared rainbow. */
        val burstColors: List<Long>
            get() = listOf(primaryColor, accentColor, glowColor)

        /**
         * The unlock that gates this skin, or null if it is available from the start. Two skins
         * are free so the selector is never an empty shop; the other three are earned, which is
         * what makes the skin tab a reason to keep playing instead of a colour menu.
         */
        val unlockId: String?
            get() = when (this) {
                SAKURA_DREAM, CYBER_MIRAI -> null
                HONEY_SWEET -> HioneUnlocks.SKIN_HONEY_SWEET
                GOTHIC_DIVA -> HioneUnlocks.SKIN_GOTHIC_DIVA
                SNOW_CRYSTAL -> HioneUnlocks.SKIN_SNOW_CRYSTAL
                HOLOGRAM_39 -> HioneSecrets.HOLO_SKIN
            }

        /** A secret skin is not merely locked, it is absent from the selector until earned. */
        val isSecret: Boolean get() = this == HOLOGRAM_39

        fun isAvailable(ctx: Context): Boolean {
            val id = unlockId ?: return true
            return HioneUnlocks.isUnlocked(ctx, id)
        }
    }

    data class Achievement(
        val id: String,
        val title: String,
        val desc: String,
        val icon: String,
        val rewardLeeks: Double,
        val isUnlocked: Boolean
    )

    /**
     * Today's Setlist — three small, legible goals that reset at midnight.
     *
     * This is the "reason to come back" and the "reason to start" the session shape was missing:
     * an idle economy alone gives a player nothing to aim at in the next five minutes. Every
     * counter here is fed ONLY by judged taps, so the accuracy goal cannot be farmed by mashing
     * the node with nothing playing, and it reads "—" until there is enough data to state a rate.
     */
    data class DailySetlist(
        val dateKey: String = "",
        val judgedTaps: Int = 0,
        val bestCombo: Int = 0,
        val greatOrBetter: Int = 0,
        /** bit 0 = taps goal claimed, bit 1 = combo goal, bit 2 = accuracy goal. */
        val claimedMask: Int = 0
    ) {
        val accuracyPct: Float
            get() = if (judgedTaps >= GOAL_ACCURACY_MIN_TAPS) greatOrBetter * 100f / judgedTaps else -1f
        val tapsDone: Boolean get() = judgedTaps >= GOAL_TAPS
        val comboDone: Boolean get() = bestCombo >= GOAL_COMBO
        val accuracyDone: Boolean get() = accuracyPct >= GOAL_ACCURACY_PCT
        val completed: Int get() = (if (tapsDone) 1 else 0) + (if (comboDone) 1 else 0) + (if (accuracyDone) 1 else 0)

        companion object {
            const val GOAL_TAPS = 150
            const val GOAL_COMBO = 30
            const val GOAL_ACCURACY_PCT = 80f
            const val GOAL_ACCURACY_MIN_TAPS = 40
            const val REWARD_EACH = 2_500.0
        }
    }

    /**
     * The Daily Challenge: ONE seeded goal per day, different in KIND from the setlist (which is
     * the same three goals every day). The kind rotates by date so every player gets the same
     * challenge on the same day and tomorrow is a different game — "come back tomorrow" only
     * works if tomorrow is not a repeat.
     */
    enum class ChallengeKind(private val template: String, val target: Int) {
        PERFECTS("Land %d PERFECTs", 39),
        COMBO("Reach a %d combo", 60),
        FEVERS("Start fever %d times", 2),
        SONG_SCORE("Score %d on one song", 6_000),
        LUCKY("Hit %d lucky notes", 3),
        NO_MISS("Go %d notes without a miss", 40);

        /** "Land 39 PERFECTs": the goal as a sentence. */
        val label: String get() = String.format(Locale.US, template, target)
    }

    data class DailyChallenge(
        val dateKey: String = "",
        val kind: ChallengeKind = ChallengeKind.PERFECTS,
        val progress: Int = 0,
        val claimed: Boolean = false
    ) {
        val done: Boolean get() = progress >= kind.target
        companion object {
            const val REWARD_LEEKS = 12_000.0
        }
    }

    private val initialBuildings get() = listOf(
        Building("chibi", "Chibi ${Mascot.name}", "🎤", 15.0, 0.3, 0, "Little chibi ${Mascot.name} humming along"),
        Building("farm", Mascot.lore("Leek Farm", "Note Farm"), "🌱", 100.0, 1.8, 0, Mascot.lore("Hydroponic cyber leeks growing under LEDs", "Hydroponic cyber notes growing under LEDs")),
        Building("synth", "Yamaha DX7", "🎹", 1100.0, 14.0, 0, "Classic 1983 6-operator FM synthesizer"),
        Building("arcade", "DIVA Arcade", "🕹️", 12000.0, 75.0, 0, "Sanwa arcade buttons and coin drops"),
        Building("stage", "Hologram Stage", "🌟", 130000.0, 420.0, 0, "A 3D projection with stadium sound"),
        Building("mirai", "Mirai Stadium", "🏟️", 1400000.0, 2600.0, 0, "50,000 glowing penlights in unison"),
        Building("satellite", "Orbital Station", "🛰️", 20000000.0, 18000.0, 0, "Broadcasting ${Mascot.name}'s voice across deep space")
    )

    // Every one of these is READ by the game loop below (see the skill accessors). They used to
    // be decorative: you could spend hundreds of thousands of leeks on a skill tree that changed
    // nothing at all, which is the least legible progression a game can have.
    private val initialSkills = listOf(
        SkillUpgrade("magnet", Mascot.lore("Golden Leek Magnet", "Golden Note Magnet"), "🧲", 250.0, 0, 10, Mascot.lore("Golden leeks show up 12% sooner per tier", "Golden notes show up 12% sooner per tier")),
        SkillUpgrade("window", "Wider Timing Window", "🎯", 600.0, 0, 8, "+6ms wider hit windows per tier"),
        SkillUpgrade("overdrive", "Longer Fever", "⚡", 2000.0, 0, 5, "+3s fever and +15x fever multiplier per tier"),
        SkillUpgrade("pentatonic", "Combo Bonus", "🎼", 5000.0, 0, 5, "+25% combo multiplier bonus per tier"),
        // NOT an auto-tapper: a machine tap has no player timing in it, so scoring one would be
        // fabricating accuracy. Chibi Hione farms in the background instead.
        SkillUpgrade("autopilot", Mascot.lore("Chibi Hione Farmer", "Chibi Farmer"), "🤖", 15000.0, 0, 5, Mascot.lore("Chibi Hione earns leeks while you rest, +12% leeks/s per tier", "Chibi ${Mascot.name} earns notes while you rest, +12% notes/s per tier"))
    )

    private val initialAchievements = listOf(
        Achievement("first_beat", "First Beat Match", "Hit 10 PERFECTs in a row", "🌸", 500.0, false),
        Achievement("fever_queen", "Fever Queen", "Fill the fever meter to 100%", "🔥", 2500.0, false),
        Achievement("diva_50", "Combo 50", "Reach a 50x combo", "👑", 10000.0, false),
        Achievement("audio_arch", "Master Calibrator", "Judge 25 taps against a real beat", "🎧", 5000.0, false),
        Achievement("leek_tycoon", Mascot.lore("Leek Tycoon", "Note Tycoon"), Mascot.lore("Earn 1,000,000 leeks", "Earn 1,000,000 notes"), "💰", 50000.0, false)
    )

    private val _leeks = MutableStateFlow(0.0)
    val leeks: StateFlow<Double> = _leeks.asStateFlow()

    private val _totalEarned = MutableStateFlow(0.0)
    val totalEarned: StateFlow<Double> = _totalEarned.asStateFlow()

    private val _buildings = MutableStateFlow(initialBuildings)
    val buildings: StateFlow<List<Building>> = _buildings.asStateFlow()

    private val _skills = MutableStateFlow(initialSkills)
    val skills: StateFlow<List<SkillUpgrade>> = _skills.asStateFlow()

    private val _currentSkin = MutableStateFlow(BpmSkin.SAKURA_DREAM)
    val currentSkin: StateFlow<BpmSkin> = _currentSkin.asStateFlow()

    private val _achievements = MutableStateFlow(initialAchievements)
    val achievements: StateFlow<List<Achievement>> = _achievements.asStateFlow()

    // Level progression system (1..1000+)
    private val _userLevel = MutableStateFlow(1)
    val userLevel: StateFlow<Int> = _userLevel.asStateFlow()

    private val _userXp = MutableStateFlow(0L)
    val userXp: StateFlow<Long> = _userXp.asStateFlow()

    // Active highway lanes (1x..6x standard, 8x & 10x past Lv 1000)
    private val _activeLanes = MutableStateFlow(1)
    val activeLanes: StateFlow<Int> = _activeLanes.asStateFlow()

    /**
     * XP required to advance from [level] to [level + 1].
     * Starts easy (~100-200 XP for early levels, reachable in 5-10 taps),
     * and curves smoothly to 1,000+ levels.
     */
    fun xpRequiredForLevel(level: Int): Long {
        if (level < 1) return 100L
        return (90.0 + 35.0 * level.toDouble().pow(1.28)).toLong().coerceAtLeast(100L)
    }

    /** Maximum unlocked simultaneous noteways based on user level. */
    fun maxUnlockedLanes(level: Int): Int = when {
        level >= 1500 -> 10
        level >= 1000 -> 8
        level >= 250 -> 6
        level >= 100 -> 5
        level >= 50 -> 4
        level >= 25 -> 3
        level >= 10 -> 2
        else -> 1
    }

    fun setActiveLanes(lanes: Int) {
        val maxAllowed = maxUnlockedLanes(_userLevel.value)
        val valid = lanes.coerceIn(1, maxAllowed)
        _activeLanes.value = valid
        prefs?.edit()?.putInt(KEY_ACTIVE_LANES, valid)?.apply()
    }

    fun addXp(ctx: Context?, xpAmount: Long) {
        if (xpAmount <= 0) return
        var currentXp = _userXp.value + xpAmount
        var level = _userLevel.value
        var leveledUp = false

        while (currentXp >= xpRequiredForLevel(level)) {
            currentXp -= xpRequiredForLevel(level)
            level += 1
            leveledUp = true

            // Trigger early unlock of Chroma Shift Dynamic Colors at Level 3!
            if (level >= 3 && ctx != null) {
                if (HioneUnlocks.unlock(ctx, HioneUnlocks.PLAYER_DYNAMIC_COLOR)) {
                    HioneCelebrations.push(
                        HioneCelebrations.Item(
                            secret = false,
                            title = "Chroma Shift Dynamic Color",
                            where = "Now Playing & Hione Hits",
                            howTo = "Level 3 reached! Vibrant album-art reactive gradients are now unlocked!"
                        )
                    )
                }
            }

            // Check if lane capacity expanded
            val newMax = maxUnlockedLanes(level)
            val prevMax = maxUnlockedLanes(level - 1)
            if (newMax > prevMax) {
                _activeLanes.value = newMax
                HioneCelebrations.push(
                    HioneCelebrations.Item(
                        secret = false,
                        title = "${newMax}x Noteway Unlocked!",
                        where = "BPM Highway",
                        howTo = "You unlocked ${newMax} simultaneous noteways! Use two thumbs to hit incoming beats!"
                    )
                )
            }
        }

        _userLevel.value = level
        _userXp.value = currentXp

        if (leveledUp) {
            pushFloatingText("LEVEL UP! Lv.$level", isCrit = true)
            HioneGameFx.pop("LEVEL $level!", 0xFFFFD166, big = true)
            HioneGameFx.shake(0.9f)
            com.hione.launcher.audio.HioneSeasonalAudioEngine.playLevelUpFanfare()
            ctx?.let { HioneUnlocks.evaluate(it) }
        }
        saveState()
    }

    private val _combo = MutableStateFlow(0)
    val combo: StateFlow<Int> = _combo.asStateFlow()

    private val _feverEnergy = MutableStateFlow(0f) // 0% to 100%
    val feverEnergy: StateFlow<Float> = _feverEnergy.asStateFlow()

    private val _feverSeconds = MutableStateFlow(0)
    val feverSeconds: StateFlow<Int> = _feverSeconds.asStateFlow()

    private val _goldenLeekVisible = MutableStateFlow(false)
    val goldenLeekVisible: StateFlow<Boolean> = _goldenLeekVisible.asStateFlow()

    private val _floatingTexts = MutableStateFlow<List<FloatingText>>(emptyList())
    val floatingTexts: StateFlow<List<FloatingText>> = _floatingTexts.asStateFlow()

    private val _particles = MutableStateFlow<List<JuiceParticle>>(emptyList())
    val particles: StateFlow<List<JuiceParticle>> = _particles.asStateFlow()

    // Session Performance Grade Tracking
    private val _sessionTaps = MutableStateFlow(0)
    val sessionTaps: StateFlow<Int> = _sessionTaps.asStateFlow()

    private val _sessionPerfects = MutableStateFlow(0)
    val sessionPerfects: StateFlow<Int> = _sessionPerfects.asStateFlow()

    /** GREAT-or-better count this session — the band the arcade grade is scored on. */
    private val _sessionGreatOrBetter = MutableStateFlow(0)
    val sessionGreatOrBetter: StateFlow<Int> = _sessionGreatOrBetter.asStateFlow()

    /** Combo shields in hand: each one eats a single MISS instead of resetting the combo. */
    private val _comboShields = MutableStateFlow(0)
    val comboShields: StateFlow<Int> = _comboShields.asStateFlow()

    /** The adaptive difficulty tier the player has settled into this session. */
    private val _rhythmTier = MutableStateFlow(HioneRhythmTiming.RhythmTier.PRACTICE)
    val rhythmTier: StateFlow<HioneRhythmTiming.RhythmTier> = _rhythmTier.asStateFlow()

    private val _dailySetlist = MutableStateFlow(DailySetlist())
    val dailySetlist: StateFlow<DailySetlist> = _dailySetlist.asStateFlow()

    private val _dailyChallenge = MutableStateFlow(DailyChallenge())
    val dailyChallenge: StateFlow<DailyChallenge> = _dailyChallenge.asStateFlow()

    /** Lifetime golden pickups (golden leeks + lucky notes). Feeds a secret; shown on the quests tab. */
    private val _goldenPickups = MutableStateFlow(0)
    val goldenPickups: StateFlow<Int> = _goldenPickups.asStateFlow()

    /** Lucky-note bonus: the next N judged hits pay this multiplier ("Hione sings along"). */
    private var encoreTapsLeft = 0
    private const val ENCORE_MULT = 3.0
    private var noMissRun = 0

    @Volatile private var appCtx: Context? = null
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    // Rolling window of the last judged taps (true = GREAT or better). Drives the tier, so a
    // player who is struggling RIGHT NOW gets wider windows rather than having to live down a
    // bad start for the rest of the session.
    private val recentJudged = ArrayDeque<Boolean>()
    private const val TIER_WINDOW = 30

    private const val MAX_COMBO_SHIELDS = 3
    private const val SHIELD_EARN_STREAK = 20
    private const val SHIELD_MIN_COMBO = 10
    private var shieldProgress = 0
    private var perfectStreak = 0
    /** Fractional-second carry for the fever countdown (tick() runs at 5 Hz, not 1 Hz). */
    private var feverAccumulator = 0.0

    /** -1 = no judged taps this session; there is no accuracy to report. */
    val sessionAccuracyPct: Float
        get() = if (_sessionTaps.value > 0) (_sessionGreatOrBetter.value.toFloat() / _sessionTaps.value.toFloat()) * 100f else -1f

    val sessionGrade: String
        get() = when {
            // Open the observatory having never tapped and this used to award "🌸 SSS" —
            // a top grade over an empty set. No JUDGED taps = no grade; under 5 = provisional.
            _sessionTaps.value == 0 -> "—"
            _sessionTaps.value < 5 -> "— (${_sessionTaps.value}/5)"
            sessionAccuracyPct >= 96f -> "SSS+"
            sessionAccuracyPct >= 90f -> "SS"
            sessionAccuracyPct >= 80f -> "S"
            sessionAccuracyPct >= 70f -> "A"
            else -> "B"
        }

    private var prefs: SharedPreferences? = null
    private var lastTickTime = SystemClock.elapsedRealtime()
    private var nextGoldenLeekCheck = SystemClock.elapsedRealtime() + 25000L

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        appCtx = context.applicationContext
        _goldenPickups.value = p.getInt("golden_pickups", 0)

        _leeks.value = p.getFloat(KEY_LEEKS, 0f).toDouble()
        _totalEarned.value = p.getFloat(KEY_TOTAL_EARNED, 0f).toDouble()

        _userLevel.value = p.getInt(KEY_USER_LEVEL, 1).coerceAtLeast(1)
        _userXp.value = p.getLong(KEY_USER_XP, 0L).coerceAtLeast(0L)
        val maxLanes = maxUnlockedLanes(_userLevel.value)
        _activeLanes.value = p.getInt(KEY_ACTIVE_LANES, 1).coerceIn(1, maxLanes)

        val updated = initialBuildings.map { b ->
            val count = p.getInt("b_${b.id}", 0)
            b.copy(owned = count)
        }
        _buildings.value = updated

        val updatedSkills = initialSkills.map { s ->
            val lvl = p.getInt("s_${s.id}", 0)
            s.copy(level = lvl)
        }
        _skills.value = updatedSkills

        // Achievements were SAVED (see save(), "ach_<id>") but never restored, so the quests card
        // reset to "0 of 5 Done" with every achievement re-locked after each launcher restart —
        // and checkAchievements() re-granted rewardLeeks for conditions that were still met,
        // silently inflating the Leek total every session.
        _achievements.value = initialAchievements.map { a ->
            a.copy(isUnlocked = p.getBoolean("ach_${a.id}", false))
        }

        // Restore Today's Setlist — a saved day that is not today starts fresh at zero.
        val savedDay = p.getString("daily_date", "") ?: ""
        val today = todayKey()
        _dailySetlist.value = if (savedDay == today) {
            DailySetlist(
                dateKey = today,
                judgedTaps = p.getInt("daily_taps", 0),
                bestCombo = p.getInt("daily_combo", 0),
                greatOrBetter = p.getInt("daily_great", 0),
                claimedMask = p.getInt("daily_claimed", 0)
            )
        } else DailySetlist(dateKey = today)
        _comboShields.value = p.getInt("combo_shields", 0).coerceIn(0, MAX_COMBO_SHIELDS)
        _dailyChallenge.value = if (p.getString("challenge_date", "") == today) {
            DailyChallenge(
                dateKey = today,
                kind = challengeKindFor(today),
                progress = p.getInt("challenge_progress", 0),
                claimed = p.getBoolean("challenge_claimed", false)
            )
        } else DailyChallenge(dateKey = today, kind = challengeKindFor(today))

        val skinName = p.getString("current_skin", BpmSkin.SAKURA_DREAM.name)
        val restored = runCatching { BpmSkin.valueOf(skinName!!) }.getOrDefault(BpmSkin.SAKURA_DREAM)
        // Never restore a skin the player no longer has (unlock store cleared, /data wipe).
        _currentSkin.value = if (restored.isAvailable(context)) restored else BpmSkin.SAKURA_DREAM

        // Offline earnings
        val lastSaved = p.getLong(KEY_LAST_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        if (lastSaved > 0L && now > lastSaved) {
            val secondsAway = ((now - lastSaved) / 1000L).coerceIn(0L, 86400L)
            val baseBps = updated.sumOf { it.totalBps }
            val offlineGain = baseBps * secondsAway * 0.6 // 60% offline efficiency
            if (offlineGain > 0.0) {
                _leeks.value += offlineGain
                _totalEarned.value += offlineGain
            }
        }
        lastTickTime = SystemClock.elapsedRealtime()
    }

    // =====================================================================================
    // SKILL EFFECTS — the skill tree is read HERE, so buying a tier visibly changes the game.
    // =====================================================================================

    fun skillLevel(id: String): Int = _skills.value.firstOrNull { it.id == id }?.level ?: 0

    /** Timing Window Expander: flat ms added to every hit window (see HioneRhythmTiming). */
    val timingWindowBonusMs: Int get() = skillLevel("window") * 6

    /** Golden Leek Magnet: shortens the spawn timer (10 tiers => ~45% sooner, never instant). */
    private val goldenLeekHaste: Double get() = 1.0 / (1.0 + skillLevel("magnet") * 0.12)

    /** Fever Rush Overdrive: longer and stronger fever. */
    private val feverDurationSeconds: Int get() = 15 + skillLevel("overdrive") * 3
    private val feverMultiplier: Double get() = 7.7 + skillLevel("overdrive") * 15.0

    /** Pentatonic Mastery: amplifies the part of the combo multiplier above x1. */
    private val comboBonusScale: Double get() = 1.0 + skillLevel("pentatonic") * 0.25

    /** Chibi Vocaloid Autopilot: passive production only — it never fakes a judged tap. */
    private val autopilotBpsScale: Double get() = 1.0 + skillLevel("autopilot") * 0.12

    // =====================================================================================
    // COMBO CURVE — legible breakpoints instead of an invisible per-tap trickle
    // =====================================================================================

    /** The rungs of the combo ladder: (combo needed, multiplier). */
    private val COMBO_RUNGS = listOf(
        4 to 1.5, 8 to 2.0, 16 to 3.0, 32 to 4.0, 64 to 5.0, 128 to 6.0
    )

    /** Raw multiplier for a combo, before the Pentatonic bonus. */
    fun comboMultiplier(combo: Int): Double {
        var m = 1.0
        for ((need, mult) in COMBO_RUNGS) if (combo >= need) m = mult
        return m
    }

    /** Multiplier actually applied (combo curve + Pentatonic Mastery on the bonus part). */
    fun effectiveComboMultiplier(combo: Int): Double {
        val base = comboMultiplier(combo)
        return 1.0 + (base - 1.0) * comboBonusScale
    }

    /**
     * The next rung, as (taps still needed, multiplier it unlocks), or null at the top.
     * Rendered as "4 more → x3" so the player can SEE what the next push is worth — anticipation
     * is what makes a combo feel like a decision rather than a side effect.
     */
    fun nextComboRung(combo: Int): Pair<Int, Double>? =
        COMBO_RUNGS.firstOrNull { it.first > combo }?.let { (need, mult) -> (need - combo) to mult }

    /**
     * Calculates base Beats/Leeks Per Second.
     */
    fun getBaseBps(): Double = _buildings.value.sumOf { it.totalBps } * autopilotBpsScale

    /**
     * Overclocks BPS based on live playing song BPM and active Fever frenzy.
     */
    fun getEffectiveBps(songBpm: Float): Double {
        val base = getBaseBps()
        val bpmFactor = (songBpm / 120.0).coerceIn(0.5, 4.0)
        val feverFactor = if (_feverSeconds.value > 0) feverMultiplier else 1.0
        return base * bpmFactor * feverFactor
    }

    /**
     * Incremental game loop tick (invoked every 100-200ms).
     */
    fun tick(dtSeconds: Double, songBpm: Float) {
        val effectiveBps = getEffectiveBps(songBpm)
        if (effectiveBps > 0.0) {
            val delta = effectiveBps * dtSeconds
            _leeks.value += delta
            _totalEarned.value += delta
        }

        // Fever countdown — in REAL seconds. tick() runs every 200 ms, and this used to take a
        // whole second off the clock on every one of those ticks, so a "15 second" fever (the
        // reward for filling the entire gauge, and the loudest moment in the loop) actually
        // lasted three seconds and the on-screen countdown fell in visible jumps of five.
        if (_feverSeconds.value > 0) {
            feverAccumulator += dtSeconds
            while (feverAccumulator >= 1.0 && _feverSeconds.value > 0) {
                feverAccumulator -= 1.0
                _feverSeconds.value = (_feverSeconds.value - 1).coerceAtLeast(0)
            }
            if (_feverSeconds.value == 0) {
                feverAccumulator = 0.0
                _feverEnergy.value = 0f
            }
        }

        // Golden Leek random spawn (approx every 30-45s)
        val now = SystemClock.elapsedRealtime()
        if (now >= nextGoldenLeekCheck && !_goldenLeekVisible.value) {
            _goldenLeekVisible.value = true
            // Golden Leek Magnet shortens the wait — the skill's description is now true.
            nextGoldenLeekCheck = now + (Random.nextLong(25000L, 45000L) * goldenLeekHaste).toLong()
        }
    }

    /**
     * Handles a rhythm tap on the orb that WAS judged against a real detected beat.
     *
     * Evaluates the graded tier, advances/breaks the combo (spending a shield if one is held),
     * charges fever, feeds the adaptive difficulty window and the daily setlist, and pays out.
     *
     * CONTRACT: never call this for a tap with no beat reference — use [freeTap]. Everything
     * this function increments is rendered later as a measured statistic.
     *
     * @param scoreScale difficulty payout scale (HioneRhythmTiming.RhythmTier.scoreScale): the
     *        tighter the windows the player is being graded on, the more each hit is worth.
     */
    fun tap(accuracy: HitAccuracy, songBpm: Float, scoreScale: Float = 1f): Double {
        _sessionTaps.value += 1
        if (accuracy == HitAccuracy.PERFECT) _sessionPerfects.value += 1
        if (accuracy.isGreatOrBetter) _sessionGreatOrBetter.value += 1

        // Rolling difficulty window: the tier follows how the player is doing RIGHT NOW.
        recentJudged.addLast(accuracy.isGreatOrBetter)
        while (recentJudged.size > TIER_WINDOW) recentJudged.removeFirst()
        val rollingPct =
            if (recentJudged.size >= 10) recentJudged.count { it } * 100f / recentJudged.size else -1f
        val newTier = HioneRhythmTiming.tierFor(_sessionTaps.value, rollingPct)
        if (newTier != _rhythmTier.value) {
            _rhythmTier.value = newTier
            // A difficulty change is a real event — say so, don't move the goalposts silently.
            pushFloatingText("${newTier.badge} ${newTier.label}", isCrit = false)
        }

        val baseClick = 1.0 + (getBaseBps() * 0.03)

        // --- COMBO: grow, hold, shield, or break -------------------------------------------
        var shieldSaved = false
        when {
            accuracy.growsCombo -> {
                val before = _combo.value
                _combo.value += 1
                if (comboMultiplier(_combo.value) > comboMultiplier(before)) {
                    // Crossing a rung is the loudest moment of a run: big centre pop and a small
                    // kick of the whole sheet, so the multiplier change is FELT, not read.
                    HioneGameFx.pop("x${_combo.value}!", 0xFFFFD166, big = true)
                    HioneGameFx.shake(if (_combo.value >= 32) 0.8f else 0.45f)
                }
                shieldProgress += 1
                if (shieldProgress >= SHIELD_EARN_STREAK && _comboShields.value < MAX_COMBO_SHIELDS) {
                    shieldProgress = 0
                    _comboShields.value += 1
                    pushFloatingText("COMBO SHIELD +1", isCrit = false)
                    com.hione.launcher.audio.HioneSeasonalAudioEngine.playLevelUpFanfare()
                }
            }
            // OK: you stayed on the train but didn't build. Tension without punishment.
            accuracy.keepsCombo -> { /* combo held */ }
            // A MISS on a real streak is the moment the game is actually about. One shield
            // turns that dread into a rescue — the single most replay-driving beat in the loop.
            _combo.value >= SHIELD_MIN_COMBO && _comboShields.value > 0 -> {
                _comboShields.value -= 1
                shieldSaved = true
                pushFloatingText("SHIELD SAVED x${_combo.value}", isCrit = true)
                com.hione.launcher.audio.HioneSeasonalAudioEngine.playCritChime()
            }
            else -> {
                if (_combo.value >= 10) pushFloatingText("COMBO BROKEN (x${_combo.value})", isCrit = false)
                // Near-miss on the ladder: losing a run two taps short of the next rung is the
                // single most "one more go" moment there is, so name it.
                val rung = nextComboRung(_combo.value)
                if (rung != null && rung.first <= 3 && _combo.value >= 6) {
                    HioneStagePerformance.say("${rung.first} short of ${String.format(Locale.US, "%.1f", rung.second)}x. so close.")
                }
                if (_combo.value >= 16) HioneGameFx.shake(0.6f)
                _combo.value = 0
                shieldProgress = 0
            }
        }
        if (!accuracy.isGreatOrBetter) shieldProgress = 0
        perfectStreak = if (accuracy == HitAccuracy.PERFECT) perfectStreak + 1 else 0

        val comboBonus = effectiveComboMultiplier(_combo.value)
        val isCrit = Random.nextFloat() < 0.09f // 9% Crit chance
        val critMultiplier = if (isCrit) 10.0 else 1.0
        val feverMult = if (_feverSeconds.value > 0) feverMultiplier else 1.0
        val bpmBonus = (songBpm / 120.0).coerceIn(0.8, 3.0)

        // --- FEVER: charged only by hits, weighted by the tier table --------------------------
        if (_feverSeconds.value == 0) {
            val chargeAmount = if (isCrit) accuracy.feverCharge * 2f else accuracy.feverCharge
            val newEnergy = (_feverEnergy.value + chargeAmount).coerceIn(0f, 100f)
            _feverEnergy.value = newEnergy
            if (newEnergy >= 100f) {
                _feverSeconds.value = feverDurationSeconds
                com.hione.launcher.audio.HioneSeasonalAudioEngine.playGoldenLeekJingle()
                HioneGameFx.pop("FEVER!", 0xFFFF3385, big = true)
                HioneGameFx.shake(1f)
                HioneSecrets.onFeverStarted()
                advanceChallenge(ChallengeKind.FEVERS, 1)
            }
        }

        // THE SHOW. The crowd reacts, Hione reacts, and a chaos mutator can be running with real
        // rules attached to it. Its payout multiplier is applied here so the number on screen and
        // the number banked can never disagree. See HioneStagePerformance.
        val stageMult = HioneStagePerformance.onJudgedTap(accuracy, _combo.value)

        val encoreMult = if (encoreTapsLeft > 0 && accuracy.isHit) { encoreTapsLeft--; ENCORE_MULT } else 1.0
        val totalTapYield = baseClick * accuracy.yieldMultiplier * comboBonus * critMultiplier *
            feverMult * bpmBonus * scoreScale.coerceIn(0.5f, 3f) * stageMult * encoreMult
        _leeks.value += totalTapYield
        _totalEarned.value += totalTapYield

        // Judgment word comes straight off the tier, so screen and economy can't disagree.
        val textStr = when {
            isCrit -> "+${formatNumber(totalTapYield)} CRIT"
            accuracy == HitAccuracy.MISS -> "+${formatNumber(totalTapYield)}"
            else -> "+${formatNumber(totalTapYield)} ${accuracy.label}"
        }
        pushFloatingText(textStr, isCrit)

        // --- JUICE: particle burst scaled by how big the moment actually is -------------------
        // A x60 combo hit must not look like a lone OK. Count rises with crit, fever and combo
        // rung, so the screen gets visibly louder exactly as the stakes do.
        val comboRungBoost = when {
            _combo.value >= 64 -> 8
            _combo.value >= 32 -> 5
            _combo.value >= 16 -> 3
            _combo.value >= 8 -> 1
            else -> 0
        }
        val count = (if (isCrit) 12 else if (accuracy.isGreatOrBetter) 7 else 4) +
            comboRungBoost + (if (_feverSeconds.value > 0) 4 else 0) + (if (shieldSaved) 10 else 0)
        spawnParticles(count.coerceAtMost(26))

        // Melodic Pentatonic Chime Feedback
        if (accuracy.isHit) {
            com.hione.launcher.audio.HioneSeasonalAudioEngine.playComboMelody(_combo.value, _feverSeconds.value > 0)
        }
        if (isCrit) {
            com.hione.launcher.audio.HioneSeasonalAudioEngine.playCritChime()
        }
        // Award Player XP
        val baseTapXp = when (accuracy) {
            HitAccuracy.PERFECT -> 25L
            HitAccuracy.GREAT -> 16L
            HitAccuracy.GOOD -> 9L
            HitAccuracy.OK -> 4L
            HitAccuracy.MISS -> 0L
        }
        val feverXpMult = if (_feverSeconds.value > 0) 2L else 1L
        val critXpMult = if (isCrit) 2L else 1L
        val gainedXp = baseTapXp * feverXpMult * critXpMult
        if (gainedXp > 0L) {
            addXp(appCtx, gainedXp)
        }

        recordDailyProgress(accuracy)
        return totalTapYield
    }

    /**
     * A tap with NO beat reference (nothing playing, or the detector has no recent pulse).
     *
     * It pays the incremental game's plain base click — the idle loop should still respond to a
     * finger — and deliberately touches nothing else: no accuracy counter, no combo, no fever,
     * no season rank, no telemetry. There was no beat, so there is no judgment to make, and a
     * judgment we did not make must never end up behind a number we display.
     */
    fun freeTap(): Double {
        val yieldAmount = 1.0 + (getBaseBps() * 0.03)
        _leeks.value += yieldAmount
        _totalEarned.value += yieldAmount
        pushFloatingText("+${formatNumber(yieldAmount)}", isCrit = false)
        spawnParticles(3)
        return yieldAmount
    }

    /** Shared floating-number spawner (used by judged taps, free taps and shield events). */
    private fun pushFloatingText(text: String, isCrit: Boolean) {
        val ft = FloatingText(
            id = SystemClock.uptimeMillis() + Random.nextInt(1000),
            text = text,
            x = Random.nextFloat() * 120f - 60f,
            y = Random.nextFloat() * 40f - 20f,
            isCrit = isCrit,
            isFever = _feverSeconds.value > 0
        )
        _floatingTexts.value = (_floatingTexts.value + ft).takeLast(6)
    }

    /** Shared particle burst. */
    private fun spawnParticles(count: Int) {
        if (count <= 0) return
        // Glyphs and tints come from the ACTIVE SKIN, so a burst looks like the skin you chose.
        val skin = _currentSkin.value
        val particleEmojis = skin.burstGlyphs
        val particleColors = skin.burstColors
        val newParticles = (0 until count).map {
            val angle = Random.nextFloat() * 2.0 * Math.PI
            val speed = Random.nextFloat() * 45f + 25f
            JuiceParticle(
                id = SystemClock.uptimeMillis() + it,
                x = 0f,
                y = 0f,
                vx = (Math.cos(angle) * speed).toFloat(),
                vy = (Math.sin(angle) * speed).toFloat(),
                emoji = particleEmojis.random(),
                colorHex = particleColors.random(),
                sizeDp = Random.nextFloat() * 6f + 14f
            )
        }
        _particles.value = (_particles.value + newParticles).takeLast(28)
    }

    /** Judged-tap progress toward Today's Setlist, with the one-off completion rewards. */
    private fun recordDailyProgress(accuracy: HitAccuracy) {
        if (accuracy == HitAccuracy.PERFECT) advanceChallenge(ChallengeKind.PERFECTS, 1)
        setChallengeAtLeast(ChallengeKind.COMBO, _combo.value)
        noMissRun = if (accuracy == HitAccuracy.MISS) 0 else noMissRun + 1
        setChallengeAtLeast(ChallengeKind.NO_MISS, noMissRun)
        setChallengeAtLeast(ChallengeKind.SONG_SCORE, HioneSongRecords.run.value.score.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val today = todayKey()
        val cur = _dailySetlist.value.let { if (it.dateKey == today) it else DailySetlist(dateKey = today) }
        var next = cur.copy(
            judgedTaps = cur.judgedTaps + 1,
            bestCombo = maxOf(cur.bestCombo, _combo.value),
            greatOrBetter = cur.greatOrBetter + if (accuracy.isGreatOrBetter) 1 else 0
        )
        var reward = 0.0
        if (next.tapsDone && (next.claimedMask and 1) == 0) { reward += DailySetlist.REWARD_EACH; next = next.copy(claimedMask = next.claimedMask or 1) }
        if (next.comboDone && (next.claimedMask and 2) == 0) { reward += DailySetlist.REWARD_EACH; next = next.copy(claimedMask = next.claimedMask or 2) }
        if (next.accuracyDone && (next.claimedMask and 4) == 0) { reward += DailySetlist.REWARD_EACH; next = next.copy(claimedMask = next.claimedMask or 4) }
        _dailySetlist.value = next
        if (reward > 0.0) {
            _leeks.value += reward
            _totalEarned.value += reward
            pushFloatingText("SETLIST GOAL +${formatNumber(reward)}", isCrit = true)
            com.hione.launcher.audio.HioneSeasonalAudioEngine.playLevelUpFanfare()
            saveState()
        }
    }

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())

    /**
     * Tapping the golden leek awards an instant bounty and 15s of 77x Fever Frenzy!
     */
    fun tapGoldenLeek(): Double {
        _goldenLeekVisible.value = false
        val reward = (getBaseBps() * 90.0).coerceAtLeast(77.0)
        _leeks.value += reward
        _totalEarned.value += reward
        _feverSeconds.value = feverDurationSeconds
        _feverEnergy.value = 100f
        // A bonus target is not a judged tap: it may hand you combo, but it must never touch
        // the accuracy counters that back the grade.
        _combo.value += 10
        com.hione.launcher.audio.HioneSeasonalAudioEngine.playGoldenLeekJingle()
        recordGoldenPickup()
        return reward
    }

    // =====================================================================================
    // LUCKY NOTES — the variable-ratio surprise
    // =====================================================================================

    /**
     * A lucky (golden) note on the highway was hit GREAT or better. What it pays is drawn from a
     * table, on purpose: a reward you cannot predict is the one you keep chasing (variable-ratio
     * reinforcement), and having several KINDS of prize means the surprise is in what you got,
     * not only in how much. Weights sum to 100.
     *
     * Returns the line to show. The caller guarantees the hit was judged against a real beat.
     */
    fun luckyNote(): String {
        recordGoldenPickup()
        advanceChallenge(ChallengeKind.LUCKY, 1)
        val roll = Random.nextInt(100)
        val text = when {
            roll < 45 -> {
                val bonus = (1.0 + getBaseBps() * 0.03) * 40.0
                _leeks.value += bonus; _totalEarned.value += bonus
                Mascot.lore("Lucky note: +${formatNumber(bonus)} leeks", "Lucky note: +${formatNumber(bonus)} notes")
            }
            roll < 65 -> {
                if (_comboShields.value < MAX_COMBO_SHIELDS) {
                    _comboShields.value += 1
                    "Lucky note: free combo shield"
                } else {
                    val bonus = (1.0 + getBaseBps() * 0.03) * 60.0
                    _leeks.value += bonus; _totalEarned.value += bonus
                    Mascot.lore("Lucky note: shields full, +${formatNumber(bonus)} leeks", "Lucky note: shields full, +${formatNumber(bonus)} notes")
                }
            }
            roll < 82 -> {
                if (_feverSeconds.value == 0) _feverEnergy.value = (_feverEnergy.value + 40f).coerceAtMost(99f)
                "Lucky note: fever +40%"
            }
            roll < 96 -> {
                encoreTapsLeft = 8
                HioneStagePerformance.say("wait, i know this one. (next 8 hits pay triple)")
                "Lucky note: ${Mascot.name} sings along, 3x for 8 hits"
            }
            else -> {
                _goldenLeekVisible.value = true
                Mascot.lore("Jackpot: a golden leek showed up", "Jackpot: a golden note showed up")
            }
        }
        com.hione.launcher.audio.HioneSeasonalAudioEngine.playGoldenLeekJingle()
        HioneGameFx.pop("LUCKY!", 0xFFFFD700, big = true)
        pushFloatingText(text, isCrit = true)
        spawnParticles(16)
        return text
    }

    private fun recordGoldenPickup() {
        _goldenPickups.value += 1
        val ctx = appCtx ?: return
        ioScope.launch { HioneSecrets.onGoldenPickup(ctx) }
    }

    // =====================================================================================
    // DAILY CHALLENGE
    // =====================================================================================

    private fun challengeKindFor(dateKey: String): ChallengeKind {
        val kinds = ChallengeKind.entries
        // Stable per date, different across consecutive days.
        val h = dateKey.hashCode().let { if (it < 0) -it else it }
        return kinds[h % kinds.size]
    }

    private fun currentChallenge(): DailyChallenge {
        val today = todayKey()
        val c = _dailyChallenge.value
        return if (c.dateKey == today) c else DailyChallenge(dateKey = today, kind = challengeKindFor(today))
    }

    private fun advanceChallenge(kind: ChallengeKind, by: Int) {
        val c = currentChallenge()
        if (c.kind != kind || c.claimed) { if (c !== _dailyChallenge.value) _dailyChallenge.value = c; return }
        settleChallenge(c.copy(progress = c.progress + by))
    }

    private fun setChallengeAtLeast(kind: ChallengeKind, value: Int) {
        val c = currentChallenge()
        if (c.kind != kind || c.claimed || value <= c.progress) { if (c !== _dailyChallenge.value) _dailyChallenge.value = c; return }
        settleChallenge(c.copy(progress = value))
    }

    private fun settleChallenge(c: DailyChallenge) {
        if (c.done && !c.claimed) {
            _dailyChallenge.value = c.copy(progress = c.kind.target, claimed = true)
            _leeks.value += DailyChallenge.REWARD_LEEKS
            _totalEarned.value += DailyChallenge.REWARD_LEEKS
            if (_comboShields.value < MAX_COMBO_SHIELDS) _comboShields.value += 1
            pushFloatingText("Daily challenge done +${formatNumber(DailyChallenge.REWARD_LEEKS)}", isCrit = true)
            HioneGameFx.pop("CHALLENGE DONE", 0xFFFFAB2E, big = true)
            HioneStagePerformance.say("daily challenge done. see you tomorrow? please?")
            com.hione.launcher.audio.HioneSeasonalAudioEngine.playLevelUpFanfare()
            saveState()
        } else {
            _dailyChallenge.value = c
        }
    }

    /**
     * Purchases an auto-producing building.
     */
    fun buyBuilding(id: String): Boolean {
        val list = _buildings.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false

        val b = list[index]
        val cost = b.currentCost
        if (_leeks.value >= cost) {
            _leeks.value -= cost
            list[index] = b.copy(owned = b.owned + 1)
            _buildings.value = list
            saveState()
            com.hione.launcher.audio.HioneSeasonalAudioEngine.playLevelUpFanfare()
            return true
        }
        return false
    }

    /**
     * Purchases a Producer Skill Upgrade.
     */
    fun buySkill(id: String): Boolean {
        val list = _skills.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false

        val s = list[index]
        if (s.level >= s.maxLevel) return false
        val cost = s.currentCost
        if (_leeks.value >= cost) {
            _leeks.value -= cost
            list[index] = s.copy(level = s.level + 1)
            _skills.value = list
            saveState()
            com.hione.launcher.audio.HioneSeasonalAudioEngine.playLevelUpFanfare()
            return true
        }
        return false
    }

    /**
     * Sets the active BPM observatory skin, if it has been earned.
     * Returns false for a locked skin — the caller shows what it takes instead.
     */
    fun setSkin(ctx: Context, skin: BpmSkin): Boolean {
        if (!skin.isAvailable(ctx)) return false
        _currentSkin.value = skin
        saveState()
        return true
    }

    /**
     * Evaluates achievement triggers and rewards bonus currency.
     */
    fun checkAchievements(): List<Achievement> {
        val list = _achievements.value.toMutableList()
        val newlyUnlocked = mutableListOf<Achievement>()

        for (i in list.indices) {
            val a = list[i]
            if (a.isUnlocked) continue

            val conditionMet = when (a.id) {
                // "in a row" is what the quest says, so count a real streak — it used to fire on
                // 10 perfects scattered across a session.
                "first_beat" -> perfectStreak >= 10
                "fever_queen" -> _feverSeconds.value > 0
                "diva_50" -> _combo.value >= 50
                // _sessionTaps counts JUDGED taps only, so this quest cannot be farmed by
                // mashing the node with nothing playing.
                "audio_arch" -> _sessionTaps.value >= 25
                "leek_tycoon" -> _totalEarned.value >= 1_000_000.0
                else -> false
            }

            if (conditionMet) {
                list[i] = a.copy(isUnlocked = true)
                _leeks.value += a.rewardLeeks
                _totalEarned.value += a.rewardLeeks
                newlyUnlocked.add(list[i])
                com.hione.launcher.audio.HioneSeasonalAudioEngine.playLevelUpFanfare()
            }
        }

        if (newlyUnlocked.isNotEmpty()) {
            _achievements.value = list
            saveState()
        }
        return newlyUnlocked
    }

    fun saveState() {
        val p = prefs ?: return
        val editor = p.edit()
        editor.putFloat(KEY_LEEKS, _leeks.value.toFloat())
        editor.putFloat(KEY_TOTAL_EARNED, _totalEarned.value.toFloat())
        editor.putLong(KEY_LAST_TIMESTAMP, System.currentTimeMillis())
        editor.putString("current_skin", _currentSkin.value.name)
        _buildings.value.forEach { b ->
            editor.putInt("b_${b.id}", b.owned)
        }
        _skills.value.forEach { s ->
            editor.putInt("s_${s.id}", s.level)
        }
        _achievements.value.forEach { a ->
            editor.putBoolean("ach_${a.id}", a.isUnlocked)
        }
        // Today's Setlist and the shields in hand survive a launcher restart; without this the
        // daily goals silently reset mid-day and the reward could be earned twice.
        val d = _dailySetlist.value
        editor.putString("daily_date", d.dateKey)
        editor.putInt("daily_taps", d.judgedTaps)
        editor.putInt("daily_combo", d.bestCombo)
        editor.putInt("daily_great", d.greatOrBetter)
        editor.putInt("daily_claimed", d.claimedMask)
        editor.putInt("combo_shields", _comboShields.value)
        val c = _dailyChallenge.value
        editor.putString("challenge_date", c.dateKey)
        editor.putInt("challenge_progress", c.progress)
        editor.putBoolean("challenge_claimed", c.claimed)
        editor.putInt("golden_pickups", _goldenPickups.value)
        editor.putInt(KEY_USER_LEVEL, _userLevel.value)
        editor.putLong(KEY_USER_XP, _userXp.value)
        editor.putInt(KEY_ACTIVE_LANES, _activeLanes.value)
        editor.apply()
    }

    /**
     * Resets the player back to base level (Level 1, 0 XP, 1 Lane) and wipes local and global unlocks.
     * Used for fresh progression testing from scratch.
     */
    fun resetToBaseLevel(ctx: Context) {
        _userLevel.value = 1
        _userXp.value = 0L
        _activeLanes.value = 1
        _leeks.value = 0.0
        _totalEarned.value = 0.0
        _combo.value = 0
        _comboShields.value = 0
        _sessionTaps.value = 0
        _sessionPerfects.value = 0
        _sessionGreatOrBetter.value = 0
        _goldenPickups.value = 0
        _buildings.value = initialBuildings
        _skills.value = initialSkills
        _achievements.value = initialAchievements

        val p = prefs ?: ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        p.edit().clear().apply()

        // Clear global unlocks and mirror
        try {
            android.provider.Settings.Global.putString(ctx.contentResolver, HioneUnlocks.SETTINGS_KEY, "{}")
        } catch (_: Throwable) {}
        try {
            ctx.getSharedPreferences("hione_unlocks_mirror", Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Throwable) {}
        saveState()
    }

    fun formatNumber(num: Double): String {
        return when {
            num >= 1_000_000_000_000.0 -> String.format(Locale.US, "%.2fT", num / 1_000_000_000_000.0)
            num >= 1_000_000_000.0 -> String.format(Locale.US, "%.2fB", num / 1_000_000_000.0)
            num >= 1_000_000.0 -> String.format(Locale.US, "%.2fM", num / 1_000_000.0)
            num >= 1_000.0 -> String.format(Locale.US, "%.1fK", num / 1_000.0)
            else -> String.format(Locale.US, "%.0f", num)
        }
    }
}
