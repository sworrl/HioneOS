package com.hione.player.scrobble

import com.hione.player.Mascot
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.media3.exoplayer.ExoPlayer
import com.hione.player.HioneTealBright
import com.hione.player.Muted
import kotlinx.coroutines.delay

/**
 * The one-time offer to set up Last.fm scrobbling.
 *
 * WHEN. Not at launch. It waits until the user has actually been listening for
 * [LISTEN_BEFORE_OFFER_SEC] of this session, so the first thing a new install does is play music,
 * not ask for an account. Shown once (see ScrobblePreferences.shouldOfferSetup); "Remind me"
 * snoozes a week, and there is no third showing after that.
 *
 * WHAT IT DOES NOT DO. It does not collect anything. Tapping "Set it up" opens the Last.fm card in
 * Settings, where sign-in happens in the user's own browser and HioneOS never sees the password.
 */
private const val LISTEN_BEFORE_OFFER_SEC = 90

@Composable
fun ScrobbleSetupOffer(player: ExoPlayer, onOpenSettings: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var show by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!ScrobblePreferences.shouldOfferSetup(ctx)) return@LaunchedEffect
        var listened = 0
        while (listened < LISTEN_BEFORE_OFFER_SEC) {
            delay(1000)
            if (player.isPlaying) listened++
        }
        // Re-check: they may have configured it during those 90 seconds.
        if (ScrobblePreferences.shouldOfferSetup(ctx)) show = true
    }

    if (!show) return
    Dialog(onDismissRequest = { ScrobblePreferences.snoozeSetupOffer(ctx); show = false }) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Mascot.color(0xFF0B1F23))
                .padding(20.dp)
        ) {
            Text("Scrobble to Last.fm?", color = HioneTealBright, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "${Mascot.musicAppName} can send what you play to your Last.fm profile, and keep a queue so " +
                    "nothing is lost while you are offline.",
                color = Mascot.color(0xFFFFF3E0), fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Sign-in happens on last.fm in your browser. ${Mascot.osName} never sees your password, and " +
                    "the key it gets back is stored encrypted on this device only.",
                color = Muted, fontSize = 11.sp
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Remind me later",
                    color = Muted,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { ScrobblePreferences.snoozeSetupOffer(ctx); show = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "No thanks",
                    color = Muted,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { ScrobblePreferences.markSetupOffered(ctx); show = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Set it up",
                    color = Mascot.color(0xFF121318),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(HioneTealBright)
                        .clickable {
                            ScrobblePreferences.markSetupOffered(ctx)
                            show = false
                            onOpenSettings()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}
