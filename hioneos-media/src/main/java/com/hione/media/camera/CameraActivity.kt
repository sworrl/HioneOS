package com.hione.media.camera

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hione.media.ui.HioneTheme
import kotlinx.coroutines.flow.MutableSharedFlow

/** What the activity was asked to do, parsed once from the launching intent. */
sealed interface CaptureMode {
    /** Launcher / STILL_IMAGE_CAMERA / VIDEO_CAMERA: a normal camera that saves to DCIM/Camera. */
    data class Free(val startInVideo: Boolean) : CaptureMode

    /** IMAGE_CAPTURE(_SECURE): one photo, reviewed, then handed back to the caller. */
    data class Image(val output: Uri?) : CaptureMode

    /** VIDEO_CAPTURE: one clip with the caller's limits, reviewed, then handed back. */
    data class Video(val output: Uri?, val durationLimitS: Int, val sizeLimit: Long, val lowQuality: Boolean) : CaptureMode
}

open class CameraActivity : ComponentActivity() {

    /** Volume keys and a hardware camera key act as the shutter, like on stock cameras. */
    val shutterKeys = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    protected open val secure: Boolean get() = false

    private var screenOff: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Viewfinder is full-bleed; hide the system bars so the 640dp of height goes to the photo.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (secure) {
            // A lock-screen camera must not outlive the screen going off, or it would still be
            // sitting over the keyguard the next time the screen wakes.
            screenOff = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { finish() }
            }.also { registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED) }
        }

        val mode = parseMode(intent)
        setContent {
            HioneTheme {
                CameraScreen(
                    activity = this,
                    mode = mode,
                    secure = secure,
                    onImageResult = ::returnImage,
                    onVideoResult = ::returnVideo
                )
            }
        }
    }

    override fun onDestroy() {
        screenOff?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_CAMERA)) {
            shutterKeys.tryEmit(Unit)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun parseMode(i: Intent): CaptureMode {
        val output: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
        return when (i.action) {
            MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.ACTION_IMAGE_CAPTURE_SECURE -> CaptureMode.Image(output)
            MediaStore.ACTION_VIDEO_CAPTURE -> CaptureMode.Video(
                output = output,
                durationLimitS = i.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, 0),
                sizeLimit = i.getLongExtra(MediaStore.EXTRA_SIZE_LIMIT, 0L).let { if (it == 0L) i.getIntExtra(MediaStore.EXTRA_SIZE_LIMIT, 0).toLong() else it },
                // EXTRA_VIDEO_QUALITY: 0 = low (MMS-sized), 1 = high. Default is high.
                lowQuality = i.getIntExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1) == 0
            )
            MediaStore.INTENT_ACTION_VIDEO_CAMERA -> CaptureMode.Free(startInVideo = true)
            else -> CaptureMode.Free(startInVideo = false)
        }
    }

    /**
     * IMAGE_CAPTURE result. With EXTRA_OUTPUT the photo has already been written there and the
     * contract says data may be empty; without it, the caller gets a small bitmap in "data"
     * (the "inline-data" action is what Gallery2's camera and Snapcam used, and some callers
     * check for it).
     */
    private fun returnImage(output: Uri?, thumb: Bitmap?) {
        val data = if (output != null) Intent().setData(output) else Intent("inline-data").putExtra("data", thumb)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private fun returnVideo(uri: Uri) {
        val data = Intent().setData(uri).apply {
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }
}

/** STILL_IMAGE_CAMERA_SECURE / IMAGE_CAPTURE_SECURE, launched over the lock screen. */
class SecureCameraActivity : CameraActivity() {
    override val secure: Boolean get() = true
}
