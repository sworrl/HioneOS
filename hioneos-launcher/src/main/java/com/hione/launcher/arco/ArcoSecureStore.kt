package com.hione.launcher.arco

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * KeyStore-backed encrypted storage for everything the arcobocconotto
 * integration must not hold as a source literal: the paired Bearer token,
 * the resolved server endpoint, device identity, and (optionally) a
 * pre-shared HMAC key pair for the direct-auth path.
 *
 * Per PAIRING_SPEC.md §3: AES256_SIV key encryption / AES256_GCM value
 * encryption, backed by the Android KeyStore master key.
 */
class ArcoSecureStore(context: Context) {
    private val appContext = context.applicationContext
    private val TAG = "ArcoSecureStore"

    private val prefs: SharedPreferences by lazy { openOrRecreate() }

    private fun buildPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            "arcobocconotto_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Recovers from a corrupted/rotated KeyStore entry (e.g. after a restore
     * to a different device) by wiping and recreating the encrypted file
     * rather than crashing every launch. */
    private fun openOrRecreate(): SharedPreferences = try {
        buildPrefs()
    } catch (t: Throwable) {
        Log.w(TAG, "Encrypted prefs unreadable, recreating", t)
        appContext.getSharedPreferences("arcobocconotto_secure_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        try {
            appContext.deleteSharedPreferences("arcobocconotto_secure_prefs")
        } catch (_: Throwable) {}
        buildPrefs()
    }

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null) ?: ArcoConfig.buildConfigLanUrl()
        set(v) = prefs.edit().putString(KEY_SERVER_URL, v).apply()

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(v) = prefs.edit().putString(KEY_AUTH_TOKEN, v).apply()

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(v) = prefs.edit().putString(KEY_DEVICE_ID, v).apply()

    var deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(v) = prefs.edit().putString(KEY_DEVICE_NAME, v).apply()

    var apiKeyId: String?
        get() = prefs.getString(KEY_API_KEY_ID, null)
        set(v) = prefs.edit().putString(KEY_API_KEY_ID, v).apply()

    var wsEndpoint: String?
        get() = prefs.getString(KEY_WS_ENDPOINT, null) ?: ArcoConfig.WS_PATH
        set(v) = prefs.edit().putString(KEY_WS_ENDPOINT, v).apply()

    var serverVersion: String?
        get() = prefs.getString(KEY_SERVER_VERSION, null)
        set(v) = prefs.edit().putString(KEY_SERVER_VERSION, v).apply()

    /** Optional pre-shared HMAC direct-auth path (API_REFERENCE.md "Direct /
     * Scripting Auth"). Falls back to the optional gitignored arco.properties
     * BuildConfig values when the encrypted store has nothing saved yet —
     * either way this is never a literal in a .kt file. */
    var hmacKeyId: String?
        get() = prefs.getString(KEY_HMAC_KEY_ID, null) ?: ArcoConfig.buildConfigHmacKeyId()
        set(v) = prefs.edit().putString(KEY_HMAC_KEY_ID, v).apply()

    var hmacSecret: String?
        get() = prefs.getString(KEY_HMAC_SECRET, null) ?: ArcoConfig.buildConfigHmacSecret()
        set(v) = prefs.edit().putString(KEY_HMAC_SECRET, v).apply()

    var musicVisualizerBridgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BPM_BRIDGE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_BPM_BRIDGE_ENABLED, v).apply()

    val isPaired: Boolean
        get() = !authToken.isNullOrBlank() && !serverUrl.isNullOrBlank()

    val isDirectKeyConfigured: Boolean
        get() = !hmacKeyId.isNullOrBlank() && !hmacSecret.isNullOrBlank()

    /** Clears the paired Bearer token / device identity (equivalent to
     * "unpair" per PAIRING_SPEC.md §4) but preserves any direct-key config
     * sourced from arco.properties, since that isn't pairing state. */
    fun clearPairing() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_API_KEY_ID)
            .remove(KEY_SERVER_VERSION)
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_API_KEY_ID = "api_key_id"
        const val KEY_WS_ENDPOINT = "ws_endpoint"
        const val KEY_SERVER_VERSION = "server_version"
        const val KEY_HMAC_KEY_ID = "hmac_key_id"
        const val KEY_HMAC_SECRET = "hmac_secret"
        const val KEY_BPM_BRIDGE_ENABLED = "bpm_bridge_enabled"
    }
}
