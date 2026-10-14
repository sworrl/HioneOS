package com.hione.launcher.bpm

import com.hione.launcher.Mascot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * The part that makes it a GAME instead of a metronome with a score attached.
 *
 * WHAT WAS MISSING. The engine underneath is a good systems game: graded timing, a combo ladder,
 * shields, adaptive difficulty, a skill tree that does something. None of it can make you laugh,
 * because nothing in it is WATCHING YOU. You tapped, a number went up, and the number going up was
 * the whole event. There was no opponent, no stakes, and nothing that could surprise you.
 *
 * This adds the four things that were actually absent:
 *
 *  1. A CROWD, which is a resource you can lose. Miss enough and they walk out and the set is over.
 *     Every tap now has a consequence in a direction that matters.
 *  2. HIONE, reacting. She has a mood that tracks how you are doing and gets progressively less
 *     professional about it in both directions.
 *  3. CHAOS. Announced, time-boxed mutators that really change the rules, not cosmetic overlays.
 *  4. A HECKLER, who is a joke and a mechanic at once: shut them up by playing well.
 *
 * Everything here reads state the engine already measures. Nothing invents a performance number.
 */
object HioneStagePerformance {

    // ─────────────────────────────────────────────────────────── crowd
    /** 0 = the room is empty and the set is over. 100 = they are on the barrier. */
    private val _crowd = MutableStateFlow(55f)
    val crowd: StateFlow<Float> = _crowd.asStateFlow()

    private val _setOver = MutableStateFlow(false)
    val setOver: StateFlow<Boolean> = _setOver.asStateFlow()

    /** Peak crowd this set, for the end card. */
    var peakCrowd = 55f
        private set

    // ─────────────────────────────────────────────────────────── Hione
    enum class Mood(val label: String, val face: String) {
        DEVASTATED("devastated", "(╥﹏╥)"),
        WORRIED("worried", "(・_・;)"),
        NEUTRAL("professional", "( ・ω・)"),
        INTO_IT("into it", "(ﾉ◕ヮ◕)ﾉ"),
        HYPED("hyped", "ヽ(°〇°)ﾉ"),
        UNHINGED("unhinged", "☆ﾟ+.(★ω★).+ﾟ☆")
    }

    private val _mood = MutableStateFlow(Mood.NEUTRAL)
    val mood: StateFlow<Mood> = _mood.asStateFlow()

    /** The most recent thing she said. Null once it has been on screen long enough. */
    private val _saying = MutableStateFlow<String?>(null)
    val saying: StateFlow<String?> = _saying.asStateFlow()

    // ─────────────────────────────────────────────────────────── chaos
    /**
     * A mutator that genuinely changes the rules for its duration. [windowScale] multiplies the
     * timing windows, [payoutScale] multiplies the yield, and the flags are read by the UI.
     */
    enum class Chaos(
        val title: String,
        val blurb: String,
        val windowScale: Float,
        val payoutScale: Float,
        val hidesBeat: Boolean = false,
        val mirrorsBar: Boolean = false,
        val endsOnMiss: Boolean = false
    ) {
        NONE("", "", 1f, 1f),
        NEGI_STORM(Mascot.lore("NEGI STORM", "NOTE STORM"), Mascot.lore("Tighter windows. Double leeks. Do not blink.", "Tighter windows. Double notes. Do not blink."), 0.7f, 2.0f),
        SILENT_DISCO("SILENT DISCO", "The beat marker is gone. Go on feel.", 1.15f, 1.8f, hidesBeat = true),
        MIRROR_STAGE("MIRROR STAGE", "Early reads as late. Your brain is the obstacle.", 1f, 1.6f, mirrorsBar = true),
        STAGE_DIVE("STAGE DIVE", "One miss and you hit the floor. Until then, triple.", 1f, 3.0f, endsOnMiss = true),
        LEEK_RAIN(Mascot.lore("LEEK RAIN", "NOTE RAIN"), Mascot.lore("It is raining leeks and nobody knows why.", "It is raining notes and nobody knows why."), 1.25f, 1.4f),
        DOUBLE_TIME("DOUBLE TIME", "Everything is fine. Everything is faster.", 0.8f, 2.2f)
    }

    private val _chaos = MutableStateFlow(Chaos.NONE)
    val chaos: StateFlow<Chaos> = _chaos.asStateFlow()
    private var chaosTapsLeft = 0
    private var tapsUntilChaos = 60 + Random.nextInt(40)

    // ─────────────────────────────────────────────────────────── heckler
    private val _heckler = MutableStateFlow<String?>(null)
    val heckler: StateFlow<String?> = _heckler.asStateFlow()
    private var hecklerStreakNeeded = 0

    // ─────────────────────────────────────────────────────────── lines
    // Written to escalate. The joke is that she completely loses her composure in both directions.

    private val LINES_DEVASTATED = listOf(
        "it's ok. it's fine. i have other songs.",
        "the drummer is fine. the drummer is a computer. the drummer is fine.",
        "i have been on stage for 6 minutes and aged 9 years",
        "please. one. on the beat. one.",
        "i am going to go stand over there for a bit",
        Mascot.lore("somebody get the leek. i need the leek.", "somebody get the note. i need the note.")
    )
    private val LINES_WORRIED = listOf(
        "we can fix this",
        "that was a choice",
        "hm.",
        "you're finding your own tempo. respect. wrong, but respect.",
        "i'm not mad, i'm recalibrating"
    )
    private val LINES_NEUTRAL = listOf(
        "good. keep that.",
        "solid.",
        "that's the one.",
        "we're locked in",
        "clean."
    )
    private val LINES_INTO_IT = listOf(
        "OK that's what i'm talking about",
        "the crowd felt that one",
        "you're ON it",
        "do that again but louder",
        "somebody's been practicing"
    )
    private val LINES_HYPED = listOf(
        "STOP. THAT WAS PERFECT.",
        "I'M NOT EVEN SINGING ANYMORE I'M JUST WATCHING",
        Mascot.lore("THE LEEK IS GLOWING. WHY IS THE LEEK GLOWING.", "THE NOTE IS GLOWING. WHY IS THE NOTE GLOWING."),
        "SOMEONE IN THE FRONT ROW IS CRYING",
        "I'VE TOLD THE BAND TO STOP. IT'S JUST YOU NOW."
    )
    private val LINES_UNHINGED = listOf(
        "I HAVE BECOME TEMPO ITSELF",
        "THE VENUE IS LEAVING THE GROUND",
        "I CAN SEE THE BEAT. I CAN SEE IT. IT'S BEAUTIFUL.",
        "SECURITY SAYS WE HAVE TO STOP. SECURITY IS ALSO DANCING.",
        "TIME IS A SUGGESTION AND YOU ARE THE ONE MAKING IT",
        "THE SOUND GUY HAS ASCENDED"
    )

    private val HECKLES = listOf(
        "my nan has better timing",
        "PLAY SOMETHING GOOD",
        "is this the soundcheck",
        "i drove four hours for this",
        "BOOOO... sorry. sorry. that was harsh.",
        "i could do that",
        "turn the metronome UP"
    )
    private val HECKLER_EJECTED = listOf(
        "the heckler has left. the heckler is running.",
        "they are now clapping. traitor.",
        "heckler ejected. crowd absolutely loved that.",
        "they went quiet. you did that."
    )

    private val ANNOUNCER_RUNGS = mapOf(
        8 to "that's a run",
        16 to "SIXTEEN. the room noticed.",
        32 to "THIRTY-TWO IN A ROW",
        64 to "SIXTY-FOUR. someone check on the announcer.",
        128 to "ONE HUNDRED AND TWENTY-EIGHT. THIS IS A CRIME SCENE."
    )

    // ─────────────────────────────────────────────────────────── the loop

    /** Start a fresh set. Called when the game screen opens. */
    fun beginSet() {
        _crowd.value = 55f
        peakCrowd = 55f
        _setOver.value = false
        _mood.value = Mood.NEUTRAL
        _saying.value = "let's see what you've got"
        _chaos.value = Chaos.NONE
        chaosTapsLeft = 0
        tapsUntilChaos = 60 + Random.nextInt(40)
        _heckler.value = null
        hecklerStreakNeeded = 0
    }

    /**
     * One judged tap happened. Everything in here is driven by an accuracy that was measured
     * against a real detected beat; a free tap never reaches this function.
     *
     * Returns the payout multiplier the chaos mutator contributes, so the engine can apply it.
     */
    fun onJudgedTap(accuracy: HitAccuracy, combo: Int): Float {
        if (_setOver.value) return 1f

        // ── crowd. Good play fills the room, bad play empties it, and the slope is steeper on the
        // way down because that is how rooms work.
        val delta = when (accuracy) {
            HitAccuracy.PERFECT -> 2.2f
            HitAccuracy.GREAT -> 1.4f
            HitAccuracy.GOOD -> 0.4f
            HitAccuracy.OK -> -0.6f
            HitAccuracy.MISS -> -3.4f
        }
        _crowd.value = (_crowd.value + delta).coerceIn(0f, 100f)
        if (_crowd.value > peakCrowd) peakCrowd = _crowd.value

        if (_crowd.value <= 0f) {
            _setOver.value = true
            _mood.value = Mood.DEVASTATED
            _saying.value = "…they've gone. all of them. even the sound guy."
            _chaos.value = Chaos.NONE
            _heckler.value = null
            return 1f
        }

        // ── mood follows the crowd, with combo able to push it over the top.
        val newMood = when {
            combo >= 64 && _crowd.value > 80f -> Mood.UNHINGED
            _crowd.value > 82f -> Mood.HYPED
            _crowd.value > 64f -> Mood.INTO_IT
            _crowd.value > 38f -> Mood.NEUTRAL
            _crowd.value > 16f -> Mood.WORRIED
            else -> Mood.DEVASTATED
        }
        if (newMood != _mood.value) {
            _mood.value = newMood
            _saying.value = linesFor(newMood).random()
        } else if (Random.nextFloat() < 0.045f) {
            // Occasional unprompted commentary, so she is not silent between mood changes.
            _saying.value = linesFor(newMood).random()
        }

        ANNOUNCER_RUNGS[combo]?.let { _saying.value = it }

        // ── heckler: shows up when the room is thin, leaves when you play through them.
        if (_heckler.value == null && _crowd.value < 34f && Random.nextFloat() < 0.05f) {
            _heckler.value = HECKLES.random()
            hecklerStreakNeeded = 4
        } else if (_heckler.value != null) {
            if (accuracy.isGreatOrBetter) {
                hecklerStreakNeeded--
                if (hecklerStreakNeeded <= 0) {
                    _heckler.value = null
                    _saying.value = HECKLER_EJECTED.random()
                    _crowd.value = (_crowd.value + 8f).coerceAtMost(100f)
                }
            } else {
                hecklerStreakNeeded = 4
            }
        }

        // ── chaos
        val active = _chaos.value
        if (active != Chaos.NONE) {
            chaosTapsLeft--
            if (active.endsOnMiss && accuracy == HitAccuracy.MISS) {
                _chaos.value = Chaos.NONE
                _saying.value = "you hit the floor. we do not speak of this."
                chaosTapsLeft = 0
            } else if (chaosTapsLeft <= 0) {
                _chaos.value = Chaos.NONE
                tapsUntilChaos = 70 + Random.nextInt(60)
            }
        } else {
            tapsUntilChaos--
            // Never during a collapse. Kicking someone while the room empties is not funny.
            if (tapsUntilChaos <= 0 && _crowd.value > 25f) {
                val pick = Chaos.entries.filter { it != Chaos.NONE }.random()
                _chaos.value = pick
                chaosTapsLeft = 24 + Random.nextInt(24)
                _saying.value = pick.blurb
            }
        }
        return _chaos.value.payoutScale
    }

    /** Timing windows are scaled by the active mutator. Read by the judging code. */
    fun windowScale(): Float = _chaos.value.windowScale

    /** The set ended because the room emptied. Call to get back on stage. */
    fun encore() = beginSet()

    /** Clears the speech bubble once the UI has shown it long enough. */
    fun clearSaying() { _saying.value = null }

    /** Put a specific line in her mouth (lucky notes, near-misses, records). */
    fun say(line: String) { _saying.value = line }

    // Reaction lines for the moments the engine now names. Same rule as the rest: she reacts to
    // what actually happened, and she is a little too invested.
    private val LINES_NEAR_PERFECT = listOf(
        "that was SO close to perfect. i felt it in my twintails",
        "a hair. a single hair off. i'm counting it emotionally",
        "nearly. NEARLY.",
        "the PERFECT window saw you coming and flinched"
    )
    private val LINES_NEW_BEST = listOf(
        Mascot.lore("new personal best. i'm telling the leeks.", "new personal best. i'm telling the notes."),
        "you just beat yourself. respectfully. on this exact song.",
        "that's a record. somebody frame this tap.",
        "PB! the sound guy is crying again"
    )
    private val LINES_SECRET = listOf(
        "wait. something just changed. go look around.",
        "you found something you weren't supposed to find yet. nice.",
        "i'm not allowed to say what that was. i can wink though."
    )
    private val LINES_LUCKY = listOf(
        "gold note. GOLD. NOTE.",
        "luck is a skill if you hit it on the beat",
        "that note was shiny and you were ready"
    )

    private var lastNearPerfectMs = 0L

    /** A GREAT that missed PERFECT by a few ms. Rate-limited so it stays funny. */
    fun onNearPerfect() {
        val now = System.currentTimeMillis()
        if (now - lastNearPerfectMs < 9_000L) return
        lastNearPerfectMs = now
        if (Random.nextFloat() < 0.5f) _saying.value = LINES_NEAR_PERFECT.random()
    }

    fun onNewBest() { _saying.value = LINES_NEW_BEST.random() }
    fun onSecretFound() { _saying.value = LINES_SECRET.random() }
    fun onLuckyNote() { _saying.value = LINES_LUCKY.random() }

    private fun linesFor(m: Mood) = when (m) {
        Mood.DEVASTATED -> LINES_DEVASTATED
        Mood.WORRIED -> LINES_WORRIED
        Mood.NEUTRAL -> LINES_NEUTRAL
        Mood.INTO_IT -> LINES_INTO_IT
        Mood.HYPED -> LINES_HYPED
        Mood.UNHINGED -> LINES_UNHINGED
    }
}
