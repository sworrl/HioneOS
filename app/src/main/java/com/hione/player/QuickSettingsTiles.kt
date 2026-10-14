package com.hione.player

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class UsbDacTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = UsbDacManager.isActive(applicationContext)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "USB DAC: ON" else "USB DAC: OFF"
        tile.subtitle = if (active) "${UsbDacManager.getSampleRate(applicationContext) / 1000}kHz" else "MTP / ADB"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val current = UsbDacManager.isActive(applicationContext)
        val next = !current
        scope.launch {
            UsbDacManager.setUsbDacMode(applicationContext, next)
            updateTile()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.N)
class PocketLockTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val cr = contentResolver
        val mode = android.provider.Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock"
        val active = mode == "touch_and_key_lock"
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Fn Pocket Lock"
        tile.subtitle = if (active) "Touch & Keys Locked" else "Key Lock Only"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val cr = contentResolver
        val mode = android.provider.Settings.Global.getString(cr, "fn_settings") ?: "touch_and_key_lock"
        val next = if (mode == "touch_and_key_lock") "key_lock" else "touch_and_key_lock"
        try {
            android.provider.Settings.Global.putString(cr, "fn_settings", next)
        } catch (t: Throwable) {
            // Settings.Global writes require WRITE_SECURE_SETTINGS (platform signature). On a
            // non-privileged build this throws SecurityException — swallow it so the QS tile
            // never crashes; the tile simply stays on its current value.
            android.util.Log.w("PocketLockTile", "Cannot set fn_settings (not privileged)", t)
        }
        updateTile()
    }
}

