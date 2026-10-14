package com.hione.player

import android.content.Context
import java.io.File

/**
 * The real gate for the BPM-game visualiser rewards.
 *
 * WHY THIS EXISTS. The rewards "Negi Rain", "Vocaloid Circuit" and "Vortex Core" used to gate
 * ShaderPreset ordinals 3/4 and ProjectMPreset ordinal 3. On a working device neither does
 * anything: the GLES2 shader engine only runs when libprojectM failed to load, and nothing ever
 * saves a ProjectMPreset while the native setPreset is a no-op. What the player actually SEES is
 * projectM shuffling every .milk file in filesDir/presets, and the Hione look ships there as eight
 * files per family ("Hione - Negi Rain 01 (Teal).milk" ...). So the gate has to act on the files.
 *
 * HOW. Before every playlist load, each gated family's files are moved out of the presets dir into
 * a sibling vault (filesDir/presets_vault) while the reward is not earned, and moved back once it
 * is. The vault is a SIBLING, not a child, because the native side adds the presets dir
 * recursively. Only explicit filenames are touched, taken from the assets/hione_presets listing
 * (read once per process): the presets dir holds ~9.8k files and is never listed here.
 *
 * Re-applied after ensurePresets on every load, so a library re-sync (which copies all of
 * hione_presets back in, locked families included) is put right before projectM ever sees it.
 */
object HioneVizPresetGates {
    private const val ASSET_DIR = "hione_presets"
    private const val VAULT_DIR = "presets_vault"

    /** Family filename prefix -> unlock id. The trailing space keeps "Hione Vortex" from matching a longer family name. */
    private val FAMILY_GATES: List<Pair<String, String>> = listOf(
        "Hione - Negi Rain " to HioneUnlocksReader.PLAYER_VIZ_NEGI_RAIN,
        "Hione - Vocaloid Circuit " to HioneUnlocksReader.PLAYER_VIZ_VOCALOID_CIRCUIT,
        "Hione - Hione Vortex " to HioneUnlocksReader.PLAYER_PROJECTM_VORTEX_CORE
    )

    @Volatile private var gatedNames: List<Pair<String, String>>? = null   // filename -> unlock id

    /** The gated filenames, from the APK's own asset listing. Cached: the APK cannot change under us. */
    private fun names(ctx: Context): List<Pair<String, String>> {
        gatedNames?.let { return it }
        val all = runCatching { ctx.assets.list(ASSET_DIR)?.toList() }.getOrNull() ?: emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (n in all) {
            if (!n.endsWith(".milk", ignoreCase = true)) continue
            val gate = FAMILY_GATES.firstOrNull { n.startsWith(it.first) } ?: continue
            out.add(n to gate.second)
        }
        // An empty listing is not cached: it is more likely a transient asset failure than an APK
        // with no Hione presets, and the next surface creation should get to try again.
        if (out.isNotEmpty()) gatedNames = out
        return out
    }

    /**
     * Bring [presetsDir] in line with what the player has earned. Call immediately before handing
     * the dir to projectM. Never throws; a failed move leaves that one file where it was.
     *
     * Uses the reader's ENABLED view, not just unlocked: these families have no picker, they just
     * turn up in the shuffle, so the game's "off" switch is the only way a player can say they do
     * not want them. A read failure in the reader means nothing is enabled, so the gated families
     * go to the vault (fail closed, same as every other reward).
     */
    @Synchronized
    fun apply(ctx: Context, presetsDir: File) {
        try {
            // A reward earned in the game seconds ago must count on this very load, not after the
            // reader's 5 s cache happens to expire.
            HioneUnlocksReader.invalidate()
            val vault = File(presetsDir.parentFile ?: return, VAULT_DIR)
            var vaulted = 0
            var restored = 0
            for ((name, id) in names(ctx)) {
                val live = File(presetsDir, name)
                val held = File(vault, name)
                if (HioneUnlocksReader.isEnabled(ctx, id)) {
                    if (held.exists()) {
                        // A fresh re-sync may already have put a newer copy back; that one wins.
                        if (live.exists()) held.delete() else if (move(held, live)) restored++
                    }
                } else if (live.exists()) {
                    vault.mkdirs()
                    if (held.exists()) held.delete()   // replace any stale copy from an older library
                    if (move(live, held)) vaulted++
                }
            }
            if (vaulted > 0 || restored > 0) {
                android.util.Log.i("projectM", "reward presets: $vaulted vaulted, $restored restored")
            }
        } catch (t: Throwable) {
            android.util.Log.w("projectM", "reward preset gating failed: $t")
        }
    }

    private fun move(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        // Same filesystem, so rename should not fail; if it does, fall back to copy + delete.
        return runCatching {
            from.inputStream().use { i -> to.outputStream().use { o -> i.copyTo(o) } }
            from.delete()
            true
        }.getOrDefault(false)
    }
}
