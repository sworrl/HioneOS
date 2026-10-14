package com.hione.player.visualizer

/**
 * Milkdrop-style GLSL ES 2.0 presets for [HioneShaderVisualizerView].
 *
 * These follow the actual Milkdrop model rather than just borrowing the look. A Milkdrop preset is
 * two things bolted together:
 *
 *   1. a PER-PIXEL MOTION EQUATION that says, for each pixel, where in the PREVIOUS frame to read
 *      from — zoom, rotate, translate, warp — and how much to fade what it finds, and
 *   2. some new material (a waveform, a shape, a burst) drawn on top of that warped history.
 *
 * Run every frame, that loop is what makes tunnels tunnel and trails trail. The presets this file
 * replaced were single-pass and stateless: each frame was computed from scratch, so nothing could
 * ever streak, echo or flow, and the result read as a generic phone-app spectrum display instead
 * of a visualizer. [HioneShaderVisualizer] now renders into a ping-pong framebuffer pair and hands
 * the previous frame in as `uPrev`, which is what makes the model above possible.
 *
 * Uniforms available to every preset:
 *   uPrev    — the previous frame. THE important one; sample it through your warp.
 *   uAudio   — 64x2 LUMINANCE: row 0 = FFT 0..1 (use fft()), row 1 = waveform (use wave()).
 *   uBass / uMid / uTreble — smoothed band energies 0..~1.5.
 *   uBeat    — bass-onset flash 0..1, decaying.
 *   uAccent / uAccent2     — the Now Playing palette (album-art derived, Hione teal/pink fallback).
 *   uRes, uAspect, uTime.
 *
 * Budget: the Adreno 610 at half surface resolution. A feedback tap plus a handful of sin() and a
 * loop no deeper than 4 is affordable; anything with dependent branching or a long loop is not.
 */
enum class ShaderPreset(val title: String, val fragment: String) {

    /**
     * The classic Milkdrop tunnel, in Hione colours. Constant inward zoom with a rotation that
     * breathes on the bass, so everything drawn gets dragged toward the centre and spirals as it
     * goes. New material is one bright waveform ring per frame; the feedback does the rest.
     */
    HIONE_VORTEX("Hione Vortex", """
        void main() {
            vec2 uv = gl_FragCoord.xy / uRes;
            vec2 p = (uv - 0.5) * vec2(uAspect, 1.0);
            float r = length(p) + 1e-4;
            float a = atan(p.y, p.x);

            // Per-pixel motion: zoom in, and rotate harder toward the middle (a real Milkdrop
            // "rot" term is radius-dependent, which is what makes the spiral rather than a spin).
            float zoom = 1.0 - (0.020 + uBass * 0.030);
            float rot  = (0.012 + uBeat * 0.030) * (1.0 - smoothstep(0.0, 0.7, r));
            float ca = cos(rot), sa = sin(rot);
            vec2 q = mat2(ca, -sa, sa, ca) * p * zoom;
            vec2 src = q / vec2(uAspect, 1.0) + 0.5;

            // Decay: what is not re-lit this frame fades. Slightly faster on the treble so busy
            // passages stay legible instead of smearing into a wash.
            vec3 c = texture2D(uPrev, src).rgb * (0.955 - uTreble * 0.030);

            // New material: a waveform-displaced ring.
            float ang = a / 6.2831853 + 0.5;
            float w = wave(fract(ang * 2.0));
            float ringR = 0.30 + w * (0.045 + uBass * 0.075);
            float d = abs(r - ringR);
            float line = smoothstep(0.012, 0.0, d);
            vec3 col = mix(uAccent, uAccent2, fract(ang + uTime * 0.05));
            c += col * line * (0.85 + uMid * 0.6);

            // Centre spark on the beat, which the zoom then pulls outward into the tunnel.
            c += uAccent2 * uBeat * exp(-r * 9.0) * 0.5;

            gl_FragColor = vec4(min(c, vec3(1.0)), 1.0);
        }
    """),

    /**
     * Long horizontal flow with a vertical sway, so material streaks sideways in ribbons that
     * fold over each other. Named for the obvious reason: it behaves like hair in water.
     */
    TWINTAIL_FLOW("Twintail Flow", """
        void main() {
            vec2 uv = gl_FragCoord.xy / uRes;

            // Motion: drift left, with a per-row vertical wobble whose phase varies down the
            // screen. The varying phase is what stops it reading as one sheet sliding.
            float sway = sin(uv.y * 7.0 + uTime * 0.9) * (0.0016 + uMid * 0.0042);
            vec2 src = uv + vec2(0.0042 + uBass * 0.0055, sway);
            // A touch of zoom-out keeps the streaks from packing solid at the left edge.
            src = (src - 0.5) * 0.9975 + 0.5;

            vec3 c = texture2D(uPrev, src).rgb * 0.948;

            // New material: two waveform ribbons, one per twintail, mirrored about the middle.
            for (int i = 0; i < 2; i++) {
                float fi = float(i);
                float base = mix(0.34, 0.66, fi);
                float y = base + wave(fract(uv.x * 0.85 + fi * 0.5 + uTime * 0.06))
                                 * (0.050 + uBass * 0.085)
                        + sin(uv.x * 4.5 + uTime * 0.7 + fi * 3.14159) * 0.030;
                float d = abs(uv.y - y);
                float ribbon = smoothstep(0.016, 0.0, d) + exp(-d * 55.0) * 0.35;
                vec3 col = mix(uAccent, uAccent2, fi);
                c += col * ribbon * (0.55 + fft(uv.x * 0.8) * 1.3);
            }

            c += uAccent * uBeat * 0.05;
            gl_FragColor = vec4(min(c, vec3(1.0)), 1.0);
        }
    """),

    /**
     * Kaleidoscope. Folds the plane into wedges before sampling the history, which is how
     * Milkdrop presets get symmetry without drawing anything symmetrical — the feedback is what
     * is mirrored, so the pattern builds on itself. Wedge count steps with the music.
     */
    HATSUNE_BLOOM("Hatsune Bloom", """
        void main() {
            vec2 uv = gl_FragCoord.xy / uRes;
            vec2 p = (uv - 0.5) * vec2(uAspect, 1.0);
            float r = length(p) + 1e-4;
            float a = atan(p.y, p.x);

            // Fold into N wedges, then unfold — the mirror happens on the LOOKUP, so the trails
            // themselves come back symmetrical.
            float wedges = 6.0 + floor(uMid * 3.0) * 2.0;
            float seg = 6.2831853 / wedges;
            float fa = abs(mod(a, seg) - seg * 0.5);

            // Slow outward breathing plus a rotation drift.
            float zoom = 1.0 + 0.010 * sin(uTime * 0.45) - uBass * 0.018;
            float spin = uTime * 0.10;
            vec2 src = vec2(cos(fa + spin), sin(fa + spin)) * r * zoom;
            src = src / vec2(uAspect, 1.0) + 0.5;

            vec3 c = texture2D(uPrev, src).rgb * (0.938 + uBass * 0.022);

            // New material: petals — a radial band modulated by the spectrum along the wedge.
            float e = fft(fa / seg * 0.9);
            float petal = 0.18 + e * 0.30 + uBeat * 0.05;
            float d = abs(r - petal);
            float shape = smoothstep(0.020, 0.0, d);
            vec3 col = mix(uAccent2, uAccent, r * 2.2);
            c += col * shape * (0.9 + uMid * 0.7);

            // Core.
            c += uAccent * exp(-r * 16.0) * (0.22 + uBeat * 0.55);

            c *= smoothstep(1.15, 0.25, r);
            gl_FragColor = vec4(min(c, vec3(1.0)), 1.0);
        }
    """),

    /**
     * Vertical fall with almost no decay in the streak direction, which turns every lit pixel into
     * a long comet tail. Leek green against the palette, because it would be wrong not to.
     */
    NEGI_RAIN("Negi Rain", """
        float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
        void main() {
            vec2 uv = gl_FragCoord.xy / uRes;

            // Motion: straight down, fast, with a slight inward squeeze so the columns converge
            // and the field gains some depth instead of being a flat curtain.
            vec2 src = uv + vec2(0.0, 0.0125 + uBass * 0.0100);
            src.x = (src.x - 0.5) * 0.9990 + 0.5;

            // Long tails: decay gently, and bleed a little sideways so the streaks have body.
            vec3 c = texture2D(uPrev, src).rgb * 0.930;
            c += texture2D(uPrev, src + vec2(0.0016, 0.0)).rgb * 0.028;
            c += texture2D(uPrev, src - vec2(0.0016, 0.0)).rgb * 0.028;

            // New material: drop heads on a column grid, lit by that column's own FFT bin.
            float cols = 26.0;
            float cx = floor(uv.x * cols);
            float h = hash(vec2(cx, 1.0));
            float speed = 0.35 + h * 0.55 + uBass * 0.45;
            float head = fract(1.0 - (uTime * speed + h * 7.0));
            float e = fft(cx / cols * 0.9);
            float dy = abs(uv.y - head);
            float dx = abs(fract(uv.x * cols) - 0.5);
            float drop = smoothstep(0.020, 0.0, dy) * smoothstep(0.42, 0.10, dx);

            // Leek: white-green at the head, deepening into the accent down the tail.
            vec3 negi = mix(vec3(0.82, 1.0, 0.72), vec3(0.16, 0.72, 0.30), h);
            vec3 col = mix(negi, uAccent, 0.35);
            c += col * drop * (0.35 + e * 1.9);
            c += vec3(1.0) * drop * e * 0.30;   // hot head

            c += uAccent2 * uBeat * 0.035;
            gl_FragColor = vec4(min(c, vec3(1.0)), 1.0);
        }
    """),

    /**
     * The dual-warp preset: two counter-rotating samples of the previous frame blended together,
     * which is how a lot of the better Milkdrop presets get their liquid, marbled motion. Heavier
     * than the others (two feedback taps) but still inside budget at half resolution.
     */
    VOCALOID_CIRCUIT("Vocaloid Circuit", """
        void main() {
            vec2 uv = gl_FragCoord.xy / uRes;
            vec2 p = (uv - 0.5) * vec2(uAspect, 1.0);
            float r = length(p) + 1e-4;

            // Two opposed warps. One winds in clockwise, the other out anticlockwise; mixing them
            // shears the history against itself and that shear is the whole effect.
            float t = uTime * 0.25;
            float s1 =  0.016 + uMid * 0.020;
            float s2 = -0.011 - uTreble * 0.016;
            float c1 = cos(s1), n1 = sin(s1);
            float c2 = cos(s2), n2 = sin(s2);
            vec2 q1 = mat2(c1, -n1, n1, c1) * p * (1.0 - 0.012 - uBass * 0.014);
            vec2 q2 = mat2(c2, -n2, n2, c2) * p * (1.0 + 0.009);
            vec2 u1 = q1 / vec2(uAspect, 1.0) + 0.5;
            vec2 u2 = q2 / vec2(uAspect, 1.0) + 0.5;

            float blend = 0.5 + 0.5 * sin(t + r * 3.0);
            vec3 c = mix(texture2D(uPrev, u1).rgb, texture2D(uPrev, u2).rgb, blend) * 0.944;

            // New material: an orthogonal trace grid that lights where the spectrum is loud —
            // circuit routing rather than a spectrum plot.
            float gx = abs(fract(uv.x * 14.0 + sin(uv.y * 3.0 + t) * 0.2) - 0.5);
            float gy = abs(fract(uv.y * 14.0 + sin(uv.x * 3.0 - t) * 0.2) - 0.5);
            float trace = smoothstep(0.47, 0.50, max(gx, gy));
            float e = fft(fract(uv.x * 0.7 + uv.y * 0.3));
            c += mix(uAccent, uAccent2, uv.y) * trace * e * 1.25;

            // Solder-point flashes on the beat.
            float node = smoothstep(0.46, 0.50, gx) * smoothstep(0.46, 0.50, gy);
            c += vec3(1.0) * node * uBeat * 0.55;

            gl_FragColor = vec4(min(c, vec3(1.0)), 1.0);
        }
    """);

    companion object {
        const val VERTEX = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        /** Final pass: the finished feedback buffer straight to the screen. */
        const val BLIT_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uRes;
            void main() { gl_FragColor = texture2D(uTex, gl_FragCoord.xy / uRes); }
        """

        /** Shared header prepended to every preset's fragment source. */
        const val HEADER = """
            precision mediump float;
            uniform vec2 uRes;
            uniform float uAspect;
            uniform float uTime;
            uniform float uBass;
            uniform float uMid;
            uniform float uTreble;
            uniform float uBeat;
            uniform vec3 uAccent;
            uniform vec3 uAccent2;
            uniform sampler2D uAudio;
            uniform sampler2D uPrev;
            float fft(float x) { return texture2D(uAudio, vec2(clamp(x, 0.0, 1.0), 0.25)).r; }
            float wave(float x) { return texture2D(uAudio, vec2(clamp(x, 0.0, 1.0), 0.75)).r * 2.0 - 1.0; }
        """
    }
}
