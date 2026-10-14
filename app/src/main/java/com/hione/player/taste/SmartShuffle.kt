package com.hione.player.taste

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import com.hione.player.FastLibraryStore
import com.hione.player.PlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.math.ln

/**
 * Smart shuffle — OFF by default. When on, ExoPlayer's shuffle order (the thing the normal
 * shuffle toggle turns on everywhere: NowPlaying, tape deck, mini bar, media session) is
 * replaced with a weighted one: tracks with higher affinity and a better time-of-day fit tend to
 * come sooner, unheard tracks keep a small floor so shuffle still surprises, and a greedy pass
 * keeps the same artist from clustering. It only ever swaps the ORDER via
 * ExoPlayer.setShuffleOrder — the queue's contents, the shuffle flag, repeat, and every
 * existing toggle keep working exactly as before; turning this off falls back to the stock
 * random order.
 */
object SmartShuffle {
    private const val PREF = "smart_shuffle_enabled"
    private const val DEBOUNCE_MS = 700L

    var enabled by mutableStateOf(false); private set
    @Volatile private var loaded = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var appliedSignature = 0L
    @Volatile private var pending: Runnable? = null

    fun isEnabled(ctx: Context): Boolean {
        if (!loaded) { enabled = ctx.getSharedPreferences("hione_taste", Context.MODE_PRIVATE).getBoolean(PREF, false); loaded = true }
        return enabled
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on; loaded = true
        ctx.getSharedPreferences("hione_taste", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        val p = PlayerHolder.player ?: return
        if (!p.shuffleModeEnabled) return
        appliedSignature = 0L
        if (on) schedule(ctx.applicationContext, p)
        else runCatching { p.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(p.mediaItemCount)) }
    }

    fun onShuffleChanged(app: Context, player: ExoPlayer, shuffleOn: Boolean) {
        if (!shuffleOn) { appliedSignature = 0L; return }
        if (!isEnabled(app)) return
        appliedSignature = 0L   // a fresh toggle deserves a fresh order
        schedule(app, player)
    }

    fun onTimelineChanged(app: Context, player: ExoPlayer) {
        if (!player.shuffleModeEnabled || !isEnabled(app)) return
        schedule(app, player)
    }

    private fun signature(player: ExoPlayer): Long {
        var h = player.mediaItemCount.toLong() * 1_000_003L
        val n = player.mediaItemCount
        for (i in 0 until n) if (i < 8 || i >= n - 8) h = h * 31 + (player.getMediaItemAt(i).mediaId.hashCode())
        return h
    }

    private fun schedule(app: Context, player: ExoPlayer) {
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable { pending = null; apply(app, player) }
        pending = r
        main.postDelayed(r, DEBOUNCE_MS)
    }

    /** Main thread: snapshot the queue, compute the order off-thread, apply if still current. */
    private fun apply(app: Context, player: ExoPlayer) {
        val n = player.mediaItemCount
        if (n < 3 || !player.shuffleModeEnabled) return
        val sig = signature(player)
        if (sig == appliedSignature) return
        val ids = LongArray(n) { player.getMediaItemAt(it).mediaId.toLongOrNull() ?: -1L }
        val current = player.currentMediaItemIndex.coerceIn(0, n - 1)
        scope.launch {
            val order = runCatching { computeOrder(app, ids, current) }.getOrNull() ?: return@launch
            main.post {
                val p = PlayerHolder.player ?: return@post
                if (p !== player || p.mediaItemCount != n || !p.shuffleModeEnabled) return@post
                if (signature(p) != sig) return@post
                runCatching {
                    p.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
                    appliedSignature = signature(p)
                }
            }
        }
    }

    /** Weighted order: affinity + time fit + a floor for unheard tracks, artist anti-clustering,
     *  current item first so playback continues seamlessly. */
    private fun computeOrder(app: Context, ids: LongArray, current: Int): IntArray {
        val lib = FastLibraryStore.loadSync(app)
        val snap = TasteModel.peek() ?: (if (lib != null && lib.isNotEmpty()) TasteModel.build(app, lib) else null)
        val slot = TasteDb.currentSlot()
        val rnd = Random()
        val n = ids.size
        data class Key(val idx: Int, val key: Double, val artist: String)
        val keys = ArrayList<Key>(n)
        for (i in 0 until n) {
            if (i == current) continue
            val sc = snap?.scored(ids[i])
            val aff = sc?.affinity ?: 0f
            val tf = if (sc != null && snap != null) snap.timeFit(sc.f, slot) else 0f
            val unheard = if (sc == null || !sc.f.everPlayed) 0.12f else 0f
            val w = 0.25f + aff + 0.5f * tf + unheard
            val u = rnd.nextDouble().coerceAtLeast(1e-9)
            keys.add(Key(i, -ln(u) / w, sc?.f?.artistKey ?: ""))
        }
        keys.sortBy { it.key }
        // Greedy anti-clustering: never the same artist within the last two placed positions if
        // any other candidate can go there instead.
        val out = IntArray(n)
        out[0] = current
        var pos = 1
        val recent = ArrayDeque<String>()
        snap?.scored(ids[current])?.f?.artistKey?.let { recent.addLast(it) }
        val deferred = ArrayList<Key>()
        fun place(k: Key) {
            out[pos++] = k.idx
            if (k.artist.isNotBlank()) { recent.addLast(k.artist); while (recent.size > 2) recent.removeFirst() } else if (recent.isNotEmpty()) recent.removeFirst()
        }
        for (k in keys) {
            if (k.artist.isNotBlank() && k.artist in recent) { deferred.add(k); continue }
            place(k)
            val it = deferred.iterator()
            while (it.hasNext()) { val d = it.next(); if (d.artist !in recent) { place(d); it.remove() } }
        }
        for (d in deferred) place(d)
        return out
    }
}
