package com.hione.wheel

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Typefaces, loaded once: Pixel Operator (CC0) for the bitmap-font eras, PT Sans (OFL 1.1) for
 * 2005, Liberation Sans (OFL 1.1, Helvetica metrics) for 2007. License texts: assets/licenses.
 */
object Fonts {
    lateinit var pixel: Typeface
    lateinit var pixelBold: Typeface
    lateinit var pixel8: Typeface
    lateinit var sans: Typeface
    lateinit var sansBold: Typeface
    lateinit var helv: Typeface
    lateinit var helvBold: Typeface
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        pixel = ResourcesCompat.getFont(ctx, R.font.pixel_operator) ?: Typeface.MONOSPACE
        pixelBold = ResourcesCompat.getFont(ctx, R.font.pixel_operator_bold) ?: Typeface.DEFAULT_BOLD
        pixel8 = ResourcesCompat.getFont(ctx, R.font.pixel_operator8) ?: Typeface.MONOSPACE
        sans = ResourcesCompat.getFont(ctx, R.font.pt_sans) ?: Typeface.SANS_SERIF
        sansBold = ResourcesCompat.getFont(ctx, R.font.pt_sans_bold) ?: Typeface.DEFAULT_BOLD
        helv = ResourcesCompat.getFont(ctx, R.font.liberation_sans) ?: Typeface.SANS_SERIF
        helvBold = ResourcesCompat.getFont(ctx, R.font.liberation_sans_bold) ?: Typeface.DEFAULT_BOLD
        loaded = true
    }
}

/**
 * Everything that makes one era look like itself: native size, scale on the 720 px wide
 * screen, row metrics, fonts and colors. Colors are approximations picked from photos of the
 * originals (see docs/clickwheel-era-research.md); nothing is sampled from Apple artwork.
 */
class Style(
    val era: Era,
    val scale: Int,
    val titleH: Int,
    val rowH: Int,
    val aa: Boolean,
    val pixelFont: Boolean,
    val listFace: Typeface,
    val titleFace: Typeface,
    val bodyFace: Typeface,
    val smallFace: Typeface,
    val listPx: Float,
    val titlePx: Float,
    val bodyPx: Float,
    val smallPx: Float,
    val mono: Boolean,
    // Screen colors.
    val bg: Int,
    val fg: Int,
    val dim: Int,
    val titleTop: Int,
    val titleBot: Int,
    val titleFg: Int,
    val titleLine: Int,
    val selTop: Int,
    val selBot: Int,
    val selFg: Int,
    val barTop: Int,
    val barBot: Int,
    val barTrack: Int,
    val barBorder: Int,
    /** Mono screens swap to these with the light off; color screens darken instead. */
    val unlitBg: Int,
    val unlitFg: Int,
    val bootBg: Int,
    val bootLogo: Int,
    val logoRes: Int,
    // The case and wheel around the screen.
    /** 2007 moved the title to the left. */
    val titleLeft: Boolean,
    val caseTop: Int,
    val caseBot: Int,
    val bezel: Int,
    val wheel: Int,
    val wheelEdge: Int,
    val wheelLabel: Int,
    val center: Int,
    val centerEdge: Int,
) {
    val w get() = era.w
    val h get() = era.h
    val visibleRows get() = (h - titleH) / rowH
    val lineH get() = if (pixelFont) 14 else (bodyPx * 1.3f).toInt()
    val textLines get() = (h - titleH - 4) / lineH

    companion object {
        private val cache = HashMap<String, Style>()

        fun of(era: Era, prefs: WheelPrefs): Style {
            val key = "${era.id}/${prefs.contrast}/${prefs.color2004}"
            return cache.getOrPut(key) { build(era, prefs.contrast, prefs.color2004) }
        }

        private fun build(era: Era, contrast: Int, color2004: Boolean): Style = when (era) {
            Era.MONO_2001 -> {
                val ink = when (contrast) { 1 -> 0xFF4A4F57.toInt(); 3 -> 0xFF050608.toInt(); else -> 0xFF1B1E23.toInt() }
                val lit = 0xFFC4D5E2.toInt()
                Style(
                    era, scale = 4, titleH = 18, rowH = 18, aa = false, pixelFont = true,
                    listFace = Fonts.pixelBold, titleFace = Fonts.pixelBold, bodyFace = Fonts.pixel, smallFace = Fonts.pixel8,
                    listPx = 16f, titlePx = 16f, bodyPx = 16f, smallPx = 8f, mono = true,
                    bg = lit, fg = ink, dim = ink, titleTop = lit, titleBot = lit, titleFg = ink, titleLine = ink,
                    selTop = ink, selBot = ink, selFg = lit,
                    barTop = ink, barBot = ink, barTrack = lit, barBorder = ink,
                    unlitBg = 0xFFA4AA9C.toInt(), unlitFg = 0xFF23261F.toInt(),
                    bootBg = lit, bootLogo = ink, logoRes = R.drawable.skin_logo_2001,
                    titleLeft = false, caseTop = 0xFFF7F8F9.toInt(), caseBot = 0xFFDCDFE2.toInt(), bezel = 0xFF9EA4AA.toInt(),
                    wheel = 0xFFEDEFF1.toInt(), wheelEdge = 0xFFB8BDC2.toInt(), wheelLabel = 0xFF8E949A.toInt(),
                    center = 0xFFF8F9FA.toInt(), centerEdge = 0xFFB8BDC2.toInt(),
                )
            }
            Era.COLOR_2004 -> if (color2004) Style(
                era, scale = 3, titleH = 20, rowH = 22, aa = false, pixelFont = true,
                listFace = Fonts.pixelBold, titleFace = Fonts.pixelBold, bodyFace = Fonts.pixel, smallFace = Fonts.pixel8,
                listPx = 16f, titlePx = 16f, bodyPx = 16f, smallPx = 8f, mono = false,
                bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), dim = 0xFF5A6068.toInt(),
                titleTop = 0xFFFBFBFC.toInt(), titleBot = 0xFFC5CAD0.toInt(), titleFg = 0xFF1A1C1F.toInt(), titleLine = 0xFF7B828A.toInt(),
                selTop = 0xFF6FA6CF.toInt(), selBot = 0xFF2A6E9A.toInt(), selFg = 0xFFFFFFFF.toInt(),
                barTop = 0xFF8EC1F5.toInt(), barBot = 0xFF2A69D1.toInt(), barTrack = 0xFFE9ECEF.toInt(), barBorder = 0xFF6D747C.toInt(),
                unlitBg = 0, unlitFg = 0,
                bootBg = 0xFFFFFFFF.toInt(), bootLogo = 0xFF000000.toInt(), logoRes = R.drawable.skin_logo_2004,
                titleLeft = false, caseTop = 0xFFF9FAFB.toInt(), caseBot = 0xFFDEE1E4.toInt(), bezel = 0xFF8F959C.toInt(),
                wheel = 0xFFF3F4F6.toInt(), wheelEdge = 0xFFC3C8CD.toInt(), wheelLabel = 0xFFA4AAB0.toInt(),
                center = 0xFFFBFCFC.toInt(), centerEdge = 0xFFC9CED3.toInt(),
            ) else Style(
                era, scale = 3, titleH = 20, rowH = 22, aa = false, pixelFont = true,
                listFace = Fonts.pixelBold, titleFace = Fonts.pixelBold, bodyFace = Fonts.pixel, smallFace = Fonts.pixel8,
                listPx = 16f, titlePx = 16f, bodyPx = 16f, smallPx = 8f, mono = true,
                bg = 0xFFD5DBD8.toInt(), fg = 0xFF15181A.toInt(), dim = 0xFF4C5256.toInt(),
                titleTop = 0xFFE9EDEB.toInt(), titleBot = 0xFFB9C0BD.toInt(), titleFg = 0xFF15181A.toInt(), titleLine = 0xFF5D6466.toInt(),
                selTop = 0xFF5E6466.toInt(), selBot = 0xFF1E2224.toInt(), selFg = 0xFFE9EDEB.toInt(),
                barTop = 0xFF4C5256.toInt(), barBot = 0xFF15181A.toInt(), barTrack = 0xFFE3E7E5.toInt(), barBorder = 0xFF4C5256.toInt(),
                unlitBg = 0xFFA9AEA6.toInt(), unlitFg = 0xFF23261F.toInt(),
                bootBg = 0xFFD5DBD8.toInt(), bootLogo = 0xFF15181A.toInt(), logoRes = R.drawable.skin_logo_2004,
                titleLeft = false, caseTop = 0xFFF9FAFB.toInt(), caseBot = 0xFFDEE1E4.toInt(), bezel = 0xFF8F959C.toInt(),
                wheel = 0xFFF3F4F6.toInt(), wheelEdge = 0xFFC3C8CD.toInt(), wheelLabel = 0xFFA4AAB0.toInt(),
                center = 0xFFFBFCFC.toInt(), centerEdge = 0xFFC9CED3.toInt(),
            )
            Era.VIDEO_2005 -> Style(
                era, scale = 2, titleH = 22, rowH = 24, aa = true, pixelFont = false,
                listFace = Fonts.sansBold, titleFace = Fonts.sansBold, bodyFace = Fonts.sans, smallFace = Fonts.sans,
                listPx = 16f, titlePx = 15f, bodyPx = 15f, smallPx = 12f, mono = false,
                bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), dim = 0xFF6B7178.toInt(),
                titleTop = 0xFFFFFFFF.toInt(), titleBot = 0xFFCDD2D8.toInt(), titleFg = 0xFF15171A.toInt(), titleLine = 0xFF8A929B.toInt(),
                selTop = 0xFF6AA6E8.toInt(), selBot = 0xFF3F78CC.toInt(), selFg = 0xFFFFFFFF.toInt(),
                barTop = 0xFF8CC6E6.toInt(), barBot = 0xFF2F86BD.toInt(), barTrack = 0xFFA9BBCD.toInt(), barBorder = 0xFF8A929B.toInt(),
                unlitBg = 0, unlitFg = 0,
                bootBg = 0xFFFFFFFF.toInt(), bootLogo = 0xFF2B2D30.toInt(), logoRes = R.drawable.skin_logo_2005,
                titleLeft = false, caseTop = 0xFF2C2D30.toInt(), caseBot = 0xFF0C0C0E.toInt(), bezel = 0xFF000000.toInt(),
                wheel = 0xFF1A1B1D.toInt(), wheelEdge = 0xFF3A3C40.toInt(), wheelLabel = 0xFFB9BCC1.toInt(),
                center = 0xFF222326.toInt(), centerEdge = 0xFF44474C.toInt(),
            )
            Era.CLASSIC_2007 -> Style(
                era, scale = 2, titleH = 22, rowH = 24, aa = true, pixelFont = false,
                listFace = Fonts.helvBold, titleFace = Fonts.helvBold, bodyFace = Fonts.helv, smallFace = Fonts.helv,
                listPx = 15f, titlePx = 14f, bodyPx = 14f, smallPx = 11f, mono = false,
                bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(), dim = 0xFF676D74.toInt(),
                titleTop = 0xFFF3F4F6.toInt(), titleBot = 0xFFBCC2C9.toInt(), titleFg = 0xFF15171A.toInt(), titleLine = 0xFF7F8790.toInt(),
                selTop = 0xFF5BC8E9.toInt(), selBot = 0xFF3198E7.toInt(), selFg = 0xFFFFFFFF.toInt(),
                barTop = 0xFFA6D0F8.toInt(), barBot = 0xFF2167D8.toInt(), barTrack = 0xFFE2E6EA.toInt(), barBorder = 0xFF7F8790.toInt(),
                unlitBg = 0, unlitFg = 0,
                bootBg = 0xFF000000.toInt(), bootLogo = 0xFFFFFFFF.toInt(), logoRes = R.drawable.skin_logo_2007,
                titleLeft = true, caseTop = 0xFFE3E6E9.toInt(), caseBot = 0xFFA9AEB4.toInt(), bezel = 0xFF16171A.toInt(),
                wheel = 0xFFDDE0E3.toInt(), wheelEdge = 0xFF9CA2A8.toInt(), wheelLabel = 0xFF7C828A.toInt(),
                center = 0xFFE8EAEC.toInt(), centerEdge = 0xFFA4AAB0.toInt(),
            )
        }
    }
}
