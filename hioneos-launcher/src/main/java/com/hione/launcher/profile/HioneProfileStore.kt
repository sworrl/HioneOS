package com.hione.launcher.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Local Multi-User Profile Store for HioneOS.
 *
 * Saves local user profiles, avatars, handles, and per-profile listening telemetry
 * to Settings.Global so both the Launcher and Player access the exact same active profile.
 */
object HioneProfileStore {
    private const val TAG = "HioneProfileStore"
    private const val GLOBAL_KEY_PROFILES = "hione_user_profiles_v1"
    private const val GLOBAL_KEY_ACTIVE_ID = "hione_active_profile_id"

    data class UserProfile(
        val id: String,
        val name: String,
        val handle: String,
        val avatarPath: String = "",
        val avatarPreset: Int = 0,
        val createdEpochMs: Long = System.currentTimeMillis(),
        val totalListeningSeconds: Long = 0L,
        val likedTrackIds: Set<String> = emptySet(),
        val bio: String = "Audiophile · Hi-Res Direct PCM"
    )

    private val defaultProfile = UserProfile(
        id = "default_user",
        name = "Oki Hione",
        handle = "@okihione",
        bio = "Mascot & Master of Direct PCM",
        avatarPreset = 0
    )

    private val _activeProfile = MutableStateFlow(defaultProfile)
    val activeProfile: StateFlow<UserProfile> = _activeProfile

    private val _allProfiles = MutableStateFlow<List<UserProfile>>(listOf(defaultProfile))
    val allProfiles: StateFlow<List<UserProfile>> = _allProfiles

    fun init(ctx: Context) {
        load(ctx)
    }

    @Synchronized
    fun load(ctx: Context): UserProfile {
        val cr = ctx.applicationContext.contentResolver
        val raw = try {
            Settings.Global.getString(cr, GLOBAL_KEY_PROFILES)
        } catch (_: Throwable) { null }

        val activeId = try {
            Settings.Global.getString(cr, GLOBAL_KEY_ACTIVE_ID) ?: defaultProfile.id
        } catch (_: Throwable) { defaultProfile.id }

        if (raw.isNullOrBlank()) {
            val list = listOf(defaultProfile)
            _allProfiles.value = list
            _activeProfile.value = defaultProfile
            save(ctx, list, defaultProfile.id)
            return defaultProfile
        }

        val parsed = parseProfiles(raw)
        val list = if (parsed.isEmpty()) listOf(defaultProfile) else parsed
        _allProfiles.value = list

        val active = list.firstOrNull { it.id == activeId } ?: list.first()
        _activeProfile.value = active
        return active
    }

    @Synchronized
    fun switchProfile(ctx: Context, profileId: String) {
        val list = _allProfiles.value
        val next = list.firstOrNull { it.id == profileId } ?: return
        _activeProfile.value = next
        try {
            Settings.Global.putString(ctx.applicationContext.contentResolver, GLOBAL_KEY_ACTIVE_ID, next.id)
        } catch (t: Throwable) {
            Log.w(TAG, "failed writing active profile id: ${t.message}")
        }
    }

    @Synchronized
    fun createProfile(ctx: Context, name: String, handle: String): UserProfile {
        val cleanName = name.trim().ifBlank { "User ${allProfiles.value.size + 1}" }
        val cleanHandle = if (handle.startsWith("@")) handle.trim() else "@${handle.trim().ifBlank { cleanName.lowercase() }}"
        val p = UserProfile(
            id = UUID.randomUUID().toString().take(8),
            name = cleanName,
            handle = cleanHandle,
            createdEpochMs = System.currentTimeMillis()
        )
        val updated = _allProfiles.value + p
        _allProfiles.value = updated
        _activeProfile.value = p
        save(ctx, updated, p.id)
        return p
    }

    @Synchronized
    fun updateProfile(ctx: Context, profile: UserProfile) {
        val updated = _allProfiles.value.map { if (it.id == profile.id) profile else it }
        _allProfiles.value = updated
        if (_activeProfile.value.id == profile.id) {
            _activeProfile.value = profile
        }
        save(ctx, updated, _activeProfile.value.id)
    }

    @Synchronized
    fun saveCustomAvatar(ctx: Context, profileId: String, bitmap: Bitmap): String {
        val dir = File(ctx.applicationContext.filesDir, "profiles")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "avatar_${profileId}.png")
        runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
        }
        val path = file.absolutePath
        val cur = _allProfiles.value.firstOrNull { it.id == profileId }
        if (cur != null) {
            updateProfile(ctx, cur.copy(avatarPath = path))
        }
        return path
    }

    fun loadAvatarBitmap(path: String): Bitmap? {
        if (path.isBlank()) return null
        return runCatching {
            val f = File(path)
            if (f.exists()) BitmapFactory.decodeFile(path) else null
        }.getOrNull()
    }

    private fun save(ctx: Context, profiles: List<UserProfile>, activeId: String) {
        val cr = ctx.applicationContext.contentResolver
        val arr = JSONArray()
        for (p in profiles) {
            val obj = JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("handle", p.handle)
                .put("avatarPath", p.avatarPath)
                .put("avatarPreset", p.avatarPreset)
                .put("createdEpochMs", p.createdEpochMs)
                .put("totalListeningSeconds", p.totalListeningSeconds)
                .put("likedTrackIds", JSONArray(p.likedTrackIds))
                .put("bio", p.bio)
            arr.put(obj)
        }
        try {
            Settings.Global.putString(cr, GLOBAL_KEY_PROFILES, arr.toString())
            Settings.Global.putString(cr, GLOBAL_KEY_ACTIVE_ID, activeId)
        } catch (t: Throwable) {
            Log.w(TAG, "save failed: ${t.message}")
        }
    }

    private fun parseProfiles(json: String): List<UserProfile> {
        val list = mutableListOf<UserProfile>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val likes = mutableSetOf<String>()
                val lArr = o.optJSONArray("likedTrackIds")
                if (lArr != null) {
                    for (j in 0 until lArr.length()) likes.add(lArr.getString(j))
                }
                list.add(
                    UserProfile(
                        id = o.optString("id", UUID.randomUUID().toString().take(8)),
                        name = o.optString("name", "User"),
                        handle = o.optString("handle", "@user"),
                        avatarPath = o.optString("avatarPath", ""),
                        avatarPreset = o.optInt("avatarPreset", 0),
                        createdEpochMs = o.optLong("createdEpochMs", System.currentTimeMillis()),
                        totalListeningSeconds = o.optLong("totalListeningSeconds", 0L),
                        likedTrackIds = likes,
                        bio = o.optString("bio", "")
                    )
                )
            }
        } catch (_: Throwable) {}
        return list
    }
}
