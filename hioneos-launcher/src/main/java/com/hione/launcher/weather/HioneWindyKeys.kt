package com.hione.launcher.weather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hione.launcher.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The user's Windy API keys, kept in the launcher's private storage in an EncryptedSharedPreferences
 * file whose AES-256-GCM master key lives in the Android Keystore (same pattern as the player's
 * HioneSecureStore). Never logged, never shown back in full.
 *
 *  - webcam: Windy Webcams API key (the widget's `windyApiKey`). Turns on the CAMS tab.
 *  - point:  Windy Point Forecast API key (the widget's `windyPointKey`). Turns on Windy GFS data.
 *  - map:    Windy Map Forecast API key (`windyMapKey`). Turns on Windy's own map layers in the MAP tab.
 *
 * Everything in the weather screen works with neither. The keys only add things on top.
 *
 * The tile's old plaintext key (prefs hione_weather_tile/windy_key) is moved in here on first load
 * and deleted. If the keystore cannot open the file it fails closed: no keys, never a plaintext fallback.
 */
object HioneWindyKeys {
    private const val TAG = "HioneWindyKeys"
    private const val FILE = "hione_windy_keys"
    private const val K_WEBCAM = "webcam_key"
    private const val K_POINT = "point_key"
    private const val K_MAP = "map_key"
    private const val LEGACY_PREFS = "hione_weather_tile"
    private const val LEGACY_KEY = "windy_key"

    data class Keys(
        val webcam: String = "",
        val point: String = "",
        val map: String = "",
        /** Point key came from the build (local.properties hione.windy.key), not from the user. */
        val pointFromBuild: Boolean = false,
        val loaded: Boolean = false,
        val storeOk: Boolean = true
    ) {
        val hasWebcam get() = webcam.isNotBlank()
        val hasPoint get() = point.isNotBlank()
        val hasMap get() = map.isNotBlank()
        val connected get() = hasWebcam || hasPoint || hasMap
    }

    private val _state = MutableStateFlow(Keys())
    val state: StateFlow<Keys> = _state

    @Volatile private var prefs: SharedPreferences? = null

    @Synchronized
    private fun store(ctx: Context): SharedPreferences? {
        prefs?.let { return it }
        val app = ctx.applicationContext
        val p = try {
            open(app)
        } catch (t: Throwable) {
            Log.w(TAG, "encrypted key file unreadable (${t.javaClass.simpleName}), recreating")
            runCatching { app.deleteSharedPreferences(FILE) }
            try { open(app) } catch (t2: Throwable) {
                Log.e(TAG, "encrypted key store unavailable, Windy extras stay off", t2)
                null
            }
        }
        prefs = p
        return p
    }

    private fun open(app: Context): SharedPreferences {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Idempotent. Does keystore work, so call it off the main thread where you can. */
    fun ensureLoaded(ctx: Context): Keys {
        if (_state.value.loaded) return _state.value
        synchronized(this) {
            if (_state.value.loaded) return _state.value
            val app = ctx.applicationContext
            val p = store(app)
            if (p != null) migrateLegacy(app, p)
            publish(p)
            return _state.value
        }
    }

    private fun migrateLegacy(app: Context, p: SharedPreferences) {
        runCatching {
            val legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val old = legacy.getString(LEGACY_KEY, null)
            if (legacy.contains(LEGACY_KEY)) {
                if (!old.isNullOrBlank() && p.getString(K_POINT, "").isNullOrBlank()) {
                    p.edit().putString(K_POINT, old.trim()).commit()
                    Log.i(TAG, "moved the tile's Windy key into the encrypted store")
                }
                legacy.edit().remove(LEGACY_KEY).commit()
            }
        }
    }

    private fun publish(p: SharedPreferences?) {
        val webcam = p?.getString(K_WEBCAM, "").orEmpty()
        val stored = p?.getString(K_POINT, "").orEmpty()
        val build = BuildConfig.HIONE_WINDY_KEY
        _state.value = Keys(
            webcam = webcam,
            map = p?.getString(K_MAP, "").orEmpty(),
            point = stored.ifBlank { build },
            pointFromBuild = stored.isBlank() && build.isNotBlank(),
            loaded = true,
            storeOk = p != null
        )
    }

    /** Plausible API key: letters, digits, dash, underscore, 8..128 chars. Blank = clear. */
    fun valid(k: String) = k.isBlank() || Regex("^[A-Za-z0-9_-]{8,128}$").matches(k.trim())

    /**
     * Save keys. null = leave that one alone, "" = clear it. Returns false when the store is not
     * available or a key does not look like a key. Callers refresh whatever depends on them.
     */
    fun set(ctx: Context, webcam: String?, point: String?, map: String? = null): Boolean {
        ensureLoaded(ctx)
        if (listOfNotNull(webcam, point, map).any { !valid(it) }) return false
        val p = store(ctx) ?: return false
        val e = p.edit()
        webcam?.let { if (it.isBlank()) e.remove(K_WEBCAM) else e.putString(K_WEBCAM, it.trim()) }
        point?.let { if (it.isBlank()) e.remove(K_POINT) else e.putString(K_POINT, it.trim()) }
        map?.let { if (it.isBlank()) e.remove(K_MAP) else e.putString(K_MAP, it.trim()) }
        val ok = e.commit()
        publish(p)
        return ok
    }

    /** "set" / "not set" / "build", for logs and adb replies. Never the key itself. */
    fun describe(k: Keys = _state.value): String =
        "webcam=${if (k.hasWebcam) "set" else "not set"} point=${when { k.pointFromBuild -> "build"; k.hasPoint -> "set"; else -> "not set" }} map=${if (k.hasMap) "set" else "not set"}"

    /** Masked form for the UI: last 4 characters only. */
    fun masked(k: String) = if (k.length <= 4) "set" else "...." + k.takeLast(4)

    /** Push a key change out to everything that uses them. */
    fun applied(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { HioneWeatherTileEngine.reloadWindyKey(app) }
        runCatching { HioneWeatherExtras.onKeysChanged(app) }
    }
}

/**
 * adb import for the Windy keys. The manifest guards it with android.permission.DUMP, which the
 * shell holds and other apps cannot get, the same gate as the ingest scan and location receivers.
 *
 *   adb shell am broadcast -p com.hione.launcher -a com.hione.launcher.action.WINDY_KEYS \
 *       --es webcam_key <KEY> --es point_key <KEY> --es map_key <KEY>
 *   adb shell am broadcast -p com.hione.launcher -a com.hione.launcher.action.WINDY_KEYS --ez clear true   (all three)
 *   adb shell am broadcast -p com.hione.launcher -a com.hione.launcher.action.WINDY_KEYS     (status only)
 *
 * Either extra can be left out to keep that key. The reply ("Broadcast completed: result=1,
 * data=...") and the log only say which keys are set, never what they are.
 * Result codes: 1 ok, 2 bad request, 3 failed.
 */
class HioneWindyKeyReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.hione.launcher.action.WINDY_KEYS"
        private const val TAG = "HioneWindyKeys"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Nothing here may throw: an exported receiver that throws takes the launcher down with it.
        try {
            if (intent.action != ACTION) return
            val app = context.applicationContext
            HioneWindyKeys.ensureLoaded(app)
            val clear = intent.getBooleanExtra("clear", false)
            val webcam = if (clear) "" else intent.getStringExtra("webcam_key")
            val point = if (clear) "" else intent.getStringExtra("point_key")
            val map = if (clear) "" else intent.getStringExtra("map_key")
            if (webcam == null && point == null && map == null) {
                resultCode = 1
                resultData = "ok ${HioneWindyKeys.describe()}"
                return
            }
            if (listOfNotNull(webcam, point, map).any { !HioneWindyKeys.valid(it) }) {
                resultCode = 2
                resultData = "that does not look like a Windy key (letters, digits, - and _ only)"
                Log.w(TAG, "import rejected: malformed key")
                return
            }
            if (!HioneWindyKeys.set(app, webcam, point, map)) {
                resultCode = 3
                resultData = "could not save: encrypted store unavailable"
                return
            }
            HioneWindyKeys.applied(app)
            val d = HioneWindyKeys.describe()
            Log.i(TAG, "keys imported over adb: $d")
            resultCode = 1
            resultData = "ok $d"
        } catch (t: Throwable) {
            Log.w(TAG, "import failed: ${t.javaClass.simpleName}")
            resultCode = 3
            resultData = "failed: ${t.javaClass.simpleName}"
        }
    }
}
