package com.hione.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lets the album's colors bleed into the OS as accents. Publishes the SAME palette the app's own
 * chrome uses (HioneArtTheme — extracted once per track from the art thumb, no extra decode) to:
 *
 *   Settings.Global `hione_np_accent`     — vibrant/dominant color, contrast-adjusted for dark glass
 *                                          (ARGB int as a decimal string)
 *   Settings.Global `hione_np_accent2`    — muted secondary (same encoding)
 *   Settings.Global `hione_np_accent_ts`  — epoch ms of the last change, so consumers can animate
 *
 * plus the same two ints as `accent` / `accent2` extras on `com.hione.player.action.TRACK_CHANGED`
 * (HioneTrackHud). Both are cleared to 0 once playback has been stopped for > 30 s. Writes are
 * deduped — a re-published identical palette costs nothing.
 */
object HioneAccentPublisher {
    const val KEY_ACCENT = "hione_np_accent"
    const val KEY_ACCENT2 = "hione_np_accent2"
    const val KEY_TS = "hione_np_accent_ts"
    private const val CLEAR_AFTER_STOP_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastAccent = Int.MIN_VALUE
    @Volatile private var lastAccent2 = Int.MIN_VALUE
    @Volatile private var lastPalette: ArtPalette? = null
    private var clearRunnable: Runnable? = null

    /** The two ARGB ints for a palette — what the Globals and the broadcast extras carry. */
    fun accentsFor(p: ArtPalette): Pair<Int, Int> {
        val d = HioneArtTheme.derive(p)
        return Pair(d.accent.toArgb(), muted(d.accent2).toArgb())
    }

    /** Muted secondary: keep the hue, pull saturation/brightness down so it reads as a tint. */
    private fun muted(c: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        hsv[1] = (hsv[1] * 0.62f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 0.88f).coerceIn(0.28f, 1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** Called by HioneArtTheme whenever the palette changes (track change, or art arriving late). */
    fun onPalette(ctx: Context, p: ArtPalette) {
        lastPalette = p
        cancelClear()
        val (a1, a2) = accentsFor(p)
        write(ctx.applicationContext, a1, a2)
    }

    /** Play/pause/stop transitions from the player listener. */
    fun onPlaybackState(ctx: Context, isPlaying: Boolean) {
        val app = ctx.applicationContext
        if (isPlaying) {
            cancelClear()
            // Resuming after a clear → put the current palette back.
            if (lastAccent == 0 && lastAccent2 == 0) lastPalette?.let { onPalette(app, it) }
        } else {
            cancelClear()
            val r = Runnable {
                clearRunnable = null
                val still = runCatching { PlayerHolder.player?.isPlaying == true }.getOrDefault(false)
                if (!still) write(app, 0, 0)
            }
            clearRunnable = r
            main.postDelayed(r, CLEAR_AFTER_STOP_MS)
        }
    }

    private fun cancelClear() { clearRunnable?.let { main.removeCallbacks(it) }; clearRunnable = null }

    private fun write(app: Context, a1: Int, a2: Int) {
        if (a1 == lastAccent && a2 == lastAccent2) return
        lastAccent = a1; lastAccent2 = a2
        scope.launch {
            val cr = app.contentResolver
            val ok = runCatching {
                Settings.Global.putString(cr, KEY_ACCENT, a1.toString())
                Settings.Global.putString(cr, KEY_ACCENT2, a2.toString())
                Settings.Global.putString(cr, KEY_TS, System.currentTimeMillis().toString())
            }.getOrDefault(false)
            if (!ok) android.util.Log.w("HioneAccentPublisher", "accent publish refused (WRITE_SECURE_SETTINGS missing?)")
        }
    }
}
