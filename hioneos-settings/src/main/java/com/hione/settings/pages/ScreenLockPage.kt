package com.hione.settings.pages

import com.hione.settings.Mascot
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.hione.settings.lock.HioneCredentialActivity
import com.hione.settings.lock.HioneLock
import com.hione.settings.ui.*

/**
 * Security > Screen lock. Set, change or remove the PIN or password, choose when HioneOS asks for
 * it, whether notifications show their content while locked, and the scrambled PIN pad.
 */
@Composable
fun ScreenLockPage() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeTick { refresh++ }

    PageList {
        item {
            key(refresh) {
                val type = remember { HioneLock.credentialType(ctx) }
                val secure = type != HioneLock.TYPE_NONE
                Section("Screen lock") {
                    InfoRow("Current lock", HioneLock.typeName(type))
                    if (!secure) {
                        NavRow("Set a PIN", "${HioneLock.MIN_LENGTH} to ${HioneLock.MAX_LENGTH} digits", Icons.Default.Pin) {
                            ctx.startActivity(HioneCredentialActivity.intent(ctx, HioneCredentialActivity.MODE_SET, HioneLock.TYPE_PIN))
                        }
                        NavRow("Set a password", "${HioneLock.MIN_LENGTH} to ${HioneLock.MAX_LENGTH} characters, at least one letter", Icons.Default.Password) {
                            ctx.startActivity(HioneCredentialActivity.intent(ctx, HioneCredentialActivity.MODE_SET, HioneLock.TYPE_PASSWORD))
                        }
                    } else {
                        NavRow("Change screen lock", "Asks for the current one first", Icons.Default.Lock) {
                            ctx.startActivity(HioneCredentialActivity.intent(ctx, HioneCredentialActivity.MODE_CHANGE))
                        }
                        NavRow("Remove screen lock", "Asks for the current one first", Icons.Default.LockOpen) {
                            ctx.startActivity(HioneCredentialActivity.intent(ctx, HioneCredentialActivity.MODE_REMOVE))
                        }
                    }
                }
            }
        }
        item {
            key(refresh) {
                val type = remember { HioneLock.credentialType(ctx) }
                val secure = type != HioneLock.TYPE_NONE
                val noun = if (type == HioneLock.TYPE_PASSWORD) "password" else "PIN"
                var until by remember { mutableStateOf(HioneLock.untilRestart(ctx)) }
                Section(
                    "When to ask",
                    note = "Android also asks again 24 hours after the last time you entered it, " +
                        "and when the screen stayed off for 8 hours. ${Mascot.osName} cannot change that."
                ) {
                    ToggleRow(
                        "Ask for $noun only after restart",
                        if (until) "After the first unlock, swipe up opens the device until the next restart"
                        else "Asks every time the screen turns on",
                        if (secure) until else false,
                        enabled = secure
                    ) {
                        if (HioneLock.setUntilRestart(ctx, it)) until = it else toast(ctx, "That setting was refused")
                    }
                    if (!secure) BodyText("Set a PIN or password first.")
                }
            }
        }
        item {
            key(refresh) {
                var show by remember { mutableStateOf(HioneLock.showContent(ctx)) }
                Section("Lock screen") {
                    ToggleRow(
                        "Show notification content when locked",
                        if (show) "The shade shows full notifications while locked"
                        else "The shade shows only the app name until you unlock",
                        show
                    ) {
                        if (HioneLock.setShowContent(ctx, it)) show = it else toast(ctx, "That setting was refused")
                    }
                    var scramble by remember { mutableStateOf(HioneLock.scramblePin(ctx)) }
                    ToggleRow(
                        "Scramble PIN layout",
                        if (scramble) "The unlock pad puts the digits in new places each time it shows"
                        else "The digits stay in the usual places",
                        scramble
                    ) {
                        if (HioneLock.setScramblePin(ctx, it)) scramble = it else toast(ctx, "That setting was refused")
                    }
                }
            }
        }
    }
}
