package com.hione.settings.lock

import com.hione.settings.Mascot
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.hione.settings.HioneMuted
import com.hione.settings.HioneTealBright
import com.hione.settings.HioneWhite
import com.hione.settings.ui.HioneAlert
import com.hione.settings.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Set, change or remove the screen lock, in HioneOS style.
 *
 * Set:    choose PIN or password, enter it, enter it again.
 * Change: enter the current one, choose PIN or password, enter the new one twice.
 * Remove: enter the current one, confirm.
 *
 * The current credential is checked by LockSettingsService before anything changes, and
 * setLockCredential checks it again, so nothing here can change the lock without it.
 */
class HioneCredentialActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODE = "com.hione.settings.extra.LOCK_MODE"
        const val EXTRA_TYPE = "com.hione.settings.extra.LOCK_TYPE"
        const val MODE_SET = "set"
        const val MODE_CHANGE = "change"
        const val MODE_REMOVE = "remove"

        fun intent(ctx: Context, mode: String, type: Int? = null): Intent =
            Intent(ctx, HioneCredentialActivity::class.java).putExtra(EXTRA_MODE, mode).apply {
                if (type != null) putExtra(EXTRA_TYPE, type)
            }
    }

    private enum class Step { CURRENT, CHOOSE, NEW, CONFIRM, REMOVE_CONFIRM, PATTERN, SAVING }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_SET
        val presetType = intent?.getIntExtra(EXTRA_TYPE, 0)?.takeIf { it == HioneLock.TYPE_PIN || it == HioneLock.TYPE_PASSWORD }
        val currentType = HioneLock.credentialType(this)
        val secure = currentType != HioneLock.TYPE_NONE

        if (!secure && mode != MODE_SET) { finish(); return }

        setContent {
            var step by remember {
                mutableStateOf(
                    when {
                        secure && currentType == HioneLock.TYPE_PATTERN -> Step.PATTERN
                        secure -> Step.CURRENT
                        presetType == null -> Step.CHOOSE
                        else -> Step.NEW
                    }
                )
            }
            var oldValue by remember { mutableStateOf<String?>(null) }
            var newType by remember { mutableIntStateOf(presetType ?: HioneLock.TYPE_PIN) }
            var firstEntry by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(false) }
            var lockedUntil by remember { mutableLongStateOf(0L) }
            var resetKey by remember { mutableIntStateOf(0) }
            var shakeKey by remember { mutableIntStateOf(0) }
            val now by produceState(SystemClock.elapsedRealtime(), lockedUntil) {
                while (SystemClock.elapsedRealtime() < lockedUntil) { value = SystemClock.elapsedRealtime(); delay(250) }
                value = SystemClock.elapsedRealtime()
            }
            val wait = ((lockedUntil - now + 999) / 1000).coerceAtLeast(0)

            fun go(s: Step) { step = s; error = null; resetKey++ }
            fun fail(msg: String) { error = msg; shakeKey++; resetKey++ }

            fun save(type: Int, value: String?) {
                step = Step.SAVING
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        HioneLock.setCredential(this@HioneCredentialActivity, type, value, if (secure) currentType else HioneLock.TYPE_NONE, oldValue)
                    }
                    if (r.isSuccess) {
                        toast(this@HioneCredentialActivity, when {
                            type == HioneLock.TYPE_NONE -> "Screen lock removed"
                            secure -> "Screen lock changed"
                            else -> "Screen lock set"
                        })
                        setResult(Activity.RESULT_OK)
                        finish()
                    } else {
                        step = if (type == HioneLock.TYPE_NONE) Step.REMOVE_CONFIRM else Step.NEW
                        fail("Not saved: ${r.exceptionOrNull()?.message ?: "unknown error"}")
                    }
                }
            }

            val back: () -> Unit = {
                when (step) {
                    Step.CONFIRM -> go(Step.NEW)
                    Step.NEW -> if (presetType == null && mode != MODE_REMOVE) go(Step.CHOOSE) else finish()
                    Step.SAVING -> {}
                    else -> finish()
                }
            }
            BackHandler { back() }

            val curNoun = if (currentType == HioneLock.TYPE_PASSWORD) "password" else "PIN"
            val newNoun = if (newType == HioneLock.TYPE_PASSWORD) "password" else "PIN"

            HioneLockBackdrop {
                when (step) {
                    Step.PATTERN -> PatternNotice(onClose = { finish() })
                    Step.CURRENT -> CredentialEntry(
                        type = currentType,
                        title = "Enter your current $curNoun",
                        subtitle = if (mode == MODE_REMOVE) "Needed to remove the screen lock" else "Needed before you change it",
                        error = if (wait > 0) "Too many tries. Try again in ${wait}s" else error,
                        busy = busy, locked = wait > 0, resetKey = resetKey, shakeKey = shakeKey,
                        submitLabel = "Next",
                        onSubmit = { v ->
                            busy = true; error = null
                            lifecycleScope.launch {
                                val r = withContext(Dispatchers.IO) { HioneLock.check(this@HioneCredentialActivity, currentType, v) }
                                busy = false
                                when (r) {
                                    is HioneLock.Check.Ok -> {
                                        oldValue = v
                                        go(if (mode == MODE_REMOVE) Step.REMOVE_CONFIRM else if (presetType == null) Step.CHOOSE else Step.NEW)
                                    }
                                    is HioneLock.Check.Wrong -> fail(if (currentType == HioneLock.TYPE_PASSWORD) "Wrong password" else "Wrong PIN")
                                    is HioneLock.Check.Throttled -> { lockedUntil = SystemClock.elapsedRealtime() + r.ms; shakeKey++; resetKey++ }
                                    is HioneLock.Check.Failed -> fail("Could not check it: ${r.why}")
                                }
                            }
                        },
                        onCancel = { finish() }
                    )
                    Step.CHOOSE -> LockTypeChoice(
                        title = if (secure) "Choose a new screen lock" else "Choose a screen lock",
                        subtitle = "${Mascot.osName} asks for it after every restart",
                        onPick = { t -> newType = t; go(Step.NEW) },
                        onCancel = { finish() }
                    )
                    Step.NEW -> CredentialEntry(
                        type = newType,
                        title = "Choose a $newNoun",
                        subtitle = if (newType == HioneLock.TYPE_PIN) "${HioneLock.MIN_LENGTH} to ${HioneLock.MAX_LENGTH} digits"
                                   else "${HioneLock.MIN_LENGTH} to ${HioneLock.MAX_LENGTH} characters, at least one letter",
                        error = error, busy = false, locked = false, resetKey = resetKey, shakeKey = shakeKey,
                        submitLabel = "Next",
                        onSubmit = { v ->
                            val bad = HioneLock.validate(newType, v)
                            if (bad != null) fail(bad) else { firstEntry = v; go(Step.CONFIRM) }
                        },
                        onCancel = { back() }
                    )
                    Step.CONFIRM -> CredentialEntry(
                        type = newType,
                        title = "Enter it again",
                        subtitle = "To make sure you typed the $newNoun you meant",
                        error = error, busy = false, locked = false, resetKey = resetKey, shakeKey = shakeKey,
                        submitLabel = "Save",
                        onSubmit = { v ->
                            if (v != firstEntry) {
                                firstEntry = ""
                                go(Step.NEW)
                                fail("Those did not match. Choose it again.")
                            } else save(newType, v)
                        },
                        onCancel = { back() }
                    )
                    Step.REMOVE_CONFIRM -> HioneAlert(
                        title = "Remove screen lock?",
                        text = "Anyone who has the device can open it, also after a restart. The swipe lockscreen stays." +
                            (error?.let { "\n\n$it" } ?: ""),
                        confirm = "Remove",
                        danger = true,
                        onConfirm = { save(HioneLock.TYPE_NONE, null) },
                        onDismiss = { finish() }
                    )
                    Step.SAVING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.CircularProgressIndicator(color = HioneTealBright, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Saving", color = HioneMuted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternNotice(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("A pattern lock is set", color = HioneWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "${Mascot.osName} handles PINs and passwords. Remove the pattern in the stock lock settings, then set a PIN or password here.",
            color = HioneMuted, fontSize = 12.5.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onClose) { Text("Close", color = HioneTealBright) }
    }
}
