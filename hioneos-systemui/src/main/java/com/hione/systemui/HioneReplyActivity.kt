package com.hione.systemui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reply box for a notification's reply action (SMS, chat apps).
 *
 * The shade is an accessibility overlay window, and those sit above the keyboard, so a text field
 * inside the shade would type behind it. The shade closes and this small activity takes the text
 * instead, then sends it through the action's RemoteInput like stock SystemUI's inline reply.
 */
class HioneReplyActivity : ComponentActivity() {
    companion object {
        const val EXTRA_KEY = "hione.reply.key"

        fun start(ctx: Context, key: String) {
            val i = Intent(ctx, HioneReplyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_KEY, key)
            runCatching {
                android.app.PendingIntent.getActivity(
                    ctx, 7, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                ).send()
            }.onFailure { runCatching { ctx.startActivity(i) } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent?.getStringExtra(EXTRA_KEY)
        val n = key?.let { HioneNotificationStore.find(it) }
        val action = n?.replyAction
        if (n == null || action == null) { finish(); return }
        setContent {
            var text by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            val accent = if (n.accent != 0) Color(n.accent) else HioneTeal
            fun sendNow() {
                val t = text.trim()
                if (t.isEmpty()) return
                val ok = HioneNotificationStore.reply(this@HioneReplyActivity, action, t)
                if (!ok) Toast.makeText(this@HioneReplyActivity, "Could not send the reply", Toast.LENGTH_SHORT).show()
                finish()
            }
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000))
                    .clickable(remember { MutableInteractionSource() }, null) { finish() }
                    .imePadding(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(HioneSurface1)
                        .clickable(remember { MutableInteractionSource() }, null) { }
                        .padding(14.dp)
                ) {
                    Text("${n.appLabel}  ·  ${n.conversationTitle ?: n.title}", color = accent, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    val lines = n.messages.takeLast(3)
                    if (lines.isNotEmpty()) {
                        lines.forEach { m ->
                            Text((m.sender?.let { "$it: " } ?: "") + m.text, color = HioneTextSecondary, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else if (n.text.isNotBlank()) {
                        Text(n.text, color = HioneTextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(HioneSurface2)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            if (text.isEmpty()) Text(action.remoteInputs.firstOrNull()?.label?.toString() ?: "Reply", color = HioneMuted, fontSize = 14.sp)
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it },
                                textStyle = TextStyle(color = HioneWhite, fontSize = 14.sp),
                                cursorBrush = SolidColor(accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { sendNow() }),
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        GlassChipButton("SEND", accent = accent, filled = text.isNotBlank()) { sendNow() }
                    }
                }
            }
        }
    }
}
