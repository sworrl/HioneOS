package com.hione.launcher.vpn

import android.content.Context
import android.content.Intent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

/**
 * Native WireGuard client for HioneOS — brings a tunnel up/down in-process via the embeddable
 * wireguard-go backend, so the user can VPN back to their home network (e.g. a CGNAT'd UDR via a
 * relay) without a separate WireGuard app. State-only for v1; live rx/tx throughput is a follow-up
 * once the Statistics API is confirmed against the resolved lib.
 *
 * CGNAT note: a direct tunnel to a CGNAT'd endpoint can't work (no inbound). Point this at a
 * reachable endpoint or a VPS relay that the home router also dials out to. UniFi Teleport's relay
 * is WiFiman-proprietary — see [HioneVpnStore.WIFIMAN_PKG] for the handoff.
 */
object HioneWireGuardManager {

    data class WgStatus(
        val state: Tunnel.State = Tunnel.State.DOWN,
        val activeName: String? = null,
        val lastError: String? = null
    )

    private val _status = MutableStateFlow(WgStatus())
    val status: StateFlow<WgStatus> = _status.asStateFlow()

    private var backend: Backend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = _status.value.activeName ?: "hione"
        override fun onStateChange(newState: Tunnel.State) {
            _status.value = _status.value.copy(state = newState)
        }
    }

    private fun backend(ctx: Context): Backend =
        backend ?: GoBackend(ctx.applicationContext).also { backend = it }

    /**
     * VPN consent: returns an Intent that MUST be launched from an Activity (startActivityForResult)
     * the first time, or null if consent was already granted. Bring the tunnel UP only after this
     * returns null / the user has accepted.
     */
    fun consentIntent(ctx: Context): Intent? = android.net.VpnService.prepare(ctx)

    /** Generate a fresh keypair; returns (privateKeyBase64, publicKeyBase64) for the guided form. */
    fun generateKeyPair(): Pair<String, String> {
        val kp = KeyPair()
        return kp.privateKey.toBase64() to kp.publicKey.toBase64()
    }

    /** Bring a tunnel UP from a full .conf. Runs off the main thread. Returns success/failure. */
    suspend fun connect(ctx: Context, name: String, configText: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val cfg = Config.parse(BufferedReader(StringReader(configText)))
                _status.value = _status.value.copy(activeName = name, lastError = null)
                // Use the state the BACKEND reports. This used to discard setState()'s return and
                // hard-set UP, so any non-UP result that did not throw still lit "● CONNECTED" and
                // the status-bar VPN lock.
                val resulting = backend(ctx).setState(tunnel, Tunnel.State.UP, cfg)
                _status.value = _status.value.copy(state = resulting, activeName = name)
                if (resulting == Tunnel.State.UP) Result.success(Unit)
                else Result.failure(IllegalStateException("tunnel did not come up (state=$resulting)"))
            } catch (t: Throwable) {
                _status.value = _status.value.copy(state = Tunnel.State.DOWN, lastError = t.message ?: "connect failed")
                Result.failure(t)
            }
        }

    /** Bring the tunnel DOWN. Runs off the main thread. */
    suspend fun disconnect(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            backend(ctx).setState(tunnel, Tunnel.State.DOWN, null)
            _status.value = _status.value.copy(state = Tunnel.State.DOWN)
            Result.success(Unit)
        } catch (t: Throwable) {
            _status.value = _status.value.copy(lastError = t.message ?: "disconnect failed")
            Result.failure(t)
        }
    }

    // ── Guided reverse-tunnel-to-home helpers ────────────────────────────────────────────────

    /**
     * Assemble a complete .conf from structured parts so the user never hand-edits key material.
     * [assignedIp] is the address this device holds on the home WG subnet (e.g. "10.7.7.9/32"),
     * [endpoint] is the reachable home endpoint or relay (host:port), [peerPublicKey] is the home
     * router/relay's public key, and [allowedIps] are the home LAN subnets to route back (the
     * "reverse path" — e.g. "10.7.7.0/24,192.168.1.0/24"). A blank [dns] omits the DNS line.
     * [keepalive] defaults to 25s so a CGNAT/relay path stays punched open from this side.
     */
    fun buildConf(
        privateKey: String,
        assignedIp: String,
        endpoint: String,
        peerPublicKey: String,
        allowedIps: String,
        dns: String = "",
        keepalive: Int = 25
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${privateKey.trim()}")
        appendLine("Address = ${assignedIp.trim()}")
        if (dns.isNotBlank()) appendLine("DNS = ${dns.trim()}")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${peerPublicKey.trim()}")
        appendLine("Endpoint = ${endpoint.trim()}")
        appendLine("AllowedIPs = ${allowedIps.trim().ifBlank { "0.0.0.0/0" }}")
        appendLine("PersistentKeepalive = $keepalive")
    }

    /** True when a tunnel is currently UP (or transitioning up). */
    fun isUp(): Boolean = _status.value.state == Tunnel.State.UP

    /**
     * Connect using a persisted tunnel by [name] from [HioneVpnStore]. No-op success if that tunnel
     * is already the active one and UP. Returns failure if no such saved tunnel exists.
     */
    suspend fun connectSaved(ctx: Context, name: String): Result<Unit> {
        if (isUp() && _status.value.activeName == name) return Result.success(Unit)
        val conf = HioneVpnStore.getConf(ctx, name)
            ?: return Result.failure(IllegalStateException("no saved tunnel '$name'"))
        return connect(ctx, name, conf)
    }

    /**
     * Save [confText] under [name] AND bring it up in one guided step. The .conf (which holds the
     * private key) only ever lands in the encrypted store, never plaintext prefs.
     */
    suspend fun saveAndConnect(ctx: Context, name: String, confText: String): Result<Unit> {
        HioneVpnStore.save(ctx, name, confText)
        return connect(ctx, name, confText)
    }

    /**
     * Whether VPN consent has already been granted (so a headless auto-connect is safe). When this
     * is false the caller must launch [consentIntent] from an Activity first. On a platform-signed
     * HioneOS build the consent can instead be pre-granted by registering as always-on VPN — see
     * [registerAlwaysOn].
     */
    fun hasConsent(ctx: Context): Boolean = consentIntent(ctx) == null

    /**
     * Register this app as the system "always-on VPN" so the reverse tunnel is brought up by the
     * framework automatically and survives reboots without the per-launch consent dialog. Only
     * works when HioneOS grants us the signature/privileged permission to write these secure
     * settings (we own the OS); silently returns false otherwise so unprivileged builds degrade to
     * the manual consent flow. [lockdown] true = block all traffic while the tunnel is down.
     */
    fun registerAlwaysOn(ctx: Context, lockdown: Boolean = false): Boolean = try {
        val pkg = ctx.applicationContext.packageName
        val cr = ctx.applicationContext.contentResolver
        android.provider.Settings.Secure.putString(cr, "always_on_vpn_app", pkg)
        android.provider.Settings.Secure.putInt(cr, "always_on_vpn_lockdown", if (lockdown) 1 else 0)
        // VERIFY. It used to return true whenever the two writes did not throw — and a write that
        // lands is still not proof that VpnManagerService accepted us as the always-on app, so the
        // checkbox stayed ticked either way.
        android.provider.Settings.Secure.getString(cr, "always_on_vpn_app") == pkg
    } catch (_: Throwable) {
        false
    }
}

/**
 * Encrypted-at-rest store for saved WireGuard tunnels. A .conf holds the PRIVATE KEY, so it lives
 * only in an AES256-GCM EncryptedSharedPreferences file whose key is in the Android Keystore —
 * never in plaintext prefs, never anything committable. Mirrors the arco ArcoSecureStore pattern.
 */
object HioneVpnStore {
    private const val PREFS = "hione_vpn_store"
    private const val KEY_NAMES = "tunnel_names"
    private const val PREFIX_CONF = "conf_"

    /** WiFiman package for the UniFi Teleport handoff (Teleport's relay is WiFiman-only). */
    const val WIFIMAN_PKG = "com.ubnt.usurvey"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx.applicationContext,
        PREFS,
        MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun names(ctx: Context): List<String> =
        prefs(ctx).getStringSet(KEY_NAMES, emptySet())!!.sorted()

    fun getConf(ctx: Context, name: String): String? =
        prefs(ctx).getString(PREFIX_CONF + name, null)

    fun save(ctx: Context, name: String, confText: String) {
        val p = prefs(ctx)
        val set = p.getStringSet(KEY_NAMES, emptySet())!!.toMutableSet().apply { add(name) }
        p.edit().putStringSet(KEY_NAMES, set).putString(PREFIX_CONF + name, confText).apply()
    }

    fun delete(ctx: Context, name: String) {
        val p = prefs(ctx)
        val set = p.getStringSet(KEY_NAMES, emptySet())!!.toMutableSet().apply { remove(name) }
        p.edit().putStringSet(KEY_NAMES, set).remove(PREFIX_CONF + name).apply()
    }
}
