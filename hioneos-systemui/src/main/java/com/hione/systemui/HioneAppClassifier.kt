package com.hione.systemui

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log

/**
 * Classifies foreground apps and activities to determine whether overlays (e.g. HioneDacBadge)
 * should duck and whether the idle brightness ladder (HioneIdleDim) should stand down.
 */
object HioneAppClassifier {
    private const val TAG = "HioneAppClassifier"

    private val gameCache = HashMap<String, Boolean>()
    private val videoCache = HashMap<String, Boolean>()
    private val fullscreenCache = HashMap<String, Boolean>()

    private val KNOWN_GAME_PKGS = setOf(
        "com.retroarch", "com.retroarch.aarch64", "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold",
        "org.dolphinemu.dolphinemu", "org.citra.citra_emu", "org.yuzu.yuzu_emu",
        "com.dsemu.drastic", "org.scummvm.scummvm", "net.kuribo64.takahashis.melonDS",
        "com.valvesoftware.steamlink", "com.mojang.minecraftpe"
    )

    private val KNOWN_VIDEO_PKGS = setOf(
        "com.google.android.youtube", "com.google.android.apps.youtube.mango",
        "org.schabi.newpipe", "org.videolan.vlc", "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient",
        "tv.twitch.android.app", "is.xyz.mpv", "com.brouken.player", "org.courville.nova",
        "video.player.videoplayer", "org.xbmc.kodi", "com.plexapp.android", "org.jellyfin.mobile",
        "com.github.anilbeesetti.nextplayer", "org.lineageos.jelly", "com.android.chrome"
    )

    fun isLandscape(ctx: Context): Boolean {
        return ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    fun isGame(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty() || isOwnSystemApp(pkg)) return false
        gameCache[pkg]?.let { return it }

        val lower = pkg.lowercase()
        if (lower in KNOWN_GAME_PKGS ||
            lower.contains("game") || lower.contains("retroarch") ||
            lower.contains("ppsspp") || lower.contains("dolphin") ||
            lower.contains("citra") || lower.contains("yuzu") ||
            lower.contains("melonds") || lower.contains("drastic") ||
            lower.contains("scummvm") || lower.contains("dosbox") ||
            lower.contains("mupen") || lower.contains("duckstation") ||
            lower.contains("aethersx2") || lower.contains("fba") ||
            lower.contains("mame") || lower.contains("steamlink") ||
            lower.contains("emulator")) {
            gameCache[pkg] = true
            return true
        }

        val res = runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            (ai.category == ApplicationInfo.CATEGORY_GAME) ||
                ((ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0)
        }.getOrDefault(false)

        gameCache[pkg] = res
        return res
    }

    fun isVideo(ctx: Context, pkg: String?, cls: String?): Boolean {
        if (pkg.isNullOrEmpty() || isOwnSystemApp(pkg)) return false
        val lowerPkg = pkg.lowercase()
        val lowerCls = cls?.lowercase() ?: ""

        if (lowerPkg in KNOWN_VIDEO_PKGS ||
            lowerPkg.contains("youtube") || lowerPkg.contains("newpipe") ||
            lowerPkg.contains("vlc") || lowerPkg.contains("netflix") ||
            lowerPkg.contains("plex") || lowerPkg.contains("jellyfin") ||
            lowerPkg.contains("kodi") || lowerPkg.contains("twitch") ||
            lowerPkg.contains("mxtech") || lowerPkg.contains("mpv") ||
            lowerPkg.contains("brouken") || lowerPkg.contains("videoplayer") ||
            lowerPkg.contains("primevideo") || lowerPkg.contains("disney") ||
            lowerPkg.contains("hulu") || lowerPkg.contains("smarttube") ||
            lowerPkg.contains("nova.video") || lowerPkg.contains("nextplayer")) {
            return true
        }

        if (lowerCls.contains("videoplayer") || lowerCls.contains("movie") ||
            lowerCls.contains("cinema") || lowerCls.contains("playeractivity") ||
            lowerCls.contains("exoplayer") || lowerCls.contains("videoview")) {
            return true
        }

        videoCache[pkg]?.let { return it }

        val res = runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            ai.category == ApplicationInfo.CATEGORY_VIDEO
        }.getOrDefault(false)

        videoCache[pkg] = res
        return res
    }

    fun isFullscreen(ctx: Context, pkg: String?, cls: String?): Boolean {
        if (pkg.isNullOrEmpty() || isOwnSystemApp(pkg)) return false
        if (isLandscape(ctx)) return true
        if (cls.isNullOrEmpty()) return false

        val key = "$pkg/$cls"
        fullscreenCache[key]?.let { return it }

        val res = runCatching {
            val cn = ComponentName(pkg, cls)
            val ai = ctx.packageManager.getActivityInfo(cn, PackageManager.GET_META_DATA)
            val orient = ai.screenOrientation
            val orientFullscreen = orient == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                orient == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
                orient == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE ||
                orient == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE

            val themeRes = ai.themeResource
            var themeFullscreen = false
            if (themeRes != 0) {
                val entryName = runCatching { ctx.resources.getResourceEntryName(themeRes).lowercase() }.getOrNull() ?: ""
                themeFullscreen = entryName.contains("fullscreen") || entryName.contains("notitlebar")
            }

            orientFullscreen || themeFullscreen
        }.getOrDefault(false)

        fullscreenCache[key] = res
        return res
    }

    /** Should duck the DAC pill over games, videos, or fullscreen apps. */
    fun shouldDuckDacPill(ctx: Context, pkg: String?, cls: String?): Boolean {
        if (pkg.isNullOrEmpty() || isOwnSystemApp(pkg)) return false
        return isGame(ctx, pkg) || isVideo(ctx, pkg, cls) || isFullscreen(ctx, pkg, cls) || isLandscape(ctx)
    }

    /** Should suppress the idle brightness dimming ladder. */
    fun shouldSuppressIdleDim(ctx: Context, pkg: String?, cls: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return isGame(ctx, pkg) || isVideo(ctx, pkg, cls) || isFullscreen(ctx, pkg, cls) || isLandscape(ctx)
    }

    private fun isOwnSystemApp(pkg: String): Boolean {
        return pkg == "com.hione.launcher" || pkg == "com.hione.settings" ||
            pkg == "com.hione.systemui" || pkg == "com.hione.sysbridge"
    }
}
