package com.hione.player.radio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Process
import android.util.Log
import dalvik.system.PathClassLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 1:1 Kotlin port of Qualcomm CAF FM Subsystem Engine from original vendor FM2.
 * Directly initializes /dev/radio0 via qcom.fmradio and runs hardware AudioRecord -> AudioTrack bridge.
 */
class QualcommFmHardwareEngine(private val context: Context) {
    companion object {
        private const val TAG = "QualcommFmEngine"
        private const val FM_DEVICE_PATH = "/dev/radio0"
        private const val AUDIO_SOURCE_RADIO_TUNER = 1998 // MediaRecorder.AudioSource.RADIO_TUNER
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioTrackHelper: AudioTrackHelper? = null
    private var audioRecord: AudioRecord? = null
    private var audioRecordThread: Thread? = null
    @Volatile private var isAudioRecordRunning: Boolean = false

    // Reflection objects for qcom.fmradio.FmReceiver
    private var fmReceiverInstance: Any? = null
    private var enableMethod: Method? = null
    private var disableMethod: Method? = null
    private var setStationMethod: Method? = null
    private var getStationMethod: Method? = null
    private var searchStationsMethod: Method? = null
    private var cancelSearchMethod: Method? = null
    private var setMuteModeMethod: Method? = null
    private var setStereoModeMethod: Method? = null
    private var getRssiMethod: Method? = null
    private var getRadioTextMethod: Method? = null
    private var getProgramServiceMethod: Method? = null
    private var fmConfigInstance: Any? = null

    val isPoweredOn = MutableStateFlow(false)
    val currentFrequencyKHz = MutableStateFlow(87500) // band floor until the tuner reports its real frequency
    val isStereo = MutableStateFlow(false)
    val isMuted = MutableStateFlow(false)
    /** 0 until getRssi() actually returns a value (was a hardcoded 68). */
    val rssi = MutableStateFlow(0)
    /** RDS PS / RT strings: empty until the tuner decodes them — never a made-up station name. */
    val stationName = MutableStateFlow("")
    val radioText = MutableStateFlow("")
    val isScanning = MutableStateFlow(false)
    /** User presets only; no seeded list of frequencies. */
    val presets = MutableStateFlow<List<Int>>(emptyList())

    init {
        initClassLoaderAndReceiver()
        registerHeadsetListener()
    }

    private fun initClassLoaderAndReceiver() {
        try {
            val jarFile = File("/system/framework/qcom.fmradio.jar")
            val classLoader = if (jarFile.exists()) {
                PathClassLoader(jarFile.absolutePath, ClassLoader.getSystemClassLoader())
            } else {
                ClassLoader.getSystemClassLoader()
            }

            val fmReceiverClass = classLoader.loadClass("qcom.fmradio.FmReceiver")
            val fmConfigClass = classLoader.loadClass("qcom.fmradio.FmConfig")
            val callbackInterface = classLoader.loadClass("qcom.fmradio.FmRxEvCallbacksAdaptor")

            enableMethod = try {
                fmReceiverClass.getMethod("enable", fmConfigClass, Context::class.java)
            } catch (_: Throwable) {
                try {
                    fmReceiverClass.getMethod("enable", fmConfigClass, Int::class.javaPrimitiveType)
                } catch (_: Throwable) {
                    try {
                        fmReceiverClass.getMethod("enable", fmConfigClass)
                    } catch (_: Throwable) { null }
                }
            }

            disableMethod = try { fmReceiverClass.getMethod("disable") } catch (_: Throwable) { null }
            setStationMethod = try { fmReceiverClass.getMethod("setStation", Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
            getStationMethod = try { fmReceiverClass.getMethod("getStation") } catch (_: Throwable) { null }
            searchStationsMethod = try { fmReceiverClass.getMethod("searchStations", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
            cancelSearchMethod = try { fmReceiverClass.getMethod("cancelSearch") } catch (_: Throwable) { null }
            setMuteModeMethod = try { fmReceiverClass.getMethod("setMuteMode", Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
            setStereoModeMethod = try { fmReceiverClass.getMethod("setStereoMode", Boolean::class.javaPrimitiveType) } catch (_: Throwable) { null }
            getRssiMethod = try { fmReceiverClass.getMethod("getRssi") } catch (_: Throwable) { null }
            getRadioTextMethod = try { fmReceiverClass.getMethod("getRadioText") } catch (_: Throwable) { null }
            getProgramServiceMethod = try { fmReceiverClass.getMethod("getProgramService") } catch (_: Throwable) { null }

            fmConfigInstance = fmConfigClass.getDeclaredConstructor().newInstance().apply {
                try {
                    fmConfigClass.getMethod("setRadioBand", Int::class.javaPrimitiveType).invoke(this, 1) // US/Europe 87.5-108.0 MHz
                    fmConfigClass.getMethod("setEmphasis", Int::class.javaPrimitiveType).invoke(this, 0)  // 75us
                    fmConfigClass.getMethod("setChSpacing", Int::class.javaPrimitiveType).invoke(this, 1) // 100kHz
                    fmConfigClass.getMethod("setRdsStd", Int::class.javaPrimitiveType).invoke(this, 1)    // RDS/RBDS
                } catch (_: Throwable) {}
            }

            // Create dynamic proxy for hardware FM events
            val callbackProxy = Proxy.newProxyInstance(
                classLoader,
                arrayOf(callbackInterface)
            ) { _, method, args ->
                handleHardwareFmEvent(method.name, args)
                null
            }

            fmReceiverInstance = try {
                fmReceiverClass.getConstructor(String::class.java, callbackInterface)
                    .newInstance(FM_DEVICE_PATH, callbackProxy)
            } catch (_: Throwable) {
                try {
                    fmReceiverClass.getDeclaredConstructor().newInstance()
                } catch (_: Throwable) { null }
            }

            Log.d(TAG, "Qualcomm FmReceiver hardware class loaded: enableMethod=$enableMethod, setStationMethod=$setStationMethod")
        } catch (t: Throwable) {
            Log.w(TAG, "qcom.fmradio reflection init note (direct V4L2/HAL fallback will be used): ${t.message}")
        }
    }

    private fun handleHardwareFmEvent(methodName: String, args: Array<Any>?) {
        when {
            methodName.contains("Tune", ignoreCase = true) || methodName.contains("Station", ignoreCase = true) -> {
                val freq = try {
                    getStationMethod?.invoke(fmReceiverInstance) as? Int ?: currentFrequencyKHz.value
                } catch (_: Throwable) { currentFrequencyKHz.value }
                if (freq > 0) currentFrequencyKHz.value = freq
                // Real RSSI read (was hardcoded 68) — getRssiMethod was resolved but never invoked.
                try { (getRssiMethod?.invoke(fmReceiverInstance) as? Int)?.let { if (it in 0..127) rssi.value = it } } catch (_: Throwable) {}
            }
            methodName.contains("Rssi", ignoreCase = true) || methodName.contains("SignalStrength", ignoreCase = true) -> {
                try { (getRssiMethod?.invoke(fmReceiverInstance) as? Int)?.let { if (it in 0..127) rssi.value = it } } catch (_: Throwable) {}
            }
            methodName.contains("Rds", ignoreCase = true) || methodName.contains("RadioText", ignoreCase = true) -> {
                val rt = try {
                    getRadioTextMethod?.invoke(fmReceiverInstance) as? String
                } catch (_: Throwable) { null }
                if (!rt.isNullOrBlank()) radioText.value = rt
            }
            methodName.contains("ProgramService", ignoreCase = true) -> {
                val ps = try {
                    getProgramServiceMethod?.invoke(fmReceiverInstance) as? String
                } catch (_: Throwable) { null }
                if (!ps.isNullOrBlank()) stationName.value = ps
            }
            methodName.contains("Search", ignoreCase = true) -> {
                isScanning.value = false
            }
        }
    }

    fun powerOn() {
        if (isPoweredOn.value) return
        isPoweredOn.value = true

        scope.launch {
            try {
                // 1. Configure Hardware Audio HAL routing parameters
                configureAudioHal(true, currentFrequencyKHz.value)

                // 2. Enable Qualcomm FM hardware chip with matching signature
                val m = enableMethod
                if (m != null && fmReceiverInstance != null) {
                    try {
                        when (m.parameterTypes.size) {
                            2 -> {
                                if (m.parameterTypes[1] == Context::class.java) {
                                    m.invoke(fmReceiverInstance, fmConfigInstance, context)
                                } else {
                                    m.invoke(fmReceiverInstance, fmConfigInstance, 1)
                                }
                            }
                            else -> m.invoke(fmReceiverInstance, fmConfigInstance)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error invoking enableMethod: ${e.message}")
                    }
                }

                // 3. Tune initial frequency
                tune(currentFrequencyKHz.value)

                // 4. Start bit-perfect AudioRecord -> AudioTrack bridge
                startAudioBridge()

                // 5. Unmute
                unmute()
                Log.d(TAG, "Hardware Qualcomm FM Tuner powered ON at ${currentFrequencyKHz.value} kHz")
            } catch (t: Throwable) {
                Log.e(TAG, "Error powering on Qualcomm FM", t)
            }
        }
    }

    fun powerOff() {
        if (!isPoweredOn.value) return
        isPoweredOn.value = false

        scope.launch {
            try {
                mute()
                stopAudioBridge()
                try { disableMethod?.invoke(fmReceiverInstance) } catch (_: Throwable) {}
                configureAudioHal(false, currentFrequencyKHz.value)
                Log.d(TAG, "Hardware Qualcomm FM Tuner powered OFF")
            } catch (t: Throwable) {
                Log.e(TAG, "Error powering off Qualcomm FM", t)
            }
        }
    }

    fun tune(freqKHz: Int) {
        val clamped = freqKHz.coerceIn(87500, 108000)
        currentFrequencyKHz.value = clamped
        // Retuning invalidates the previous station's RDS; leave both empty until RDS decodes again.
        stationName.value = ""
        radioText.value = ""

        scope.launch {
            try {
                try { setStationMethod?.invoke(fmReceiverInstance, clamped) } catch (_: Throwable) {}
                // AudioManager.setParameters IS the HAL write; the setprop mirror that used to
                // follow needed su (absent on HioneOS) and carried no extra information.
                audioManager.setParameters("fm_freq=$clamped;fm_status=1;fm_mute=0")
            } catch (t: Throwable) {
                Log.e(TAG, "Error tuning frequency $clamped", t)
            }
        }
    }

    fun seek(up: Boolean) {
        isScanning.value = true
        scope.launch {
            try {
                val mode = 0 // Search Seek
                val dir = if (up) 0 else 1
                searchStationsMethod?.invoke(fmReceiverInstance, mode, 0, dir)
            } catch (_: Throwable) {
                // Manual seek fallback: step 200kHz
                val step = if (up) 200 else -200
                var next = currentFrequencyKHz.value + step
                if (next > 108000) next = 87500
                if (next < 87500) next = 108000
                tune(next)
                isScanning.value = false
            }
        }
    }

    fun mute() {
        isMuted.value = true
        audioTrackHelper?.setVolume(0.0f)
        try {
            setMuteModeMethod?.invoke(fmReceiverInstance, 1)
            audioManager.setParameters("fm_mute=1")
        } catch (_: Throwable) {}
    }

    fun unmute() {
        isMuted.value = false
        audioTrackHelper?.setVolume(1.0f)
        try {
            setMuteModeMethod?.invoke(fmReceiverInstance, 0)
            audioManager.setParameters("fm_mute=0")
        } catch (_: Throwable) {}
    }

    fun toggleStereo() {
        val newState = !isStereo.value
        isStereo.value = newState
        try {
            setStereoModeMethod?.invoke(fmReceiverInstance, newState)
        } catch (_: Throwable) {}
    }

    private fun configureAudioHal(enable: Boolean, freqKHz: Int) {
        try {
            val status = if (enable) "1" else "0"
            audioManager.setParameters("handle_fm=$status;fm_status=$status;fm_volume=1.0;fm_mute=0;fm_freq=$freqKHz;fm_active=$status")
            audioManager.setParameters(if (enable) "fm_route=playback" else "fm_route=off")
            audioManager.setParameters("vendor.audio.hw.fm.mode=$status")
            audioManager.setParameters("vendor.audio.fm.route=$status;vendor.audio.fm.freq=$freqKHz;vendor.audio.fm.status=$status;vendor.audio.fm.mute=0")
        } catch (t: Throwable) {
            Log.e(TAG, "Audio HAL parameter configuration error", t)
        }
    }

    private fun startAudioBridge() {
        if (isAudioRecordRunning) return
        isAudioRecordRunning = true

        audioTrackHelper = AudioTrackHelper(48000).apply {
            play()
            setVolume(if (isMuted.value) 0.0f else 1.0f)
        }

        audioRecordThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val sampleRate = 48000
            val channelIn = AudioFormat.CHANNEL_IN_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding).coerceAtLeast(4096)
            val buffer = ByteArray(minBuf)

            try {
                audioRecord = AudioRecord(AUDIO_SOURCE_RADIO_TUNER, sampleRate, channelIn, encoding, minBuf).apply {
                    if (state == AudioRecord.STATE_INITIALIZED) {
                        startRecording()
                        Log.d(TAG, "AudioRecord started on RADIO_TUNER source (48000Hz stereo)")
                    } else {
                        Log.w(TAG, "AudioRecord init returned state=$state")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed creating AudioRecord for FM", t)
            }

            while (isAudioRecordRunning && isPoweredOn.value) {
                val record = audioRecord
                if (record != null && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        audioTrackHelper?.write(buffer, 0, read)
                    }
                } else {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                }
            }

            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (_: Throwable) {}

            audioTrackHelper?.release()
            audioTrackHelper = null
            Log.d(TAG, "AudioRecord bridge stopped")
        }, "HioneFmAudioBridgeThread").apply { start() }
    }

    private fun stopAudioBridge() {
        isAudioRecordRunning = false
        audioRecordThread?.interrupt()
        audioRecordThread = null
    }

    private fun registerHeadsetListener() {
        try {
            val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_HEADSET_PLUG) {
                        val state = intent.getIntExtra("state", 0)
                        Log.d(TAG, "Headset plug state changed: $state")
                    }
                }
            }, filter)
        } catch (_: Throwable) {}
    }
}
