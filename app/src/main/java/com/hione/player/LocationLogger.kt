package com.hione.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hione.player.metrics.HioneMetricDatabase
import com.hione.player.weather.HioneWeatherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Oki Hione Per-Listen Context & Atmospheric Meteorological Logger.
 * Records precise listening events correlated with real-time Open-Meteo weather
 * metrics (temp, humidity, wind, condition code), GPS coordinates, and DAC hardware state
 * directly into HioneMetricDatabase for deep music telemetry intelligence.
 */
object LocationLogger {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun logForTrack(
        ctx: Context,
        trackId: Long,
        nowMs: Long = System.currentTimeMillis(),
        title: String = "",
        artist: String = "",
        album: String = "",
        /** Real per-track sample rate from the library DB (TrackTech); null = unknown, stored as 0. */
        sampleRateHz: Int? = null
    ) {
        if (!HionePowerGovernor.allowLocation) return   // power governor: no location work while screen-off / idle
        scope.launch {
            try {
                val weatherState = HioneWeatherService.state.value
                val weather = weatherState.weather
                val gps = weatherState.gps
                // Only a weather condition from a REAL completed fetch may be recorded; the service's
                // default instance (never fetched in this process) must not be written as a reading.
                val haveWeather = weather.lastUpdatedTime > 0L && !weather.tempF.isNaN()

                var finalLat = gps.latitude
                var finalLon = gps.longitude
                var finalCity = gps.city   // empty = unknown; no "Local Station" placeholder

                // Optional single-shot coarse location fix if GPS wasn't already locked
                if (!gps.isLocked && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    if (lm != null) {
                        val provider = when {
                            Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                            else -> null
                        }
                        if (provider != null) {
                            @Suppress("DEPRECATION")
                            val last = lm.getLastKnownLocation(provider)
                            if (last != null) {
                                finalLat = last.latitude
                                finalLon = last.longitude
                            }
                        }
                    }
                }

                // Record full correlated song playback + atmospheric weather entry in DB
                HioneMetricDatabase.getInstance(ctx).insertSongPlayWeatherTelemetry(
                    HioneMetricDatabase.SongPlayWeatherRecord(
                        timestamp = nowMs,
                        trackId = trackId,
                        title = title,
                        artist = artist,
                        album = album,
                        tempF = if (haveWeather) weather.tempF else Float.NaN,
                        feelsLikeF = if (haveWeather) weather.feelsLikeF else Float.NaN,
                        humidityPct = if (haveWeather) weather.humidityPct else -1,
                        windSpeedMph = if (haveWeather) weather.windSpeedMph else Float.NaN,
                        weatherSummary = if (haveWeather) weather.summary else "",
                        weatherCode = if (haveWeather) weather.code else -1,
                        isDay = weather.isDay,
                        latitude = finalLat,
                        longitude = finalLon,
                        locationCity = finalCity,
                        // Real values only (was hardcoded 192000 / "HIGH"): 0 = unknown rate.
                        dacSampleRate = sampleRateHz ?: (runCatching { TrackTech.cachedSampleRateFor(ctx, trackId) }.getOrNull() ?: 0),
                        dacGain = runCatching { CirrusLogicManager.getGainMode(ctx).name }.getOrDefault("UNKNOWN"),
                        playbackDurationMs = 0L
                    )
                )
            } catch (_: Throwable) {}
        }
    }
}
