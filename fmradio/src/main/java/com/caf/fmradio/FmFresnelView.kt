package com.caf.fmradio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.max
import kotlin.math.min

/**
 * The hill, drawn to scale, with the radio path over it.
 *
 * Every number on this comes from somewhere real: ground elevations from the elevation
 * service, the transmitter's height from its HAAT in the public record, the Fresnel radius
 * from the actual wavelength. With no profile it draws nothing and says so, because a plausible
 * looking cross-section of invented terrain is worse than an empty panel.
 */
@Composable
fun FmFresnelProfileView(
    profile: FmFresnel.Profile?,
    modifier: Modifier = Modifier,
    /** The station as the engine rated it, carrying the measured and predicted levels. */
    rated: FmStationCatalogue.Station? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF0C0D10), RoundedCornerShape(14.dp))
            .border(1.2.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
    ) {
        if (profile == null) {
            Column(Modifier.align(Alignment.Center).padding(14.dp)) {
                Text("NO TERRAIN PROFILE", color = HioneTeal.copy(alpha = 0.8f),
                     fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("Needs a position and one lookup against the elevation service.",
                     color = HioneTeal.copy(alpha = 0.45f), fontSize = 7.sp)
            }
            return@Box
        }

        val pts = profile.points
        Canvas(Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 16.dp)) {
            val w = size.width
            val h = size.height
            val total = pts.last().distM.coerceAtLeast(1.0)

            // Vertical extent covers the terrain, the sight line and the top of the Fresnel
            // zone, so nothing important is drawn off the panel.
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (p in pts) {
                lo = min(lo, min(p.groundM, p.sightM - p.fresnelM))
                hi = max(hi, max(p.groundM, p.sightM + p.fresnelM))
            }
            val pad = ((hi - lo) * 0.08).coerceAtLeast(10.0)
            lo -= pad; hi += pad
            val span = (hi - lo).coerceAtLeast(1.0)

            fun x(d: Double) = (d / total * w).toFloat()
            fun y(m: Double) = (h - (m - lo) / span * h).toFloat()

            // The first Fresnel zone, as the ellipse it actually is.
            val zone = Path().apply {
                moveTo(x(pts.first().distM), y(pts.first().sightM + pts.first().fresnelM))
                for (p in pts) lineTo(x(p.distM), y(p.sightM + p.fresnelM))
                for (p in pts.reversed()) lineTo(x(p.distM), y(p.sightM - p.fresnelM))
                close()
            }
            drawPath(zone, HioneTeal.copy(alpha = 0.13f))
            drawPath(zone, HioneTeal.copy(alpha = 0.35f), style = Stroke(width = 1f))

            // The 60% boundary: inside this and the path starts costing signal.
            val sixty = Path().apply {
                moveTo(x(pts.first().distM),
                       y(pts.first().sightM - pts.first().fresnelM * FmFresnel.FRESNEL_RULE))
                for (p in pts) lineTo(x(p.distM), y(p.sightM - p.fresnelM * FmFresnel.FRESNEL_RULE))
            }
            drawPath(sixty, HionePink.copy(alpha = 0.45f), style = Stroke(
                width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))))

            // Line of sight.
            val sight = Path().apply {
                moveTo(x(pts.first().distM), y(pts.first().sightM))
                for (p in pts) lineTo(x(p.distM), y(p.sightM))
            }
            drawPath(sight, HioneTeal.copy(alpha = 0.9f), style = Stroke(width = 1.6f))

            // The ground, filled. Where it rises through the sight line it is drawn in pink,
            // because that is the part that is in the way and it should be obvious which.
            val ground = Path().apply {
                moveTo(0f, h)
                for (p in pts) lineTo(x(p.distM), y(p.groundM))
                lineTo(w, h)
                close()
            }
            drawPath(ground, Brush.verticalGradient(
                listOf(Color(0xFF0E5A66), Color(0xFF04141A)), y(hi), h))
            drawPath(
                Path().apply {
                    moveTo(x(pts.first().distM), y(pts.first().groundM))
                    for (p in pts) lineTo(x(p.distM), y(p.groundM))
                },
                if (profile.blocked) HioneNeonPink else HioneTeal,
                style = Stroke(width = 1.8f))

            // Mark the worst point, which is the one the verdict is about.
            profile.worst?.let { wst ->
                val px = x(wst.distM)
                drawLine(HionePink.copy(alpha = 0.6f), Offset(px, 0f), Offset(px, h), strokeWidth = 1f)
                drawCircle(HionePink, radius = 3f, center = Offset(px, y(wst.groundM)))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
        ) {
            Text(
                "PATH TO ${profile.station.label} · ${"%.1f".format(profile.distanceKm)} km",
                color = HioneTeal.copy(alpha = 0.85f), fontSize = 7.5.sp, fontWeight = FontWeight.Bold
            )
            // The terrain line is a prediction. A reading from the tuner, when there is one,
            // is the fact and goes next to it.
            Text(
                profile.verdict.uppercase() +
                    (if (profile.diffractionLossDb > 0.5)
                        " · PREDICTS -${"%.0f".format(profile.diffractionLossDb)} dB"
                    else "") +
                    (rated?.measuredDbuv?.let { " · MEASURED $it dBµV" } ?: ""),
                color = when {
                    rated?.reach == FmReach.Verdict.HEARD -> HioneCyan
                    profile.blocked -> HioneNeonPink
                    else -> HionePink
                },
                fontSize = 7.5.sp, fontWeight = FontWeight.Bold
            )
        }

        profile.worst?.let { wst ->
            Text(
                "worst point ${"%.1f".format(wst.distM / 1000)} km out · terrain " +
                    "${wst.groundM.toInt()} m vs sight line ${wst.sightM.toInt()} m · " +
                    "${"%.0f".format(wst.fresnelFraction * 100)}% of the first zone · prediction",
                color = HioneTeal.copy(alpha = 0.5f), fontSize = 6.5.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}
