package com.hione.update.ui

import com.hione.update.Mascot
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Dark modules on a white tile: scanners want the contrast, so this one stays light on purpose. */
@Composable
fun QrCode(text: String, size: Dp = 168.dp) {
    val matrix: BitMatrix? = remember(text) {
        runCatching {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    }
    if (matrix == null) return
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)
    ) {
        Canvas(Modifier.size(size - 20.dp)) {
            val n = matrix.width
            val cell = this.size.width / n
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) {
                    drawRect(Mascot.color(0xFF0D0E11), topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
