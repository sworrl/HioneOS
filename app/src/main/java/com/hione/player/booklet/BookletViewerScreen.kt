package com.hione.player.booklet

import com.hione.player.Mascot
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.AudiowideFont
import com.hione.player.Ground
import com.hione.player.HapticIconButton
import com.hione.player.Haptics
import com.hione.player.HioneGold
import com.hione.player.HionePink
import com.hione.player.HioneTeal
import com.hione.player.HioneTealBright
import com.hione.player.Muted
import com.hione.player.OrbitronFont
import com.hione.player.ReleaseTagColor
import com.hione.player.Surface1
import com.hione.player.ui.HioneTopBar
import com.hione.player.ui.yieldSystemGestureEdges
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Per-page pinch/pan state, kept outside the page composable so back can reset it and the pager
 *  can stop swiping while a page is zoomed. */
class PageZoomState {
    var scale by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    val zoomed: Boolean get() = scale > 1.02f
    fun reset() { scale = 1f; offset = Offset.Zero }
    fun clamp(w: Float, h: Float) {
        val mx = (w * (scale - 1f)) / 2f + w * 0.15f * (scale - 1f).coerceAtMost(1f)
        val my = (h * (scale - 1f)) / 2f + h * 0.15f * (scale - 1f).coerceAtMost(1f)
        offset = Offset(offset.x.coerceIn(-mx, mx), offset.y.coerceIn(-my, my))
    }
}

/**
 * The skeuomorphic package viewer. Discovers art for [query], shows the closed sleeve first (tap to
 * fold the cover open around its spine), then a page-turning pager with pinch-zoom, a thumbnail
 * strip and a page counter. Nothing is shown that isn't on disk.
 */
@Composable
fun BookletViewerScreen(
    query: AlbumArtQuery,
    albumTitle: String,
    releaseTag: String?,
    qualityTag: String?,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullPx = remember(cfg.screenWidthDp, cfg.screenHeightDp) {
        with(density) { BookletImageLoader.fullTargetPx(cfg.screenWidthDp.dp.roundToPx(), cfg.screenHeightDp.dp.roundToPx()) }
    }

    var pkg by remember(query.cacheKey) { mutableStateOf(AlbumArtSources.peek(query)) }
    var loading by remember(query.cacheKey) { mutableStateOf(pkg == null) }
    LaunchedEffect(query.cacheKey) {
        if (pkg == null) {
            pkg = AlbumArtSources.discover(ctx, query)
            loading = false
        }
    }
    val pages = pkg?.pages ?: emptyList()

    // 0 = sleeve closed, 1 = cover folded fully open. Drives the 3D fold and the pager reveal.
    val fold = remember { Animatable(0f) }
    var opened by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = 0) { pages.size }
    val zoomStates = remember { HashMap<Int, PageZoomState>() }
    fun zoomFor(i: Int) = zoomStates.getOrPut(i) { PageZoomState() }
    val currentZoom = zoomFor(pagerState.currentPage)

    var orientationMode by remember { mutableIntStateOf(0) } // 0 auto · 1 landscape · 2 portrait
    fun applyOrientation(mode: Int) {
        (ctx as? Activity)?.requestedOrientation = when (mode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    fun openSleeve() {
        if (opened || pages.isEmpty()) return
        Haptics.tick(ctx)
        scope.launch {
            fold.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 110f))
            opened = true
            // Opening the cover reveals what's under it: land on the first inner page.
            if (pages.size > 1 && pagerState.currentPage == 0) pagerState.scrollToPage(1)
        }
    }
    fun closeSleeve() {
        if (!opened) return
        Haptics.tick(ctx)
        opened = false
        zoomStates.values.forEach { it.reset() }
        scope.launch {
            pagerState.scrollToPage(0)
            fold.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = 140f))
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            currentZoom.zoomed -> currentZoom.reset()
            opened -> closeSleeve()
            else -> onClose()
        }
    }

    val subtitle = remember(query.artist, qualityTag, pkg) {
        buildList {
            if (query.artist.isNotBlank()) add(query.artist)
            qualityTag?.takeIf { it.isNotBlank() }?.let { add(it) }
            pkg?.let { p ->
                if (!p.isEmpty) {
                    add("${p.pages.size} page${if (p.pages.size == 1) "" else "s"}")
                    if (p.pdfCount > 0) add("PDF")
                }
            }
        }.joinToString("  ·  ")
    }

    Column(Modifier.fillMaxSize().background(Ground)) {
        HioneTopBar(
            title = albumTitle,
            onBack = { if (currentZoom.zoomed) currentZoom.reset() else if (opened) closeSleeve() else onClose() },
            actions = {
                releaseTag?.let {
                    Text(
                        it, color = ReleaseTagColor, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, fontFamily = OrbitronFont,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier
                            .border(1.dp, ReleaseTagColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                val tint = when (orientationMode) { 1 -> HionePink; 2 -> HioneGold; else -> HioneTealBright }
                HapticIconButton(
                    onClick = { orientationMode = (orientationMode + 1) % 3; applyOrientation(orientationMode) },
                    flat = true, modifier = Modifier.size(38.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ScreenRotation, "Rotate", tint = tint, modifier = Modifier.size(18.dp))
                        Text(
                            when (orientationMode) { 1 -> "LAND"; 2 -> "PORT"; else -> "AUTO" },
                            color = tint, fontSize = 7.sp, fontFamily = OrbitronFont, letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        )
        if (!isLandscape && subtitle.isNotBlank()) {
            Text(
                subtitle, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 4.dp)
            )
        }

        val showThumbs = opened && pages.size > 1
        val stage: @Composable (Modifier) -> Unit = { m ->
            Box(m, contentAlignment = Alignment.Center) {
                when {
                    loading -> LoadingSleeve()
                    pages.isEmpty() -> EmptyPackage(pkg)
                    else -> PackageStage(
                        pages = pages, fold = fold, opened = opened, pagerState = pagerState,
                        zoomFor = ::zoomFor, fullPx = fullPx, onOpen = ::openSleeve
                    )
                }
            }
        }

        if (isLandscape) {
            Row(Modifier.fillMaxSize()) {
                stage(Modifier.weight(1f).fillMaxHeight())
                if (showThumbs) ThumbStrip(pages, pagerState, vertical = true, modifier = Modifier.width(64.dp).fillMaxHeight())
            }
        } else {
            stage(Modifier.weight(1f).fillMaxWidth())
            if (showThumbs) ThumbStrip(pages, pagerState, vertical = false, modifier = Modifier.fillMaxWidth().height(64.dp))
            if (opened && pages.isNotEmpty()) PageCounter(pages, pagerState.currentPage, currentZoom)
        }
    }
}

// ------------------------------------------------------------------------------------ stage

@Composable
private fun PackageStage(
    pages: List<ArtPage>,
    fold: Animatable<Float, *>,
    opened: Boolean,
    pagerState: PagerState,
    zoomFor: (Int) -> PageZoomState,
    fullPx: Int,
    onOpen: () -> Unit,
) {
    val f = fold.value
    // HorizontalPager can't take an edge guard, so the container yields the window's edge bands
    // instead: a horizontal swipe that starts in the side bands is the system BACK gesture (and a
    // vertical one from the bottom/top band is home/shade) — the pager/zoom never see it. Taps
    // and interior swipes are untouched.
    Box(Modifier.fillMaxSize().yieldSystemGestureEdges(), contentAlignment = Alignment.Center) {
        // The inside of the package: revealed as the cover folds away.
        if (f > 0.02f || opened) {
            val currentZoom = zoomFor(pagerState.currentPage)
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().alpha(f.coerceIn(0f, 1f)),
                userScrollEnabled = opened && !currentZoom.zoomed,
                pageSpacing = 12.dp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                key = { pages[it].key },
            ) { index ->
                BookletPage(page = pages[index], index = index, pagerState = pagerState, zoom = zoomFor(index), fullPx = fullPx)
            }
        }
        // The front cover, hinged on its spine (left edge), folding open over the pager.
        if (f < 0.995f) {
            ClosedCover(
                page = pages[0], fold = f, fullPx = fullPx,
                onTap = onOpen,
                modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun ClosedCover(page: ArtPage, fold: Float, fullPx: Int, onTap: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp = rememberPageBitmap(page, fullPx)
    val ratio = bmp?.let { it.width.toFloat() / it.height.toFloat() } ?: 1f
    val pulse by rememberInfiniteTransition(label = "hint").animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "hintAlpha"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .aspectRatio(ratio.coerceIn(0.5f, 2f))
                    .graphicsLayer {
                        // Fold around the spine: the cover swings out of the screen toward the
                        // viewer's left, exactly like lifting a jewel-case lid held at its hinge.
                        rotationY = -fold * 165f
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        cameraDistance = 14f * density
                        shadowElevation = (8f + 20f * fold) * density
                        alpha = if (fold > 0.9f) ((1f - fold) / 0.1f).coerceIn(0f, 1f) else 1f
                    }
                    .clickable(enabled = fold < 0.05f) { onTap() }
            ) {
                if (fold <= 0.5f) {
                    PaperFrame(kind = ArtKind.FRONT, modifier = Modifier.fillMaxSize()) {
                        if (bmp != null) Image(bmp, "Front cover", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else PagePlaceholder()
                    }
                } else {
                    // Past 90° we are looking at the INSIDE of the cover; draw it un-mirrored.
                    Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { InsideCoverFace() }
                }
            }
            if (fold < 0.05f) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "TAP TO OPEN", color = HioneTealBright.copy(alpha = pulse), fontSize = 10.sp, fontFamily = OrbitronFont,
                    letterSpacing = 2.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "rotate for wide pages · pinch to zoom", color = Muted.copy(alpha = 0.85f), fontSize = 9.5.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
    // Keep the hi-res cover warm for the pager's page 0 (same key → memory hit).
    LaunchedEffect(page.key) { BookletImageLoader.load(ctx, page, fullPx) }
}

@Composable
private fun InsideCoverFace() {
    Box(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(3.dp))
            .background(Brush.linearGradient(listOf(Mascot.color(0xFF0B2226), Surface1, Mascot.color(0xFF07191C))))
            .border(1.dp, HioneTeal.copy(alpha = 0.25f), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MenuBook, null, tint = HioneTeal.copy(alpha = 0.35f), modifier = Modifier.size(42.dp))
    }
}

// ------------------------------------------------------------------------------------ pages

@Composable
private fun BookletPage(page: ArtPage, index: Int, pagerState: PagerState, zoom: PageZoomState, fullPx: Int) {
    val bmp = rememberPageBitmap(page, fullPx)
    val ratio = bmp?.let { it.width.toFloat() / it.height.toFloat() } ?: 1f
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            // Neighbouring pages lean away around the gutter as they turn — a hint of paper, not a
            // carousel. Small angle, stiff camera: cheap on a DAP GPU.
            val off = ((pagerState.currentPage - index) + pagerState.currentPageOffsetFraction).coerceIn(-1f, 1f)
            rotationY = off * 26f
            transformOrigin = if (off > 0f) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)
            cameraDistance = 16f * density
            alpha = 1f - abs(off) * 0.35f
        },
        contentAlignment = Alignment.Center
    ) {
        ZoomableArt(zoom = zoom, modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .aspectRatio(ratio.coerceIn(0.3f, 3.5f))
                    .graphicsLayer {
                        scaleX = zoom.scale; scaleY = zoom.scale
                        translationX = zoom.offset.x; translationY = zoom.offset.y
                    }
            ) {
                PaperFrame(kind = page.kind, modifier = Modifier.fillMaxSize()) {
                    if (bmp != null) Image(bmp, page.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else PagePlaceholder()
                }
            }
        }
    }
}

/** Pinch-zoom + pan that only claims the pointer when it's a two-finger gesture or the page is
 *  already zoomed — so the pager's single-finger swipe keeps working at 1x. Double-tap toggles. */
@Composable
private fun ZoomableArt(zoom: PageZoomState, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .pointerInput(zoom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val z = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val newScale = (zoom.scale * z).coerceIn(1f, 6f)
                            val c = centroid - Offset(size.width / 2f, size.height / 2f)
                            val ratio = newScale / zoom.scale
                            zoom.offset = c - (c - zoom.offset) * ratio + pan
                            zoom.scale = newScale
                            zoom.clamp(size.width.toFloat(), size.height.toFloat())
                            event.changes.forEach { it.consume() }
                        } else if (zoom.zoomed && pressed.size == 1) {
                            val ch = pressed[0]
                            zoom.offset += ch.position - ch.previousPosition
                            zoom.clamp(size.width.toFloat(), size.height.toFloat())
                            ch.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (zoom.scale < 1.04f) zoom.reset()
                }
            }
            .pointerInput(zoom) {
                detectTapGestures(onDoubleTap = { pos ->
                    if (zoom.zoomed) zoom.reset() else {
                        val c = pos - Offset(size.width / 2f, size.height / 2f)
                        zoom.scale = 2.6f
                        zoom.offset = c - c * 2.6f
                        zoom.clamp(size.width.toFloat(), size.height.toFloat())
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Physical-media chrome around a page: spine on covers, gutter shadow on booklet pages, hub hole
 *  on a disc, gloss on everything — drawn over the image so it zooms with it. */
@Composable
private fun PaperFrame(kind: ArtKind, modifier: Modifier, content: @Composable () -> Unit) {
    val shape = if (kind == ArtKind.DISC) CircleShape else RoundedCornerShape(3.dp)
    Box(
        modifier
            .shadow(10.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Mascot.color(0xFF07171A))
            .drawWithContent {
                drawContent()
                val w = size.width; val h = size.height
                val spineW = (w * 0.045f).coerceIn(6f, 22f)
                when (kind) {
                    ArtKind.FRONT -> {
                        drawRect(Brush.horizontalGradient(listOf(Color(0x99000000), Color(0x22000000), Color.Transparent), 0f, spineW * 1.6f), size = size.copy(width = spineW * 1.6f))
                        drawRect(Color(0x33FFFFFF), topLeft = Offset(spineW, 0f), size = size.copy(width = 1.5f))
                    }
                    ArtKind.BACK -> {
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x22000000), Color(0x99000000)), w - spineW * 1.6f, w), topLeft = Offset(w - spineW * 1.6f, 0f), size = size.copy(width = spineW * 1.6f))
                        drawRect(Color(0x33FFFFFF), topLeft = Offset(w - spineW - 1.5f, 0f), size = size.copy(width = 1.5f))
                    }
                    ArtKind.BOOKLET, ArtKind.INLAY, ArtKind.OTHER, ArtKind.SPINE -> {
                        drawRect(Brush.horizontalGradient(listOf(Color(0x66000000), Color.Transparent), 0f, spineW * 1.4f), size = size.copy(width = spineW * 1.4f))
                        drawRect(Color(0x1AFFFFFF), topLeft = Offset(w - 1.5f, 0f), size = size.copy(width = 1.5f))
                    }
                    ArtKind.DISC -> {
                        val c = Offset(w / 2f, h / 2f)
                        val r = minOf(w, h) / 2f
                        drawCircle(Color(0x66000000), radius = r * 0.19f, center = c)
                        drawCircle(Ground, radius = r * 0.075f, center = c)
                        drawCircle(Color(0x55FFFFFF), radius = r * 0.075f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                        drawCircle(Color(0x33FFFFFF), radius = r * 0.185f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        drawCircle(Color(0x44FFFFFF), radius = r - 1f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    }
                }
                // Plastic/gloss sheen, diagonal, very light.
                drawRect(Brush.linearGradient(listOf(Color(0x1FFFFFFF), Color.Transparent, Color(0x0DFFFFFF)), Offset.Zero, Offset(w, h)))
            }
            .border(1.dp, HioneTeal.copy(alpha = 0.28f), shape)
    ) { content() }
}

@Composable
private fun PagePlaceholder() {
    Box(Modifier.fillMaxSize().background(Surface1), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = HioneTeal, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun rememberPageBitmap(page: ArtPage, targetPx: Int): ImageBitmap? {
    val ctx = LocalContext.current
    var bmp by remember(page.key, targetPx) { mutableStateOf(BookletImageLoader.peek(page, targetPx)) }
    LaunchedEffect(page.key, targetPx) {
        if (bmp == null) bmp = BookletImageLoader.load(ctx, page, targetPx)
    }
    return bmp
}

// ------------------------------------------------------------------------------------ chrome

@Composable
private fun ThumbStrip(pages: List<ArtPage>, pagerState: PagerState, vertical: Boolean, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val current = pagerState.currentPage
    LaunchedEffect(current) { listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    val thumb: @Composable (Int, ArtPage) -> Unit = { i, p ->
        val bmp = rememberPageBitmap(p, BookletImageLoader.THUMB_PX)
        val sel = i == current
        Box(
            Modifier
                .size(50.dp)
                .clip(if (p.kind == ArtKind.DISC) CircleShape else RoundedCornerShape(5.dp))
                .background(Surface1)
                .border(if (sel) 1.5.dp else 1.dp, if (sel) HioneTealBright else HioneTeal.copy(alpha = 0.25f), if (p.kind == ArtKind.DISC) CircleShape else RoundedCornerShape(5.dp))
                .alpha(if (sel) 1f else 0.62f)
                .clickable { scope.launch { pagerState.animateScrollToPage(i) } },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) Image(bmp, p.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (p.isPdf) Icon(Icons.Default.PictureAsPdf, null, tint = HioneTeal.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
            if (p.isPdf && bmp != null) {
                Text(
                    "PDF", color = Color.White, fontSize = 6.5.sp, fontFamily = OrbitronFont,
                    modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xAA000000), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp)
                )
            }
        }
    }
    if (vertical) {
        LazyColumn(state = listState, modifier = modifier.padding(horizontal = 7.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(pages, key = { _, p -> p.key }) { i, p -> thumb(i, p) }
        }
    } else {
        LazyRow(state = listState, modifier = modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            itemsIndexed(pages, key = { _, p -> p.key }) { i, p -> thumb(i, p) }
        }
    }
}

@Composable
private fun PageCounter(pages: List<ArtPage>, current: Int, zoom: PageZoomState) {
    val p = pages.getOrNull(current) ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${current + 1} / ${pages.size}", color = HioneTealBright, fontSize = 12.sp, fontFamily = OrbitronFont, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.width(10.dp))
        Text(p.kind.label.uppercase(), color = HionePink, fontSize = 9.sp, fontFamily = OrbitronFont, letterSpacing = 1.sp)
        Spacer(Modifier.width(8.dp))
        val detail = (p.ref as? ArtSourceRef.PdfPage)?.let { "${it.file.name} · ${it.pageIndex + 1}/${it.pageCount}" } ?: p.title
        Text(detail, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (zoom.zoomed) {
            Text("${"%.1f".format(zoom.scale)}×", color = HioneGold, fontSize = 10.sp, fontFamily = OrbitronFont)
        }
    }
}

@Composable
private fun LoadingSleeve() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = HioneTeal, strokeWidth = 2.dp, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(12.dp))
        Text("READING THE SLEEVE", color = HioneTealBright, fontSize = 10.sp, fontFamily = OrbitronFont, letterSpacing = 2.sp)
    }
}

@Composable
private fun EmptyPackage(pkg: AlbumArtPackage?) {
    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.BrokenImage, null, tint = HioneTeal.copy(alpha = 0.5f), modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text("NO PRINTED ART ON DISK", color = HioneTealBright, fontSize = 11.sp, fontFamily = AudiowideFont, letterSpacing = 1.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "This album's folder has no cover, back, inlay, disc or booklet scans and no booklet PDF. " +
                "Drop files named cover.jpg, back.jpg, disc.png, booklet-01.jpg… (or a scans/ folder, or a .pdf) next to the music.",
            color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 15.sp
        )
        val folders = pkg?.foldersSearched.orEmpty()
        if (folders.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Looked in:", color = Muted.copy(alpha = 0.85f), fontSize = 9.5.sp)
            folders.take(3).forEach {
                Text(it.absolutePath, color = Muted.copy(alpha = 0.85f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Online auto-sourcing: not enabled yet", color = Muted.copy(alpha = 0.85f), fontSize = 9.5.sp, fontFamily = OrbitronFont)
    }
}
