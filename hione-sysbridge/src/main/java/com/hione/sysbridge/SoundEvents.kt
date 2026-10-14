package com.hione.sysbridge

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * HioneOS system sounds: charger, battery, USB DAC, headphones, lock, unlock, screenshot, boot,
 * shutdown and volume keys. The files and the catalog come from the image
 * (/system/media/audio/ui/hione/index.json, written by tools/sounds/build_os_sounds.py). The
 * user's pick per category is Settings.Global hione_sound_<category>: an option id from the
 * catalog, "off", or unset for the catalog default. HioneSettings has the pickers.
 *
 * Playback is USAGE_ASSISTANCE_SONIFICATION, so it follows the system volume. Nothing plays
 * in silent or vibrate mode, with Do Not Disturb on, or during a call. Voice clips briefly duck
 * the music instead of mixing over it.
 *
 * So there is never a double sound, the stock version of each event is switched off while the
 * HioneOS one is active: lockscreen_sounds_enabled for lock and unlock, charging_sounds_enabled
 * for the charger, power_sounds_enabled for low battery, headset_connect_sound for headphones and
 * hiby_hione_sounds_enable (the launcher's boot jingle) for boot. The stock screenshot click
 * (camera_click.ogg) is replaced by a silent file in the image. HiBy's spoken prompts were
 * already silenced at build time. Picking "Off" for a category leaves its stock toggle alone.
 *
 * Boot: HiBy's boot animation plays its welcome voice when persist.sys.customanim.boot.sounds
 * is 1. The image sets it to 1 at early-init, but persistent properties load later (before
 * the boot animation starts), so the stored value is what the animation sees. The "anim"
 * choice keeps it at 1, anything else stores 0 and plays the chosen sound at BOOT_COMPLETED.
 */
object SoundEvents {
    private const val TAG = "HioneSounds"
    const val INDEX = "/system/media/audio/ui/hione/index.json"
    const val PREFIX = "hione_sound_"
    /** Master switch, Global, default on. */
    const val KEY_MASTER = "hione_sounds_enabled"
    const val PROP_BOOT_VOICE = "persist.sys.customanim.boot.sounds"

    val CATEGORIES = listOf(
        "charging_start", "charging_stop", "battery_full", "battery_low", "usb_connect", "usb_disconnect",
        "headphones_in", "headphones_out", "lock", "unlock", "screenshot", "boot", "shutdown", "volume_tick"
    )

    private class Option(val id: String, val path: String, val voice: Boolean)
    private class Category(val default: String, val options: Map<String, Option>)

    private var started = false
    private lateinit var app: Context
    private lateinit var handler: Handler
    private var catalog: Map<String, Category> = emptyMap()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    // Event state
    private var plugged = -1
    private var level = -1
    private var fullSaid = false
    private var startedAt = 0L
    private var lastPowerEvent = 0L
    private var lastTick = 0L
    private var devicesPrimed = false
    private val seenShots = ArrayDeque<Long>()

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        startedAt = SystemClock.elapsedRealtime()
        handler = Handler(HandlerThread("HioneSounds").apply { start() }.looper)
        handler.post {
            catalog = loadCatalog()
            Log.i(TAG, "catalog: ${catalog.size} categories")
            applyStockSwitches()
            registerAll()
        }
    }

    // ── Catalog and choice ────────────────────────────────────────────────
    private fun loadCatalog(): Map<String, Category> = runCatching {
        val root = JSONObject(File(INDEX).readText())
        val base = root.optString("device_base", "/system/media/audio")
        val cats = root.getJSONObject("categories")
        CATEGORIES.filter { cats.has(it) }.associateWith { cid ->
            val c = cats.getJSONObject(cid)
            val arr = c.getJSONArray("options")
            val opts = (0 until arr.length()).map { arr.getJSONObject(it) }.associate { o ->
                o.getString("id") to Option(o.getString("id"), "$base/${o.getString("device")}", o.optString("group") == "Voices")
            }
            Category(c.optString("default", "off"), opts)
        }
    }.onFailure { Log.w(TAG, "no sound catalog at $INDEX: $it") }.getOrDefault(emptyMap())

    private fun choice(cid: String): String =
        Settings.Global.getString(app.contentResolver, PREFIX + cid) ?: catalog[cid]?.default ?: "off"

    private fun active(cid: String): Boolean {
        val c = choice(cid)
        return c != "off" && c != "anim" && catalog[cid]?.options?.containsKey(c) == true
    }

    private fun masterOn(): Boolean =
        runCatching { Settings.Global.getInt(app.contentResolver, KEY_MASTER, 1) }.getOrDefault(1) != 0

    // ── Stock switches ────────────────────────────────────────────────────
    private fun applyStockSwitches() {
        val cr = app.contentResolver
        runCatching {
            if (active("lock") || active("unlock")) Settings.System.putInt(cr, "lockscreen_sounds_enabled", 0)
            if (active("charging_start") || active("charging_stop")) {
                Settings.Secure.putInt(cr, "charging_sounds_enabled", 0)
                Settings.Global.putInt(cr, "charging_sounds_enabled", 0)
            }
            if (active("battery_low")) Settings.Global.putInt(cr, "power_sounds_enabled", 0)
            if (active("headphones_in") || active("headphones_out")) Settings.Global.putInt(cr, "headset_connect_sound", 0)
            if (active("boot")) Settings.Global.putInt(cr, "hiby_hione_sounds_enable", 0)
            Settings.Global.putInt(cr, "dock_sounds_enabled", 0)   // the M500 has no dock
        }.onFailure { Log.w(TAG, "stock switches: $it") }
        val bootVoice = if (choice("boot") == "anim") "1" else "0"
        if (getProp(PROP_BOOT_VOICE) != bootVoice) setProp(PROP_BOOT_VOICE, bootVoice)
    }

    // ── Playback ──────────────────────────────────────────────────────────
    private fun allowedNow(): Boolean {
        val am = app.getSystemService(AudioManager::class.java) ?: return false
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (am.mode != AudioManager.MODE_NORMAL) return false
        val nm = app.getSystemService(NotificationManager::class.java)
        val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        return filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** Plays the chosen sound for [cid] (or [optionId]). [force] skips the silent and DND checks. */
    fun play(cid: String, force: Boolean = false, optionId: String? = null, onDone: (() -> Unit)? = null) {
        handler.post {
            var done = onDone
            try {
                if (!force && (!masterOn() || !allowedNow())) return@post
                val c = catalog[cid] ?: return@post
                var opt = c.options[optionId ?: choice(cid)] ?: return@post
                // A mascot pack with its own voice replaces the voice options, never the plain sounds.
                if (opt.voice && optionId == null) packClip(cid)?.let { opt = Option("pack", it, true) }
                if (!opt.path.startsWith("content://") && !File(opt.path).canRead()) { Log.w(TAG, "missing ${opt.path}"); return@post }
                startPlayer(opt, done)
                done = null
            } catch (t: Throwable) {
                Log.w(TAG, "play $cid: $t")
            } finally {
                done?.invoke()
            }
        }
    }

    private fun startPlayer(opt: Option, onDone: (() -> Unit)?) {
        stopPlayer()
        val am = app.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(if (opt.voice) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (opt.voice && am != null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs).setWillPauseWhenDucked(false).build()
            am.requestAudioFocus(focus!!)
        }
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(attrs)
        if (opt.path.startsWith("content://")) mp.setDataSource(app, Uri.parse(opt.path)) else mp.setDataSource(opt.path)
        mp.setOnCompletionListener { handler.post { if (player === mp) stopPlayer(); onDone?.invoke() } }
        mp.setOnErrorListener { _, w, e -> Log.w(TAG, "player error $w/$e"); handler.post { if (player === mp) stopPlayer(); onDone?.invoke() }; true }
        mp.prepare()
        mp.start()
    }

    // ── Mascot pack voice ────────────────────────────────────────────────
    // Settings.Global hione_mascot_pack names the active pack (see Mascot.kt in the app modules).
    // Its pack.json "sounds.clips" maps a category to a file in the pack, served by Hione Update's
    // provider. The Hione pack has no clips, so the catalog's own voice plays.
    private var clipsFor: String? = null
    private var clips: Map<String, String> = emptyMap()

    private fun packClip(cid: String): String? = runCatching {
        val id = Settings.Global.getString(app.contentResolver, "hione_mascot_pack")?.trim()?.lowercase()
        if (id.isNullOrEmpty() || id == "hione") return null
        if (clipsFor != id) {
            clips = emptyMap()
            app.contentResolver.query(Uri.parse("content://com.hione.update.mascot/packs"), null, null, null, null)?.use { c ->
                val ii = c.getColumnIndex("id")
                val ij = c.getColumnIndex("json")
                while (c.moveToNext()) {
                    if (c.getString(ii) != id) continue
                    val o = JSONObject(c.getString(ij)).optJSONObject("sounds")?.optJSONObject("clips") ?: continue
                    clips = o.keys().asSequence().associateWith { o.getString(it) }
                }
            }
            clipsFor = id
        }
        val path = clips[cid] ?: return null
        "content://com.hione.update.mascot/file/$id/" + path.split('/').joinToString("/") { Uri.encode(it) }
    }.onFailure { Log.w(TAG, "pack clip $cid: $it") }.getOrNull()

    private fun stopPlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        focus?.let { f -> app.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f) }
        focus = null
    }

    // ── Event sources ─────────────────────────────────────────────────────
    private fun settled() = SystemClock.elapsedRealtime() - startedAt > 4000

    private fun registerAll() {
        val power = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val now = SystemClock.elapsedRealtime()
                when (i.action) {
                    Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                        // USB cables flap on insert: one sound per second at most.
                        if (!settled() || now - lastPowerEvent < 1200) { lastPowerEvent = now; return }
                        lastPowerEvent = now
                        if (i.action == Intent.ACTION_POWER_CONNECTED) { fullSaid = false; play("charging_start") }
                        else play("charging_stop")
                    }
                    Intent.ACTION_BATTERY_CHANGED -> onBattery(i)
                    Intent.ACTION_SCREEN_OFF -> if (settled()) play("lock")
                    Intent.ACTION_USER_PRESENT -> if (settled()) play("unlock")
                    Intent.ACTION_BOOT_COMPLETED -> if (choice("boot") != "anim") play("boot")
                    "android.media.VOLUME_CHANGED_ACTION" -> onVolume(i)
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        app.registerReceiver(power, f, null, handler)

        // Shutdown: ShutdownThread waits for ordered receivers, so hold the broadcast until the
        // sound has finished (at most 3 s).
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!active("shutdown")) return
                val pending = goAsync()
                var finished = false
                val finish = { if (!finished) { finished = true; pending.finish() } }
                handler.postDelayed({ finish() }, 3000)
                play("shutdown") { handler.post { finish() } }
            }
        }, IntentFilter(Intent.ACTION_SHUTDOWN), null, handler)

        // Charge limit reached counts as full for the sound.
        app.contentResolver.registerContentObserver(Settings.Global.getUriFor("hione_charge_hold"), false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    val hold = runCatching { Settings.Global.getInt(app.contentResolver, "hione_charge_hold", 0) }.getOrDefault(0)
                    if (hold == 1 && plugged > 0 && !fullSaid && settled()) { fullSaid = true; play("battery_full") }
                }
            })
        // Any pick in HioneSettings re-applies the stock switches.
        val choiceObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = applyStockSwitches()
        }
        for (cid in CATEGORIES) app.contentResolver.registerContentObserver(Settings.Global.getUriFor(PREFIX + cid), false, choiceObserver)

        registerDevices()
        registerScreenshots()
    }

    private fun onBattery(i: Intent) {
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (l >= 0 && s > 0) l * 100 / s else -1
        val p = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val first = level < 0
        if (!first && settled()) {
            if (p > 0 && !fullSaid && (status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100) && level in 0..99) {
                fullSaid = true
                play("battery_full")
            }
            if (p == 0 && plugged == 0 && ((level > 15 && pct in 0..15) || (level > 5 && pct in 0..5))) play("battery_low")
        }
        if (first && p > 0 && (status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100)) fullSaid = true
        if (p == 0) fullSaid = false
        level = pct
        plugged = p
    }

    private fun onVolume(i: Intent) {
        if (!active("volume_tick")) return
        val stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
        val v = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
        val prev = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
        if (stream != AudioManager.STREAM_MUSIC || v == prev) return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        if (am.isMusicActive) return              // the music itself is the feedback
        val now = SystemClock.elapsedRealtime()
        if (now - lastTick < 60) return
        lastTick = now
        play("volume_tick")
    }

    private fun registerDevices() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val usb = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)
        val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            // Registering reports every device already present: those are not events.
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (!devicesPrimed || !settled()) return
                if (added.any { it.isSink && it.type in usb }) play("usb_connect")
                else if (added.any { it.isSink && it.type in wired }) play("headphones_in")
            }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (!devicesPrimed || !settled()) return
                if (removed.any { it.isSink && it.type in usb }) play("usb_disconnect")
                else if (removed.any { it.isSink && it.type in wired }) play("headphones_out")
            }
        }, handler)
        handler.postDelayed({ devicesPrimed = true }, 1500)
    }

    /** Screenshots land in MediaStore under .../Screenshots/ a moment after the capture. */
    private fun registerScreenshots() {
        val cr = app.contentResolver
        cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!active("screenshot") || uri == null) return
                val id = runCatching { uri.lastPathSegment?.toLong() }.getOrNull() ?: return
                if (id in seenShots) return
                runCatching {
                    cr.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { c ->
                        if (!c.moveToFirst()) return
                        val rel = c.getString(0) ?: ""
                        val added = c.getLong(1)
                        val pending = c.getInt(2)
                        if (!rel.contains("Screenshots", ignoreCase = true) || pending != 0) return
                        if (System.currentTimeMillis() / 1000 - added > 20) return
                        seenShots.addLast(id)
                        while (seenShots.size > 20) seenShots.removeFirst()
                        play("screenshot")
                    }
                }.onFailure { Log.w(TAG, "screenshot query: $it") }
            }
        })
    }

    // ── Properties ────────────────────────────────────────────────────────
    private fun getProp(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrDefault("")

    private fun setProp(key: String, value: String) {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
                .invoke(null, key, value)
        }.onFailure { Log.w(TAG, "could not set $key=$value: ${it.cause ?: it}") }
    }
}
