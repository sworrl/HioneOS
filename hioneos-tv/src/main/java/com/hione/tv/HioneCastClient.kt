package com.hione.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Receives the M500's live PCM audio stream and metadata for the TV.
 *
 * BROADCAST BEACON & CROSS-VLAN SUPPORT:
 * The TV binds a ServerSocket on port 8796 and advertises an mDNS beacon `_hionetv._tcp`.
 * When the M500 is on a separate VLAN where the TV cannot initiate outbound connections,
 * the M500 sees the TV's beacon (or probes the TV IP) and initiates the TCP connection
 * to this ServerSocket. Once connected, full-duplex PCM, metadata, and controls flow
 * seamlessly across the router/firewall.
 */
class HioneCastClient(private val ctx: Context) {

    companion object {
        private const val TAG = "HioneCastClient"
        const val PORT = 8796
        const val TV_SERVICE_TYPE = "_hionetv._tcp"
        const val M500_SERVICE_TYPE = "_hionecast._tcp"

        private const val HEADER_SIZE = 9
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'I'.code.toByte(), 'K'.code.toByte(), 'U'.code.toByte())

        private const val TYPE_FORMAT: Byte = 1
        private const val TYPE_PCM: Byte = 2
        private const val TYPE_META: Byte = 3
        private const val TYPE_CONTROL: Byte = 4
        private const val TYPE_PING: Byte = 5
        private const val TYPE_SKIP: Byte = 6
        private const val TYPE_AUTH: Byte = 7
        private const val PREFS = "hione_cast_pairing"
    }

    data class StreamFormat(
        val sampleRate: Int = 0,
        val bytesPerSample: Int = 0,
        val channelCount: Int = 0,
        val encoding: Int = 0,
    )

    data class ClientState(
        val connected: Boolean = false,
        val host: String = "",
        val status: String = "Broadcasting TV beacon...",
        val source: StreamFormat = StreamFormat(),
        val grantedRate: Int = 0,
        val grantedEncodingLabel: String = "",
        val passthroughExact: Boolean = false,
        val bytesPlayed: Long = 0L,
        val skips: Long = 0L,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val isPlaying: Boolean = false,
        val lastError: String = "",
        val needsCode: Boolean = false,
        val pairError: String = "",
        val tvIp: String = "",
        val cacheMs: Long = 1500L,
        val bufferedMs: Long = 0L,
        val isBuffering: Boolean = false,
        val underruns: Int = 0,
        val artBitmap: ImageBitmap? = null,
    )

    data class AudioVisuals(
        val waveform: FloatArray = FloatArray(128),
        val spectrum: FloatArray = FloatArray(64),
        val rms: Float = 0f,
        val peak: Float = 0f,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var serverSocket: ServerSocket? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var track: AudioTrack? = null

    private val _state = MutableStateFlow(ClientState())
    val state: StateFlow<ClientState> = _state

    private val _visuals = MutableStateFlow(AudioVisuals())
    val visuals: StateFlow<AudioVisuals> = _visuals

    @Volatile var rawPcmBuffer: FloatArray = FloatArray(512)
    @Volatile var rawFftBuffer: FloatArray = FloatArray(64)
    @Volatile var bassLevel: Float = 0f
    @Volatile var trebleLevel: Float = 0f

    @Volatile private var pendingCode: String? = null
    private val prefs by lazy { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    @Volatile private var lastFormatConfig: JSONObject? = null
    @Volatile var targetCacheMs: Long = 1500L
    @Volatile var underrunCount: Int = 0
    @Volatile var isBuffering: Boolean = true
    private val queuedBytes = java.util.concurrent.atomic.AtomicLong(0L)
    private val audioQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(256)
    private var audioLoopJob: Job? = null

    @Volatile private var metaBasePosition = 0L
    @Volatile private var metaBaseBytes = 0L

    fun pairWith(code: String) {
        pendingCode = code.filter { it.isDigit() }.take(6)
        _state.value = _state.value.copy(pairError = "", status = "Pairing with code")
    }

    fun start() {
        if (job?.isActive == true) return
        val localIp = getLocalIpAddress()
        _state.value = _state.value.copy(tvIp = localIp, status = "Broadcasting TV beacon on $localIp:$PORT")

        // 1. Register mDNS Beacon
        registerMdnsBeacon()

        // 2. Start UDP and TCP Servers & Client Watcher
        job = scope.launch {
            launch { runServerListener() }
            launch { runUdpBeacon(localIp) }
            launch { runClientScanner() }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        unregisterMdnsBeacon()
        runCatching { serverSocket?.close() }
        runCatching { socket?.close() }
        serverSocket = null; socket = null; out = null
        releaseTrack()
        _state.value = ClientState(status = "Stopped")
    }

    fun send(cmd: String, extra: JSONObject? = null) {
        val o = out ?: return
        val body = (extra ?: JSONObject()).put("cmd", cmd).toString().toByteArray()
        scope.launch {
            runCatching {
                synchronized(o) {
                    o.write(header(TYPE_CONTROL, body.size)); o.write(body); o.flush()
                }
            }.onFailure { Log.w(TAG, "control send '$cmd' failed: ${it.message}") }
        }
    }

    // ---- Beacon & Discovery -----------------------------------------------

    private fun registerMdnsBeacon() {
        try {
            val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "Hione TV"
                serviceType = TV_SERVICE_TYPE
                port = PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS Beacon registered: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS Beacon registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            nsdRegistration = l
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (t: Throwable) {
            Log.w(TAG, "mDNS register error: ${t.message}")
        }
    }

    private fun unregisterMdnsBeacon() {
        try {
            val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdRegistration?.let { nsd.unregisterService(it) }
            nsdRegistration = null
        } catch (_: Throwable) {}
    }

    private suspend fun runUdpBeacon(ip: String) {
        while (scope.isActive) {
            if (!_state.value.connected) {
                runCatching {
                    DatagramSocket().use { ds ->
                        ds.broadcast = true
                        val msg = "HIONE_TV_BEACON:$ip:$PORT".toByteArray()
                        val packet = DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), 8797)
                        ds.send(packet)
                    }
                }
            }
            delay(3000)
        }
    }

    // ---- TCP Server & Client Handlers -------------------------------------

    private suspend fun runServerListener() {
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", PORT), 4)
            }
            serverSocket = server
            Log.i(TAG, "TV ServerSocket listening on port $PORT for incoming M500 connections")
            while (scope.isActive && !server.isClosed) {
                val s = try { server.accept() } catch (t: Throwable) { break }
                val clientIp = s.inetAddress?.hostAddress ?: "M500"
                Log.i(TAG, "Accepted incoming connection from M500 ($clientIp)")
                handleActiveSocket(s, clientIp)
            }
        } catch (t: Throwable) {
            if (scope.isActive) Log.w(TAG, "runServerListener failed: ${t.message}")
        }
    }

    private suspend fun runClientScanner() {
        while (scope.isActive) {
            if (!_state.value.connected) {
                val m500Host = discoverM500()
                if (m500Host != null && !_state.value.connected) {
                    runCatching {
                        val s = Socket()
                        s.connect(InetSocketAddress(m500Host, PORT), 4000)
                        handleActiveSocket(s, m500Host)
                    }
                }
            }
            delay(5000)
        }
    }

    private suspend fun handleActiveSocket(s: Socket, host: String) {
        runCatching { socket?.close() }
        socket = s
        s.tcpNoDelay = true
        s.keepAlive = true
        out = s.getOutputStream()
        _state.value = _state.value.copy(connected = true, host = host, status = "Connected to $host", lastError = "")

        startAudioPlaybackLoop()

        try {
            val input = DataInputStream(s.getInputStream())
            val head = ByteArray(HEADER_SIZE)
            while (s.isConnected && !s.isClosed) {
                input.readFully(head)
                if (!magicMatches(head)) continue
                val len = lengthOf(head)
                if (len < 0 || len > 1 shl 22) break
                when (head[4]) {
                    TYPE_FORMAT -> {
                        val b = ByteArray(len); input.readFully(b)
                        applyFormat(JSONObject(String(b)))
                    }
                    TYPE_PCM -> {
                        val b = ByteArray(len); input.readFully(b)
                        playPcm(b)
                    }
                    TYPE_META -> {
                        val b = ByteArray(len); input.readFully(b)
                        applyMeta(JSONObject(String(b)))
                    }
                    TYPE_SKIP -> {
                        val b = ByteArray(len); if (len > 0) input.readFully(b)
                        audioQueue.clear()
                        queuedBytes.set(0L)
                        isBuffering = true
                        runCatching { track?.flush() }
                        metaBasePosition = _state.value.positionMs
                        metaBaseBytes = _state.value.bytesPlayed
                        _state.value = _state.value.copy(
                            skips = _state.value.skips + 1,
                            status = "Resynced stream after network stall"
                        )
                    }
                    TYPE_AUTH -> {
                        val b = ByteArray(len); if (len > 0) input.readFully(b)
                        if (!handleAuth(JSONObject(String(b)))) break
                    }
                    TYPE_PING -> {}
                    else -> { if (len > 0) input.skipBytes(len) }
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "Socket session ended: ${t.message}")
            _state.value = _state.value.copy(connected = false, lastError = t.localizedMessage ?: t.javaClass.simpleName, status = "Waiting for M500...")
        } finally {
            audioLoopJob?.cancel()
            audioQueue.clear()
            queuedBytes.set(0L)
            isBuffering = true
            runCatching { s.close() }
            if (socket === s) {
                socket = null; out = null
                _state.value = _state.value.copy(connected = false)
                releaseTrack()
            }
        }
    }

    @Volatile private var serverId = ""

    private fun handleAuth(j: JSONObject): Boolean {
        val o = out ?: return false
        if (j.optBoolean("ok", false) || j.optString("status") == "ok") {
            if (j.has("token")) {
                prefs.edit().putString("id_$serverId", j.optString("id")).putString("token_$serverId", j.optString("token")).apply()
            }
            _state.value = _state.value.copy(needsCode = false, pairError = "", status = "Connected")
            return true
        }
        if (j.has("challenge")) {
            serverId = j.optString("server")
            val id = prefs.getString("id_$serverId", null)
            val token = prefs.getString("token_$serverId", null)
            val code = pendingCode
            val reply = when {
                id != null && token != null -> JSONObject().put("id", id).put("mac", hmacHex(token, j.optString("challenge")))
                code != null -> { pendingCode = null; JSONObject().put("code", code).put("name", android.os.Build.MODEL ?: "Sony TV") }
                else -> {
                    JSONObject().put("name", android.os.Build.MODEL ?: "Sony TV").put("auto", true).put("v", 1)
                }
            }
            val b = reply.toString().toByteArray()
            synchronized(o) { o.write(header(TYPE_AUTH, b.size)); o.write(b); o.flush() }
            return true
        }
        val err = j.optString("error")
        if (err == "unknown-tv") prefs.edit().remove("id_$serverId").remove("token_$serverId").apply()
        _state.value = _state.value.copy(
            needsCode = err != "locked",
            pairError = when (err) {
                "bad-code" -> "Wrong code. Check the M500 screen."
                "locked" -> "Too many wrong codes. Wait a minute and try again."
                "unknown-tv" -> "The M500 forgot this TV. Enter a new code."
                "no-code" -> "Turn on Cast to a TV on the M500 to get a code."
                else -> "Pairing failed."
            },
            status = "Not paired"
        )
        return false
    }

    private fun hmacHex(key: String, data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun applyFormat(j: JSONObject) {
        lastFormatConfig = j
        val f = StreamFormat(
            sampleRate = j.optInt("sampleRate"),
            bytesPerSample = j.optInt("bytesPerSample"),
            channelCount = j.optInt("channelCount"),
            encoding = j.optInt("encoding"),
        )
        audioQueue.clear()
        releaseTrack()

        val enc = when (f.bytesPerSample) {
            2 -> AudioFormat.ENCODING_PCM_16BIT
            3 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            4 -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        val channelMask = when (f.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        val built = runCatching {
            val min = AudioTrack.getMinBufferSize(f.sampleRate, channelMask, enc).coerceAtLeast(8192)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(enc)
                        .setSampleRate(f.sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(min * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.w(TAG, "AudioTrack refused: ${it.message}")
            _state.value = _state.value.copy(
                source = f, grantedRate = 0, grantedEncodingLabel = "refused",
                passthroughExact = false, status = "TV refused ${f.sampleRate}Hz ${f.bytesPerSample * 8}-bit"
            )
            return
        }

        built.play()
        track = built

        val grantedRate = runCatching { built.sampleRate }.getOrDefault(0)
        val grantedEnc = runCatching { built.audioFormat }.getOrDefault(0)
        val exact = grantedRate == f.sampleRate && grantedEnc == enc
        _state.value = _state.value.copy(
            source = f,
            grantedRate = grantedRate,
            grantedEncodingLabel = encodingLabel(grantedEnc),
            passthroughExact = exact,
            status = if (exact) "Playing Bit-Perfect" else "Playing (Resampled by TV)"
        )
    }

    private fun playPcm(b: ByteArray) {
        // 1. Instantly feed real-time visualizer so visuals never stutter
        processVisuals(b, _state.value.source.bytesPerSample)

        // 2. Bound queue to prevent unconstrained memory growth (~4MB max)
        while (audioQueue.size >= 240) {
            val dropped = audioQueue.poll()
            if (dropped != null) {
                queuedBytes.addAndGet(-dropped.size.toLong())
            }
        }
        audioQueue.offer(b)
        queuedBytes.addAndGet(b.size.toLong())
    }

    private fun startAudioPlaybackLoop() {
        audioLoopJob?.cancel()
        audioLoopJob = scope.launch(Dispatchers.IO) {
            isBuffering = true
            while (isActive) {
                val src = _state.value.source
                val bytesPerSec = if (src.sampleRate > 0 && src.bytesPerSample > 0 && src.channelCount > 0) {
                    src.sampleRate.toLong() * src.bytesPerSample * src.channelCount
                } else 176400L

                val targetBytes = ((bytesPerSec * targetCacheMs) / 1000L).coerceAtLeast(12 * 1024L)

                // Jitter pre-buffering phase
                if (isBuffering) {
                    val currentBuffered = queuedBytes.get()
                    val bufferedMs = (currentBuffered * 1000L / bytesPerSec)
                    _state.value = _state.value.copy(
                        isBuffering = true,
                        cacheMs = targetCacheMs,
                        bufferedMs = bufferedMs
                    )

                    if (currentBuffered >= targetBytes) {
                        isBuffering = false
                        Log.i(TAG, "Streaming jitter cache filled: ${bufferedMs}ms ($currentBuffered bytes). Resuming smooth playback.")
                    } else {
                        delay(20)
                        continue
                    }
                }

                val chunk = try {
                    audioQueue.poll(80, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }

                if (chunk == null) {
                    // Buffer ran empty! Check if this was a network jitter underrun during active playback
                    if (_state.value.isPlaying && _state.value.connected) {
                        underrunCount++
                        // Dynamically scale cache up to absorb slower / jittery networks
                        targetCacheMs = (targetCacheMs + 500L).coerceAtMost(4000L)
                        isBuffering = true
                        Log.w(TAG, "Network stutter detected (underrun #$underrunCount). Dynamically scaling streaming cache to ${targetCacheMs}ms")
                        _state.value = _state.value.copy(
                            underruns = underrunCount,
                            cacheMs = targetCacheMs,
                            isBuffering = true
                        )
                    }
                    continue
                }

                queuedBytes.addAndGet(-chunk.size.toLong())
                val curBufMs = (queuedBytes.get() * 1000L / bytesPerSec)

                var t = track
                if (t == null) {
                    lastFormatConfig?.let { applyFormat(it) }
                    t = track
                }
                if (t == null) continue

                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    runCatching { t.play() }
                }

                val written = runCatching {
                    t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                }.getOrDefault(-1)

                if (written < 0) {
                    Log.w(TAG, "AudioTrack write error ($written); auto-recovering track")
                    releaseTrack()
                    lastFormatConfig?.let { applyFormat(it) }
                } else if (written > 0) {
                    val newBytes = _state.value.bytesPlayed + written
                    var newPos = _state.value.positionMs
                    if (bytesPerSec > 0) {
                        val deltaMs = ((newBytes - metaBaseBytes) * 1000L) / bytesPerSec
                        newPos = (metaBasePosition + deltaMs).coerceAtLeast(0L)
                        val dur = _state.value.durationMs
                        if (dur > 0L && newPos > dur) {
                            newPos = dur
                        }
                    }
                    _state.value = _state.value.copy(
                        bytesPlayed = newBytes,
                        positionMs = newPos,
                        bufferedMs = curBufMs,
                        isBuffering = false,
                        cacheMs = targetCacheMs
                    )
                }
            }
        }
    }

    private fun processVisuals(b: ByteArray, bytesPerSample: Int) {
        val step = when (bytesPerSample) {
            2 -> 2
            3 -> 3
            4 -> 4
            else -> 2
        }
        val samplesCount = (b.size / step).coerceAtLeast(1)
        val wave = FloatArray(128)
        var sumSquares = 0f
        var peak = 0f

        val stride = (samplesCount / 128).coerceAtLeast(1)
        for (i in 0 until 128) {
            val sampleIdx = (i * stride).coerceAtMost(samplesCount - 1)
            val byteOffset = sampleIdx * step
            if (byteOffset + 1 < b.size) {
                val raw16 = (b[byteOffset].toInt() and 0xFF) or (b[byteOffset + 1].toInt() shl 8)
                val s = raw16.toShort().toFloat() / 32768f
                wave[i] = s
                val mag = abs(s)
                sumSquares += s * s
                if (mag > peak) peak = mag
            }
        }

        val rms = sqrt(sumSquares / 128f).coerceIn(0f, 1f)

        // Synthetic 64-band equalizer spectrum from waveform autocorrelation / multi-band filters
        val spec = FloatArray(64)
        var bs = 0f
        var ts = 0f
        for (i in 0 until 64) {
            val idx = (i * 2).coerceAtMost(127)
            val raw = abs(wave[idx]) * 0.7f + (if (idx + 1 < 128) abs(wave[idx + 1]) * 0.3f else 0f)
            val weighted = raw * (1f + (63 - i) * 0.02f) * (0.8f + rms * 1.5f)
            val s = weighted.coerceIn(0f, 1.2f)
            spec[i] = s
            if (i < 10) bs += s
            if (i > 35) ts += s
        }

        // Full 512-sample PCM buffer for projectM audio engine
        val pcm = FloatArray(512)
        val pcmStride = (samplesCount / 512).coerceAtLeast(1)
        for (i in 0 until 512) {
            val sampleIdx = (i * pcmStride).coerceAtMost(samplesCount - 1)
            val byteOffset = sampleIdx * step
            if (byteOffset + 1 < b.size) {
                val raw16 = (b[byteOffset].toInt() and 0xFF) or (b[byteOffset + 1].toInt() shl 8)
                pcm[i] = raw16.toShort().toFloat() / 32768f
            }
        }

        rawPcmBuffer = pcm
        rawFftBuffer = spec
        bassLevel = (bs / 10f).coerceIn(0f, 1.5f)
        trebleLevel = (ts / 28f).coerceIn(0f, 1.5f)

        _visuals.value = AudioVisuals(waveform = wave, spectrum = spec, rms = rms, peak = peak)
    }

    private fun applyMeta(j: JSONObject) {
        val title = j.optString("title", "")
        val artist = j.optString("artist", "")
        val album = j.optString("album", "")
        val durationMs = j.optLong("durationMs", 0L).coerceAtLeast(0L)
        val positionMs = j.optLong("positionMs", 0L).coerceAtLeast(0L)
        val playing = j.optBoolean("playing", true)

        metaBasePosition = positionMs
        metaBaseBytes = _state.value.bytesPlayed

        // Preserve previous valid duration if the new frame is for the same track but duration is temporarily 0
        val resolvedDur = if (durationMs > 0L) {
            durationMs
        } else if (title == _state.value.title && artist == _state.value.artist && _state.value.durationMs > 0L) {
            _state.value.durationMs
        } else {
            0L
        }

        val artB64 = j.optString("artB64", "")
        val resolvedArt = if (artB64.isNotBlank()) {
            runCatching {
                val b = Base64.decode(artB64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
            }.getOrNull()
        } else if (title == _state.value.title && artist == _state.value.artist) {
            _state.value.artBitmap
        } else null

        _state.value = _state.value.copy(
            title = title,
            artist = artist,
            album = album,
            durationMs = resolvedDur,
            positionMs = positionMs,
            isPlaying = playing,
            artBitmap = resolvedArt
        )
    }

    private fun releaseTrack() {
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
    }

    private suspend fun discoverM500(): String? {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        var found: String? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("hionecast")) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) { found = i.host?.hostAddress }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        return runCatching {
            nsd.discoverServices(M500_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            repeat(40) { if (found != null) return@repeat; delay(100) }
            runCatching { nsd.stopServiceDiscovery(listener) }
            found
        }.getOrNull()
    }

    private fun getLocalIpAddress(): String {
        return runCatching {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en != null && en.hasMoreElements()) {
                val intf = en.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs != null && addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
            "10.3.64.222"
        }.getOrDefault("10.3.64.222")
    }

    private fun encodingLabel(enc: Int): String = when (enc) {
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit packed"
        AudioFormat.ENCODING_PCM_32BIT -> "32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "float"
        else -> "enc $enc"
    }

    private fun header(type: Byte, length: Int): ByteArray {
        val h = ByteArray(HEADER_SIZE)
        System.arraycopy(MAGIC, 0, h, 0, 4)
        h[4] = type
        h[5] = (length ushr 24).toByte(); h[6] = (length ushr 16).toByte()
        h[7] = (length ushr 8).toByte(); h[8] = length.toByte()
        return h
    }

    private fun magicMatches(h: ByteArray) =
        h[0] == MAGIC[0] && h[1] == MAGIC[1] && h[2] == MAGIC[2] && h[3] == MAGIC[3]

    private fun lengthOf(h: ByteArray) =
        ((h[5].toInt() and 0xFF) shl 24) or ((h[6].toInt() and 0xFF) shl 16) or
            ((h[7].toInt() and 0xFF) shl 8) or (h[8].toInt() and 0xFF)

    fun onExternalFormat(sampleRate: Int, bytesPerSample: Int, channelCount: Int) {
        val j = JSONObject()
            .put("sampleRate", sampleRate)
            .put("bytesPerSample", bytesPerSample)
            .put("channelCount", channelCount)
            .put("encoding", when (bytesPerSample) {
                2 -> AudioFormat.ENCODING_PCM_16BIT
                3 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                4 -> AudioFormat.ENCODING_PCM_32BIT
                else -> AudioFormat.ENCODING_PCM_16BIT
            })
        applyFormat(j)
        _state.value = _state.value.copy(connected = true, host = "3rd-Party Bridge")
        startAudioPlaybackLoop()
    }

    fun onExternalPcm(b: ByteArray) {
        playPcm(b)
    }

    fun onExternalMeta(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        isPlaying: Boolean,
        artBitmap: ImageBitmap? = null
    ) {
        _state.value = _state.value.copy(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isPlaying = isPlaying,
            artBitmap = artBitmap ?: _state.value.artBitmap,
            connected = true
        )
    }

    fun onExternalStop() {
        _state.value = _state.value.copy(isPlaying = false)
        audioQueue.clear()
        queuedBytes.set(0L)
        runCatching { track?.pause() }
    }
}
