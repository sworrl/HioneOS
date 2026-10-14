package com.hione.player.booklet

import com.hione.player.Mascot
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.Haptics
import com.hione.player.HioneTeal
import com.hione.player.HioneTealBright
import com.hione.player.Muted
import com.hione.player.Track

/**
 * Album-screen entry point for the booklet viewer. Runs discovery off-thread and renders NOTHING
 * until it knows there is real art on disk for this album — so the album header never advertises
 * a booklet that isn't there. Pill styling matches the Play/Shuffle action row above it.
 */
@Composable
fun AlbumBookletButton(
    tracks: List<Track>,
    albumTitle: String,
    qualityTag: String? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val query = remember(tracks, albumTitle) { BookletViewerActivity.queryFrom(tracks, albumTitle) }
    var pkg by remember(query.cacheKey) { mutableStateOf(AlbumArtSources.peek(query)) }
    LaunchedEffect(query.cacheKey) {
        if (pkg == null && query.folders.isNotEmpty()) pkg = AlbumArtSources.discover(ctx, query)
    }
    val p = pkg ?: return
    if (p.isEmpty) return

    val label = when {
        p.onlyEmbeddedCover -> "Cover art"
        p.pages.size == 1 -> p.pages[0].kind.label
        else -> "Booklet · ${p.pages.size} pages"
    }
    val extras = buildList {
        if (p.pdfCount > 0) add("PDF")
        if (p.count(ArtKind.DISC) > 0) add("disc")
        if (p.count(ArtKind.BACK) > 0) add("back")
    }

    Spacer(Modifier.height(10.dp))
    Row(
        modifier
            .clip(RoundedCornerShape(19.dp))
            .background(Mascot.color(0xFF0F2B2E))
            .border(1.dp, HioneTeal.copy(alpha = 0.5f), RoundedCornerShape(19.dp))
            .clickable {
                Haptics.tick(ctx)
                BookletViewerActivity.launch(ctx, tracks, albumTitle, qualityTag)
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.MenuBook, "Open booklet", tint = HioneTealBright, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = HioneTealBright, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        if (extras.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(extras.joinToString(" · "), color = Muted, fontSize = 10.5.sp)
        }
    }
}
