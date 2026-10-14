package com.hione.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Where the heavy library work runs, so it can never starve the UI thread again.
 *
 * WHAT WENT WRONG (2026-09-19, "takes 30 seconds to open, laggy as shit"). At launch the artist
 * grouping, album grouping, the MediaStore query, the disc-image pass and the art prewarm all ran
 * on Dispatchers.Default and Dispatchers.IO at NORMAL priority. That is four to six threads at the
 * same priority as the main thread on a Snapdragon 665 with four little cores that matter. The
 * main thread got roughly a sixth of the CPU, and a binary cache read that costs a couple of
 * hundred milliseconds unstarved took 7 to 10 seconds (measured: "Loaded 17048 tracks from binary
 * cache in 9985ms"). Everything the user touched during that window was frozen.
 *
 * THE FIX. Two threads, not four, one notch above THREAD_PRIORITY_BACKGROUND. The scheduler always
 * prefers main and RenderThread over these, so the app stays responsive while the library grinds
 * through in the background, which takes a little longer in wall-clock and is not noticed because
 * the screen is drawing. Same reasoning as AlbumArtCache.artDispatcher.
 *
 * NOT full THREAD_PRIORITY_BACKGROUND: that drops the thread into the background cgroup's
 * few-percent CPU share, and the library grouping is something the user is waiting on.
 */
object HioneBackground {
    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2) { r ->
        Thread({
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND + android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE
            )
            r.run()
        }, "hione-library").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
