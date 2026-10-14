package com.hione.player.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto projection service using the Car App Library (IOT category).
 * This runs purely as a projected UI (a remote control) rather than a system Media source,
 * which ensures the M500 DAP's local AUX/DAC audio routing remains perfectly intact 
 * instead of being forced over the A2DP/USB-Audio routing that the standard Media App
 * template incurs. 
 */
class HioneCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        return HioneCarSession()
    }
}
