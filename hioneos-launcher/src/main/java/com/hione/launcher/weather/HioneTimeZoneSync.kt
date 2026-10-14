package com.hione.launcher.weather

import android.app.AlarmManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.TimeZone

/**
 * Keeps the system time zone following the device around the country.
 *
 * WHY THIS EXISTS. The M500's clock is correct in absolute terms: `auto_time` is on and NTP keeps
 * UTC honest. What does not follow is the ZONE, so after a flight the device shows the right
 * instant rendered in the wrong place, and every clock, alarm and "2 min ago" in the OS is off by
 * however many hours you travelled.
 *
 * Android's own `auto_time_zone` is already on and does not help here, because the mechanism behind
 * it is NITZ, which arrives over the carrier signalling channel. This device runs a data-only SIM
 * whose PS registration is frequently DENIED (rejectCause 7), and no registration means no NITZ,
 * ever. Waiting for the carrier to tell us the zone is waiting for something that is not coming.
 *
 * WHAT IT USES INSTEAD. The launcher already resolves a position for the weather tile and already
 * asks Open-Meteo with `timezone=auto`, and Open-Meteo already answers with the IANA zone for those
 * coordinates. `HioneWeatherTileSources` has been parsing it into `WxSnapshot.tzId` the whole time
 * and nothing ever did anything with it. This applies it. No new network call, no new permission
 * beyond SET_TIME_ZONE, no extra battery.
 *
 * THE OBVIOUS CAVEAT. This is only ever as good as the position, and the position on this device
 * comes from Wi-Fi scanning, which has a known failure mode: access points that TRAVEL WITH YOU
 * (a hotspot, a Starlink terminal, your own gear in the car) are still mapped at the place they
 * were surveyed, so the fix can lag reality by a whole country. `HioneWifiLocator` excludes those,
 * but it cannot be perfect. Two guards follow from that:
 *
 *  1. Never move the zone on a position the weather engine did not trust enough to render.
 *  2. Leave the user a switch. `hione_auto_timezone` in Settings.Global, default on, 0 pins the
 *     zone. Anyone who has been bitten by a wrong guess can turn it off and set it by hand.
 *
 * It also stands down when the user has turned Android's own `auto_time_zone` off, because that is
 * them saying they want to control the clock, and it would be rude to override it from a weather
 * refresh.
 */
object HioneTimeZoneSync {

    private const val TAG = "HioneTimeZoneSync"

    /** Settings.Global switch, default ON. Set to 0 to pin the zone and stop this running. */
    const val KEY_AUTO_TZ = "hione_auto_timezone"

    /** Last zone we set, so a user's manual change is not immediately stomped on the next refresh. */
    @Volatile private var lastApplied: String? = null

    fun enabled(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, KEY_AUTO_TZ, 1) == 1
    } catch (_: Throwable) { true }

    fun setEnabled(ctx: Context, on: Boolean) {
        runCatching { Settings.Global.putInt(ctx.contentResolver, KEY_AUTO_TZ, if (on) 1 else 0) }
    }

    /**
     * Apply an IANA zone id (e.g. "America/Denver") if it is real, different, and allowed.
     *
     * @param ianaId the zone from Open-Meteo's `timezone` field, or null when the fetch had none.
     * @param placeLabel only used to make the log line legible.
     * @return true when the system zone was actually changed.
     */
    fun apply(ctx: Context, ianaId: String?, placeLabel: String? = null): Boolean {
        val id = ianaId?.trim().orEmpty()
        if (id.isEmpty()) return false

        if (!enabled(ctx)) {
            Log.d(TAG, "skipped: $KEY_AUTO_TZ is 0, the zone is pinned by the user")
            return false
        }
        // Respect the platform switch. If the user turned automatic time zone off, they want the
        // clock left alone, and a weather refresh is not the place to argue.
        val androidAuto = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1) == 1
        } catch (_: Throwable) { true }
        if (!androidAuto) {
            Log.d(TAG, "skipped: Settings.Global.AUTO_TIME_ZONE is 0")
            return false
        }

        // TimeZone.getTimeZone() answers GMT for anything it does not recognise, which would
        // silently park the device in UTC on a typo or an unexpected API response. Check the id is
        // one the platform actually knows before handing it to AlarmManager.
        if (id !in TimeZone.getAvailableIDs()) {
            Log.w(TAG, "refusing unknown zone id '$id'")
            return false
        }

        val current = TimeZone.getDefault().id
        if (current == id) return false

        // If the current zone is neither what we last set nor the one we are about to set, the user
        // (or NITZ, which is authoritative when it does show up) moved it by hand since our last
        // pass. Take it once, so the next refresh treats it as the new baseline instead of fighting.
        val prior = lastApplied
        if (prior != null && current != prior) {
            Log.i(TAG, "zone changed underneath us ($prior -> $current); adopting it, not overriding")
            lastApplied = current
            return false
        }

        return try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setTimeZone(id)
            lastApplied = id
            val where = placeLabel?.takeIf { it.isNotBlank() }?.let { " for $it" } ?: ""
            Log.i(TAG, "system time zone $current -> $id$where")
            true
        } catch (t: Throwable) {
            // SET_TIME_ZONE is signature|privileged. A platform-signed build holds it; a debug
            // build installed over the top of a different key does not, and that is worth seeing
            // rather than swallowing.
            Log.w(TAG, "could not set time zone to $id: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }
}
