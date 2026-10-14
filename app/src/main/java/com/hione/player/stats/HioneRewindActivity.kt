package com.hione.player.stats

import com.hione.player.Mascot
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.hione.player.CrashSentinel
import com.hione.player.CyberDarkBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hione Rewind — swipeable full-screen story cards (Spotify-Wrapped style) over the real listen DB.
 * Period chips (This week / month / year / All time + any past calendar year with data), story
 * progress segments, tap-right/left or swipe to advance, and a Share that renders the CURRENT
 * card to a PNG through a Compose GraphicsLayer and hands it to the share sheet via the existing
 * `com.hione.player.statsexport` FileProvider (same `stats/` dir the CSV export uses).
 *
 * Every deck is precomputed once per period on IO ([RewindBuilder.build]) and cached for the
 * activity's lifetime; the UI never touches the DB.
 */
class HioneRewindActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashSentinel.install(this)
        super.onCreate(savedInstanceState)
        setContent { HioneRewindScreen(onBack = { finish() }) }
    }

    companion object {
        fun launch(ctx: Context) = ctx.startActivity(Intent(ctx, HioneRewindActivity::class.java))
    }
}

private const val EXPORT_AUTHORITY = "com.hione.player.statsexport"

@Composable
fun HioneRewindScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val builder = remember { RewindBuilder(ctx) }
    val scope = rememberCoroutineScope()
    val decks = remember { HashMap<String, RewindDeck>() }

    var periods by remember { mutableStateOf(RewindPeriod.standard()) }
    var period by remember { mutableStateOf(periods[0]) }
    var deck by remember { mutableStateOf<RewindDeck?>(null) }
    var loading by remember { mutableStateOf(true) }
    var sharing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { periods = runCatching { RewindPeriod.available(ctx) }.getOrDefault(periods) }
    LaunchedEffect(period) {
        val cached = decks[period.key]
        if (cached != null) { deck = cached; loading = false; return@LaunchedEffect }
        loading = true
        val d = runCatching { builder.build(period) }.getOrElse {
            RewindDeck(period, 0, listOf(RewindStory.NotEnough("${Mascot.name} Rewind", 0, Threshold.DECK, "listens (stats DB unreadable: ${it.message})")))
        }
        decks[period.key] = d
        deck = d
        loading = false
    }

    // The share target: everything drawn inside this layer is what lands in the PNG.
    val shareLayer = rememberGraphicsLayer()

    Box(Modifier.fillMaxSize().background(CyberDarkBg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---- header ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = RewindTealBright,
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onBack() }.padding(6.dp)
                )
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("${Mascot.nameUpper} REWIND", color = RewindTealBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(period.label, color = RewindInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                val canShare = !loading && !sharing && deck != null
                Icon(
                    Icons.Default.Share, "Share this card", tint = if (canShare) RewindPinkSoft else RewindMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(36.dp).clip(CircleShape).clickable(enabled = canShare) {
                        sharing = true
                        scope.launch {
                            try {
                                val bmp = shareLayer.toImageBitmap().asAndroidBitmap()
                                val f = withContext(Dispatchers.IO) { writeSharePng(ctx, bmp, period) }
                                val uri = FileProvider.getUriForFile(ctx, EXPORT_AUTHORITY, f)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "${Mascot.name} Rewind · ${period.label}")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { ctx.startActivity(Intent.createChooser(send, "Share ${Mascot.name} Rewind")) }
                                    .onFailure { Toast.makeText(ctx, "Saved ${f.absolutePath}", Toast.LENGTH_LONG).show() }
                            } catch (t: Throwable) {
                                Toast.makeText(ctx, "Share failed: ${t.message}", Toast.LENGTH_LONG).show()
                            }
                            sharing = false
                        }
                    }.padding(7.dp)
                )
            }
            // ---- period chips ----
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                periods.forEach { p -> PeriodChip(p.label, p.key == period.key) { period = p } }
            }
            Spacer(Modifier.height(8.dp))

            // ---- stories ----
            val d = deck
            Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (loading || d == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = RewindTeal, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Rewinding ${period.inWords}…", color = RewindMuted, fontSize = 13.sp)
                        }
                    }
                } else {
                    StoryDeck(d, shareLayerModifier = Modifier.drawWithContent {
                        shareLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(shareLayer)
                    })
                }
            }
        }
    }
}

@Composable
private fun StoryDeck(deck: RewindDeck, shareLayerModifier: Modifier) {
    val n = deck.stories.size
    val pager = rememberPagerState(initialPage = 0) { n }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(22.dp)

    Column(Modifier.fillMaxSize()) {
        // ---- progress segments (stories style) ----
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0 until n) {
                val on by animateFloatAsState(if (i <= pager.currentPage) 1f else 0f, label = "seg$i")
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(on).height(3.dp).background(if (i == pager.currentPage) RewindPink else RewindTeal))
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .border(1.dp, RewindTeal.copy(alpha = 0.35f), shape)
                .then(shareLayerModifier)
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                val story = deck.stories[page]
                val active = pager.currentPage == page
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(page, n) {
                            detectTapGestures { off ->
                                val next = off.x > size.width * 0.35f
                                val target = if (next) (page + 1).coerceAtMost(n - 1) else (page - 1).coerceAtLeast(0)
                                if (target != page) scope.launch { pager.animateScrollToPage(target) }
                            }
                        }
                ) {
                    RewindBackdrop(index = page, active = active)
                    RewindStoryCard(story, deck.period)
                }
            }
            // page counter, bottom-right
            Text(
                "${pager.currentPage + 1}/$n", color = RewindMuted.copy(alpha = 0.8f), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun PeriodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) RewindPink.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (selected) RewindPinkSoft.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) RewindPinkSoft else RewindMuted, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** PNG into the same `stats/` dir the CSV export uses (covered by res/xml/stats_export_paths.xml). */
private fun writeSharePng(ctx: Context, bmp: Bitmap, period: RewindPeriod): File {
    val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "stats").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val f = File(dir, "hione-rewind-${period.key.lowercase()}-$stamp.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return f
}
