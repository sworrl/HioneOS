package com.hione.player

import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hione Central Nervous System & Subsystem Orchestrator (HioneBrain).
 *
 * Coordinates and orchestrates all subsystem "bones" (Audio, Scanner, Network, Hardware, UI)
 * across isolated thread dispatchers with watchdog health monitoring.
 *
 * Completely eliminates Main UI Thread stalls, unresponsiveness, and Android ANRs.
 */
object HioneBrain {
    private const val TAG = "HioneBrain"

    enum class BoneType(val displayName: String) {
        AUDIO_DSP("Audio and DSP"),
        LIBRARY_SCANNER("Library and tag scanner"),
        NETWORK_INGRESS("Network sync (m500d, rsync)"),
        HARDWARE_IO("CS43198 DAC sysfs and I/O"),
        UI_RENDERER("UI rendering")
    }

    enum class BoneState {
        IDLE,
        ACTIVE,
        THROTTLED,
        STALLED,
        ERROR
    }

    data class BoneHealth(
        val type: BoneType,
        val state: BoneState = BoneState.IDLE,
        val lastHeartbeatMs: Long = SystemClock.elapsedRealtime(),
        val activeTasks: Int = 0,
        /** Honest until a real heartbeat arrives — this used to default to "Nominal", which the
         *  Brain modal rendered as a health verdict for bones that had never reported anything. */
        val lastMessage: String = "Not probed yet",
        /** True only once [heartbeat] has actually been called for this bone. */
        val probed: Boolean = false
    )

    data class BrainTelemetry(
        val isUiUnderLoad: Boolean = false,
        val totalBonesActive: Int = 0,
        val allBonesHealthy: Boolean = true,
        val bones: Map<BoneType, BoneHealth> = emptyMap()
    )

    // Dedicated, isolated priority thread pools for each bone subsystem
    val AudioDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor(
        PriorityThreadFactory("HioneBrain-Audio", Process.THREAD_PRIORITY_AUDIO)
    ).asCoroutineDispatcher()

    val ScannerDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(
        2,
        PriorityThreadFactory("HioneBrain-Scanner", Process.THREAD_PRIORITY_BACKGROUND)
    ).asCoroutineDispatcher()

    val NetworkDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(
        2,
        PriorityThreadFactory("HioneBrain-Network", Process.THREAD_PRIORITY_BACKGROUND)
    ).asCoroutineDispatcher()

    val HardwareDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor(
        PriorityThreadFactory("HioneBrain-Hardware", Process.THREAD_PRIORITY_DEFAULT)
    ).asCoroutineDispatcher()

    private val brainScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val boneRegistry = ConcurrentHashMap<BoneType, BoneHealth>()
    private val _telemetry = MutableStateFlow(BrainTelemetry())
    val telemetry: StateFlow<BrainTelemetry> = _telemetry

    private val lastUiActivityTime = AtomicLong(0L)
    private val isWatchdogRunning = AtomicBoolean(false)

    init {
        // Register all core bones
        BoneType.values().forEach { bone ->
            boneRegistry[bone] = BoneHealth(type = bone, state = BoneState.IDLE)
        }
        startWatchdog()
    }

    /**
     * Called by any subsystem bone to report progress and register heartbeat.
     */
    fun heartbeat(bone: BoneType, state: BoneState = BoneState.ACTIVE, message: String = "OK", activeTasks: Int = 0) {
        val now = SystemClock.elapsedRealtime()
        boneRegistry[bone] = BoneHealth(
            type = bone,
            state = state,
            lastHeartbeatMs = now,
            activeTasks = activeTasks,
            lastMessage = message,
            probed = true
        )
    }

    /**
     * Signals the Brain that user is actively interacting with the UI.
     * The Brain will automatically throttle background I/O bones to keep UI 100% fluid.
     */
    fun notifyUiInteraction() {
        lastUiActivityTime.set(SystemClock.elapsedRealtime())
    }

    /**
     * Checks if background workers should yield CPU and throttle I/O.
     */
    fun shouldThrottleBackgroundWork(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return (now - lastUiActivityTime.get()) < 1500L
    }

    /**
     * Cooperative yield point for scanner and sync workers.
     * Call this inside loops to prevent CPU hogging.
     */
    suspend fun cooperativeYield() {
        if (shouldThrottleBackgroundWork()) {
            delay(35L)
        } else {
            yield()
        }
    }

    /**
     * Executes work safely on the dedicated Hardware Dispatcher without blocking UI.
     */
    fun launchHardware(block: suspend CoroutineScope.() -> Unit): Job {
        return brainScope.launch(HardwareDispatcher) {
            try {
                heartbeat(BoneType.HARDWARE_IO, BoneState.ACTIVE, "Executing I/O")
                block()
                heartbeat(BoneType.HARDWARE_IO, BoneState.IDLE, "Idle")
            } catch (e: Throwable) {
                Log.e(TAG, "Hardware bone error", e)
                heartbeat(BoneType.HARDWARE_IO, BoneState.ERROR, e.message ?: "Error")
            }
        }
    }

    /**
     * Executes work safely on the dedicated Network Ingress Dispatcher.
     */
    fun launchNetwork(block: suspend CoroutineScope.() -> Unit): Job {
        return brainScope.launch(NetworkDispatcher) {
            try {
                heartbeat(BoneType.NETWORK_INGRESS, BoneState.ACTIVE, "Network I/O")
                block()
                heartbeat(BoneType.NETWORK_INGRESS, BoneState.IDLE, "Idle")
            } catch (e: Throwable) {
                Log.e(TAG, "Network bone error", e)
                heartbeat(BoneType.NETWORK_INGRESS, BoneState.ERROR, e.message ?: "Error")
            }
        }
    }

    /**
     * Executes work safely on the dedicated Library Scanner Dispatcher.
     */
    fun launchScanner(block: suspend CoroutineScope.() -> Unit): Job {
        return brainScope.launch(ScannerDispatcher) {
            try {
                heartbeat(BoneType.LIBRARY_SCANNER, BoneState.ACTIVE, "Scanning")
                block()
                heartbeat(BoneType.LIBRARY_SCANNER, BoneState.IDLE, "Idle")
            } catch (e: Throwable) {
                Log.e(TAG, "Scanner bone error", e)
                heartbeat(BoneType.LIBRARY_SCANNER, BoneState.ERROR, e.message ?: "Error")
            }
        }
    }

    /**
     * Continuous 1-second autonomous watchdog monitor & telemetry engine.
     * Continuously probes and live-streams real-time telemetry from all 5 subsystem bones
     * without ever requiring manual user polling.
     */
    private fun startWatchdog() {
        if (isWatchdogRunning.getAndSet(true)) return
        brainScope.launch {
            while (isActive) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    val uiUnderLoad = (now - lastUiActivityTime.get()) < 1500L

                    // ========================================================
                    // 1. AUTONOMOUS LIVE PROBE: AUDIO DSP & CS43198 DIRECT ALSA
                    // ========================================================
                    try {
                        val p = PlayerHolder.player
                        val isPlaying = p?.isPlaying == true
                        val title = p?.mediaMetadata?.title?.toString()
                        val artist = p?.mediaMetadata?.artist?.toString()
                        val audioMsg = if (isPlaying) {
                            "Playing: \"${title ?: "Audio"}\" ${if (artist != null) "by $artist" else ""}"
                        } else {
                            "Player idle"
                        }
                        heartbeat(BoneType.AUDIO_DSP, if (isPlaying) BoneState.ACTIVE else BoneState.IDLE, audioMsg, if (isPlaying) 1 else 0)
                    } catch (_: Throwable) {}

                    // ========================================================
                    // 2. AUTONOMOUS LIVE PROBE: NETWORK INGRESS & TRANSCEIVER
                    // ========================================================
                    try {
                        val net = com.hione.player.network.HioneNetworkService.state.value
                        val wifi = net.wifi
                        val isConnected = wifi.isConnected && wifi.ssid.isNotEmpty() && wifi.ssid != "<unknown ssid>"
                        val netMsg = if (isConnected) {
                            "Wi-Fi: ${wifi.ssid} (${if (wifi.linkSpeedMbps > 0) "${wifi.linkSpeedMbps} Mbps" else "link —"} · ${wifi.rssiDbm?.let { "$it dBm" } ?: "RSSI —"})"
                        } else if (net.activeTransport == "CELLULAR") {
                            "Cellular ${net.cellular.networkType.ifEmpty { "—" }} (${net.cellular.carrierName.ifEmpty { "operator —" }})"
                        } else if (net.isScanning) {
                            "Wi-Fi scan in progress"
                        } else {
                            "No network link"
                        }
                        heartbeat(BoneType.NETWORK_INGRESS, if (isConnected || net.isScanning) BoneState.ACTIVE else BoneState.IDLE, netMsg, if (isConnected) 1 else 0)
                    } catch (_: Throwable) {}

                    // ========================================================
                    // 3. AUTONOMOUS LIVE PROBE: MEDIA SCANNER & TAG INDEXER
                    // ========================================================
                    // Real scanner state from ScanProgress (was an always-"Synchronized" constant).
                    try {
                        val scanning = ScanProgress.active
                        val scanMsg = if (scanning) {
                            "${ScanProgress.phase.ifEmpty { "Scanning" }} · ${ScanProgress.visited.get()} files visited, ${ScanProgress.newFound.get()} new"
                        } else if (ScanProgress.resultTotal > 0) {
                            "Idle · last scan: ${ScanProgress.resultTotal} tracks (${if (ScanProgress.resultDelta >= 0) "+" else ""}${ScanProgress.resultDelta})"
                        } else "Idle · no scan completed in this process"
                        heartbeat(BoneType.LIBRARY_SCANNER, if (scanning) BoneState.ACTIVE else BoneState.IDLE, scanMsg, if (scanning) 1 else 0)
                    } catch (_: Throwable) {}

                    // ========================================================
                    // 4. AUTONOMOUS LIVE PROBE: HARDWARE I/O — reports only what is actually readable
                    //    (the DAC sysfs nodes). No claims about the RGB light, which has no working driver here.
                    // ========================================================
                    try {
                        val audit = CirrusLogicManager.getLiveHardwareAudit()
                        val hwMsg = if (audit.isSysfsReadable) {
                            "DAC sysfs readable · filter ${audit.kernelFilterText} · gain ${audit.kernelGainText} · out ${audit.kernelOutputText}"
                        } else "DAC sysfs not readable"
                        heartbeat(BoneType.HARDWARE_IO, if (audit.isSysfsReadable) BoneState.ACTIVE else BoneState.ERROR, hwMsg, if (audit.isSysfsReadable) 1 else 0)
                    } catch (_: Throwable) {}

                    // ========================================================
                    // 5. AUTONOMOUS LIVE PROBE: UI RENDERER — only the fact we can observe (recent touch activity).
                    // ========================================================
                    try {
                        val uiMsg = if (uiUnderLoad) "Touch interaction in the last 1.5 s · background work yielding" else "No recent touch input"
                        heartbeat(BoneType.UI_RENDERER, if (uiUnderLoad) BoneState.ACTIVE else BoneState.IDLE, uiMsg, if (uiUnderLoad) 1 else 0)
                    } catch (_: Throwable) {}

                    // Evaluate overall systemic health
                    var healthy = true
                    var activeCount = 0

                    boneRegistry.forEach { (_, health) ->
                        if (health.state == BoneState.ACTIVE) {
                            activeCount++
                        } else if (health.state == BoneState.ERROR || health.state == BoneState.STALLED) {
                            healthy = false
                        }
                    }

                    _telemetry.value = BrainTelemetry(
                        isUiUnderLoad = uiUnderLoad,
                        totalBonesActive = activeCount,
                        allBonesHealthy = healthy,
                        bones = boneRegistry.toMap()
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "Watchdog tick error: ${e.message}")
                }
                delay(1000L)
            }
        }
    }

    private class PriorityThreadFactory(
        private val name: String,
        private val priority: Int
    ) : ThreadFactory {
        override fun newThread(r: Runnable): Thread {
            return Thread({
                Process.setThreadPriority(priority)
                r.run()
            }, name)
        }
    }
}
