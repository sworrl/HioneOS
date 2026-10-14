package com.hione.player.screentime

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.hione.player.IdleController
import com.hione.player.PlayerHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import kotlin.math.*

/**
 * Smart ScreenTime & Attention Sensing Engine for HiBy M500 DAP.
 *
 * Uses multi-sensor fusion (Accelerometer orientation, Hand micro-tremor variance,
 * Proximity sensor, Ambient Light, Foreground UI engagement, and Audio playback)
 * to predict if the user is actively looking at the screen:
 *
 * 1. Viewing Posture: Pitch (12°–80°) & Roll (±40°) orientation angles.
 * 2. Physiological Micro-Jitter: Standard deviation of hand acceleration (0.02–0.45 m/s²).
 * 3. Proximity & Pocket Guard: Near-distance and upside-down pitch suppress wake.
 * 4. App Engagement: Now Playing, Lyrics, Tape Deck, and Live Visualizer boost attention.
 * 5. Adaptive Screen Poke: Extends screen awake seamlessly when P(Looking) >= 0.52.
 */
@android.annotation.SuppressLint("StaticFieldLeak")
object HioneSmartScreenTimeEngine : SensorEventListener {
    private const val TAG = "HioneSmartScreenTime"

    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null
    private var proxSensor: Sensor? = null
    private var lightSensor: Sensor? = null

    private var isMonitoring = false
    private var currentActivity: Activity? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Telemetry & State
    data class AttentionMetrics(
        val pitchDeg: Float = 0f,
        val rollDeg: Float = 0f,
        val handJitterVariance: Float = 0f,
        val isHandheld: Boolean = false,
        val isViewingAngle: Boolean = false,
        val isPocketed: Boolean = false,
        val appEngagementScore: Float = 0.5f,
        val lookingProbability: Float = 0.5f,
        val isScreenKeptAwake: Boolean = false
    )

    private val _metrics = MutableStateFlow(AttentionMetrics())
    val metrics: StateFlow<AttentionMetrics> = _metrics

    // Rolling window for accelerometer variance (last 20 samples ~1.0s)
    private val accelHistory = ArrayDeque<Float>(20)
    @Volatile private var rawX = 0f
    @Volatile private var rawY = 0f
    @Volatile private var rawZ = 9.8f
    @Volatile private var isProximityNear = false
    @Volatile private var ambientLux = 50f

    // Configurable weighting factors
    var isEnabled: Boolean = true
    @Volatile var isAudioPlaying: Boolean = false
    @Volatile var isVisualizerOrLyricsActive: Boolean = false
    @Volatile var isNowPlayingOrTapeActive: Boolean = false

    private var evaluationJob: Job? = null

    fun init(context: Context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = sm
        accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        proxSensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        lightSensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    }

    fun start(activity: Activity) {
        currentActivity = activity
        if (!isEnabled || isMonitoring) return
        val sm = sensorManager ?: return

        try {
            accelSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            proxSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            lightSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            isMonitoring = true
            startEvaluationLoop()
            Log.d(TAG, "Smart ScreenTime attention sensing started")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed registering attention sensors: ${t.message}")
        }
    }

    fun stop() {
        if (!isMonitoring) return
        try {
            sensorManager?.unregisterListener(this)
            evaluationJob?.cancel()
            evaluationJob = null
            isMonitoring = false
            currentActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            currentActivity = null
            Log.d(TAG, "Smart ScreenTime attention sensing stopped")
        } catch (_: Throwable) {}
    }

    private fun startEvaluationLoop() {
        evaluationJob?.cancel()
        evaluationJob = scope.launch {
            while (isActive) {
                evaluateAttention()
                delay(800L) // 1.25 Hz evaluation cadence
            }
        }
    }

    private fun evaluateAttention() {
        val x = rawX
        val y = rawY
        val z = rawZ

        // 1. Calculate Spatial Angles
        // Pitch: Angle along Y-axis (tilt back / forward)
        val pitch = (atan2(-y.toDouble(), sqrt(x * x + z * z).toDouble()) * (180.0 / Math.PI)).toFloat()
        // Roll: Angle along X-axis (side to side tilt)
        val roll = (atan2(x.toDouble(), z.toDouble()) * (180.0 / Math.PI)).toFloat()

        // 2. Handheld Micro-Tremor Variance Calculation
        val totalAcc = sqrt(x * x + y * y + z * z)
        synchronized(accelHistory) {
            if (accelHistory.size >= 20) accelHistory.removeFirst()
            accelHistory.addLast(totalAcc)
        }
        val historyList = synchronized(accelHistory) { accelHistory.toList() }
        val mean = if (historyList.isNotEmpty()) historyList.average().toFloat() else 9.8f
        val variance = if (historyList.size > 2) {
            historyList.map { (it - mean) * (it - mean) }.average().toFloat()
        } else 0f

        // 3. Posture & Viewing Angle Scoring
        val isViewingAngle = pitch in 12f..82f && abs(roll) <= 42f && z > -1.0f
        val postureScore = when {
            isViewingAngle -> 1.0f
            pitch in 5f..88f && abs(roll) <= 60f -> 0.7f
            z > 8.8f && abs(pitch) < 10f -> 0.25f // Laying flat on table
            z < -3.5f || pitch < -20f -> 0.0f     // Face down or upside down
            else -> 0.35f
        }

        // 4. Handheld Micro-Motion Scoring
        // Human holding DAP creates natural tremor variance between 0.0025 and 0.45
        val isHandheld = variance in 0.0025f..0.45f
        val handheldScore = when {
            isHandheld -> 1.0f
            variance < 0.001f -> 0.15f // Dead rigid stillness (table/stand)
            variance > 1.2f -> 0.60f   // Walking/jogging motion
            else -> 0.4f
        }

        // 5. Pocket & Coverage Detection
        val isPocketed = isProximityNear || (ambientLux < 2f && (pitch < -18f || z < -4.0f))

        // 6. App Engagement Scoring
        val appScore = when {
            isVisualizerOrLyricsActive -> 1.0f
            isNowPlayingOrTapeActive -> 0.90f
            else -> 0.65f
        }

        // 7. Audio Playback Status
        val act = currentActivity
        val isPlaying = if (act != null) {
            val am = act.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isMusicActive == true || isAudioPlaying
        } else false
        val audioScore = if (isPlaying) 1.0f else 0.40f

        // 8. Fusion Probability Calculation
        val pLooking = if (isPocketed) {
            0.0f
        } else {
            (0.35f * postureScore + 0.25f * handheldScore + 0.25f * appScore + 0.15f * audioScore).coerceIn(0.0f, 1.0f)
        }

        val shouldKeepAwake = pLooking >= 0.52f

        _metrics.value = AttentionMetrics(
            pitchDeg = pitch,
            rollDeg = roll,
            handJitterVariance = variance,
            isHandheld = isHandheld,
            isViewingAngle = isViewingAngle,
            isPocketed = isPocketed,
            appEngagementScore = appScore,
            lookingProbability = pLooking,
            isScreenKeptAwake = shouldKeepAwake
        )

        // Apply Adaptive Screen Poke
        act?.runOnUiThread {
            if (shouldKeepAwake) {
                // Keep screen awake while user is actively looking
                IdleController.poke(act)
                act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                // Allow standard idle dim and timeout
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY -> {
                rawX = event.values[0]
                rawY = event.values[1]
                rawZ = event.values[2]
            }
            Sensor.TYPE_PROXIMITY -> {
                val dist = event.values[0]
                val max = event.sensor.maximumRange
                isProximityNear = dist < min(max, 3.0f)
            }
            Sensor.TYPE_LIGHT -> {
                ambientLux = event.values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
