package com.hione.sysbridge

import android.app.Application

/**
 * The bridge runs as a persistent app (see the manifest) so the charge limit and the CPU power profile can follow the
 * battery. Everything else here is still on-demand broadcast receivers.
 */
class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ChargeLimiter.start(this)
        PowerProfiles.start(this)
        // So hione_dac_state is there after boot, before anything changes a DAC setting.
        AudioReceiver.publishState(this)
        // Auto reboot after long locked, USB data off while locked, lockdown (see SecurityGuard).
        SecurityGuard.start(this)
        // HioneOS system sounds: charger, battery, USB DAC, headphones, lock, screenshot, boot, shutdown.
        SoundEvents.start(this)
        // Boot settings, grants and app-ops that hione_ime.rc could never apply (see BootSeeds).
        BootSeeds.applyAsync(this, "app start")
    }
}
