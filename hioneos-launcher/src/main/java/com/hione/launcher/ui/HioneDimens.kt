package com.hione.launcher.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * HioneOS launcher size tokens, targeted at the M500's 720x1280 @ 320dpi panel (= 360x640dp).
 *
 * Everything user-visible in the launcher should size itself from these instead of ad-hoc
 * literals so the top bar, quilt, dock, drawer, modals and lockscreen share one scale:
 *  - an 8dp grid,
 *  - touch targets never below [touchMin],
 *  - text never below [textXs] (11sp) on this panel.
 */
object HioneDimens {
    // Grid
    val grid = 8.dp
    val gridHalf = 4.dp
    val screenHPad = 12.dp

    // Top bar (thin, standard status-bar layout) + quilt (badge patchwork under it)
    val statusBarHeight = 30.dp
    val statusGlyph = 14.dp
    val quiltGapCompact = 3.dp
    val quiltGapRoomy = 6.dp

    // Icons
    val appIcon = 56.dp        // drawer / desktop cell icon
    val appIconArt = 52.dp     // the drawn (clipped) icon inside the cell
    val dockIcon = 52.dp
    val dockHero = 66.dp
    val badgeHeight = 36.dp
    val pill = 32.dp
    val touchMin = 40.dp

    // Corners
    val cornerS = 12.dp
    val cornerM = 16.dp
    val cornerL = 24.dp

    // Text
    val textXs = 11.sp
    val textS = 13.sp
    val textM = 15.sp
    val textL = 18.sp
    val textXl = 24.sp
}
