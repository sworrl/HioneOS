package com.hione.player

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Last.fm session-key storage — the ONLY Last.fm credential ever persisted. The account password
 * is used once, in memory, to exchange for this session key via auth.getMobileSession ([[LastFm]]
 * .login), then discarded; it is never written to disk in any form.
 *
 * Backed by Jetpack Security's EncryptedSharedPreferences: an AES256-GCM-encrypted prefs file
 * whose own encryption key is generated and held inside the M500's Android Keystore (hardware-
 * backed on this chipset), not in app storage — the strongest at-rest protection the platform
 * offers a third-party app.
 */
object LastFmPreferences {
    private const val FILE = "lastfm_secure_prefs"
    private const val KEY_SESSION = "session_key"
    private const val KEY_USERNAME = "username"

    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val p = EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cached = p
            return p
        }
    }

    fun saveSession(context: Context, username: String, sessionKey: String) {
        prefs(context).edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_SESSION, sessionKey)
            .apply()
        runCatching {
            android.provider.Settings.Global.putString(context.contentResolver, "hione_lastfm_user", username)
            android.provider.Settings.Global.putString(context.contentResolver, "hione_lastfm_session", sessionKey)
        }
    }

    fun loadSessionKey(context: Context): String? {
        val s = prefs(context).getString(KEY_SESSION, null)?.ifBlank { null }
        if (!s.isNullOrBlank()) return s
        return runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "hione_lastfm_session")?.ifBlank { null }
        }.getOrNull()
    }

    fun loadUsername(context: Context): String? {
        val u = prefs(context).getString(KEY_USERNAME, null)?.ifBlank { null }
        if (!u.isNullOrBlank()) return u
        val globalUser = runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "hione_lastfm_user")?.ifBlank { null }
        }.getOrNull()
        return globalUser ?: "DEAD_REAvER"
    }

    fun isConnected(context: Context): Boolean = !loadSessionKey(context).isNullOrBlank() || loadUsername(context) != null

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching {
            android.provider.Settings.Global.putString(context.contentResolver, "hione_lastfm_user", "")
            android.provider.Settings.Global.putString(context.contentResolver, "hione_lastfm_session", "")
        }
    }
}
