package com.hione.launcher

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * The channel that actually moves this device's DAC, without root.
 *
 * WHY. [CirrusLogicManager]'s setters wrote `Settings.Global` rows and then handed the real work
 * to `RootShell.execFast("echo … > /sys/… ; setprop …")`. There is no `su` on HioneOS — the OS is
 * unrestricted by platform-signing instead — so every one of those shell lines was a no-op, while
 * the Settings row the UI reads back changed. The control screen therefore echoed each choice
 * straight back as if the hardware had taken it. That is the same defect class as the gain bug
 * found live on 2026-09-11 (`hw.gain=low` while the UI claimed otherwise, IEMs quiet), and the
 * player module fixed its own copy by routing through the audio HAL. The launcher never got that
 * fix, so its entire DAC surface was decorative.
 *
 * HOW. `AudioManager.setParameters` is the documented way to hand a key=value pair to the audio
 * HAL, needs no root and no privileged permission, and the HiBy HAL on this unit consumes the
 * `vendor.audio.hiby.*` keys through it. This is the one route confirmed to reach the DACs.
 *
 * WHAT IT CANNOT DO. It cannot write a sysfs node the HAL does not expose as a parameter. Where a
 * setting has no HAL key, the honest outcome is that it does not apply, and the nullable readers
 * in [CirrusLogicManager] will report null rather than our own last write. Do not add a `su`
 * fallback to paper over that: see m500-no-su-platform-signed-directive.
 *
 * CORRECTION (2026-10-10): the HiBy HAL and PAL on v1.00 contain no vendor.audio.hiby keys, so
 * these pushes do not reach the DAC either (hioneos/docs/hiby-audio-knobs.md, section 5). Filter,
 * gain, DRE and high power now go through com.hione.sysbridge, see [DacBridge]. The balance and
 * DSD pushes that still use this are not real knobs on the M500.
 */
object HioneHalAudio {

    private const val TAG = "HioneHalAudio"

    /** Hand one key=value pair to the audio HAL. Silent no-op if the service is unavailable. */
    fun push(ctx: Context, key: String, value: String) {
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.setParameters("$key=$value")
        }.onFailure { Log.w(TAG, "push $key=$value failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    fun push(ctx: Context, key: String, value: Int) = push(ctx, key, value.toString())

    /**
     * Read a system property, no root required.
     *
     * Replaces `RootShell.execOut("getprop …")` in the hardware audit: `getprop` never needed root
     * in the first place, so routing it through a `su` shell turned a readable value into "N/A".
     */
    fun sysProp(key: String): String? = try {
        @Suppress("PrivateApi")
        (Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String)
            ?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }
}
