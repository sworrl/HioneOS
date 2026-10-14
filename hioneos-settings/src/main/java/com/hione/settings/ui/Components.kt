package com.hione.settings.ui

import com.hione.settings.Mascot
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.settings.*

/** Shown wherever the system will not tell us a value. Never a made-up number. */
const val UNKNOWN = "Unknown"

fun toast(ctx: Context, msg: String) {
    try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
}

@Composable
fun PageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) { content() }
}

/** A titled card. Title is upper-cased like the existing HioneOS section headers. */
@Composable
fun Section(title: String? = null, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().hioneCard().padding(14.dp)) {
        if (title != null) {
            Text(title.uppercase(), color = HioneTealBright, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
            if (note != null) {
                Spacer(Modifier.height(3.dp))
                Text(note, color = HioneMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/**
 * On/off row. [checked] null means the current value could not be read: the switch is shown
 * disabled with "Unknown" rather than pretending it is off.
 */
@Composable
fun ToggleRow(
    title: String,
    summary: String? = null,
    checked: Boolean?,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val s = if (checked == null) listOfNotNull(summary, UNKNOWN).joinToString(" · ") else summary
            if (s != null) Text(s, color = HioneMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked == true,
            enabled = enabled && checked != null,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = HioneTealBright, checkedTrackColor = Mascot.color(0xFF0F3238))
        )
    }
}

/** Tappable row that opens another page or flow. */
@Composable
fun NavRow(title: String, summary: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(HioneSurface2)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = HioneTealBright, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (summary != null) Text(summary, color = HioneMuted, fontSize = 13.5.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = HioneMuted,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f }
        )
    }
}

@Composable
fun InfoRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = HioneMuted, fontSize = 14.5.sp, modifier = Modifier.weight(0.45f))
        Text(
            value?.takeIf { it.isNotBlank() } ?: UNKNOWN, color = Color.White, fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.55f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
fun BodyText(text: String) {
    Text(text, color = HioneMuted, fontSize = 14.sp, lineHeight = 19.sp)
}

@Composable
fun ActionButton(text: String, danger: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) Color(0x33FF5252) else HioneTeal.copy(alpha = 0.22f),
            contentColor = if (danger) Color(0xFFFF8A80) else HioneTealBright
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(44.dp)
    ) { Text(text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun RadioRow(title: String, summary: String? = null, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) HioneTealBright.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = HioneTealBright, unselectedColor = HioneMuted))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(title, color = Color.White, fontSize = 15.5.sp)
            if (summary != null) Text(summary, color = HioneMuted, fontSize = 13.5.sp)
        }
    }
}

@Composable
fun HioneAlert(
    title: String,
    text: String,
    confirm: String,
    dismiss: String? = "Cancel",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HioneCardBg,
        titleContentColor = Color.White,
        textContentColor = HioneMuted,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) HionePinkBright else HioneTealBright, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = if (dismiss == null) null else ({ TextButton(onClick = onDismiss) { Text(dismiss, color = HioneMuted) } })
    )
}

/** Single text field dialog (rename, password, PIN). */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    password: Boolean = false,
    numeric: Boolean = false,
    confirm: String = "Save",
    minLength: Int = 0,
    message: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HioneCardBg,
        titleContentColor = Color.White,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (message != null) { Text(message, color = HioneMuted, fontSize = 14.sp); Spacer(Modifier.height(8.dp)) }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = when {
                        numeric -> KeyboardType.Number
                        password -> KeyboardType.Password
                        else -> KeyboardType.Text
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = HioneTealBright, unfocusedBorderColor = HioneMuted,
                        focusedLabelColor = HioneTealBright, unfocusedLabelColor = HioneMuted, cursorColor = HioneTealBright
                    )
                )
                if (password) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = show, onCheckedChange = { show = it }, colors = CheckboxDefaults.colors(checkedColor = HioneTealBright))
                        Text("Show password", color = HioneMuted, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value.length >= minLength, onClick = { onConfirm(value) }) {
                Text(confirm, color = if (value.length >= minLength) HioneTealBright else HioneMuted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HioneMuted) } }
    )
}

/** Options picker dialog. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HioneCardBg,
        titleContentColor = Color.White,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options.size) { i ->
                    val (v, label) = options[i]
                    RadioRow(label, selected = v == selected) { onPick(v) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = HioneMuted) } }
    )
}

@Composable
fun SliderRow(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, enabled: Boolean = true, onChange: (Float) -> Unit, onDone: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            Text(valueLabel, color = HioneTealBright, fontSize = 14.5.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = HioneTealBright, activeTrackColor = HioneTeal, inactiveTrackColor = HioneSurface2)
        )
    }
}
