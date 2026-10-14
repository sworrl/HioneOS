package com.hione.settings.route

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.hione.settings.HioneSettingsActivity
import com.hione.settings.pages.HionePageActivity

/**
 * Invisible trampoline that receives every mirrored stock Settings intent and sends it on.
 *
 * FLAG_ACTIVITY_FORWARD_RESULT hands the caller's result slot to whatever we start, so an app
 * that used startActivityForResult still gets its answer from the real page. The router never
 * shows UI itself and finishes in onCreate (Theme.NoDisplay requires that).
 *
 * Permission-protected stock entries arrive through activity-aliases that carry the same
 * android:permission as the stock activity. HioneSettings holds those permissions itself, so
 * without the alias any app could reach a protected stock page by going through us.
 */
class SettingsRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent ?: Intent()
        try {
            route(this, src)
        } catch (t: Throwable) {
            Log.e(TAG, "routing ${src.action} failed", t)
        }
        finish()
    }

    companion object {
        private const val TAG = "HioneSettingsRouter"

        fun route(activity: Activity, src: Intent) {
            val dest = Routes.resolve(src)
            Log.i(TAG, "${src.action} -> $dest")
            when (dest) {
                is Dest.Page -> activity.startActivity(HionePageActivity.intent(activity, dest.route, src).forward())
                is Dest.Section -> activity.startActivity(
                    Intent(activity, HioneSettingsActivity::class.java).apply {
                        if (dest.section != null) putExtra("section", dest.section)
                    }.forward()
                )
                is Dest.External -> {
                    if (!tryStart(activity, dest.intent.forward())) openStockOrExplain(activity, src, "No app on this device handles this.")
                }
                is Dest.Stock -> openStockOrExplain(activity, src, dest.why)
            }
        }

        /** Interim deep link to the stock page; explain in HioneOS UI if stock Settings is gone. */
        fun openStockOrExplain(activity: Activity, src: Intent, why: String) {
            val cls = Routes.stockClassFor(src)
            if (cls != null && openStock(activity, src, cls)) return
            activity.startActivity(
                HionePageActivity.intent(activity, "unavailable", src).putExtra(HionePageActivity.EXTRA_WHY, why).forward()
            )
        }

        /** Copy of [src] aimed at the stock component. False when it is missing or refuses us. */
        fun openStock(activity: Activity, src: Intent, cls: String): Boolean {
            // Intent(src) keeps the caller's extras, data and flags; only the target changes.
            val i = Intent(src).apply { component = ComponentName(StockMap.PKG, cls) }.forward()
            return tryStart(activity, i)
        }

        fun stockAvailable(ctx: Context, cls: String): Boolean = try {
            val info = ctx.packageManager.getActivityInfo(ComponentName(StockMap.PKG, cls), 0)
            info.enabled && info.applicationInfo.enabled
        } catch (_: Throwable) { false }

        private fun tryStart(activity: Activity, i: Intent): Boolean = try {
            activity.startActivity(i); true
        } catch (t: Throwable) {
            Log.w(TAG, "start ${i.component ?: i.action} failed: ${t.message}"); false
        }

        private fun Intent.forward(): Intent = addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
    }
}
