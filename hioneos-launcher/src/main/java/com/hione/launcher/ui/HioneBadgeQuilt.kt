package com.hione.launcher.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.VectorConverter
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.hione.launcher.AudiowideFont
import com.hione.launcher.CyberGlassBorder
import com.hione.launcher.CyberGlassCard
import com.hione.launcher.HioneCyan
import com.hione.launcher.HioneNeonPink
import com.hione.launcher.HioneTextSecondary
import com.hione.launcher.quiltStitch
import kotlin.math.roundToInt

/** One patch sewn into the quilt: a stable id (persisted order key), a label, and the badge composable. */
class QuiltBadge(
    val id: String,
    val label: String,
    val content: @Composable () -> Unit
)

enum class QuiltBadgeSize(val label: String, val scale: Float) { S("S", 0.85f), M("M", 1f), L("L", 1.15f) }
enum class QuiltDensity(val label: String, val gap: Dp, val pad: Dp) {
    COMPACT("Compact", HioneDimens.quiltGapCompact, 4.dp),
    ROOMY("Roomy", HioneDimens.quiltGapRoomy, 8.dp)
}

/** User-configurable quilt geometry — persisted by [HioneQuiltPrefs]. */
data class HioneQuiltConfig(
    val size: QuiltBadgeSize = QuiltBadgeSize.M,
    val density: QuiltDensity = QuiltDensity.COMPACT,
    val rows: Int = 99   // 99 = all rows
)

object HioneQuiltPrefs {
    private const val PREFS = "hione_launcher_prefs"
    private const val KEY_ORDER = "quilt_badge_order"
    private const val KEY_SIZE = "quilt_badge_size"
    private const val KEY_DENSITY = "quilt_density"
    private const val KEY_ROWS = "quilt_rows"

    /** Saved order, reconciled against [defaultIds]: unknown ids dropped, new ids appended. */
    fun loadOrder(ctx: Context, defaultIds: List<String>): List<String> {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ORDER, null)
            ?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        val known = saved.filter { it in defaultIds }
        return known + defaultIds.filter { it !in known }
    }

    fun saveOrder(ctx: Context, order: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ORDER, order.joinToString(",")).apply()
    }

    fun resetOrder(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ORDER).apply()
    }

    fun loadConfig(ctx: Context): HioneQuiltConfig {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HioneQuiltConfig(
            size = runCatching { QuiltBadgeSize.valueOf(p.getString(KEY_SIZE, "M") ?: "M") }.getOrDefault(QuiltBadgeSize.M),
            density = runCatching { QuiltDensity.valueOf(p.getString(KEY_DENSITY, "COMPACT") ?: "COMPACT") }.getOrDefault(QuiltDensity.COMPACT),
            rows = p.getInt(KEY_ROWS, 99).coerceIn(1, 99)
        )
    }

    fun saveConfig(ctx: Context, c: HioneQuiltConfig) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SIZE, c.size.name)
            .putString(KEY_DENSITY, c.density.name)
            .putInt(KEY_ROWS, c.rows)
            .apply()
    }
}

/**
 * Scales a composable AND its reported layout size (plain Modifier.scale only scales pixels,
 * leaving the original footprint), so S/M/L badges reflow correctly inside the quilt.
 */
fun Modifier.scaledLayout(scale: Float): Modifier = if (scale == 1f) this else this.layout { measurable, constraints ->
    val inner = constraints.copy(
        maxWidth = if (constraints.maxWidth == Int.MAX_VALUE) Int.MAX_VALUE else (constraints.maxWidth / scale).roundToInt(),
        maxHeight = if (constraints.maxHeight == Int.MAX_VALUE) Int.MAX_VALUE else (constraints.maxHeight / scale).roundToInt(),
        minWidth = 0, minHeight = 0
    )
    val p = measurable.measure(inner)
    layout((p.width * scale).roundToInt(), (p.height * scale).roundToInt()) {
        p.placeWithLayer(0, 0) {
            scaleX = scale; scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/**
 * THE QUILT — the patchwork of small badge "patches" that sits directly UNDER the thin top bar
 * (both are visible at once). Keeps the stitched-panel look ([quiltStitch]) and adds:
 *  - LONG-PRESS DRAG to rearrange: pick a patch up (haptic), it follows the finger; crossing another
 *    patch swaps places live (tick haptic) and the hovered target lights up pink; drop persists
 *    the order via [onOrderChange].
 *  - User geometry: badge size S/M/L, compact/roomy density, 1-3 rows ([HioneQuiltConfig]).
 * Taps still go to each badge — the drag only claims the pointer after a long press.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HioneBadgeQuilt(
    badges: List<QuiltBadge>,
    order: List<String>,
    onOrderChange: (List<String>) -> Unit,
    config: HioneQuiltConfig,
    modifier: Modifier = Modifier
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // Drop settle: the released patch springs from where the finger left it back into its slot.
    var settleId by remember { mutableStateOf<String?>(null) }
    val settleOffset = remember { androidx.compose.animation.core.Animatable(Offset.Zero, Offset.VectorConverter) }
    val settleScale = remember { androidx.compose.animation.core.Animatable(1f) }
    val byId = remember(badges) { badges.associateBy { it.id } }
    val ordered = remember(order, byId) { order.mapNotNull { byId[it] } }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var hoverId by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val latestOrder = rememberUpdatedState(order)
    val latestOnOrderChange = rememberUpdatedState(onOrderChange)

    fun hit(pos: Offset): String? = bounds.entries.firstOrNull { it.value.contains(pos) }?.key

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .quiltStitch()
            .padding(horizontal = config.density.pad + 2.dp, vertical = config.density.pad)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val id = hit(pos)
                        draggedId = id
                        hoverId = null
                        pointer = pos
                        if (id != null) com.hione.launcher.haptics.HioneHaptics.pop(ctx)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        pointer = change.position
                        val d = draggedId ?: return@detectDragGesturesAfterLongPress
                        val target = hit(change.position)
                        if (target != null && target != d) {
                            val cur = latestOrder.value.toMutableList()
                            val from = cur.indexOf(d); val to = cur.indexOf(target)
                            if (from >= 0 && to >= 0) {
                                cur.removeAt(from); cur.add(to, d)
                                latestOnOrderChange.value(cur)
                                com.hione.launcher.haptics.HioneHaptics.tick(ctx)
                            }
                            hoverId = target
                        } else if (target == d) hoverId = null
                    },
                    onDragEnd = {
                        val d = draggedId
                        val b = d?.let { bounds[it] }
                        draggedId = null; hoverId = null
                        if (d != null && b != null) {
                            com.hione.launcher.haptics.HioneHaptics.confirm(ctx)
                            settleId = d
                            scope.launch {
                                settleOffset.snapTo(Offset(pointer.x - b.center.x, pointer.y - b.center.y))
                                settleScale.snapTo(1.05f)
                                launch { settleScale.animateTo(1f, com.hione.launcher.ui.HioneMotion.settleNow) }
                                settleOffset.animateTo(Offset.Zero, androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 420f))
                                settleId = null
                            }
                        }
                    },
                    onDragCancel = { draggedId = null; hoverId = null }
                )
            },
        horizontalArrangement = Arrangement.spacedBy(config.density.gap, Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(config.density.gap, Alignment.CenterVertically),
        maxLines = config.rows
    ) {
        ordered.forEach { badge ->
            val isDragged = draggedId == badge.id
            val isHover = hoverId == badge.id
            val isSettling = settleId == badge.id
            Box(
                Modifier
                    .zIndex(if (isDragged || isSettling) 2f else 0f)
                    .onGloballyPositioned { c -> bounds[badge.id] = Rect(c.positionInParent(), c.size.toSize()) }
                    .graphicsLayer {
                        if (isDragged) {
                            val b = bounds[badge.id]
                            if (b != null) {
                                translationX = pointer.x - b.center.x
                                translationY = pointer.y - b.center.y
                            }
                            scaleX = 1.05f; scaleY = 1.05f
                            shadowElevation = 12.dp.toPx()
                            alpha = 0.96f
                        } else if (isSettling) {
                            translationX = settleOffset.value.x
                            translationY = settleOffset.value.y
                            scaleX = settleScale.value; scaleY = settleScale.value
                            shadowElevation = 12.dp.toPx() * (settleScale.value - 1f) / 0.05f
                        }
                    }
                    .then(if (isHover) Modifier.border(1.2.dp, HioneNeonPink, RoundedCornerShape(10.dp)) else Modifier)
                    .scaledLayout(config.size.scale)
            ) {
                badge.content()
            }
        }
    }
}

/** Options sheet for the quilt (opened from the home long-press menu → "Quilt & badges"). */
@Composable
fun HioneQuiltOptionsDialog(
    config: HioneQuiltConfig,
    onConfigChange: (HioneQuiltConfig) -> Unit,
    backgroundName: String,
    onCycleBackground: () -> Unit,
    onResetOrder: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .swipeUpFromBottomToDismiss(onDismiss = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .width(300.dp)
                    .clip(RoundedCornerShape(HioneDimens.cornerL))
                    .background(CyberGlassCard)
                    .border(1.dp, CyberGlassBorder, RoundedCornerShape(HioneDimens.cornerL))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(HioneDimens.grid * 2)
            ) {
                Text("BADGES", color = HioneCyan, fontSize = HioneDimens.textS, fontWeight = FontWeight.Black, fontFamily = AudiowideFont)
                Text("Long-press a badge and drag it to move it.", color = HioneTextSecondary, fontSize = HioneDimens.textXs, lineHeight = 15.sp)
                Spacer(Modifier.height(HioneDimens.grid * 1.5f))
                OptionRow("Badge size", QuiltBadgeSize.entries.map { it.label }, config.size.ordinal) {
                    onConfigChange(config.copy(size = QuiltBadgeSize.entries[it]))
                }
                OptionRow("Density", QuiltDensity.entries.map { it.label }, config.density.ordinal) {
                    onConfigChange(config.copy(density = QuiltDensity.entries[it]))
                }
                val rowChoices = listOf(3, 5, 99)
                OptionRow("Rows", listOf("3", "5", "All"), rowChoices.indexOf(config.rows).coerceAtLeast(0).let { if (config.rows !in rowChoices) 2 else it }) {
                    onConfigChange(config.copy(rows = rowChoices[it]))
                }
                Spacer(Modifier.height(HioneDimens.grid))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Backdrop", color = Color.White, fontSize = HioneDimens.textS, fontWeight = FontWeight.Bold)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(HioneDimens.cornerS))
                            .background(HioneCyan.copy(alpha = 0.15f))
                            .border(1.dp, HioneCyan.copy(alpha = 0.6f), RoundedCornerShape(HioneDimens.cornerS))
                            .clickable { onCycleBackground() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text(backgroundName, color = HioneCyan, fontSize = HioneDimens.textXs, fontWeight = FontWeight.Bold, maxLines = 1) }
                }
                Spacer(Modifier.height(HioneDimens.grid))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(HioneDimens.cornerS))
                            .background(HioneNeonPink.copy(alpha = 0.15f))
                            .border(1.dp, HioneNeonPink.copy(alpha = 0.6f), RoundedCornerShape(HioneDimens.cornerS))
                            .clickable { onResetOrder(); onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text("Reset order", color = HioneNeonPink, fontSize = HioneDimens.textXs, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(HioneDimens.grid))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(HioneDimens.cornerS))
                            .background(HioneCyan)
                            .clickable { onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text("Done", color = Color.Black, fontSize = HioneDimens.textXs, fontWeight = FontWeight.Black) }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = HioneDimens.textS, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .height(HioneDimens.pill)
                        .clip(RoundedCornerShape(HioneDimens.cornerS))
                        .background(if (on) HioneCyan else Color(0x22FFFFFF))
                        .border(1.dp, if (on) HioneCyan else CyberGlassBorder, RoundedCornerShape(HioneDimens.cornerS))
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (on) Color.Black else Color.White, fontSize = HioneDimens.textXs, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
