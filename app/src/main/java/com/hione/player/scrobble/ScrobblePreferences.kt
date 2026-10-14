package com.hione.player.scrobble

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hione.player.BuildConfig

/**
 * Scrobbling switches, user-entered Last.fm API credentials, and the live status line.
 *
 * Two stores:
 *  - An ENCRYPTED prefs file (same Jetpack Security setup as LastFmPreferences, hardware-backed
 *    master key) for the user's own API key + shared secret. The secret signs every request, so it
 *    gets the same at-rest protection as the session key.
 *  - A plain prefs file for the on/off toggle and the status fields (last scrobble time, last
 *    error, counters) — none of which are sensitive, and which the settings card reads on every
 *    recomposition.
 *
 * Credential precedence: user-entered key/secret win; if both are blank we fall back to the
 * build-time values from local.properties (BuildConfig), which are "" on a build that never had
 * them. [LastFmCredentials] is the single resolver every Last.fm call goes through.
 */
object ScrobblePreferences {
    private const val SECURE_FILE = "lastfm_scrobble_secure"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_API_SECRET = "api_secret"

    private const val FILE = "hione_scrobble_prefs"
    private const val KEY_ENABLED = "scrobble_enabled"
    private const val KEY_LAST_SCROBBLE_AT = "last_scrobble_at"
    private const val KEY_LAST_NOW_PLAYING_AT = "last_now_playing_at"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_ERROR_AT = "last_error_at"
    private const val KEY_ACCEPTED_TOTAL = "accepted_total"
    private const val KEY_IGNORED_TOTAL = "ignored_total"
    private const val KEY_HARD_ERROR = "hard_error"
    private const val KEY_PROMPT_SHOWN_AT = "setup_prompt_shown_at"
    private const val KEY_PROMPT_SNOOZED_UNTIL = "setup_prompt_snoozed_until"

    /**
     * The one-time "do you want to scrobble?" offer.
     *
     * Asked ONCE, and only after the user has actually listened to something, so it is an offer
     * rather than a cold-start nag. "Remind me" snoozes seven days; anything else and it never
     * appears again on its own. The settings card is always there either way, so declining costs
     * the user nothing.
     */
    fun shouldOfferSetup(ctx: Context): Boolean {
        if (LastFmCredentials.isConfigured && isConnected(ctx)) return false
        val p = plain(ctx)
        val snoozed = p.getLong(KEY_PROMPT_SNOOZED_UNTIL, 0L)
        if (snoozed > System.currentTimeMillis()) return false
        if (p.getLong(KEY_PROMPT_SHOWN_AT, 0L) > 0L && snoozed == 0L) return false
        return true
    }

    fun markSetupOffered(ctx: Context) {
        plain(ctx).edit()
            .putLong(KEY_PROMPT_SHOWN_AT, System.currentTimeMillis())
            .putLong(KEY_PROMPT_SNOOZED_UNTIL, 0L)
            .apply()
    }

    fun snoozeSetupOffer(ctx: Context, days: Int = 7) {
        plain(ctx).edit()
            .putLong(KEY_PROMPT_SHOWN_AT, System.currentTimeMillis())
            .putLong(KEY_PROMPT_SNOOZED_UNTIL, System.currentTimeMillis() + days * 86_400_000L)
            .apply()
    }

    private fun isConnected(ctx: Context): Boolean =
        runCatching { com.hione.player.LastFmPreferences.loadSessionKey(ctx) != null }.getOrDefault(false)

    @Volatile private var appCtx: Context? = null
    @Volatile private var secure: SharedPreferences? = null

    /** Remember an application context so context-free callers (LastFm.setLoved from LikeStore)
     *  can still resolve user credentials. Idempotent; call from any entry point. */
    fun init(ctx: Context) { if (appCtx == null) appCtx = ctx.applicationContext }
    internal fun ctxOrNull(): Context? = appCtx

    private fun plain(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun securePrefs(ctx: Context): SharedPreferences {
        secure?.let { return it }
        synchronized(this) {
            secure?.let { return it }
            val p = try {
                val masterKey = MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    ctx.applicationContext, SECURE_FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Throwable) {
                // Keystore hiccup (seen once on a fresh wipe): fall back to a plain private file
                // rather than losing scrobbling entirely. Still app-private storage.
                ctx.applicationContext.getSharedPreferences("$SECURE_FILE.fallback", Context.MODE_PRIVATE)
            }
            secure = p
            return p
        }
    }

    // ---- credentials -------------------------------------------------------------------------

    fun userApiKey(ctx: Context): String = securePrefs(ctx).getString(KEY_API_KEY, "")?.trim() ?: ""
    fun userApiSecret(ctx: Context): String = securePrefs(ctx).getString(KEY_API_SECRET, "")?.trim() ?: ""
    fun saveCredentials(ctx: Context, apiKey: String, apiSecret: String) {
        init(ctx)
        securePrefs(ctx).edit().putString(KEY_API_KEY, apiKey.trim()).putString(KEY_API_SECRET, apiSecret.trim()).apply()
        clearHardError(ctx)
    }
    fun clearCredentials(ctx: Context) { securePrefs(ctx).edit().remove(KEY_API_KEY).remove(KEY_API_SECRET).apply() }
    fun hasBuildCredentials(): Boolean = BuildConfig.LASTFM_API_KEY.isNotBlank() && BuildConfig.LASTFM_API_SECRET.isNotBlank()
    fun hasUserCredentials(ctx: Context): Boolean = userApiKey(ctx).isNotBlank() && userApiSecret(ctx).isNotBlank()

    // ---- toggle ------------------------------------------------------------------------------

    fun isEnabled(ctx: Context): Boolean = plain(ctx).getBoolean(KEY_ENABLED, true)
    fun setEnabled(ctx: Context, on: Boolean) { plain(ctx).edit().putBoolean(KEY_ENABLED, on).apply() }

    // ---- status (real values only — written by ScrobbleManager after actual API responses) ----

    data class Status(
        val lastScrobbleAt: Long,
        val lastNowPlayingAt: Long,
        val lastError: String?,
        val lastErrorAt: Long,
        val acceptedTotal: Int,
        val ignoredTotal: Int,
        val hardError: String?
    )

    fun status(ctx: Context): Status = plain(ctx).let { p ->
        Status(
            lastScrobbleAt = p.getLong(KEY_LAST_SCROBBLE_AT, 0L),
            lastNowPlayingAt = p.getLong(KEY_LAST_NOW_PLAYING_AT, 0L),
            lastError = p.getString(KEY_LAST_ERROR, null),
            lastErrorAt = p.getLong(KEY_LAST_ERROR_AT, 0L),
            acceptedTotal = p.getInt(KEY_ACCEPTED_TOTAL, 0),
            ignoredTotal = p.getInt(KEY_IGNORED_TOTAL, 0),
            hardError = p.getString(KEY_HARD_ERROR, null)
        )
    }

    fun recordScrobbled(ctx: Context, at: Long, accepted: Int, ignored: Int) {
        val p = plain(ctx)
        p.edit()
            .putLong(KEY_LAST_SCROBBLE_AT, at)
            .putInt(KEY_ACCEPTED_TOTAL, p.getInt(KEY_ACCEPTED_TOTAL, 0) + accepted)
            .putInt(KEY_IGNORED_TOTAL, p.getInt(KEY_IGNORED_TOTAL, 0) + ignored)
            .remove(KEY_LAST_ERROR).remove(KEY_LAST_ERROR_AT)
            .apply()
    }
    fun recordNowPlaying(ctx: Context, at: Long) { plain(ctx).edit().putLong(KEY_LAST_NOW_PLAYING_AT, at).apply() }
    fun recordError(ctx: Context, msg: String, at: Long = System.currentTimeMillis()) {
        plain(ctx).edit().putString(KEY_LAST_ERROR, msg.take(200)).putLong(KEY_LAST_ERROR_AT, at).apply()
    }
    /** A hard error = "retrying won't help until the user changes something" (bad API key,
     *  suspended key, invalid session). Flushing stops until it's cleared by a settings change. */
    fun setHardError(ctx: Context, msg: String) { plain(ctx).edit().putString(KEY_HARD_ERROR, msg.take(200)).apply(); recordError(ctx, msg) }
    fun clearHardError(ctx: Context) { plain(ctx).edit().remove(KEY_HARD_ERROR).apply() }
}

/** Single resolver for the API key / shared secret used to sign every Last.fm call. */
object LastFmCredentials {
    private fun ctx(): Context? = ScrobblePreferences.ctxOrNull()

    fun apiKey(): String {
        val c = ctx()
        val user = if (c != null) ScrobblePreferences.userApiKey(c) else ""
        return user.ifBlank { BuildConfig.LASTFM_API_KEY }
    }

    fun apiSecret(): String {
        val c = ctx()
        val user = if (c != null) ScrobblePreferences.userApiSecret(c) else ""
        return user.ifBlank { BuildConfig.LASTFM_API_SECRET }
    }

    val isConfigured: Boolean get() = apiKey().isNotBlank() && apiSecret().isNotBlank()

    /** "user" | "build" | "none" — surfaced in the settings card so it's clear which key is live. */
    fun source(): String {
        val c = ctx()
        if (c != null && ScrobblePreferences.hasUserCredentials(c)) return "user"
        return if (ScrobblePreferences.hasBuildCredentials()) "build" else "none"
    }
}
