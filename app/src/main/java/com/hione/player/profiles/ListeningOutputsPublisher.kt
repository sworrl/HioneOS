package com.hione.player.profiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tells the rest of HioneOS which listening profile each output uses, and takes EQ cuts back from
 * the Hardware app's measurement.
 *
 * PER-OUTPUT PROFILES. The profile per jack already exists here: a profile can be pinned to the
 * 3.5 mm or 4.4 mm jack (AutoSwitchRule WIRED_PORT), and otherwise a jack keeps the profile last
 * used on it (ListeningProfileStore.lastProfileForPort). Bluetooth and USB devices are claimed by
 * name or address rules. ListeningProfileManager switches on every route change. The built-in
 * speaker is never listening hardware.
 *
 * PUBLISHED AS Settings.Global "hione_listening_outputs", read-only for everyone (the Hardware
 * app's DAC graph reads it, same as hione_listening_profile):
 *   {"v":1, "active":"3.5mm",
 *    "outputs":[{"port":"3.5mm","label":"3.5 mm","connected":true,"pinned":true,
 *                "id":"custom-1a2b3c4d","name":"Jeep aux","type":"line-out","impedanceOhm":32,
 *                "eqEnabled":true,"eq":{"preamp":0,"bands":[{"type":"PK","fc":120,"gain":-3,"q":1.4}]},
 *                "digitalFilter":"fast_rolloff_phase_compensated","gain":"low","highPower":false,"dre":true}, ...]}
 * Ports: "3.5mm" and "4.4mm" always, then "usb:<name>" and "bt:<name>" for connected devices.
 *
 * EQ FROM A MEASUREMENT. Intent com.hione.player.profiles.SET_EQ with extras profileId and eq
 * (EqCurve JSON) sets that profile's EQ override and turns EQ on. Registered at runtime with
 * com.hione.permission.SYSTEM_BRIDGE (signature) as the sender permission, so only HioneOS apps
 * can send it. Works while Hione Music is running, which it is whenever music plays.
 */
object ListeningOutputsPublisher {
    private const val TAG = "ListeningOutputs"
    const val KEY = "hione_listening_outputs"
    const val ACTION_SET_EQ = "com.hione.player.profiles.SET_EQ"
    private const val PERMISSION = "com.hione.permission.SYSTEM_BRIDGE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start(ctx: Context) {
        if (started) return
        started = true
        val a = ctx.applicationContext
        scope.launch {
            combine(
                ListeningProfileStore.profiles,
                ListeningProfileStore.currentId,
                ListeningProfileManager.connectedOutputs,
            ) { _, _, outs -> outs }
                .debounce(300)
                .collect { outs -> runCatching { publish(a, outs) }.onFailure { Log.w(TAG, "publish failed", it) } }
        }
        runCatching {
            val filter = IntentFilter(ACTION_SET_EQ)
            val handler = Handler(Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(setEqReceiver, filter, PERMISSION, handler, Context.RECEIVER_EXPORTED)
            else a.registerReceiver(setEqReceiver, filter, PERMISSION, handler)
        }.onFailure { Log.w(TAG, "SET_EQ receiver not registered", it) }
    }

    private fun publish(ctx: Context, outs: List<ListeningProfileManager.Output>) {
        val all = ListeningProfileStore.all(ctx)
        val active = outs.lastOrNull()
        val arr = JSONArray()
        for ((port, label) in listOf(ListeningProfileStore.PORT_35 to "3.5 mm", ListeningProfileStore.PORT_44 to "4.4 mm")) {
            val pinned = all.firstOrNull { p -> p.autoSwitch.any { it.kind == AutoSwitchRule.Kind.WIRED_PORT && it.value == port } }
            val connected = outs.any { it.kind == ListeningProfileManager.Output.Kind.WIRED && it.port == port }
            val p = pinned
                ?: ListeningProfileStore.get(ctx, ListeningProfileStore.lastProfileForPort(ctx, port))
                ?: if (active?.port == port) ListeningProfileStore.current(ctx) else null
            arr.put(entry(port, label, connected, pinned != null, p))
        }
        for (o in outs) {
            if (o.kind == ListeningProfileManager.Output.Kind.WIRED) continue
            val port = (if (o.kind == ListeningProfileManager.Output.Kind.USB) "usb:" else "bt:") + o.name
            val p = ListeningProfileManager.resolve(ctx, o) ?: if (o.id == active?.id) ListeningProfileStore.current(ctx) else null
            arr.put(entry(port, o.label, true, p?.autoSwitch?.isNotEmpty() == true, p))
        }
        val activePort = when (active?.kind) {
            null -> null
            ListeningProfileManager.Output.Kind.WIRED -> active.port
            ListeningProfileManager.Output.Kind.USB -> "usb:" + active.name
            ListeningProfileManager.Output.Kind.BLUETOOTH -> "bt:" + active.name
        }
        val o = JSONObject().apply {
            put("v", 1)
            activePort?.let { put("active", it) }
            put("outputs", arr)
        }
        Settings.Global.putString(ctx.contentResolver, KEY, o.toString())
    }

    private fun entry(port: String, label: String, connected: Boolean, pinned: Boolean, p: ListeningProfile?): JSONObject = JSONObject().apply {
        put("port", port)
        put("label", label)
        put("connected", connected)
        put("pinned", pinned)
        if (p == null) return@apply
        val e = p.effective
        put("id", p.id)
        put("name", p.name)
        put("type", p.hardware.type.id)
        p.hardware.impedanceOhm?.let { put("impedanceOhm", it) }
        put("eqEnabled", e.eqEnabled == true)
        e.eq?.let { put("eq", it.toJson()) }
        e.digitalFilter?.let { put("digitalFilter", it) }
        e.gain?.let { put("gain", it) }
        e.highPower?.let { put("highPower", it) }
        e.dre?.let { put("dre", it) }
    }

    private val setEqReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getStringExtra("profileId") ?: return
            val eq = runCatching { EqCurve.fromJson(JSONObject(i.getStringExtra("eq") ?: return)) }.getOrNull() ?: return
            val p = ListeningProfileStore.get(c, id) ?: return
            // Bounded: the Hardware app only sends cuts, but never trust a broadcast with a boost.
            val safe = eq.copy(bands = eq.bands.take(10).map { it.copy(gainDb = it.gainDb.coerceIn(-12.0, 0.0)) }, preampDb = eq.preampDb.coerceIn(-12.0, 0.0))
            ListeningProfileStore.setOverrides(c, id, p.overrides.copy(eqEnabled = true, eq = safe))
            Log.i(TAG, "EQ from measurement set on $id: ${safe.bands.size} bands")
            val updated = ListeningProfileStore.get(c, id) ?: return
            if (ListeningProfileStore.currentId.value == id) {
                scope.launch { ListeningProfileManager.applySettings(c.applicationContext, updated.effective) }
            }
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(c.applicationContext, "${p.name}: EQ set from the measurement", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
