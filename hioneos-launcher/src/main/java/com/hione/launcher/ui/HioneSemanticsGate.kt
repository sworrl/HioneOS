package com.hione.launcher.ui

import com.hione.launcher.Mascot
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager

/**
 * Turns off Compose's accessibility semantics machinery when the ONLY accessibility service on the
 * device is HioneOS's own navigation service, which never reads content.
 *
 * WHY. On HioneOS an accessibility service is the navigation: the home pill, the back edges and
 * the shade pull all live in com.hione.systemui's AccessibilityService, so it is always enabled.
 * Compose decides whether to maintain its semantics tree with one test, "is any accessibility
 * service enabled", and does not care whether that service can or ever will read window content.
 * So every Compose screen in every app on the device rebuilt its semantics node map, computed
 * every node's window bounds and diffed the structure ON EVERY LAYOUT PASS, for a consumer that
 * does not exist. Profiled 2026-09-19 while scrolling the artist list: the accessibility delegate
 * (getCurrentSemanticsNodes, checkForSemanticsChanges, sendAccessibilitySemanticsStructureChangeEvents)
 * was 35 to 40 percent of the UI thread's time, ahead of measure, layout and draw combined.
 *
 * WHAT THIS DOES. Marks the content view IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, which
 * removes the whole subtree from the accessibility tree and lets the delegate skip it. It does
 * this ONLY when the nav service is the sole enabled service. The moment a real screen reader
 * (TalkBack, or anything not ours) is enabled, this leaves the view alone and the app is fully
 * accessible again. A blind user is never locked out to save frames; a sighted user on a device
 * with no screen reader is not paying for one.
 */
object HioneSemanticsGate {
    private const val NAV_SERVICE_PKG = "com.hione.systemui"

    /** True when every enabled accessibility service belongs to HioneOS's navigation package. */
    fun onlyHioneNavEnabled(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        if (!am.isEnabled) return false
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (services.isNullOrEmpty()) return false
        return services.all { it.resolveInfo?.serviceInfo?.packageName == NAV_SERVICE_PKG }
    }

    /**
     * Apply to an Activity after setContent. Re-evaluated on each call, so calling it from
     * onResume picks up a screen reader being turned on or off while the app was in the back.
     */
    fun apply(activity: Activity) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        val mode = if (onlyHioneNavEnabled(activity))
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        else
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (content.importantForAccessibility != mode) {
            content.importantForAccessibility = mode
            android.util.Log.i(
                "HioneSemanticsGate",
                if (mode == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                    "only the ${Mascot.osName} nav service is enabled; Compose semantics switched off for this window"
                else "a content-reading accessibility service is enabled; semantics left on"
            )
        }
    }
}
