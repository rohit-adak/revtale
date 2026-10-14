package io.github.revtale

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Weather at one spot right now. Null fields = the source didn't report them. */
data class WeatherNow(
    val tempC: Double?, val humidity: Double?, val windMs: Double?,
    /** mm per hour; 0 = dry. */
    val rainMmH: Double?,
    /** "Weather Union" (a station within ~2 km) or "Open-Meteo" (model, used where Weather Union has no station). */
    val source: String
) {
    val raining get() = (rainMmH ?: 0.0) > 0.1

    /** "Raining hard", "Light rain", "Dry" — the words for the phone. */
    val condition get() = when {
        (rainMmH ?: 0.0) >= 7.6 -> "Heavy rain"
        (rainMmH ?: 0.0) >= 2.5 -> "Rain"
        raining -> "Light rain"
        (windMs ?: 0.0) >= 10 -> "Windy"
        else -> "Dry"
    }

    /** One line for the cluster (17 drawable characters, no '.', '%' or '-'): "DEST 27C HVY RAIN". */
    fun clusterLine(prefix: String): String {
        val t = tempC?.let { "${it.roundToInt()}C" }
        val c = when {
            (rainMmH ?: 0.0) >= 7.6 -> "HVY RAIN"
            raining -> "RAIN"
            (windMs ?: 0.0) >= 10 -> "WINDY"
            else -> "DRY"
        }
        return Protocol.clusterText(listOfNotNull(prefix, t, c).joinToString(" ")).take(17)
    }

    /** "27°C · Light rain · 80% humidity" for the phone. */
    fun phoneText(): String = listOfNotNull(tempC?.let { "${it.roundToInt()}°C" }, condition,
        humidity?.let { "${it.roundToInt()}% humidity" }, windMs?.takeIf { it >= 5 }?.let { "wind ${(it * 3.6).roundToInt()} km/h" })
        .joinToString(" · ")
}

/**
 * Weather from Weather Union (Zomato's free network of street-level stations in Indian cities; the rider's own API
 * key from weatherunion.com, saved in Settings). Where it has no station near the spot, Open-Meteo (free, no key)
 * fills in so the cluster still gets an answer. Parsing is pure Kotlin and unit-tested; [fetch] runs off the main thread.
 */
object Weather {
    private const val WU = "https://www.weatherunion.com/gw/weather/external/v0/get_weather_data"

    /** Weather Union's reply; null when it has no data for that spot (no station in range) or failed. */
    fun parseWeatherUnion(json: String): WeatherNow? = runCatching {
        val j = org.json.JSONObject(json)
        val d = j.optJSONObject("locality_weather_data") ?: return null
        fun num(k: String) = if (d.isNull(k)) null else d.optDouble(k).takeIf { it.isFinite() }
        val w = WeatherNow(num("temperature"), num("humidity"), num("wind_speed"), num("rain_intensity"), "Weather Union")
        w.takeIf { it.tempC != null || it.rainMmH != null }
    }.getOrNull()

    fun parseOpenMeteo(json: String): WeatherNow? = runCatching {
        val c = org.json.JSONObject(json).optJSONObject("current") ?: return null
        fun num(k: String) = if (!c.has(k) || c.isNull(k)) null else c.optDouble(k).takeIf { it.isFinite() }
        // Open-Meteo gives the last hour's rain in mm, i.e. mm/h
        WeatherNow(num("temperature_2m"), num("relative_humidity_2m"), num("wind_speed_10m"), num("precipitation"), "Open-Meteo")
    }.getOrNull()

    private fun get(url: String, key: String? = null): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000
            key?.let { setRequestProperty("x-zomato-api-key", it.trim()) }
        }
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            return code to body
        } finally { c.disconnect() }
    }

    /** Throws with a readable message when the key is refused; null when nothing could be found. Blocking. */
    fun fetch(key: String?, lat: Double, lon: Double): WeatherNow? {
        val at = "latitude=%.5f&longitude=%.5f".format(java.util.Locale.US, lat, lon)
        if (!key.isNullOrBlank()) {
            val (code, body) = runCatching { get("$WU?$at", key) }.getOrElse { 0 to "" }
            if (code == 401 || code == 403) error("Weather Union refused the API key")
            if (code == 200) parseWeatherUnion(body)?.let { return it }
        }
        val om = "https://api.open-meteo.com/v1/forecast?$at&current=temperature_2m,relative_humidity_2m,precipitation,wind_speed_10m&wind_speed_unit=ms"
        return runCatching { get(om) }.getOrNull()?.takeIf { it.first == 200 }?.let { parseOpenMeteo(it.second) }
    }

    fun key(ctx: android.content.Context): String? = BleService.prefs(ctx).getString("weatherKey", null)?.takeIf { it.isNotBlank() }
}
