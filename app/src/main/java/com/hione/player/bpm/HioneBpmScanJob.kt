package com.hione.player.bpm

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.util.Log
import com.hione.player.FastLibraryStore

/**
 * Background tempo analysis of the whole library, so a track already has its tempo the first time
 * it plays instead of waiting a few seconds for the decode.
 *
 * Runs only while the device is charging (JobScheduler), one file at a time on a background-
 * priority thread, and stops the moment the system asks. A whole-track decode plus TempoTrackV2
 * measured 8 to 15 s per four-minute file on the M500, so a library of ~17k tracks needs about
 * 60 hours on the charger in total, spread over as many sessions as it takes. A now-playing
 * analysis always goes first: it stops the job's current file, which is retried next run. Rows are keyed by path + size + mtime, so each file is done once until it changes.
 * Disc-image virtual tracks are skipped here and analysed per clip when they play.
 */
class HioneBpmScanJob : JobService() {

    @Volatile private var stop = false
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!HioneBeatNative.available) return false
        stop = false
        val ctx = applicationContext
        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var done = 0
            var finished = false
            try {
                val tracks = FastLibraryStore.peek() ?: FastLibraryStore.loadSync(ctx) ?: emptyList()
                val db = HioneTempoDb.get(ctx)
                for (t in tracks) {
                    if (stop) break
                    if (t.parentId != 0L || t.isDiscImage || t.path.isBlank()) continue
                    if (db.lookup(t.path) != null) continue
                    HioneTempoAnalyzer.resolve(ctx, t.path, shouldStop = { stop }, background = true)
                    done++
                }
                finished = !stop
            } catch (t: Throwable) {
                Log.w(TAG, "library tempo scan stopped", t)
            } finally {
                Log.i(TAG, "library tempo scan: $done files this run, ${if (finished) "library done" else "will resume"}")
                if (!stop) jobFinished(params, !finished)
            }
        }, "HioneBpmScan").apply { isDaemon = true; start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stop = true
        return true
    }

    companion object {
        private const val TAG = "HioneBpmScanJob"
        private const val JOB_ID = 0x4D42504D // "MBPM"

        /** Idempotent. Called on every playback start; JobScheduler keeps one pending job. */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, HioneBpmScanJob::class.java))
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(6 * 60 * 60 * 1000L)
                .build()
            runCatching { js.schedule(info) }.onFailure { Log.w(TAG, "could not schedule library tempo scan", it) }
        }
    }
}
