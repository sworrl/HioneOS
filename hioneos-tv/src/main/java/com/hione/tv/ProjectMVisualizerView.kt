package com.hione.tv

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.hione.player.ProjectMNative
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Composable
fun ProjectMVisualizerView(
    client: HioneCastClient,
    modifier: Modifier = Modifier
) {
    if (!ProjectMNative.available) {
        Box(modifier = modifier.fillMaxSize().background(Color(0xFF03070C)))
        return
    }

    AndroidView(
        factory = { ctx ->
            ProjectMGLSurfaceView(ctx, client)
        },
        modifier = modifier.fillMaxSize()
    )
}

class ProjectMGLSurfaceView(
    context: Context,
    private val client: HioneCastClient
) : GLSurfaceView(context) {

    private val renderer: ProjectMRenderer

    companion object {
        @Volatile private var current: ProjectMGLSurfaceView? = null
    }

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        renderer = ProjectMRenderer(context.applicationContext, client)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        // TV 4K target: render at 1920x1080 or 1280x720 internal framebuffer
        // to maintain rock-solid 60 fps on Bravia Android TV Mali-G52 GPU.
        val maxDim = maxOf(w, h)
        val targetDim = if (maxDim > 1920) 1920 else maxDim
        val scale = targetDim.toFloat() / maxDim.toFloat()
        val targetW = (w * scale).toInt().coerceAtLeast(640)
        val targetH = (h * scale).toInt().coerceAtLeast(360)
        holder.setFixedSize(targetW, targetH)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronized(Companion) {
            val prev = current
            if (prev != null && prev !== this) runCatching { prev.onPause() }
            current = this
        }
        onResume()
    }

    override fun onDetachedFromWindow() {
        onPause()
        super.onDetachedFromWindow()
        synchronized(Companion) {
            if (current === this) current = null
        }
    }
}

private class ProjectMRenderer(
    private val appCtx: Context,
    private val client: HioneCastClient
) : GLSurfaceView.Renderer {

    private var startTime = System.currentTimeMillis()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        ProjectMNative.init()
        val path = ProjectMNative.ensurePresets(appCtx)
        if (!path.isNullOrEmpty()) {
            ProjectMNative.loadPresets(path)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        ProjectMNative.resize(w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0f
        ProjectMNative.drainActions()

        if (ProjectMNative.consumeSkip()) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        } else {
            ProjectMNative.feedAudio(client.rawFftBuffer, client.rawPcmBuffer)
            ProjectMNative.render(elapsed, 0, client.bassLevel, client.trebleLevel)
        }
    }
}
