package com.hione.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.Player

/**
 * Dedicated Hardware Media Button Receiver for HiBy M500.
 *
 * Catches [Intent.ACTION_MEDIA_BUTTON] and vendor [hiby_media_button_action] broadcasts
 * directly from the Android framework (InputManager / PhoneWindowManager / AudioService)
 * with priority 1000.
 *
 * Operates reliably when:
 * 1. The screen is completely OFF / device is sleeping.
 * 2. The music player app is in the background or another app is focused.
 * 3. Headset / DAC inline remote buttons or M500 physical side buttons are pressed.
 */
class HioneMediaButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HioneMediaBtnReceiver"
        private const val WAKE_LOCK_TAG = "hione:media_button_wake"
        const val ACTION_HIBY_MEDIA_BUTTON = "hiby_media_button_action"
        const val ACTION_CUSTOM_MEDIA_BUTTON = "com.hione.action.MEDIA_BUTTON"

        @Volatile
        private var lastEventTime = 0L
        @Volatile
        private var lastKeyCode = 0
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action != Intent.ACTION_MEDIA_BUTTON &&
            action != ACTION_HIBY_MEDIA_BUTTON &&
            action != ACTION_CUSTOM_MEDIA_BUTTON &&
            action != "ACTION_MEDIA_BUTTON"
        ) {
            return
        }

        @Suppress("DEPRECATION")
        val keyEvent: KeyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            ?: intent.getParcelableExtra("android.intent.extra.KEY_EVENT")
            ?: return

        // We only act on ACTION_DOWN to respond immediately to button presses
        if (keyEvent.action != KeyEvent.ACTION_DOWN) {
            if (isOrderedBroadcast) abortBroadcast()
            return
        }

        val keyCode = keyEvent.keyCode
        val now = SystemClock.elapsedRealtime()

        // Debounce rapid duplicate hardware button events within 60ms
        if (keyCode == lastKeyCode && now - lastEventTime < 60L) {
            if (isOrderedBroadcast) abortBroadcast()
            return
        }
        lastEventTime = now
        lastKeyCode = keyCode

        Log.i(TAG, "Hardware media button received: keyCode=$keyCode action=$action")

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        try {
            wakeLock?.acquire(3000L)
        } catch (_: Throwable) {}

        val appContext = context.applicationContext

        // Ensure PlaybackService is active in the foreground so the session keeps alive
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, PlaybackService::class.java))
        } catch (_: Throwable) {}

        // Execute transport command on Main looper (Media3 player requirement)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                handleMediaKey(appContext, keyCode, keyEvent)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed handling media key $keyCode", t)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                } catch (_: Throwable) {}
            }
        }

        if (isOrderedBroadcast) {
            abortBroadcast()
        }
    }

    private fun handleMediaKey(context: Context, keyCode: Int, keyEvent: KeyEvent? = null) {
        if (AlarmRingService.interceptMediaKey(context, keyCode)) return   // ringing alarm owns the side buttons (even under pocket lock): play/pause = snooze, next/prev = dismiss
        if (HionePocketLockManager.keysLocked(context)) return   // Fn pocket lock: keys are dead
        if (HioneHardwareGestureEngine.onKeyDown(keyCode, keyEvent, context)) {
            return
        }
        val player = PlayerHolder.ensure(context)
        PlayerHolder.ensureSession(context)
        PlayerHolder.ensureControllerConnected(context)

        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                Haptics.tick(context)
                if (player.isPlaying) {
                    player.pause()
                } else {
                    ensureQueueAndPlay(context, player)
                }
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                Haptics.tick(context)
                ensureQueueAndPlay(context, player)
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                Haptics.tick(context)
                player.pause()
            }

            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                Haptics.tick(context)
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                } else if (player.mediaItemCount > 0) {
                    player.seekToDefaultPosition(0)
                } else {
                    ensureQueueAndPlay(context, player)
                }
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                Haptics.tick(context)
                if (player.currentPosition > 3000L) {
                    player.seekTo(0L)
                } else if (player.hasPreviousMediaItem()) {
                    player.seekToPreviousMediaItem()
                } else if (player.mediaItemCount > 0) {
                    player.seekToDefaultPosition(player.mediaItemCount - 1)
                } else {
                    ensureQueueAndPlay(context, player)
                }
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
    }

    private fun ensureQueueAndPlay(context: Context, player: androidx.media3.exoplayer.ExoPlayer) {
        if (player.mediaItemCount > 0) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            return
        }

        // Restore last session's queue or entire library if queue is empty
        val lastTrackId = PlayerPreferences.loadLastTrackId(context)
        val allTracks = FastLibraryStore.loadSync(context) ?: emptyList()

        if (allTracks.isNotEmpty()) {
            val targetIdx = if (lastTrackId > 0) {
                allTracks.indexOfFirst { it.id == lastTrackId }.coerceAtLeast(0)
            } else 0

            val items = allTracks.map { mediaItemFor(it) }
            val pos = PlayerPreferences.loadLastPositionMs(context).coerceAtLeast(0L)
            player.setMediaItems(items, targetIdx, pos)
            player.prepare()
            player.play()
            PlayerPreferences.saveQueue(context, allTracks.map { it.id }, targetIdx)
        }
    }
}
