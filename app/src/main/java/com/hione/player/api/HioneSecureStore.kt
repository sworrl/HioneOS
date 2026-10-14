package com.hione.player.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted prefs for the remote API token, the URL-signing key and the paired TV tokens.
 *
 * The master key lives in the Android Keystore (AES-256-GCM). If the file can't be opened
 * (a keystore reset after a wipe leaves undecryptable data behind) it is deleted and recreated,
 * which signs out every paired browser and TV. If that fails too this returns null and callers
 * fail closed: no token means no server, never a plaintext fallback.
 */
object HioneSecureStore {
    private const val TAG = "HioneSecureStore"
    private const val FILE = "hione_api_secure"

    @Volatile private var prefs: SharedPreferences? = null

    @Synchronized
    fun get(ctx: Context): SharedPreferences? {
        prefs?.let { return it }
        val app = ctx.applicationContext
        val p = try {
            open(app)
        } catch (t: Throwable) {
            Log.w(TAG, "encrypted prefs unreadable (${t.javaClass.simpleName}), recreating")
            runCatching { app.deleteSharedPreferences(FILE) }
            try { open(app) } catch (t2: Throwable) {
                Log.e(TAG, "encrypted prefs unavailable, remote API stays off", t2)
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
}
