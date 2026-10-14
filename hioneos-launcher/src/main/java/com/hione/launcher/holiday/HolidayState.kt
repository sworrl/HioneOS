package com.hione.launcher.holiday

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.hione.launcher.Mascot
import com.hione.launcher.MascotPack
import org.json.JSONObject
import java.io.InputStream
import java.time.LocalDate
import java.util.Locale

/**
 * The launcher's holiday and season art, and the glue to Android: the calendar from assets,
 * the user's scope from Settings.Global, the latitude from the weather fix, the active pack's
 * art slots, and the bitmaps the home screen and lockscreen draw.
 *
 * Where it shows:
 *  - Home wallpaper: holiday art in a holiday window, otherwise the season's art, but only while
 *    the user's wallpaper choice is the default one ("classic" or the Default theme) and no
 *    gallery picture is set. A wallpaper the user picked on purpose always stays.
 *  - Lockscreen: holiday art in a holiday window only. Outside one it keeps its own portrait
 *    (which the pack already swaps).
 *  - Settings.Global hione_holiday_art = 0 turns all of it off.
 *  - Framing: art with a focus point (WallpaperFocus) is cropped so her face lands in the gap the
 *    widgets leave. [homeFocus], [lockFocus] and [focusTable] carry the points for the draw code.
 */
object HolidayState {
    private const val TAG = "HioneHolidays"
    const val CALENDAR_ASSET = "holidays/calendar.json"
    /** Wallpaper ids that mean "the default": automatic art may replace them. */
    val DEFAULT_WALLPAPER_IDS = setOf("classic", "theme_default")

    /** What the engine picked for today. Null before the first refresh. */
    val active = mutableStateOf<ActiveArt?>(null)
    /** Home wallpaper override, null = draw the normal wallpaper. */
    val homeBitmap = mutableStateOf<ImageBitmap?>(null)
    /** Lockscreen backdrop override (holiday windows only), null = normal backdrop. */
    val lockBitmap = mutableStateOf<ImageBitmap?>(null)
    /** Face center in [homeBitmap], null = center crop. */
    val homeFocus = mutableStateOf<FocusPoint?>(null)
    /** Face center in [lockBitmap], null = center crop. */
    val lockFocus = mutableStateOf<FocusPoint?>(null)
    /** The active pack's focus points by art path and "drawable:<name>". Empty for Hione. */
    val focusTable = mutableStateOf<Map<String, FocusPoint>>(emptyMap())

    /** One refresh: the art pick, its bitmaps and their focus points. */
    private class Computed(
        val art: ActiveArt? = null,
        val home: Bitmap? = null,
        val lock: Bitmap? = null,
        val homeFocus: FocusPoint? = null,
        val lockFocus: FocusPoint? = null,
        val table: Map<String, FocusPoint> = emptyMap(),
    )

    @Volatile private var started = false
    @Volatile private var calendar: List<Holiday>? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Called after every refresh, on the main thread (for View code such as the live wallpaper). */
    fun addListener(l: () -> Unit) { listeners += l }

    /** Idempotent. Observes the holiday settings and the mascot pack, refreshes, arms the midnight alarm. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Mascot.init(app)
        if (started) return
        started = true
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { refresh(app) }
        }
        for (k in listOf(Mascot.HOLIDAY_SCOPE, Mascot.HOLIDAY_CUSTOM, Mascot.HOLIDAY_ART, Mascot.HOLIDAY_REMINDERS)) {
            runCatching { app.contentResolver.registerContentObserver(Settings.Global.getUriFor(k), false, obs) }
        }
        Mascot.addListener { refresh(app) }
        refresh(app)
        HolidayScheduler.scheduleNextMidnight(app)
    }

    /** Recompute on a background thread and publish the bitmaps. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val c = runCatching { compute(app) }
                .onFailure { Log.w(TAG, "refresh failed: $it") }
                .getOrElse { Computed(table = runCatching { focusTable(app, Mascot.pack) }.getOrDefault(emptyMap())) }
            main.post {
                active.value = c.art
                homeBitmap.value = c.home?.asImageBitmap()
                lockBitmap.value = c.lock?.asImageBitmap()
                homeFocus.value = c.homeFocus
                lockFocus.value = c.lockFocus
                focusTable.value = c.table
                for (l in listeners) runCatching { l() }
            }
        }.apply { isDaemon = true; name = "HioneHolidays" }.start()
    }

    /** Blocking: the home wallpaper bitmap for now, or null for the normal one. For the live wallpaper. */
    fun homeBitmapNow(ctx: Context): Bitmap? = runCatching { compute(ctx.applicationContext).home }.getOrNull()

    /** Blocking: [homeBitmapNow] with its focus point. */
    fun homeArtNow(ctx: Context): Pair<Bitmap, FocusPoint?>? = runCatching {
        compute(ctx.applicationContext).let { c -> c.home?.let { it to c.homeFocus } }
    }.getOrNull()

    private fun compute(ctx: Context): Computed {
        val pack = Mascot.pack
        val table = focusTable(ctx, pack)
        if (!artEnabled(ctx)) return Computed(table = table)
        val today = LocalDate.now()
        val lat = latitude(ctx)
        val slots = packSlots(ctx, pack)
        val maxDim = ctx.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }.coerceAtLeast(1280)
        var art = HolidayEngine.activeArt(
            today, calendar(ctx), scope(ctx), lat, pack.id, slots.first, slots.second, seasons(ctx),
        )
        var home = art.art?.let { loadArt(ctx, it, pack.id, maxDim) }
        if (home == null && art.kind == "holiday") {
            // Planned art that is not shipped yet: show the season instead.
            art = HolidayEngine.seasonArt(today, lat, pack.id, slots.first, slots.second, seasons(ctx))
            home = art.art?.let { loadArt(ctx, it, pack.id, maxDim) }
        }
        // Lockscreen: its own image when there is one, the holiday's home image in a holiday,
        // otherwise null (the lockscreen keeps its portrait).
        val lockOwn = art.lockArt?.let { loadArt(ctx, it, pack.id, maxDim) }
        val lock = lockOwn ?: if (art.kind == "holiday") home else null
        val homeFocus = home?.let { focusOf(ctx, table, art.art) }
        val lockFocus = when {
            lockOwn != null -> focusOf(ctx, table, art.lockArt)
            lock != null -> homeFocus
            else -> null
        }
        return Computed(art, home, lock, homeFocus, lockFocus, table)
    }

    /**
     * The focus for [ref], or null. A "drawable:" ref only has one when the pack really replaces
     * that drawable: otherwise the bitmap is the launcher's own Hione art, which the point is not for.
     */
    private fun focusOf(ctx: Context, table: Map<String, FocusPoint>, ref: String?): FocusPoint? {
        val p = WallpaperFocus.lookup(table, ref) ?: return null
        if (ref != null && ref.startsWith("drawable:")) {
            val id = ctx.resources.getIdentifier(ref.removePrefix("drawable:"), "drawable", ctx.packageName)
            if (id == 0 || Mascot.artFor(ctx, id) == null) return null
        }
        return p
    }

    // ── Inputs ──────────────────────────────────────────────────────────────────────────────

    fun calendar(ctx: Context): List<Holiday> {
        calendar?.let { return it }
        val text = calendarText(ctx)
        val list = runCatching { HolidayCalendar.parse(text ?: return emptyList<Holiday>().also { calendar = it }) }
            .onFailure { Log.w(TAG, "holiday calendar unreadable: $it") }.getOrDefault(emptyList())
        calendar = list
        return list
    }

    @Volatile private var seasonList: List<SeasonArt>? = null

    /** calendar.json's season art (drawn for HolidayCalendar.CALENDAR_ART_PACK). */
    fun seasons(ctx: Context): List<SeasonArt> {
        seasonList?.let { return it }
        val list = runCatching { HolidayCalendar.parseSeasons(calendarText(ctx) ?: "") }.getOrDefault(emptyList())
        seasonList = list
        return list
    }

    private fun calendarText(ctx: Context): String? = runCatching {
        ctx.assets.open(CALENDAR_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    }.onFailure { Log.i(TAG, "no holiday calendar in assets ($it), seasons only") }.getOrNull()

    fun artEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Mascot.HOLIDAY_ART, 1) }.getOrDefault(1) != 0

    fun remindersEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Mascot.HOLIDAY_REMINDERS, 1) }.getOrDefault(1) != 0

    fun scope(ctx: Context): HolidayScope {
        val cr = ctx.contentResolver
        val mode = runCatching { Settings.Global.getString(cr, Mascot.HOLIDAY_SCOPE) }.getOrNull()
            ?.trim()?.lowercase()?.ifEmpty { null } ?: "local"
        val custom = HolidayScope.parseCustom(runCatching { Settings.Global.getString(cr, Mascot.HOLIDAY_CUSTOM) }.getOrNull())
        return HolidayScope(mode, localRegions(ctx), custom)
    }

    /** Regions from the weather location's country, the SIM, and the locale, in that order. */
    fun localRegions(ctx: Context): Set<String> {
        val out = LinkedHashSet<String>()
        val p = ctx.getSharedPreferences("hione_weather", Context.MODE_PRIVATE)
        if (p.getBoolean("manual_loc_enabled", false)) {
            HolidayCalendar.normRegion(p.getString("manual_country", null))?.let { out += it }
        }
        HolidayCalendar.normRegion(p.getString("last_country", null))?.let { out += it }
        runCatching {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            HolidayCalendar.normRegion(tm?.simCountryIso)?.let { out += it }
            HolidayCalendar.normRegion(tm?.networkCountryIso)?.let { out += it }
        }
        HolidayCalendar.normRegion(Locale.getDefault().country)?.let { out += it }
        out += "intl"
        return out
    }

    /** The weather fix's latitude (manual location first), or null when none is saved. */
    fun latitude(ctx: Context): Double? {
        val p = ctx.getSharedPreferences("hione_weather", Context.MODE_PRIVATE)
        val key = if (p.getBoolean("manual_loc_enabled", false) && p.contains("manual_lat")) "manual_lat" else "last_lat"
        if (!p.contains(key)) return null
        val v = java.lang.Double.longBitsToDouble(p.getLong(key, 0L))
        return if (v == 0.0 || v.isNaN()) null else v
    }

    // ── Pack art slots ──────────────────────────────────────────────────────────────────────

    /** Wallpaper slots of packs/hione/pack.json, compiled in (the Hione pack is the tree itself).
     *  All the usual wallpaper for now: the Hione pack keeps its current look. Seasonal Hione art
     *  can go in these slots later. */
    val HIONE_SEASONS: Map<String, String?> = mapOf(
        "default" to "drawable:hione_wallpaper",
        "spring" to "drawable:hione_wallpaper",
        "summer" to "drawable:hione_wallpaper",
        "autumn" to "drawable:hione_wallpaper",
        "winter" to "drawable:hione_wallpaper",
    )

    /** Lockscreen slots of packs/hione/pack.json: none, the lockscreen keeps its portrait. */
    val HIONE_SEASON_LOCKS: Map<String, String?> = emptyMap()

    private val packSlotCache = HashMap<String, Pair<Map<String, String?>, Map<String, String?>>>()
    private val packFocusCache = HashMap<String, Map<String, FocusPoint>>()
    @Volatile private var calendarFocus: Map<String, FocusPoint>? = null

    /** art.wallpaper of the active pack (home), compiled in for Hione, from Hione Update's provider otherwise. */
    fun seasonArt(ctx: Context, pack: MascotPack): Map<String, String?> = packSlots(ctx, pack).first

    /** art.wallpaper and art.wallpaper_lock of [pack]. */
    fun packSlots(ctx: Context, pack: MascotPack): Pair<Map<String, String?>, Map<String, String?>> {
        if (pack.id == "hione") return HIONE_SEASONS to HIONE_SEASON_LOCKS
        val ck = "${pack.id}@${pack.version}"
        synchronized(packSlotCache) { packSlotCache[ck]?.let { return it } }
        var focus: Map<String, FocusPoint> = emptyMap()
        val slots = runCatching {
            ctx.contentResolver.openInputStream(Mascot.fileUri("pack.json", pack.id))?.use { s ->
                val o = JSONObject(s.readBytes().toString(Charsets.UTF_8))
                focus = runCatching { WallpaperFocus.parsePack(o) }.getOrDefault(emptyMap())
                parseSeasonSlots(o) to parseSlots(o, "wallpaper_lock")
            }
        }.getOrNull() ?: (mapOf<String, String?>("default" to "drawable:hione_wallpaper") to emptyMap())
        synchronized(packSlotCache) { packSlotCache[ck] = slots; packFocusCache[ck] = focus }
        return slots
    }

    /**
     * Focus points of [pack]: its pack.json art.focus, plus calendar.json's home_focus / lock_focus
     * when the calendar art is drawn for it. Empty for Hione, whose wallpapers keep the center crop.
     */
    fun focusTable(ctx: Context, pack: MascotPack): Map<String, FocusPoint> {
        if (pack.id == "hione") return emptyMap()
        val ck = "${pack.id}@${pack.version}"
        packSlots(ctx, pack)
        val own = synchronized(packSlotCache) { packFocusCache[ck] } ?: emptyMap()
        if (pack.id != HolidayCalendar.CALENDAR_ART_PACK) return own
        val cal = calendarFocus ?: runCatching { WallpaperFocus.parseCalendar(calendarText(ctx) ?: "") }
            .getOrDefault(emptyMap()).also { calendarFocus = it }
        return if (own.isEmpty()) cal else cal + own
    }

    fun parseSeasonSlots(o: JSONObject): Map<String, String?> {
        val out = parseSlots(o, "wallpaper").toMutableMap()
        if (out["default"] == null) out["default"] = "drawable:hione_wallpaper"
        return out
    }

    private fun parseSlots(o: JSONObject, key: String): Map<String, String?> {
        val w = o.optJSONObject("art")?.optJSONObject(key) ?: return emptyMap()
        val out = LinkedHashMap<String, String?>()
        for (k in w.keys()) out[if (k == "fall") "autumn" else k] = if (w.isNull(k)) null else w.optString(k).ifBlank { null }
        return out
    }

    // ── Loading ─────────────────────────────────────────────────────────────────────────────

    /**
     * Decode [ref]: "drawable:<name>" from the launcher's own resources (the pack's replacement of
     * that drawable when it has one), or a path inside pack [packId] through Hione Update's
     * provider. Sampled down so the long side is about [maxDim].
     */
    fun loadArt(ctx: Context, ref: String, packId: String, maxDim: Int): Bitmap? {
        if (ref.startsWith("drawable:")) {
            val id = ctx.resources.getIdentifier(ref.removePrefix("drawable:"), "drawable", ctx.packageName)
            if (id == 0) return null
            Mascot.artFor(ctx, id)?.let { return it }
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(ctx.resources, id, b)
            val o = BitmapFactory.Options().apply { inSampleSize = sample(b.outWidth, b.outHeight, maxDim) }
            return BitmapFactory.decodeResource(ctx.resources, id, o)
        }
        val uri = Mascot.fileUri(ref, packId)
        fun open(): InputStream? = runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, b) } ?: return null
        val o = BitmapFactory.Options().apply { inSampleSize = sample(b.outWidth, b.outHeight, maxDim) }
        return open()?.use { BitmapFactory.decodeStream(it, null, o) }
    }

    private fun sample(w: Int, h: Int, maxDim: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxDim) s *= 2
        return s
    }
}
