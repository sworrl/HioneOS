package com.hione.player

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer

/**
 * Forces the shared ExoPlayer's output to the M500's built-in **ANALOG** wired jack (3.5mm / 4.4mm)
 * whenever Android Auto / car mode is active.
 *
 * WHY THIS EXISTS: in the car the M500 is the Android Auto *source* device. USB carries the
 * projected VIDEO signal to the head unit, so the audio MUST leave the M500 via its wired analog
 * headphone port — never the USB-DAC route (that would push audio into the video link, or nowhere).
 * We enumerate the real output devices, prefer the wired-analog family, and *explicitly avoid* every
 * USB output type. Best-effort with verbose logging so the exact on-device [AudioDeviceInfo.type]
 * values can be read straight from logcat and this heuristic tuned if the HiBy jack ever reports an
 * unusual type (see [routeToAnalog]'s per-device dump).
 *
 * Route selection is applied via [ExoPlayer.setPreferredAudioDevice]; the existing player/session is
 * reused (see [PlayerHolder]) — this never creates a second player.
 */
object HioneCarAudioRouter {
    private const val TAG = "HioneCarAudio"

    // Android Auto phone-projection host + related car media controllers. Matched against the
    // connecting controller's package name. Kept to known packages (+ a couple of stable substrings)
    // rather than a loose "contains car" so unrelated apps never trip car routing.
    private val CAR_PACKAGES = setOf(
        "com.google.android.projection.gearhead", // Android Auto (phone projection host)
        "com.google.android.autosimulator",       // Desktop Head Unit / AA simulator
        "com.google.android.carassistant",        // Assistant Driving Mode
        "com.google.android.gms",                 // some AA connection flows arrive via GMS
        "com.android.car.media"                   // Android Automotive OS system browser
        // NOTE: com.android.bluetooth was REMOVED here. It is the controller package for ANY
        // Bluetooth audio (headphones, speakers) via AVRCP, not just an in-car head unit — so
        // including it made every BT connection latch car-mode and force-route audio to the
        // analog jack, yanking it off the BT device ("Bluetooth audio disconnected") and leaving
        // the player spinning at ~300% CPU on a dead sink (starves the whole system incl. a11y
        // nav). Real Android Auto is still caught by gearhead/projection/automotive + UI_MODE_CAR.
    )

    // Latches true once we've seen a car controller so later re-assertions (e.g. right before
    // playback) still route correctly even if the player was recreated after the initial connect.
    @Volatile private var carControllerSeen = false

    fun isCarPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg in CAR_PACKAGES) return true
        return pkg.contains("projection") || pkg.contains("gearhead") || pkg.contains("automotive")
    }

    fun isCarUiMode(context: Context): Boolean = try {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        ui?.currentModeType == Configuration.UI_MODE_TYPE_CAR
    } catch (t: Throwable) {
        false
    }

    @Volatile private var isCallbackRegistered = false

    fun init(context: Context) {
        if (isCallbackRegistered) return
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            am.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    Log.i(TAG, "Audio devices added: ${addedDevices?.joinToString { typeName(it.type) }}")
                    ensureAnalogIfCar(context.applicationContext, "devices-added")
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    Log.i(TAG, "Audio devices removed: ${removedDevices?.joinToString { typeName(it.type) }}")
                    ensureAnalogIfCar(context.applicationContext, "devices-removed")
                }
            }, Handler(Looper.getMainLooper()))
            isCallbackRegistered = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register AudioDeviceCallback", t)
        }
    }

    /** Call when a controller connects to the session. Applies analog routing if it's a car
     *  controller (or the device itself is in car UI mode). */
    fun onControllerConnected(context: Context, pkg: String?) {
        init(context)
        val car = isCarPackage(pkg) || isCarUiMode(context)
        Log.i(TAG, "controller connected: pkg=$pkg -> car=$car")
        if (car) {
            carControllerSeen = true
            routeToAnalog(context, "controller-connect")
        }
    }

    /** Re-assert analog routing right before playback if a car controller has been seen (or the
     *  device is in car UI mode). Cheap; guards against player recreation between connect and play. */
    fun ensureAnalogIfCar(context: Context, reason: String) {
        val allowUsb = PlayerPreferences.loadAllowUsbAudio(context)
        if (allowUsb) {
            Log.d(TAG, "ensureAnalogIfCar($reason): USB Audio explicitly permitted by user")
            return
        }
        if (carControllerSeen || isCarUiMode(context)) {
            routeToAnalog(context, reason)
        }
    }

    private val USB_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY
    )

    // The wired analog jack family, in preference order. The HiBy 3.5mm/4.4mm output normally
    // reports as TYPE_WIRED_HEADPHONES; LINE_ANALOG / AUX_LINE cover line-out variants.
    private val ANALOG_PREFERENCE = listOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_AUX_LINE
    )

    // Fallback avoids: never fall back onto USB (the whole point), Bluetooth, the built-in speaker/
    // earpiece, HDMI or telephony — leaving only a genuine external wired/analog sink.
    private val FALLBACK_AVOID = USB_TYPES + setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_TELEPHONY
    )

    fun routeToAnalog(context: Context, reason: String) {
        val allowUsb = PlayerPreferences.loadAllowUsbAudio(context)
        if (allowUsb) {
            Log.i(TAG, "routeToAnalog($reason): Skipping analog enforcement because allowUsbAudio is TRUE")
            return
        }

        val player: ExoPlayer = PlayerHolder.player ?: run {
            Log.w(TAG, "routeToAnalog($reason): no player yet; skipping")
            return
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: run {
            Log.w(TAG, "routeToAnalog($reason): no AudioManager; skipping")
            return
        }
        val devices = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (t: Throwable) {
            Log.e(TAG, "routeToAnalog($reason): getDevices failed", t)
            return
        }
        // Dump EVERY output device so on-device tuning is a single logcat read away.
        for (d in devices) {
            Log.i(
                TAG,
                "output device: type=${d.type} ${typeName(d.type)} id=${d.id} " +
                    "product='${d.productName}' sink=${d.isSink}"
            )
        }
        val analog = pickAnalog(devices)
        if (analog != null) {
            runOnPlayerThread(player) {
                try {
                    player.setPreferredAudioDevice(analog)
                    Log.i(
                        TAG,
                        "routeToAnalog($reason): PREFERRED -> ${typeName(analog.type)} id=${analog.id} " +
                            "product='${analog.productName}' (USB Audio Blocked · High-Res AUX Active)"
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "routeToAnalog($reason): setPreferredAudioDevice failed", t)
                }
            }
        } else {
            Log.w(
                TAG,
                "routeToAnalog($reason): no analog/wired non-USB output found; leaving system " +
                    "default route (fallback). Seen output types: " +
                    devices.joinToString { typeName(it.type) }
            )
        }
    }

    private fun pickAnalog(devices: Array<AudioDeviceInfo>): AudioDeviceInfo? {
        val outputs = devices.filter { it.isSink }
        // 1. Exact wired-analog types, in preference order.
        for (t in ANALOG_PREFERENCE) {
            outputs.firstOrNull { it.type == t }?.let { return it }
        }
        // 2. Non-USB wired output
        return outputs.firstOrNull { it.type !in FALLBACK_AVOID }
    }

    private fun runOnPlayerThread(player: ExoPlayer, block: () -> Unit) {
        val looper = player.applicationLooper
        if (Looper.myLooper() == looper) block() else Handler(looper).post(block)
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "TYPE_$type"
    }
}
