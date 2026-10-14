package com.hione.player

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Off-main-thread dispatch for widget re-renders. `Player.Listener` callbacks
 * (onIsPlayingChanged, onMediaMetadataChanged) fire on the main thread, and both
 * HioneMusicWidget.pushUpdate and HioneTapeWidget.pushUpdate do blocking ContentResolver I/O
 * (loadThumbnail) plus, for the tape widget, ~50 Canvas draw calls building a fresh 640×400
 * bitmap (MiniTapeRenderer) — real work that used to run synchronously on the main thread on
 * every single play/pause toggle or track change, a genuine dropped-frame risk.
 *
 * Single-thread executor: widget renders are cheap enough to serialize, and serializing avoids
 * two renders racing on the same underlying player/bitmap state. Coalesced via a pending flag —
 * a metadata change immediately followed by a play-state change (the common track-transition
 * case) collapses into one actual render pass instead of two, and any pushes that arrive while a
 * render is already in flight are dropped rather than queued (the in-flight render already
 * reflects the latest player state by the time it reads it).
 */
object WidgetUpdateExecutor {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "hione-widget-update").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)

    @Volatile private var lastRenderMs = 0L

    fun push(context: Context) {
        // Captured HERE, synchronously, on the calling thread — every real call site (Player.
        // Listener callbacks, BroadcastReceiver.onReceive) already runs on main, which is the only
        // thread Media3's Player contract allows reading these fields from. Only the snapshot
        // (plain data, safe from any thread) crosses over to the background executor below.
        val snapshot = PlayerHolder.snapshot()
        // Screen off (AUDIO_ONLY/IDLE): nobody sees the widget — no repaint faster than every 30 s.
        val now = android.os.SystemClock.elapsedRealtime()
        if (!HionePowerGovernor.allowBackgroundWork && now - lastRenderMs < HionePowerGovernor.WIDGET_MIN_INTERVAL_SAVING_MS) return
        lastRenderMs = now
        if (!pending.compareAndSet(false, true)) return // a render is already queued/running — it'll pick up the latest state
        val app = context.applicationContext
        executor.execute {
            runCatching { HioneMusicWidget.pushUpdate(app, snapshot) }
            runCatching { HioneTapeWidget.pushUpdate(app, snapshot) }
            pending.set(false)
        }
    }
}
