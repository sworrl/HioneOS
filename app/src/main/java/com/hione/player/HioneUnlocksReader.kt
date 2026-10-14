package com.hione.player

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

/**
 * READ-ONLY view of the cross-app unlocks earned in the BPM rhythm game.
 *
 * The game (in the launcher, `com.hione.launcher.bpm.HioneUnlocks`) is the ONLY writer. It keeps a
 * JSON object in `Settings.Global` under `hione_unlocks`:
 *
 *     {"<unlock_id>": {"at": <epochMillis>, "src": "bpm", "off": true}}
 *
 * "off" is optional. It means the player earned the reward and then switched it off from the
 * game's Secrets list. So there are two separate questions:
 *   - UNLOCKED: the key is present. Earned, whatever the switch says. Pickers use this.
 *   - ENABLED:  the key is present AND "off" is not true. Things that simply appear on their own
 *               (the leek on Now Playing) use this, because the player has no other way to say
 *               "not that one, thanks".
 *
 * Hione Music only reads it, to decide which cosmetics are available. The ids are copied here
 * verbatim and must never be renamed on either side: a renamed id silently re-locks something the
 * player already earned.
 *
 * FAILURE POSTURE: if the read throws or the value is missing, NOTHING is treated as unlocked.
 * Failing open would hand out every reward for free and make the game pointless; failing closed is
 * recoverable (play a few more taps, or the game re-grants on its next evaluate()).
 */
object HioneUnlocksReader {
    private const val SETTINGS_KEY = "hione_unlocks"

    /** Dynamic color shifting in Now Playing & chrome (unlocked early in BPM Game). */
    const val PLAYER_DYNAMIC_COLOR = "player.feature.dynamic_color"

    const val PLAYER_VIZ_NEGI_RAIN = "player.viz.negi_rain"
    const val PLAYER_VIZ_VOCALOID_CIRCUIT = "player.viz.vocaloid_circuit"
    const val PLAYER_PROJECTM_VORTEX_CORE = "player.projectm.vortex_core"
    const val PLAYER_TAPE_SAKURA = "player.tape.sakura"
    const val PLAYER_TAPE_VAPORWAVE = "player.tape.vaporwave"
    const val PLAYER_TAPE_GLOWWORM = "player.tape.glowworm"

    /** Secrets: never shown at all until earned (see [SECRET_TAPE_INDICES]). */
    const val SECRET_TAPE_HIONE39 = "secret.player.tape.hione39"
    const val SECRET_LEEK_SPIN = "secret.player.leek_spin"

    /** Both views of one parse. Fields are never mutated after construction. */
    private class Snapshot(val unlocked: Set<String>, val enabled: Set<String>)
    private val EMPTY = Snapshot(emptySet(), emptySet())

    /**
     * Cheap in-memory cache. The game writes rarely and a Settings read per grid cell is silly.
     * The JSON is parsed once per refresh into both sets, so isEnabled() costs no extra parse.
     */
    @Volatile private var cached: Snapshot? = null
    @Volatile private var cachedAt = 0L

    private fun snapshot(ctx: Context): Snapshot {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAt < 5_000L) return it }
        val snap = try {
            val raw = Settings.Global.getString(ctx.contentResolver, SETTINGS_KEY)
            if (raw.isNullOrBlank()) EMPTY else {
                val o = JSONObject(raw)
                val unlocked = HashSet<String>()
                val enabled = HashSet<String>()
                val it = o.keys()
                while (it.hasNext()) {
                    val id = it.next()
                    unlocked.add(id)
                    // A value that is not an object (an older writer, or a hand edit) still means
                    // "earned"; it just cannot carry an off switch, so it counts as on.
                    val off = o.optJSONObject(id)?.optBoolean("off", false) ?: false
                    if (!off) enabled.add(id)
                }
                Snapshot(unlocked, enabled)
            }
        } catch (_: Throwable) {
            // Fail closed. See the class doc. Nothing unlocked also means nothing enabled.
            EMPTY
        }
        cached = snap; cachedAt = now
        return snap
    }

    fun unlockedIds(ctx: Context): Set<String> = snapshot(ctx).unlocked

    fun isUnlocked(ctx: Context, id: String): Boolean = id in snapshot(ctx).unlocked

    /** Earned AND not switched off in the game's Secrets list. */
    fun isEnabled(ctx: Context, id: String): Boolean = id in snapshot(ctx).enabled

    /** Forget the cache so a grant made while Hione Music is open shows up on the next picker open. */
    fun invalidate() { cached = null }

    /**
     * Tape theme index -> unlock id, for the indices that are earned. Indices are the position in
     * `TAPE_THEMES`; an unlisted index is always available. Keep this keyed by index rather than by
     * filtering the list, because the saved preference is a raw ordinal and removing an entry would
     * shift every later theme.
     */
    val TAPE_THEME_GATES: Map<Int, String> = mapOf(
        15 to PLAYER_TAPE_SAKURA,
        16 to PLAYER_TAPE_VAPORWAVE,
        17 to PLAYER_TAPE_GLOWWORM,
        18 to SECRET_TAPE_HIONE39
    )

    /**
     * Tape theme indices that are SECRETS rather than visible rewards. A visible reward (15-17) sits
     * in the picker greyed out with a lock, so the player knows it exists and can chase it. A secret
     * is left out of the picker entirely until it is earned; the cycle button already skips every
     * locked index, so it never surfaces there either.
     */
    val SECRET_TAPE_INDICES: Set<Int> = setOf(18)

    /** True when [index] is a secret theme that should not be shown at all yet. */
    fun hiddenTapeTheme(ctx: Context, index: Int): Boolean =
        index in SECRET_TAPE_INDICES && locked(ctx, TAPE_THEME_GATES, index)

    /** `ShaderPreset` ordinal -> unlock id. Same raw-ordinal reasoning as the tape gates. */
    val SHADER_PRESET_GATES: Map<Int, String> = mapOf(
        3 to PLAYER_VIZ_NEGI_RAIN,
        4 to PLAYER_VIZ_VOCALOID_CIRCUIT
    )

    /** `ProjectMPreset` ordinal -> unlock id. */
    val PROJECTM_PRESET_GATES: Map<Int, String> = mapOf(
        3 to PLAYER_PROJECTM_VORTEX_CORE
    )

    /** True when [index] is gated and not yet earned. */
    fun locked(ctx: Context, gates: Map<Int, String>, index: Int): Boolean {
        val id = gates[index] ?: return false
        return !isUnlocked(ctx, id)
    }

    /** First index at or after [from] that is not locked, wrapping. Used by the cycle buttons. */
    fun nextUnlocked(ctx: Context, gates: Map<Int, String>, from: Int, size: Int): Int {
        if (size <= 0) return 0
        for (step in 1..size) {
            val cand = (from + step) % size
            if (!locked(ctx, gates, cand)) return cand
        }
        return (from + 1) % size
    }
}
