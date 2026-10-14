package com.hione.launcher.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hione.launcher.HioneIngestConfig

/**
 * Index what the ingress relay just pushed, from outside the UI.
 *
 *   adb shell am broadcast -a com.hione.launcher.action.INGEST_SCAN -p com.hione.launcher
 *
 * This closes the loop on a sync. The host relay (`tools/ingress-relay/relay.py`) pushes files
 * over adb, which writes them straight to the card without MediaStore ever hearing about it, so
 * without this the device holds the music and shows none of it until something else happens to
 * trigger a scan. The relay fires this as its last step.
 *
 * Deliberately a separate action from REFRESH_LOCATION's pattern of doing the work inline: a scan
 * can take a while, and `triggerForceScan` already runs on the engine's own scope, so this returns
 * immediately rather than holding the broadcast open.
 */
class HioneIngestScanReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.hione.launcher.action.INGEST_SCAN"
        private const val TAG = "HioneIngestScanReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Nothing here may throw: an exported receiver that throws takes the launcher down with it.
        try {
            if (intent.action != ACTION) return
            val app = context.applicationContext
            if (intent.hasExtra("host")) {
                val h = intent.getStringExtra("host")
                if (!h.isNullOrBlank()) {
                    HioneIngestConfig.setSyncHost(app, h)
                    Log.i(TAG, "sync host set to $h via intent")
                }
            }
            if (!HioneIngestEngine.isEngineEnabled(app)) {
                // Say so rather than scanning anyway. The engine being off is a user choice, and a
                // relay push should not quietly override it.
                Log.i(TAG, "ingest scan requested but the ingest engine is OFF, ignoring")
                return
            }
            Log.i(TAG, "ingest scan requested by the relay")
            HioneIngestEngine.triggerForceScan(app)
        } catch (t: Throwable) {
            Log.w(TAG, "ingest scan failed: ${t.message}")
        }
    }
}
