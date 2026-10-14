package com.hione.update.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hione.update.ota.OtaEngine
import com.hione.update.ota.Prefs
import java.util.concurrent.TimeUnit

/** The scheduled check. Constraints come from the user's settings (Wi-Fi and charging by default). */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        if (OtaEngine.runScheduled(applicationContext)) Result.success() else Result.retry()

    companion object {
        private const val NAME = "hione-update-periodic"

        /** Call after any settings change. UPDATE keeps the schedule's phase when nothing changed. */
        fun schedule(ctx: Context) {
            val prefs = Prefs(ctx)
            val wm = WorkManager.getInstance(ctx)
            if (!prefs.scheduleEnabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(prefs.chargingOnly)
                .setRequiresStorageNotLow(true)
                .build()
            val hours = prefs.intervalHours.toLong()
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS, (hours / 4).coerceAtLeast(1), TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
