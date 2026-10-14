package com.hione.player.weather

import com.hione.player.HionePowerGovernor
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Oki Hione Real-Time GPS & Weather Observatory Engine.
 * Incorporates the complete multi-source meteorological pipeline from OnThe8s (RetroWeather):
 * 1. National Weather Service (api.weather.gov) station resolution, direct observations, & forecast lookaheads.
 * 2. Open-Meteo high-resolution hourly/daily meteograms & global satellite fallbacks.
 * 3. Look-ahead precipitation classifier ("RAIN IN 2 HRS", "TSTORM NOW", "SNOW IN 4 HRS").
 * 4. Astronomical Solar Arc calculations (sunrise/sunset, solar day progress, night tracking).
 * 5. Multi-source telemetry: Dew Point, Barometric Pressure, Cloud Cover, Visibility, AQI, UV Index.
 */
object HioneWeatherService {
    private const val TAG = "HioneWeatherService"
    private const val NWS_BASE = "https://api.weather.gov"

    data class GpsTelemetry(
        val isLocked: Boolean = false,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val altitudeM: Double = 0.0,
        val accuracyM: Float = 0f,
        val speedMph: Float = 0f,
        val bearing: Float = 0f,
        val provider: String = "None",
        val lastFixTime: Long = 0L,
        /** Empty until reverse geocoding actually resolves it — never a placeholder string. */
        val city: String = "",
        val county: String = "",
        val state: String = "",
        val country: String = "",
        val fuzzyLocation: String = ""
    )

    // Every forecast point below is built field-by-field from a parsed Open-Meteo response. The
    // defaults are deliberately un-renderable (NaN / -1 / em dash) so that a point constructed
    // without real numbers can never pass itself off as a reading — these used to default to 72F /
    // 70F / 5 mph / 8 mph / 75F / 55F / 10 mph / "Clear Sky" / "08/18", i.e. a plausible fake day.
    data class HourlyPrecipPoint(
        val timeLabel: String = "—",
        val precipInches: Float = Float.NaN,
        val precipProbPct: Int = -1,
        val weatherCode: Int = -1,
        val tempF: Float = Float.NaN,
        val summary: String = "—",
        val icon: String = ""
    )

    data class HourlyMeteogramPoint(
        val timeLabel: String = "—",
        val dayLabel: String = "—",
        val tempF: Float = Float.NaN,
        val feelsLikeF: Float = Float.NaN,
        val precipInches: Float = Float.NaN,
        val precipProbPct: Int = -1,
        val windSpeedMph: Float = Float.NaN,
        val windGustsMph: Float = Float.NaN,
        val windDirectionDeg: Int = -1,
        val windDirectionCompass: String = "—",
        val weatherCode: Int = -1,
        val summary: String = "—",
        val icon: String = "",
        val isDay: Boolean = true
    )

    data class DailyForecastPoint(
        val dayName: String = "—",
        val dateFormatted: String = "—",
        val maxTempF: Float = Float.NaN,
        val minTempF: Float = Float.NaN,
        val precipSumIn: Float = Float.NaN,
        val precipProbMax: Int = -1,
        val maxWindSpeedMph: Float = Float.NaN,
        val weatherCode: Int = -1,
        val summary: String = "—",
        val icon: String = ""
    )

    data class MoonPhaseInfo(
        val phaseFraction: Float = 0.5f,
        val phaseName: String = "Waxing Gibbous",
        val phaseIcon: String = "🌔",
        val illuminationPct: Int = 75,
        val ageDays: Float = 11.2f
    )

    /**
     * Defaults are the HONEST "no data yet" state: NaN / -1 / "—". A [WeatherCondition] only carries
     * real numbers once a fetch succeeded (lastUpdatedTime > 0); consumers must check that, or the
     * NaN sentinels, before rendering or recording a reading.
     */
    data class WeatherCondition(
        val code: Int = -1,
        val summary: String = "—",
        val icon: String = "",
        val tempF: Float = Float.NaN,
        val feelsLikeF: Float = Float.NaN,
        val humidityPct: Int = -1,
        val windSpeedMph: Float = Float.NaN,
        val windGustMph: Float = Float.NaN,
        val windDirectionDeg: Int = -1,
        val windDirectionCompass: String = "—",
        val precipitationIn: Float = Float.NaN,
        val precipitationProbPct: Int = -1,
        val highTempF: Float = Float.NaN,
        val lowTempF: Float = Float.NaN,
        val dewPointF: Float = Float.NaN,
        val pressureInHg: Float = Float.NaN,
        val cloudCoverPct: Int = -1,
        val visibilityMiles: Float = Float.NaN,
        val uvIndex: Float = Float.NaN,
        /** Air quality is NOT fetched by this service; -1 / "" = not available. */
        val aqi: Int = -1,
        val aqiCategory: String = "",
        val sunrise: String = "—",
        val sunset: String = "—",
        val solarFraction: Float = 0f,
        val isDay: Boolean = true,
        val moonPhase: MoonPhaseInfo = calculateMoonPhase(),
        val nextPrecipLabel: String = "",
        val todayCond: String = "—",
        val tomorrowDay: String = "Tomorrow",
        val tomorrowHi: Float = Float.NaN,
        val tomorrowLo: Float = Float.NaN,
        val tomorrowCond: String = "—",
        val sourcesUsed: String = "",
        val hourlySparklineTemps: List<Int> = emptyList(),
        val nwsStationId: String = "",
        val severeWarning: String? = null,
        val lastUpdatedTime: Long = 0L,
        val hourlyPrecip6h: List<HourlyPrecipPoint> = emptyList(),
        val hourlyMeteogram: List<HourlyMeteogramPoint> = emptyList(),
        val dailyForecast: List<DailyForecastPoint> = emptyList()
    )

    fun calculateMoonPhase(timestamp: Long = System.currentTimeMillis()): MoonPhaseInfo {
        val synodicMonthMs = 29.530588853 * 86400000.0
        val diffMs = (timestamp - 1704974220000L).toDouble()
        val phase = ((diffMs % synodicMonthMs) + synodicMonthMs) % synodicMonthMs
        val phaseFrac = (phase / synodicMonthMs).toFloat()
        val ageDays = (phaseFrac * 29.530588853f)
        val illum = (0.5f * (1.0f - kotlin.math.cos(phaseFrac * 2.0 * Math.PI).toFloat()) * 100f).roundToInt()

        val (name, icon) = when {
            phaseFrac < 0.03f || phaseFrac >= 0.97f -> "New Moon" to "🌑"
            phaseFrac < 0.22f -> "Waxing Crescent" to "🌒"
            phaseFrac < 0.28f -> "First Quarter" to "🌓"
            phaseFrac < 0.47f -> "Waxing Gibbous" to "🌔"
            phaseFrac < 0.53f -> "Full Moon" to "🌕"
            phaseFrac < 0.72f -> "Waning Gibbous" to "🌖"
            phaseFrac < 0.78f -> "Last Quarter" to "🌗"
            else -> "Waning Crescent" to "🌘"
        }

        return MoonPhaseInfo(
            phaseFraction = phaseFrac,
            phaseName = name,
            phaseIcon = icon,
            illuminationPct = illum,
            ageDays = ageDays
        )
    }

    data class WeatherState(
        val gps: GpsTelemetry = GpsTelemetry(),
        val weather: WeatherCondition = WeatherCondition(),
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var locationJob: Job? = null
    private var weatherJob: Job? = null

    // Cache NWS endpoint routes
    private var cachedStationId: String? = null
    private var cachedForecastUrl: String? = null
    private var cachedHourlyForecastUrl: String? = null

    fun start(ctx: Context) {
        if (locationJob != null && weatherJob != null) return

        startLocationTracking(ctx)

        if (weatherJob == null) {
            weatherJob = scope.launch {
                while (isActive) {
                    if (HionePowerGovernor.allowBackgroundWork) refreshWeather(ctx)   // deferred while screen-off / idle
                    delay(8 * 60 * 1000L) // 8 minutes update cycle
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationTracking(ctx: Context, force: Boolean = false) {
        if (locationJob != null && !force) return
        locationJob?.cancel()
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        locationJob = scope.launch {
            try {
                if (isManualLocationEnabled(ctx)) {
                    restoreLastGoodFix(ctx)
                    refreshWeather(ctx)
                    return@launch
                }

                // 1. Initial cached fallback
                var bestLoc: Location? = null
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).forEach { prov ->
                    try {
                        val l = lm.getLastKnownLocation(prov)
                        if (l != null && (bestLoc == null || l.accuracy < (bestLoc?.accuracy ?: 9999f))) {
                            bestLoc = l
                        }
                    } catch (_: Throwable) {}
                }

                if (bestLoc != null) {
                    updateLocation(ctx, bestLoc!!)
                    refreshWeather(ctx)
                } else {
                    if (!restoreLastGoodFix(ctx)) {
                        fetchIpGeolocation(ctx)
                    }
                    refreshWeather(ctx)
                }

                // 2. Continuous real GPS telemetry listener
                withContext(Dispatchers.Main) {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            scope.launch {
                                updateLocation(ctx, loc)
                            }
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }

                    try {
                        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, listener)
                        }
                        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 10f, listener)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Location listener request failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Location tracking init failed", t)
            }
        }
    }

    private fun updateLocation(ctx: Context, loc: Location) {
        val speedMph = loc.speed * 2.23694f
        val currentGps = _state.value.gps

        _state.value = _state.value.copy(
            gps = currentGps.copy(
                isLocked = true,
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitudeM = loc.altitude,
                accuracyM = loc.accuracy,
                speedMph = speedMph,
                bearing = loc.bearing,
                provider = loc.provider ?: "GPS",
                lastFixTime = SystemClock.elapsedRealtime()
            )
        )

        // Asynchronously reverse geocode if location drifted > 2km or not yet resolved
        if (currentGps.city.isEmpty() || currentGps.city == "Finding location…" ||
            Math.abs(currentGps.latitude - loc.latitude) > 0.02 ||
            Math.abs(currentGps.longitude - loc.longitude) > 0.02) {
            reverseGeocode(ctx, loc.latitude, loc.longitude)
        }

        // A real GNSS/network fix is the best truth we get — persist for future cold starts.
        saveLastGoodFix(ctx, loc.latitude, loc.longitude, currentGps.city, currentGps.state)
    }

    data class CitySearchResult(
        val name: String,
        val region: String,
        val country: String,
        val latitude: Double,
        val longitude: Double
    )

    fun isManualLocationEnabled(ctx: Context): Boolean {
        return ctx.getSharedPreferences("hione_weather", Context.MODE_PRIVATE)
            .getBoolean("manual_loc_enabled", false)
    }

    fun setManualLocation(ctx: Context, lat: Double, lon: Double, city: String, region: String, country: String = "US") {
        ctx.getSharedPreferences("hione_weather", Context.MODE_PRIVATE).edit()
            .putBoolean("manual_loc_enabled", true)
            .putLong("manual_lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("manual_lon", java.lang.Double.doubleToRawLongBits(lon))
            .putString("manual_city", city)
            .putString("manual_region", region)
            .putString("manual_country", country)
            .apply()

        _state.value = _state.value.copy(
            gps = _state.value.gps.copy(
                isLocked = true,
                latitude = lat,
                longitude = lon,
                city = city,
                state = region,
                country = country,
                provider = "Manual Override",
                lastFixTime = SystemClock.elapsedRealtime(),
                fuzzyLocation = buildFuzzyString("", city, region)
            )
        )
        refreshWeather(ctx)
    }

    fun clearManualLocation(ctx: Context) {
        ctx.getSharedPreferences("hione_weather", Context.MODE_PRIVATE).edit()
            .putBoolean("manual_loc_enabled", false)
            .apply()
        _state.value = _state.value.copy(
            gps = _state.value.gps.copy(
                provider = "Auto GPS",
                isLocked = false
            )
        )
        startLocationTracking(ctx, force = true)
    }

    fun searchCity(query: String, onResult: (List<CitySearchResult>) -> Unit) {
        scope.launch {
            if (query.trim().length < 2) {
                withContext(Dispatchers.Main) { onResult(emptyList()) }
                return@launch
            }
            try {
                val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=8&language=en&format=json")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val json = JSONObject(reader.readText())
                    reader.close()
                    val resultsArr = json.optJSONArray("results")
                    val list = mutableListOf<CitySearchResult>()
                    if (resultsArr != null) {
                        for (i in 0 until resultsArr.length()) {
                            val item = resultsArr.getJSONObject(i)
                            val name = item.optString("name", "")
                            val admin1 = item.optString("admin1", "")
                            val country = item.optString("country", "")
                            val lat = item.optDouble("latitude", 0.0)
                            val lon = item.optDouble("longitude", 0.0)
                            if (name.isNotEmpty() && lat != 0.0) {
                                list.add(CitySearchResult(name, admin1, country, lat, lon))
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onResult(list) }
                    return@launch
                }
            } catch (t: Throwable) {
                Log.w(TAG, "City search failed: ${t.message}")
            }
            withContext(Dispatchers.Main) { onResult(emptyList()) }
        }
    }

    /** Persist the last GOOD fix so a reboot/offline start never regresses to default coords. */
    private fun saveLastGoodFix(ctx: Context, lat: Double, lon: Double, city: String, region: String) {
        runCatching {
            ctx.getSharedPreferences("hione_weather", Context.MODE_PRIVATE).edit()
                .putLong("last_lat", java.lang.Double.doubleToRawLongBits(lat))
                .putLong("last_lon", java.lang.Double.doubleToRawLongBits(lon))
                .putString("last_city", city)
                .putString("last_region", region)
                .apply()
        }
    }

    /** Restore the persisted fix; returns false when none saved yet. */
    fun restoreLastGoodFix(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("hione_weather", Context.MODE_PRIVATE)
        if (p.getBoolean("manual_loc_enabled", false)) {
            val lat = java.lang.Double.longBitsToDouble(p.getLong("manual_lat", 0L))
            val lon = java.lang.Double.longBitsToDouble(p.getLong("manual_lon", 0L))
            val city = p.getString("manual_city", "") ?: ""
            val region = p.getString("manual_region", "") ?: ""
            val country = p.getString("manual_country", "US") ?: "US"
            if (lat != 0.0 || lon != 0.0) {
                _state.value = _state.value.copy(
                    gps = _state.value.gps.copy(
                        isLocked = true,
                        latitude = lat,
                        longitude = lon,
                        city = city,
                        state = region,
                        country = country,
                        provider = "Manual",
                        lastFixTime = SystemClock.elapsedRealtime(),
                        fuzzyLocation = buildFuzzyString("", city, region)
                    )
                )
                return true
            }
        }
        if (!p.contains("last_lat")) return false
        val lat = java.lang.Double.longBitsToDouble(p.getLong("last_lat", 0L))
        val lon = java.lang.Double.longBitsToDouble(p.getLong("last_lon", 0L))
        if (lat == 0.0 && lon == 0.0) return false
        _state.value = _state.value.copy(
            gps = _state.value.gps.copy(
                isLocked = true,
                latitude = lat,
                longitude = lon,
                city = p.getString("last_city", "") ?: "",
                state = p.getString("last_region", "") ?: "",
                provider = "Last Known",
                lastFixTime = SystemClock.elapsedRealtime(),
                fuzzyLocation = buildFuzzyString("", p.getString("last_city", "") ?: "", p.getString("last_region", "") ?: "")
            )
        )
        return true
    }

    private fun fetchIpGeolocation(ctx: Context) {
        // Fallback CHAIN — if manual location is set, do not query IP-geo
        if (isManualLocationEnabled(ctx)) return

        data class GeoApi(val url: String, val latKey: String, val lonKey: String, val cityKey: String, val regionKey: String, val countryKey: String)
        val providers = listOf(
            GeoApi("http://ip-api.com/json/", "lat", "lon", "city", "regionName", "country"),
            GeoApi("https://ipapi.co/json/", "latitude", "longitude", "city", "region", "country_name"),
            GeoApi("https://ipwho.is/", "latitude", "longitude", "city", "region", "country")
        )
        for (api in providers) {
            try {
                val conn = (URL(api.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "HioneMeteorology/1.0 (contact@falcontechnix.com)")
                }
                if (conn.responseCode != 200) continue
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val json = JSONObject(reader.readText())
                reader.close()

                val lat = json.optDouble(api.latKey, 0.0)
                val lon = json.optDouble(api.lonKey, 0.0)
                if (lat == 0.0 && lon == 0.0) continue
                val city = json.optString(api.cityKey, "")
                val region = json.optString(api.regionKey, "")
                val country = json.optString(api.countryKey, "")

                val fuzzy = buildFuzzyString("", city, region)

                _state.value = _state.value.copy(
                    gps = _state.value.gps.copy(
                        isLocked = true,
                        latitude = lat,
                        longitude = lon,
                        city = city,
                        state = region,
                        country = country,
                        provider = "IP Geolocation",
                        lastFixTime = SystemClock.elapsedRealtime(),
                        fuzzyLocation = fuzzy
                    )
                )
                saveLastGoodFix(ctx, lat, lon, city, region)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "IP geolocation via ${api.url} failed: ${t.message}")
            }
        }
        Log.w(TAG, "All IP geolocation providers failed")
    }

    private fun reverseGeocode(ctx: Context, lat: Double, lon: Double) {
        scope.launch {
            var resolvedCity = ""
            var resolvedCounty = ""
            var resolvedState = ""
            var resolvedCountry = ""

            // 1. Android Geocoder
            try {
                val geocoder = android.location.Geocoder(ctx, Locale.US)
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    resolvedCity = addr.locality ?: addr.subAdminArea ?: ""
                    resolvedCounty = addr.subAdminArea ?: ""
                    resolvedState = addr.adminArea ?: ""
                    resolvedCountry = addr.countryName ?: ""
                }
            } catch (_: Throwable) {}

            // 2. Open-Meteo / Nominatim reverse geocoding fallback
            if (resolvedCity.isEmpty()) {
                try {
                    val url = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=10")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        setRequestProperty("User-Agent", "HioneMeteorology/1.0 (contact@falcontechnix.com)")
                    }
                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        val json = JSONObject(reader.readText())
                        reader.close()
                        val address = json.optJSONObject("address")
                        if (address != null) {
                            resolvedCity = address.optString("city", address.optString("town", address.optString("village", "")))
                            resolvedCounty = address.optString("county", "")
                            resolvedState = address.optString("state", "")
                            resolvedCountry = address.optString("country", "")
                        }
                    }
                } catch (_: Throwable) {}
            }

            val currentGps = _state.value.gps
            val finalCity = if (resolvedCity.isNotEmpty()) resolvedCity else currentGps.city
            val finalCounty = if (resolvedCounty.isNotEmpty()) resolvedCounty else currentGps.county
            val finalState = if (resolvedState.isNotEmpty()) resolvedState else currentGps.state
            val fuzzy = buildFuzzyString(finalCounty, finalCity, finalState)

            _state.value = _state.value.copy(
                gps = _state.value.gps.copy(
                    city = finalCity,
                    county = finalCounty,
                    state = finalState,
                    country = if (resolvedCountry.isNotEmpty()) resolvedCountry else currentGps.country,
                    fuzzyLocation = fuzzy
                )
            )
        }
    }

    private fun buildFuzzyString(county: String, city: String, state: String): String {
        val parts = mutableListOf<String>()
        if (county.isNotEmpty()) {
            val cleanCounty = if (county.contains("County", ignoreCase = true)) county else "$county Co."
            parts.add(cleanCounty)
        }
        if (city.isNotEmpty() && !city.equals(county, ignoreCase = true)) {
            parts.add(city)
        }
        if (state.isNotEmpty()) {
            parts.add(state)
        }
        // FAKE-DATA FIX: with nothing geocoded this returned the invented place name
        // "Local Station", which then rendered as the observatory's station. Empty = unknown,
        // and every caller already treats an empty fuzzyLocation as "no place yet".
        return if (parts.isNotEmpty()) parts.joinToString(", ") else ""
    }

    /**
     * Unified OnThe8s Dual Meteorological Engine:
     * Combines National Weather Service (NWS) direct station observations with Open-Meteo hourly meteogram models.
     */
    fun refreshWeather(ctx: Context) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true)
            var lat = _state.value.gps.latitude
            var lon = _state.value.gps.longitude

            // Resolve coordinates: live fix → persisted last-good fix → IP geolocation chain.
            // NEVER a hardcoded default city — skip the cycle instead of showing someone
            // else's forecast.
            if (lat == 0.0 && lon == 0.0) {
                if (restoreLastGoodFix(ctx)) {
                    lat = _state.value.gps.latitude
                    lon = _state.value.gps.longitude
                }
            }
            if (lat == 0.0 && lon == 0.0) {
                fetchIpGeolocation(ctx)
                lat = _state.value.gps.latitude
                lon = _state.value.gps.longitude
                if (lat == 0.0 && lon == 0.0) {
                    Log.w(TAG, "No location from any source — skipping weather cycle")
                    _state.value = _state.value.copy(isLoading = false)
                    return@launch
                }
            }

            try {
                // Step 1: Query Open-Meteo for high-res hourly meteogram + solar astro
                val omUrlStr = "https://api.open-meteo.com/v1/forecast?" +
                        "latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m,surface_pressure,cloud_cover" +
                        "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility,uv_index,is_day" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,sunrise,sunset" +
                        "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch&timezone=auto"

                val omConn = (URL(omUrlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                if (omConn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(omConn.inputStream))
                    val json = JSONObject(reader.readText())
                    reader.close()

                    val current = json.optJSONObject("current")
                    val daily = json.optJSONObject("daily")
                    val hourly = json.optJSONObject("hourly")

                    // Missing fields stay NaN / -1 (honest unknown) — never a made-up "typical" value.
                    var tempF = current?.optDouble("temperature_2m", Double.NaN)?.toFloat() ?: Float.NaN
                    var feelsLike = current?.optDouble("apparent_temperature", Double.NaN)?.toFloat() ?: Float.NaN
                    var humidity = current?.optInt("relative_humidity_2m", -1) ?: -1
                    var wCode = current?.optInt("weather_code", -1) ?: -1
                    var windSpeed = current?.optDouble("wind_speed_10m", Double.NaN)?.toFloat() ?: Float.NaN
                    var windGust = current?.optDouble("wind_gusts_10m", Double.NaN)?.toFloat() ?: Float.NaN
                    var windDir = current?.optInt("wind_direction_10m", -1) ?: -1
                    val precipIn = current?.optDouble("precipitation", Double.NaN)?.toFloat() ?: Float.NaN
                    val pressureHpa = current?.optDouble("surface_pressure", Double.NaN)?.toFloat() ?: Float.NaN
                    val pressureInHg = if (pressureHpa.isNaN()) Float.NaN else pressureHpa * 0.02953f
                    val cloudCover = current?.optInt("cloud_cover", -1) ?: -1
                    var isDay = (current?.optInt("is_day", 1) ?: 1) == 1

                    // Sunrise and Sunset: "—" until the daily astro arrays actually parse.
                    var sunriseStr = "—"
                    var sunsetStr = "—"
                    val srRaw = daily?.optJSONArray("sunrise")?.optString(0) ?: ""
                    val ssRaw = daily?.optJSONArray("sunset")?.optString(0) ?: ""
                    if (srRaw.isNotEmpty() && ssRaw.isNotEmpty()) {
                        val astroParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
                        val astroFmt = SimpleDateFormat("h:mm a", Locale.US)
                        try {
                            sunriseStr = astroFmt.format(astroParser.parse(srRaw) ?: Date())
                            sunsetStr = astroFmt.format(astroParser.parse(ssRaw) ?: Date())
                        } catch (_: Throwable) {}
                    }

                    // Solar fraction only from REAL sunrise/sunset; otherwise keep the API's is_day
                    // and leave the fraction at 0 (unknown) instead of a synthetic "midday".
                    val haveAstro = sunriseStr != "—" && sunsetStr != "—"
                    val solarFraction = if (haveAstro) {
                        val (frac, calculatedIsDay) = calculateSolarProgress(sunriseStr, sunsetStr)
                        isDay = calculatedIsDay
                        frac
                    } else 0f

                    val maxTemps = daily?.optJSONArray("temperature_2m_max")
                    val minTemps = daily?.optJSONArray("temperature_2m_min")
                    val highTemp = if (maxTemps != null && maxTemps.length() > 0) maxTemps.optDouble(0, Double.NaN).toFloat() else Float.NaN
                    val lowTemp = if (minTemps != null && minTemps.length() > 0) minTemps.optDouble(0, Double.NaN).toFloat() else Float.NaN

                    val (summary, icon) = mapWeatherCode(wCode, isDay)
                    val compass = degreesToCompass(windDir)

                    // Step 2: Try NWS Station lookup & Live Observation (Direct from onthe8s WeatherDataCache)
                    var nwsStation = cachedStationId ?: ""
                    var nwsCondition = ""
                    val sourcesList = mutableListOf("Open-Meteo")

                    try {
                        if (cachedStationId == null || cachedForecastUrl == null) {
                            val nwsPts = httpGetJson("$NWS_BASE/points/${String.format(Locale.US, "%.4f", lat)},${String.format(Locale.US, "%.4f", lon)}")
                            val props = nwsPts?.optJSONObject("properties")
                            cachedForecastUrl = props?.optString("forecast", "")?.ifEmpty { null }
                            cachedHourlyForecastUrl = props?.optString("forecastHourly", "")?.ifEmpty { null }
                            val obsStationsUrl = props?.optString("observationStations", "")?.ifEmpty { null }
                            if (obsStationsUrl != null) {
                                val stJson = httpGetJson(obsStationsUrl)
                                val features = stJson?.optJSONArray("features")
                                if (features != null && features.length() > 0) {
                                    val first = features.getJSONObject(0).optJSONObject("properties")
                                    cachedStationId = first?.optString("stationIdentifier", "")
                                    nwsStation = cachedStationId ?: ""
                                }
                            }
                        }

                        if (nwsStation.isNotEmpty()) {
                            val obsJson = httpGetJson("$NWS_BASE/stations/$nwsStation/observations/latest")
                            val obsProps = obsJson?.optJSONObject("properties")
                            if (obsProps != null) {
                                val nwsTempC = obsProps.optJSONObject("temperature")?.optDouble("value", Double.NaN) ?: Double.NaN
                                if (!nwsTempC.isNaN()) {
                                    tempF = (nwsTempC * 9.0 / 5.0 + 32.0).toFloat()
                                    sourcesList.add(0, "NWS ($nwsStation)")
                                }
                                val nwsRh = obsProps.optJSONObject("relativeHumidity")?.optDouble("value", Double.NaN) ?: Double.NaN
                                if (!nwsRh.isNaN()) {
                                    humidity = nwsRh.roundToInt()
                                }
                                val nwsWs = obsProps.optJSONObject("windSpeed")?.optDouble("value", Double.NaN) ?: Double.NaN
                                if (!nwsWs.isNaN()) {
                                    windSpeed = (nwsWs * 0.621371).toFloat()
                                }
                                val nwsWd = obsProps.optJSONObject("windDirection")?.optDouble("value", Double.NaN) ?: Double.NaN
                                if (!nwsWd.isNaN()) {
                                    windDir = nwsWd.roundToInt()
                                }
                                nwsCondition = obsProps.optString("textDescription", "")
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "NWS observation fetch skipped: ${e.message}")
                    }

                    val finalSummary = if (nwsCondition.isNotEmpty()) nwsCondition else summary

                    // Step 3: Hourly forecast look-ahead & Meteogram indexing starting from NOW
                    val hourlyTimes = hourly?.optJSONArray("time")
                    val hourlyPrecip = hourly?.optJSONArray("precipitation")
                    val hourlyPrecipProbs = hourly?.optJSONArray("precipitation_probability")
                    val hourlyCodes = hourly?.optJSONArray("weather_code")
                    val hourlyTemps = hourly?.optJSONArray("temperature_2m")
                    val hourlyFeels = hourly?.optJSONArray("apparent_temperature")
                    val hourlyDewPoints = hourly?.optJSONArray("dew_point_2m")
                    val hourlyWindSpeed = hourly?.optJSONArray("wind_speed_10m")
                    val hourlyWindGusts = hourly?.optJSONArray("wind_gusts_10m")
                    val hourlyWindDir = hourly?.optJSONArray("wind_direction_10m")
                    val hourlyVis = hourly?.optJSONArray("visibility")
                    val hourlyUv = hourly?.optJSONArray("uv_index")
                    val hourlyIsDay = hourly?.optJSONArray("is_day")

                    val precipList6h = mutableListOf<HourlyPrecipPoint>()
                    val meteogramList = mutableListOf<HourlyMeteogramPoint>()
                    val sparklineTemps = mutableListOf<Int>()
                    val totalHourlyPoints = hourlyTimes?.length() ?: 0

                    val inSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
                    val dayHeaderSdf = SimpleDateFormat("EEEE d", Locale.US)
                    val hourSdf = SimpleDateFormat("HH:mm", Locale.US)

                    val nowMs = System.currentTimeMillis()
                    var startIdx = 0

                    // Locate index of current hour so we don't start from midnight in the past
                    for (i in 0 until totalHourlyPoints) {
                        val rawTime = hourlyTimes?.optString(i, "") ?: ""
                        try {
                            val parsedDate = inSdf.parse(rawTime)
                            if (parsedDate != null && parsedDate.time >= nowMs - 3600000L) {
                                startIdx = i
                                break
                            }
                        } catch (_: Throwable) {}
                    }

                    // Look-ahead precipitation classifier from onthe8s
                    var nextPrecip = ""
                    // Unknown until the hourly arrays supply the current-hour value (no "typical" fill-ins).
                    var dewPointVal = Float.NaN
                    var visibilityVal = Float.NaN
                    var uvVal = Float.NaN

                    for (i in startIdx until minOf(totalHourlyPoints, startIdx + 24)) {
                        val rawTime = hourlyTimes?.optString(i, "") ?: ""
                        var hourDisplay = "+${i - startIdx}h"
                        var dayHeaderDisplay = "Today"
                        var hourTimestamp = nowMs + (i - startIdx) * 3600000L
                        try {
                            val parsedDate = inSdf.parse(rawTime)
                            if (parsedDate != null) {
                                hourDisplay = hourSdf.format(parsedDate)
                                dayHeaderDisplay = dayHeaderSdf.format(parsedDate)
                                hourTimestamp = parsedDate.time
                            }
                        } catch (_: Throwable) {
                            if (rawTime.contains("T")) {
                                hourDisplay = rawTime.substringAfter("T").take(5)
                            }
                        }

                        // An hour with no temperature is not a data point — skip it rather than invent one.
                        val tF = hourlyTemps?.optDouble(i, Double.NaN)?.toFloat() ?: Float.NaN
                        if (tF.isNaN()) continue
                        val pIn = hourlyPrecip?.optDouble(i, 0.0)?.toFloat() ?: 0f
                        val prob = hourlyPrecipProbs?.optInt(i, 0) ?: 0
                        val code = hourlyCodes?.optInt(i, 0) ?: 0
                        val appTF = hourlyFeels?.optDouble(i, tF.toDouble())?.toFloat() ?: tF
                        val wSpd = hourlyWindSpeed?.optDouble(i, 0.0)?.toFloat() ?: 0f
                        val wGst = hourlyWindGusts?.optDouble(i, wSpd.toDouble())?.toFloat() ?: wSpd
                        val wDegree = hourlyWindDir?.optInt(i, 0) ?: 0
                        val ptIsDay = (hourlyIsDay?.optInt(i, 1) ?: 1) == 1
                        val (s, ic) = mapWeatherCode(code, ptIsDay)

                        if (i == startIdx) {
                            dewPointVal = hourlyDewPoints?.optDouble(i, Double.NaN)?.toFloat() ?: Float.NaN
                            val vM = hourlyVis?.optDouble(i, Double.NaN) ?: Double.NaN
                            visibilityVal = if (vM.isNaN()) Float.NaN else (vM / 1609.34).toFloat()
                            uvVal = hourlyUv?.optDouble(i, Double.NaN)?.toFloat() ?: Float.NaN
                            // Current gust: the API's current block when present, else this hour's forecast gust.
                            if (windGust.isNaN() && hourlyWindGusts != null) windGust = hourlyWindGusts.optDouble(i, Double.NaN).toFloat()
                        }

                        // Collect 8-point temperature sparkline
                        if (sparklineTemps.size < 8) {
                            sparklineTemps.add(tF.roundToInt())
                        }

                        // Look-ahead precipitation classifier logic
                        if (nextPrecip.isEmpty() && (prob >= 40 || pIn > 0.02f)) {
                            val hoursAway = ((hourTimestamp - nowMs) / 3600000L).toInt().coerceAtLeast(0)
                            val type = classifyPrecip(s)
                            nextPrecip = when (hoursAway) {
                                0 -> "$type NOW"
                                1 -> "$type IN 1 HR"
                                else -> "$type IN $hoursAway HRS"
                            }
                        }

                        if (precipList6h.size < 6) {
                            precipList6h.add(
                                HourlyPrecipPoint(
                                    timeLabel = if (precipList6h.isEmpty()) "NOW" else hourDisplay,
                                    precipInches = pIn,
                                    precipProbPct = prob,
                                    weatherCode = code,
                                    tempF = tF,
                                    summary = s,
                                    icon = ic
                                )
                            )
                        }

                        meteogramList.add(
                            HourlyMeteogramPoint(
                                timeLabel = hourDisplay,
                                dayLabel = dayHeaderDisplay,
                                tempF = tF,
                                feelsLikeF = appTF,
                                precipInches = pIn,
                                precipProbPct = prob,
                                windSpeedMph = wSpd,
                                windGustsMph = wGst,
                                windDirectionDeg = wDegree,
                                windDirectionCompass = degreesToCompass(wDegree),
                                weatherCode = code,
                                summary = s,
                                icon = ic,
                                isDay = ptIsDay
                            )
                        )
                    }

                    // Step 4: Daily Multi-Day Forecast parsing
                    val dailyList = mutableListOf<DailyForecastPoint>()
                    val dailyTimes = daily?.optJSONArray("time")
                    val dailyCodes = daily?.optJSONArray("weather_code")
                    val dailyMaxTemps = daily?.optJSONArray("temperature_2m_max")
                    val dailyMinTemps = daily?.optJSONArray("temperature_2m_min")
                    val dailyPrecipSums = daily?.optJSONArray("precipitation_sum")
                    val dailyPrecipProbMax = daily?.optJSONArray("precipitation_probability_max")
                    val dailyMaxWind = daily?.optJSONArray("wind_speed_10m_max")
                    val totalDailyDays = dailyTimes?.length() ?: 0

                    val dateParseSdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val dayNameSdf = SimpleDateFormat("EEEE", Locale.US)
                    val shortDateSdf = SimpleDateFormat("MM/dd", Locale.US)

                    var tomorrowDName = "Tomorrow"
                    var tomorrowHiVal = Float.NaN
                    var tomorrowLoVal = Float.NaN
                    var tomorrowSummary = "—"

                    for (d in 0 until totalDailyDays) {
                        val dStr = dailyTimes?.optString(d, "") ?: ""
                        var dName = "Day $d"
                        var dFormatted = "—"
                        try {
                            val parsedDate = dateParseSdf.parse(dStr)
                            if (parsedDate != null) {
                                dName = if (d == 0) "Today" else dayNameSdf.format(parsedDate)
                                dFormatted = shortDateSdf.format(parsedDate)
                            }
                        } catch (_: Throwable) {}

                        val dCode = dailyCodes?.optInt(d, 0) ?: 0
                        val dMax = dailyMaxTemps?.optDouble(d, Double.NaN)?.toFloat() ?: Float.NaN
                        val dMin = dailyMinTemps?.optDouble(d, Double.NaN)?.toFloat() ?: Float.NaN
                        if (dMax.isNaN() && dMin.isNaN()) continue   // no real numbers for this day
                        val dPrecipSum = dailyPrecipSums?.optDouble(d, 0.0)?.toFloat() ?: 0f
                        val dProb = dailyPrecipProbMax?.optInt(d, 0) ?: 0
                        val dWind = dailyMaxWind?.optDouble(d, 0.0)?.toFloat() ?: 0f
                        val (s, ic) = mapWeatherCode(dCode, true)

                        if (d == 1) {
                            tomorrowDName = dName
                            tomorrowHiVal = dMax
                            tomorrowLoVal = dMin
                            tomorrowSummary = s
                        }

                        dailyList.add(
                            DailyForecastPoint(
                                dayName = dName,
                                dateFormatted = dFormatted,
                                maxTempF = dMax,
                                minTempF = dMin,
                                precipSumIn = dPrecipSum,
                                precipProbMax = dProb,
                                maxWindSpeedMph = dWind,
                                weatherCode = dCode,
                                summary = s,
                                icon = ic
                            )
                        )
                    }

                    // Severe Weather Alerts Check
                    val severeAlert = checkSevereAlerts(wCode, windSpeed, precipIn)

                    val condition = WeatherCondition(
                        code = wCode,
                        summary = finalSummary,
                        icon = icon,
                        tempF = tempF,
                        feelsLikeF = feelsLike,
                        humidityPct = humidity,
                        windSpeedMph = windSpeed,
                        windGustMph = windGust,   // real (current or current-hour forecast), never speed × factor
                        windDirectionDeg = windDir,
                        windDirectionCompass = compass,
                        precipitationIn = precipIn,
                        precipitationProbPct = if (precipList6h.isNotEmpty()) precipList6h[0].precipProbPct else 0,
                        highTempF = highTemp,
                        lowTempF = lowTemp,
                        dewPointF = dewPointVal,
                        pressureInHg = pressureInHg,
                        cloudCoverPct = cloudCover,
                        visibilityMiles = visibilityVal,
                        uvIndex = uvVal,
                        // Air quality is not part of this fetch — leave it "not available".
                        aqi = -1,
                        aqiCategory = "",
                        sunrise = sunriseStr,
                        sunset = sunsetStr,
                        solarFraction = solarFraction,
                        isDay = isDay,
                        nextPrecipLabel = nextPrecip,
                        todayCond = summary,
                        tomorrowDay = tomorrowDName,
                        tomorrowHi = tomorrowHiVal,
                        tomorrowLo = tomorrowLoVal,
                        tomorrowCond = tomorrowSummary,
                        sourcesUsed = sourcesList.joinToString(" + "),
                        hourlySparklineTemps = sparklineTemps,
                        nwsStationId = nwsStation,
                        severeWarning = severeAlert,
                        lastUpdatedTime = System.currentTimeMillis(),
                        hourlyPrecip6h = precipList6h,
                        hourlyMeteogram = meteogramList,
                        dailyForecast = dailyList
                    )

                    _state.value = _state.value.copy(
                        weather = condition,
                        isLoading = false,
                        error = null
                    )
                } else {
                    _state.value = _state.value.copy(isLoading = false, error = "HTTP ${omConn.responseCode}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Weather fetch error: ${t.message}")
                _state.value = _state.value.copy(isLoading = false, error = t.message)
            }
        }
    }

    private fun httpGetJson(urlStr: String): JSONObject? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "HioneMeteorology/1.0 (contact@falcontechnix.com)")
                setRequestProperty("Accept", "application/geo+json, application/json")
            }
            if (conn.responseCode == 200) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                JSONObject(body)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun calculateSolarProgress(sunriseStr: String, sunsetStr: String): Pair<Float, Boolean> {
        try {
            val sdf = SimpleDateFormat("h:mm a", Locale.US)
            val sr = sdf.parse(sunriseStr) ?: return 0.5f to true
            val ss = sdf.parse(sunsetStr) ?: return 0.5f to true
            val cal = Calendar.getInstance()
            val currentMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val calSr = Calendar.getInstance().apply { time = sr }
            val srMin = calSr.get(Calendar.HOUR_OF_DAY) * 60 + calSr.get(Calendar.MINUTE)

            val calSs = Calendar.getInstance().apply { time = ss }
            val ssMin = calSs.get(Calendar.HOUR_OF_DAY) * 60 + calSs.get(Calendar.MINUTE)

            val isDay = currentMin in srMin..ssMin
            val totalDayMin = (ssMin - srMin).coerceAtLeast(60)
            val frac = if (isDay) {
                ((currentMin - srMin).toFloat() / totalDayMin).coerceIn(0f, 1f)
            } else {
                val nightDuration = 1440 - totalDayMin
                val nightElapsed = if (currentMin > ssMin) currentMin - ssMin else (1440 - ssMin + currentMin)
                (nightElapsed.toFloat() / nightDuration).coerceIn(0f, 1f)
            }
            return frac to isDay
        } catch (_: Throwable) {
            return 0.5f to true
        }
    }

    private fun classifyPrecip(forecast: String): String {
        val f = forecast.lowercase(Locale.US)
        return when {
            f.contains("thunder") -> "TSTORM"
            f.contains("wintry mix") -> "WINTRY MIX"
            f.contains("snow") && f.contains("rain") -> "WINTRY MIX"
            f.contains("freezing rain") -> "FREEZE RAIN"
            f.contains("freezing") -> "FREEZE RAIN"
            f.contains("sleet") -> "SLEET"
            f.contains("snow") -> "SNOW"
            f.contains("hail") -> "HAIL"
            f.contains("shower") -> "SHOWERS"
            f.contains("drizzle") -> "DRIZZLE"
            f.contains("rain") -> "RAIN"
            else -> "PRECIP"
        }
    }

    fun mapWeatherCode(code: Int, isDay: Boolean, timestamp: Long = System.currentTimeMillis()): Pair<String, String> {
        val moonIcon = calculateMoonPhase(timestamp).phaseIcon
        return when (code) {
            0 -> "Clear Sky" to if (isDay) "☀️" else moonIcon
            1 -> "Mainly Clear" to if (isDay) "🌤️" else moonIcon
            2 -> "Partly Cloudy" to if (isDay) "⛅" else "☁️"
            3 -> "Overcast" to "☁️"
            45, 48 -> "Fog & Haze" to "🌫️"
            51, 53, 55 -> "Drizzle" to "🌦️"
            56, 57 -> "Freezing Drizzle" to "🌧️"
            61, 63, 65 -> "Rain" to "🌧️"                 // WMO 61-65 = steady rain (not showers)
            66, 67 -> "Freezing Rain" to "🌧️"            // liquid rain that freezes — not a snow icon
            71, 73, 75 -> "Snowfall" to "❄️"
            77 -> "Snow Grains" to "❄️"
            80, 81, 82 -> "Rain Showers" to "🌦️"          // WMO 80-82 = rain showers, NOT thunderstorms (⛈️ was wrong)
            85, 86 -> "Snow Showers" to "🌨️"
            95 -> "Thunderstorm" to "⛈️"                  // thunder lives here (95-99), where ⛈️ belongs
            96, 99 -> "Thunderstorm & Hail" to "⛈️⚡"
            else -> "Fair" to if (isDay) "☀️" else moonIcon
        }
    }

    private fun degreesToCompass(deg: Int): String {
        val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val idx = (((deg % 360) / 22.5) + 0.5).toInt() % 16
        return directions[idx]
    }

    private fun checkSevereAlerts(code: Int, windSpeed: Float, precipIn: Float): String? {
        return when {
            code in listOf(95, 96, 99) -> "SEVERE THUNDERSTORM"
            windSpeed >= 40f -> "HIGH WIND (40+ MPH)"
            precipIn >= 0.75f -> "FLASH FLOOD RISK (HEAVY RAIN)"
            code in listOf(85, 86) -> "HEAVY SNOW SHOWERS"
            else -> null
        }
    }
}

/** Honest rounding for weather readings: NaN (not fetched / not reported) renders as "—", never as a number. */
internal fun Float.wxInt(suffix: String = ""): String = if (isNaN()) "—" else "${roundToInt()}$suffix"
