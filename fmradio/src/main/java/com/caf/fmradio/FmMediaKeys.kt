package com.caf.fmradio

import android.content.Context
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import java.lang.reflect.Proxy

/**
 * The M500's transport keys (play/pause, previous, next) driving the radio.
 *
 *  - next / previous, short press: next or previous preset, wrapping round. With no presets,
 *    a seek to the next station instead.
 *  - next / previous held, fast forward, rewind: seek up or down to the next station.
 *  - play/pause: mute or unmute while the tuner is on, power it on when it is off.
 *  - play/pause held: power the tuner off.
 *
 * Every action gives one short haptic tick. The rest of the feedback is what the action does
 * anyway: the dial moves, the seek overlay runs, the lockscreen and widgets follow the session.
 *
 * TWO ROUTES IN. In front, the activity hands keys here from dispatchKeyEvent. Behind other
 * apps or with the screen off, keys go to whichever media session Android picks, and it picks
 * by the app that last played audio through an AudioTrack. The radio plays through the HAL's
 * hardware loopback, not an AudioTrack, so Android never picks it and Hione Music (or its media
 * button receiver) gets the keys. So while the tuner is on this registers the system's media
 * key listener (SET_MEDIA_KEY_LISTENER, signature, held by this platform-signed app), which sees
 * every media key before any session does. It is removed the moment the tuner powers off, so
 * the keys go back to Hione Music.
 *
 * Long presses are timed from key down to key up, not from key repeats: with the screen off,
 * the window manager hands media keys straight to the session service and no repeats are made.
 */
object FmMediaKeys {

    private const val TAG = "FmMediaKeys"
    /** Held at least this long is a long press. */
    private const val LONG_MS = 500L
    /** In front, key repeats arrive; this many means held, so act without waiting for key up. */
    private const val LONG_REPEATS = 2

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var listener: Any? = null
    /** Keys whose long-press action already ran on a repeat; their key up does nothing. */
    private val longDone = HashSet<Int>()

    fun isTransportKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_STOP -> true
        else -> false
    }

    private fun hasLongPress(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> true
        else -> false
    }

    /**
     * Handle one key event. True when it was a transport key and is now dealt with. Main thread.
     */
    fun handle(ctx: Context, e: KeyEvent): Boolean {
        val code = e.keyCode
        if (!isTransportKey(code)) return false
        app = ctx.applicationContext
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0) {
                    longDone.remove(code)
                    // Fast forward and rewind act on the press, like a seek button.
                    if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND) {
                        longDone += code
                        act { FmRadioManager.seek(code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) }
                    }
                } else if (e.repeatCount >= LONG_REPEATS && hasLongPress(code) && code !in longDone) {
                    longDone += code
                    longPress(code)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (longDone.remove(code)) return true
                if (e.eventTime - e.downTime >= LONG_MS && hasLongPress(code)) longPress(code)
                else shortPress(code)
            }
        }
        return true
    }

    private fun shortPress(code: Int) {
        val a = app ?: return
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> act { presetStep(a, true) }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> act { presetStep(a, false) }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> act { playPause(a) }
            KeyEvent.KEYCODE_MEDIA_PLAY -> act { play(a) }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> act {
                val st = FmRadioManager.state.value
                if (st.isPowerOn && !st.isMuted) FmRadioManager.toggleMute()
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> act { powerOff(a) }
        }
    }

    private fun longPress(code: Int) {
        val a = app ?: return
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> act { seekOrStart(a, true) }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> act { seekOrStart(a, false) }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> act { powerOff(a) }
        }
    }

    private inline fun act(block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "key action failed: $it") }
        tick()
    }

    // ------------------------------------------------------------------ actions

    /** Power on, or unmute if it is on and muted. */
    fun play(ctx: Context) {
        val st = FmRadioManager.state.value
        if (st.isPowerOn) {
            if (st.isMuted) FmRadioManager.toggleMute()
            return
        }
        HioneFmService.start(ctx.applicationContext)
    }

    fun powerOff(ctx: Context) {
        if (FmRadioManager.state.value.isPowerOn) HioneFmService.stop(ctx.applicationContext)
    }

    /**
     * Mute and unmute while on, so the key keeps the radio, not Hione Music: powering off here
     * would hand the next press to Hione Music. Held, the same key powers off.
     */
    private fun playPause(ctx: Context) {
        val st = FmRadioManager.state.value
        if (st.isPowerOn) FmRadioManager.toggleMute() else play(ctx)
    }

    private fun seekOrStart(ctx: Context, up: Boolean) {
        val st = FmRadioManager.state.value
        if (st.isHardwareOnline) FmRadioManager.seek(up)
        else { FmRadioManager.ensure(ctx).step(up); play(ctx) }
    }

    /** Next or previous preset, wrapping; a seek when there are no presets. */
    fun presetStep(ctx: Context, up: Boolean) {
        val st = FmRadioManager.state.value
        val presets = st.favorites.sorted()
        if (presets.isEmpty()) { seekOrStart(ctx, up); return }
        val cur = st.frequencyKHz
        val target = if (up) presets.firstOrNull { it > cur } ?: presets.first()
            else presets.lastOrNull { it < cur } ?: presets.last()
        FmRadioManager.ensure(ctx)
        if (target != st.frequencyKHz) FmRadioManager.tune(target)
        play(ctx)
    }

    private fun tick() {
        val a = app ?: return
        runCatching {
            val v = a.getSystemService(Vibrator::class.java) ?: return
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }.onFailure { Log.w(TAG, "haptic tick failed: $it") }
    }

    // ------------------------------------------------------------------ background route

    /**
     * Take the media keys while the tuner is on, give them back when it is off. Cheap to call
     * on every state change; it only touches the system when the answer changes.
     */
    fun setTunerOn(ctx: Context, on: Boolean) {
        app = ctx.applicationContext
        if (on == (listener != null)) return
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        runCatching {
            val iface = Class.forName("android.media.session.MediaSessionManager\$OnMediaKeyListener")
            val set = MediaSessionManager::class.java.getMethod("setOnMediaKeyListener", iface, Handler::class.java)
            if (on) {
                var self: Any? = null
                val l = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
                    when (m.name) {
                        "onMediaKey" -> onBackgroundKey(args?.getOrNull(0) as? KeyEvent)
                        "equals" -> args?.getOrNull(0) === self
                        "hashCode" -> System.identityHashCode(self)
                        "toString" -> "FmMediaKeys"
                        else -> null
                    }
                }
                self = l
                set.invoke(msm, l, main)
                listener = l
                Log.i(TAG, "media keys now drive the radio")
            } else {
                set.invoke(msm, null, null)
                listener = null
                longDone.clear()
                Log.i(TAG, "media keys handed back")
            }
        }.onFailure {
            Log.w(TAG, "could not ${if (on) "take" else "release"} the media keys: ${it.cause ?: it}")
        }
    }

    /** From the system listener (main thread). False lets the key go on to the sessions. */
    private fun onBackgroundKey(e: KeyEvent?): Boolean {
        e ?: return false
        if (!FmRadioManager.state.value.isPowerOn && !longDone.contains(e.keyCode)) return false
        val a = app ?: return false
        return handle(a, e)
    }
}
