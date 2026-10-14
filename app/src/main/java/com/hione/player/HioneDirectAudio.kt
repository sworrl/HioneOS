package com.hione.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.util.Log

/**
 * DTA (Direct Transport Audio) controller — puts Hione Music on the M500's bit-perfect DIRECT
 * output path to the dual CS43198 DACs instead of the default Android 48kHz mixer.
 *
 * How the platform gates this (verified against the device's decompiled framework AudioTrack AND
 * the disassembled QTI AudioPolicyManager — BOTH gates must pass):
 *  - HiBy gate (framework AudioTrack ctor, isDirectEnable()): the app's process name must appear
 *    in Settings.Global "direct_support_app_list" (JSON: {"list":[{"packageName":"..."}]}), the
 *    route must not be A2DP/speaker, and no other app may currently hold the direct flag. When it
 *    passes, the framework tags the track's AudioAttributes with "direct_flag=1" and sets
 *    vendor.audio.hiby.hw.diect_flags_enable=yes / diect_proess_name=<app> into the HAL.
 *  - QTI gate (AudioPolicyManager::getOutputForDevices, "Force direct flags to use pcm offload"):
 *    a linear-PCM STREAM_MUSIC track with usage MEDIA is force-routed to the vendor "direct_pcm"
 *    DIRECT profile (16/24/32-bit int, native rate) ONLY when its requested output flags are
 *    NONE. AudioTrack.shouldEnablePowerSaving() silently adds FLAG_DEEP_BUFFER to any MUSIC
 *    track whose buffer is >= 100ms of audio, which disqualifies it — that (Media3's 250ms+
 *    default buffer) is why playback used to land on the deep_buffer MIXER thread despite the
 *    allow-list. HioneDirectAudioSink therefore requests just under 100ms (see its configure()).
 *  - The factory list contains only com.hiby.music; this object adds com.hione.player so OUR
 *    player gets the exact same hardware path (interop with our own device, same access pattern).
 *
 * Verification: [status] reads the HAL's own flags back via AudioManager.getParameters, so the UI
 * can prove "DIRECT to DAC" vs "Android mixer" truthfully rather than assuming.
 */
object HioneDirectAudio {
    private const val TAG = "HioneDirectAudio"
    private const val KEY_APP_LIST = "direct_support_app_list"
    private const val KEY_VOLUME_LOCK = "vendor.audio.hw.volume_lock"

    data class DirectStatus(
        /** The HAL reports the direct flag is up. */
        val directFlagEnabled: Boolean,
        /** Which process currently holds the direct output (should be us while playing). */
        val holderProcess: String,
        /** We are in the allow-list, so new AudioTracks we open are eligible for DIRECT. */
        val allowListed: Boolean
    ) {
        val weHoldDirect: Boolean get() = directFlagEnabled && holderProcess == "com.hione.player"
    }

    /** Current allow-list JSON (empty string when unset). */
    fun allowList(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, KEY_APP_LIST) ?: ""

    fun isAllowListed(ctx: Context): Boolean =
        allowList(ctx).contains(ctx.packageName)

    /**
     * Ensure com.hione.player is in the direct allow-list. Tries a plain Settings write first
     * (works when the ROM grants us WRITE_SECURE_SETTINGS), falls back to root. Preserves any
     * existing entries (keeps com.hiby.music etc). Safe to call every launch — no-ops when
     * already present. Returns true when the list contains us afterwards.
     */
    fun ensureAllowListed(ctx: Context): Boolean {
        val current = allowList(ctx)
        if (current.contains(ctx.packageName)) return true
        val updated = when {
            current.isBlank() ->
                """{"list":[{"packageName":"com.hiby.music"},{"packageName":"${ctx.packageName}"}]}"""
            // Splice our entry into the existing JSON list (the framework check is a plain
            // String.contains, but keep the JSON well-formed for the vendor code that parses it).
            current.contains("\"list\":[") ->
                current.replaceFirst("\"list\":[", "\"list\":[{\"packageName\":\"${ctx.packageName}\"},")
            else ->
                """{"list":[{"packageName":"com.hiby.music"},{"packageName":"${ctx.packageName}"}]}"""
        }
        // Platform path ONLY (standing directive: HioneOS is unrestricted via platform-signing,
        // never su). WRITE_SECURE_SETTINGS is granted to the platform-signed build; if this write
        // fails the fix is the ROM permission grant, not a root shell.
        val direct = runCatching {
            Settings.Global.putString(ctx.contentResolver, KEY_APP_LIST, updated)
        }.getOrDefault(false)
        if (!direct) Log.w(TAG, "ensureAllowListed: Settings write denied — check WRITE_SECURE_SETTINGS grant")
        val ok = allowList(ctx).contains(ctx.packageName)
        Log.i(TAG, "ensureAllowListed: ok=$ok list=${allowList(ctx)}")
        return ok
    }

    /**
     * Unlock the full 0-100 STREAM_MUSIC range on the wired outputs.
     *
     * Root cause of the "volume stuck at 35/100 on the 4.4mm jack" clamp (verified in the
     * decompiled vendor SystemUI/services): it is NOT the audio_policy volume curves and NOT
     * safe-media-volume. HiBy ships a "volume lock" — `ro.vendor.volume_lock_enable=yes` on the
     * M500 arms it, and Settings.Global "vendor.audio.hw.volume_lock" == "yes" makes SystemUI's
     * HibyBarTool/HiByNewVolumeDialog clamp STREAM_MUSIC to getLockMaxVolume(): 35 for the
     * balanced/single-ended phone-out, 40 for h2w/lineout, 80 for USB/SPDIF. Per-jack saved
     * levels live in Settings.Global under the jack's product name ("balance", "h2w", "lineout",
     * "balance_lo", ...) and get re-clamped on plug. Writing "no" here is exactly the switch the
     * vendor's own volume-lock toggle flips: SystemUI re-reads the Global on every volume event,
     * and HibyAudioSettingInitUtils preserves a non-empty value across boots. The vendor MUSIC
     * curve (DEFAULT_MEDIA_VOLUME_CURVE, 0 dB at index 100) then rules the whole range.
     */
    @Volatile private var unlockPushed = false

    fun ensureFullVolumeRange(ctx: Context) {
        val cr = ctx.contentResolver
        runCatching {
            Settings.Global.putString(cr, KEY_VOLUME_LOCK, "no")
            Settings.Global.putString(cr, "volum_tips_ce_flag", "yes")
            Settings.Global.putInt(cr, "audio_safe_volume_state", 0)
            Settings.System.putInt(cr, "max_volume_value", 100)
            Settings.System.putInt(cr, "max_volume_value_preout", 100)
            // NOTE: deliberately NOT writing volume_music_headphone/speaker/headset = 100 here.
            // Those are the per-jack CURRENT levels AudioService restores at boot, not ceilings -
            // writing 100 would start every wired output at full volume after a reboot
            // (hearing-safety hazard). The ceiling is max_volume_value above; the level is the
            // user's.
        }
        // The unlock is idempotent - push/broadcast it once per process. Re-broadcasting
        // "volume_lock_state_update" on every player start made HiBy SystemUI's HibyBarTool
        // re-apply its saved per-jack level, snapping the volume back on each launch.
        if (unlockPushed) return
        unlockPushed = true
        // No su fallback (standing no-root directive): the Settings writes above run on the
        // platform WRITE_SECURE_SETTINGS/WRITE_SETTINGS grants, and the HAL push below covers the
        // setprop mirror (SystemUI re-reads the Global on every volume event anyway).
        // Live-apply: push the unlock into the audio HAL (what SystemUI's own receiver does) and
        // poke HibyBarTool's registered "volume_lock_state_update" receiver so the running
        // SystemUI drops its 35/40 cap without a reboot.
        pushToHal(ctx, KEY_VOLUME_LOCK, "no")
        pushToHal(ctx, "volum_tips_ce_flag", "yes")
        runCatching {
            ctx.sendBroadcast(Intent("volume_lock_state_update"))
            ctx.sendBroadcast(Intent("volum_tips_ce_flag_change"))
            ctx.sendBroadcast(Intent("com.android.settings.action.MAX_VOLUME_CHANGED"))
        }
    }

    /**
     * BEST AUDIO MODE, always: force the CS43198 pair to HIGH gain.
     *
     * Per the standing audio-lockdown directive this device never runs a lower-quality path, and
     * gain is part of that - LOW gain is what made IEMs sound quiet. Writes the Settings rows the
     * HiBy framework/SystemUI read AND pushes the parameters into the audio HAL (the only
     * root-free way to actually move the hardware; the old su/sysfs path silently no-opped).
     * Idempotent and cheap; safe to call on every player start.
     */
    fun ensureMaxGain(ctx: Context) = ensureBestAudio(ctx)

    // ---- "All audio to max unless the user lowered it" (user directive 2026-09-13) ----
    // Each knob has a Settings.Global row that ONLY the settings UIs write when the user makes an
    // explicit choice. No row = the enforcer pushes the maximum; a row = the user's own value is
    // what gets (re)applied. The rows live in Global so the player, HioneOS Settings and M500
    // Hardware Settings all agree.
    const val KEY_USER_GAIN = "hione_audio_user_gain"              // "high" | "low"
    const val KEY_USER_DRE = "hione_audio_user_dre"                // 1 | 0
    const val KEY_USER_HIGH_POWER = "hione_audio_user_high_power"  // 1 | 0

    /** Record an explicit user choice so [ensureBestAudio] applies it instead of the maximum. */
    fun rememberUserGain(ctx: Context, gain: String) =
        runCatching { Settings.Global.putString(ctx.contentResolver, KEY_USER_GAIN, gain.lowercase()) }
    fun rememberUserDre(ctx: Context, enabled: Boolean) =
        runCatching { Settings.Global.putInt(ctx.contentResolver, KEY_USER_DRE, if (enabled) 1 else 0) }
    fun rememberUserHighPower(ctx: Context, enabled: Boolean) =
        runCatching { Settings.Global.putInt(ctx.contentResolver, KEY_USER_HIGH_POWER, if (enabled) 1 else 0) }

    data class AudioPolicy(val gain: String, val dre: Boolean, val highPower: Boolean) {
        val isMax: Boolean get() = gain == "high" && dre && highPower
    }

    fun readAudioPolicy(ctx: Context): AudioPolicy {
        val cr = ctx.contentResolver
        val gain = runCatching { Settings.Global.getString(cr, KEY_USER_GAIN) }.getOrNull()
            ?.trim()?.lowercase()?.takeIf { it == "low" || it == "high" } ?: "high"
        val dre = runCatching { Settings.Global.getInt(cr, KEY_USER_DRE) }.getOrDefault(1) == 1
        val hp = runCatching { Settings.Global.getInt(cr, KEY_USER_HIGH_POWER) }.getOrDefault(1) == 1
        return AudioPolicy(gain, dre, hp)
    }

    /**
     * BEST AUDIO MODE, enforced: push the CS43198 pair to HIGH gain, DRE on (Dynamic Range
     * Enhancement = more effective resolution at low volume) and high-power output - unless the
     * user explicitly lowered one of them in a settings UI, in which case that choice is what
     * gets re-applied. Writes the Settings rows the HiBy framework/SystemUI read AND pushes the
     * parameters into the audio HAL (the only root-free way to actually move the hardware; the
     * old su/sysfs path silently no-opped). Idempotent and cheap; runs on every player start.
     */
    fun ensureBestAudio(ctx: Context) {
        val cr = ctx.contentResolver
        val p = readAudioPolicy(ctx)
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.hw.gain", p.gain) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.gain", p.gain) }
        pushToHal(ctx, "vendor.audio.hiby.hw.gain", p.gain)
        pushToHal(ctx, "vendor.audio.hiby.gain", p.gain)

        val dreStr = if (p.dre) "dremode_enable" else "dremode_disable"
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.dre", if (p.dre) 1 else 0) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.dre_mode", dreStr) }
        pushToHal(ctx, "vendor.audio.hiby.hw.dre", if (p.dre) "1" else "0")
        pushToHal(ctx, "vendor.audio.hiby.hw.dre_mode", dreStr)

        val hpStr = if (p.highPower) "hpower_enable" else "hpower_disable"
        runCatching { Settings.Global.putInt(cr, "vendor.audio.hiby.hw.high_power", if (p.highPower) 1 else 0) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.high_power", hpStr) }
        runCatching { Settings.Global.putString(cr, "vendor.audio.hiby.high_power_mode", hpStr) }
        pushToHal(ctx, "vendor.audio.hiby.hw.high_power", if (p.highPower) "1" else "0")
        pushToHal(ctx, "vendor.audio.hiby.hw.high_power_mode", hpStr)
        Log.i(TAG, "ensureBestAudio: gain=${p.gain} dre=${p.dre} highPower=${p.highPower} (max=${p.isMax})")

        // The rows and HAL params above never reached the DAC (hioneos/docs/hiby-audio-knobs.md).
        // This does: com.hione.sysbridge sets persist.vendor.audio.hione.*, which the image's
        // hione_audio.rc writes to the sysfs nodes. Blocking read-back, so off the main thread.
        val app = ctx.applicationContext
        com.hione.player.profiles.HibyDacBridge.init(app)
        Thread({
            val bridge = com.hione.player.profiles.HibyDacBridge
            val hpBefore = bridge.get(bridge.PROP_HIGH_POWER)
            bridge.set(bridge.PROP_GAIN, p.gain)
            bridge.set(bridge.PROP_DRE, dreStr)
            val hp = bridge.set(bridge.PROP_HIGH_POWER, hpStr)
            // First time high power really engages on this device: the same volume step is now
            // louder (external amp stage), so bring music down once and say why. Until 0.2.0 the
            // setting never reached the amp, so nobody has heard this level yet.
            if (p.highPower && hpBefore != "hpower_enable" &&
                hp == com.hione.player.profiles.HibyDacBridge.Result.CONFIRMED) {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val safe = (max * 0.3f).toInt().coerceAtLeast(1)
                    if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > safe) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, safe, 0)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(app,
                                "High power is on now, so the same volume is louder. Volume lowered to be safe.",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }, "HioneDacApply").start()
    }

    /** Read the HAL's live direct-output state back for truthful verification. */
    fun status(ctx: Context): DirectStatus {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return DirectStatus(false, "", isAllowListed(ctx))
        fun param(key: String): String = runCatching {
            // HAL returns "key=value"; strip to the value.
            am.getParameters("getprop=$key").substringAfter('=').trim()
        }.getOrDefault("")
        return DirectStatus(
            directFlagEnabled = param("vendor.audio.hiby.hw.diect_flags_enable") == "yes",
            holderProcess = param("vendor.audio.hiby.hw.diect_proess_name"),
            allowListed = isAllowListed(ctx)
        )
    }

    /**
     * Push a vendor.audio.hiby.* value straight into the audio HAL — the same live channel the
     * platform's own audio settings use. Complements CirrusLogicManager's sysfs/setprop/Global
     * writes so a change is heard immediately, not just after the next output reopen.
     */
    fun pushToHal(ctx: Context, key: String, value: String) {
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.setParameters("$key=$value")
        }.onFailure { Log.w(TAG, "pushToHal $key failed", it) }
    }
}
