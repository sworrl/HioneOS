package com.hione.player.visualizer

import com.hione.player.Mascot
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hione.player.AudioCapture
import com.hione.player.IdleController
import com.hione.player.HionePowerGovernor
import com.hione.player.VisualizerMemoryGuard
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/*
 * ENGINE NOTE — how the two visualizer backends relate:
 *
 *   ProjectMVisualizer.kt  = the native libprojectM (Milkdrop .milk) engine, already built via the
 *                            NDK/CMake in this module. Gorgeous, but ~185 MB of native heap/GL on a
 *                            device that regularly sits at <100 MB free (VisualizerMemoryGuard).
 *   THIS FILE              = "Hione Shaders": pure GLES 2.0, zero native code, a handful of MB. Five
 *                            hand-written Milkdrop-inspired GLSL presets (ShaderPresets.kt) driven by
 *                            the SAME shared android.media.Visualizer capture (AudioCapture) and the
 *                            player's audioSessionId. It is the engine when the user picks it, when
 *                            libprojectM-native failed to load, and the safety net when GL init fails
 *                            (→ ShaderFallbackCanvas, plain 2D).
 *
 * A future engine (projectM 4 with a different preset pack, a Shadertoy-style importer, a Vulkan
 * path…) plugs in as another VizEngine value in NowPlaying.kt's StageVisualizer switch; nothing
 * here assumes it is the only GL surface in the app. No projectM/NDK changes are made by this file.
 */

/** Which backend is drawing the stage. Persisted by PlayerPreferences.saveVizEngine as its `key`. */
enum class VizEngine(val key: String, val title: String) {
    PROJECTM("projectm", "projectM"),
    SHADER("shader", "${Mascot.name} Shaders");

    companion object {
        fun fromKey(k: String): VizEngine = entries.firstOrNull { it.key == k } ?: PROJECTM
    }
}

/** Process-wide "did GL init fail" latch so we don't retry a broken driver every recomposition. */
object ShaderVizState {
    var glFailed by mutableStateOf(false)
        internal set
}

/**
 * Compose host for the GLES2 shader visualizer. Same lifecycle discipline as ProjectMVisualizerView:
 * explicit onPause/onResume tied to the Activity lifecycle (Compose never calls them for a
 * screen-off), rendering stopped whenever the screen is idle-dimmed, and a Canvas fallback when the
 * memory guard has tripped or GL init failed.
 */
@Composable
fun HioneShaderVisualizerView(
    sessionId: Int,
    preset: ShaderPreset,
    accent: Color,
    accent2: Color,
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        HionePowerGovernor.noteVisualizerVisible(true)
        onDispose { HionePowerGovernor.noteVisualizerVisible(false) }
    }
    if (!IdleController.screenActive) {
        Box(modifier.background(Mascot.color(0xFF0A2528)))
        return
    }
    if (ShaderVizState.glFailed || VisualizerMemoryGuard.suppressed) {
        ShaderFallbackCanvas(sessionId, accent, accent2, modifier)
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val viewRef = remember { mutableStateOf<HioneShaderGLSurfaceView?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewRef.value?.onPause()
                Lifecycle.Event.ON_RESUME -> viewRef.value?.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { ctx ->
            HioneShaderGLSurfaceView(ctx, sessionId).apply {
                setPreset(preset)
                setAccent(accent, accent2)
                viewRef.value = this
            }
        },
        update = { view ->
            view.setPreset(preset)
            view.setAccent(accent, accent2)
            view.setSessionId(sessionId)
        },
        onRelease = { view ->
            view.onPause()
            viewRef.value = null
        },
        modifier = modifier
    )
}

/**
 * Lightweight 2D fallback (no GL at all): mirrored spectrum bars from the shared capture, ~30 fps,
 * zero per-frame allocation (reads AudioCapture.fft in place; a counter state drives redraws).
 */
@Composable
fun ShaderFallbackCanvas(sessionId: Int, accent: Color, accent2: Color, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(sessionId) {
        runCatching { AudioCapture.ensure(sessionId) }
        while (true) {
            if (IdleController.screenActive) { frame++; delay(33) } else delay(500)
        }
    }
    val brush = remember(accent, accent2) { Brush.verticalGradient(listOf(accent2, accent, accent2)) }
    Canvas(modifier.background(Mascot.color(0xFF06171A))) {
        val tick = frame
        if (tick < 0) return@Canvas
        val fft = AudioCapture.fft
        val n = 48
        val gap = 2.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        val midY = size.height / 2f
        for (i in 0 until n) {
            val v = fft[(i * fft.size / n).coerceIn(0, fft.size - 1)]
            val h = (v * size.height * 0.9f).coerceAtLeast(2f)
            val x = i * (bw + gap)
            drawRoundRect(brush, topLeft = Offset(x, midY - h / 2f), size = Size(bw, h), cornerRadius = CornerRadius(bw / 2f, bw / 2f))
        }
    }
}

class HioneShaderGLSurfaceView(context: Context, sessionId: Int) : GLSurfaceView(context) {
    private val renderer = ShaderRenderer(sessionId)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setPreset(p: ShaderPreset) { renderer.requestPreset(p.ordinal) }
    fun setSessionId(id: Int) { renderer.sessionId = id }
    fun setAccent(a: Color, b: Color) {
        renderer.setAccent(a.red, a.green, a.blue, b.red, b.green, b.blue)
    }

    // Reduced render-buffer size, upscaled by the SurfaceView: 0.75x fullscreen (the Adreno 610
    // fills 360x600 comfortably at 60 fps with these presets), ~0.6x for the embedded mini stage
    // which shares the GPU with the animated UI around it. Output is soft/glowy, so it's invisible.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val longest = maxOf(w, h)
        val s = if (longest < 520) 0.6f else 0.75f
        holder.setFixedSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); onResume() }
    override fun onDetachedFromWindow() { onPause(); super.onDetachedFromWindow() }
}

private class ShaderRenderer(@Volatile var sessionId: Int) : GLSurfaceView.Renderer {
    @Volatile var preset = 0
    // Set when the preset changes so the next frame wipes both feedback buffers first — otherwise
    // the incoming preset spends several seconds dissolving the outgoing one's trails.
    @Volatile private var clearHistory = true
    fun requestPreset(i: Int) { if (i != preset) { preset = i; clearHistory = true } }
    @Volatile private var ar = 0.22f; @Volatile private var ag = 0.77f; @Volatile private var ab = 0.73f
    @Volatile private var br = 1.0f; @Volatile private var bg = 0.37f; @Volatile private var bb = 0.64f
    fun setAccent(r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float) { ar = r1; ag = g1; ab = b1; br = r2; bg = g2; bb = b2 }

    private class Prog(val id: Int, val aPos: Int, val uRes: Int, val uTime: Int, val uBass: Int, val uMid: Int, val uTreble: Int, val uBeat: Int, val uAccent: Int, val uAccent2: Int, val uAudio: Int, val uPrev: Int, val uAspect: Int)
    private val programs = arrayOfNulls<Prog>(ShaderPreset.entries.size)
    private val failed = BooleanArray(ShaderPreset.entries.size)
    private var vertexShader = 0
    private var texture = 0
    private var width = 1; private var height = 1

    // ── Milkdrop feedback buffers ────────────────────────────────────────────────────────────
    // The defining feature of a Milkdrop/projectM preset is not the shapes it draws, it is that
    // every frame is drawn ON TOP OF a warped, zoomed, faded copy of the PREVIOUS frame. That
    // feedback loop is what produces the trails, tunnels and smears the whole look rests on, and
    // it is exactly what the old stateless one-pass presets had no way to do.
    //
    // Two RGBA textures ping-pong: the preset renders into `fbo[cur]` while sampling `tex[1-cur]`
    // through the uPrev sampler, then the result is blitted to the screen. Kept at half the
    // surface resolution (fbTexW/H) because feedback is bandwidth-bound and the Adreno 610 has
    // very little of it — at this scale the softening actually helps the look.
    private val fbo = IntArray(2)
    private val fboTex = IntArray(2)
    private var fbW = 0; private var fbH = 0
    private var cur = 0
    private var blitProg = 0
    private var blitPos = 0
    private var blitTex = 0
    private var feedbackReady = false
    private val startNs = System.nanoTime()

    // Preallocated once: full-screen triangle + the 64x2 audio texture staging buffer.
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0)
    }
    private val audioBuf: ByteBuffer = ByteBuffer.allocateDirect(64 * 2).order(ByteOrder.nativeOrder())

    // Smoothed band energies + a bass-onset beat flash (0..1) — all scalar state, no allocation.
    private var sBass = 0f; private var sMid = 0f; private var sTreble = 0f
    private var bassAvg = 0f; private var beat = 0f
    private var lastFrameEndNs = 0L

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        if (s == 0) return 0
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            android.util.Log.w("HioneShaders", "shader compile failed: " + GLES20.glGetShaderInfoLog(s))
            GLES20.glDeleteShader(s); return 0
        }
        return s
    }

    private fun program(index: Int): Prog? {
        programs[index]?.let { return it }
        if (failed[index] || vertexShader == 0) return null
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, ShaderPreset.HEADER + ShaderPreset.entries[index].fragment)
        if (fs == 0) { failed[index] = true; return null }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertexShader)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(fs)
        if (ok[0] == 0) {
            android.util.Log.w("HioneShaders", "link failed: " + GLES20.glGetProgramInfoLog(p))
            GLES20.glDeleteProgram(p); failed[index] = true; return null
        }
        val prog = Prog(
            p,
            GLES20.glGetAttribLocation(p, "aPos"),
            GLES20.glGetUniformLocation(p, "uRes"), GLES20.glGetUniformLocation(p, "uTime"),
            GLES20.glGetUniformLocation(p, "uBass"), GLES20.glGetUniformLocation(p, "uMid"),
            GLES20.glGetUniformLocation(p, "uTreble"), GLES20.glGetUniformLocation(p, "uBeat"),
            GLES20.glGetUniformLocation(p, "uAccent"), GLES20.glGetUniformLocation(p, "uAccent2"),
            GLES20.glGetUniformLocation(p, "uAudio"),
            GLES20.glGetUniformLocation(p, "uPrev"), GLES20.glGetUniformLocation(p, "uAspect")
        )
        programs[index] = prog
        return prog
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // The EGL context may be brand new (first surface, or a lost context): rebuild everything.
        programs.fill(null); failed.fill(false)
        // A fresh EGL context invalidates every GL name we held, including the feedback pair.
        fboTex[0] = 0; fboTex[1] = 0; fbo[0] = 0; fbo[1] = 0
        feedbackReady = false; blitProg = 0; cur = 0; clearHistory = true
        vertexShader = compile(GLES20.GL_VERTEX_SHADER, ShaderPreset.VERTEX)
        if (vertexShader == 0) { markFailed(); return }
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        for (i in 0 until 128) audioBuf.put(i, (if (i < 64) 0 else 128).toByte())
        audioBuf.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 64, 2, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, audioBuf)
        GLES20.glClearColor(0.02f, 0.09f, 0.10f, 1f)
        // Warm the current preset now so the first frame isn't a compile stall.
        if (program(preset.coerceIn(0, programs.size - 1)) == null && programs.all { it == null } && failed.all { it }) markFailed()
    }

    private fun markFailed() {
        Handler(Looper.getMainLooper()).post { ShaderVizState.glFailed = true }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
        createFeedbackTargets(width / 2, height / 2)
    }

    /** (Re)allocate the ping-pong pair. Safe to call repeatedly; tears the old pair down first. */
    private fun createFeedbackTargets(w: Int, h: Int) {
        val tw = w.coerceAtLeast(1)
        val th = h.coerceAtLeast(1)
        if (feedbackReady && tw == fbW && th == fbH) return
        releaseFeedbackTargets()
        fbW = tw; fbH = th
        GLES20.glGenTextures(2, fboTex, 0)
        GLES20.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fbW, fbH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            // CLAMP_TO_EDGE, not REPEAT: a zoom-out warp that wraps drags the opposite edge of the
            // frame into shot, which reads as a glitch rather than a tunnel.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.w("HioneShaders", "feedback FBO incomplete - running without trails")
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                releaseFeedbackTargets()
                return
            }
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (blitProg == 0) blitProg = buildBlit()
        feedbackReady = blitProg != 0
    }

    private fun releaseFeedbackTargets() {
        if (fboTex[0] != 0 || fboTex[1] != 0) GLES20.glDeleteTextures(2, fboTex, 0)
        if (fbo[0] != 0 || fbo[1] != 0) GLES20.glDeleteFramebuffers(2, fbo, 0)
        fboTex[0] = 0; fboTex[1] = 0; fbo[0] = 0; fbo[1] = 0
        feedbackReady = false
    }

    /** Trivial texture-to-screen pass for the final blit out of the feedback buffer. */
    private fun buildBlit(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, ShaderPreset.VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, ShaderPreset.BLIT_FRAGMENT)
        if (vs == 0 || fs == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        if (ok[0] == 0) { GLES20.glDeleteProgram(p); return 0 }
        blitPos = GLES20.glGetAttribLocation(p, "aPos")
        blitTex = GLES20.glGetUniformLocation(p, "uTex")
        return p
    }

    override fun onDrawFrame(gl: GL10?) {
        // Honor the power governor's frame cap (30 when hot / SAVE / user-capped) exactly like projectM.
        val cap = HionePowerGovernor.visFpsCap
        if (cap in 1..59 && lastFrameEndNs != 0L) {
            val budgetNs = 1_000_000_000L / cap
            val waitNs = budgetNs - (System.nanoTime() - lastFrameEndNs)
            if (waitNs > 500_000L) runCatching { Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt()) }
        }
        AudioCapture.ensure(sessionId)

        // Pick a working program: the requested preset, else the first one that compiles.
        var idx = preset.coerceIn(0, programs.size - 1)
        var prog = program(idx)
        if (prog == null) {
            for (i in programs.indices) { prog = program(i); if (prog != null) { idx = i; break } }
        }
        if (prog == null) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (failed.all { it }) markFailed()
            lastFrameEndNs = System.nanoTime()
            return
        }

        // Audio → texture rows (no allocation: writes into the direct buffer in place).
        val fft = AudioCapture.fft; val wf = AudioCapture.waveform
        var mid = 0f
        for (i in 0 until 64) {
            val m = fft[i]
            audioBuf.put(i, (m * 255f).toInt().coerceIn(0, 255).toByte())
            audioBuf.put(64 + i, (wf[i] * 127f + 128f).toInt().coerceIn(0, 255).toByte())
            if (i in 10..35) mid += m
        }
        audioBuf.position(0)
        val bass = AudioCapture.bass; val treble = AudioCapture.treble
        mid = (mid / 26f).coerceIn(0f, 1.5f)
        sBass += (bass - sBass) * 0.45f
        sMid += (mid - sMid) * 0.35f
        sTreble += (treble - sTreble) * 0.35f
        // Beat: bass jumping well above its running average → flash, then decay.
        bassAvg += (bass - bassAvg) * 0.06f
        if (bass > bassAvg * 1.35f && bass > 0.12f) beat = 1f else beat *= 0.88f

        // ── Pass 1: the preset, rendered into the back feedback buffer ───────────────────────
        // It samples the FRONT buffer (last frame) through uPrev, so each preset's own warp+decay
        // is what builds the trails. Without a working FBO pair we fall straight through to the
        // screen and the presets still draw, just with no history to feed on.
        val useFeedback = feedbackReady
        if (useFeedback && clearHistory) {
            clearHistory = false
            for (i in 0..1) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
                GLES20.glViewport(0, 0, fbW, fbH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        val back = 1 - cur
        if (useFeedback) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[back])
            GLES20.glViewport(0, 0, fbW, fbH)
        }

        GLES20.glUseProgram(prog.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 64, 2, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, audioBuf)
        GLES20.glUniform1i(prog.uAudio, 0)
        if (prog.uPrev >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (useFeedback) fboTex[cur] else 0)
            GLES20.glUniform1i(prog.uPrev, 1)
        }
        val rw = if (useFeedback) fbW else width
        val rh = if (useFeedback) fbH else height
        GLES20.glUniform2f(prog.uRes, rw.toFloat(), rh.toFloat())
        if (prog.uAspect >= 0) GLES20.glUniform1f(prog.uAspect, rw.toFloat() / rh.toFloat().coerceAtLeast(1f))
        GLES20.glUniform1f(prog.uTime, ((System.nanoTime() - startNs) / 1_000_000_000.0).toFloat())
        GLES20.glUniform1f(prog.uBass, sBass)
        GLES20.glUniform1f(prog.uMid, sMid)
        GLES20.glUniform1f(prog.uTreble, sTreble)
        GLES20.glUniform1f(prog.uBeat, beat)
        GLES20.glUniform3f(prog.uAccent, ar, ag, ab)
        GLES20.glUniform3f(prog.uAccent2, br, bg, bb)
        GLES20.glEnableVertexAttribArray(prog.aPos)
        GLES20.glVertexAttribPointer(prog.aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glDisableVertexAttribArray(prog.aPos)

        // ── Pass 2: blit the finished frame to the screen, then swap ─────────────────────────
        if (useFeedback) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(blitProg)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[back])
            GLES20.glUniform1i(blitTex, 0)
            GLES20.glEnableVertexAttribArray(blitPos)
            GLES20.glVertexAttribPointer(blitPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            GLES20.glDisableVertexAttribArray(blitPos)
            cur = back
        }
        lastFrameEndNs = System.nanoTime()
    }
}
