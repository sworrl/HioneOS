package com.hione.player

/**
 * Launch-crash forensics rig (2026-09-10 "Unsupported concurrent change during composition"
 * crash-loop). Two tools:
 *
 *  - [install]: a snapshot apply-observer. OPT-IN (`settings put global hione_dbg_snaplog 1`) so it
 *    costs exactly nothing in production. When on, every snapshot apply is logged with the FULL
 *    package-qualified class name + identity of each modified state object, the writing thread,
 *    whether that thread is the main thread, whether a Compose composition/recomposition is on the
 *    stack, and the com.hione frames that led there. Applies that are off-main or in-composition are
 *    logged at ERROR; everything else at DEBUG — so `logcat -s SnapApply:E` lists only the writers
 *    that can actually produce the crash. The crash itself is thrown by Recomposer.applyAndCheck at
 *    APPLY time, detached from the writer, which is why the stack never names the culprit; this
 *    observer is the only thing that does.
 *
 *  - [off]: Settings.Global-gated kill switches (`hione_dbg_off_<key> = 1`) so launch-time
 *    subsystems can be bisected live without rebuilding.
 *
 * Flags are read once per process - am force-stop after flipping.
 */
object HioneDbg {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Kill switch: true when `hione_dbg_off_<key>` is 1, i.e. the subsystem must stay dark. */
    fun off(ctx: android.content.Context, key: String): Boolean = cache.getOrPut("off_$key") {
        flag(ctx, "hione_dbg_off_$key")
    }

    /** Opt-in debug switch: true only when `hione_dbg_<key>` is 1. Default off. */
    fun on(ctx: android.content.Context, key: String): Boolean = cache.getOrPut("on_$key") {
        flag(ctx, "hione_dbg_$key")
    }

    private fun flag(ctx: android.content.Context, name: String): Boolean = try {
        android.provider.Settings.Global.getInt(ctx.contentResolver, name, 0) == 1
    } catch (_: Throwable) { false }

    private fun isMain(): Boolean = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    @Volatile private var installed = false
    // Retain the handle: registerApplyObserver returns an ObserverHandle that stops firing if
    // it is garbage-collected (the 2026-09-10 first attempt logged nothing because of this).
    @Suppress("unused") private var applyHandle: Any? = null

    fun install(ctx: android.content.Context) {
        if (installed) return
        installed = true
        if (!on(ctx, "snaplog")) return
        try {
            applyHandle = androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { set, _ ->
                if (set.isEmpty()) return@registerApplyObserver
                val th = Thread.currentThread()
                val onMain = isMain()
                val stack = th.stackTrace
                // Heuristic, and honest about being one: these are the runtime classes that are on
                // the stack while a composition / recomposition is actually running.
                val composing = stack.any { f ->
                    f.className.startsWith("androidx.compose.runtime.ComposerImpl") ||
                        f.className.startsWith("androidx.compose.runtime.CompositionImpl") ||
                        f.className.startsWith("androidx.compose.runtime.Recomposer") ||
                        f.methodName == "performRecompose" || f.methodName == "composeContent"
                }
                val objs = set.toList()
                val names = objs.take(8).joinToString(", ") { o ->
                    o.javaClass.name + "@" + Integer.toHexString(System.identityHashCode(o)) + detail(o)
                } + (if (objs.size > 8) " (+" + (objs.size - 8) + " more)" else "")
                val app = stack.asSequence()
                    .filter { it.className.startsWith("com.hione") }
                    .take(12)
                    .joinToString(" <- ") { it.className + "." + it.methodName + ":" + it.lineNumber }
                val suspect = !onMain || composing
                val head = "[" + th.name + (if (onMain) " main" else " OFF-MAIN") +
                    (if (composing) " IN-COMPOSITION" else "") + "] "
                val body = head + names +
                    (if (app.isNotEmpty()) "\n    via " + app else "\n    (no com.hione frames on the stack)")
                android.util.Log.println(
                    if (suspect) android.util.Log.ERROR else android.util.Log.DEBUG, "SnapApply", body
                )
            }
        } catch (_: Throwable) {}
    }

    // SnapshotStateMap / SnapshotStateList implement Map / List, so matching the plain interfaces
    // identifies them (and any other collection-shaped state object) without naming internals.
    private fun detail(o: Any): String = try {
        when (o) {
            is Map<*, *> -> "[map n=" + o.size + "]"
            is List<*> -> "[list n=" + o.size + "]"
            else -> ""
        }
    } catch (_: Throwable) { "" }

    /**
     * Belt-and-braces tripwire for code that MUST only write Compose-visible state on the main
     * thread. No-op unless `hione_dbg_snaplog` is on; then it logs an ERROR with a stack so the
     * offending call site names itself. Never throws.
     */
    fun expectMain(ctx: android.content.Context, what: String) {
        if (isMain()) return
        if (!on(ctx, "snaplog")) return
        android.util.Log.e("SnapApply", "OFF-MAIN state touch: " + what +
            " on " + Thread.currentThread().name, Throwable("expectMain"))
    }
}
