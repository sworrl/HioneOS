package com.caf.fmradio

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Songs liked off the radio.
 *
 * A song identified on FM is usually one that is not in the library yet, so a like here is
 * really "I want this": it is kept with where and when it was heard (station, frequency, time,
 * position) and handed to Hione Music, which files it as a liked track the library does not have
 * and marks it for acquisition. Hione Music owns that list; this file keeps the radio's own copy
 * so the like survives if Hione Music is not running or not installed, and re-sends anything not
 * yet acknowledged.
 *
 * Contract with Hione Music: broadcast ACTION to package com.hione.player, sender holds
 * com.hione.permission.SYSTEM_BRIDGE (signature). Extras: id, title, artist, album, cover_url,
 * web_url, station (call or PS), freq_khz, heard_at_ms, lat, lon (Double, may be absent),
 * source ("fm-songid" or "fm-rds").
 */
object FmLikes {

    private const val TAG = "FmLikes"
    const val ACTION = "com.hione.player.action.FM_TRACK_LIKED"
    private const val PLAYER = "com.hione.player"
    private const val PERMISSION = "com.hione.permission.SYSTEM_BRIDGE"

    data class Like(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val coverUrl: String?,
        val webUrl: String?,
        val station: String,
        val freqKHz: Int,
        val heardAtMs: Long,
        val lat: Double?,
        val lon: Double?,
        val source: String,
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "fm_likes.json")

    @Synchronized
    fun all(ctx: Context): List<Like> = runCatching {
        val a = JSONArray(file(ctx).readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Like(o.getString("id"), o.getString("title"), o.getString("artist"), o.optString("album").ifBlank { null },
                o.optString("cover").ifBlank { null }, o.optString("web").ifBlank { null }, o.optString("station"),
                o.optInt("khz"), o.optLong("at"), if (o.has("lat")) o.getDouble("lat") else null,
                if (o.has("lon")) o.getDouble("lon") else null, o.optString("source", "fm-songid"))
        }
    }.getOrDefault(emptyList())

    fun isLiked(ctx: Context, title: String, artist: String): Boolean =
        all(ctx).any { it.title.equals(title, true) && it.artist.equals(artist, true) }

    @Synchronized
    private fun save(ctx: Context, list: List<Like>) {
        val a = JSONArray()
        list.forEach { l ->
            a.put(JSONObject().put("id", l.id).put("title", l.title).put("artist", l.artist).put("album", l.album ?: "")
                .put("cover", l.coverUrl ?: "").put("web", l.webUrl ?: "").put("station", l.station).put("khz", l.freqKHz)
                .put("at", l.heardAtMs).put("source", l.source).apply {
                    l.lat?.let { put("lat", it) }; l.lon?.let { put("lon", it) }
                })
        }
        file(ctx).writeText(a.toString())
    }

    /** Like (or unlike) a song heard now. Returns the new liked state. */
    fun toggle(ctx: Context, m: FmSongId.Match, station: String, source: String = "fm-songid"): Boolean {
        val app = ctx.applicationContext
        val cur = all(app)
        val existing = cur.firstOrNull { it.title.equals(m.title, true) && it.artist.equals(m.artist, true) }
        if (existing != null) {
            save(app, cur - existing)
            send(app, existing, unlike = true)
            return false
        }
        val pos = FmStationCatalogue.listenerPosition(app)
        val like = Like(
            id = java.util.UUID.randomUUID().toString(), title = m.title, artist = m.artist, album = m.album,
            coverUrl = m.coverUrl, webUrl = m.webUrl, station = station, freqKHz = m.freqKHz,
            heardAtMs = m.atMs, lat = pos?.first, lon = pos?.second, source = source,
        )
        save(app, cur + like)
        send(app, like, unlike = false)
        Log.i(TAG, "liked ${like.artist} - ${like.title} on ${like.station} ${like.freqKHz}")
        return true
    }

    private fun send(ctx: Context, l: Like, unlike: Boolean) {
        val i = Intent(ACTION).setPackage(PLAYER)
            .putExtra("id", l.id).putExtra("title", l.title).putExtra("artist", l.artist)
            .putExtra("album", l.album).putExtra("cover_url", l.coverUrl).putExtra("web_url", l.webUrl)
            .putExtra("station", l.station).putExtra("freq_khz", l.freqKHz).putExtra("heard_at_ms", l.heardAtMs)
            .putExtra("source", l.source).putExtra("unlike", unlike)
        l.lat?.let { i.putExtra("lat", it) }
        l.lon?.let { i.putExtra("lon", it) }
        runCatching { ctx.sendBroadcast(i, PERMISSION) }.onFailure { Log.w(TAG, "could not reach Hione Music: $it") }
    }

    /** Re-send every like, e.g. when Hione Music was reinstalled; Hione Music de-duplicates by id. */
    fun resendAll(ctx: Context) = all(ctx).forEach { send(ctx.applicationContext, it, unlike = false) }
}
