package com.m500.hardware

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val HwHioneTeal get() = Mascot.color(0xFFFFC56B)
val HwHionePink get() = Mascot.color(0xFF6FB0FF)
val HwBgDark get() = Mascot.color(0xFF121318)
val HwSurface1 get() = Mascot.color(0xFF0B242A)
val HwSurface2 get() = Mascot.color(0xFF13363E)
val HwMuted = Color(0xFF8BA7AB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HardwareSettingsScreen(onBack = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRooted by remember { mutableStateOf(false) }
    // null = not probed yet; true/false = whether com.hione.sysbridge is installed
    var bridgeOk by remember { mutableStateOf<Boolean?>(null) }
    // What the DAC was last told (persist.vendor.audio.hione.*), and the last failed set, if any
    var dacApplied by remember { mutableStateOf("") }
    var dacProblem by remember { mutableStateOf<String?>(null) }

    // Cirrus Logic CS43198 DAC States — null until READ from the persist props / Settings.Global. Nothing is
    // pre-selected from a local default (the old NOS / LOW / +6 dB defaults were shown as if real).
    var csFilter by remember { mutableStateOf<CirrusLogicManager.DigitalFilter?>(null) }
    var csGain by remember { mutableStateOf<CirrusLogicManager.GainMode?>(null) }
    var csDre by remember { mutableStateOf<Boolean?>(null) }
    var csTurbo by remember { mutableStateOf<Boolean?>(null) }
    var csDsdComp by remember { mutableStateOf<Int?>(null) }
    var csOutput by remember { mutableStateOf<CirrusLogicManager.OutputMode?>(null) }
    var csBalance by remember { mutableStateOf<Float?>(null) }
    // Pulsar: whether the diode can be driven AT ALL on this unit (probed, not assumed).
    var pulsarDrivable by remember { mutableStateOf<Boolean?>(null) }

    // Fn Switch & Pocket Lock
    var fnMode by remember {
        mutableStateOf(Settings.Global.getString(ctx.contentResolver, "fn_settings") ?: "touch_and_key_lock")
    }
    var allowVolumeWheel by remember {
        mutableStateOf(Settings.Global.getInt(ctx.contentResolver, "m500_fn_allow_volume_wheel", 0) == 1)
    }


    // USB DAC
    var usbDacActive by remember { mutableStateOf(UsbDacManager.isActive(ctx)) }
    // Nullable: null = the user has never picked a rate/depth. The non-null getters fall back to
    // 192000 / 32, and these chips were rendering that build default as a highlighted selection.
    var usbDacRate by remember { mutableStateOf<Int?>(UsbDacManager.getSampleRateOrNull(ctx)) }
    var usbDacBits by remember { mutableStateOf<Int?>(UsbDacManager.getBitDepthOrNull(ctx)) }

    // CPU power profile: the Settings.Global row, the charger, and the caps the kernel reports.
    var cpuProfile by remember { mutableStateOf(CpuPerformance.current(ctx)) }
    var cpuCharging by remember { mutableStateOf(CpuPerformance.isCharging(ctx)) }
    var cpuCaps by remember { mutableStateOf(CpuPerformance.caps()) }
    var cpuProblem by remember { mutableStateOf<String?>(null) }

    // init applies the caps in the background after sysbridge sets the property, so read the
    // caps again a moment later as well.
    suspend fun reloadCpu() {
        repeat(3) { n ->
            withContext(Dispatchers.IO) {
                cpuProfile = CpuPerformance.current(ctx)
                cpuCharging = CpuPerformance.isCharging(ctx)
                cpuCaps = CpuPerformance.caps()
            }
            if (n < 2) kotlinx.coroutines.delay(if (n == 0) 400L else 1200L)
        }
    }

    // Hione Music changes the profile on its own (playback, screen, charger).
    DisposableEffect(Unit) {
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scope.launch { reloadCpu() } }
        }
        runCatching { ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(CpuPerformance.KEY_PROFILE), false, obs) }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(obs) } }
    }

    // Re-read the four live knobs. Runs after every set, so a control that did not apply snaps
    // back to what the DAC actually has.
    suspend fun reloadDac() = withContext(Dispatchers.IO) {
        bridgeOk = DacBridge.available(ctx)
        csFilter = CirrusLogicManager.getDigitalFilter(ctx)
        csGain = CirrusLogicManager.getGainMode(ctx)
        csDre = CirrusLogicManager.isDreEnabled(ctx)
        csTurbo = CirrusLogicManager.isHighPowerEnabled(ctx)
        dacApplied = CirrusLogicManager.appliedSummary()
    }
    // The screen's colors come from the applied combo (dacTheme, same function as the status bar
    // badge and the SystemUI tiles): filter sets the hue, gain the saturation, DRE the second
    // accent, high power the glow and a slow pulse. Changes fade over 450 ms.
    val dacPal = remember(csFilter, csGain, csDre, csTurbo) { dacTheme(csFilter?.id, csGain?.id, csDre, csTurbo) }
    val dacPrimary by animateColorAsState(Color(dacPal.primary), tween(450), label = "dacPrimary")
    val dacSecondary by animateColorAsState(Color(dacPal.secondary), tween(450), label = "dacSecondary")
    val dacBg by animateColorAsState(Color(dacPal.background), tween(450), label = "dacBg")
    val dacGlowBase by animateFloatAsState(dacPal.glow, tween(450), label = "dacGlow")
    val dacGlow = if (dacPal.pulse) {
        val t = rememberInfiniteTransition(label = "dacPulse")
        dacGlowBase * t.animateFloat(0.7f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "dacPulseV").value
    } else dacGlowBase

    // A change made somewhere else (Hione Music, SystemUI tiles) lands in hione_dac_state.
    DisposableEffect(Unit) {
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scope.launch { reloadDac() } }
        }
        runCatching { ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor("hione_dac_state"), false, obs) }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(obs) } }
    }

    fun applyDac(block: suspend () -> DacBridge.Result) {
        scope.launch {
            val r = block()
            dacProblem = DacBridge.lastProblem.takeIf { r != DacBridge.Result.CONFIRMED }
            reloadDac()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            isRooted = android.os.Process.myUid() == 1000 || RootShell.isAvailable() || ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            try {
                val targetRes = ctx.packageManager.getResourcesForApplication("com.android.settings")
                val idTitle = targetRes.getIdentifier("fn_function_title", "array", "com.android.settings")
                val idValue = targetRes.getIdentifier("fn_function_value", "array", "com.android.settings")
                val titles = targetRes.getStringArray(idTitle)
                val values = targetRes.getStringArray(idValue)
                Log.i("SettingsOverlayCheck", "Titles ($idTitle): ${titles.toList()}")
                Log.i("SettingsOverlayCheck", "Values ($idValue): ${values.toList()}")
            } catch (e: Throwable) {
                Log.e("SettingsOverlayCheck", "Failed to query settings resources", e)
            }
            reloadDac()
            csDsdComp = CirrusLogicManager.getDsdGainCompensate(ctx)
            csOutput = CirrusLogicManager.getOutputMode(ctx)
            // getBalance() substitutes 0 when nothing is readable, which the UI then printed as
            // "Center" on an enabled slider. Only show a position when a real source reported one.
            csBalance = CirrusLogicManager.getBalanceOrNull(ctx)?.toFloat()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "M500 HARDWARE & DAC",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = HwHioneTeal)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = dacBg)
            )
        },
        containerColor = dacBg
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Blackjack: which driver runs the DACs and what it is doing now. Tap for the Monitor.
            item {
                Spacer(Modifier.height(4.dp))
                BlackjackStatusCard(dacPrimary, dacSecondary, dacBg) {
                    ctx.startActivity(android.content.Intent(ctx, BlackjackMonitorActivity::class.java))
                }
            }

            // Status Card
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(lerp(dacBg, dacPrimary, 0.10f), lerp(dacBg, dacSecondary, 0.22f))))
                        .border((1f + dacGlow * 1.5f).dp, Brush.linearGradient(listOf(dacPrimary.copy(alpha = 0.3f + 0.6f * dacGlow), dacSecondary.copy(alpha = 0.3f + 0.6f * dacGlow))), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    // The combo in plain words, in the combo's own color.
                    Text(dacPal.label, color = dacPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 23.sp)
                    Spacer(Modifier.height(10.dp))
                    // Status reflects what was actually probed: whether the bridge is installed,
                    // and the values the DAC was last told (persist.vendor.audio.hione.*).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(11.dp).clip(CircleShape).background(when (bridgeOk) { true -> Mascot.color(0xFF1ABC9C); false -> Color(0xFFF1C40F); null -> HwMuted }))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (bridgeOk) {
                                null -> "Checking the DAC control path…"
                                true -> "DAC settings go through the ${Mascot.osName} system bridge"
                                false -> "${Mascot.osName} system bridge not found"
                            },
                            color = if (bridgeOk == true) dacSecondary else HwMuted,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (bridgeOk) {
                            true -> "Applied now. $dacApplied"
                            false -> "com.hione.sysbridge is not installed, so filter, gain, DRE and high power cannot change. Controls show the last saved choice."
                            null -> "Reading the DAC settings…"
                        },
                        color = HwMuted,
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    )
                    dacProblem?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = Color(0xFFF1C40F), fontSize = 14.sp, lineHeight = 19.sp)
                    }
                }
            }

            // Section 1: Cirrus Logic Dual CS43198 MasterHIFI™ Audio Architecture
            item {
                HwSettingsSection("Cirrus Logic Dual CS43198 DAC")

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(lerp(HwSurface1, dacBg, 0.5f))
                        .border(1.dp, dacPrimary.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Text("DIGITAL FILTER", color = dacPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    CirrusLogicManager.DigitalFilter.values().forEach { filter ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    csFilter = filter
                                    applyDac { CirrusLogicManager.setDigitalFilter(ctx, filter) }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = csFilter == filter,
                                onClick = {
                                    csFilter = filter
                                    applyDac { CirrusLogicManager.setDigitalFilter(ctx, filter) }
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = dacPrimary, unselectedColor = HwMuted)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(filter.label, color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(filter.description, color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("HEADPHONE GAIN", color = dacPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CirrusLogicManager.GainMode.values().forEach { gain ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (csGain == gain) dacPrimary else HwSurface2)
                                    .clickable {
                                        csGain = gain
                                        applyDac { CirrusLogicManager.setGainMode(ctx, gain) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(gain.label, color = if (csGain == gain) Color.Black else Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("OUTPUT ROUTING (3.5mm SE / 4.4mm BAL)", color = dacPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CirrusLogicManager.OutputMode.values().forEach { mode ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (csOutput == mode) HwHionePink else HwSurface2)
                                    .clickable {
                                        csOutput = mode
                                        scope.launch { CirrusLogicManager.setOutputMode(ctx, mode) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(mode.label, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    HwSettingsToggleRow(
                        title = "Dynamic Range Enhancement (DRE)",
                        subtitle = if (csDre == null) "Not set yet" else "Lowers the noise floor at low volume",
                        checked = csDre == true,
                        accent = dacPrimary
                    ) { enabled ->
                        csDre = enabled
                        applyDac { CirrusLogicManager.setDreEnabled(ctx, enabled) }
                    }

                    HwSettingsToggleRow(
                        title = "High power mode",
                        subtitle = if (csTurbo == null) "Not set yet" else "Switches in the external amp stage. The same volume step gets louder, so music volume is lowered to 30% when you turn it on",
                        checked = csTurbo == true,
                        accent = dacPrimary
                    ) { enabled ->
                        csTurbo = enabled
                        applyDac { CirrusLogicManager.setHighPowerEnabled(ctx, enabled) }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(if (csDsdComp == null) "DSD GAIN COMPENSATION (not set)" else "DSD GAIN COMPENSATION", color = dacPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "0 dB (Direct)", 6 to "+6 dB (SACD level)").forEach { (db, label) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (csDsdComp == db) dacPrimary else HwSurface2)
                                    .clickable {
                                        csDsdComp = db
                                        scope.launch { CirrusLogicManager.setDsdGainCompensate(ctx, db) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (csDsdComp == db) Color.Black else Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    val bal = csBalance
                    Text("L/R BALANCE (${when { bal == null -> "unknown"; bal.toInt() == 0 -> "Center"; bal > 0 -> "+${bal.toInt()} R"; else -> "${bal.toInt()} L" }})", color = dacPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Slider(
                        value = bal ?: 0f,
                        enabled = bal != null,
                        onValueChange = {
                            csBalance = it
                            scope.launch { CirrusLogicManager.setBalance(ctx, it.toInt()) }
                        },
                        valueRange = -10f..10f,
                        steps = 19,
                        colors = SliderDefaults.colors(thumbColor = dacPrimary, activeTrackColor = dacPrimary, inactiveTrackColor = HwSurface2)
                    )
                }
            }

            // The selected filter's frequency response and impulse, per jack (DacResponseCard.kt).
            item { DacResponseCard(csFilter?.id, csGain?.id, csDre, csTurbo, dacPrimary, dacSecondary, dacBg) }

            // Section 2: Hardware Fn Switch & Pocket Lock
            item {
                HwSettingsSection("Hardware Fn Switch & Touch Lock")

                val isPocket = fnMode == "touch_and_key_lock" || fnMode == "Both"

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwHioneTeal.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    HwSettingsToggleRow(
                        title = "Pocket Lock (Touch & Key Lock)",
                        subtitle = "Locks the touchscreen and side buttons at the hardware level",
                        checked = isPocket
                    ) { enabled ->
                        val next = if (enabled) "touch_and_key_lock" else "key_lock"
                        fnMode = next
                        Settings.Global.putString(ctx.contentResolver, "fn_settings", next)
                    }

                    if (isPocket) {
                        Spacer(Modifier.height(8.dp))
                        HwSettingsToggleRow(
                            title = "Volume Wheel Works in Pocket",
                            subtitle = "Keep the volume wheel working while the screen and side buttons are locked",
                            checked = allowVolumeWheel
                        ) { enabled ->
                            allowVolumeWheel = enabled
                            Settings.Global.putInt(ctx.contentResolver, "m500_fn_allow_volume_wheel", if (enabled) 1 else 0)
                        }
                    }
                }
            }


            // Section 4: USB DAC UAC2 Bit-Perfect Subsystem
            item {
                HwSettingsSection("USB DAC (UAC2)")

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwHioneTeal.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    HwSettingsToggleRow(
                        title = "UAC2 USB DAC Mode",
                        subtitle = if (usbDacActive) "uac2 is in the live USB gadget config (sys.usb.state)" else "Exposes the M500 as a USB Audio Class 2 DAC to a PC/Mac",
                        checked = usbDacActive
                    ) { enabled ->
                        scope.launch {
                            UsbDacManager.setUsbDacMode(ctx, enabled)
                            // Was "usbDacActive = enabled" straight off the tap. The setprop path
                            // needs su and normally fails, so the row reported UAC2 composed when
                            // the gadget had not changed. isActive() reads sys.usb.state.
                            usbDacActive = withContext(Dispatchers.IO) { UsbDacManager.isActive(ctx) }
                            if (usbDacActive) {
                                val intent = android.content.Intent(ctx, UsbDacActivity::class.java).apply {
                                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                ctx.startActivity(intent)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (usbDacRate == null) "SAMPLE RATE  ·  NOT SET (gadget would use ${UsbDacManager.DEFAULT_SAMPLE_RATE / 1000}k)" else "SAMPLE RATE",
                        color = HwHioneTeal, fontSize = 14.5.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(44100 to "44.1k", 96000 to "96k", 192000 to "192k", 384000 to "384k").forEach { (rate, label) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (usbDacRate == rate) HwHioneTeal else HwSurface2)
                                    .clickable {
                                        usbDacRate = rate
                                        scope.launch {
                                            UsbDacManager.configureParams(ctx, rate, usbDacBits ?: UsbDacManager.DEFAULT_BIT_DEPTH)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (usbDacRate == rate) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (usbDacBits == null) "BIT DEPTH  ·  NOT SET (gadget would use ${UsbDacManager.DEFAULT_BIT_DEPTH}-bit)" else "BIT DEPTH",
                        color = HwHioneTeal, fontSize = 14.5.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(16 to "16-bit", 24 to "24-bit", 32 to "32-bit Float").forEach { (bits, label) ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (usbDacBits == bits) HwHioneTeal else HwSurface2)
                                    .clickable {
                                        usbDacBits = bits
                                        scope.launch {
                                            UsbDacManager.configureParams(ctx, usbDacRate ?: UsbDacManager.DEFAULT_SAMPLE_RATE, bits)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (usbDacBits == bits) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Section 5: CPU power profile (no root: Settings.Global -> sysbridge -> hione_powerd.rc)
            item {
                HwSettingsSection("CPU Power Profile")

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HwSurface1)
                        .border(1.dp, HwHioneTeal.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    val effective = CpuPerformance.effective(cpuProfile, cpuCharging)
                    val nowLine = when {
                        cpuProfile == null -> "Now: not set, runs as Balanced"
                        effective != cpuProfile -> "Now: ${CpuPerformance.label(cpuProfile)}, running as Balanced while charging"
                        else -> "Now: ${CpuPerformance.label(cpuProfile)}"
                    }
                    Text(nowLine, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Max speed: little cores ${CpuPerformance.formatKhz(cpuCaps.first)}, big cores ${CpuPerformance.formatKhz(cpuCaps.second)}",
                        color = HwMuted, fontSize = 14.sp, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(10.dp))

                    CpuPerformance.PROFILES.forEach { p ->
                        val selected = cpuProfile == p.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) HwHioneTeal.copy(alpha = 0.18f) else HwSurface2)
                                .border(1.dp, if (selected) HwHioneTeal else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { CpuPerformance.set(ctx, p.id) }
                                        cpuProblem = if (ok) null else "The profile was not changed."
                                        reloadCpu()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.label, color = if (selected) HwHioneTeal else Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(p.summary, color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp)
                            }
                        }
                    }

                    cpuProblem?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = HwHionePink, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${Mascot.musicAppName} picks a profile on its own when playback, the screen or the charger changes, so a choice here lasts until then. While charging, Audio only and Idle run as Balanced.",
                        color = HwMuted, fontSize = 13.5.sp, lineHeight = 18.sp
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun HwSettingsSection(title: String) {
    Text(
        title.uppercase(),
        color = HwHioneTeal,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun HwSettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color = HwHioneTeal,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = HwMuted, fontSize = 14.sp, lineHeight = 19.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accent,
                checkedTrackColor = HwSurface2,
                uncheckedThumbColor = HwMuted,
                uncheckedTrackColor = HwSurface1
            )
        )
    }
}
