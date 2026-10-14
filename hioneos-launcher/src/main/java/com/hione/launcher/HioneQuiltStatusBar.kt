package com.hione.launcher

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The "quilt" — HioneOS's normalized top-bar treatment. Instead of a loose scatter of status badges
 * (which on the home screen let the far-right battery patch wrap off the visible header), the whole
 * status cluster is sewn into one stitched quilt patch: a soft quilted fill with a dashed
 * running-stitch seam around the edge, exactly like the panels of a quilt.
 *
 * The SAME modifier is applied on both the home header and the lockscreen status row so the two
 * bars are visually identical ("normalize the whole top bar") — the individual badges become the
 * patches sewn into that quilt.
 *
 * Purely a decoration modifier: it draws behind its content and adds no gesture/scroll behavior, so
 * it can wrap an existing FlowRow/Row without disturbing the swipe-down-to-open-shade gesture.
 */
fun Modifier.quiltStitch(
    fillStart: Color = Mascot.color(0x1A0EE7DD),
    fillEnd: Color = Mascot.color(0x14FF4FA3),
    seam: Color = Mascot.color(0xB3FFAB2E),
    corner: Dp = 11.dp,
    seamInset: Dp = 3.dp
): Modifier = this.drawBehind {
    val cr = CornerRadius(corner.toPx(), corner.toPx())
    // Quilted fill — a gentle teal→pink wash under the patches.
    drawRoundRect(
        brush = Brush.horizontalGradient(listOf(fillStart, fillEnd)),
        cornerRadius = cr
    )
    // Running-stitch seam: a dashed inset outline that reads as thread stitched around the panel.
    val inset = seamInset.toPx()
    val stitch = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
    drawRoundRect(
        color = seam,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        cornerRadius = CornerRadius(cr.x - inset, cr.y - inset),
        style = Stroke(width = 1.2.dp.toPx(), pathEffect = stitch)
    )
}

/** Convenience: the quilt patch with the standard interior padding the status badges expect. */
@Composable
fun Modifier.quiltPatch(): Modifier =
    this.quiltStitch().padding(horizontal = 6.dp, vertical = 4.dp)
