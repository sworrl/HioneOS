package com.hione.player

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hione Hardware Button Press Gesture Engine & Textured Tactile Feedback.
 *
 * Supported Gestures:
 * 1. [HOLD POWER + DOUBLE-PRESS NEXT (or PLAY)] -> Like / Toggle Favorite for the currently playing song!
 * 2. [HOLD POWER + DOUBLE-PRESS PREV] -> Dislike / Remove from Favorites.
 * 3. [HOLD PLAY + DOUBLE-PRESS NEXT] -> Jump to Next Album / Playlist.
 *
 * Tactile Feedback:
 * - Emits a custom textured "bumpy but good" micro-waveform vibration feeling like mechanical
 *   gear teeth / textured notches clicking smoothly under fingers.
 */
object HioneHardwareGestureEngine {
    private const val TAG = "HardwareGestureEngine"

    // Time window for double-press detection (ms)
    private const val DOUBLE_PRESS_WINDOW_MS = 480L
    // Maximum time since Power press to consider Power "Held / Chorded" (ms)
    private const val POWER_CHORD_WINDOW_MS = 1400L

    private var isPowerDown = false
    private var powerDownTimestamp = 0L

    private var isPlayDown = false
    private var playDownTimestamp = 0L

    private var lastNextPressTimestamp = 0L
    private var nextPressCount = 0

    private var lastPlayPressTimestamp = 0L
    private var playPressCount = 0

    private var lastPrevPressTimestamp = 0L
    private var prevPressCount = 0

    // Heart Toast State
    data class HeartToastState(
        val visible: Boolean = false,
        val isLiked: Boolean = true,
        val trackTitle: String = "",
        val trackArtist: String = ""
    )

    private val _heartToast = MutableStateFlow(HeartToastState())
    val heartToast: StateFlow<HeartToastState> = _heartToast.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideToastRunnable: Runnable? = null

    /**
     * Intercepts KeyDown events for chorded gestures.
     * Returns TRUE if the gesture was consumed and standard action should be aborted.
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent?, context: Context): Boolean {
        val now = SystemClock.uptimeMillis()

        when (keyCode) {
            KeyEvent.KEYCODE_POWER -> {
                isPowerDown = true
                powerDownTimestamp = now
                return false // Don't block initial power press unless chord succeeds
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                isPlayDown = true
                playDownTimestamp = now

                // Check for Power + Double-Press Play chord
                val isPowerChorded = isPowerDown || (now - powerDownTimestamp < POWER_CHORD_WINDOW_MS)
                if (isPowerChorded) {
                    if (now - lastPlayPressTimestamp < DOUBLE_PRESS_WINDOW_MS) {
                        playPressCount++
                    } else {
                        playPressCount = 1
                    }
                    lastPlayPressTimestamp = now

                    if (playPressCount >= 2) {
                        playPressCount = 0
                        triggerLikeGesture(context)
                        return true // Consumed! Prevents pause/play toggle
                    }
                }
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                val isPowerChorded = isPowerDown || (now - powerDownTimestamp < POWER_CHORD_WINDOW_MS)
                val isPlayChorded = isPlayDown || (now - playDownTimestamp < POWER_CHORD_WINDOW_MS)

                if (isPowerChorded) {
                    if (now - lastNextPressTimestamp < DOUBLE_PRESS_WINDOW_MS) {
                        nextPressCount++
                    } else {
                        nextPressCount = 1
                    }
                    lastNextPressTimestamp = now

                    if (nextPressCount >= 2) {
                        nextPressCount = 0
                        triggerLikeGesture(context)
                        return true // Consumed! Prevents track skipping
                    }
                } else if (isPlayChorded) {
                    // Hold Play + Double Next = Next Album
                    if (now - lastNextPressTimestamp < DOUBLE_PRESS_WINDOW_MS) {
                        nextPressCount++
                    } else {
                        nextPressCount = 1
                    }
                    lastNextPressTimestamp = now

                    if (nextPressCount >= 2) {
                        nextPressCount = 0
                        triggerNextAlbumGesture(context)
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val isPowerChorded = isPowerDown || (now - powerDownTimestamp < POWER_CHORD_WINDOW_MS)
                if (isPowerChorded) {
                    if (now - lastPrevPressTimestamp < DOUBLE_PRESS_WINDOW_MS) {
                        prevPressCount++
                    } else {
                        prevPressCount = 1
                    }
                    lastPrevPressTimestamp = now

                    if (prevPressCount >= 2) {
                        prevPressCount = 0
                        triggerDislikeGesture(context)
                        return true
                    }
                }
            }
        }

        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_POWER -> isPowerDown = false
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY -> isPlayDown = false
        }
        return false
    }

    /**
     * Executes the Like / Favorite toggle on the currently playing song with textured haptics.
     */
    private fun triggerLikeGesture(context: Context) {
        val player = PlayerHolder.ensure(context)
        val currentItem = player.currentMediaItem
        val mediaId = currentItem?.mediaId?.toLongOrNull()
        val title = currentItem?.mediaMetadata?.title?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: try { android.provider.Settings.Global.getString(context.contentResolver, "hione_now_playing_title")?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }
        // NO fabricated artist. This fell back to the literal "Oki Hione" for any track with no
        // artist tag, and the branch below then called LikeStore.toggleArtist with it: a like
        // gesture on a tag-less file or a third-party stream PERSISTED a like against her name.
        // Same defect as the lockscreen attribution bug fixed in 2.0.263, different call site.
        val artist = currentItem?.mediaMetadata?.artist?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: try { android.provider.Settings.Global.getString(context.contentResolver, "hione_now_playing_artist")?.takeIf { it.isNotBlank() } } catch (_: Throwable) { null }

        val isNowLiked = when {
            mediaId != null -> LikeStore.toggle(context, mediaId)
            // Not a raw numeric id: an artist-level toggle is the only thing LikeStore can do, and
            // it needs a real artist. With none, there is nothing to like — report the current
            // state unchanged rather than inventing a target.
            artist != null -> LikeStore.toggleArtist(context, artist)
            else -> {
                Log.w(TAG, "like gesture ignored: no media id and no artist to attribute it to")
                false
            }
        }

        // Bumpy Tactile Feedback
        playBumpyLikeTexture(context)


        // Display Floating HUD
        showHeartToast(isLiked = isNowLiked, title = title, artist = artist)
        Log.i(TAG, "Hardware Gesture Triggered: LIKED=$isNowLiked for '$title' by '$artist'")
    }

    /**
     * Executes Dislike / Remove from Favorites gesture.
     */
    private fun triggerDislikeGesture(context: Context) {
        val player = PlayerHolder.ensure(context)
        val currentItem = player.currentMediaItem
        val mediaId = currentItem?.mediaId?.toLongOrNull()
        // See [triggerLikeGesture]: no invented title, no invented artist.
        val title = currentItem?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
        val artist = currentItem?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() }

        if (mediaId != null && LikeStore.isLiked(mediaId)) {
            LikeStore.toggle(context, mediaId)
        }

        playBumpyDislikeTexture(context)
        showHeartToast(isLiked = false, title = title, artist = artist)
    }

    private fun triggerNextAlbumGesture(context: Context) {
        playRatchetTick(context)
        // Seek to next track
        val player = PlayerHolder.ensure(context)
        player.seekToNextMediaItem()
    }

    /**
     * Like / unlike / gear-gesture feedback. These used to be amplitude textures, which the M500's
     * on/off motor cannot play (the ratchet one never fired at all), so they now map onto the
     * shared [Haptics] pulses: like = heavy, unlike = tick, gear gesture = click.
     */
    fun playBumpyLikeTexture(context: Context) = Haptics.heavy(context)

    fun playBumpyDislikeTexture(context: Context) = Haptics.tick(context)

    fun playRatchetTick(context: Context) = Haptics.click(context)

    /**
     * [title] and [artist] are nullable because "not tagged" is a real state and the callers used
     * to paper over it with the literals "Current Track" and "Oki Hione". The HUD renders the
     * absence instead of a name that was never in the file.
     */
    private fun showHeartToast(isLiked: Boolean, title: String?, artist: String?) {
        _heartToast.value = HeartToastState(
            visible = true,
            isLiked = isLiked,
            trackTitle = title ?: "Unknown title",
            trackArtist = artist ?: "Unknown artist"
        )
        hideToastRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { _heartToast.value = _heartToast.value.copy(visible = false) }
        hideToastRunnable = r
        mainHandler.postDelayed(r, 2200L)
    }
}

/**
 * Animated Cyber Heart Floating Toast Overlay.
 */
@Composable
fun HioneHeartGestureHudOverlay(modifier: Modifier = Modifier) {
    val toastState by HioneHardwareGestureEngine.heartToast.collectAsState()

    val scaleAnim = remember { Animatable(0.8f) }
    LaunchedEffect(toastState.visible) {
        if (toastState.visible) {
            scaleAnim.snapTo(0.75f)
            scaleAnim.animateTo(1.0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }

    AnimatedVisibility(
        visible = toastState.visible,
        enter = fadeIn(tween(140)) + scaleIn(tween(160, easing = LinearOutSlowInEasing), initialScale = 0.8f),
        exit = fadeOut(tween(220)) + scaleOut(tween(220, easing = FastOutLinearInEasing), targetScale = 0.85f),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 90.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .scale(scaleAnim.value)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            if (toastState.isLiked) listOf(Mascot.color(0xF02A0015), Mascot.color(0xF5100008))
                            else listOf(Mascot.color(0xE6081A24), Mascot.color(0xF2030D13))
                        )
                    )
                    .border(
                        1.5.dp,
                        Brush.horizontalGradient(
                            if (toastState.isLiked) listOf(Mascot.color(0xFFFF2277), Mascot.color(0xFFFF88AA))
                            else listOf(Mascot.color(0xFFFFAB2E), Color(0x66FFFFFF))
                        ),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (toastState.isLiked) Mascot.color(0x33FF2277) else Mascot.color(0x22FFAB2E)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (toastState.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (toastState.isLiked) Mascot.color(0xFFFF2277) else Mascot.color(0xFFFFAB2E),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(modifier = Modifier.widthIn(max = 240.dp)) {
                        Text(
                            text = if (toastState.isLiked) "ADDED TO LIKED SONGS" else "REMOVED FROM LIKED SONGS",
                            color = if (toastState.isLiked) Mascot.color(0xFFFF5599) else Mascot.color(0xFFFFE0B0),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = AudiowideFont,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = toastState.trackTitle,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = toastState.trackArtist,
                            color = Mascot.color(0xFFB0D0D8),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
