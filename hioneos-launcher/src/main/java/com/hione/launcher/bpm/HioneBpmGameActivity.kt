package com.hione.launcher.bpm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The BPM game as a real Activity, so it can be opened from anywhere and closes back to wherever
 * the player was.
 *
 * WHY. The game used to exist only as a modal inside the home screen. Opening it from Hione Music
 * meant launching the launcher, which put the player on the home screen with the game over it;
 * closing the game left them on home, not on the Now Playing screen they came from.
 *
 * RETURN-TO-ORIGIN is done with the task stack, not with bookkeeping:
 *  - Started from an Activity (Now Playing, the home screen, the app drawer) WITHOUT
 *    FLAG_ACTIVITY_NEW_TASK, this activity stacks on top of the caller's own task. Closing it is
 *    finish(), and the caller is simply what is underneath.
 *  - Started from a widget or any other non-Activity context it needs NEW_TASK; its private
 *    taskAffinity and excludeFromRecents keep it from dragging the home task forward or leaving a
 *    stray recents card, so finish() again returns to whatever was in front.
 *
 * CONTRACT for other apps (see [open]):
 *   component  com.hione.launcher/com.hione.launcher.bpm.HioneBpmGameActivity
 *   action     com.hione.action.OPEN_BPM_GAME
 *   extra      "from" (String, optional): where it was opened from, e.g. now_playing
 *
 * The home-screen modal still works exactly as before; both host the same composable.
 */
class HioneBpmGameActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN = "com.hione.action.OPEN_BPM_GAME"
        const val EXTRA_FROM = "from"

        /** Open the game from inside the launcher process, keeping the caller underneath. */
        fun open(ctx: Context, from: String) {
            val i = Intent(ctx, HioneBpmGameActivity::class.java)
                .setAction(ACTION_OPEN)
                .putExtra(EXTRA_FROM, from)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (_: Throwable) {}
        }

        /**
         * For widgets: a widget click has no Activity underneath it, so the game gets its own
         * task (private affinity, excluded from recents) and closing it returns to whatever was
         * in front, normally the home screen the widget sits on.
         */
        fun pendingIntent(ctx: Context, from: String): android.app.PendingIntent {
            val i = Intent(ctx, HioneBpmGameActivity::class.java)
                .setAction(ACTION_OPEN)
                .putExtra(EXTRA_FROM, from)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return android.app.PendingIntent.getActivity(
                ctx, 7139, i,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        try {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        } catch (_: Throwable) {}
        // The beat sources live in this process; both calls are idempotent. When the game is
        // opened on top of Hione Music the home activity may never have run in this process yet.
        HioneBpmEngine.startListening(applicationContext)
        setContent {
            val bpmState by HioneBpmEngine.state.collectAsState()
            HioneBpmObservatoryModal(onClose = { finish() }, bpmState = bpmState)
        }
    }

    override fun onStart() {
        super.onStart()
        // The live beat detector only runs while launcher UI is on screen; this is launcher UI.
        com.hione.launcher.ui.HionePowerProfile.setVisible("bpm_game", true)
    }

    override fun onStop() {
        com.hione.launcher.ui.HionePowerProfile.setVisible("bpm_game", false)
        HioneBeatClickerEngine.saveState()
        super.onStop()
    }

    // Any touch re-arms the ambient attention window, same as the home screen.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.actionMasked == android.view.MotionEvent.ACTION_DOWN) com.hione.launcher.ui.HioneAmbient.touch()
        return super.dispatchTouchEvent(ev)
    }
}
