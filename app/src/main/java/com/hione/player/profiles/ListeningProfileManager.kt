package com.hione.player.profiles

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.hione.player.CirrusLogicManager
import com.hione.player.PlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Applies listening profiles and makes them follow the hardware.
 *
 * FOLLOWING THE HARDWARE. The output that connected most recently is "what you are listening
 * on"; when it goes away, the previous one still connected takes over. Bluetooth and USB devices
 * name themselves, so a profile claims them by rule (or, for Bluetooth, by an unambiguous name
 * match against a built-in). Wired jacks cannot identify the headphone, so a jack uses, in order:
 * the profile pinned to that port, the profile last used on that port, the current profile.
 * The built-in speaker is never "listening hardware" and is ignored.
 *
 * WHAT APPLYING DOES. DAC knobs go through CirrusLogicManager (which now also drives the real
 * property route, HibyDacBridge); fields a profile leaves null are not touched. The EQ goes to
 * [HioneEq]. Profiles for Bluetooth-only hardware carry no DAC knobs because the M500's DAC is not
 * in that path. With several outputs playing at once (HioneMirrorOutput) there is one EQ chain,
 * so every output hears the current profile's EQ.
 */
object ListeningProfileManager {
    private const val TAG = "ListeningProfiles"

    data class Output(val id: Int, val kind: Kind, val name: String, val address: String, val port: String?) {
        enum class Kind { WIRED, BLUETOOTH, USB }
        val label: String get() = when (kind) {
            Kind.WIRED -> port ?: "Wired"
            Kind.USB -> "USB $name".trim()
            Kind.BLUETOOTH -> name.ifBlank { "Bluetooth" }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null

    /** Outputs in connection order; the last one is what you are listening on. */
    private val connected = mutableListOf<Output>()

    private val _activeOutput = MutableStateFlow<Output?>(null)
    val activeOutput: StateFlow<Output?> = _activeOutput

    private val _connectedOutputs = MutableStateFlow<List<Output>>(emptyList())
    val connectedOutputs: StateFlow<List<Output>> = _connectedOutputs

    fun init(ctx: Context) {
        HibyDacBridge.init(ctx)
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        ListeningProfileStore.load(a)
        // Profile per output for the rest of the OS (Settings.Global hione_listening_outputs).
        ListeningOutputsPublisher.start(a)
        // The live EQ starts as the current profile's (unsaved edits do not survive a restart).
        ListeningProfileStore.current(a)?.effective?.let { HioneEq.set(it.eqEnabled == true, it.eq ?: EqCurve.FLAT) }
        HioneEq.onArmChanged = { requestReflush() }
        scope.launch {
            // vendor.audio.hiby.digital_filter is a plain (non-persist) property and nothing in
            // HiBy's boot code restores it, so after a reboot the DAC is on the kernel default
            // whatever the app says. Put the user's last choice back.
            runCatching { HibyDacBridge.set(HibyDacBridge.PROP_FILTER, CirrusLogicManager.getDigitalFilter(a).id) }
        }
        val am = a.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // registerAudioDeviceCallback reports the already-connected devices first; those seed the
        // list without announcing anything.
        seeding = true
        am.registerAudioDeviceCallback(deviceCallback, main)
        main.post { seeding = false; settle(a, announce = false) }
    }

    @Volatile private var seeding = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val a = app ?: return
            var changed = false
            for (d in addedDevices) {
                val o = toOutput(d) ?: continue
                if (connected.any { it.id == o.id }) continue
                if (seeding) {
                    // No connect times for what was already there: order by the same priority the
                    // mirror uses for the primary track (wired, then USB, then Bluetooth), so the
                    // best output ends up last = active.
                    connected.add(o)
                    connected.sortBy { seedRank(it.kind) }
                } else {
                    connected.add(o)
                }
                changed = true
            }
            if (changed) {
                publishOutputs()
                if (!seeding) settle(a, announce = true)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val a = app ?: return
            val before = connected.lastOrNull()
            connected.removeAll { c -> removedDevices.any { it.id == c.id } }
            publishOutputs()
            if (connected.lastOrNull()?.id != before?.id) settle(a, announce = true)
        }
    }

    private fun seedRank(k: Output.Kind) = when (k) {
        Output.Kind.BLUETOOTH -> 0
        Output.Kind.USB -> 1
        Output.Kind.WIRED -> 2
    }

    private fun publishOutputs() {
        _connectedOutputs.value = connected.toList()
        _activeOutput.value = connected.lastOrNull()
    }

    private fun toOutput(d: AudioDeviceInfo): Output? {
        if (!d.isSink) return null
        val name = d.productName?.toString().orEmpty()
        val address = runCatching { d.address }.getOrNull().orEmpty()
        return when (d.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                // HiBy names the balanced jack "balance..." (same test as HioneOutputVolumes.label).
                Output(d.id, Output.Kind.WIRED, name, address,
                    if (name.contains("balance", ignoreCase = true)) ListeningProfileStore.PORT_44 else ListeningProfileStore.PORT_35)
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE ->
                Output(d.id, Output.Kind.USB, name.removePrefix("USB-Audio - ").trim(), address, null)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                Output(d.id, Output.Kind.BLUETOOTH, name, address, null)
            else -> null
        }
    }

    /** Work out which profile the active output wants and switch to it if that is a change. */
    private fun settle(ctx: Context, announce: Boolean) {
        val out = connected.lastOrNull() ?: return
        val profile = resolve(ctx, out) ?: return
        val current = ListeningProfileStore.current(ctx)
        if (profile.id == current?.id) return
        Log.i(TAG, "${out.label} connected -> ${profile.name}")
        switchTo(ctx, profile, announce = announce)
    }

    fun resolve(ctx: Context, out: Output): ListeningProfile? {
        val all = ListeningProfileStore.all(ctx)
        all.firstOrNull { p -> p.autoSwitch.any { matches(it, out) } }?.let { return it }
        return when (out.kind) {
            Output.Kind.WIRED -> {
                val last = out.port?.let { ListeningProfileStore.lastProfileForPort(ctx, it) }
                ListeningProfileStore.get(ctx, last) ?: ListeningProfileStore.current(ctx)
            }
            Output.Kind.BLUETOOTH -> {
                // Name match against built-ins, only when exactly one fits: "MOMENTUM 4" is the
                // Momentum 4 Wireless; an ambiguous name switches nothing.
                val dev = norm(out.name)
                if (dev.length < 4) null else all.filter { p ->
                    p.builtIn && p.hardware.connection.isBluetooth && norm(p.hardware.model).let { m ->
                        m.length >= 4 && (m == dev || m.contains(dev) || dev.contains(m))
                    }
                }.singleOrNull()
            }
            Output.Kind.USB -> null
        }
    }

    private fun matches(rule: AutoSwitchRule, out: Output): Boolean = when (rule.kind) {
        AutoSwitchRule.Kind.BT_NAME -> out.kind == Output.Kind.BLUETOOTH && norm(out.name).let { n ->
            val v = norm(rule.value); v.isNotEmpty() && (n == v || (v.length >= 3 && n.contains(v)))
        }
        AutoSwitchRule.Kind.BT_ADDRESS -> out.kind == Output.Kind.BLUETOOTH && out.address.isNotEmpty() &&
            // Android 14 may hand us an anonymised address (XX:XX:XX:XX:AB:CD); compare what is visible.
            (out.address.equals(rule.value, true) || out.address.takeLast(5).equals(rule.value.takeLast(5), true))
        AutoSwitchRule.Kind.USB_NAME -> out.kind == Output.Kind.USB && norm(out.name).contains(norm(rule.value))
        AutoSwitchRule.Kind.WIRED_PORT -> out.kind == Output.Kind.WIRED && out.port == rule.value
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // ------------------------------------------------------------------------------ actions

    /**
     * Make [profile] current. [apply] = false keeps the live settings (used by "keep my current
     * settings", which saves them into the profile instead).
     */
    fun switchTo(ctx: Context, profile: ListeningProfile, apply: Boolean = true, announce: Boolean = true) {
        val a = ctx.applicationContext
        ListeningProfileStore.setCurrent(a, profile.id)
        connected.lastOrNull()?.takeIf { it.kind == Output.Kind.WIRED }?.port?.let {
            ListeningProfileStore.rememberPort(a, it, profile.id)
        }
        if (!apply) {
            onProfileApplied(profile)
            return
        }
        scope.launch {
            applySettings(a, profile.effective)
            if (announce) toast(a, "Profile: ${profile.name} (${summary(profile.effective)})")
            onProfileApplied(profile)
        }
    }

    /** Push [s] to the hardware and the EQ. Null fields are left as they are. */
    suspend fun applySettings(ctx: Context, s: DspSettings) = withContext(Dispatchers.IO) {
        s.digitalFilter?.let { id ->
            CirrusLogicManager.DigitalFilter.entries.firstOrNull { it.id == id }?.let { CirrusLogicManager.setDigitalFilter(ctx, it) }
        }
        s.gain?.let { g ->
            CirrusLogicManager.GainMode.entries.firstOrNull { it.sysfsValue == g }?.let { CirrusLogicManager.setGainMode(ctx, it) }
        }
        s.highPower?.let { CirrusLogicManager.setHighPowerEnabled(ctx, it) }
        s.dre?.let { CirrusLogicManager.setDreEnabled(ctx, it) }
        s.dsdGainComp?.let { CirrusLogicManager.setDsdGainCompensate(ctx, it) }
        if (s.eqEnabled != null || s.eq != null) {
            val st = HioneEq.state.value
            HioneEq.set(s.eqEnabled ?: st.enabled, s.eq ?: st.curve)
        }
    }

    /** What the device is set to right now, read the same way the hardware screen reads it. */
    suspend fun liveSnapshot(ctx: Context): DspSettings = withContext(Dispatchers.IO) {
        val eq = HioneEq.state.value
        DspSettings(
            digitalFilter = runCatching { CirrusLogicManager.getDigitalFilter(ctx).id }.getOrNull(),
            gain = runCatching { CirrusLogicManager.getGainMode(ctx).sysfsValue }.getOrNull(),
            highPower = runCatching { CirrusLogicManager.isHighPowerEnabled(ctx) }.getOrNull(),
            dre = runCatching { CirrusLogicManager.isDreEnabled(ctx) }.getOrNull(),
            // Not snapshotted: it reaches nothing on this firmware (DspKnob.DSD_GAIN_COMP), and
            // reading its default would record a "change" against every built-in.
            dsdGainComp = null,
            // "On but flat" sounds exactly like "off", so it is saved as off; the curve is only
            // captured when it is actually heard.
            eqEnabled = eq.audible,
            eq = if (eq.audible) eq.curve else null,
        )
    }

    /**
     * "Save my current settings as my defaults for this hardware": snapshot the live state and
     * store only what differs from the hardware's recommended settings. For a Bluetooth profile
     * the DAC knobs are not part of what you hear, so only the EQ is captured.
     */
    suspend fun saveLiveAsDefaults(ctx: Context, profileId: String): DspSettings {
        val p = ListeningProfileStore.get(ctx, profileId) ?: return DspSettings()
        var live = liveSnapshot(ctx)
        if (!p.hardware.connection.isWired) live = live.copy(digitalFilter = null, gain = null, highPower = null, dre = null, dsdGainComp = null)
        val overrides = live.diffAgainst(p.recommended)
        ListeningProfileStore.setOverrides(ctx, profileId, overrides)
        return overrides
    }

    /** Drop the user's overrides; re-apply when this is the current profile. */
    fun resetToRecommended(ctx: Context, profileId: String) {
        ListeningProfileStore.setOverrides(ctx, profileId, DspSettings())
        val p = ListeningProfileStore.get(ctx, profileId) ?: return
        if (ListeningProfileStore.currentId.value == profileId) {
            scope.launch {
                applySettings(ctx.applicationContext, p.effective)
                toast(ctx.applicationContext, "Profile: ${p.name} (reset to recommended)")
                onProfileApplied(p)
            }
        }
    }

    /**
     * Called after a profile becomes current (applied or not). Intentionally a no-op beyond the
     * log line: this is the place for a later OS-wide status broadcast or a voice announcement,
     * so they hook one function instead of every switch path.
     */
    fun onProfileApplied(profile: ListeningProfile) {
        Log.i(TAG, "profile applied: ${profile.id} (${profile.name}) ${summary(profile.effective)}")
        // Published for the rest of the OS (the launcher's anatomy screen, SystemUI): they cannot
        // read this app's files, and Hione Music already writes its other hione_* keys here.
        app?.contentResolver?.let { cr ->
            runCatching {
                android.provider.Settings.Global.putString(cr, "hione_listening_profile", profile.name)
                android.provider.Settings.Global.putString(cr, "hione_listening_profile_summary", summary(profile.effective))
            }
        }
    }

    fun summary(s: DspSettings): String = buildList {
        s.digitalFilter?.let { add(filterShort(it)) }
        s.gain?.let { add(if (it == "low") "Low gain" else "High gain") }
        if (s.highPower == true) add("High power")
        if (s.eqAudible) add("EQ ${s.eq?.bands?.count { kotlin.math.abs(it.gainDb) > 1e-9 } ?: 0} bands")
        else if (s.eqEnabled != null) add("EQ off")
    }.joinToString(", ").ifEmpty { "no DAC changes" }

    fun filterShort(id: String): String = when (id) {
        "nos" -> "NOS"
        "fast_rolloff_phase_compensated" -> "Fast linear"
        "fast_rolloff_low_latency" -> "Fast low-latency"
        "slow_rolloff_phase_compensated" -> "Slow linear"
        "slow_rolloff_low_latency" -> "Slow low-latency"
        else -> id
    }

    private fun toast(ctx: Context, msg: String) {
        main.post { runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } }
    }

    /**
     * The EQ processor only rejoins Media3's pipeline at a flush (see HioneEq). A seek to where we
     * already are is the cheapest flush the player offers; it costs a brief rebuffer, once, when
     * the EQ goes from silent to audible.
     */
    private fun requestReflush() {
        main.post {
            runCatching {
                val p = PlayerHolder.player ?: return@runCatching
                if (p.currentMediaItem != null && p.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                    p.playbackState != androidx.media3.common.Player.STATE_ENDED) {
                    p.seekTo(p.currentPosition)
                }
            }.onFailure { Log.w(TAG, "reflush failed", it) }
        }
    }
}
