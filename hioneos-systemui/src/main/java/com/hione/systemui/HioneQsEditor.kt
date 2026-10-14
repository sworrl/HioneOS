package com.hione.systemui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Quick-settings editor, the modern-Android way: one surface, two sections.
 *
 *   ACTIVE      the shade's tiles in order; the first row (4) is the collapsed quick row
 *   ─────────   drag a tile below this line to remove it, above it to add it
 *   AVAILABLE   everything else
 *
 * Long-press picks a tile up (pop haptic), it follows the finger, the others slide out of the
 * way on the settle spring with a tick each time the drop slot changes. The slot it will land in
 * is a dashed outline; the section under the finger lights its rim (teal = place, pink = remove).
 * Near the top or bottom edge the list auto-scrolls. Tap an available tile to append it. Every
 * drop persists through QsTileOrder (Settings.Secure hione_qs_tiles); Reset needs a second tap.
 *
 * Hit-testing is pure grid arithmetic on the finger position, not on tile bounds, so a tile can
 * never oscillate between two slots: crossing the divider only ever moves the divider AWAY from
 * the finger (a section that gains a tile grows toward it, one that loses a tile shrinks back).
 */

private const val COLS = 4

/** Grid geometry in content pixels for a given pair of section sizes. */
private class EditorGeom(
    val widthPx: Float, val cellW: Float, val cellH: Float, val gap: Float,
    val labelH: Float, val dividerH: Float,
    val nActive: Int, val nAvail: Int
) {
    private fun rows(n: Int) = maxOf(1, (n + COLS - 1) / COLS)
    private fun blockH(n: Int) = rows(n) * cellH + (rows(n) - 1) * gap
    val activeTop = labelH
    val activeBottom = activeTop + blockH(nActive)
    val dividerTop = activeBottom
    val availTop = dividerTop + dividerH
    val availBottom = availTop + blockH(nAvail)
    val total = availBottom + gap
    /** Finger above this line = active section. */
    val boundary = dividerTop + dividerH / 2f

    fun slot(active: Boolean, i: Int): Offset {
        val top = if (active) activeTop else availTop
        return Offset((i % COLS) * (cellW + gap), top + (i / COLS) * (cellH + gap))
    }

    /** Section + insertion index for a finger at [p]. [maxIdx] clamps per section. */
    fun target(p: Offset, maxActive: Int, maxAvail: Int): Pair<Boolean, Int> {
        val inActive = p.y < boundary
        val top = if (inActive) activeTop else availTop
        val row = floor((p.y - top + gap / 2f) / (cellH + gap)).toInt().coerceAtLeast(0)
        val col = floor((p.x + gap / 2f) / (cellW + gap)).toInt().coerceIn(0, COLS - 1)
        val idx = row * COLS + col
        return inActive to idx.coerceIn(0, if (inActive) maxActive else maxAvail)
    }

    fun hit(p: Offset, active: List<String>, avail: List<String>): String? {
        fun probe(list: List<String>, isActive: Boolean): String? {
            list.forEachIndexed { i, id ->
                val o = slot(isActive, i)
                if (p.x >= o.x && p.x < o.x + cellW && p.y >= o.y && p.y < o.y + cellH) return id
            }
            return null
        }
        return probe(active, true) ?: probe(avail, false)
    }
}

@Composable
fun HioneQsEditor(
    onDone: () -> Unit,
    accent: Color = HioneTeal,
    accentBright: Color = HioneTealBright,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val order by QsTileOrder.active.collectAsState()

    val active = remember { mutableStateListOf<String>().apply { addAll(order) } }
    val avail = remember { mutableStateListOf<String>().apply { addAll(QsTileOrder.ALL_IDS.filter { it !in order }) } }
    var dragId by remember { mutableStateOf<String?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }      // content coords
    var grab by remember { mutableStateOf(Offset.Zero) }        // finger minus tile top-left at pickup
    var dirty by remember { mutableStateOf(false) }
    var suppressTapUntil by remember { mutableLongStateOf(0L) }
    var resetArmed by remember { mutableStateOf(false) }
    var viewportH by remember { mutableFloatStateOf(0f) }
    val scroll = rememberScrollState()
    val anims = remember { HashMap<String, Animatable<Offset, AnimationVector2D>>() }

    // External change (shell `settings put`, Reset) while nothing is held: adopt it.
    LaunchedEffect(order) {
        if (dragId == null && order != active.toList()) {
            active.clear(); active.addAll(order)
            avail.clear(); avail.addAll(QsTileOrder.ALL_IDS.filter { it !in order })
        }
    }
    LaunchedEffect(resetArmed) { if (resetArmed) { delay(3000); resetArmed = false } }

    fun persist() { QsTileOrder.save(ctx, active.toList()); dirty = false }

    Column(
        modifier
            .fillMaxWidth()
            .hioneGlass(
                RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                HioneGlass.panelStyle(HioneGlass.backdropBlur.collectAsState().value),
                accent = accentBright
            )
            .padding(top = 24.dp)
    ) {
        // ------------------------------------------------------------ header
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EDIT TILES", color = accentBright, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 2.sp)
                Text("Hold a tile, then drag", color = HioneTextSecondary, fontSize = 11.sp)
            }
            GlassChipButton(
                if (resetArmed) "SURE?" else "RESET",
                accent = HionePinkBright, filled = resetArmed,
                textColor = if (resetArmed) HioneDarkBg else HionePinkBright
            ) {
                if (resetArmed) { resetArmed = false; QsTileOrder.reset(ctx); HioneHaptics.pop(view) } else resetArmed = true
            }
            Spacer(Modifier.width(8.dp))
            GlassChipButton("DONE", accent = accentBright, filled = true) {
                if (dirty) persist()
                onDone()
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp)
                .drawBehind { drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(accentBright, accent.copy(alpha = 0.3f), Color.Transparent))) }
        )

        // ------------------------------------------------------------ grid
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .onSizeChanged { viewportH = it.height.toFloat() }
        ) {
            val widthPx = with(density) { (maxWidth - 32.dp).toPx() }
            val gapPx = with(density) { 8.dp.toPx() }
            val cellW = (widthPx - gapPx * (COLS - 1)) / COLS
            val cellH = with(density) { 84.dp.toPx() }
            val labelH = with(density) { 36.dp.toPx() }
            val dividerH = with(density) { 56.dp.toPx() }
            val geom = EditorGeom(widthPx, cellW, cellH, gapPx, labelH, dividerH, active.size, avail.size)
            val geomNow by rememberUpdatedState(geom)

            fun moveTo() {
                val id = dragId ?: return
                val g = geomNow
                val fromActive = id in active
                val (toActive, rawIdx) = g.target(
                    finger,
                    maxActive = if (fromActive) active.size - 1 else active.size,
                    maxAvail = if (fromActive) avail.size else avail.size - 1
                )
                val cur = if (fromActive) active.indexOf(id) else avail.indexOf(id)
                if (toActive == fromActive && rawIdx == cur) return
                if (fromActive && !toActive && active.size <= 1) return   // the shade keeps at least one tile
                if (fromActive) active.removeAt(cur) else avail.removeAt(cur)
                if (toActive) active.add(rawIdx.coerceIn(0, active.size), id) else avail.add(rawIdx.coerceIn(0, avail.size), id)
                dirty = true
                HioneHaptics.tick(view)
            }

            fun drop() {
                val id = dragId ?: return
                suppressTapUntil = System.currentTimeMillis() + 250
                scope.launch {
                    anims[id]?.snapTo(finger - grab)
                    dragId = null
                    HioneHaptics.confirm(view)
                    if (dirty) persist()
                }
            }

            // Auto-scroll while a tile is held near the top or bottom edge of the viewport.
            val held = dragId != null
            LaunchedEffect(held) {
                if (!held) return@LaunchedEffect
                val edge = with(density) { 56.dp.toPx() }
                val maxStep = with(density) { 12.dp.toPx() }
                while (isActive && dragId != null) {
                    withFrameNanos { }
                    val vy = finger.y - scroll.value
                    val step = when {
                        vy < edge -> -maxStep * ((edge - vy) / edge).coerceIn(0f, 1f)
                        vy > viewportH - edge -> maxStep * ((vy - (viewportH - edge)) / edge).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (step != 0f) {
                        val moved = scroll.scrollBy(step)
                        if (moved != 0f) { finger += Offset(0f, moved); moveTo() }
                    }
                }
            }

            val heightDp = with(density) { geom.total.toDp() }
            val activeBottomAnim by animateFloatAsState(geom.activeBottom, HioneMotion.settle(), label = "activeBottom")
            val availBottomAnim by animateFloatAsState(geom.availBottom, HioneMotion.settle(), label = "availBottom")
            // derivedStateOf: `finger` changes on every move event, these only when the side flips
            val hoverActive by remember { derivedStateOf { dragId != null && finger.y < geomNow.boundary } }
            val hoverAvail by remember { derivedStateOf { dragId != null && finger.y >= geomNow.boundary } }
            val heldFromActive = dragId?.let { it in active } == true
            val activeLit by animateFloatAsState(if (hoverActive) 1f else 0f, HioneMotion.ease(), label = "activeLit")
            val availLit by animateFloatAsState(if (hoverAvail) 1f else 0f, HioneMotion.ease(), label = "availLit")

            Box(Modifier.fillMaxSize().verticalScroll(scroll, enabled = !held)) {
                Box(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .height(heightDp)
                        .drawBehind {
                            val pad = 6.dp.toPx()
                            val r = CornerRadius(20.dp.toPx())
                            // section wells: lit teal (place) / pink (remove) while a tile hovers them
                            fun well(top: Float, bottom: Float, lit: Float, c: Color) {
                                drawRoundRect(Mascot.color(0xFF020A0E).copy(alpha = 0.55f), Offset(-pad, top - pad), Size(size.width + 2 * pad, bottom - top + 2 * pad), r)
                                drawRoundRect(
                                    c.copy(alpha = 0.18f + 0.62f * lit), Offset(-pad, top - pad),
                                    Size(size.width + 2 * pad, bottom - top + 2 * pad), r, style = Stroke((1f + 1.2f * lit) * 1.dp.toPx())
                                )
                            }
                            well(geom.activeTop, activeBottomAnim, activeLit, accentBright)
                            well(activeBottomAnim + geom.dividerH, availBottomAnim, availLit, HionePinkBright)
                            // quick-row band behind the first active row
                            drawRoundRect(
                                accentBright.copy(alpha = 0.10f), Offset(-pad / 2, geom.activeTop - pad / 2),
                                Size(size.width + pad, geom.cellH + pad), CornerRadius(18.dp.toPx())
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { p ->
                                if (System.currentTimeMillis() < suppressTapUntil) return@detectTapGestures
                                val id = geomNow.hit(p, active, avail) ?: return@detectTapGestures
                                if (id in avail) {
                                    avail.remove(id); active.add(id)
                                    HioneHaptics.confirm(view)
                                    persist()
                                } else HioneHaptics.tick(view)
                            })
                        }
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { p ->
                                    val g = geomNow
                                    val id = g.hit(p, active, avail) ?: return@detectDragGesturesAfterLongPress
                                    val isA = id in active
                                    val o = g.slot(isA, if (isA) active.indexOf(id) else avail.indexOf(id))
                                    grab = p - o; finger = p; dragId = id
                                    HioneHaptics.pop(view)
                                },
                                onDrag = { change, _ ->
                                    if (dragId == null) return@detectDragGesturesAfterLongPress
                                    change.consume()
                                    // absolute, not finger += amount: positions are re-mapped into
                                    // the scrolled content on every event, deltas are not
                                    finger = change.position
                                    moveTo()
                                },
                                onDragEnd = { drop() },
                                onDragCancel = { drop() }
                            )
                        }
                ) {
                    // section labels
                    SectionLabel("ACTIVE", "first row = quick bar", accentBright, Modifier)
                    SectionLabel(
                        if (hoverAvail && heldFromActive) "RELEASE TO REMOVE" else "AVAILABLE",
                        if (hoverActive && held && !heldFromActive) "release to add" else "drag up to add · tap to append",
                        HionePinkBright,
                        Modifier.offset { IntOffset(0, (activeBottomAnim + 12.dp.toPx()).roundToInt()) }
                    )
                    if (avail.isEmpty() && !held) {
                        Text(
                            "Every tile is in the shade",
                            color = HioneMuted, fontSize = 11.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().offset { IntOffset(0, (geom.availTop + geom.cellH / 2 - 8.dp.toPx()).roundToInt()) }
                        )
                    }
                    // drop target: dashed outline where the held tile will land
                    dragId?.let { id ->
                        val isA = id in active
                        val o = geom.slot(isA, if (isA) active.indexOf(id) else avail.indexOf(id))
                        val c = if (isA) accentBright else HionePinkBright
                        Box(
                            Modifier
                                .offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }
                                .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() })
                                .drawBehind {
                                    drawRoundRect(
                                        c.copy(alpha = 0.85f), cornerRadius = CornerRadius(18.dp.toPx()),
                                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                                    )
                                    drawRoundRect(c.copy(alpha = 0.10f), cornerRadius = CornerRadius(18.dp.toPx()))
                                }
                        )
                    }
                    // tiles: ONE loop over both sections so key(id) keeps each tile's state
                    // (its position animation) when it crosses from one section to the other
                    // (Specs resolved up front: an early `return@key` out of the composable key
                    // lambda produces bytecode R8 rejects.)
                    val entries = active.mapIndexed { i, id -> Triple(id, true, i) } + avail.mapIndexed { i, id -> Triple(id, false, i) }
                    entries.mapNotNull { (id, isA, i) -> QsTileOrder.spec(id)?.let { Triple(it, isA, i) } }.forEach { (spec, isA, i) ->
                        val id = spec.id
                        key(id) {
                            val target = geom.slot(isA, i)
                            val anim = anims.getOrPut(id) { Animatable(target, Offset.VectorConverter) }
                            val isDragged = dragId == id
                            LaunchedEffect(target, isDragged) {
                                if (!isDragged) anim.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 420f))
                            }
                            val lift by animateFloatAsState(if (isDragged) 1f else 0f, HioneMotion.bouncy(), label = "lift")
                            EditorTile(
                                spec = spec,
                                inQuickRow = isA && i < QsTileOrder.QUICK_COUNT,
                                isActive = isA,
                                lift = { lift },
                                accentBright = accentBright,
                                modifier = Modifier
                                    .zIndex(if (isDragged) 10f else 0f)
                                    .offset {
                                        val p = if (isDragged) finger - grab else anim.value
                                        IntOffset(p.x.roundToInt(), p.y.roundToInt())
                                    }
                                    .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() })
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SectionLabel(title: String, hint: String, color: Color, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = color, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(hint, color = HioneTextSecondary.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Static tile for the editor: icon + name, lit when active, lifted while held. */
@Composable
private fun EditorTile(
    spec: QsTileSpec,
    inQuickRow: Boolean,
    isActive: Boolean,
    lift: () -> Float,
    accentBright: Color,
    modifier: Modifier
) {
    val lit by animateFloatAsState(if (isActive) 0.35f else 0f, HioneMotion.settle(), label = "editorLit")
    Column(
        modifier
            .graphicsLayer {
                val l = lift()
                scaleX = 1f + 0.10f * l; scaleY = 1f + 0.10f * l
                rotationZ = -2f * l
            }
            .hioneGlass(
                RoundedCornerShape(18.dp), HioneGlass.Tile,
                accent = if (isActive) accentBright else HionePinkBright,
                active = { maxOf(lit, lift()) }
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            spec.icon, null,
            tint = if (isActive) accentBright else HioneTextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            spec.name, color = if (isActive) HioneWhite else HioneTextSecondary,
            fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp
        )
        if (inQuickRow) {
            Spacer(Modifier.height(2.dp))
            Text("QUICK", color = accentBright, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
    }
}
