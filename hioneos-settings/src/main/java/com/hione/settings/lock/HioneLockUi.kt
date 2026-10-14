package com.hione.settings.lock

import com.hione.settings.Mascot
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.settings.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The HioneOS PIN and password entry, shared by the unlock screen and the set/change/remove flow.
 * Same palette as the rest of HioneSettings: obsidian background, Hione teal, pink for errors.
 */

@Composable
fun HioneLockBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Mascot.color(0xFF0B2A2E), HioneDarkBg, Mascot.color(0xFF05080A)))
        ),
        content = content
    )
}

/** Small clock and date for the top of the unlock screen. */
@Composable
fun LockClock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(5_000) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            color = HioneTealBright, fontSize = 44.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace
        )
        Text(
            SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now),
            color = HioneMuted, fontSize = 13.sp
        )
    }
}

/**
 * Title, dots or password field, error line and keypad.
 *
 * [resetKey] clears the typed value whenever it changes (wrong entry, next step).
 * [shakeKey] runs the error shake whenever it changes.
 * [scramble] puts the PIN digits in random places (SecureRandom), new places whenever
 * [layoutKey] changes. The grid shape stays the same.
 */
@Composable
fun CredentialEntry(
    type: Int,
    title: String,
    subtitle: String?,
    error: String?,
    busy: Boolean,
    locked: Boolean,
    resetKey: Int,
    shakeKey: Int,
    submitLabel: String = "OK",
    onSubmit: (String) -> Unit,
    onCancel: (() -> Unit)?,
    header: (@Composable () -> Unit)? = null,
    scramble: Boolean = false,
    layoutKey: Int = 0
) {
    var value by remember(resetKey) { mutableStateOf("") }
    val view = LocalView.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) {
        if (shakeKey > 0) {
            for (x in listOf(18f, -14f, 10f, -6f, 3f, 0f)) shake.animateTo(x, tween(45))
        }
    }
    val canType = !busy && !locked
    // An old error goes away as soon as the next entry starts.
    val shownError = if (value.isNotEmpty() && !locked) null else error
    fun submit() {
        if (!canType || value.isEmpty()) return
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onSubmit(value)
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (header != null) header() else Spacer(Modifier.height(24.dp))
        Spacer(Modifier.weight(0.6f))
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(HioneTeal.copy(alpha = 0.16f))
                .border(1.dp, HioneTealBright.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Lock, contentDescription = null, tint = HioneTealBright, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(title, color = HioneWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = HioneMuted, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.offset { IntOffset(shake.value.toInt(), 0) }) {
            if (type == HioneLock.TYPE_PASSWORD) {
                PasswordBox(value, enabled = canType, onChange = { if (it.length <= HioneLock.MAX_LENGTH) value = it }, onDone = { submit() })
            } else {
                PinDots(value.length, shownError != null)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(color = HioneTealBright, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                shownError != null -> Text(shownError, color = HionePinkBright, fontSize = 12.5.sp, textAlign = TextAlign.Center, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.weight(0.4f))
        if (type == HioneLock.TYPE_PASSWORD) {
            Button(
                onClick = { submit() },
                enabled = canType && value.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = HioneTeal.copy(alpha = 0.25f), contentColor = HioneTealBright),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(submitLabel, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        } else {
            PinPad(
                enabled = canType,
                okEnabled = canType && value.isNotEmpty(),
                scramble = scramble,
                layoutKey = layoutKey,
                onDigit = { d -> if (value.length < HioneLock.MAX_LENGTH) value += d },
                onBack = { value = value.dropLast(1) },
                onClear = { value = "" },
                onOk = { submit() }
            )
        }
        Spacer(Modifier.height(8.dp))
        if (onCancel != null) {
            TextButton(onClick = onCancel) { Text("Cancel", color = HioneMuted, fontSize = 13.sp) }
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun PinDots(count: Int, error: Boolean) {
    val color = if (error) HionePinkBright else HioneTealBright
    Row(
        Modifier.height(28.dp).widthIn(min = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (count == 0) {
            repeat(4) { Box(Modifier.size(12.dp).clip(CircleShape).border(1.5.dp, HioneMuted.copy(alpha = 0.6f), CircleShape)) }
        } else {
            repeat(count) { Box(Modifier.size(12.dp).clip(CircleShape).background(color)) }
        }
    }
}

@Composable
private fun PasswordBox(value: String, enabled: Boolean, onChange: (String) -> Unit, onDone: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        placeholder = { Text("Password", color = HioneMuted) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrect = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (show) "Hide" else "Show", tint = HioneMuted)
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = HioneWhite, unfocusedTextColor = HioneWhite, disabledTextColor = HioneMuted,
            focusedBorderColor = HioneTealBright, unfocusedBorderColor = HioneMuted, cursorColor = HioneTealBright,
            focusedContainerColor = HioneSurface1, unfocusedContainerColor = HioneSurface1, disabledContainerColor = HioneSurface1
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
    )
}

@Composable
private fun PinPad(
    enabled: Boolean,
    okEnabled: Boolean,
    scramble: Boolean,
    layoutKey: Int,
    onDigit: (Char) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onOk: () -> Unit
) {
    // Ten keys: three rows of three, then the one between Delete and Enter.
    val order = remember(scramble, layoutKey) {
        if (scramble) "0123456789".toMutableList().apply { shuffle(java.security.SecureRandom()) }.joinToString("")
        else "1234567890"
    }
    val rows = listOf(order.substring(0, 3), order.substring(3, 6), order.substring(6, 9))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                r.forEach { c -> PadKey(label = c.toString(), enabled = enabled) { onDigit(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            PadKey(icon = Icons.AutoMirrored.Filled.Backspace, description = "Delete", enabled = enabled, quiet = true, onLongClick = onClear) { onBack() }
            PadKey(label = order[9].toString(), enabled = enabled) { onDigit(order[9]) }
            PadKey(icon = Icons.Default.Check, description = "Enter", enabled = okEnabled, accent = true) { onOk() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PadKey(
    label: String? = null,
    icon: ImageVector? = null,
    description: String? = null,
    enabled: Boolean,
    quiet: Boolean = false,
    accent: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val border = when {
        !enabled -> HioneMuted.copy(alpha = 0.2f)
        accent -> HioneTealBright.copy(alpha = 0.8f)
        quiet -> Color.Transparent
        else -> HioneTeal.copy(alpha = 0.45f)
    }
    val fill = when {
        accent && enabled -> HioneTeal.copy(alpha = 0.28f)
        quiet -> Color.Transparent
        else -> HioneSurface2.copy(alpha = 0.85f)
    }
    Box(
        Modifier.size(68.dp).clip(CircleShape).background(fill).border(1.2.dp, border, CircleShape)
            .combinedClickable(
                enabled = enabled,
                onLongClick = onLongClick?.let { l -> { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); l() } },
                onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        val tint = if (enabled) (if (accent) HioneTealBright else HioneWhite) else HioneMuted.copy(alpha = 0.4f)
        if (label != null) Text(label, color = tint, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        if (icon != null) Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Two big choices, used when picking PIN or password. */
@Composable
fun LockTypeChoice(title: String, subtitle: String, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = HioneWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = HioneMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        ChoiceCard("PIN", "${HioneLock.MIN_LENGTH} to ${HioneLock.MAX_LENGTH} digits") { onPick(HioneLock.TYPE_PIN) }
        Spacer(Modifier.height(10.dp))
        ChoiceCard("Password", "${HioneLock.MIN_LENGTH} to ${HioneLock.MAX_LENGTH} characters, at least one letter") { onPick(HioneLock.TYPE_PASSWORD) }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCancel) { Text("Cancel", color = HioneMuted, fontSize = 13.sp) }
    }
}

@Composable
private fun ChoiceCard(title: String, summary: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().hioneCard().clickable(onClick = onClick).padding(16.dp)
    ) {
        Text(title, color = HioneTealBright, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(summary, color = HioneMuted, fontSize = 12.sp)
    }
}
