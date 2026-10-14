package com.hione.media.gallery

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hione.media.ui.BareIconButton
import com.hione.media.ui.GlassIconButton
import com.hione.media.ui.HioneHeader
import com.hione.media.ui.HionePink
import com.hione.media.ui.HioneTeal
import com.hione.media.ui.HioneTheme
import com.hione.media.ui.glass
import com.hione.media.ui.hioneBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Crop for com.android.camera.action.CROP and a crop/rotate editor for ACTION_EDIT.
 *
 * CROP follows the extras Gallery2 understood, since that is what senders were written against:
 * aspectX/aspectY lock the ratio, outputX/outputY set the exact output size, EXTRA_OUTPUT is
 * written to (JPEG unless outputFormat says PNG), and return-data asks for a small bitmap in the
 * "data" extra. EDIT never touches the original: it saves a copy next to it.
 */
class CropActivity : ComponentActivity() {

    private val isEdit get() = intent.action == Intent.ACTION_EDIT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val src = intent.data
        if (src == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val ax = intent.getIntExtra("aspectX", 0)
        val ay = intent.getIntExtra("aspectY", 0)
        val aspect = if (ax > 0 && ay > 0) ax / ay.toFloat() else 0f
        setContent { HioneTheme { CropScreen(src, aspect) } }
    }

    @Composable
    private fun CropScreen(src: Uri, aspect: Float) {
        val scope = rememberCoroutineScope()
        var bmp by remember { mutableStateOf<Bitmap?>(null) }
        var crop by remember { mutableStateOf(Rect.Zero) }   // in bitmap pixels
        var busy by remember { mutableStateOf(false) }

        LaunchedEffect(src) {
            val b = MediaActions.decodeForDisplay(this@CropActivity, src, if (isEdit) 3072 else 2048, software = true)
            if (b == null) {
                Toast.makeText(this@CropActivity, "Could not open this picture", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_CANCELED); finish()
            } else {
                bmp = b; crop = initialRect(b, aspect)
            }
        }

        fun rotate(deg: Float) {
            val b = bmp ?: return
            val m = Matrix().apply { postRotate(deg) }
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            bmp = r; crop = initialRect(r, aspect)
        }

        Column(Modifier.fillMaxSize().hioneBackground().statusBarsPadding()) {
            HioneHeader(
                title = if (isEdit) "Edit" else "Crop",
                leading = { BareIconButton(Icons.Rounded.Close, "Cancel") { setResult(Activity.RESULT_CANCELED); finish() } },
                actions = {
                    BareIconButton(Icons.Rounded.Check, "Save", tint = HioneTeal, enabled = bmp != null && !busy) {
                        val b = bmp ?: return@BareIconButton
                        busy = true
                        scope.launch { finishWith(b, crop) }
                    }
                }
            )
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b == null || busy) CircularProgressIndicator(color = HioneTeal)
                else CropCanvas(b, crop, aspect) { crop = it }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Row(Modifier.glass(28.dp).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassIconButton(Icons.Rounded.RotateLeft, "Rotate left", enabled = bmp != null) { rotate(-90f) }
                    GlassIconButton(Icons.Rounded.RotateRight, "Rotate right", enabled = bmp != null) { rotate(90f) }
                }
            }
        }
    }

    private fun initialRect(b: Bitmap, aspect: Float): Rect {
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        if (aspect <= 0f) return Rect(0f, 0f, w, h)
        val (cw, ch) = if (w / h > aspect) h * aspect to h else w to w / aspect
        return Rect((w - cw) / 2f, (h - ch) / 2f, (w + cw) / 2f, (h + ch) / 2f)
    }

    private suspend fun finishWith(b: Bitmap, r: Rect) {
        val left = r.left.roundToInt().coerceIn(0, b.width - 1)
        val top = r.top.roundToInt().coerceIn(0, b.height - 1)
        val w = r.width.roundToInt().coerceIn(1, b.width - left)
        val h = r.height.roundToInt().coerceIn(1, b.height - top)
        var out = withContext(Dispatchers.Default) { Bitmap.createBitmap(b, left, top, w, h) }

        if (isEdit) {
            val path = originalFolder(intent.data!!)
            val name = "edit_" + System.currentTimeMillis() + ".jpg"
            val saved = MediaActions.saveBitmap(this, out, name, path)
            Toast.makeText(this, if (saved != null) "Saved a copy" else "Could not save", Toast.LENGTH_SHORT).show()
            setResult(if (saved != null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                saved?.let { Intent().setData(it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
            finish()
            return
        }

        val ox = intent.getIntExtra("outputX", 0)
        val oy = intent.getIntExtra("outputY", 0)
        if (ox > 0 && oy > 0) out = withContext(Dispatchers.Default) { Bitmap.createScaledBitmap(out, ox, oy, true) }

        val target: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        } else @Suppress("DEPRECATION") intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
        val png = intent.getStringExtra("outputFormat")?.contains("png", ignoreCase = true) == true

        when {
            target != null -> {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(target, "wt")?.use {
                            out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it)
                        } ?: false
                    }.getOrDefault(false)
                }
                setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, Intent().setData(target))
            }
            intent.getBooleanExtra("return-data", false) -> {
                // Binder transactions cap out around 1 MB, so the inline bitmap is kept small.
                val small = scaleDown(out, 512)
                setResult(Activity.RESULT_OK, Intent().putExtra("data", small))
            }
            else -> {
                val saved = MediaActions.saveBitmap(this, out, "crop_" + System.currentTimeMillis() + if (png) ".png" else ".jpg",
                    Environment.DIRECTORY_PICTURES + "/Cropped", png)
                setResult(if (saved != null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                    saved?.let { Intent().setData(it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
            }
        }
        finish()
    }

    private fun scaleDown(b: Bitmap, edge: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= edge) return b
        val f = edge / long.toFloat()
        return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt(), (b.height * f).roundToInt(), true)
    }

    /** Save edits beside the original when that folder is one MediaStore lets us write images to. */
    private suspend fun originalFolder(uri: Uri): String = withContext(Dispatchers.IO) {
        val rel = if (MediaActions.isMediaStore(uri)) runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() else null
        if (rel != null && (rel.startsWith("DCIM/") || rel.startsWith("Pictures/"))) rel
        else Environment.DIRECTORY_PICTURES + "/Edited"
    }
}

private enum class Handle { NONE, MOVE, TL, TR, BL, BR }

/** Draws the image, darkens outside the crop, and lets corners resize and the middle move it. */
@Composable
private fun CropCanvas(b: Bitmap, crop: Rect, aspect: Float, onChange: (Rect) -> Unit) {
    val image = remember(b) { b.asImageBitmap() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val touch = with(LocalDensity.current) { 32.dp.toPx() }
    val minSide = max(32f, min(b.width, b.height) * 0.05f)

    // Bitmap to screen mapping for a ContentScale.Fit image centred in the box.
    val s = if (box.width == 0) 1f else min(box.width / b.width.toFloat(), box.height / b.height.toFloat())
    val ox = (box.width - b.width * s) / 2f
    val oy = (box.height - b.height * s) / 2f
    fun toScreen(r: Rect) = Rect(ox + r.left * s, oy + r.top * s, ox + r.right * s, oy + r.bottom * s)

    // Pointer input outlives recompositions, so it reads the latest rect through this, and keeps
    // its own running copy during a drag (touch events can arrive faster than frames).
    val latest by rememberUpdatedState(crop)

    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }
            .pointerInput(b, aspect, box) {
                var handle = Handle.NONE
                var current = latest
                detectDragGestures(
                    onDragStart = { p ->
                        current = latest
                        val sr = toScreen(current)
                        fun near(a: Offset) = abs(p.x - a.x) < touch && abs(p.y - a.y) < touch
                        handle = when {
                            near(sr.topLeft) -> Handle.TL
                            near(sr.topRight) -> Handle.TR
                            near(sr.bottomLeft) -> Handle.BL
                            near(sr.bottomRight) -> Handle.BR
                            sr.contains(p) -> Handle.MOVE
                            else -> Handle.NONE
                        }
                    },
                    onDrag = { change, d ->
                        change.consume()
                        val dx = d.x / s
                        val dy = d.y / s
                        val r = current
                        val W = b.width.toFloat()
                        val H = b.height.toFloat()
                        val next = when (handle) {
                            Handle.NONE -> r
                            Handle.MOVE -> {
                                val nx = (r.left + dx).coerceIn(0f, W - r.width)
                                val ny = (r.top + dy).coerceIn(0f, H - r.height)
                                Rect(Offset(nx, ny), r.size)
                            }
                            else -> resize(r, handle, dx, dy, aspect, W, H, minSide)
                        }
                        current = next
                        onChange(next)
                    }
                )
            }
    ) {
        Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val sr = toScreen(crop)
            val hole = Path().apply { addRect(sr) }
            clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
            drawRect(HioneTeal, sr.topLeft, sr.size, style = Stroke(2.dp.toPx()))
            // Rule-of-thirds guides
            for (i in 1..2) {
                val x = sr.left + sr.width * i / 3f
                val y = sr.top + sr.height * i / 3f
                drawLine(Color.White.copy(alpha = 0.35f), Offset(x, sr.top), Offset(x, sr.bottom), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.35f), Offset(sr.left, y), Offset(sr.right, y), 1.dp.toPx())
            }
            val k = 10.dp.toPx()
            listOf(sr.topLeft, sr.topRight, sr.bottomLeft, sr.bottomRight).forEach {
                drawCircle(HionePink, k, it)
                drawCircle(Color.White, k, it, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

private fun resize(r: Rect, h: Handle, dx: Float, dy: Float, aspect: Float, W: Float, H: Float, minSide: Float): Rect {
    var l = r.left; var t = r.top; var rr = r.right; var b = r.bottom
    when (h) {
        Handle.TL -> { l += dx; t += dy }
        Handle.TR -> { rr += dx; t += dy }
        Handle.BL -> { l += dx; b += dy }
        Handle.BR -> { rr += dx; b += dy }
        else -> {}
    }
    l = l.coerceIn(0f, rr - minSide); rr = rr.coerceIn(l + minSide, W)
    t = t.coerceIn(0f, b - minSide); b = b.coerceIn(t + minSide, H)
    if (aspect > 0f) {
        // Width leads, height follows; anchored at the corner opposite the one being dragged.
        var w = rr - l
        var hh = w / aspect
        val maxH = if (h == Handle.TL || h == Handle.TR) r.bottom else H - r.top
        if (hh > maxH) { hh = maxH; w = hh * aspect }
        when (h) {
            Handle.TL -> { l = r.right - w; t = r.bottom - hh; rr = r.right; b = r.bottom }
            Handle.TR -> { rr = r.left + w; t = r.bottom - hh; l = r.left; b = r.bottom }
            Handle.BL -> { l = r.right - w; b = r.top + hh; rr = r.right; t = r.top }
            Handle.BR -> { rr = r.left + w; b = r.top + hh; l = r.left; t = r.top }
            else -> {}
        }
        if (l < 0f || rr > W || t < 0f || b > H) return r
    }
    return Rect(l, t, rr, b)
}
