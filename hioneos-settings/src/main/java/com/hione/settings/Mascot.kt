package com.hione.settings

// Mascot packs at runtime: names, colors and art follow the pack the user picked, live, with no
// reflash and no app restart.
//
// The same file (only the package line differs) lives in every HioneOS app module: app,
// hioneos-launcher, hioneos-systemui, hioneos-settings, hardware-settings, hioneos-tools,
// hioneos-media, hioneos-update, hioneos-riot, hioneos-wheel. Keep them identical. The launcher copy
// is the one to edit, then copy it over with hioneos/mascot/runtime/sync_mascot_kt.sh.
//
// Source of truth: Settings.Global hione_mascot_pack, a pack id. Unset or unknown means "hione".
// HioneSettings writes it (Theme & mascot page). To test from a computer:
//   adb shell settings put global hione_mascot_pack hione
//   adb shell settings put global hione_mascot_pack hione
//
// Packs:
//   hione, hione   built in. Names and palettes are compiled in below (MascotPackTest checks
//                 them against hioneos/mascot/packs/<id>/pack.json). Hione's art and voice clips
//                 ship inside Hione Update and are read through its provider.
//   anything else downloaded by Hione Update ("content packs" in the signed OTA manifest),
//                 unpacked into Hione Update's private storage and read through the same provider:
//                 content://com.hione.update.mascot (signature permission MASCOT_PACKS).
//
// Colors: the apps keep writing HioneOS hex values. Mascot.color(0xFF39C5BB) returns the active
// pack's replacement (alpha kept), the runtime twin of apply_pack.py's color table. It reads
// Compose state, so a composable that calls it recomposes when the pack changes.
// Text: Mascot.osName, musicAppName, name, fullName and friends, or Mascot.fill("{os_name}").
// Art: mascotPainter(R.drawable.x) and Mascot.decodeResource() swap a drawable for the pack file
// of the same resource name, when the pack has one.

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import org.json.JSONObject
import java.io.InputStream

/** The seven palette roles every pack defines. ARGB ints, so View code and Compose can share them. */
data class MascotPalette(
    val primary: Int,
    val primaryDeep: Int,
    val secondary: Int,
    val accent: Int,
    val dark: Int,
    val light: Int,
    val warn: Int,
) {
    fun role(name: String): Int? = when (name) {
        "primary" -> primary
        "primary_deep" -> primaryDeep
        "secondary" -> secondary
        "accent" -> accent
        "dark" -> dark
        "light" -> light
        "warn" -> warn
        else -> null
    }
}

/**
 * Recolors a family of shades the color table does not list: any color whose hue is in [hueFrom,
 * hueTo] (degrees), with saturation and lightness in range, takes the hue of palette role [to].
 * Lightness is kept, so a pale teal becomes a pale amber and a near-black teal a near-black grey.
 */
data class HueRule(
    val hueFrom: Float,
    val hueTo: Float,
    val minSat: Float,
    val minLight: Float,
    val maxLight: Float,
    val to: String,
)

/** One mascot pack. Field names match pack.json (snake_case there). */
data class MascotPack(
    val id: String,
    /** Short mascot name: "Hione". */
    val name: String,
    val nameJa: String,
    /** "Oki Hione", family name first. */
    val fullName: String,
    val fullNameJa: String,
    /** "HioneOS". */
    val osName: String,
    /** The music app's name: "Hione Music". */
    val musicAppName: String,
    val launcherName: String,
    val settingsName: String,
    val updateName: String,
    val edition: String,
    val bluetoothName: String,
    val palette: MascotPalette,
    /** HioneOS RGB (no alpha) to pack RGB. Empty for the Hione pack. */
    val colorMap: Map<Int, Int>,
    /** "hione" keeps the Hione-only lore text (leeks, Project DIVA, Mirai Stadium). */
    val lore: String,
    /** Which activity-alias set names the apps on the home screen: Hione, Hione or Plain. */
    val labelAlias: String,
    val version: Int = 1,
    /** builtin or download. */
    val source: String = "builtin",
    /** Shades outside [colorMap], recolored by hue family. Empty for the Hione pack. */
    val hueRules: List<HueRule> = emptyList(),
) {
    // Flat arrays for the per-draw color lookup: no boxing, no allocation.
    private val from = colorMap.keys.toIntArray()
    private val to = IntArray(from.size) { colorMap.getValue(from[it]) }
    // Hue-rule results, RGB to RGB, filled as colors are first seen. Open addressing, -1 = empty.
    private val memoKeys = IntArray(1024) { -1 }
    private val memoVals = IntArray(1024)

    /** [argb] with its RGB swapped through [colorMap] or [hueRules], alpha kept. Others pass through. */
    fun mapArgb(argb: Int): Int {
        val rgb = argb and 0xFFFFFF
        for (i in from.indices) if (from[i] == rgb) return (argb and 0xFF000000.toInt()) or to[i]
        if (hueRules.isEmpty()) return argb
        return (argb and 0xFF000000.toInt()) or memoRule(rgb)
    }

    private fun memoRule(rgb: Int): Int {
        synchronized(memoKeys) {
            var i = (rgb * -0x61c88647 ushr 22) and 1023
            repeat(1024) {
                val k = memoKeys[i]
                if (k == rgb) return memoVals[i]
                if (k == -1) {
                    val v = applyRules(rgb)
                    memoKeys[i] = rgb
                    memoVals[i] = v
                    return v
                }
                i = (i + 1) and 1023
            }
            return applyRules(rgb)
        }
    }

    /** The first matching hue rule applied to [rgb], or [rgb] unchanged. */
    fun applyRules(rgb: Int): Int {
        val hsl = rgbToHsl(rgb)
        for (r in hueRules) {
            val h = hsl[0]
            val inHue = if (r.hueFrom <= r.hueTo) h >= r.hueFrom && h <= r.hueTo else h >= r.hueFrom || h <= r.hueTo
            if (!inHue || hsl[1] < r.minSat || hsl[2] < r.minLight || hsl[2] > r.maxLight) continue
            val target = palette.role(r.to) ?: continue
            val t = rgbToHsl(target and 0xFFFFFF)
            return hslToRgb(t[0], minOf(hsl[1], t[1]), hsl[2])
        }
        return rgb
    }

    /** {os_name}, {music_app_name}, {mascot_name} and the rest, filled from this pack. */
    fun fill(template: String): String {
        if (template.indexOf('{') < 0) return template
        var s = template
        for ((k, v) in tokens()) s = s.replace("{$k}", v)
        return s
    }

    fun tokens(): List<Pair<String, String>> = listOf(
        "os_name" to osName,
        "music_app_name" to musicAppName,
        "mascot_name" to name,
        "mascot_name_ja" to nameJa,
        "full_name" to fullName,
        "full_name_ja" to fullNameJa,
        "launcher_name" to launcherName,
        "settings_name" to settingsName,
        "update_name" to updateName,
        "edition" to edition,
        "bluetooth_name" to bluetoothName,
    )

    companion object {
        /** Parse a pack.json. Missing names fall back to "<name> Music", "<os_name> Settings" and so on. */
        fun fromJson(o: JSONObject, source: String = "download"): MascotPack {
            val id = o.getString("id")
            val name = o.getString("name")
            val os = o.optString("os_name", "${name}OS")
            val p = o.getJSONObject("palette")
            fun col(k: String, fb: Int) = p.optString(k, "").let { if (it.isBlank()) fb else parseHex(it) }
            val primary = col("primary", 0xFF39C5BB.toInt())
            val palette = MascotPalette(
                primary = primary,
                primaryDeep = col("primary_deep", primary),
                secondary = col("secondary", 0xFF0E1A18.toInt()),
                accent = col("accent", 0xFFFF5FA2.toInt()),
                dark = col("dark", 0xFF0B1418.toInt()),
                light = col("light", 0xFFE0F7FA.toInt()),
                warn = col("warn", 0xFFFF5A4E.toInt()),
            )
            val map = HashMap<Int, Int>()
            o.optJSONObject("replaces")?.let { r ->
                for (k in r.keys()) {
                    val v = r.getString(k)
                    val target = palette.role(v) ?: parseHex(v)
                    map[parseHex(k) and 0xFFFFFF] = target and 0xFFFFFF
                }
            }
            return MascotPack(
                id = id,
                name = name,
                nameJa = o.optString("name_ja", name),
                fullName = o.optString("full_name", name),
                fullNameJa = o.optString("full_name_ja", o.optString("name_ja", name)),
                osName = os,
                musicAppName = o.optString("music_app_name", "$name Music"),
                launcherName = o.optString("launcher_name", "$os Launcher"),
                settingsName = o.optString("settings_name", "$os Settings"),
                updateName = o.optString("update_name", "$name Update"),
                edition = o.optString("edition", "$name edition"),
                bluetoothName = o.optString("bluetooth_name", "$name M500"),
                palette = palette,
                colorMap = map,
                lore = o.optString("lore", id),
                labelAlias = o.optString("label_alias", if (id == "hione") "Hione" else "Plain"),
                version = o.optInt("version", 1),
                source = source,
                hueRules = buildList {
                    val arr = o.optJSONArray("hue_rules") ?: return@buildList
                    for (i in 0 until arr.length()) {
                        val r = arr.getJSONObject(i)
                        val hue = r.getJSONArray("hue")
                        add(
                            HueRule(
                                hueFrom = hue.getDouble(0).toFloat(),
                                hueTo = hue.getDouble(1).toFloat(),
                                minSat = r.optDouble("min_sat", 0.0).toFloat(),
                                minLight = r.optDouble("min_light", 0.0).toFloat(),
                                maxLight = r.optDouble("max_light", 1.0).toFloat(),
                                to = r.getString("to"),
                            )
                        )
                    }
                },
            )
        }

        fun rgbToHsl(rgb: Int): FloatArray {
            val r = ((rgb shr 16) and 0xFF) / 255f
            val g = ((rgb shr 8) and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val l = (max + min) / 2f
            if (max == min) return floatArrayOf(0f, 0f, l)
            val d = max - min
            val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            var h = when (max) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h *= 60f
            return floatArrayOf(h, s, l)
        }

        fun hslToRgb(h: Float, s: Float, l: Float): Int {
            val c = (1f - Math.abs(2f * l - 1f)) * s
            val hp = ((h % 360f) + 360f) % 360f / 60f
            val x = c * (1f - Math.abs(hp % 2f - 1f))
            val (r1, g1, b1) = when (hp.toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            val m = l - c / 2f
            fun ch(v: Float) = Math.round((v + m) * 255f).coerceIn(0, 255)
            return (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
        }

        /** "#RRGGBB" or "#AARRGGBB" to ARGB (opaque when no alpha is given). */
        fun parseHex(s: String): Int {
            val h = s.trim().removePrefix("#")
            val v = h.toLong(16)
            return if (h.length <= 6) (0xFF000000 or v).toInt() else v.toInt()
        }
    }
}

/** The pack compiled into every app. MascotPackTest keeps it equal to packs/hione/pack.json. */
object MascotPacks {
    val HIONE = MascotPack(
        id = "hione",
        name = "Hione",
        nameJa = "ヒオネ",
        fullName = "Oki Hione",
        fullNameJa = "隠岐氷音",
        osName = "HioneOS",
        musicAppName = "Hione Hits",
        launcherName = "HioneOS Launcher",
        settingsName = "HioneOS Settings",
        updateName = "Hione Update",
        edition = "Hione edition",
        bluetoothName = "Hione M500",
        palette = MascotPalette(
            primary = 0xFFFFAB2E.toInt(), primaryDeep = 0xFF8F5300.toInt(), secondary = 0xFF2B2F38.toInt(),
            accent = 0xFF8EC5FF.toInt(), dark = 0xFF111216.toInt(), light = 0xFFF4EFE6.toInt(), warn = 0xFFFF5A4E.toInt(),
        ),
        colorMap = mapOf(
            0x39C5BB to 0xFFAB2E, // teal -> primary
            0xFF5FA2 to 0x8EC5FF, // pink -> accent
            0xFF2A85 to 0x8EC5FF, // hot pink -> accent
            0xFF5C8A to 0x8EC5FF, // launcher pink -> accent
            0x0B1418 to 0x111216, // dark
            0x00E5FF to 0xFFC56B, // cyber cyan -> light amber
            0xFF4081 to 0x6FB0FF, // neon pink -> light ice blue
            0xB388FF to 0xB9C2D0, // purple -> neutral
            0x7FE6DE to 0xFFD08A,
            0x00F5D4 to 0xFFC56B,
            0x9FF3EC to 0xFFE0B0,
            0x0E1A18 to 0x1B1C22,
            0x04161A to 0x121318,
            0x040D12 to 0x0C0D10,
            0x070B0D to 0x0D0E11,
            0x06090B to 0x0B0C0E,
            0xE0F7FA to 0xFFF3E0,
            0x80DEEA to 0xFFD59A,
        ),
        lore = "hione",
        labelAlias = "Hits",  // the alias with ic_launcher_hione and "Hione Hits" (0.4.4 called it MainActivityHione)
        hueRules = listOf(
            HueRule(160f, 192f, 0.25f, 0.2f, 1f, "primary"),   // teal and cyan shades -> amber
            HueRule(300f, 345f, 0.25f, 0.2f, 1f, "accent"),    // pinks -> ice blue
            HueRule(160f, 205f, 0.15f, 0f, 0.2f, "secondary"), // teal-blacks -> gunmetal
            HueRule(300f, 345f, 0.15f, 0f, 0.2f, "secondary"), // pink-blacks -> gunmetal
        ),
    )

    val BUILT_IN = listOf(HIONE)

    fun builtIn(id: String?): MascotPack? = BUILT_IN.firstOrNull { it.id == id }
}

object Mascot {
    /** Settings.Global key: the active pack id. */
    const val SETTING = "hione_mascot_pack"
    const val DEFAULT_ID = "hione"
    /** Hione Update's pack provider. */
    const val AUTHORITY = "com.hione.update.mascot"
    const val PERMISSION = "com.hione.permission.MASCOT_PACKS"

    /** Holidays and seasons (the launcher reads them, HioneSettings writes them). */
    const val HOLIDAY_SCOPE = "hione_holiday_scope"          // intl | local | custom | off
    const val HOLIDAY_CUSTOM = "hione_holiday_custom"        // comma-separated holiday ids
    const val HOLIDAY_REMINDERS = "hione_holiday_reminders"  // 1 on (default), 0 off
    const val HOLIDAY_ART = "hione_holiday_art"              // 1 on (default), 0 off

    /**
     * Launcher entries that show the OS or mascot name. Each has one activity-alias per label
     * set ("<activity>Hione", "<activity>Hione", "<activity>Plain"). The Hione alias is enabled in
     * the manifest, the others are disabled until a pack asks for them.
     */
    val ALIASES = listOf(
        "com.hione.player" to "com.hione.player.MainActivity",
        "com.hione.update" to "com.hione.update.ui.MainActivity",
        "com.hione.systemui" to "com.hione.systemui.HioneSystemUIActivity",
    )
    val ALIAS_SETS = listOf("Hione", "Hits", "Plain")

    private val state = mutableStateOf(MascotPacks.HIONE)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(MascotPack) -> Unit>()
    @Volatile private var app: Context? = null
    private var observer: ContentObserver? = null

    /** The active pack. Reading it inside a composable subscribes to pack changes. */
    val pack: MascotPack get() = state.value
    val id: String get() = pack.id
    val name: String get() = pack.name
    val nameUpper: String get() = pack.name.uppercase()
    val nameJa: String get() = pack.nameJa
    val fullName: String get() = pack.fullName
    val fullNameJa: String get() = pack.fullNameJa
    val osName: String get() = pack.osName
    val osNameUpper: String get() = pack.osName.uppercase()
    val musicAppName: String get() = pack.musicAppName
    val launcherName: String get() = pack.launcherName
    val settingsName: String get() = pack.settingsName
    val updateName: String get() = pack.updateName
    val edition: String get() = pack.edition
    val bluetoothName: String get() = pack.bluetoothName
    val isHione: Boolean get() = pack.id == "hione"
    /** True when Hione-only lore (leeks, Project DIVA, Mirai Stadium) should show. */
    val hasHioneLore: Boolean get() = pack.lore == "hione"

    val primary: Color get() = Color(pack.palette.primary)
    val primaryDeep: Color get() = Color(pack.palette.primaryDeep)
    val secondary: Color get() = Color(pack.palette.secondary)
    val accent: Color get() = Color(pack.palette.accent)
    val dark: Color get() = Color(pack.palette.dark)
    val light: Color get() = Color(pack.palette.light)
    val warn: Color get() = Color(pack.palette.warn)

    /** A HioneOS color literal mapped to the active pack, alpha kept. */
    fun color(argb: Long): Color = Color(pack.mapArgb(argb.toInt()))
    fun color(argb: Int): Color = Color(pack.mapArgb(argb))
    /** Same, for View code that works in ARGB ints. */
    fun argb(argb: Int): Int = pack.mapArgb(argb)
    fun argb(argb: Long): Int = pack.mapArgb(argb.toInt())

    /** Fill {os_name}, {music_app_name}, {mascot_name} and the rest from the active pack. */
    fun fill(template: String): String = pack.fill(template)

    /** Pick text by lore: [hione] under the Hione pack, [other] under every other pack. */
    fun lore(hione: String, other: String): String = if (hasHioneLore) hione else other

    /** Called by View code that does not recompose on its own. Runs on the main thread. */
    fun addListener(l: (MascotPack) -> Unit) { listeners += l }
    fun removeListener(l: (MascotPack) -> Unit) { listeners -= l }

    /** For tests and previews: make [p] the active pack without touching Settings. */
    fun setForTest(p: MascotPack) = apply(p)

    /**
     * Read the setting, start observing it, and put this app's own home-screen aliases in line.
     * Idempotent. MascotInit calls it at process start, so every process has the pack before
     * its first frame.
     */
    fun init(ctx: Context) {
        val a = ctx.applicationContext ?: ctx
        synchronized(this) {
            if (app != null) return
            app = a
        }
        reload(a)
        val h = Handler(Looper.getMainLooper())
        val obs = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) { reload(a) }
        }
        observer = obs
        runCatching { a.contentResolver.registerContentObserver(Settings.Global.getUriFor(SETTING), false, obs) }
        // A downloaded pack can be updated in place: Hione Update notifies this URI when it does.
        runCatching { a.contentResolver.registerContentObserver(Uri.parse("content://$AUTHORITY/packs"), true, obs) }
    }

    private fun reload(ctx: Context) {
        val id = runCatching { Settings.Global.getString(ctx.contentResolver, SETTING) }.getOrNull()
            ?.trim()?.lowercase()?.ifEmpty { null } ?: DEFAULT_ID
        val p = resolve(ctx, id) ?: MascotPacks.HIONE
        if (Looper.myLooper() == Looper.getMainLooper()) apply(p) else Handler(Looper.getMainLooper()).post { apply(p) }
        // The launcher and HioneSettings hold CHANGE_COMPONENT_ENABLED_STATE and put every app's
        // labels in line, so a pack set from adb switches the home screen too. Others do their own.
        val all = ctx.checkSelfPermission("android.permission.CHANGE_COMPONENT_ENABLED_STATE") ==
            PackageManager.PERMISSION_GRANTED
        Thread { syncAliases(ctx, p, onlyOwn = !all) }.apply { isDaemon = true }.start()
    }

    private fun apply(p: MascotPack) {
        if (p == state.value) return
        artCache.evictAll()
        missing.clear()
        state.value = p
        for (l in listeners) runCatching { l(p) }
    }

    /** A pack by id: built in, or from Hione Update's provider. Null when nobody has it. */
    fun resolve(ctx: Context, id: String): MascotPack? {
        MascotPacks.builtIn(id)?.let { return it }
        return installed(ctx).firstOrNull { it.id == id }
    }

    /** Every pack this device can switch to right now: the built-in ones, then downloaded ones. */
    fun available(ctx: Context): List<MascotPack> {
        val out = MascotPacks.BUILT_IN.toMutableList()
        for (p in installed(ctx)) if (out.none { it.id == p.id }) out += p
        return out
    }

    /** Packs Hione Update holds (downloaded ones; built-in ids it also lists are skipped by callers). */
    fun installed(ctx: Context): List<MascotPack> = runCatching {
        val out = ArrayList<MascotPack>()
        ctx.contentResolver.query(Uri.parse("content://$AUTHORITY/packs"), null, null, null, null)?.use { c ->
            val ij = c.getColumnIndex("json")
            val isrc = c.getColumnIndex("source")
            while (c.moveToNext()) {
                runCatching {
                    out += MascotPack.fromJson(JSONObject(c.getString(ij)), if (isrc >= 0) c.getString(isrc) else "download")
                }
            }
        }
        out
    }.getOrDefault(emptyList())

    /**
     * Make [id] the active pack: write the setting and switch the home-screen labels of every
     * HioneOS app. Needs WRITE_SECURE_SETTINGS (HioneSettings and the launcher hold it). Returns
     * false when the write was refused.
     */
    fun select(ctx: Context, id: String): Boolean {
        val ok = runCatching { Settings.Global.putString(ctx.contentResolver, SETTING, id) }.getOrDefault(false)
        if (ok) resolve(ctx, id)?.let { syncAliases(ctx, it, onlyOwn = false) }
        return ok
    }

    /**
     * Enable the alias set [p] asks for and disable the others. With [onlyOwn] it touches this
     * app's components only (any app may). Otherwise it needs CHANGE_COMPONENT_ENABLED_STATE,
     * which the platform-signed launcher and HioneSettings hold. The wanted alias is enabled
     * before the others are disabled, so an app never drops off the home screen.
     */
    fun syncAliases(ctx: Context, p: MascotPack, onlyOwn: Boolean) {
        val pm = ctx.packageManager
        val want = if (p.labelAlias in ALIAS_SETS) p.labelAlias else "Plain"
        for ((pkg, base) in ALIASES) {
            if (onlyOwn && pkg != ctx.packageName) continue
            val order = listOf(want) + ALIAS_SETS.filter { it != want }
            for (set in order) {
                val cn = ComponentName(pkg, base + set)
                val manifestOn = set == "Hione"
                val target = when {
                    set == want && manifestOn -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    set == want -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    manifestOn -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                }
                runCatching {
                    if (pm.getComponentEnabledSetting(cn) != target) {
                        pm.setComponentEnabledSetting(cn, target, PackageManager.DONT_KILL_APP)
                    }
                }
            }
        }
    }

    // ── Art ──────────────────────────────────────────────────────────────────────────────────

    private val artCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())
    private val names = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun entryName(res: Resources, id: Int): String? = names[id] ?: runCatching {
        res.getResourceEntryName(id)
    }.getOrNull()?.also { names[id] = it }

    /** The pack's file for a slot or resource name ("hione_wall_00", "avatar", "chibi"), or null. */
    fun openArt(ctx: Context, key: String, packId: String = id): InputStream? {
        if (packId == "hione") return null
        return runCatching {
            ctx.contentResolver.openInputStream(Uri.parse("content://$AUTHORITY/art/$packId/" + Uri.encode(key)))
        }.getOrNull()
    }

    /** Any file in a pack by its path inside the pack ("art/holidays/tanabata.webp"). */
    fun fileUri(path: String, packId: String = id): Uri =
        Uri.parse("content://$AUTHORITY/file/$packId/" + path.split('/').joinToString("/") { Uri.encode(it) })

    /** Decoded art for [key] in the active pack, cached. Null under Hione or when the pack has none. */
    fun artBitmap(ctx: Context, key: String): Bitmap? {
        val pid = id
        if (pid == "hione") return null
        val ck = "$pid/$key"
        artCache.get(ck)?.let { return it }
        if (ck in missing) return null
        val bmp = openArt(ctx, key, pid)?.use { BitmapFactory.decodeStream(it) }
        if (bmp == null) missing += ck else artCache.put(ck, bmp)
        return bmp
    }

    /** The pack's replacement for drawable [resId] (matched by resource name), or null. */
    fun artFor(ctx: Context, resId: Int): Bitmap? {
        if (id == "hione") return null
        val n = entryName(ctx.resources, resId) ?: return null
        return artBitmap(ctx, n)
    }

    /** BitmapFactory.decodeResource that honors the active pack. */
    fun decodeResource(res: Resources, resId: Int, opts: BitmapFactory.Options? = null): Bitmap? {
        val c = app
        if (c != null && id != "hione") {
            val n = entryName(res, resId)
            if (n != null) artBitmap(c, n)?.let { return it }
        }
        return if (opts != null) BitmapFactory.decodeResource(res, resId, opts) else BitmapFactory.decodeResource(res, resId)
    }

    /** A content URI for the pack's voice clip that replaces raw resource [rawName], or null. */
    fun voiceUri(ctx: Context, rawName: String): Uri? {
        if (id == "hione") return null
        val u = Uri.parse("content://$AUTHORITY/voice/$id/" + Uri.encode(rawName))
        return runCatching { ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { u } }.getOrNull()
    }
}

/** painterResource that swaps in the active pack's art for character drawables. */
@Composable
fun mascotPainter(id: Int): Painter {
    val pack = Mascot.pack
    if (pack.id != "hione") {
        val ctx = LocalContext.current
        val bmp = remember(pack.id, id) { Mascot.artFor(ctx, id) }
        if (bmp != null) return remember(bmp) { BitmapPainter(bmp.asImageBitmap()) }
    }
    return painterResource(id)
}

/**
 * Starts [Mascot] at process start, before Application.onCreate, in every process of the app.
 * Declared in each module's manifest with authority "${applicationId}.mascot-init", not exported.
 */
class MascotInit : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { runCatching { Mascot.init(it) } }
        return true
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
