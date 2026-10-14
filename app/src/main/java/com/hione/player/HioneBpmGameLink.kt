package com.hione.player

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent

/**
 * The one way Hione Music opens the BPM rhythm game. Every button that leads to the game goes
 * through [open], so they all behave the same and only this file knows the launcher's contract.
 *
 * The game is an exported activity in the HioneOS launcher. Started from an Activity context it is
 * deliberately NOT given FLAG_ACTIVITY_NEW_TASK: it then stacks on top of Hione Music's own task,
 * and when the player leaves the game it finishes and they land back exactly where they were (Now
 * Playing stays Now Playing). NEW_TASK is only added when there is no Activity to stack on, since
 * Android requires it there.
 *
 * Older launchers have no game activity; for those it falls back to the original hand-off, the
 * launcher's own launch intent with the `open_bpm` extra.
 */
object HioneBpmGameLink {
    private const val LAUNCHER_PKG = "com.hione.launcher"
    private const val GAME_ACTIVITY = "com.hione.launcher.bpm.HioneBpmGameActivity"
    private const val ACTION_OPEN_BPM_GAME = "com.hione.action.OPEN_BPM_GAME"
    private const val EXTRA_FROM = "from"

    const val FROM_NOW_PLAYING = "now_playing"
    const val FROM_PLAYER_BAR = "player_bar"
    const val FROM_HEADER = "header"
    const val FROM_MONITOR = "monitor"

    fun open(ctx: Context, from: String) {
        val activity = ctx.findActivity()

        // 1. The game activity itself. startActivity with an explicit component is not subject to
        //    package-visibility filtering, so trying it and catching the failure is the reliable
        //    "is it there" test.
        val game = Intent(ACTION_OPEN_BPM_GAME)
            .setComponent(ComponentName(LAUNCHER_PKG, GAME_ACTIVITY))
            .putExtra(EXTRA_FROM, from)
        if (activity == null) game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            (activity ?: ctx).startActivity(game)
            return
        } catch (_: Throwable) {
            // ActivityNotFoundException on an older launcher; anything else, same fallback.
        }

        // 2. Older launcher: open it with the `open_bpm` extra. This one is the launcher's own
        //    main task, so it always needs NEW_TASK.
        val launch = ctx.packageManager.getLaunchIntentForPackage(LAUNCHER_PKG)?.apply {
            putExtra("open_bpm", true)
            putExtra(EXTRA_FROM, from)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        if (launch != null) {
            try {
                ctx.startActivity(launch)
                return
            } catch (_: Throwable) {
            }
        }

        android.widget.Toast.makeText(ctx, "Couldn't open the BPM game", android.widget.Toast.LENGTH_SHORT).show()
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
