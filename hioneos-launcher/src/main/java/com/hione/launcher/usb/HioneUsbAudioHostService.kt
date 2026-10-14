package com.hione.launcher.usb

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * System-Wide Persistent USB Audio & Host Controller Service.
 * Manages UAC2 Direct ALSA bypass, MTP configurations, and host PC connection events.
 */
class HioneUsbAudioHostService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var usbReceiver: BroadcastReceiver? = null

    companion object {
        /** A plug-in must still be connected this long later to count; unplug bounces are shorter. */
        private const val SETTLE_MS = 1500L
        private val _usbConnectedEvents = MutableSharedFlow<Boolean>(replay = 0)
        val usbConnectedEvents: SharedFlow<Boolean> = _usbConnectedEvents.asSharedFlow()

        fun start(context: Context) {
            try {
                val intent = Intent(context, HioneUsbAudioHostService::class.java)
                context.startService(intent)
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerUsbReceiver()
    }

    /** Last USB data state this process saw, so only a real plug-in prompts. Null = not yet known. */
    @Volatile private var lastConnected: Boolean? = null
    private var pendingPrompt: kotlinx.coroutines.Job? = null

    /**
     * Prompt (or apply the saved mode) on a real plug-in, and only then.
     *
     * USB_STATE is a STICKY broadcast. Registering for it replays the last state at once, so
     * every time this process was restarted (often: the launcher is killed under memory pressure
     * and comes back) it looked like a fresh connection and the modal popped up out of nowhere.
     * It is also re-sent while connected whenever the USB functions change, including the
     * changes this modal makes itself, and the gadget bounces through connected/configured while
     * the cable is being pulled. So: the replayed sticky state only seeds [lastConnected]; a
     * prompt needs a disconnected-to-connected edge; and the connection must still be there
     * [SETTLE_MS] later, which is what filters out the unplug bounce.
     */
    private fun registerUsbReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != "android.hardware.usb.action.USB_STATE") return
                val ctx = context ?: return
                val connected = intent.getBooleanExtra("connected", false)
                val configured = intent.getBooleanExtra("configured", false)
                // Android Auto (and any other USB accessory host) puts the gadget into
                // accessory mode. Prompting here, or applying a saved mode like USB DAC, would
                // switch the USB functions out from under the car and end the session, so stay
                // out of the way entirely while an accessory is attached.
                if (intent.getBooleanExtra("accessory", false) ||
                    intent.getBooleanExtra("audio_source", false)) {
                    pendingPrompt?.cancel(); pendingPrompt = null
                    lastConnected = connected
                    return
                }
                val was = lastConnected
                lastConnected = connected
                if (isInitialStickyBroadcast) return
                if (!connected) { pendingPrompt?.cancel(); pendingPrompt = null; return }
                if (was == true || !configured || pendingPrompt?.isActive == true) return
                pendingPrompt = scope.launch {
                    kotlinx.coroutines.delay(SETTLE_MS)
                    if (lastConnected != true) return@launch
                    val savedMode = HioneUsbPreferences.getSavedMode(ctx)
                    if (HioneUsbPreferences.shouldShowPrompt(ctx)) {
                        _usbConnectedEvents.emit(true)
                    } else {
                        // Apply saved mode silently
                        when (savedMode) {
                            HioneUsbPreferences.MODE_DAC -> HioneUsbAudioHostManager.setUsbDacMode(ctx, true)
                            HioneUsbPreferences.MODE_MTP -> HioneUsbAudioHostManager.setMtpMode(ctx)
                            HioneUsbPreferences.MODE_CHARGE -> HioneUsbAudioHostManager.setChargeOnlyMode(ctx)
                        }
                    }
                }
            }
        }
        usbReceiver = receiver
        val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
        registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        usbReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        usbReceiver = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
