package com.caf.fmradio

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/**
 * The bespoke Hione font kit (all SIL OFL). One frontrunner carries the brand wordmark; the rest
 * are reserved for roles to be designated (headers, numbers, JP flavor text, special screens).
 *
 *  - Audiowide     — FRONTRUNNER: techno-rounded stage-equipment lettering; the "Hione Music" wordmark.
 *  - Orbitron      — geometric sci-fi (variable weight): candidate for numbers/metrics.
 *  - Righteous     — clean rounded display: candidate for section headers.
 *  - Baloo 2       — chubby kawaii (variable weight): candidate for playful labels.
 *  - DotGothic16   — Japanese pixel/retro-game (full JP glyphs): candidate for VFD/console accents.
 *  - Mochiy Pop One — Japanese kawaii pop (full JP glyphs): candidate for Hione flavor text.
 *  - Monoton       — neon-sign inline display: candidate for splash/branding moments.
 */
val AudiowideFont = FontFamily(Font(R.font.audiowide))
val OrbitronFont = FontFamily(Font(R.font.orbitron))
val RighteousFont = FontFamily(Font(R.font.righteous))
val Baloo2Font = FontFamily(Font(R.font.baloo2))
val DotGothicFont = FontFamily(Font(R.font.dotgothic16))
val MochiyPopFont = FontFamily(Font(R.font.mochiypopone))
val MonotonFont = FontFamily(Font(R.font.monoton))
