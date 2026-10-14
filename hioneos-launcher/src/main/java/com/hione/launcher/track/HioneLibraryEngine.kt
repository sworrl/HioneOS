package com.hione.launcher.track

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HioneLibraryState(
    val totalTracks: Int = 0,
    // Removed totalArtists / totalAlbums / hiResCount: they were declared but NEVER assigned, so any
    // surface that rendered them would have shown a permanent, authoritative-looking 0.
    // "—" until the MediaStore query has actually run; "0" would claim an empty library.
    val abbreviatedTracks: String = "—",
    val isInitialized: Boolean = false
)

/**
 * System-Level Audio Library & Track Telemetry Engine.
 * Queries MediaStore in real-time and observes storage additions/deletions.
 */
object HioneLibraryEngine {
    private val _state = MutableStateFlow(HioneLibraryState())
    val state: StateFlow<HioneLibraryState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerRegistered = false

    fun init(context: Context) {
        val appContext = context.applicationContext
        refresh(appContext)

        if (!observerRegistered) {
            observerRegistered = true
            try {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        refresh(appContext)
                    }
                }
                appContext.contentResolver.registerContentObserver(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer
                )
            } catch (_: Throwable) {}
        }
    }

    fun refresh(context: Context) {
        scope.launch {
            try {
                val cr = context.contentResolver
                var trackCount = 0
                val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.IS_MUSIC)
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

                cr.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    null
                )?.use { cursor ->
                    trackCount = cursor.count
                }

                // Fixed: the old form appended a second unit letter after a removeSuffix that could
                // not match, so a 1500-track library rendered as "1.5kk" (and 1.5M as "1.5MM").
                val abbrev = when {
                    trackCount >= 1_000_000 ->
                        String.format(java.util.Locale.US, "%.1f", trackCount / 1_000_000f).removeSuffix(".0") + "M"
                    trackCount >= 10_000 -> "${trackCount / 1000}k"
                    trackCount >= 1_000 ->
                        String.format(java.util.Locale.US, "%.1f", trackCount / 1000f).removeSuffix(".0") + "k"
                    else -> trackCount.toString()
                }

                _state.value = HioneLibraryState(
                    totalTracks = trackCount,
                    abbreviatedTracks = abbrev,
                    isInitialized = true
                )
            } catch (_: Throwable) {}
        }
    }
}
