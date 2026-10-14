package com.hione.launcher.theme

import com.hione.launcher.Mascot
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * HioneOS Theming Engine — data model + runtime-selectable theme registry.
 *
 * This file is intentionally self-contained (pure data + a persisted StateFlow) and is NOT
 * wired into HioneLauncherActivity.kt. Wiring — reading [HioneThemeRegistry.selectedTheme],
 * applying [HioneTheme.wallpaperAsset] as the home/lock background, and recoloring UI chrome
 * from [HioneAccentPalette] — is a separate follow-up pass, done by whoever owns the activity.
 *
 * See docs/10_hione_theme_catalog.md for the full asset catalog, source-image inventory, and
 * per-theme suggested palettes this registry was seeded from.
 *
 * ### Asset resolution model
 * Themes deliberately do NOT hold compile-time `@DrawableRes Int` references, because the
 * underlying bitmaps (hione-assets/themes/<id>/wallpaper.*) are not yet copied into any
 * module's res/drawable-nodpi at the time this registry was authored. Instead each theme
 * carries the *drawable resource name it expects to exist* as a String, and callers resolve
 * it at runtime with [HioneThemeRegistry.resolveDrawableRes] (falls back to the Default
 * theme's drawable, then to 0 / "not found", if the name hasn't been wired into res/ yet).
 * This keeps the registry compilable today and forward-compatible once the real drawables
 * are added under names like `theme_default_wallpaper`, `theme_cyber_stage_wallpaper`, etc.
 */

/** Accent color set an applied theme pushes across launcher/lockscreen/settings chrome. */
data class HioneAccentPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color
)

/**
 * A single installable/selectable HioneOS visual theme.
 *
 * @param id stable machine id, persisted to SharedPreferences — never rename once shipped.
 * @param displayName human-readable name shown in the theme picker.
 * @param description one-line blurb for the picker UI.
 * @param wallpaperDrawableName expected res/drawable(-nodpi) resource name for the home
 *   wallpaper (see [HioneThemeRegistry.resolveDrawableRes]).
 * @param lockscreenDrawableName expected resource name for the lockscreen wallpaper. Many
 *   themes reuse the home wallpaper; pass the same name in that case.
 * @param accentPalette the recolor tokens this theme pushes when applied.
 * @param keyboardThemeRef optional id of a matching IME/keyboard theme (see
 *   docs/10_hione_theme_catalog.md §4 for AOSP LatinIME feasibility notes). Null = no matching
 *   keyboard skin exists yet.
 * @param hasRealArt false = the wallpaper is a stand-in/placeholder, not the art the theme is
 *   actually named after. UI should badge these as "preview" until real art lands. Every built-in
 *   theme now ships its own art; when wiring the picker, prefer deriving this from an actual
 *   resolveDrawableRes() hit rather than trusting this hand-maintained flag to stay accurate.
 */
data class HioneTheme(
    val id: String,
    val displayName: String,
    val description: String,
    val wallpaperDrawableName: String,
    val lockscreenDrawableName: String,
    val accentPalette: HioneAccentPalette,
    val keyboardThemeRef: String? = null,
    val hasRealArt: Boolean = true
)

/**
 * Built-in HioneOS theme catalog + the currently-selected theme as a persisted [StateFlow].
 *
 * Usage (once wired):
 * ```
 * HioneThemeRegistry.init(context)
 * val theme by HioneThemeRegistry.selectedTheme.collectAsState()
 * val wallpaperRes = HioneThemeRegistry.resolveDrawableRes(context, theme.wallpaperDrawableName)
 * ```
 */
object HioneThemeRegistry {

    private const val PREFS_NAME = "hioneos_theme_prefs"
    private const val KEY_SELECTED_THEME_ID = "selected_theme_id"

    const val DEFAULT_THEME_ID = "default"

    /** Default = the beach/ocean Hione wallpaper already shipping as R.drawable.hione_wallpaper
     * (720x1280 — the exact M500 panel resolution). This is the "classic" wallpaper-picker
     * entry that already exists in HioneLauncherActivity.kt's `wallpapers` list; this theme
     * entry just gives it a place in the theme system too. */
    val Default get() = HioneTheme(
        id = "default",
        displayName = "Default (Beach)",
        description = "${Mascot.name} on the shore at sunrise. This is the default.",
        wallpaperDrawableName = "hione_wallpaper", // already exists: R.drawable.hione_wallpaper
        lockscreenDrawableName = "hione_wallpaper",
        accentPalette = HioneAccentPalette(
            primary = Mascot.color(0xFFFFAB2E),   // Hione teal
            secondary = Mascot.color(0xFF7ED6E0), // sea-foam
            tertiary = Color(0xFFFF9E80),  // coral (her hair-tie / warm accents)
            background = Mascot.color(0xFFEAF6F6)
        ),
        keyboardThemeRef = null,
        hasRealArt = true
    )

    /** Dedicated art IS bundled (res/drawable-nodpi/theme_halloween_wallpaper.jpg), so hasRealArt is
     * true — it was left false with an "art not sourced yet" description long after the file landed,
     * which would have badged a real theme "coming soon" in the picker. */
    val Halloween get() = HioneTheme(
        id = "halloween",
        displayName = "Halloween",
        description = "Jack-o-lantern ${Mascot.name}, witch hat and candy colors.",
        wallpaperDrawableName = "theme_halloween_wallpaper", // present: res/drawable-nodpi/theme_halloween_wallpaper.jpg
        lockscreenDrawableName = "theme_halloween_wallpaper",
        accentPalette = HioneAccentPalette(
            primary = Color(0xFFFF7518),   // pumpkin orange
            secondary = Color(0xFF6A3FA0), // witch violet
            tertiary = Mascot.color(0xFFFFAB2E),  // keep a sliver of Hione teal so it still reads "Hione"
            background = Mascot.color(0xFF140A10)
        ),
        keyboardThemeRef = null,
        hasRealArt = true
    )

    /** Requested as "Hione eating ice cream on a beach". A dedicated render IS bundled now;
     * see res/drawable-nodpi/theme_beach_icecream_wallpaper.jpg. The old comment/description still
     * called it a stand-in that "reuses Default's beach art" with hasRealArt = false, which the
     * picker would have shown as a placeholder claim about art that actually exists. */
    val BeachIcecream get() = HioneTheme(
        id = "beach_icecream",
        displayName = "Beach (Ice Cream)",
        description = "${Mascot.name} with ice cream on the beach.",
        wallpaperDrawableName = "theme_beach_icecream_wallpaper", // present: res/drawable-nodpi/theme_beach_icecream_wallpaper.jpg
        lockscreenDrawableName = "theme_beach_icecream_wallpaper",
        accentPalette = HioneAccentPalette(
            primary = Mascot.color(0xFFFFAB2E),
            secondary = Color(0xFFFFB6C1), // strawberry ice-cream pink
            tertiary = Color(0xFFFFF3D6),  // vanilla cream
            background = Mascot.color(0xFFEAF6F6)
        ),
        keyboardThemeRef = null,
        hasRealArt = true
    )

    /** Real dedicated art: hione-assets/themes/cyber_stage/wallpaper.webp
     * (source: hione-player-kotlin app/hioneos-launcher res/drawable/hione_banner_cyber_stage.webp,
     * a.k.a. hione_renders/banners/banner_02_waifu_cyber_stage_16x9.jpg — Hione on a neon concert
     * stage holding the cassette-UI DAP). Picked as a bonus built-in theme because it already
     * has finished, on-brand art and a natural teal/magenta accent pair. */
    val CyberStage get() = HioneTheme(
        id = "cyber_stage",
        displayName = "Cyber Stage",
        description = "Neon concert-stage ${Mascot.name} with the cassette-deck DAP UI.",
        wallpaperDrawableName = "hione_banner_cyber_stage", // already exists in res/drawable
        lockscreenDrawableName = "hione_banner_cyber_stage",
        accentPalette = HioneAccentPalette(
            primary = Mascot.color(0xFFFFAB2E),
            secondary = Mascot.color(0xFFE03177), // neon magenta
            tertiary = Color(0xFF2F5DFF),  // stage laser blue
            background = Color(0xFF060B18)
        ),
        keyboardThemeRef = null,
        hasRealArt = true
    )

    /** Real dedicated art: hione-assets/themes/cozy_cafe/wallpaper.jpg
     * (source: hione_renders/09_waifu_cozy_cafe.jpg — Hione in a sweater in a warm vinyl/lo-fi
     * lounge, cassette-UI DAP in hand). Second bonus built-in theme; warm palette contrasts
     * nicely with Cyber Stage. */
    val CozyCafe get() = HioneTheme(
        id = "cozy_cafe",
        displayName = "Cozy Cafe",
        description = "Lo-fi vinyl lounge ${Mascot.name} in a knit sweater, mug of coffee in frame.",
        wallpaperDrawableName = "theme_cozy_cafe_wallpaper", // present: res/drawable-nodpi/theme_cozy_cafe_wallpaper.jpg
        lockscreenDrawableName = "theme_cozy_cafe_wallpaper",
        accentPalette = HioneAccentPalette(
            primary = Color(0xFFD89B4A),   // warm amber / tungsten bulb glow
            secondary = Mascot.color(0xFF4FB3AC), // muted teal
            tertiary = Color(0xFFF0DFC0),  // cream
            background = Color(0xFF2A1B12) // dark wood
        ),
        keyboardThemeRef = null,
        hasRealArt = true
    )

    /** All built-in themes, in picker display order. */
    val builtIns: List<HioneTheme> = listOf(Default, Halloween, BeachIcecream, CyberStage, CozyCafe)

    private val _selectedTheme = MutableStateFlow(Default)
    /** Currently-applied theme. Collect this as Compose state once wired into the UI. */
    val selectedTheme: StateFlow<HioneTheme> = _selectedTheme.asStateFlow()

    private var prefs: SharedPreferences? = null

    /** Call once (e.g. from Application.onCreate or the first Activity that needs it) to
     * restore the persisted theme selection. Safe to call multiple times. */
    fun init(context: Context) {
        val p = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }
        val savedId = p.getString(KEY_SELECTED_THEME_ID, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID
        _selectedTheme.value = byId(savedId) ?: Default
    }

    /** Look up a built-in theme by id, or null if unknown. */
    fun byId(id: String): HioneTheme? = builtIns.firstOrNull { it.id == id }

    /** Apply + persist a theme selection by id. No-ops if [themeId] is unknown. */
    fun selectTheme(context: Context, themeId: String) {
        val theme = byId(themeId) ?: return
        val p = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }
        p.edit().putString(KEY_SELECTED_THEME_ID, themeId).apply()
        _selectedTheme.value = theme
    }

    /**
     * Resolve a theme's drawable resource *name* to an actual `@DrawableRes` id at runtime,
     * via [android.content.res.Resources.getIdentifier]. Falls back to [Default]'s wallpaper
     * name, then to 0, if neither name has been added to res/drawable(-nodpi) yet — so callers
     * never crash on a theme whose art hasn't been wired in.
     *
     * getIdentifier() is a reflection-ish lookup and intentionally only used here, once per
     * theme switch — not in a hot path.
     */
    fun resolveDrawableRes(context: Context, drawableName: String): Int {
        val res = context.resources
        val pkg = context.packageName
        val direct = res.getIdentifier(drawableName, "drawable", pkg)
        if (direct != 0) return direct
        if (drawableName != Default.wallpaperDrawableName) {
            val fallback = res.getIdentifier(Default.wallpaperDrawableName, "drawable", pkg)
            if (fallback != 0) return fallback
        }
        return 0
    }
}
