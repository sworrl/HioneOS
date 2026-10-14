package com.hione.media.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** One physical camera as CameraX reports it, with a human label for the switch button. */
data class CamEntry(val id: String, val facing: Int?, val info: CameraInfo) {
    val label: String
        get() = when (facing) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Camera $id"
        }
}

/**
 * Owns the CameraX use cases. Kept out of the composable so binding, capture and recording are
 * plain method calls and the UI only reads the observable fields.
 *
 * Built to survive odd hardware. The M500's camera stack is a BSP leftover: it may report one
 * camera, a camera with no facing CameraX expects, no flash, no autofocus, or a sensor that
 * cannot run Preview + ImageCapture at 4:3. So the camera list is whatever CameraX enumerates
 * (never DEFAULT_BACK_CAMERA, which throws when there is no back-facing lens), each feature is
 * probed before its button is shown, and a failed bind falls back to preview only with the
 * reason on screen instead of a crash.
 */
class CameraEngine(private val ctx: Context) {
    private val tag = "HioneCamera"
    private val main = ContextCompat.getMainExecutor(ctx)

    var provider: ProcessCameraProvider? = null
        private set
    var cameras by mutableStateOf<List<CamEntry>>(emptyList())
        private set
    var camIndex by mutableStateOf(0)
        private set
    var camera by mutableStateOf<Camera?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var hasFlash by mutableStateOf(false)
        private set
    var videoSupported by mutableStateOf(true)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var minZoom by mutableFloatStateOf(1f)
        private set
    var maxZoom by mutableFloatStateOf(1f)
        private set

    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var rotation = android.view.Surface.ROTATION_0

    suspend fun init(): Boolean {
        val p = runCatching {
            suspendCoroutine<ProcessCameraProvider> { cont ->
                val f = ProcessCameraProvider.getInstance(ctx)
                f.addListener({
                    try { cont.resume(f.get()) } catch (t: Throwable) { cont.resumeWith(Result.failure(t)) }
                }, main)
            }
        }.onFailure { Log.w(tag, "CameraX init failed", it) }.getOrNull()
        if (p == null) {
            error = "The camera service did not start."
            return false
        }
        provider = p
        cameras = enumerate(p)
        if (cameras.isEmpty()) {
            error = "No camera was found on this device."
            return false
        }
        camIndex = cameras.indexOfFirst { it.facing == CameraCharacteristics.LENS_FACING_BACK }.takeIf { it >= 0 } ?: 0
        return true
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun enumerate(p: ProcessCameraProvider): List<CamEntry> =
        runCatching {
            p.availableCameraInfos.mapNotNull { info ->
                runCatching {
                    val c2 = Camera2CameraInfo.from(info)
                    CamEntry(c2.cameraId, c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING), info)
                }.getOrNull()
            }.sortedBy {
                when (it.facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> 0
                    CameraCharacteristics.LENS_FACING_FRONT -> 1
                    else -> 2
                }
            }
        }.getOrDefault(emptyList())

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorFor(entry: CamEntry): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { runCatching { Camera2CameraInfo.from(it).cameraId == entry.id }.getOrDefault(false) } }
            .build()

    val current: CamEntry? get() = cameras.getOrNull(camIndex)
    val isFront: Boolean get() = current?.facing == CameraCharacteristics.LENS_FACING_FRONT

    fun nextCamera() {
        if (cameras.size > 1) camIndex = (camIndex + 1) % cameras.size
    }

    /**
     * Bind the use cases for [video] or photo mode. Photo is 4:3 (the sensor's native shape, so
     * no resolution is thrown away); video is 16:9. Preview uses the same ratio so the
     * viewfinder shows exactly what will be saved.
     */
    fun bind(owner: LifecycleOwner, view: PreviewView, video: Boolean, lowQualityVideo: Boolean = false, flashMode: Int = ImageCapture.FLASH_MODE_OFF) {
        val p = provider ?: return
        val entry = current ?: return
        error = null
        val ratio = if (video) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val res = ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build()

        val pv = Preview.Builder().setResolutionSelector(res).build().also { it.setSurfaceProvider(view.surfaceProvider) }
        preview = pv
        val useCases = mutableListOf<UseCase>(pv)
        imageCapture = null
        videoCapture = null

        if (video) {
            val caps = runCatching { Recorder.getVideoCapabilities(entry.info).getSupportedQualities(DynamicRange.SDR) }.getOrDefault(emptyList())
            videoSupported = caps.isNotEmpty()
            if (videoSupported) {
                val qs = if (lowQualityVideo) QualitySelector.from(Quality.LOWEST)
                else QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
                val rec = Recorder.Builder().setQualitySelector(qs).build()
                videoCapture = VideoCapture.withOutput(rec).also { it.targetRotation = rotation; useCases += it }
            }
        } else {
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(res)
                .setFlashMode(flashMode)
                .setTargetRotation(rotation)
                .build()
                .also { useCases += it }
        }

        p.unbindAll()
        val cam = runCatching { p.bindToLifecycle(owner, selectorFor(entry), *useCases.toTypedArray()) }
            .recoverCatching {
                Log.w(tag, "bind with capture failed, falling back to preview only", it)
                imageCapture = null; videoCapture = null
                p.unbindAll()
                val c = p.bindToLifecycle(owner, selectorFor(entry), pv)
                error = if (video) "Video recording is not supported by this camera." else "Photo capture is not supported by this camera."
                c
            }
            .onFailure {
                Log.e(tag, "bind failed", it)
                error = "Could not open the camera. ${it.message ?: ""}".trim()
            }
            .getOrNull()
        camera = cam
        hasFlash = cam?.cameraInfo?.hasFlashUnit() == true
        cam?.cameraInfo?.zoomState?.value?.let {
            minZoom = it.minZoomRatio; maxZoom = it.maxZoomRatio; zoom = it.zoomRatio
        } ?: run { minZoom = 1f; maxZoom = 1f; zoom = 1f }
    }

    fun unbind() {
        runCatching { provider?.unbindAll() }
        camera = null
    }

    fun setRotation(r: Int) {
        rotation = r
        imageCapture?.targetRotation = r
        videoCapture?.targetRotation = r
    }

    fun setFlash(mode: Int) { imageCapture?.flashMode = mode }

    fun setTorch(on: Boolean) { if (hasFlash) camera?.cameraControl?.enableTorch(on) }

    fun applyZoom(r: Float) {
        val c = r.coerceIn(minZoom, maxZoom)
        zoom = c
        camera?.cameraControl?.setZoomRatio(c)
    }

    /** [onLocked] fires only when autofocus actually reports a lock, never for metering alone. */
    fun focusAt(view: PreviewView, x: Float, y: Float, onLocked: () -> Unit = {}) {
        val cam = camera ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        // Fixed-focus sensors reject AF; metering alone still helps exposure, so try both.
        if (cam.cameraInfo.isFocusMeteringSupported(action)) {
            val f = cam.cameraControl.startFocusAndMetering(action)
            f.addListener({
                if (runCatching { f.get().isFocusSuccessful }.getOrDefault(false)) onLocked()
            }, main)
        } else {
            val ae = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE).build()
            if (cam.cameraInfo.isFocusMeteringSupported(ae)) cam.cameraControl.startFocusAndMetering(ae)
        }
    }

    val canCapturePhoto get() = imageCapture != null
    val canRecord get() = videoCapture != null

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Save a photo straight into DCIM/Camera. CameraX handles the pending flag and EXIF. */
    suspend fun takePhotoToGallery(): Uri? {
        val ic = imageCapture ?: return null
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
        ).setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = isFront }).build()
        return capture(ic, opts)?.savedUri
    }

    /** Capture to a private file for the IMAGE_CAPTURE review step. */
    suspend fun takePhotoToFile(file: File): Boolean {
        val ic = imageCapture ?: return false
        val opts = ImageCapture.OutputFileOptions.Builder(file)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = isFront }).build()
        return capture(ic, opts) != null
    }

    private suspend fun capture(ic: ImageCapture, opts: ImageCapture.OutputFileOptions): ImageCapture.OutputFileResults? =
        suspendCoroutine { cont ->
            ic.takePicture(opts, main, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) = cont.resume(r)
                override fun onError(e: ImageCaptureException) {
                    Log.w(tag, "capture failed", e)
                    error = "Could not take the photo."
                    cont.resume(null)
                }
            })
        }

    /**
     * Start recording. [target] is the caller's EXTRA_OUTPUT for VIDEO_CAPTURE; null saves a new
     * clip in DCIM/Camera. Limits of 0 mean none. [onEvent] gets every recorder event.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        target: Uri?,
        withAudio: Boolean,
        durationLimitMs: Long,
        sizeLimit: Long,
        onEvent: (VideoRecordEvent) -> Unit
    ): Boolean {
        val vc = videoCapture ?: return false
        val pending = if (target != null) {
            val pfd: ParcelFileDescriptor = runCatching { ctx.contentResolver.openFileDescriptor(target, "rwt") }.getOrNull()
                ?: runCatching { ctx.contentResolver.openFileDescriptor(target, "w") }.getOrNull()
                ?: return false
            outputPfd = pfd
            val b = FileDescriptorOutputOptions.Builder(pfd)
            if (durationLimitMs > 0) b.setDurationLimitMillis(durationLimitMs)
            if (sizeLimit > 0) b.setFileSizeLimit(sizeLimit)
            vc.output.prepareRecording(ctx, b.build())
        } else {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${stamp()}")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            }
            val b = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv)
            if (durationLimitMs > 0) b.setDurationLimitMillis(durationLimitMs)
            if (sizeLimit > 0) b.setFileSizeLimit(sizeLimit)
            vc.output.prepareRecording(ctx, b.build())
        }
        val withA = if (withAudio) pending.withAudioEnabled() else pending
        recording = runCatching {
            withA.start(main) { ev ->
                // The recorder dups the caller's descriptor; ours is ours to close once done.
                if (ev is VideoRecordEvent.Finalize) { runCatching { outputPfd?.close() }; outputPfd = null }
                onEvent(ev)
            }
        }
            .onFailure { Log.w(tag, "record start failed", it); error = "Could not start recording." }
            .getOrNull()
        return recording != null
    }

    fun pauseRecording() = recording?.pause()
    fun resumeRecording() = recording?.resume()
    fun stopRecording() {
        recording?.stop()
        recording = null
    }
    val isRecording get() = recording != null
}
