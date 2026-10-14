package com.hione.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Throwaway target so the KDE menu's Preview shows the REAL window transition.
 *  Tap anywhere to dismiss (which plays the effect's exit animation). */
class HioneCompositingPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra("label") ?: "PREVIEW"
        setContent {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.radialGradient(listOf(Color(0xFF141A2E), Color(0xFF04060C))))
                    .clickable { finish() },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.clip(RoundedCornerShape(20.dp))
                        .background(Mascot.color(0x220FF5FF))
                        .border(1.dp, Mascot.color(0xFFFFC56B), RoundedCornerShape(20.dp))
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("KDE COMPOSITING", color = Mascot.color(0xFFFF80AB), fontSize = 13.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    Text(label, color = Mascot.color(0xFFFFC56B), fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("tap to dismiss (plays exit)", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        // exit anim is supplied by the launcher via the enter/exit pair; nothing to do.
    }
}
