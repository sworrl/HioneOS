package com.hione.player

/**
 * Registry of every bundled Hione artwork, so no two UI slots ever show the same image.
 *
 * The bespoke HiBy x Hione collab art (pulled from this device's own firmware) each has a
 * dedicated job: the sprite is the header glyph, the wide background banner is the header
 * watermark, the official default cover is the widget fallback, the clock-widget preview is our
 * widget's picker preview, and the ten official wallpapers form the rotating pool for ambient
 * watermarks/backdrops. The `extra` set (online art, deduped) pads the rotating pools so
 * back-to-back picks stay fresh.
 */
object HioneArt {
    // Bespoke fixed-role art (device/collab-specific — always used, never doubled up):
    val sprite = R.drawable.hione_sprite            // header app glyph
    val bgWide = R.drawable.hione_bg_wide           // header banner watermark
    val cover = R.drawable.hione_cover              // widget / no-art fallback cover
    val audiophile = R.drawable.hione_audiophile_art // high-end cyberpunk audiophile DAP art
    val chibiHearts = R.drawable.hione_chibi_hearts // cute chibi mascot with rainbow heart
    val chibiDj = R.drawable.hione_pose_chibi_dj    // cute chibi DJ with turntables
    val poseVocal = R.drawable.hione_pose_vocal     // full body standing idol with microphone
    val poseHeadphones = R.drawable.hione_pose_headphones // playful winking headphone portrait
    val posePeace = R.drawable.hione_pose_peace     // full body concert double peace V-sign
    val cyberStage = R.drawable.hione_cyber_stage   // concert stage wallpaper & hero backdrop
    val bootSplash = R.drawable.hione_boot_splash
    val bootSplashPose2 = R.drawable.hione_boot_splash_pose2
    val djMegaphone = R.drawable.hione_dj_megaphone // announcer pose w/ megaphone + speaker, transparent bg — scan modal mascot
    val poseDance = R.drawable.hione_pose_dance     // headphone-gesture dance pose w/ audio visualizer bars, transparent bg
    val renderStageDj = R.drawable.hione_render_stage_dj // concert-stage DJ render, cassette in hand — unused now, kept for reuse elsewhere
    val bannerCyberStage = R.drawable.hione_banner_cyber_stage // true 16:9 concert banner — Home "Daily Highlight" banner
    val falconTechnixLogo = R.drawable.falcon_technix_logo // publisher mark — Settings "About" footer
    // clock_widget_hione_preview is referenced from XML as the widget picker preview image.

    /** Dedicated pool of standalone character illustrations of Oki Hione */
    val characters = listOf(
        R.drawable.hione_audiophile_art, R.drawable.hione_pose_vocal,
        R.drawable.hione_pose_headphones, R.drawable.hione_pose_peace,
        R.drawable.hione_pose_chibi_dj, R.drawable.hione_chibi_hearts,
        R.drawable.hione_boot_splash, R.drawable.hione_boot_splash_pose2,
        R.drawable.hione_cover, R.drawable.hione_sprite,
    )

    /** The official M500 wallpapers + newly bundled high-res artworks. */
    private val walls = listOf(
        R.drawable.hione_wall_00, R.drawable.hione_wall_01, R.drawable.hione_wall_02,
        R.drawable.hione_wall_03, R.drawable.hione_wall_04, R.drawable.hione_wall_05,
        R.drawable.hione_wall_06, R.drawable.hione_wall_07, R.drawable.hione_wall_08,
        R.drawable.hione_wall_09, R.drawable.hione_cyber_stage, R.drawable.hione_audiophile_art,
        R.drawable.hione_pose_peace, R.drawable.hione_pose_vocal, R.drawable.hione_pose_headphones,
    )
    private val extras = listOf(
        R.drawable.hione_extra_00, R.drawable.hione_extra_01, R.drawable.hione_extra_02,
        R.drawable.hione_extra_03, R.drawable.hione_extra_04, R.drawable.hione_extra_05,
        R.drawable.hione_extra_06, R.drawable.hione_extra_07, R.drawable.hione_extra_08,
        R.drawable.hione_extra_09, R.drawable.hione_extra_10, R.drawable.hione_extra_11,
        R.drawable.hione_extra_12, R.drawable.hione_extra_13, R.drawable.hione_extra_14,
        R.drawable.hione_extra_15, R.drawable.hione_extra_16, R.drawable.hione_extra_17,
        R.drawable.hione_chibi_hearts, R.drawable.hione_pose_chibi_dj,
        R.drawable.hione_boot_splash, R.drawable.hione_boot_splash_pose2,
    )

    /** Wallpaper of the day (Home backdrop) — official wallpapers only, cycles daily. */
    fun wallOfTheDay(): Int {
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        return walls[day % walls.size]
    }

    /**
     * Ambient art for a track-scoped slot (Now Playing watermark): deterministic per track, drawn
     * from walls + extras, and offset so it can never collide with today's Home backdrop.
     */
    fun forTrack(trackId: Long): Int {
        val pool = walls + extras
        val today = wallOfTheDay()
        val pick = pool[((trackId % pool.size).toInt() + pool.size) % pool.size]
        return if (pick == today) pool[(((trackId + 1) % pool.size).toInt() + pool.size) % pool.size] else pick
    }
}
