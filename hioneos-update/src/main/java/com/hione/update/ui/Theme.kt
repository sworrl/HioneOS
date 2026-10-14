package com.hione.update.ui

import com.hione.update.Mascot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// HioneOS palette, same values as hioneos-settings' Theme.kt (#FFAB2E teal, diva pink, carbon).
val HioneTeal get() = Mascot.color(0xFFFFAB2E)
val HioneTealBright get() = Mascot.color(0xFFFFC56B)
val HionePink get() = Mascot.color(0xFF8EC5FF)
val HionePinkBright get() = Mascot.color(0xFF6FB0FF)
val HioneGold = Color(0xFFFFD54F)
val HioneDarkBg get() = Mascot.color(0xFF0D0E11)
val HioneSurface1 get() = Mascot.color(0xFF0D1417)
val HioneSurface2 get() = Mascot.color(0xFF131E22)
val HioneMuted = Color(0xFF7E9AA0)
val HioneWhite get() = Mascot.color(0xFFF0FDFE)

private val GlassFill get() = Brush.verticalGradient(listOf(Mascot.color(0xCC0E1D21), Mascot.color(0xCC081114)))
private val GlassEdge = Brush.linearGradient(listOf(HioneTeal.copy(alpha = 0.55f), HionePink.copy(alpha = 0.30f)))
private val GlassEdgeHot = Brush.linearGradient(listOf(HionePink.copy(alpha = 0.75f), HioneTealBright.copy(alpha = 0.45f)))

/** Subtle glass: translucent carbon, teal-to-pink hairline. [hot] swaps to the pink-first edge. */
fun Modifier.glassCard(hot: Boolean = false) = this
    .clip(RoundedCornerShape(16.dp))
    .background(GlassFill)
    .border(1.dp, if (hot) GlassEdgeHot else GlassEdge, RoundedCornerShape(16.dp))

@Composable
fun HioneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HioneTealBright,
            secondary = HionePink,
            background = HioneDarkBg,
            surface = HioneSurface1,
            onPrimary = HioneDarkBg,
            onBackground = HioneWhite,
            onSurface = HioneWhite,
        ),
        content = content,
    )
}

@Composable
fun Section(title: String, note: String? = null, hot: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glassCard(hot).padding(14.dp)) {
        Text(title.uppercase(), color = if (hot) HionePinkBright else HioneTealBright, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(note, color = HioneMuted, fontSize = 13.5.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
fun BodyText(text: String, color: Color = HioneMuted) {
    Text(text, color = color, fontSize = 14.5.sp, lineHeight = 19.sp)
}

@Composable
fun ActionButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) HionePink.copy(alpha = 0.20f) else HioneTeal.copy(alpha = 0.22f),
            contentColor = if (danger) HionePinkBright else HioneTealBright,
            disabledContainerColor = HioneSurface2,
            disabledContentColor = HioneMuted,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(42.dp),
    ) { Text(text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun ToggleRow(title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (summary != null) Text(summary, color = HioneMuted, fontSize = 13.5.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = HioneTealBright, checkedTrackColor = Mascot.color(0xFF0F3238)),
        )
    }
}

@Composable
fun RadioRow(title: String, summary: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) HioneTealBright.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = HioneTealBright, unselectedColor = HioneMuted))
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (summary != null) Text(summary, color = HioneMuted, fontSize = 13.5.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) HioneDarkBg else HioneTealBright,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) HioneTealBright else HioneTeal.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

@Composable
fun HioneAlert(title: String, text: String, confirm: String, danger: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HioneSurface1,
        titleContentColor = Color.White,
        textContentColor = HioneMuted,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) HionePinkBright else HioneTealBright, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HioneMuted) } },
    )
}
