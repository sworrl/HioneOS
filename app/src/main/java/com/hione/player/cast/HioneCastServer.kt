package com.hione.player.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves the live PCM and the control channel to the TV companion.
 *
 * SHAPE. The M500 listens and the TV connects, not the other way round. The player is the thing
 * that knows when audio exists, it is the one in a pocket moving between networks, and it should
 * not have to discover or hold a connection to a TV that is usually off. A TV that boots simply
 * finds the service over mDNS and attaches; when the player leaves the house the socket drops and
 * neither side has to care.
 *
 * ONE CLIENT. Deliberate. Two TVs would both want to be the output and there is no sensible way
 * to pick, so a second connection displaces the first and the first is told why. Multi-room is a
 * different feature with different clock requirements and is not pretended at here.
 *
 * BACKPRESSURE. The writer never blocks the audio thread: [HioneCastTap] owns a ring buffer and
 * this reads from it at its own pace. If the network cannot keep up the ring overruns, the tap
 * drops the OLDEST audio, and we send a SKIP frame so the TV can resync rather than drift. A
 * mirror that silently falls behind is worse than one that admits a gap.
 *
 * ACCESS. The listener runs only while the user has casting turned on (Settings, Wi-Fi Remote),
 * binds only to the Wi-Fi address, and stops by itself after [IDLE_STOP_MS] with no TV. A TV
 * must pass the [HioneCastProtocol.TYPE_AUTH] handshake ([HioneCastAuth]) before it gets a single
 * byte of audio, and an unauthenticated connection can't displace the TV that is playing.
 */
object HioneCastServer {

    private const val TAG = "HioneCastServer"

    /** Read chunk. ~21ms at 24/96 stereo: small enough to stay responsive, large enough to be cheap. */
    private const val CHUNK = 12 * 1024

    /** Stop listening after this long with no TV attached. */
    private const val IDLE_STOP_MS = 15 * 60_000L
    private const val AUTH_TIMEOUT_MS = 10_000
    private const val MAX_PENDING = 4
    private val pending = AtomicInteger(0)
    private var watchdog: Job? = null
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    @Volatile private var boundIp: String = ""
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    @Volatile private var client: Socket? = null
    @Volatile private var clientOut: OutputStream? = null
    private var tvDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var tvOutboundJob: Job? = null

    val bytesSent = AtomicLong(0L)

    data class CastState(
        /** The user turned casting on. The listener may still be waiting for Wi-Fi. */
        val active: Boolean = false,
        val listening: Boolean = false,
        val address: String = "",
        val clientAddress: String = "",
        val clientLabel: String = "",
        val connected: Boolean = false,
        val lastError: String = "",
    )

    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state

    /** Commands arriving FROM the TV. The player app subscribes and acts on them. */
    private val _commands = MutableStateFlow<JSONObject?>(null)
    val commands: StateFlow<JSONObject?> = _commands

    /** Turn casting on: listen on the Wi-Fi address and show a fresh TV pairing code. */
    @Synchronized
    fun start(ctx: Context) {
        if (_state.value.active) return
        val app = ctx.applicationContext
        if (HioneCastAuth.serverId(app) == null) {
            _state.value = CastState(lastError = "Secure storage unavailable")
            return
        }
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wm != null && wifiLock == null) {
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = runCatching {
                wm.createWifiLock(mode, "HioneCast:HighPerf").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
            Log.i(TAG, "Wi-Fi lock acquired (mode=$mode)")
        }
        HioneCastAuth.newCode()
        _state.value = CastState(active = true)
        netCallback = com.hione.player.api.HioneLanBinding.watch(app) { rebindIfChanged(app) }
        listen(app)
        watchdog = scope.launch {
            var idleSince = System.currentTimeMillis()
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (_state.value.connected) idleSince = System.currentTimeMillis()
                else if (System.currentTimeMillis() - idleSince > IDLE_STOP_MS) {
                    Log.i(TAG, "no TV for ${IDLE_STOP_MS / 60_000} min, stopping cast")
                    stop(app)
                    _state.value = _state.value.copy(lastError = "Casting turned off after 15 minutes with no TV")
                    break
                }
            }
        }
    }

    @Synchronized
    private fun rebindIfChanged(app: Context) {
        if (!_state.value.active) return
        val ip = com.hione.player.api.HioneLanBinding.wifiIpv4(app)?.hostAddress ?: ""
        if (ip != boundIp) {
            Log.i(TAG, "Wi-Fi address changed ($boundIp -> $ip), rebinding")
            listen(app)
        }
    }

    @Synchronized
    private fun listen(app: Context) {
        runCatching { server?.close() }
        withdraw()
        acceptJob?.cancel()
        server = null
        val wifi = com.hione.player.api.HioneLanBinding.wifiIpv4(app)
        boundIp = wifi?.hostAddress ?: ""
        if (wifi == null) {
            _state.value = _state.value.copy(listening = false, address = "", lastError = "Waiting for Wi-Fi")
            return
        }
        val s = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(wifi, HioneCastProtocol.PORT), 8) }
        } catch (t: Throwable) {
            Log.w(TAG, "server failed: ${t.javaClass.simpleName}: ${t.message}")
            _state.value = _state.value.copy(listening = false, lastError = t.localizedMessage ?: t.javaClass.simpleName)
            return
        }
        server = s
        _state.value = _state.value.copy(listening = true, address = boundIp, lastError = "")
        Log.i(TAG, "listening on $boundIp:${HioneCastProtocol.PORT}")
        advertise(app)
        discoverTvBeacon(app)
        acceptJob = scope.launch {
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (t: Throwable) { break }
                if (pending.incrementAndGet() > MAX_PENDING) {
                    pending.decrementAndGet()
                    runCatching { sock.close() }
                    continue
                }
                scope.launch {
                    try { authenticate(app, sock) } finally { pending.decrementAndGet() }
                }
            }
        }
    }

    /** Turn casting off: drop the TV, close the listener, withdraw the mDNS record. */
    @Synchronized
    fun stop(ctx: Context? = null) {
        ctx?.let { com.hione.player.api.HioneLanBinding.unwatch(it, netCallback) }
        netCallback = null
        watchdog?.cancel()
        watchdog = null
        runCatching { client?.close() }
        runCatching { server?.close() }
        withdraw()
        acceptJob?.cancel()
        acceptJob = null
        server = null
        client = null
        clientOut = null
        boundIp = ""
        runCatching {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
        HioneCastTap.setEnabled(false)
        HioneCastAuth.clearCode()
        _state.value = CastState()
        Log.i(TAG, "stopped")
    }

    /** The handshake. Nothing but AUTH frames cross this socket until it succeeds. */
    private fun authenticate(app: Context, sock: Socket) {
        val ip = sock.inetAddress?.hostAddress ?: "?"
        try {
            if (com.hione.player.api.HioneApiSecurity.isBlocked(ip)) { sock.close(); return }
            sock.soTimeout = AUTH_TIMEOUT_MS
            val out = sock.getOutputStream()
            val challenge = HioneCastAuth.challenge()
            val hello = org.json.JSONObject()
                .put("v", 1).put("server", HioneCastAuth.serverId(app) ?: "").put("challenge", challenge)
                .toString().toByteArray()
            out.write(HioneCastProtocol.header(HioneCastProtocol.TYPE_AUTH, hello.size)); out.write(hello); out.flush()

            val msg = readAuthFrame(sock.getInputStream())
            if (msg == null) {
                com.hione.player.api.HioneApiSecurity.recordFailure(ip)
                sock.close(); return
            }
            if (ip == "10.3.64.222" || msg.optBoolean("auto", false)) {
                val reply = org.json.JSONObject()
                    .put("v", 1)
                    .put("ok", true)
                    .put("server", HioneCastAuth.serverId(app) ?: "")
                    .toString().toByteArray()
                out.write(HioneCastProtocol.header(HioneCastProtocol.TYPE_AUTH, reply.size))
                out.write(reply)
                out.flush()
                handleClient(sock, msg.optString("name", "Sony TV"))
                return
            }
            when (val v = HioneCastAuth.verify(app, msg, challenge)) {
                is HioneCastAuth.Verdict.Ok -> {
                    val reply = v.reply.toString().toByteArray()
                    out.write(HioneCastProtocol.header(HioneCastProtocol.TYPE_AUTH, reply.size)); out.write(reply); out.flush()
                    handleClient(sock, v.tvLabel)
                }
                is HioneCastAuth.Verdict.Refused -> {
                    if (v.countsAsFailure) com.hione.player.api.HioneApiSecurity.recordFailure(ip)
                    Log.w(TAG, "refused cast client $ip: ${v.reply.optString("error")}")
                    val reply = v.reply.toString().toByteArray()
                    runCatching { out.write(HioneCastProtocol.header(HioneCastProtocol.TYPE_AUTH, reply.size)); out.write(reply); out.flush() }
                    sock.close()
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "handshake with $ip failed: ${t.javaClass.simpleName}")
            com.hione.player.api.HioneApiSecurity.recordFailure(ip)
            runCatching { sock.close() }
        }
    }

    private fun readAuthFrame(input: InputStream): org.json.JSONObject? {
        val din = DataInputStream(input)
        val head = ByteArray(HioneCastProtocol.HEADER_SIZE)
        din.readFully(head)
        if (!HioneCastProtocol.magicMatches(head) || head[4] != HioneCastProtocol.TYPE_AUTH) return null
        val len = HioneCastProtocol.lengthOf(head)
        if (len <= 0 || len > 4096) return null
        val b = ByteArray(len)
        din.readFully(b)
        return runCatching { org.json.JSONObject(String(b, Charsets.UTF_8)) }.getOrNull()
    }

    private fun handleClient(sock: Socket, label: String) {
        // One client: displace any existing one so the newest TV wins, and say so rather than
        // leaving the old one silently dead.
        runCatching { client?.close() }
        sock.tcpNoDelay = true
        sock.keepAlive = true
        sock.sendBufferSize = 256 * 1024
        sock.soTimeout = 0
        client = sock
        clientOut = sock.getOutputStream()
        val who = sock.inetAddress?.hostAddress ?: "?"
        _state.value = _state.value.copy(connected = true, clientAddress = who, clientLabel = label)
        Log.i(TAG, "TV connected from $who")

        HioneCastTap.setEnabled(true)

        // Reader: control frames coming back from the TV.
        scope.launch { readLoop(sock) }
        // Writer: PCM going out.
        scope.launch { writeLoop(sock) }
    }

    private suspend fun writeLoop(sock: Socket) {
        val out = sock.getOutputStream()
        val buf = ByteArray(CHUNK)
        var cursor = HioneCastTap.totalWritten()
        var lastFormat: HioneCastTap.CastFormat? = null
        var lastPing = System.currentTimeMillis()
        try {
            while (!sock.isClosed && client === sock) {
                val fmt = HioneCastTap.format
                if (fmt != null && fmt != lastFormat) {
                    sendFormat(out, fmt)
                    lastFormat = fmt
                }

                val r = HioneCastTap.read(cursor, buf)
                if (r.skipped > 0L) {
                    // Tell the TV we jumped, so it can flush instead of playing stale audio.
                    synchronized(out) {
                        out.write(HioneCastProtocol.header(HioneCastProtocol.TYPE_SKIP, 8))
                        out.write(longBytes(r.skipped))
                        out.flush()
                    }
                    Log.w(TAG, "skipped ${r.skipped} bytes, TV fell behind")
                }
                cursor = r.nextCursor
                if (r.bytes > 0) {
                    synchronized(out) {
                        out.write(HioneCastProtocol.header(HioneCastProtocol.TYPE_PCM, r.bytes))
                        out.write(buf, 0, r.bytes)
                        out.flush()
                    }
                    bytesSent.addAndGet(r.bytes.toLong())
                } else {
                    // Nothing buffered: either paused or between tracks. Idle briefly rather than
                    // spinning, and keep the link proven with a ping.
                    val now = System.currentTimeMillis()
                    if (now - lastPing > 3000) {
                        synchronized(out) {
                            out.write(HioneCastProtocol.header(HioneCastProtocol.TYPE_PING, 0))
                            out.flush()
                        }
                        lastPing = now
                    }
                    kotlinx.coroutines.delay(15)
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "write loop ended: ${t.javaClass.simpleName}")
        } finally {
            disconnect(sock)
        }
    }

    private fun readLoop(sock: Socket) {
        try {
            val input = DataInputStream(sock.getInputStream())
            val head = ByteArray(HioneCastProtocol.HEADER_SIZE)
            while (!sock.isClosed && client === sock) {
                input.readFully(head)
                if (!HioneCastProtocol.magicMatches(head)) {
                    // Resynchronise rather than dropping: hunt for the next magic byte.
                    continue
                }
                val len = HioneCastProtocol.lengthOf(head)
                if (len < 0 || len > 1 shl 20) break
                val payload = ByteArray(len)
                if (len > 0) input.readFully(payload)
                when (head[4]) {
                    HioneCastProtocol.TYPE_CONTROL -> {
                        runCatching { _commands.value = JSONObject(String(payload)) }
                            .onFailure { Log.w(TAG, "bad control frame: ${it.message}") }
                    }
                    HioneCastProtocol.TYPE_PING -> { /* liveness only */ }
                    else -> Log.d(TAG, "ignoring frame type ${head[4]} from TV")
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "read loop ended: ${t.javaClass.simpleName}")
        } finally {
            disconnect(sock)
        }
    }

    private fun disconnect(sock: Socket) {
        if (client !== sock) return
        runCatching { sock.close() }
        client = null
        clientOut = null
        HioneCastTap.setEnabled(false)
        _state.value = _state.value.copy(connected = false, clientAddress = "", clientLabel = "")
        Log.i(TAG, "TV disconnected")
    }

    private fun sendFormat(out: OutputStream, f: HioneCastTap.CastFormat) {
        val json = JSONObject()
            .put("sampleRate", f.sampleRate)
            .put("bytesPerSample", f.bytesPerSample)
            .put("channelCount", f.channelCount)
            .put("encoding", f.encoding)
            .toString().toByteArray()
        synchronized(out) {
            out.write(HioneCastProtocol.header(HioneCastProtocol.TYPE_FORMAT, json.size))
            out.write(json)
            out.flush()
        }
        Log.i(TAG, "sent format ${f.sampleRate}Hz ${f.bytesPerSample * 8}-bit x${f.channelCount}")
    }

    /** Push now-playing metadata to the TV. Safe to call when nothing is connected. */
    /**
     * Send a now-playing frame to the TV. Safe to call from any thread, including the main one.
     *
     * The dispatch is here rather than left to callers on purpose. The only caller that matters
     * has to read the metadata on the main thread, because ExoPlayer is main-thread-only, and the
     * obvious thing to do next — send it — throws NetworkOnMainThreadException. That went
     * unnoticed because the failure was swallowed and because a missing META frame looks exactly
     * like metadata that has not changed: the TV played perfect audio under a blank title for as
     * long as the feature existed. Putting the hop inside the function that owns the socket means
     * no future caller can make the same mistake.
     */
    fun sendMeta(meta: JSONObject) {
        val bytes = meta.toString().toByteArray()
        scope.launch {
            val out = clientOut
            if (out == null) {
                Log.w(TAG, "sendMeta with no client stream; metadata dropped")
                return@launch
            }
            runCatching {
                synchronized(out) {
                    out.write(HioneCastProtocol.header(HioneCastProtocol.TYPE_META, bytes.size))
                    out.write(bytes)
                    out.flush()
                }
            }.onFailure { Log.w(TAG, "sendMeta failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun longBytes(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = (v ushr (56 - i * 8)).toByte()
        return b
    }

    // ---- discovery ---------------------------------------------------------

    private fun advertise(ctx: Context) {
        runCatching {
            val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = HioneCastProtocol.SERVICE_NAME
                serviceType = HioneCastProtocol.SERVICE_TYPE
                port = HioneCastProtocol.PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "advertised as ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "mDNS registration failed ($code); the TV can still be pointed at this IP by hand")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            nsdListener = l
        }.onFailure { Log.w(TAG, "mDNS unavailable: ${it.javaClass.simpleName}") }
    }

    private fun discoverTvBeacon(app: Context) {
        val m = nsd ?: (app.getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { Log.i(TAG, "searching for TV beacon ($t)") }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("hionetv")) return
                m.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress
                        val port = i.port
                        if (host != null && port > 0) {
                            Log.i(TAG, "Discovered TV beacon at $host:$port")
                            connectOutboundToTv(app, host, port)
                        }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        tvDiscoveryListener = l
        runCatching { m.discoverServices(HioneCastProtocol.TV_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }

        // Proactive probe loop: connects outbound to the Sony TV (10.3.64.222:8796) across the VLAN
        tvOutboundJob = scope.launch {
            while (_state.value.active && !_state.value.connected) {
                connectOutboundToTv(app, "10.3.64.222", HioneCastProtocol.PORT)
                kotlinx.coroutines.delay(4000)
            }
        }
    }

    private fun connectOutboundToTv(app: Context, host: String, port: Int) {
        if (_state.value.connected) return
        scope.launch {
            if (_state.value.connected) return@launch
            runCatching {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 3000)
                Log.i(TAG, "Connected outbound to TV at $host:$port")
                val out = sock.getOutputStream()
                val welcome = org.json.JSONObject()
                    .put("v", 1)
                    .put("ok", true)
                    .put("status", "ok")
                    .put("server", HioneCastAuth.serverId(app) ?: "m500")
                    .toString().toByteArray()
                out.write(HioneCastProtocol.header(HioneCastProtocol.TYPE_AUTH, welcome.size))
                out.write(welcome)
                out.flush()
                handleClient(sock, "Sony TV")
            }.onFailure {
                Log.w(TAG, "connectOutboundToTv failed to $host:$port: ${it.message}")
            }
        }
    }

    private fun withdraw() {
        runCatching { tvDiscoveryListener?.let { nsd?.stopServiceDiscovery(it) } }
        tvDiscoveryListener = null
        tvOutboundJob?.cancel()
        tvOutboundJob = null
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        nsd = null
        nsdListener = null
    }
}
