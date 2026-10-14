package io.github.revtale

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Before you start: how far it is, how long it takes on an empty road (OSRM, free, no key), and how long it
 * usually takes YOU (your own past rides between the same two places). Live traffic is Google Maps' job: its ETA
 * arrives with the first turn notification and goes on the cluster.
 */
data class RoutePreview(
    val km: Double?, val freeFlowMin: Int?,
    /** Average of your recorded rides from here to there; null when you've never ridden it. */
    val usualMin: Int?, val usualRides: Int
)

object RouteInfo {
    /** OSRM's reply → (km, minutes). Pure, unit-tested. */
    fun parseOsrm(json: String): Pair<Double, Int>? = runCatching {
        val j = org.json.JSONObject(json)
        if (j.optString("code") != "Ok") return null
        val r = j.getJSONArray("routes").getJSONObject(0)
        (r.getDouble("distance") / 1000.0) to (r.getDouble("duration") / 60.0).roundToInt()
    }.getOrNull()

    /** Road distance and empty-road time (blocking, off the main thread); null offline. */
    fun fetch(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Pair<Double, Int>? = runCatching {
        val url = "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false"
            .format(java.util.Locale.US, fromLon, fromLat, toLon, toLat)
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 6000 }
        try { if (c.responseCode == 200) parseOsrm(c.inputStream.bufferedReader().readText()) else null } finally { c.disconnect() }
    }.getOrNull()

    private fun near(aLat: Double, aLon: Double, bLat: Double, bLon: Double, m: Double): Boolean {
        val dy = (aLat - bLat) * 111_320.0
        val dx = (aLon - bLon) * 111_320.0 * cos(Math.toRadians(aLat))
        return dx * dx + dy * dy <= m * m
    }

    /** Your usual time for a trip from (fromLat, fromLon) to (toLat, toLon), from the overview's usual routes. Pure, unit-tested. */
    fun usual(routes: List<UsualRoute>, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): UsualRoute? =
        routes.filter { near(it.to.lat, it.to.lon, toLat, toLon, 400.0) && near(it.from.lat, it.from.lon, fromLat, fromLon, 400.0) }
            .maxByOrNull { it.count }

    /** "TRAFFIC 8MIN LATE" when Google's live ETA is clearly slower than your usual time, else null (the cluster has no '+'). */
    fun trafficLine(googleMin: Int, usualMin: Int): String? {
        val extra = googleMin - usualMin
        return if (extra >= 4) Protocol.clusterText("TRAFFIC ${extra}MIN LATE").take(17) else null
    }
}
