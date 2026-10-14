package com.hione.systemui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.util.Log

/**
 * HioneOS task-stack access for quick-switch + the Hione Recents overview.
 *
 * com.hione.systemui is signed with the platform key, so it is exempt from hidden-API
 * restrictions (ApplicationInfo.isAllowedToUseHiddenApis()) and signature-level permissions
 * (REAL_GET_TASKS / MANAGE_ACTIVITY_TASKS / START_TASKS_FROM_RECENTS / REMOVE_TASKS /
 * READ_FRAME_BUFFER) are granted just by declaring them. Everything hidden is reached by
 * reflection with graceful fallbacks, and every call is wrapped so a framework difference can
 * never crash the navigation service.
 */
object HioneTaskStack {
    private const val TAG = "HioneTaskStack"

    data class Entry(
        val taskId: Int,
        val pkg: String,
        val component: ComponentName?,
        val label: CharSequence,
        val icon: Drawable?,
        val isRunning: Boolean
    )

    /** Activities that live in the task stack but must never be "switched to" or listed. */
    private val hiddenClassFragments = listOf(
        "Lockscreen", "HioneAod", "AodActivity", "FallbackHome", "HioneRecentsActivity",
        "HionePowerMenuActivity", "HioneShadeActivity", "AlarmRing"
    )

    private val atmService: Any? by lazy {
        runCatching {
            Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        }.onFailure { Log.w(TAG, "ActivityTaskManager.getService() unavailable: $it") }.getOrNull()
    }

    private val atmInstance: Any? by lazy {
        runCatching {
            Class.forName("android.app.ActivityTaskManager").getMethod("getInstance").invoke(null)
        }.onFailure { Log.w(TAG, "ActivityTaskManager.getInstance() unavailable: $it") }.getOrNull()
    }

    private fun taskIdOf(info: ActivityManager.RecentTaskInfo): Int =
        try { info.taskId } catch (_: Throwable) { @Suppress("DEPRECATION") info.id }

    private fun taskIdOf(info: ActivityManager.RunningTaskInfo): Int =
        try { info.taskId } catch (_: Throwable) { @Suppress("DEPRECATION") info.id }

    private fun isHomeIntent(intent: Intent?): Boolean =
        intent?.categories?.contains(Intent.CATEGORY_HOME) == true

    private fun isHiddenComponent(cn: ComponentName?): Boolean {
        val cls = cn?.className ?: return false
        return hiddenClassFragments.any { cls.contains(it) }
    }

    /**
     * Most-recent-first list of user-visible app tasks: our own package, home tasks, the
     * launcher's lockscreen/AOD tasks and excluded-from-recents tasks are filtered out.
     */
    fun recents(ctx: Context, max: Int = 12): List<Entry> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val pm = ctx.packageManager
        val raw = try {
            @Suppress("DEPRECATION")
            am.getRecentTasks(max + 6, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
        } catch (t: Throwable) {
            Log.w(TAG, "getRecentTasks failed: $t"); return emptyList()
        }
        val out = ArrayList<Entry>()
        for (info in raw) {
            val cn = info.topActivity ?: info.baseActivity ?: info.baseIntent?.component ?: continue
            val pkg = cn.packageName
            if (pkg == ctx.packageName) continue
            if (pkg == "com.hione.launcher") continue          // the home app never shows in overview
            if (isHomeIntent(info.baseIntent)) continue
            if (isHiddenComponent(cn) || isHiddenComponent(info.baseActivity)) continue
            if (out.any { it.pkg == pkg && it.component?.className == cn.className }) continue
            val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            val label = appInfo?.let { runCatching { pm.getApplicationLabel(it) }.getOrNull() } ?: pkg
            val icon = appInfo?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
            val running = try { info.isRunning } catch (_: Throwable) { true }
            out += Entry(taskIdOf(info), pkg, cn, label, icon, running)
            if (out.size >= max) break
        }
        return out
    }

    /** The task currently on top (may be the home task). */
    fun topTask(ctx: Context): Pair<Int, ComponentName?>? {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return try {
            @Suppress("DEPRECATION")
            val t = am.getRunningTasks(1).firstOrNull() ?: return null
            Pair(taskIdOf(t), t.topActivity ?: t.baseActivity)
        } catch (t: Throwable) {
            Log.w(TAG, "getRunningTasks failed: $t"); null
        }
    }

    fun isHomeOnTop(ctx: Context): Boolean {
        val top = topTask(ctx)?.second ?: return false
        return top.packageName == "com.hione.launcher" && !isHiddenComponent(top)
    }

    /**
     * Bring [taskId] to the front. Order: IActivityTaskManager.startActivityFromRecents (the
     * real Recents path — no background-activity-launch gating), then
     * ActivityManager.moveTaskToFront. Returns true if either reported success.
     */
    fun switchTo(ctx: Context, taskId: Int, options: Bundle? = null): Boolean {
        atmService?.let { svc ->
            try {
                val m = svc.javaClass.getMethod(
                    "startActivityFromRecents", Int::class.javaPrimitiveType, Bundle::class.java
                )
                val r = m.invoke(svc, taskId, options) as? Int ?: -1
                // ActivityManager.START_SUCCESS = 0, START_TASK_TO_FRONT = 2 (any >= 0 is success)
                if (r >= 0) return true
                Log.w(TAG, "startActivityFromRecents($taskId) -> $r")
            } catch (t: Throwable) {
                Log.w(TAG, "startActivityFromRecents reflection failed: $t")
            }
        }
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION, options)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "moveTaskToFront($taskId) failed: $t"); false
        }
    }

    /** Remove (kill) a task via IActivityTaskManager.removeTask. */
    fun remove(taskId: Int): Boolean {
        val svc = atmService ?: return false
        return try {
            val m = svc.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType)
            (m.invoke(svc, taskId) as? Boolean) ?: true
        } catch (t: Throwable) {
            Log.w(TAG, "removeTask($taskId) failed: $t"); false
        }
    }

    /**
     * Task snapshot as a software ARGB bitmap, or null. Uses
     * ActivityTaskManager.getTaskSnapshot(taskId, isLowResolution[, takeSnapshotIfNeeded])
     * (READ_FRAME_BUFFER) and TaskSnapshot.getHardwareBuffer()/getColorSpace().
     */
    fun snapshot(taskId: Int, lowRes: Boolean = true, takeIfNeeded: Boolean = false): Bitmap? {
        // Android 14: only IActivityTaskManager carries getTaskSnapshot(int, boolean, boolean).
        // Older builds had ActivityTaskManager.getTaskSnapshot(int, boolean[, boolean]).
        val snap: Any = try {
            var r: Any? = null
            atmService?.let { svc ->
                val m = svc.javaClass.methods.firstOrNull { it.name == "getTaskSnapshot" }
                r = when (m?.parameterTypes?.size) {
                    3 -> m.invoke(svc, taskId, lowRes, takeIfNeeded)
                    2 -> m.invoke(svc, taskId, lowRes)
                    else -> null
                }
            }
            if (r == null) atmInstance?.let { inst ->
                val m = inst.javaClass.methods.firstOrNull { it.name == "getTaskSnapshot" }
                r = when (m?.parameterTypes?.size) {
                    3 -> m.invoke(inst, taskId, lowRes, takeIfNeeded)
                    2 -> m.invoke(inst, taskId, lowRes)
                    else -> null
                }
            }
            if (r == null) Log.w(TAG, "getTaskSnapshot($taskId) returned null (no cached snapshot?)")
            r ?: return null
        } catch (t: Throwable) {
            Log.w(TAG, "getTaskSnapshot($taskId) failed: $t"); return null
        }
        return try {
            val hb = snap.javaClass.getMethod("getHardwareBuffer").invoke(snap) as? HardwareBuffer ?: return null
            val cs = runCatching {
                snap.javaClass.getMethod("getColorSpace").invoke(snap) as? android.graphics.ColorSpace
            }.getOrNull()
            val hw = Bitmap.wrapHardwareBuffer(hb, cs) ?: return null
            val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            sw
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot bitmap conversion failed: $t"); null
        }
    }
}
