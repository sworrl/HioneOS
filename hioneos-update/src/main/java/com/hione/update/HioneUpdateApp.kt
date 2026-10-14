package com.hione.update

import android.app.Application
import com.hione.update.ota.Notifier
import com.hione.update.work.UpdateWorker

class HioneUpdateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        // WorkManager keeps periodic work across reboots itself; this only (re)applies settings.
        runCatching { UpdateWorker.schedule(this) }
    }
}
