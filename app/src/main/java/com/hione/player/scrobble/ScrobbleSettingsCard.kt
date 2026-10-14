package com.hione.player.scrobble

import com.hione.player.Mascot
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.LastFm
import com.hione.player.LastFmPreferences
import com.hione.player.HioneTealBright
import com.hione.player.Muted
import com.hione.player.Surface1
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LastFmRed = Color(0xFFD51007)
private val CardShape = RoundedCornerShape(18.dp)

/**
 * Settings → Last.fm card. Replaces the earlier build-key-only card: the user can paste their own
 * API key + shared secret (from https://www.last.fm/api/account/create), sign in with username/
 * password (auth.getMobileSession — the password is never stored), flip scrobbling on/off, and
 * see a REAL status line: last scrobble time, queue depth, last error, accepted/ignored totals —
 * every value comes from [ScrobbleManager.state], i.e. from actual API responses and the on-disk
 * queue. Nothing here is simulated.
 */
@Composable
fun ScrobbleSettingsCard(ctx: Context) {
    LaunchedEffect(Unit) { ScrobbleManager.init(ctx); ScrobbleManager.refreshState() }
    val state by ScrobbleManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    /** The request token waiting for the user to approve it on last.fm. Not secret on its own. */
    var pendingToken by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(ScrobblePreferences.userApiKey(ctx)) }
    var apiSecret by remember { mutableStateOf(ScrobblePreferences.userApiSecret(ctx)) }
    var showKeys by remember { mutableStateOf(!ScrobblePreferences.hasUserCredentials(ctx) && !ScrobblePreferences.hasBuildCredentials()) }
    var username by remember { mutableStateOf(LastFmPreferences.loadUsername(ctx) ?: "") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(Surface1, Color(0xFF2A0A10))))
            .border(1.dp, LastFmRed.copy(alpha = 0.35f), CardShape)
            .padding(18.dp)
    ) {
        // ---- header + master toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Podcasts, "Last.fm", tint = LastFmRed, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Last.fm Scrobbling", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill(state.enabled, accent = LastFmRed) { on -> ScrobblePreferences.setEnabled(ctx, on); ScrobbleManager.onSettingsChanged() }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !state.enabled -> "Scrobbling is off. Listens are still logged locally; nothing is sent to Last.fm."
                // With a shipped app key (the normal release), there is nothing to paste: the user
                // just signs in as themselves. The key fields below stay, collapsed, for anyone who
                // would rather run HioneOS against their OWN Last.fm API account.
                !state.configured -> "Last.fm isn't set up on this build. Open API credentials below, or use your own Last.fm API account."
                state.connected -> "Connected as ${state.username ?: username}. Tracks scrobble after 50% or 4 minutes, and queue offline."
                else -> "Sign in to scrobble every track you play to your Last.fm profile."
            },
            color = Muted, fontSize = 12.sp, lineHeight = 16.sp
        )

        // ---- API credentials
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showKeys = !showKeys }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (ScrobblePreferences.hasBuildCredentials() && !ScrobblePreferences.hasUserCredentials(ctx))
                    "API credentials (optional)" else "API credentials",
                color = Mascot.color(0xFFE8F4F2), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            Text(
                when (state.credentialSource) { "user" -> "your key"; "build" -> "${Mascot.osName} key"; else -> "missing" },
                color = if (state.credentialSource == "none") Color(0xFFFF6B6B) else Muted, fontSize = 11.sp
            )
            Spacer(Modifier.width(6.dp))
            Text(if (showKeys) "▾" else "▸", color = Muted, fontSize = 12.sp)
        }
        if (showKeys) {
            Spacer(Modifier.height(6.dp))
            Field(apiKey, { apiKey = it }, "API key (32 hex chars)")
            Spacer(Modifier.height(6.dp))
            Field(apiSecret, { apiSecret = it }, "Shared secret", isPassword = true)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Save keys", LastFmRed, enabled = apiKey.isNotBlank() && apiSecret.isNotBlank()) {
                    ScrobblePreferences.saveCredentials(ctx, apiKey, apiSecret)
                    ScrobbleManager.onSettingsChanged()
                    error = null
                }
                if (ScrobblePreferences.hasUserCredentials(ctx)) SmallButton("Clear", Color.White.copy(alpha = 0.10f)) {
                    ScrobblePreferences.clearCredentials(ctx); apiKey = ""; apiSecret = ""
                    ScrobbleManager.onSettingsChanged()
                }
            }
            Text(
                if (ScrobblePreferences.hasBuildCredentials())
                    "${Mascot.osName} ships its own Last.fm API key, so you do not need these. Fill them in only " +
                        "if you would rather scrobble through your OWN API account. Free at " +
                        "last.fm/api/account/create. Stored encrypted on this device only."
                else
                    "Free at last.fm/api/account/create. Stored encrypted on this device only.",
                color = Muted.copy(alpha = 0.85f), fontSize = 10.5.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }

        // ---- account
        if (state.configured) {
            Spacer(Modifier.height(14.dp))
            if (state.connected) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable {
                            LastFmPreferences.disconnect(ctx)
                            username = ""; password = ""; error = null
                            ScrobbleManager.onSettingsChanged()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LinkOff, "Disconnect", tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Disconnect", color = Color(0xFFFF6B6B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // BROWSER SIGN-IN, not a password box. auth.getMobileSession works and is still
                // there, but it takes the account password into this process; the browser flow
                // never shows it to us at all. Two taps: approve on last.fm, then Finish.
                Text(
                    "Sign in on last.fm in your browser. ${Mascot.osName} never sees your password.",
                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (loading) LastFmRed.copy(alpha = 0.35f) else LastFmRed)
                        .clickable(enabled = !loading) {
                            loading = true; error = null
                            scope.launch {
                                when (val r = LastFm.requestAuthToken()) {
                                    is LastFm.TokenResult.Success -> {
                                        pendingToken = r.token
                                        runCatching {
                                            ctx.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(LastFm.authUrl(r.token))
                                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }.onFailure { error = "No browser on this device to approve it in" }
                                    }
                                    is LastFm.TokenResult.Failure -> error = r.message
                                }
                                loading = false
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (loading) "Opening Last.fm…" else if (pendingToken.isBlank()) "Connect with Last.fm" else "Open Last.fm again",
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
                if (pendingToken.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(HioneTealBright.copy(alpha = if (loading) 0.3f else 0.9f))
                            .clickable(enabled = !loading) {
                                loading = true; error = null
                                scope.launch {
                                    when (val r = LastFm.completeAuth(pendingToken)) {
                                        is LastFm.LoginResult.Success -> {
                                            LastFmPreferences.saveSession(ctx, r.username, r.sessionKey)
                                            pendingToken = ""
                                            ScrobbleManager.onSettingsChanged()
                                        }
                                        is LastFm.LoginResult.Failure -> error = r.message
                                    }
                                    loading = false
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            if (loading) "Finishing…" else "I approved it — finish",
                            color = Mascot.color(0xFF121318), fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Color(0xFFFF6B6B), fontSize = 11.5.sp)
        }

        // ---- status line (real values only)
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.25f)).padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("STATUS", color = LastFmRed.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                if (state.flushing) Text("sending…", color = Muted, fontSize = 10.5.sp)
                else if (state.queueSize > 0 && state.active) Icon(
                    Icons.Default.Refresh, "Retry now", tint = Muted,
                    modifier = Modifier.size(22.dp).clip(CircleShape).clickable { ScrobbleManager.retryNow() }.padding(3.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            StatusLine("Last scrobble", if (state.lastScrobbleAt > 0) fmtWhen(state.lastScrobbleAt) else "never")
            StatusLine("Now playing sent", if (state.lastNowPlayingAt > 0) fmtWhen(state.lastNowPlayingAt) else "never")
            StatusLine("Queued", if (state.queueSize == 0) "0 — all sent" else "${state.queueSize} waiting for network / retry")
            StatusLine("Accepted / ignored", "${state.acceptedTotal} / ${state.ignoredTotal}")
            val hard = state.hardError
            val err = state.lastError
            when {
                hard != null -> StatusLine("Stopped", hard, color = Color(0xFFFF6B6B))
                err != null -> StatusLine("Last error", "$err (${fmtWhen(state.lastErrorAt)})", color = Color(0xFFFFB86B))
            }
            if (hard != null || state.queueSize > 0) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Retry now", LastFmRed.copy(alpha = 0.85f)) { ScrobbleManager.retryNow() }
                    if (state.queueSize > 0) SmallButton("Drop queue", Color.White.copy(alpha = 0.10f)) { ScrobbleManager.clearQueue() }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, color: Color = Mascot.color(0xFFE8F4F2)) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, color = Muted, fontSize = 11.5.sp, modifier = Modifier.width(118.dp))
        Text(value, color = color, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SmallButton(label: String, bg: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.3f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Pill(checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(44.dp).height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) accent.copy(alpha = 0.35f) else Mascot.color(0xFF2A1A1E))
            .border(1.dp, if (checked) accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
    ) {
        Box(
            Modifier
                .padding(start = if (checked) 22.dp else 3.dp, top = 3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) accent else Color(0xFF6B4A50))
        )
    }
}

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, placeholder: String, isPassword: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(color = Mascot.color(0xFFE8F4F2), fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(LastFmRed),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text, autoCorrect = false),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = Muted.copy(alpha = 0.85f), fontSize = 13.sp)
                inner()
            }
        )
    }
}

private fun fmtWhen(at: Long): String {
    val diff = System.currentTimeMillis() - at
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L} min ago"
        diff < 86_400_000L -> "${diff / 3_600_000L} h ago"
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(at))
    }
}
