package com.hione.player.profiles

import com.hione.player.Mascot
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.AudiowideFont
import com.hione.player.CirrusLogicManager
import com.hione.player.CrashSentinel
import com.hione.player.CyberDarkBg
import com.hione.player.CyberGlassBorder
import com.hione.player.CyberGlassCard
import com.hione.player.HioneCyan
import com.hione.player.HioneNeonPink
import com.hione.player.HionePurple
import com.hione.player.HioneTextPrimary
import com.hione.player.HioneTextSecondary
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Listening / device profiles: pick the headphone you are listening on, see what it is set to
 * and which of those settings are yours, save the live sound as your defaults for it, and tell
 * it which Bluetooth/USB device or wired port it owns.
 *
 * Reached from the Hione Cyber Audio Controller (HardwareSettingsActivity).
 */
class ListeningProfilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        ListeningProfileManager.init(this)
        setContent { ListeningProfilesScreen(onBack = { finish() }) }
    }
}

private val YoursPink = HioneNeonPink
private val RecCyan = HioneCyan
private val Unverified = Color(0xFFFFB74D)
private val NotWired = Color(0xFF8A9BA0)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListeningProfilesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val profiles by ListeningProfileStore.profiles.collectAsState()
    val currentId by ListeningProfileStore.currentId.collectAsState()
    val activeOutput by ListeningProfileManager.activeOutput.collectAsState()
    val connected by ListeningProfileManager.connectedOutputs.collectAsState()
    val eqState by HioneEq.state.collectAsState()
    val eqRuntime by HioneEq.runtime.collectAsState()

    var selectedId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var brandFilter by remember { mutableStateOf<String?>(null) }
    var typeFilter by remember { mutableStateOf<HardwareType?>(null) }
    var live by remember { mutableStateOf<DspSettings?>(null) }
    var liveTick by remember { mutableIntStateOf(0) }

    var confirmUse by remember { mutableStateOf<ListeningProfile?>(null) }
    var showCreate by remember { mutableStateOf<ListeningProfile?>(null) }
    var creatingBlank by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ListeningProfile?>(null) }
    var deleting by remember { mutableStateOf<ListeningProfile?>(null) }
    var assigning by remember { mutableStateOf<ListeningProfile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(liveTick, eqState) { live = ListeningProfileManager.liveSnapshot(ctx) }

    val current = profiles.firstOrNull { it.id == currentId }
    val selected = profiles.firstOrNull { it.id == (selectedId ?: currentId) } ?: current
    val attribution = remember { ListeningProfileStore.catalogInfo(ctx)?.attribution }

    fun refreshLive() { liveTick++ }

    // ------------------------------------------------------------------ dialogs
    confirmUse?.let { p ->
        val diffs = live?.let { describeDiffs(it, p.effective, p.hardware.connection.isWired) }.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmUse = null },
            containerColor = Mascot.color(0xFF0A1E26),
            title = { Text("Use ${p.name}", fontFamily = AudiowideFont, fontSize = 14.sp, color = HioneCyan) },
            text = {
                Column {
                    Text("Your current sound differs from this profile:", color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    diffs.forEach { Text("• $it", color = HioneTextSecondary, fontSize = 11.sp) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Keep mine: the profile becomes current and your current settings are saved as your defaults for it (only what differs from its recommended settings).",
                        color = HioneTextSecondary, fontSize = 10.5.sp, lineHeight = 14.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUse = null
                    ListeningProfileManager.switchTo(ctx, p, apply = true)
                    selectedId = p.id
                    scope.launch { kotlinx.coroutines.delay(400); refreshLive() }
                }) { Text("APPLY PROFILE", color = HioneCyan, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmUse = null
                    scope.launch {
                        val saved = ListeningProfileManager.saveLiveAsDefaults(ctx, p.id)
                        ListeningProfileStore.get(ctx, p.id)?.let { ListeningProfileManager.switchTo(ctx, it, apply = false) }
                        selectedId = p.id
                        message = if (saved.isEmpty()) "${p.name} is current. Your settings already match its recommended ones."
                        else "${p.name} is current, with your settings saved as its defaults."
                        refreshLive()
                    }
                }) { Text("KEEP MINE", color = YoursPink, fontWeight = FontWeight.Bold) }
            }
        )
    }

    if (creatingBlank || showCreate != null) {
        CreateProfileDialog(
            from = showCreate,
            onDismiss = { creatingBlank = false; showCreate = null },
            onCreate = { name, hw ->
                val p = ListeningProfileStore.createCustom(ctx, name, hw, from = showCreate)
                creatingBlank = false; showCreate = null
                selectedId = p.id
            }
        )
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = Mascot.color(0xFF0A1E26),
            title = { Text("Rename", color = HioneCyan, fontFamily = AudiowideFont, fontSize = 14.sp) },
            text = { HioneField(name, { name = it }, "Name") },
            confirmButton = { TextButton(onClick = { ListeningProfileStore.rename(ctx, p.id, name); renaming = null }) { Text("SAVE", color = HioneCyan) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("CANCEL", color = HioneTextSecondary) } }
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Mascot.color(0xFF0A1E26),
            title = { Text("Delete ${p.name}?", color = YoursPink, fontFamily = AudiowideFont, fontSize = 14.sp) },
            text = { Text("This custom profile and its auto-switch rules are removed. The current sound is not changed.", color = Color.White, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    ListeningProfileStore.delete(ctx, p.id); deleting = null
                    if (selectedId == p.id) selectedId = null
                }) { Text("DELETE", color = YoursPink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("KEEP", color = HioneCyan) } }
        )
    }

    assigning?.let { p ->
        AssignDialog(
            profile = p,
            connected = connected,
            onDismiss = { assigning = null },
            onSave = { rules -> ListeningProfileStore.setAutoSwitch(ctx, p.id, rules); assigning = null }
        )
    }

    // ------------------------------------------------------------------ layout
    Box(Modifier.fillMaxSize().background(CyberDarkBg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.hione.player.ui.HioneBackButton(onClick = onBack)
                Spacer(Modifier.width(6.dp))
                Text(
                    "LISTENING PROFILES", color = HioneCyan, fontSize = 13.5.sp, fontWeight = FontWeight.Black,
                    fontFamily = AudiowideFont, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
                )
                Badge(activeOutput?.label ?: "SPEAKER", HioneCyan)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // ---------------- now listening
                item(key = "now") {
                    Card {
                        Text("NOW LISTENING", color = HioneCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                        Spacer(Modifier.height(4.dp))
                        Text(current?.name ?: "No profile selected", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "on ${activeOutput?.label ?: "the built-in speaker (not followed)"}" +
                                (current?.let { " · ${ListeningProfileManager.summary(it.effective)}" } ?: ""),
                            color = HioneTextSecondary, fontSize = 11.sp
                        )
                        val diffs = if (current != null && live != null) describeDiffs(live!!, current.effective, current.hardware.connection.isWired) else emptyList()
                        if (diffs.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Live sound differs from this profile (not saved): ${diffs.joinToString("; ")}", color = YoursPink, fontSize = 10.5.sp, lineHeight = 14.sp)
                        }
                        message?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = HioneCyan, fontSize = 10.5.sp)
                        }
                        if (activeOutput?.kind == ListeningProfileManager.Output.Kind.WIRED) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Wired jacks cannot tell which headphone is plugged in, so ${activeOutput?.port} keeps the profile you last chose on it (or the one you pin to it).",
                                color = HioneTextSecondary, fontSize = 10.sp, lineHeight = 13.sp
                            )
                        }
                    }
                }

                // ---------------- selected profile detail
                selected?.let { p ->
                    item(key = "detail-${p.id}") {
                        ProfileDetail(
                            p = p,
                            isCurrent = p.id == currentId,
                            live = live,
                            onUse = {
                                val d = live?.let { describeDiffs(it, p.effective, p.hardware.connection.isWired) }.orEmpty()
                                if (d.isEmpty()) { ListeningProfileManager.switchTo(ctx, p, apply = true); refreshLive() }
                                else confirmUse = p
                            },
                            onApplyAgain = {
                                ListeningProfileManager.switchTo(ctx, p, apply = true)
                                scope.launch { kotlinx.coroutines.delay(400); refreshLive() }
                            },
                            onSaveLive = {
                                scope.launch {
                                    val o = ListeningProfileManager.saveLiveAsDefaults(ctx, p.id)
                                    message = if (o.isEmpty()) "Nothing to save: your settings match ${p.name}'s recommended ones."
                                    else "Saved as your defaults for ${p.name}: ${describeOverrides(o).joinToString(", ")}"
                                    refreshLive()
                                }
                            },
                            onReset = {
                                ListeningProfileManager.resetToRecommended(ctx, p.id)
                                message = "${p.name} reset to recommended."
                                scope.launch { kotlinx.coroutines.delay(400); refreshLive() }
                            },
                            onDuplicate = { showCreate = p },
                            onRename = { renaming = p },
                            onDelete = { deleting = p },
                            onAssign = { assigning = p },
                        )
                    }
                }

                // ---------------- live controls
                item(key = "live") {
                    LiveControls(
                        live = live,
                        eqState = eqState,
                        runtime = eqRuntime,
                        profileEq = current?.effective,
                        onChanged = { refreshLive() },
                    )
                }

                // ---------------- library
                item(key = "search") {
                    Column {
                        HioneField(query, { query = it }, "Search ${profiles.size} profiles (model, brand)")
                        Spacer(Modifier.height(8.dp))
                        val brands = remember(profiles) { profiles.filter { it.builtIn }.map { it.hardware.brand }.distinct().sorted() }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("All", brandFilter == null) { brandFilter = null }
                            Chip("Custom", brandFilter == "__custom") { brandFilter = "__custom" }
                            brands.forEach { b -> Chip(b, brandFilter == b) { brandFilter = b } }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("Any type", typeFilter == null) { typeFilter = null }
                            HardwareType.entries.filter { t -> profiles.any { it.hardware.type == t } }.forEach { t ->
                                Chip(t.label, typeFilter == t) { typeFilter = t }
                            }
                        }
                    }
                }

                val q = query.trim().lowercase()
                val filtered = profiles.filter { p ->
                    (brandFilter == null || (brandFilter == "__custom" && !p.builtIn) || (p.builtIn && p.hardware.brand == brandFilter)) &&
                        (typeFilter == null || p.hardware.type == typeFilter) &&
                        (q.isEmpty() || p.name.lowercase().contains(q) || p.hardware.brand.lowercase().contains(q) || p.hardware.model.lowercase().contains(q))
                }
                val groups = filtered.groupBy { if (it.builtIn) "${it.hardware.brand} · ${it.hardware.type.label}" else "Your profiles" }
                    .toList()
                    .sortedWith(compareBy({ if (it.first == "Your profiles") 0 else 1 }, { it.first }))

                if (filtered.isEmpty()) {
                    item(key = "empty") { Text("No profile matches.", color = HioneTextSecondary, fontSize = 12.sp) }
                }
                groups.forEach { (title, list) ->
                    item(key = "h-$title") {
                        Text("${title.uppercase()} (${list.size})", color = HionePurple, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                    }
                    items(list.sortedBy { it.name.lowercase() }, key = { "p-${it.id}" }) { p ->
                        ProfileRow(p, isCurrent = p.id == currentId, isSelected = p.id == selected?.id) { selectedId = p.id; message = null }
                    }
                }

                item(key = "new") {
                    OutlinedButton(onClick = { creatingBlank = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("+ NEW CUSTOM PROFILE", color = HioneCyan, fontFamily = AudiowideFont, fontSize = 11.sp)
                    }
                }
                item(key = "credits") {
                    Text(
                        attribution ?: "Built-in profile data not found in this build.",
                        color = HioneTextSecondary.copy(alpha = 0.7f), fontSize = 9.5.sp, lineHeight = 13.sp
                    )
                }
            }
        }
    }
}

// ============================================================================ detail

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileDetail(
    p: ListeningProfile,
    isCurrent: Boolean,
    live: DspSettings?,
    onUse: () -> Unit,
    onApplyAgain: () -> Unit,
    onSaveLive: () -> Unit,
    onReset: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAssign: () -> Unit,
) {
    Card(border = if (isCurrent) HioneCyan else CyberGlassBorder) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(p.hardware.specLine(), color = HioneTextSecondary, fontSize = 10.5.sp)
            }
            if (isCurrent) Badge("CURRENT", HioneCyan)
            if (!p.builtIn) { Spacer(Modifier.width(4.dp)); Badge("CUSTOM", HionePurple) }
        }
        Spacer(Modifier.height(6.dp))
        p.source.rationale?.let { Text("Why: $it", color = HioneTextSecondary, fontSize = 10.sp, lineHeight = 13.sp) }
        Text(
            "EQ: " + (p.source.eq ?: "no measurement, so it starts flat"),
            color = if (p.source.eq != null) HioneTextSecondary else Unverified, fontSize = 10.sp, lineHeight = 13.sp
        )
        p.source.notes?.let { Text(it, color = HioneTextSecondary.copy(alpha = 0.8f), fontSize = 9.5.sp, lineHeight = 12.sp) }

        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isCurrent) ActionButton("APPLY AGAIN", HioneCyan, onApplyAgain) else ActionButton("USE THIS PROFILE", HioneCyan, onUse)
            ActionButton("SAVE CURRENT AS MY DEFAULTS", YoursPink, onSaveLive)
            ActionButton("RESET TO RECOMMENDED", HionePurple, onReset, enabled = p.hasOverrides)
        }

        Spacer(Modifier.height(12.dp))
        Text("SETTINGS (recommended, plus your changes)", color = HioneCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
        Spacer(Modifier.height(4.dp))
        val eff = p.effective
        val ov = p.overrides
        SettingRow(DspKnob.FILTER, eff.digitalFilter?.let { ListeningProfileManager.filterShort(it) }, ov.digitalFilter != null,
            live?.digitalFilter?.takeIf { eff.digitalFilter != null && it != eff.digitalFilter }?.let { ListeningProfileManager.filterShort(it) },
            p.recommended.digitalFilter?.let { ListeningProfileManager.filterShort(it) })
        SettingRow(DspKnob.GAIN, eff.gain?.let { if (it == "low") "Low" else "High" }, ov.gain != null,
            live?.gain?.takeIf { eff.gain != null && it != eff.gain }?.let { if (it == "low") "Low" else "High" },
            p.recommended.gain?.let { if (it == "low") "Low" else "High" })
        SettingRow(DspKnob.HIGH_POWER, eff.highPower?.let { if (it) "On" else "Off" }, ov.highPower != null,
            live?.highPower?.takeIf { eff.highPower != null && it != eff.highPower }?.let { if (it) "On" else "Off" },
            p.recommended.highPower?.let { if (it) "On" else "Off" })
        SettingRow(DspKnob.DRE, eff.dre?.let { if (it) "On" else "Off" }, ov.dre != null,
            live?.dre?.takeIf { eff.dre != null && it != eff.dre }?.let { if (it) "On" else "Off" },
            p.recommended.dre?.let { if (it) "On" else "Off" })
        if (eff.dsdGainComp != null) {
            SettingRow(DspKnob.DSD_GAIN_COMP, if (eff.dsdGainComp) "On" else "Off", ov.dsdGainComp != null, null,
                p.recommended.dsdGainComp?.let { if (it) "On" else "Off" })
        }
        if (!p.hardware.connection.isWired) {
            Text("Bluetooth only: the M500's DAC is not in this signal path, so this profile sets no DAC knobs.", color = HioneTextSecondary, fontSize = 10.sp)
        }
        // EQ row
        val eqYours = ov.eq != null || ov.eqEnabled != null
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EQ", color = Color.White, fontSize = 11.5.sp, modifier = Modifier.width(110.dp))
            Text(
                when {
                    eff.eqEnabled != true -> "Off"
                    eff.eq == null || eff.eq.isFlat -> "On (flat)"
                    else -> "On · ${eff.eq.bands.size} bands · preamp ${fmt(eff.eq.preampDb)} dB"
                },
                color = if (eqYours) YoursPink else RecCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Marker(eqYours)
        }
        val liveEq = live?.let { l -> if (l.eqEnabled == true) l.eq else EqCurve.FLAT }
        val effEq = if (eff.eqEnabled == true) eff.eq ?: EqCurve.FLAT else EqCurve.FLAT
        EqGraph(effEq, liveEq?.takeIf { !it.sameAs(effEq) })
        Text(
            "Cyan: this profile. " + if (liveEq != null && !liveEq.sameAs(effEq)) "Pink dashed: what is playing now." else "",
            color = HioneTextSecondary, fontSize = 9.5.sp
        )

        Spacer(Modifier.height(10.dp))
        Text("AUTO-SWITCH", color = HioneCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
        if (p.autoSwitch.isEmpty()) {
            Text(
                if (p.hardware.connection.isBluetooth) "No device assigned. A Bluetooth device whose name clearly matches this model is picked up automatically."
                else "No device or port assigned.",
                color = HioneTextSecondary, fontSize = 10.5.sp
            )
        } else {
            p.autoSwitch.forEach { Text("• ${it.label}", color = Color.White, fontSize = 11.sp) }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("ASSIGN DEVICE / PORT", HioneCyan, onAssign)
            ActionButton("DUPLICATE", HioneTextSecondary, onDuplicate)
            if (!p.builtIn) {
                ActionButton("RENAME", HioneTextSecondary, onRename)
                ActionButton("DELETE", YoursPink, onDelete)
            }
        }
        p.source.eqUrl?.let {
            Spacer(Modifier.height(6.dp))
            Text("Source: $it", color = HioneTextSecondary.copy(alpha = 0.6f), fontSize = 8.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingRow(knob: DspKnob, value: String?, yours: Boolean, liveDiffers: String?, recommended: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(knob.label, color = Color.White, fontSize = 11.5.sp, modifier = Modifier.width(110.dp))
            Text(
                value ?: "not set (left as is)",
                color = if (value == null) HioneTextSecondary else if (yours) YoursPink else RecCyan,
                fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            TruthBadge(knob.truth)
            Spacer(Modifier.width(4.dp))
            if (value != null) Marker(yours)
        }
        if (yours && recommended != null) Text("recommended: $recommended", color = HioneTextSecondary, fontSize = 9.5.sp, modifier = Modifier.padding(start = 110.dp))
        if (liveDiffers != null) Text("live now: $liveDiffers (unsaved)", color = YoursPink.copy(alpha = 0.8f), fontSize = 9.5.sp, modifier = Modifier.padding(start = 110.dp))
    }
}

@Composable
private fun Marker(yours: Boolean) {
    Text(
        if (yours) "YOURS" else "REC",
        color = if (yours) YoursPink else RecCyan.copy(alpha = 0.7f), fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.width(38.dp)
    )
}

@Composable
private fun TruthBadge(t: DspKnob.Truth) {
    val c = when (t) {
        DspKnob.Truth.PATH_FOUND -> Color(0xFF00E676)
        DspKnob.Truth.UNVERIFIED -> Unverified
        DspKnob.Truth.NOT_WIRED -> NotWired
    }
    Text(
        t.badge, color = c, fontSize = 7.5.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

// ============================================================================ live controls

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveControls(
    live: DspSettings?,
    eqState: HioneEq.State,
    runtime: HioneEq.Runtime,
    profileEq: DspSettings?,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showFindings by remember { mutableStateOf(false) }
    Card {
        Text("LIVE SOUND", color = HioneCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
        Text(
            "Changes here are heard now and are not saved until you press \"Save current as my defaults\".",
            color = HioneTextSecondary, fontSize = 10.sp, lineHeight = 13.sp
        )
        Spacer(Modifier.height(8.dp))

        Text("Digital filter", color = Color.White, fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CirrusLogicManager.DigitalFilter.entries.forEach { f ->
                Chip(ListeningProfileManager.filterShort(f.id), live?.digitalFilter == f.id) {
                    scope.launch { CirrusLogicManager.setDigitalFilter(ctx, f); onChanged() }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gain", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(80.dp))
            CirrusLogicManager.GainMode.entries.forEach { g ->
                Chip(if (g == CirrusLogicManager.GainMode.LOW) "Low" else "High", live?.gain == g.sysfsValue) {
                    scope.launch { CirrusLogicManager.setGainMode(ctx, g); onChanged() }
                }
                Spacer(Modifier.width(6.dp))
            }
        }
        ToggleRow("High power", live?.highPower == true) { on -> scope.launch { CirrusLogicManager.setHighPowerEnabled(ctx, on); onChanged() } }
        ToggleRow("DRE", live?.dre == true) { on -> scope.launch { CirrusLogicManager.setDreEnabled(ctx, on); onChanged() } }
        Text(
            if (showFindings) "Hide what reaches the DAC" else "What actually reaches the DAC?",
            color = Unverified, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { showFindings = !showFindings }.padding(vertical = 4.dp)
        )
        if (showFindings) {
            DspKnob.entries.forEach { k ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    TruthBadge(k.truth)
                    Spacer(Modifier.width(6.dp))
                    Text("${k.label}: ${k.finding}", color = HioneTextSecondary, fontSize = 9.5.sp, lineHeight = 12.5.sp)
                }
            }
            val bridge = HibyDacBridge.lastResults
            Text(
                if (!HibyDacBridge.available) "Property bridge: not available on this framework."
                else "Property bridge (read back through AudioService): " +
                    listOf(HibyDacBridge.PROP_FILTER, HibyDacBridge.PROP_HIGH_POWER, HibyDacBridge.PROP_DRE)
                        .joinToString(", ") { "${it.substringAfterLast('.')}=${bridge[it]?.name?.lowercase() ?: "not written yet"}" },
                color = HioneTextSecondary, fontSize = 9.5.sp, lineHeight = 12.5.sp
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PARAMETRIC EQ", color = HioneCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, modifier = Modifier.weight(1f))
            Switch(checked = eqState.enabled, onCheckedChange = { HioneEq.setEnabled(it); onChanged() },
                colors = SwitchDefaults.colors(checkedThumbColor = HioneCyan, checkedTrackColor = HioneCyan.copy(alpha = 0.4f)))
        }
        Text(
            when {
                !eqState.enabled -> "Off: the EQ is out of the signal path; playback is bit-perfect."
                eqState.curve.isFlat -> "On but flat: nothing is changed, the EQ stays out of the signal path."
                else -> "Active" + if (runtime.sampleRate > 0) " · last stream ${runtime.sampleRate / 1000.0} kHz ${encodingName(runtime.configuredEncoding)}" else ""
            },
            color = HioneTextSecondary, fontSize = 10.sp
        )
        if (!HioneEq.hiResCovered) {
            Text(
                "EQ runs on 16-bit tracks. 24-bit and other hi-res tracks currently play without EQ (one hook in the hi-res sink path is still to be wired).",
                color = Unverified, fontSize = 10.sp, lineHeight = 13.sp
            )
        }
        val peak = remember(eqState.curve) { if (eqState.curve.bands.isEmpty()) eqState.curve.preampDb else HioneEq.peakDb(eqState.curve) }
        if (eqState.audible && peak > 0.05) {
            Text(
                String.format(java.util.Locale.US, "This curve boosts up to +%.1f dB. Lower the preamp by that much to avoid clipping.", peak),
                color = YoursPink, fontSize = 10.sp
            )
        }
        if (runtime.clippedSamples > 0) {
            Text("${runtime.clippedSamples} samples clipped since the stream started.", color = YoursPink, fontSize = 10.sp)
        }
        EqGraph(eqState.curve.takeIf { eqState.enabled } ?: EqCurve.FLAT, null)

        // Preamp
        Text("Preamp ${fmt(eqState.curve.preampDb)} dB", color = Color.White, fontSize = 11.sp)
        Slider(
            value = eqState.curve.preampDb.toFloat().coerceIn(-24f, 6f),
            onValueChange = { v -> HioneEq.setCurve(eqState.curve.copy(preampDb = (Math.round(v * 10) / 10.0))) },
            onValueChangeFinished = onChanged,
            valueRange = -24f..6f,
            colors = SliderDefaults.colors(thumbColor = HioneCyan, activeTrackColor = HioneCyan)
        )
        eqState.curve.bands.forEachIndexed { i, b ->
            BandEditor(i, b,
                onChange = { nb -> HioneEq.setCurve(eqState.curve.copy(bands = eqState.curve.bands.toMutableList().also { it[i] = nb })) },
                onRemove = { HioneEq.setCurve(eqState.curve.copy(bands = eqState.curve.bands.filterIndexed { j, _ -> j != i })); onChanged() },
                onDone = onChanged)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("+ BAND", HioneCyan, {
                HioneEq.setCurve(eqState.curve.copy(bands = eqState.curve.bands + EqBand(EqFilterType.PEAKING, 1000.0, 0.0, 1.0)))
                onChanged()
            }, enabled = eqState.curve.bands.size < 16)
            ActionButton("FLATTEN", HioneTextSecondary, { HioneEq.setCurve(EqCurve.FLAT); onChanged() })
            profileEq?.let { pe ->
                ActionButton("BACK TO PROFILE EQ", HionePurple, {
                    HioneEq.set(pe.eqEnabled == true, pe.eq ?: EqCurve.FLAT); onChanged()
                })
            }
        }
    }
}

@Composable
private fun BandEditor(i: Int, b: EqBand, onChange: (EqBand) -> Unit, onRemove: () -> Unit, onDone: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = !open }) {
            Text(
                "${i + 1}. ${b.type.code} ${fmtHz(b.freqHz)}  Q ${String.format(java.util.Locale.US, "%.2f", b.q)}",
                color = Color.White, fontSize = 10.5.sp, modifier = Modifier.weight(1f)
            )
            Text(String.format(java.util.Locale.US, "%+.1f dB", b.gainDb), color = if (b.gainDb >= 0) HioneCyan else YoursPink, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = b.gainDb.toFloat().coerceIn(-15f, 15f),
            onValueChange = { onChange(b.copy(gainDb = Math.round(it * 10) / 10.0)) },
            onValueChangeFinished = onDone,
            valueRange = -15f..15f,
            colors = SliderDefaults.colors(thumbColor = HioneNeonPink, activeTrackColor = HioneNeonPink)
        )
        if (open) {
            Text("Frequency", color = HioneTextSecondary, fontSize = 9.5.sp)
            Slider(
                value = (ln(b.freqHz.coerceIn(20.0, 20000.0) / 20.0) / ln(1000.0)).toFloat(),
                onValueChange = { t -> onChange(b.copy(freqHz = Math.round(20.0 * 1000.0.pow(t.toDouble())).toDouble())) },
                onValueChangeFinished = onDone,
                colors = SliderDefaults.colors(thumbColor = HioneCyan, activeTrackColor = HioneCyan)
            )
            Text("Q", color = HioneTextSecondary, fontSize = 9.5.sp)
            Slider(
                value = b.q.toFloat().coerceIn(0.1f, 10f),
                onValueChange = { onChange(b.copy(q = Math.round(it * 100) / 100.0)) },
                onValueChangeFinished = onDone,
                valueRange = 0.1f..10f,
                colors = SliderDefaults.colors(thumbColor = HioneCyan, activeTrackColor = HioneCyan)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EqFilterType.entries.forEach { t -> Chip(t.label, b.type == t) { onChange(b.copy(type = t)); onDone() } }
                Chip("Remove", false, onRemove)
            }
        }
    }
}

// ============================================================================ list row

@Composable
private fun ProfileRow(p: ListeningProfile, isCurrent: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Mascot.color(0x22FFC56B) else CyberGlassCard)
            .border(1.dp, if (isCurrent) HioneCyan else CyberGlassBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = HioneTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.hardware.specLine(), color = HioneTextSecondary, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                p.source.eq ?: "EQ: no measurement",
                color = if (p.source.eq != null) HioneTextSecondary.copy(alpha = 0.75f) else Unverified.copy(alpha = 0.85f),
                fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (isCurrent) Badge("CURRENT", HioneCyan)
            if (p.hasOverrides) Badge("YOURS", YoursPink)
            if (p.autoSwitch.isNotEmpty()) Badge("AUTO", HionePurple)
        }
    }
}

// ============================================================================ dialogs

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateProfileDialog(from: ListeningProfile?, onDismiss: () -> Unit, onCreate: (String, Hardware) -> Unit) {
    val src = from?.hardware
    var name by remember { mutableStateOf(from?.let { "${it.name} (my copy)" } ?: "") }
    var brand by remember { mutableStateOf(src?.brand ?: "") }
    var model by remember { mutableStateOf(src?.model ?: "") }
    var type by remember { mutableStateOf(src?.type ?: HardwareType.IEM) }
    var conn by remember { mutableStateOf(src?.connection ?: Connection.WIRED) }
    var imp by remember { mutableStateOf(src?.impedanceOhm?.let { fmt(it) } ?: "") }
    var sens by remember { mutableStateOf(src?.sensitivity?.let { fmt(it) } ?: "") }
    var unit by remember { mutableStateOf(src?.sensitivityUnit ?: "dB/mW") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Mascot.color(0xFF0A1E26),
        title = { Text(if (from != null) "Duplicate ${from.name}" else "New profile", color = HioneCyan, fontFamily = AudiowideFont, fontSize = 14.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HioneField(name, { name = it }, "Profile name")
                HioneField(brand, { brand = it }, "Brand")
                HioneField(model, { model = it }, "Model")
                Text("Type", color = HioneTextSecondary, fontSize = 10.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { HardwareType.entries.forEach { t -> Chip(t.label, type == t) { type = t } } }
                Text("Connection", color = HioneTextSecondary, fontSize = 10.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Connection.entries.forEach { c -> Chip(c.label, conn == c) { conn = c } } }
                HioneField(imp, { imp = it }, "Impedance Ω (optional)", numeric = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { HioneField(sens, { sens = it }, "Sensitivity (optional)", numeric = true) }
                    Spacer(Modifier.width(4.dp))
                    Chip(unit, true) { unit = if (unit == "dB/mW") "dB/V" else "dB/mW" }
                }
                Text(
                    if (from != null) "Starts as an exact copy of ${from.name}'s current settings."
                    else "Starts flat, with DAC settings from the impedance/sensitivity rule.",
                    color = HioneTextSecondary, fontSize = 9.5.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() || model.isNotBlank(),
                onClick = {
                    onCreate(name, Hardware(
                        brand = brand.trim(), model = model.trim().ifEmpty { name.trim() }, type = type, connection = conn,
                        impedanceOhm = imp.toDoubleOrNull(), sensitivity = sens.toDoubleOrNull(),
                        sensitivityUnit = if (sens.toDoubleOrNull() != null) unit else null,
                    ))
                }
            ) { Text("CREATE", color = HioneCyan, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = HioneTextSecondary) } }
    )
}

@Composable
private fun AssignDialog(
    profile: ListeningProfile,
    connected: List<ListeningProfileManager.Output>,
    onDismiss: () -> Unit,
    onSave: (List<AutoSwitchRule>) -> Unit,
) {
    var rules by remember { mutableStateOf(profile.autoSwitch) }
    var btName by remember { mutableStateOf("") }
    fun add(r: AutoSwitchRule) { if (rules.none { it.kind == r.kind && it.value.equals(r.value, true) }) rules = rules + r }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Mascot.color(0xFF0A1E26),
        title = { Text("Auto-switch: ${profile.name}", color = HioneCyan, fontFamily = AudiowideFont, fontSize = 13.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("When one of these connects, this profile becomes current and is applied. A device or port belongs to one profile at a time.",
                    color = HioneTextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
                Spacer(Modifier.height(6.dp))
                rules.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { rules = rules - r }) { Text("REMOVE", color = YoursPink, fontSize = 10.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Connected now", color = HioneCyan, fontSize = 10.sp)
                if (connected.isEmpty()) Text("Nothing but the speaker.", color = HioneTextSecondary, fontSize = 10.sp)
                connected.forEach { o ->
                    val rule = when (o.kind) {
                        ListeningProfileManager.Output.Kind.BLUETOOTH -> AutoSwitchRule(AutoSwitchRule.Kind.BT_NAME, o.name)
                        ListeningProfileManager.Output.Kind.USB -> AutoSwitchRule(AutoSwitchRule.Kind.USB_NAME, o.name)
                        ListeningProfileManager.Output.Kind.WIRED -> AutoSwitchRule(AutoSwitchRule.Kind.WIRED_PORT, o.port ?: ListeningProfileStore.PORT_35)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.label, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { add(rule) }) { Text("ADD", color = HioneCyan, fontSize = 10.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Pin a wired port", color = HioneCyan, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(ListeningProfileStore.PORT_35, false) { add(AutoSwitchRule(AutoSwitchRule.Kind.WIRED_PORT, ListeningProfileStore.PORT_35)) }
                    Chip(ListeningProfileStore.PORT_44, false) { add(AutoSwitchRule(AutoSwitchRule.Kind.WIRED_PORT, ListeningProfileStore.PORT_44)) }
                }
                Spacer(Modifier.height(6.dp))
                Text("Bluetooth name (as the device advertises it)", color = HioneCyan, fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { HioneField(btName, { btName = it }, "e.g. MOMENTUM 4") }
                    TextButton(onClick = { if (btName.isNotBlank()) { add(AutoSwitchRule(AutoSwitchRule.Kind.BT_NAME, btName.trim())); btName = "" } }) {
                        Text("ADD", color = HioneCyan, fontSize = 10.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(rules) }) { Text("SAVE", color = HioneCyan, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = HioneTextSecondary) } }
    )
}

// ============================================================================ pieces

@Composable
private fun Card(border: Color = CyberGlassBorder, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CyberGlassCard)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color, RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) { Text(text, color = color, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont, maxLines = 1) }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) HioneCyan.copy(alpha = 0.22f) else Color(0x22FFFFFF))
            .border(1.dp, if (selected) HioneCyan else CyberGlassBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) { Text(text, color = if (selected) HioneCyan else HioneTextSecondary, fontSize = 10.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun ActionButton(text: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = if (enabled) 0.14f else 0.04f))
            .border(1.dp, color.copy(alpha = if (enabled) 0.9f else 0.25f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) { Text(text, color = color.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = HioneCyan, checkedTrackColor = HioneCyan.copy(alpha = 0.4f)))
    }
}

@Composable
private fun HioneField(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = HioneCyan, unfocusedBorderColor = CyberGlassBorder,
            focusedLabelColor = HioneCyan, unfocusedLabelColor = HioneTextSecondary, cursorColor = HioneCyan
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Frequency response, 20 Hz–20 kHz log, ±15 dB. [overlay] is drawn dashed in pink. */
@Composable
private fun EqGraph(curve: EqCurve, overlay: EqCurve?) {
    val pts = remember(curve) { sample(curve) }
    val overlayPts = remember(overlay) { overlay?.let { sample(it) } }
    Canvas(Modifier.fillMaxWidth().height(90.dp).padding(vertical = 6.dp)) {
        val w = size.width; val h = size.height
        fun y(db: Double) = (h / 2 - (db.coerceIn(-15.0, 15.0) / 15.0) * (h / 2)).toFloat()
        listOf(-12.0, -6.0, 0.0, 6.0, 12.0).forEach { db ->
            drawLine(if (db == 0.0) HioneCyan.copy(alpha = 0.35f) else Color(0x22FFFFFF), Offset(0f, y(db)), Offset(w, y(db)), 1f)
        }
        listOf(100.0, 1000.0, 10000.0).forEach { f ->
            val x = (ln(f / 20.0) / ln(1000.0) * w).toFloat()
            drawLine(Color(0x22FFFFFF), Offset(x, 0f), Offset(x, h), 1f)
        }
        fun path(p: List<Pair<Double, Double>>) = Path().apply {
            p.forEachIndexed { i, (t, db) -> val x = (t * w).toFloat(); if (i == 0) moveTo(x, y(db)) else lineTo(x, y(db)) }
        }
        drawPath(path(pts), HioneCyan, style = Stroke(width = 3f))
        overlayPts?.let { drawPath(path(it), HioneNeonPink, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))) }
    }
}

private fun sample(curve: EqCurve): List<Pair<Double, Double>> =
    (0..120).map { i -> val t = i / 120.0; t to HioneEq.responseDb(curve, 20.0 * 1000.0.pow(t)) }

private fun fmtHz(f: Double) = if (f >= 1000) String.format(java.util.Locale.US, "%.2f kHz", f / 1000) else "${f.toInt()} Hz"

private fun encodingName(e: Int) = when (e) {
    androidx.media3.common.C.ENCODING_PCM_16BIT -> "16-bit"
    androidx.media3.common.C.ENCODING_PCM_24BIT -> "24-bit"
    androidx.media3.common.C.ENCODING_PCM_32BIT -> "32-bit"
    androidx.media3.common.C.ENCODING_PCM_FLOAT -> "float"
    else -> ""
}

/** Human list of where [live] differs from [target], limited to knobs [target] actually sets. */
internal fun describeDiffs(live: DspSettings, target: DspSettings, wired: Boolean): List<String> = buildList {
    if (wired) {
        target.digitalFilter?.let { t -> live.digitalFilter?.takeIf { it != t }?.let { add("filter ${ListeningProfileManager.filterShort(it)} (profile: ${ListeningProfileManager.filterShort(t)})") } }
        target.gain?.let { t -> live.gain?.takeIf { it != t }?.let { add("gain $it (profile: $t)") } }
        target.highPower?.let { t -> live.highPower?.takeIf { it != t }?.let { add("high power ${onOff(it)} (profile: ${onOff(t)})") } }
        target.dre?.let { t -> live.dre?.takeIf { it != t }?.let { add("DRE ${onOff(it)} (profile: ${onOff(t)})") } }
    }
    val tOn = target.eqEnabled == true
    val lOn = live.eqEnabled == true
    if (tOn != lOn) add("EQ ${onOff(lOn)} (profile: ${onOff(tOn)})")
    else if (tOn && !(live.eq ?: EqCurve.FLAT).sameAs(target.eq ?: EqCurve.FLAT)) add("EQ curve edited")
}

internal fun describeOverrides(o: DspSettings): List<String> = buildList {
    o.digitalFilter?.let { add(ListeningProfileManager.filterShort(it)) }
    o.gain?.let { add("$it gain") }
    o.highPower?.let { add("high power ${onOff(it)}") }
    o.dre?.let { add("DRE ${onOff(it)}") }
    o.dsdGainComp?.let { add("DSD comp ${onOff(it)}") }
    o.eqEnabled?.let { add("EQ ${onOff(it)}") }
    o.eq?.let { add("EQ curve") }
}

private fun onOff(b: Boolean) = if (b) "on" else "off"
