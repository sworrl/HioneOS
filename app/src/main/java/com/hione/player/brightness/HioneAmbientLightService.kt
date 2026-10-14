package com.hione.player.brightness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Camera-Based Intelligent Ambient Light Sensor for Hione OS.
 *
 * The HiBy M500 has no hardware ambient light sensor (ALS). This service uses the
 * rear camera to periodically sample ambient luminance while intelligently filtering
 * false readings caused by:
 * 1. Device resting flat on a desk/table (rear camera flush against surface -> pitch black)
 * 2. Device in pocket or face-down
 * 3. Optical occlusion (zero sensor variance / flush surface blockage)
 *
 * Battery & Performance Safeguards:
 * - Samples 1 single 176×144 YUV frame every [SAMPLE_INTERVAL_MS] (45s).
 * - Camera ISP powers up for <80ms per sample and immediately shuts down.
 * - Accelerometer orientation gates sampling: if the rear lens is pressed against a table
 *   (gravity Z > 8.2 m/s²), the false zero reading is rejected and last valid brightness is held.
 * - Auto-stops whenever the screen is turned off.
 */
object HioneAmbientLightService : SensorEventListener {
    private const val TAG = "HioneAmbientLight"

    /** Sampling interval in milliseconds (45s default). */
    private const val SAMPLE_INTERVAL_MS = 45_000L

    /** Minimum capture resolution — ideal for fast Y-channel luma scan. */
    private const val CAPTURE_WIDTH = 176
    private const val CAPTURE_HEIGHT = 144

    /** Brightness range (0–255 for Settings.System.SCREEN_BRIGHTNESS). */
    private const val MIN_BRIGHTNESS = 15   // ~6% floor so display is always visible
    private const val MAX_BRIGHTNESS = 255
    private const val DEFAULT_INDOOR_BRIGHTNESS = 100 // ~40% sensible fallback

    /** Temporal smoothing factor (higher = smoother transitions). */
    private const val SMOOTHING_ALPHA = 0.35f

    private val isRunning = AtomicBoolean(false)
    private val autoEnabled = AtomicBoolean(false)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null

    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null

    // Orientation state tracking
    @Volatile private var accelZ: Float = 9.8f
    @Volatile private var accelX: Float = 0f
    @Volatile private var accelY: Float = 0f

    /** Smoothed brightness value to prevent jarring jumps. */
    private var smoothedBrightness: Float = -1f
    private var lastValidNonOccludedBrightness: Int = DEFAULT_INDOOR_BRIGHTNESS

    /** Telemetry values for status badges and battery/system observatory. */
    @Volatile var lastLuminance: Int = -1; private set
    @Volatile var lastBrightness: Int = -1; private set
    @Volatile var isOccluded: Boolean = false; private set

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Pause camera sampling while display is off
                    pauseSampling()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Resume sampling if auto-brightness is enabled
                    if (autoEnabled.get()) {
                        resumeSampling(context.applicationContext)
                    }
                }
            }
        }
    }

    /**
     * Enable or disable camera-based auto-brightness.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        autoEnabled.set(enabled)
        val appContext = context.applicationContext
        if (enabled) {
            start(appContext)
        } else {
            stop(appContext)
        }
    }

    fun isEnabled(): Boolean = autoEnabled.get()

    /**
     * Start the ambient light monitoring engine.
     */
    fun start(context: Context) {
        if (!autoEnabled.get()) return
        if (isRunning.getAndSet(true)) return

        val appContext = context.applicationContext
        Log.d(TAG, "Starting intelligent camera-based ambient light sensor")

        // Register accelerometer to detect table-flat orientation
        sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        accelSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Register screen state receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            appContext.registerReceiver(screenReceiver, filter)
        } catch (_: Throwable) {}

        startCameraWorker(appContext)
    }

    private fun startCameraWorker(context: Context) {
        if (cameraThread != null) return
        cameraThread = HandlerThread("HioneAmbientLight").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        cameraHandler?.post { sampleLoop(context) }
    }

    private fun pauseSampling() {
        Log.d(TAG, "Screen off: pausing ambient sampling")
        try { captureSession?.close() } catch (_: Throwable) {}
        try { cameraDevice?.close() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { cameraThread?.quitSafely() } catch (_: Throwable) {}
        captureSession = null
        cameraDevice = null
        imageReader = null
        cameraThread = null
        cameraHandler = null
    }

    private fun resumeSampling(context: Context) {
        Log.d(TAG, "Screen on: resuming ambient sampling")
        startCameraWorker(context)
    }

    /**
     * Stop sampling and release all hardware resources.
     */
    fun stop(context: Context) {
        Log.d(TAG, "Stopping camera-based ambient light sensor")
        isRunning.set(false)

        try { sensorManager?.unregisterListener(this) } catch (_: Throwable) {}
        try { context.applicationContext.unregisterReceiver(screenReceiver) } catch (_: Throwable) {}

        pauseSampling()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER || event.sensor.type == Sensor.TYPE_GRAVITY) {
            accelX = event.values[0]
            accelY = event.values[1]
            accelZ = event.values[2]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Determines whether the rear camera is physically resting flush against a desk or flat surface.
     * When resting screen-up on a table:
     * - Gravity Z is ~ +9.8 m/s² (pointing out of screen)
     * - Gravity X and Y are near 0 (within ±2.5 m/s²)
     */
    private fun isRestingFlatOnBack(): Boolean {
        return accelZ > 8.0f && abs(accelX) < 2.8f && abs(accelY) < 2.8f
    }

    private fun isRestingFaceDown(): Boolean {
        return accelZ < -8.0f && abs(accelX) < 2.8f && abs(accelY) < 2.8f
    }

    /**
     * Periodic sampling execution.
     */
    private fun sampleLoop(context: Context) {
        if (!isRunning.get() || !autoEnabled.get()) {
            pauseSampling()
            return
        }

        try {
            captureAndMeasure(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Ambient light sample loop error", t)
        }

        cameraHandler?.postDelayed({ sampleLoop(context) }, SAMPLE_INTERVAL_MS)
    }

    private fun captureAndMeasure(context: Context) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull() ?: run {
            Log.w(TAG, "No camera device found for ambient measurement")
            return
        }

        imageReader?.close()
        imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, ImageFormat.YUV_420_888, 2)

        val handler = cameraHandler ?: return

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    captureSingleFrame(context, camera, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, handler)
        } catch (se: SecurityException) {
            Log.e(TAG, "Camera permission missing", se)
            autoEnabled.set(false)
            pauseSampling()
        }
    }

    private fun captureSingleFrame(context: Context, camera: CameraDevice, handler: Handler) {
        val reader = imageReader ?: return

        reader.setOnImageAvailableListener({ imgReader ->
            val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val stats = analyzeLumaFrame(image)
                val rawLuma = stats.meanLuma
                val stdDev = stats.stdDev

                // Check for physical desk occlusion or flush blockage:
                // 1. Device is lying flat on back (Z > 8.0) AND luma is near zero (< 14)
                // 2. Spatial standard deviation across the frame is near zero (uniform pitch black surface contact)
                val flatOnBack = isRestingFlatOnBack()
                val lensBlocked = (rawLuma < 14 && stdDev < 3.0f) || (flatOnBack && rawLuma < 18)

                isOccluded = lensBlocked
                lastLuminance = rawLuma

                val targetBrightness = if (lensBlocked) {
                    Log.d(TAG, "Rear camera is occluded (on desk / blocked, Z=$accelZ, luma=$rawLuma). Preserving last valid brightness: $lastValidNonOccludedBrightness")
                    lastValidNonOccludedBrightness
                } else {
                    val computed = luminanceToBrightness(rawLuma)
                    lastValidNonOccludedBrightness = computed
                    computed
                }

                applyBrightness(context, targetBrightness)
            } finally {
                image.close()
            }

            // Immediately tear down session to free camera hardware
            try { captureSession?.close() } catch (_: Throwable) {}
            try { camera.close() } catch (_: Throwable) {}
            captureSession = null
            cameraDevice = null
        }, handler)

        try {
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                            }
                            session.capture(captureRequest.build(), null, handler)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Capture request failed", t)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera session configuration failed")
                    }
                },
                handler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createCaptureSession failed", t)
        }
    }

    private data class LumaStats(val meanLuma: Int, val stdDev: Float)

    /**
     * Compute both mean luminance and spatial standard deviation across the Y-plane.
     * Optical blockage against a flat table exhibits near-zero standard deviation (< 2.5).
     */
    private fun analyzeLumaFrame(image: Image): LumaStats {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val width = image.width
        val height = image.height

        var totalLuma = 0L
        var sampleCount = 0
        val samples = ArrayList<Int>(1600)

        for (row in 0 until height step 4) {
            val rowOffset = row * rowStride
            for (col in 0 until width step 4) {
                val idx = rowOffset + col * pixelStride
                if (idx < yBuffer.capacity()) {
                    val luma = yBuffer.get(idx).toInt() and 0xFF
                    totalLuma += luma
                    samples.add(luma)
                    sampleCount++
                }
            }
        }

        if (sampleCount == 0) return LumaStats(128, 10f)

        val mean = (totalLuma / sampleCount).toInt()
        var varianceSum = 0.0
        for (luma in samples) {
            val diff = luma - mean
            varianceSum += diff * diff
        }
        val stdDev = sqrt(varianceSum / sampleCount).toFloat()

        return LumaStats(mean, stdDev)
    }

    private fun luminanceToBrightness(luminance: Int): Int {
        val normalized = luminance.coerceIn(0, 255) / 255.0f
        val perceptual = sqrt(normalized.toDouble()).toFloat()
        val raw = MIN_BRIGHTNESS + (perceptual * (MAX_BRIGHTNESS - MIN_BRIGHTNESS))

        val target = if (smoothedBrightness < 0) {
            raw
        } else {
            smoothedBrightness + SMOOTHING_ALPHA * (raw - smoothedBrightness)
        }
        smoothedBrightness = target

        return target.roundToInt().coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
    }

    private fun applyBrightness(context: Context, brightness: Int) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            lastBrightness = brightness
            Log.d(TAG, "Ambient update: luma=$lastLuminance (occluded=$isOccluded) -> brightness=$brightness (${(brightness * 100 / 255)}%)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write system brightness", t)
        }
    }
}
